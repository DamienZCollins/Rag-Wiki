package com.ragwiki.common.result;

/**
 * 统一错误码。
 * code 用于响应体，httpStatus 用于 HTTP 状态码，两者在一个枚举里成对声明，避免各处对不上。
 * 业务模块的错误码从 1000 往后扩展。
 */
public enum ResultCode {

    SUCCESS(200, 200, "成功"),
    PARAM_INVALID(400, 400, "参数校验失败"),
    NOT_FOUND(404, 404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, 405, "请求方法不支持"),
    BUSINESS_ERROR(1000, 400, "业务处理失败"),
    SYSTEM_ERROR(500, 500, "系统内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ResultCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
