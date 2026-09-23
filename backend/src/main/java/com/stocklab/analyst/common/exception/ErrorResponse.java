package com.stocklab.analyst.common.exception;

/**
 * 错误响应体：与前端错误分支一一对应。
 *
 * @param code    业务错误码（见 {@link ErrorCode}）
 * @param message 可直接展示给用户的提示
 */
public record ErrorResponse(String code, String message) {
}
