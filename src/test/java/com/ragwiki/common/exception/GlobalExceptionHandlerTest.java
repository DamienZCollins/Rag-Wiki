package com.ragwiki.common.exception;

import com.ragwiki.common.result.Result;
import com.ragwiki.common.result.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理的端到端校验。
 * 用一个只在测试里存在的探针 controller 触发各类异常，避免为了验证往主代码里塞示例接口。
 * 用 controllers 收窄切片，否则 web 切片还会去建 ChatController，连带把整条 agent 依赖链拖进来。
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailureReturnsUnifiedBody() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_INVALID.code()))
                .andExpect(jsonPath("$.message").value(containsString("question")))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void validRequestReturnsUnifiedBody() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"退货运费谁出\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data").value("退货运费谁出"));
    }

    @Test
    void businessExceptionCarriesItsOwnCode() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.BUSINESS_ERROR.code()))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void unknownPathReturns404NotHandledAs500() throws Exception {
        mockMvc.perform(get("/probe/not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.code()));
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(post("/probe/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ResultCode.METHOD_NOT_ALLOWED.code()));
    }

    @Test
    void unexpectedExceptionDoesNotLeakStackTrace() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.code()))
                .andExpect(jsonPath("$.message").value(ResultCode.SYSTEM_ERROR.message()));
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record EchoRequest(@NotBlank(message = "不能为空") String question) {
        }

        @PostMapping("/echo")
        Result<String> echo(@Valid @RequestBody EchoRequest request) {
            return Result.ok(request.question());
        }

        @GetMapping("/business")
        Result<Void> business() {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "订单不存在");
        }

        @GetMapping("/boom")
        Result<Void> boom() {
            throw new IllegalStateException("内部细节不应出现在响应体中");
        }
    }
}
