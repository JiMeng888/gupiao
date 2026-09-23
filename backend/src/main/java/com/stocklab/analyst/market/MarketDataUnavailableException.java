package com.stocklab.analyst.market;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;

/**
 * 行情上游不可用。
 *
 * <p>与 {@code QUOTE_NOT_FOUND} 的区别是本项目错误处理的核心分界：
 * <ul>
 *   <li>{@code QUOTE_NOT_FOUND} —— 上游正常工作，但没有这个标的。属于
 *       「用户的输入问题」，返回 404，前端应引导用户换一个代码。</li>
 *   <li>{@code MarketDataUnavailableException} —— 上游超时/拒连/返回不可解析内容。
 *       属于「我们的服务问题」，返回 503，前端应提示稍后重试，
 *       并允许降级到演示数据。</li>
 * </ul>
 * 把两者混为一谈会让用户以为「贵州茅台不存在」，也会让监控告警失去意义。
 */
public class MarketDataUnavailableException extends BizException {

    public MarketDataUnavailableException(String message) {
        super(ErrorCode.MARKET_DATA_UNAVAILABLE, message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(ErrorCode.MARKET_DATA_UNAVAILABLE, message);
        initCause(cause);
    }
}
