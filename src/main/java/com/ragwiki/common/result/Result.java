package com.ragwiki.common.result;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一返回体。正常响应与异常响应结构一致，客户端只解析这一种结构。
 */
@Schema(description = "统一返回体")
public record Result<T>(

        @Schema(description = "业务码，200 表示成功", example = "200")
        int code,

        @Schema(description = "提示信息", example = "成功")
        String message,

        @Schema(description = "业务数据，失败时为 null")
        T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), data);
    }

    public static Result<Void> ok() {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.code(), resultCode.message());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.code(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
