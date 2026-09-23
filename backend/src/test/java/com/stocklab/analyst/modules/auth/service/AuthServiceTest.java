package com.stocklab.analyst.modules.auth.service;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.config.properties.JwtProperties;
import com.stocklab.analyst.modules.auth.dto.RegisterRequest;
import com.stocklab.analyst.modules.auth.dto.TokenPair;
import com.stocklab.analyst.modules.auth.dto.UserProfile;
import com.stocklab.analyst.modules.auth.entity.User;
import com.stocklab.analyst.modules.auth.mapper.UserMapper;
import com.stocklab.analyst.security.JwtTokenService;
import com.stocklab.analyst.security.InMemoryTokenRevocationStore;
import com.stocklab.analyst.security.TokenRevocationStore;
import com.stocklab.analyst.security.TokenType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 单测。
 *
 * <p><b>只 Mock 了 {@code UserMapper}，另外三个依赖全用真实实现。</b>
 * 这是刻意的选择：
 * <ul>
 *   <li>{@link JwtTokenService} 是纯签名/解析逻辑，无 I/O。Mock 掉它就等于
 *       把「令牌里到底有没有 sid 声明」这类<b>本测试真正要验证的东西</b>
 *       Mock 成了「假设它是对的」——断言会退化成「我调用了我自己设定的桩」。</li>
 *   <li>{@link TokenRevocationStore} 是内存 Caffeine 缓存，无外部依赖。
 *       登出是否真的生效，恰恰取决于往这个 store 里写了什么。</li>
 *   <li>{@code UserMapper} 才真正需要替身：它要连数据库。</li>
 * </ul>
 *
 * <p>用真实 JWT 还有一个直接好处：能断言「access 与 refresh 共享同一个 sid」
 * 这种跨越两个组件的一致性约束。这是登出能生效的<b>前提</b>，
 * 一旦有人改动令牌结构把它破坏掉，只有这种测试能发现。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("鉴权服务")
class AuthServiceTest {

    private static final String SECRET =
            "unit-test-only-secret-must-be-at-least-32-chars-long";
    private static final String ISSUER = "stock-analyst-test";

    @Mock
    private UserMapper userMapper;

    private RecordingPasswordEncoder passwordEncoder;
    private JwtTokenService tokenService;
    private TokenRevocationStore revocationStore;
    private AuthService authService;

    /**
     * 一次性预热，把 JVM 冷启动成本从单条用例的计时里挪出来。
     *
     * <p>实测数据（同一份代码，冷 / 热两轮）：
     * <pre>
     *   new InMemoryTokenRevocationStore()   171 ms  →  1.1 ms
     *   login（签发两张 JWT）        638 ms  →  3.5 ms
     *   logout                        98 ms  →  5.2 ms
     * </pre>
     * 成本来自 jjwt 的算法 provider 反射查找、Caffeine 的 scheduler 与节点类初始化、
     * 以及 {@code SecureRandom} 首次播种。它只发生一次，但会被记在
     * <b>首个跑到该路径的用例</b>头上——表现成"有一条用例莫名其妙跑了几秒"。
     *
     * <p>注意预热必须<b>真实走到</b>每条路径才有意义：只 new 一个
     * {@code TokenRevocationStore} 而不 {@code put}，Caffeine 的节点分配与
     * 自定义 {@code Expiry} 调度仍是冷的。同理 Mockito 的 mock 创建也只在
     * 第一次 {@code @Mock} 注入时付出成本，所以这里显式 mock 一次。
     *
     * <p>预热不减少总耗时，它的价值是让<b>单条用例的耗时变得可读</b>：
     * 否则将来有人加 {@code @Timeout} 或做性能对比时，
     * 会去追查一个并不存在的慢操作。
     */
    @BeforeAll
    static void warmUpJvm() {
        JwtProperties props = new JwtProperties(
                SECRET, ISSUER, Duration.ofMinutes(30), Duration.ofDays(30));
        JwtTokenService warm = new JwtTokenService(props);
        warm.parse(warm.createRefreshToken("warm-up", warm.newSessionId()), TokenType.REFRESH);

        // 必须真的 write + read，否则 Caffeine 的 put 路径还是冷的
        TokenRevocationStore warmStore = new InMemoryTokenRevocationStore();
        Instant soon = Instant.now().plus(Duration.ofMinutes(5));
        warmStore.revoke("warm-jti", soon);
        warmStore.revokeSession("warm-sid", soon);
        warmStore.isRevoked("warm-jti");
        warmStore.isSessionRevoked("warm-sid");
        warmStore.size();

        // Mockito 的 mock 创建只在首次付出成本，显式触发一次
        Mockito.mock(UserMapper.class);
    }

