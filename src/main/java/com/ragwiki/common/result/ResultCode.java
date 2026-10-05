package com.ragwiki.common.result;

/**
 * 统一错误码。
 * code 用于响应体，httpStatus 用于 HTTP 状态码，两者在一个枚举里成对声明，避免各处对不上。
 *
 * 号段划分：200 HTTP 语义码；1000 模型接入；2000 agent；3000 wiki；4000 rag；5000 eval。
 */
public enum ResultCode {

    SUCCESS(200, 200, "成功"),
    PARAM_INVALID(400, 400, "参数校验失败"),
    NOT_FOUND(404, 404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, 405, "请求方法不支持"),
    BUSINESS_ERROR(1000, 400, "业务处理失败"),
    SYSTEM_ERROR(500, 500, "系统内部错误"),

    MODEL_NOT_CONFIGURED(1001, 500, "模型未配置"),
    MODEL_AUTH_FAILED(1002, 500, "模型鉴权失败"),
    MODEL_RATE_LIMITED(1003, 429, "模型限流"),
    MODEL_TIMEOUT(1004, 504, "模型调用超时"),
    MODEL_UNAVAILABLE(1005, 502, "模型服务不可用"),
    MODEL_RESPONSE_INVALID(1006, 502, "模型响应无法解析");

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
