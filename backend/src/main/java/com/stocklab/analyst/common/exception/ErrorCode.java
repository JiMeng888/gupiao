package com.stocklab.analyst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码字典。
 *
 * <p>每个错误码绑定默认提示与 HTTP 状态，避免在业务代码里散落魔法字符串与状态码。
 * 前端依据 {@code code} 做差异化提示（如 AUTH_EXPIRED 触发静默刷新令牌）。
 */
public enum ErrorCode {

    // ---------- 通用 ----------
    INVALID_PARAMS("请求参数不合法", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    RATE_LIMITED("请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    // ---------- 鉴权 ----------
    AUTH_REQUIRED("请先登录", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED("请先登录", HttpStatus.UNAUTHORIZED),
    AUTH_INVALID("无效凭证", HttpStatus.UNAUTHORIZED),
    AUTH_EXPIRED("登录已过期，请重新登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("无权访问该资源", HttpStatus.FORBIDDEN),
    PHONE_EXISTS("该手机号已注册", HttpStatus.CONFLICT),

    // ---------- 行情 ----------
    QUOTE_NOT_FOUND("未找到该股票代码", HttpStatus.NOT_FOUND),
    MARKET_DATA_UNAVAILABLE("行情数据源暂不可用", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_INDICATOR("不支持的技术指标", HttpStatus.BAD_REQUEST),
    INVALID_PERIOD("不支持的 K 线周期", HttpStatus.BAD_REQUEST),

    // ---------- 自选股 ----------
    GROUP_NOT_FOUND("自选分组不存在", HttpStatus.NOT_FOUND),
    ITEM_NOT_FOUND("自选股不存在", HttpStatus.NOT_FOUND),
    DUPLICATE_ITEM("该股票已在自选组中", HttpStatus.CONFLICT),

    // ---------- 持仓 ----------
    HOLDING_NOT_FOUND("未找到该持仓", HttpStatus.NOT_FOUND),
    INSUFFICIENT_SHARES("卖出股数超过持有数量", HttpStatus.BAD_REQUEST),

    // ---------- AI ----------
    LLM_UPSTREAM_ERROR("大模型调用失败", HttpStatus.BAD_GATEWAY),
    LLM_TIMEOUT("大模型响应超时", HttpStatus.GATEWAY_TIMEOUT),
    ANALYSIS_TASK_NOT_FOUND("分析任务不存在", HttpStatus.NOT_FOUND);

    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(String defaultMessage, HttpStatus status) {
        this.defaultMessage = defaultMessage;
        this.status = status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }
}
