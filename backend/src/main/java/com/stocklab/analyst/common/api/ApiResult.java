package com.stocklab.analyst.common.api;

/**
 * 统一成功响应包装。
 *
 * <p>约定：HTTP 状态码承载传输层语义（2xx 成功 / 4xx 业务失败 / 5xx 系统失败），
 * 本包装仅承载成功数据，业务错误由 {@link com.stocklab.analyst.common.exception.GlobalExceptionHandler}
 * 统一输出 {@code {"code": "...", "message": "..."}}，与前端错误分支一一对应。
 *
 * @param code    固定为 {@code OK}，便于前端做统一断言
 * @param message 人类可读描述
 * @param data    业务数据
 */
public record ApiResult<T>(String code, String message, T data) {

    private static final String OK_CODE = "OK";
    private static final String OK_MESSAGE = "success";

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(OK_CODE, OK_MESSAGE, data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(OK_CODE, OK_MESSAGE, null);
    }
}