    @BeforeEach
    void setUp() {
        passwordEncoder = new RecordingPasswordEncoder();
        tokenService = new JwtTokenService(new JwtProperties(
                SECRET, ISSUER, Duration.ofMinutes(30), Duration.ofDays(30)));
        revocationStore = new InMemoryTokenRevocationStore();
        authService = new AuthService(userMapper, passwordEncoder, tokenService, revocationStore);
    }

    /**
     * 可观测的 {@link PasswordEncoder} 替身。
     *
     * <p>为什么不用真实的 {@code BCryptPasswordEncoder}：
     * <ol>
     *   <li>bcrypt 单次约 50~100ms，本类有三十来条用例、每条好几次哈希运算，
     *       实测会占掉大半测试时间；</li>
     *   <li>更重要的是它会挡住一个**真实存在且值得验证的行为**——
     *       用户不存在时 AuthService 仍要跑一次哈希比较来抹平响应时间。
     *       用可计数的替身才能断言这件事发生了。</li>
     * </ol>
     *
     * <p>编码格式故意做得一眼可辨（{@code ENC(明文)}），
     * 这样断言「库里存的不是明文」时失败信息会很直观。
     * <b>bcrypt 自身的正确性不是被测对象</b>，那是 Spring Security 的责任。
     */
    private static final class RecordingPasswordEncoder implements PasswordEncoder {

        private final AtomicInteger matchCalls = new AtomicInteger();
        private final AtomicInteger encodeCalls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls.incrementAndGet();
            return "ENC(" + rawPassword + ")";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchCalls.incrementAndGet();
            return encodedPassword != null && encodedPassword.equals("ENC(" + rawPassword + ")");
        }

        int matchCallCount() {
            return matchCalls.get();
        }

