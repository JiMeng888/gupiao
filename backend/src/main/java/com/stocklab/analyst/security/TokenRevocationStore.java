package com.stocklab.analyst.security;

import java.time.Instant;

/**
 * 已注销令牌 / 已注销会话的黑名单。
 *
 * <p>解决的问题：JWT 是无状态的，服务端「没有」会话可以销毁——
 * 这意味着<b>用户点了退出登录，手里的 Refresh Token 仍然是有效的</b>，
 * 只要拿到它就能一直换取新的 Access Token。原 FastAPI 版本的
 * {@code /auth/logout} 只是返回 {@code {"ok": true}}，属于「假装成功」。
 *
 * <h2>两级吊销，粒度不同，用途也不同</h2>
 * <ol>
 *   <li><b>令牌级</b>（{@link #revoke}）——按 {@code jti} 拉黑单个令牌。
 *       用于刷新令牌轮转：旧 refresh token 一旦被使用就立即作废。</li>
 *   <li><b>会话级</b>（{@link #revokeSession}）——按 {@code sid} 拉黑整次登录。
 *       用于登出：同一次登录签发的 access 与 refresh <b>全部</b>立即失效，
 *       包括那张还没到期的 Access Token。</li>
 * </ol>
 *
 * <p>为什么登出必须用会话级而不是令牌级？因为客户端请求登出时，
 * 服务端手里只有 Access Token。若只拉黑 refresh token 的 jti，
 * 就必须依赖客户端把 refresh token 也一起递上来——客户端漏传时
 * 服务端只能「成功但什么都没做」，退化成假登出。会话级吊销让服务端
 * 能从 Access Token 自身读出 sid 并裁决，不再依赖客户端配合。
 *
 * <h2>两种实现与部署形态的对应关系</h2>
 * <ul>
 *   <li>{@link InMemoryTokenRevocationStore}（默认）——单进程内存。
 *       <b>多实例部署下登出不跨实例生效</b>，进程重启后黑名单丢失。
 *       只适用于单副本。</li>
 *   <li>{@link RedisTokenRevocationStore}——多实例共享，
 *       进程重启不丢。{@code app.session.store=redis} 时启用。</li>
 * </ul>
 * 由 {@code SecurityStoreConfig} 按配置选择，业务代码只依赖本接口。
 *
 * <h2>调用方必须传「该令牌自己的过期时间」</h2>
 * 会话级条目按 {@code max(accessTtl, refreshTtl)} 存活。
 * 若按 refreshTtl（30 天）存，则每次登出都要留 30 天的垃圾；
 * 按 accessTtl（30 分钟）存则登出 30 分钟后 refresh token 会「复活」。
 * 因此调用方必须传<b>该会话中最长令牌的过期时间</b>，
 * 见 {@code AuthService#logout}。
 */
public interface TokenRevocationStore {

    /**
     * 拉黑一个令牌（令牌级）。
     *
     * @param tokenId   令牌的 {@code jti}
     * @param expiresAt 令牌自身的过期时间；已过期或为 {@code null} 时不做任何事
     */
    void revoke(String tokenId, Instant expiresAt);

    /**
     * 拉黑一次登录会话（会话级）。
     *
     * <p>该会话签发的 access 与 refresh <b>全部</b>立即失效——
     * {@code JwtAuthenticationFilter} 会在每个请求上校验，
     * {@code AuthService#refresh} 会在刷新时校验。
     *
     * @param sessionId 会话 ID（令牌中的 {@code sid} 声明）
     * @param expiresAt 该会话中最长令牌的过期时间，决定黑名单条目存活多久
     */
    void revokeSession(String sessionId, Instant expiresAt);

    /** 令牌是否已被拉黑。 */
    boolean isRevoked(String tokenId);

    /**
     * 会话是否已被吊销。
     *
     * <p>传 {@code null} 或空串时返回 {@code false}（视为「无会话」）。
     * 这是有意为之：sid 机制上线前签发的旧令牌不含该声明，
     * 若把「缺失 sid」当作「已吊销」，所有存量会话会在发版瞬间全部掉线。
     * 代价是这类旧令牌无法被登出吊销，只能等它自然过期。
     */
    boolean isSessionRevoked(String sessionId);

    /**
     * 当前黑名单条目数（监控用，含令牌级与会话级）。
     *
     * @return 条目数；返回 {@code -1} 表示该实现不提供精确计数
     *         （见 {@link RedisTokenRevocationStore#size()}）
     */
    long size();
}
