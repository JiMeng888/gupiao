package com.stocklab.analyst.modules.auth.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.modules.auth.dto.RegisterRequest;
import com.stocklab.analyst.modules.auth.dto.TokenPair;
import com.stocklab.analyst.modules.auth.dto.UserProfile;
import com.stocklab.analyst.modules.auth.entity.User;
import com.stocklab.analyst.modules.auth.mapper.UserMapper;
import com.stocklab.analyst.security.JwtTokenService;
import com.stocklab.analyst.security.TokenRevocationStore;
import com.stocklab.analyst.security.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 鉴权服务：注册 / 登录 / 刷新 / 登出 / 改密。
 *
 * <p><b>关于事务</b>：本类的每个方法都只包含<b>一条</b>数据库写语句。
 * 单条语句在数据库层面本身就是原子的，加 {@code @Transactional} 只会引入
 * 额外的连接持有与提交开销，没有任何一致性收益。
 * 真正需要显式事务的是「多条写语句要么全成功要么全失败」的场景，
 * 例如 {@code HoldingService} 里「先查持仓再更新成本」的序列。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final TokenRevocationStore revocationStore;

    /**
     * 用于「用户不存在」分支的占位哈希。
     *
     * <p>如果用户不存在就直接返回，响应时间会明显短于「用户存在但密码错误」
     * （后者要跑一次 bcrypt，约 50~100ms）。攻击者据此可以枚举出
     * 哪些手机号已注册。这里对不存在的用户也做一次同等成本的哈希比较，
     * 把两条分支的耗时拉平。
     */
    private final String dummyHash;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService tokenService,
                       TokenRevocationStore revocationStore) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.revocationStore = revocationStore;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-placeholder");
    }

    /**
     * 注册并直接签发令牌（免去注册后再登录一次）。
     *
     * <p>手机号唯一性有两道防线：这里的前置查询用于给出友好提示，
     * 数据库唯一索引才是真正的保证。并发注册同一手机号时，
     * 后到的请求会触发 {@link DuplicateKeyException}，同样翻译为
     * {@code PHONE_EXISTS}——因此用户看到的提示是一致的，
     * 不会出现「偶发 500」。
     */
    public TokenPair register(RegisterRequest request) {
        if (userMapper.countByPhone(request.phone()) > 0) {
            throw new BizException(ErrorCode.PHONE_EXISTS);
        }

        User user = new User();
        user.setPhone(request.phone());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(resolveNickname(request));

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.PHONE_EXISTS);
        }
        log.info("新用户注册成功 userId={} phone={}", user.getId(), maskPhone(user.getPhone()));
        // 每次登录 / 注册都开一个新会话（新 sid），互不影响：
        // 在手机上登出不会把电脑上的登录一起踢掉
        return issueTokens(user.getId(), tokenService.newSessionId());
    }

    /**
     * 登录。
     *
     * <p>「用户不存在」与「密码错误」返回<b>完全相同</b>的错误码与文案，
     * 避免攻击者据此判断手机号是否已注册。
     */
    public TokenPair login(String phone, String rawPassword) {
        User user = userMapper.selectByPhone(phone);

        if (user == null) {
            // 走一次等价的哈希比较，抹平响应时间差异（详见 dummyHash 注释）
            passwordEncoder.matches(rawPassword, dummyHash);
            log.info("登录失败（用户不存在）phone={}", maskPhone(phone));
            throw new BizException(ErrorCode.AUTH_INVALID, "手机号或密码错误");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.info("登录失败（密码错误）phone={}", maskPhone(phone));
            throw new BizException(ErrorCode.AUTH_INVALID, "手机号或密码错误");
        }
        return issueTokens(user.getId(), tokenService.newSessionId());
    }

    /**
     * 用 Refresh Token 换新令牌对。
     *
     * <p>两道校验缺一不可：
     * <ol>
     *   <li><b>会话级</b>——该 sid 是否已被登出吊销。这是登出能真正生效的关键：
     *       少了这一条，用户点了退出登录后手里的 Refresh Token 仍能换出新令牌对。</li>
     *   <li><b>令牌级</b>——该 jti 是否已被用过。令牌轮转后旧刷新令牌立即作废，
     *       把被盗令牌的可用窗口压缩到「攻击者与真实用户谁先用」。</li>
     * </ol>
     *
     * <p>每次签发<b>新的</b> refresh token，但<b>沿用同一个 sid</b>：
     * 轮转改变的是令牌，不是会话。会话 ID 若跟着变，登出时拉黑的 sid
     * 就会与客户端手里的新令牌对不上，登出又变成只失效一半。
     */
    public TokenPair refresh(String refreshToken) {
        JwtTokenService.ParsedToken parsed = tokenService.parse(refreshToken, TokenType.REFRESH);
        if (revocationStore.isSessionRevoked(parsed.sessionId())) {
            throw new BizException(ErrorCode.AUTH_INVALID, "登录状态已失效，请重新登录");
        }
        if (revocationStore.isRevoked(parsed.tokenId())) {
            throw new BizException(ErrorCode.AUTH_INVALID, "令牌已注销，请重新登录");
        }
        // 轮转：旧令牌立即失效，缩短被盗令牌的可用窗口。
        // 用令牌自身的过期时间做黑名单存活期，而不是配置里的完整 TTL
        revokeQuietly(parsed);
        // 旧令牌不带 sid（sid 机制上线前签发）时补发一个新的，
        // 让这类会话在下次刷新后也能被正常登出吊销
        String sessionId = parsed.sessionId() != null && !parsed.sessionId().isBlank()
                ? parsed.sessionId()
                : tokenService.newSessionId();
        return issueTokens(parsed.userId(), sessionId);
    }

    /**
     * 登出：吊销整个会话。
     *
     * <p>幂等设计——令牌缺失、已损坏、已过期都返回成功。
     * 理由是登出的语义是「确保这次登录不再可用」，
     * 这些情况下它本来就不可用，目标已达成；
     * 反复调用也不应产生副作用。
     *
     * <p>为什么同时接受 {@code sessionId} 与 {@code refreshToken}：
     * <ul>
     *   <li>{@code sessionId} 来自 Access Token（过滤器解析后注入），
     *       只要调用方带了 Authorization 头就能拿到。它让登出
     *       连带作废那张尚未过期的 Access Token，并<b>不依赖客户端</b>
     *       是否记得把 Refresh Token 递上来。</li>
     *   <li>{@code refreshToken} 不只是「额外拉黑一个 jti」。它<b>自带 sid</b>，
     *       所以当 {@code sessionId} 缺失（Access Token 已过期，过滤器取不到）时，
     *       它是唯一还能完成<b>会话级</b>吊销的线索；同时它的过期时间
     *       被用来精确设定黑名单存活期，避免条目提前过期导致令牌「复活」。</li>
     * </ul>
     * 两条线索任给其一都能真正登出——这是修复的核心：
     * 原实现只认 refreshToken，客户端不传就退化成「返回成功但什么都没做」。
     * 反过来，只给 refreshToken 时若不用它自带的 sid，
     * 会话级吊销会被整个跳过，尚未过期的 Access Token 仍能用到自然过期——
     * 那只是把「假登出」换了个更隐蔽的形态。
     *
     * @param refreshToken 客户端携带的刷新令牌，可为 null
     * @param sessionId    当前 Access Token 所属会话，可为 null
     */
    public void logout(String refreshToken, String sessionId) {
        JwtTokenService.ParsedToken parsed = parseQuietly(refreshToken);

        // 会话吊销条目的存活时间需要覆盖「该会话中活得最久的令牌」，
        // 否则黑名单条目先过期，Refresh Token 会在登出后不久「复活」。
        // 优先用刷新令牌自己的过期时间；拿不到时退回 now + refreshTtl 作为上界。
        Instant sessionExpiry = parsed != null && parsed.expiresAt() != null
                ? parsed.expiresAt()
                : Instant.now().plus(tokenService.refreshTokenTtl());

        // 会话 ID 有两个来源，必须都认，否则会退化成「只黑掉一张令牌」：
        //   1. 参数 sessionId —— 由过滤器从 Access Token 的 sid 声明取出；
        //   2. parsed.sessionId() —— 刷新令牌同样带 sid，标识的是同一次登录。
        //
        // 只认第 1 个是不够的。Access Token 过期时过滤器取不到 sid，
        // 此时客户端只递 refreshToken（AuthController 注释里明确支持这种调用），
        // 会话级吊销就会被整个跳过：refresh 用不了了，但那张尚未过期的
        // Access Token 会继续可用到自然过期（最多 30 分钟）——
        // 与「该会话的 access 与 refresh 令牌全部立即失效」的承诺不符。
        String effectiveSessionId = sessionId != null && !sessionId.isBlank()
                ? sessionId
                : (parsed == null ? null : parsed.sessionId());

        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            revocationStore.revokeSession(effectiveSessionId, sessionExpiry);
        }
        if (parsed != null) {
            revocationStore.revoke(parsed.tokenId(), sessionExpiry);
        }
        if ((sessionId == null || sessionId.isBlank()) && parsed == null) {
            // 两者都没有：本次登出实际上什么都没吊销（例如请求既无 Authorization 头，
            // 也没带请求体）。返回成功是为了幂等，但必须留下痕迹，
            // 否则又会退化成「看起来成功」的假登出
            log.warn("登出请求未携带会话标识与刷新令牌，未吊销任何令牌");
        }
    }

    public UserProfile currentUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 令牌有效但用户已被删除：按未认证处理，让前端回到登录页
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return new UserProfile(user.getId(), user.getPhone(), user.getNickname());
    }

    /**
     * 修改密码。
     *
     * <p>改密后<b>不</b>主动吊销已签发的 Access Token（无状态令牌无法枚举）。
     * 实际影响有限：Access Token 最多 30 分钟自然过期。
     * 若业务要求「改密即踢下线」，需要引入「令牌版本号」机制——
     * 在用户表加 {@code token_version}，签发时写入、校验时比对，
     * 改密即递增。当前未实现，属于已知边界。
     */
    public void changePassword(String userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            // 用 AUTH_INVALID 而不是 400：这是凭证校验失败，不是参数格式问题
            throw new BizException(ErrorCode.AUTH_INVALID, "原密码错误");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.INVALID_PARAMS, "新密码不能与原密码相同");
        }

        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        // 只更新密码字段，不用 updateById(update) 整体更新：
        // 后者会把未设置的字段写成 null，把手机号、昵称一并清空
        userMapper.updateById(update);
        log.info("用户修改密码 userId={}", userId);
    }

    // ------------------------------------------------------------------

    private TokenPair issueTokens(String userId, String sessionId) {
        return TokenPair.of(
                tokenService.createAccessToken(userId, sessionId),
                tokenService.createRefreshToken(userId, sessionId),
                tokenService.accessTokenTtlSeconds());
    }

    /** 解析失败返回 null（不抛异常），用于保证登出接口幂等。 */
    private JwtTokenService.ParsedToken parseQuietly(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        try {
            return tokenService.parse(refreshToken, TokenType.REFRESH);
        } catch (BizException ex) {
            log.debug("登出时令牌不可解析，忽略：{}", ex.getCode());
            return null;
        }
    }

    /** 尽力拉黑一个已解析的刷新令牌。 */
    private void revokeQuietly(JwtTokenService.ParsedToken parsed) {
        if (parsed == null || parsed.tokenId() == null || parsed.expiresAt() == null) {
            return;
        }
        revocationStore.revoke(parsed.tokenId(), parsed.expiresAt());
    }

    private String resolveNickname(RegisterRequest request) {
        if (request.nickname() != null && !request.nickname().isBlank()) {
            return request.nickname().trim();
        }
        // 默认昵称用手机号中间四位打码，避免把完整手机号当成昵称展示给他人
        String phone = request.phone();
        return "用户" + phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 日志脱敏：手机号只保留前 3 后 2 位。 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }
}
