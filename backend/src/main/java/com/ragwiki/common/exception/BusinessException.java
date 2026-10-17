package com.ragwiki.common.exception;

import com.ragwiki.common.result.ResultCode;

/**
 * 业务异常。由 service 层主动抛出，携带错误码，由全局异常处理器统一转成响应体。
 */
public class BusinessException extends RuntimeException {

    private final transient ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.message());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    public int getCode() {
        return resultCode.code();
    }
}