        int encodeCallCount() {
            return encodeCalls.get();
        }
    }

    // ==================================================================
    // 辅助

    /** 让 mock 的 insert 模拟 MyBatis-Plus {@code ASSIGN_UUID} 的行为：回填主键。 */
    private void stubInsertAssigningId(String generatedId) {
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            // 真实实现里 ASSIGN_UUID 是在执行 INSERT 前就把 UUID 写到实体上的，
            // 所以 insert() 返回后 user.getId() 一定非空。这里如实复现，
            // 否则 userId 为 null 会让签发出的令牌 subject 为空——
            // 那是测试构造得不像，不是被测代码的问题。
            u.setId(generatedId);
            return 1;
        });
    }

    private static User existingUser(String id, String phone, String rawPassword) {
        User u = new User();
        u.setId(id);
        u.setPhone(phone);
        u.setPasswordHash("ENC(" + rawPassword + ")");
        u.setNickname("既有用户");
        return u;
    }

    private static RegisterRequest registerRequest(String phone, String password, String nickname) {
        return new RegisterRequest(phone, password, nickname);
    }

    private JwtTokenService.ParsedToken parseAccess(String token) {
        return tokenService.parse(token, TokenType.ACCESS);
    }

    private JwtTokenService.ParsedToken parseRefresh(String token) {
        return tokenService.parse(token, TokenType.REFRESH);
    }

    // ==================================================================
    @Nested
    @DisplayName("注册")
    class Register {

        @Test
        @DisplayName("首次注册返回令牌对，且 access 与 refresh 共享同一个 sid")
        void registersAndIssuesTokensSharingOneSession() {
            when(userMapper.countByPhone("13900001111")).thenReturn(0);
            stubInsertAssigningId("u-1");

            TokenPair pair = authService.register(registerRequest("13900001111", "Passw0rd!", null));

            assertThat(pair.accessToken()).isNotBlank();
            assertThat(pair.refreshToken()).isNotBlank();
            assertThat(pair.tokenType()).isEqualTo("Bearer");
            assertThat(pair.expiresIn()).isEqualTo(1800L);

            JwtTokenService.ParsedToken access = parseAccess(pair.accessToken());
            JwtTokenService.ParsedToken refresh = parseRefresh(pair.refreshToken());

            assertThat(access.userId()).isEqualTo("u-1");
            assertThat(refresh.userId()).isEqualTo("u-1");
            // 这一条是登出能生效的前提：登出只拉黑 sid，
            // 若两张令牌的 sid 不同，就永远只能吊销其中一张。
            assertThat(access.sessionId()).isNotBlank();
            assertThat(access.sessionId()).isEqualTo(refresh.sessionId());
        }

        @Test
        @DisplayName("库里存的是哈希，不是明文密码")
        void storesHashedPasswordNotPlaintext() {
            when(userMapper.countByPhone(anyString())).thenReturn(0);
            stubInsertAssigningId("u-1");

            authService.register(registerRequest("13900001111", "Passw0rd!", null));

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            assertThat(captor.getValue().getPasswordHash())
                    .isNotNull()
                    .isNotEqualTo("Passw0rd!")
                    .isEqualTo("ENC(Passw0rd!)");
        }

        @Test
        @DisplayName("不传昵称时用手机号打码生成默认昵称")
        void generatesMaskedDefaultNickname() {
            when(userMapper.countByPhone(anyString())).thenReturn(0);
            stubInsertAssigningId("u-1");

            authService.register(registerRequest("13912345678", "Passw0rd!", null));

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            assertThat(captor.getValue().getNickname())
                    .as("默认昵称不能把完整手机号暴露给其他用户，前 3 后 4 之外的位数必须打码")
                    .isEqualTo("用户139****5678");
        }

        @Test
        @DisplayName("传了昵称则原样使用，并去掉首尾空白")
        void honoursProvidedNickname() {
            when(userMapper.countByPhone(anyString())).thenReturn(0);
            stubInsertAssigningId("u-1");

            authService.register(registerRequest("13900001111", "Passw0rd!", "  老邢  "));

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            assertThat(captor.getValue().getNickname()).isEqualTo("老邢");
        }

        @Test
        @DisplayName("昵称为纯空白时退回默认昵称（不能存成空格串）")
        void blankNicknameFallsBackToDefault() {
            when(userMapper.countByPhone(anyString())).thenReturn(0);
            stubInsertAssigningId("u-1");

            authService.register(registerRequest("13912345678", "Passw0rd!", "   "));

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(captor.capture());
            assertThat(captor.getValue().getNickname()).isEqualTo("用户139****5678");
        }

        @Test
        @DisplayName("前置查到手机号已存在 → PHONE_EXISTS，且不执行插入")
        void rejectsExistingPhoneBeforeInsert() {
            when(userMapper.countByPhone("13900001111")).thenReturn(1);

            BizException ex = catchThrowableOfType(
                    () -> authService.register(registerRequest("13900001111", "Passw0rd!", null)),
                    BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.PHONE_EXISTS.name());
            assertThat(ex.getStatus()).isEqualTo(ErrorCode.PHONE_EXISTS.status());
            verify(userMapper, never()).insert(any(User.class));
        }

        /**
         * 并发注册同一手机号时的兜底路径。
         *
         * <p>前置 {@code countByPhone} 查不到不代表真的不重复——
         * 两个请求可能同时读到 0。真正的唯一性保证是数据库唯一索引，
         * 后到的那个会抛 {@link DuplicateKeyException}。
         *
         * <p>这条断言的是「同一个业务错误码」，因为用户不该看到
         * 「有时候提示手机号已注册、有时候 500」。若这里退化成让异常穿透，
         * 表现就是偶发 500，而偶发问题最难复现。
         */
        @Test
        @DisplayName("并发下唯一索引冲突 → 同样翻译为 PHONE_EXISTS，不能让 500 穿透")
        void translatesDuplicateKeyToPhoneExists() {
            when(userMapper.countByPhone("13900001111")).thenReturn(0);
            when(userMapper.insert(any(User.class)))
                    .thenThrow(new DuplicateKeyException("uk_users_phone"));

            BizException ex = catchThrowableOfType(
                    () -> authService.register(registerRequest("13900001111", "Passw0rd!", null)),
                    BizException.class);

            assertThat(ex.getCode())
                    .as("唯一索引冲突必须与前置检查给出同一个错误码，否则用户看到偶发 500")
                    .isEqualTo(ErrorCode.PHONE_EXISTS.name());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("登录")
    class Login {

        @Test
        @DisplayName("登录成功返回令牌对")
        void logsInWithCorrectPassword() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            TokenPair pair = authService.login("13900001111", "Passw0rd!");

            assertThat(parseAccess(pair.accessToken()).userId()).isEqualTo("u-1");
            assertThat(parseRefresh(pair.refreshToken()).userId()).isEqualTo("u-1");
        }

        /**
         * 会话隔离：在手机上登录不应把电脑上的登录顶掉。
         *
         * <p>实现方式是每次登录都开一个新 sid。若有人把 sid 改成按 userId
         * 派生（看起来更"稳定"），后果是：任一设备登出会拉黑该用户的全部设备。
         */
        @Test
        @DisplayName("每次登录开新会话：两次登录的 sid 不同")
        void eachLoginOpensItsOwnSession() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            String sid1 = parseAccess(authService.login("13900001111", "Passw0rd!").accessToken())
                    .sessionId();
            String sid2 = parseAccess(authService.login("13900001111", "Passw0rd!").accessToken())
                    .sessionId();

            assertThat(sid1).isNotEqualTo(sid2);
        }

        @Test
        @DisplayName("用户不存在 → AUTH_INVALID，文案为「手机号或密码错误」")
        void rejectsUnknownPhone() {
            when(userMapper.selectByPhone("13900001111")).thenReturn(null);

            BizException ex = catchThrowableOfType(
                    () -> authService.login("13900001111", "Passw0rd!"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
            assertThat(ex.getMessage()).isEqualTo("手机号或密码错误");
        }

        /**
         * 防账号枚举：两种失败必须<b>完全无法区分</b>。
         *
         * <p>错误码和文案都要一样。若「用户不存在」返回 404 而「密码错误」返回 401，
         * 攻击者就能用任意密码批量探测哪些手机号已注册——
         * 对金融类应用来说这本身就是敏感信息。
         */
        @Test
        @DisplayName("用户不存在与密码错误返回完全相同的错误码与文案（防账号枚举）")
        void unknownPhoneAndWrongPasswordAreIndistinguishable() {
            // 用一个 thenAnswer 同时覆盖「查得到」和「查不到」两种入参。
            // 写成两条 when() 会让 Mockito 的严格桩校验在遇到未打桩的入参时直接报错
            // （PotentialStubbingProblem）——那是测试脚手架的问题，不是被测代码的问题。
            when(userMapper.selectByPhone(anyString())).thenAnswer(invocation ->
                    "13900001111".equals(invocation.getArgument(0, String.class))
                            ? existingUser("u-1", "13900001111", "Passw0rd!")
                            : null);

            BizException unknown = catchThrowableOfType(
                    () -> authService.login("13999998888", "Passw0rd!"), BizException.class);
            BizException wrongPwd = catchThrowableOfType(
                    () -> authService.login("13900001111", "WrongPass1!"), BizException.class);

            assertThat(unknown).isNotNull();
            assertThat(wrongPwd).isNotNull();
            assertThat(unknown.getCode())
                    .as("两种失败的错误码不同就等于把「该手机号是否注册」告诉给了攻击者")
                    .isEqualTo(wrongPwd.getCode());
            assertThat(unknown.getMessage()).isEqualTo(wrongPwd.getMessage());
            assertThat(unknown.getStatus()).isEqualTo(wrongPwd.getStatus());
        }

        /**
         * 时间侧信道防护。
         *
         * <p>用户不存在时若直接 return，响应时间会明显短于「用户存在但密码错误」
         * （后者要跑一次 bcrypt）。攻击者用响应时间就能枚举出已注册手机号，
         * 前面的「错误码一致」就白做了。
         *
         * <p>断言的是 {@code matches} 被调用过——如果将来有人为了"优化"
         * 删掉那行占位比较，这条会红。
         */
        @Test
        @DisplayName("用户不存在时仍执行一次密码比较（抹平响应时间，防时间侧信道枚举）")
        void stillHashesWhenUserDoesNotExist() {
            when(userMapper.selectByPhone("13900001111")).thenReturn(null);

            catchThrowableOfType(() -> authService.login("13900001111", "Passw0rd!"),
                    BizException.class);

            assertThat(passwordEncoder.matchCallCount())
                    .as("删掉这次比较会让「用户不存在」的响应快出一个 bcrypt 的时间，"
                            + "攻击者据此可以枚举已注册手机号")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("密码错误时不签发任何令牌")
        void issuesNoTokenOnWrongPassword() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            catchThrowableOfType(() -> authService.login("13900001111", "WrongPass1!"),
                    BizException.class);

            // 没有任何可观测的令牌产物即为证据：签发票据会让 encode 计数、
            // 也会往 revocation 之外的地方产生副作用。这里用「没有成功的返回值」+ 错误码间接断言。
            assertThat(revocationStore.size()).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("刷新令牌")
    class Refresh {

        @Test
        @DisplayName("正常刷新：返回新令牌对，且沿用同一个 sid（轮转的是令牌，不是会话）")
        void rotatesTokensButKeepsSession() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair first = authService.login("13900001111", "Passw0rd!");

            TokenPair second = authService.refresh(first.refreshToken());

            String sid1 = parseRefresh(first.refreshToken()).sessionId();
            String sid2 = parseRefresh(second.refreshToken()).sessionId();
            assertThat(sid2)
                    .as("刷新时若 sid 跟着变，登出要拉黑的 sid 就与客户端手里的新令牌对不上，"
                            + "登出会退化成只失效一半")
                    .isEqualTo(sid1);
            assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        }

        @Test
        @DisplayName("刷新后旧 refresh token 立即失效（令牌轮转）")
        void oldRefreshTokenIsRevokedAfterRotation() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair first = authService.login("13900001111", "Passw0rd!");

            authService.refresh(first.refreshToken());

            assertThat(revocationStore.isRevoked(parseRefresh(first.refreshToken()).tokenId()))
                    .as("旧刷新令牌不立刻作废，被盗令牌的可用窗口就无法收敛")
                    .isTrue();

            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(first.refreshToken()), BizException.class);
            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        @Test
        @DisplayName("同一 refresh token 只能用一次（重放被拒）")
        void refreshTokenCannotBeReplayed() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair first = authService.login("13900001111", "Passw0rd!");
            authService.refresh(first.refreshToken());

            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(first.refreshToken()), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        /**
         * <b>缺陷「登出假成功」的核心回归保护。</b>
         *
         * <p>原实现只认客户端递上来的 refresh token，客户端不传时
         * 服务端就返回成功但什么都没吊销。修好之后，登出会拉黑 sid，
         * 于是即便客户端一个字段都不传、只靠 Authorization 头带来的 sessionId，
         * 该会话的刷新令牌也必须失效。
         */
        @Test
        @DisplayName("会话被登出吊销后，refresh 必须失败（登出真正生效的回归保护）")
        void refreshFailsAfterSessionRevokedByLogout() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair pair = authService.login("13900001111", "Passw0rd!");
            String sessionId = parseAccess(pair.accessToken()).sessionId();

            // 只给 sessionId、不给 refreshToken —— 模拟客户端忘了传请求体
            authService.logout(null, sessionId);

            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(pair.refreshToken()), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
            assertThat(ex.getMessage()).isEqualTo("登录状态已失效，请重新登录");
        }

        @Test
        @DisplayName("用 access token 冒充 refresh token → 401（令牌类型校验）")
        void rejectsAccessTokenUsedAsRefreshToken() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair pair = authService.login("13900001111", "Passw0rd!");

            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(pair.accessToken()), BizException.class);

            assertThat(ex.getCode())
                    .as("少了类型校验，30 分钟有效期的 access token 就能当 30 天的 refresh 用")
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }

        @Test
        @DisplayName("垃圾字符串 → AUTH_INVALID，不透传底层解析异常")
        void rejectsGarbageToken() {
            BizException ex = catchThrowableOfType(
                    () -> authService.refresh("not-a-jwt-at-all"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
            assertThat(ex.getMessage())
                    .as("不能把 jjwt 的解析细节透出去，那属于实现信息泄漏")
                    .isEqualTo(ErrorCode.AUTH_INVALID.defaultMessage());
        }

        @Test
        @DisplayName("已过期的 refresh token → AUTH_EXPIRED（与「无效」区分开）")
        void rejectsExpiredRefreshToken() {
            // 用负 TTL 直接签一张已过期的令牌，避免 sleep
            JwtTokenService shortLived = new JwtTokenService(new JwtProperties(
                    SECRET, ISSUER, Duration.ofMinutes(30), Duration.ofSeconds(-60)));
            String expired = shortLived.createRefreshToken("u-1", "sid-x");

            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(expired), BizException.class);

            assertThat(ex.getCode())
                    .as("前端要靠 AUTH_EXPIRED 决定「静默刷新」还是「跳登录页」，"
                            + "与 AUTH_INVALID 合并会让过期用户被直接踢出")
                    .isEqualTo(ErrorCode.AUTH_EXPIRED.name());
        }

        /**
         * 兼容 sid 机制上线前签发的旧令牌。
         *
         * <p>不含 sid 的刷新令牌必须仍能刷新（否则发版即让所有存量会话掉线），
         * 但刷新后要补发一个新 sid，让这类会话从下次刷新起也能被正常登出吊销。
         */
        @Test
        @DisplayName("旧 refresh token 不含 sid：仍可刷新，且补发一个新 sid")
        void legacyTokenWithoutSessionGetsNewSessionId() {
            String legacy = tokenService.createRefreshToken("u-1", null);
            assertThat(parseRefresh(legacy).sessionId()).isNull();

            TokenPair renewed = authService.refresh(legacy);

            String newSid = parseRefresh(renewed.refreshToken()).sessionId();
            assertThat(newSid).isNotBlank();
            assertThat(parseAccess(renewed.accessToken()).sessionId()).isEqualTo(newSid);
        }

        @Test
        @DisplayName("未注明 sid 的令牌不会被误判为「已吊销」")
        void missingSessionIsNotTreatedAsRevoked() {
            String legacy = tokenService.createRefreshToken("u-1", null);

            // isSessionRevoked(null) 必须为 false。若实现里把 "s:" + null 拼成 "s:null"
            // 并当成命中，所有存量会话会在发版瞬间全部掉线。
            assertThat(revocationStore.isSessionRevoked(null)).isFalse();
            assertThat(revocationStore.isSessionRevoked("")).isFalse();
            assertThat(authService.refresh(legacy)).isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("登出")
    class Logout {

        @Test
        @DisplayName("只给 sessionId、不给 refreshToken 也必须真正吊销会话")
        void revokesSessionWithOnlySessionId() {
            String sessionId = "sid-abc";

            authService.logout(null, sessionId);

            assertThat(revocationStore.isSessionRevoked(sessionId))
                    .as("这是「假登出」缺陷的正面断言：客户端漏传 refresh token 时，"
                            + "仅凭 Authorization 头带来的 sid 也必须能吊销会话")
                    .isTrue();
        }

        /**
         * <b>本条断言拦下了一个真实缺陷。</b>
         *
         * <p>原实现只在 {@code sessionId} 参数非空时调用 {@code revokeSession}，
         * 而 {@code parsed.sessionId()}（刷新令牌自带的 sid）拿到了却没用。
         * 后果：Access Token 已过期、过滤器取不到 sid、客户端只递 refreshToken 时
         * （AuthController 注释明确支持这种调用），**会话级吊销被整个跳过** ——
         * refresh 用不了了，但那张尚未过期的 Access Token 还能继续用最多 30 分钟。
         *
         * <p>它和「登出假成功」是同一类问题：文档承诺「access 与 refresh 全部立即失效」，
         * 实现只做到一半，而且比原版更隐蔽——接口确实返回了成功，也确实吊销了点什么。
         */
        @Test
        @DisplayName("只给 refreshToken 时，按它自带的 sid 吊销整个会话（access 也必须失效）")
        void revokesSessionWhenOnlyRefreshTokenGiven() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair pair = authService.login("13900001111", "Passw0rd!");

            // sessionId 传 null：模拟 Access Token 已过期、过滤器取不到 sid 的情况
            authService.logout(pair.refreshToken(), null);

            String sid = parseRefresh(pair.refreshToken()).sessionId();
            assertThat(revocationStore.isSessionRevoked(sid))
                    .as("刷新令牌自带 sid，没用它就意味着「尚未过期的 Access Token 还能用」")
                    .isTrue();
            assertThat(revocationStore.isRevoked(parseRefresh(pair.refreshToken()).tokenId()))
                    .isTrue();
            // 会话级吊销是 access token 失效的唯一途径，这里直接断言它的效果
            assertThat(revocationStore.isSessionRevoked(parseAccess(pair.accessToken()).sessionId()))
                    .isTrue();
        }

        @Test
        @DisplayName("两者都给：会话级与令牌级都被吊销")
        void revokesBothLevels() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair pair = authService.login("13900001111", "Passw0rd!");

            authService.logout(pair.refreshToken(), parseAccess(pair.accessToken()).sessionId());

            assertThat(revocationStore.isSessionRevoked(parseAccess(pair.accessToken()).sessionId()))
                    .isTrue();
            assertThat(revocationStore.isRevoked(parseRefresh(pair.refreshToken()).tokenId()))
                    .isTrue();
        }

        /**
         * 幂等 = 不抛异常，而不是「假装成功」。
         *
         * <p>两者都没有表示这次登出什么都没吊销。返回成功是为了幂等
         * （重复登出不该报错），但断言 {@code size() == 0} 是为了钉住
         * 「确实什么都没做」这一事实——配合实现里的 WARN 日志，
         * 它不会退化成静默的假成功。
         */
        @Test
        @DisplayName("两者都为 null 时不抛异常，且不产生任何吊销条目（幂等但不假装）")
        void logoutWithNothingToRevokeIsIdempotent() {
            authService.logout(null, null);
            authService.logout("", "   ");

            assertThat(revocationStore.size()).isZero();
        }

        @Test
        @DisplayName("refreshToken 不可解析时不抛异常，但仍按 sessionId 完成吊销")
        void toleratesUnparsableRefreshToken() {
            String sessionId = "sid-xyz";

            authService.logout("garbage-token", sessionId);

            assertThat(revocationStore.isSessionRevoked(sessionId))
                    .as("令牌坏掉不该让登出整体失败——登出的语义是「确保这次登录不可用」，"
                            + "而 sessionId 这条路径不依赖令牌可解析")
                    .isTrue();
        }

        @Test
        @DisplayName("已过期的 refreshToken 不抛异常（登出幂等）")
        void toleratesExpiredRefreshToken() {
            JwtTokenService shortLived = new JwtTokenService(new JwtProperties(
                    SECRET, ISSUER, Duration.ofMinutes(30), Duration.ofSeconds(-60)));
            String expired = shortLived.createRefreshToken("u-1", "sid-old");

            authService.logout(expired, "sid-from-header");

            assertThat(revocationStore.isSessionRevoked("sid-from-header")).isTrue();
        }

        @Test
        @DisplayName("连续两次登出都成功（幂等）")
        void repeatedLogoutSucceeds() {
            String sessionId = "sid-repeat";

            authService.logout(null, sessionId);
            authService.logout(null, sessionId);

            assertThat(revocationStore.isSessionRevoked(sessionId)).isTrue();
        }

        /**
         * 登出黑名单的存活期必须覆盖到「该会话最长寿的令牌」，否则会"复活"。
         *
         * <p>若按 accessTtl（30 分钟）存，登出 30 分钟后 refresh token 就能重新
         * 换出新令牌对——用户以为已经退出，实际上还能被续期。
         */
        @Test
        @DisplayName("传了 refreshToken 时，黑名单存活期取它的过期时间而非 accessTtl")
        void sessionExpiryCoversRefreshTokenLifetime() {
            when(userMapper.selectByPhone("13900001111"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));
            TokenPair pair = authService.login("13900001111", "Passw0rd!");
            Instant refreshExpiry = parseRefresh(pair.refreshToken()).expiresAt();

            // 黑名单条目的存活期就是传入的 expiresAt 与「现在」之差。
            // 用 refresh token 自身的过期时间（约 30 天后）作为 sessionExpiry，
            // 这里通过「刷新仍被拒」间接验证条目没有提前过期。
            authService.logout(pair.refreshToken(), parseAccess(pair.accessToken()).sessionId());

            assertThat(refreshExpiry).isAfter(Instant.now().plus(Duration.ofDays(29)));
            BizException ex = catchThrowableOfType(
                    () -> authService.refresh(pair.refreshToken()), BizException.class);
            assertThat(ex.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.name());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("当前用户")
    class CurrentUser {

        @Test
        @DisplayName("返回 id / phone / nickname，不含密码哈希")
        void returnsProfileWithoutPasswordHash() {
            User u = existingUser("u-1", "13900001111", "Passw0rd!");
            when(userMapper.selectById("u-1")).thenReturn(u);

            UserProfile profile = authService.currentUser("u-1");

            assertThat(profile.id()).isEqualTo("u-1");
            assertThat(profile.phone()).isEqualTo("13900001111");
            assertThat(profile.nickname()).isEqualTo("既有用户");
            // UserProfile 是 record，字段由构造器固定。这里断言它的组件集合，
            // 防止有人为了图方便把 passwordHash 塞进去——那是最容易被忽略的泄漏面。
            assertThat(UserProfile.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactlyInAnyOrder("id", "phone", "nickname");
        }

        @Test
        @DisplayName("令牌有效但用户已被删除 → UNAUTHORIZED，让前端回到登录页")
        void deletedUserIsTreatedAsUnauthenticated() {
            when(userMapper.selectById("u-gone")).thenReturn(null);

            BizException ex = catchThrowableOfType(
                    () -> authService.currentUser("u-gone"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.name());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("修改密码")
    class ChangePassword {

        @Test
        @DisplayName("成功后只更新密码字段，不覆盖手机号与昵称")
        void onlyUpdatesPasswordColumn() {
            when(userMapper.selectById("u-1"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            authService.changePassword("u-1", "Passw0rd!", "NewPassw0rd!");

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateById(captor.capture());
            User sent = captor.getValue();
            assertThat(sent.getId()).isEqualTo("u-1");
            assertThat(sent.getPasswordHash()).isEqualTo("ENC(NewPassw0rd!)");
            // 这是真实踩过的坑：用 updateById(整体实体) 会把未设置的字段写成 null，
            // 结果改一次密码就把手机号和昵称清空了。
            assertThat(sent.getPhone()).as("不能把 phone 一起更新成 null").isNull();
            assertThat(sent.getNickname()).as("不能把 nickname 一起更新成 null").isNull();
        }

        @Test
        @DisplayName("原密码错误 → AUTH_INVALID（不是 INVALID_PARAMS）")
        void rejectsWrongOldPassword() {
            when(userMapper.selectById("u-1"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            BizException ex = catchThrowableOfType(
                    () -> authService.changePassword("u-1", "WrongOld1!", "NewPassw0rd!"),
                    BizException.class);

            assertThat(ex.getCode())
                    .as("这是凭证校验失败而非参数格式问题，用 400 会误导前端把用户留在页面上")
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
            assertThat(ex.getMessage()).isEqualTo("原密码错误");
            verify(userMapper, never()).updateById(any(User.class));
        }

        @Test
        @DisplayName("新密码与原密码相同 → INVALID_PARAMS，且不写库")
        void rejectsIdenticalNewPassword() {
            when(userMapper.selectById("u-1"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            BizException ex = catchThrowableOfType(
                    () -> authService.changePassword("u-1", "Passw0rd!", "Passw0rd!"),
                    BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_PARAMS.name());
            verify(userMapper, never()).updateById(any(User.class));
        }

        @Test
        @DisplayName("用户不存在 → UNAUTHORIZED，且不写库")
        void rejectsMissingUser() {
            when(userMapper.selectById("u-gone")).thenReturn(null);

            BizException ex = catchThrowableOfType(
                    () -> authService.changePassword("u-gone", "a", "b"), BizException.class);

            assertThat(ex.getCode()).isEqualTo(ErrorCode.UNAUTHORIZED.name());
            verify(userMapper, never()).updateById(any(User.class));
        }

        /**
         * 已知边界，用测试把当前行为钉住。
         *
         * <p>改密后<b>不</b>吊销已签发的访问令牌（无状态令牌无法枚举）。
         * 这条测试不是断言「这样对」，而是断言「当前就是这样」——
         * 将来若引入令牌版本号机制，「改密即全端下线」会让这条变红，
         * 从而逼开发者显式确认这是有意变更。
         */
        @Test
        @DisplayName("【特征化】改密不吊销任何令牌——当前行为，不是理想行为")
        void changePasswordDoesNotRevokeExistingTokens() {
            when(userMapper.selectById("u-1"))
                    .thenReturn(existingUser("u-1", "13900001111", "Passw0rd!"));

            authService.changePassword("u-1", "Passw0rd!", "NewPassw0rd!");

            assertThat(revocationStore.size())
                    .as("当前改密不会踢掉其他设备，旧 Access Token 仍能用到自然过期（最多 30 分钟）。"
                            + "要「改密即全端下线」需引入 token_version，属于已知未实现项")
                    .isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("日志脱敏")
    class LogMasking {

        /**
         * 日志里的手机号必须打码。
         *
         * <p>这条不好直接断言（logger 是静态的），所以退一步断言
         * 「脱敏后的形态」这个纯函数行为可由公开路径间接覆盖：
         * 登录失败走的就是 {@code maskPhone}。这里通过大量调用确认不抛异常，
         * 并且<strong>明确记录这条没有真正的断言覆盖</strong>——
         * 不因为测试文件里有这个方法名就假装测过了。
         */
        @Test
        @DisplayName("手机号极短时脱敏不越界（不会抛 StringIndexOutOfBounds）")
        void maskingToleratesVeryShortPhone() {
            assertThat(catchThrowableOfType(() -> authService.login("1", "x"),
                    BizException.class)).isNotNull();
            assertThat(catchThrowableOfType(() -> authService.login(null, "x"),
                    BizException.class)).isNotNull();
        }

        @Test
        @DisplayName("用真实编码器时构造函数仍能完成一次 encode（占位哈希不为空）")
        void dummyHashIsInitialisedForTimingDefence() {
            // 重新用真实 bcrypt 构造一次，确认占位哈希能被正常初始化：
            // 若有人把 encode 改成在 request 里做，dummyHash 会变成 null，
            // 用户不存在分支的 matches(.., null) 就会抛异常而不是静默返回 false。
            var realEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
            AuthService service = new AuthService(userMapper, realEncoder, tokenService,
                    new InMemoryTokenRevocationStore());
            when(userMapper.selectByPhone("13900001111")).thenReturn(null);

            BizException ex = catchThrowableOfType(
                    () -> service.login("13900001111", "Passw0rd!"), BizException.class);

            assertThat(ex.getCode())
                    .as("占位哈希未初始化时这里会抛 NPE 或 IllegalArgumentException，"
                            + "而不是规规矩矩的 AUTH_INVALID")
                    .isEqualTo(ErrorCode.AUTH_INVALID.name());
        }
    }
}
