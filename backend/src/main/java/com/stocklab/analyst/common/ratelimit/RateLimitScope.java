package com.stocklab.analyst.common.ratelimit;

/**
 * 限流档位。
 *
 * <p>按「被滥用的代价」而不是「接口类型」划分：
 * 写操作与消耗外部资源的操作进严格桶，纯读接口进宽松桶。
 */
public enum RateLimitScope {

    /** 严格桶：登录 / 注册 / 修改密码 / AI 分析 / 写入类操作。 */
    STRICT,

    /** 宽松桶：行情、K 线、指标、资讯等只读查询。 */
    LOOSE
}
