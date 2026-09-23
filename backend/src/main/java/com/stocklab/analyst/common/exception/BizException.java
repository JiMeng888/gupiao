package com.stocklab.analyst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 *
 * <p>仅用于「可预期的业务失败」——例如股票代码不存在、卖出股数超过持仓。
 * 不可预期的异常（NPE、数据库连接失败等）不应包装成本异常，交给
 * {@link GlobalExceptionHandler} 的兜底分支记录完整堆栈。
 *
 * <p>异常仅承载可对外暴露的信息，不携带 SQL、内部路径或密钥。
 */
public class BizException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.name();
        this.status = errorCode.status();
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
