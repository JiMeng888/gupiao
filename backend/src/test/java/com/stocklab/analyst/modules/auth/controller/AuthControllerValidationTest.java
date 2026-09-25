package com.stocklab.analyst.modules.auth.controller;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.common.exception.GlobalExceptionHandler;
import com.stocklab.analyst.modules.auth.dto.TokenPair;
import com.stocklab.analyst.modules.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权接口的参数校验与异常映射切片测试。
 *
 * <p>用 standalone MockMvc（不启动 Spring 上下文）只装配
 * {@link AuthController} + {@link GlobalExceptionHandler}，
 * 钉住的是<b>「非法输入在进入业务代码之前被拦下，并翻译成前端认识的错误体」</b>
 * 这条链路。此前这一层只有 HTTP 冒烟兜着，冒烟只测合法输入。
 *
 * <p>Controller 层失效的形态是：DTO 少一个 {@code @Valid}、
 * 异常处理器少一个分支——非法输入直穿到 Service，接口返回 500
 * 或者干脆写库成功，测试全绿。所以这里专测非法输入。
 *
 * <p>注：standalone 模式不织入 {@code @RateLimit} 切面与自定义参数解析器，
 * 因此 /me、/logout 这两个依赖 {@code @CurrentUserId}/{@code @CurrentSessionId}
 * 的端点不在本类覆盖范围，它们由 HTTP 冒烟与后续集成测试兜底。
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerValidationTest {

    @Mock
    private AuthService authService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("注册接口校验")
    class RegisterValidation {

        @Test
        @DisplayName("手机号格式非法 → 400 + INVALID_PARAMS + 字段级提示")
        void badPhoneRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"12345\",\"password\":\"secret123\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMS"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("手机号格式不正确")));
        }

        @Test
        @DisplayName("密码过短 → 400，提示长度约束")
        void shortPasswordRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800000000\",\"password\":\"123\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("密码长度")));
        }

        @Test
        @DisplayName("合法注册 → 200，业务层被调用且返回令牌对")
        void validRegisterPassesThrough() throws Exception {
            when(authService.register(any()))
                    .thenReturn(TokenPair.of("at", "rt", 1800));

            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800000000\",\"password\":\"secret123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.accessToken").value("at"))
                    .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
        }
    }

    @Nested
    @DisplayName("登录接口校验与安全语义")
    class LoginValidation {

        @Test
        @DisplayName("缺密码 → 400，不进入业务层")
        void missingPasswordRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800000000\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("密码不能为空")));
        }

        @Test
        @DisplayName("登录对格式古怪的手机号不做格式校验（防用户枚举），交给业务层统一拒绝")
        void loginDoesNotLeakPhoneFormat() throws Exception {
            // LoginRequest 刻意只有 @NotBlank：如果登录对非法手机号返回「格式不正确」，
            // 攻击者就能用响应差异枚举出哪些号段已注册。这条测试钉住该设计不被「顺手优化」掉
            when(authService.login("abc-def", "pw123456"))
                    .thenThrow(new BizException(ErrorCode.AUTH_INVALID, "手机号或密码错误"));

            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"abc-def\",\"password\":\"pw123456\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        }
    }

    @Nested
    @DisplayName("通用异常映射")
    class ExceptionMapping {

        @Test
        @DisplayName("请求体不是合法 JSON → 400 + 人类可读提示")
        void malformedJsonRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMS"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("合法 JSON")));
        }

        @Test
        @DisplayName("refresh 空 token → 400 + 字段提示")
        void blankRefreshTokenRejected() throws Exception {
            mvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("refreshToken 不能为空")));
        }

        @Test
        @DisplayName("业务异常按错误码映射 HTTP 状态（AUTH_INVALID → 401）")
        void bizExceptionMapsToItsHttpStatus() throws Exception {
            when(authService.login(any(), any()))
                    .thenThrow(new BizException(ErrorCode.AUTH_INVALID, "手机号或密码错误"));

            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800000000\",\"password\":\"wrong-pw\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                    .andExpect(jsonPath("$.message").value("手机号或密码错误"));
        }

        @Test
        @DisplayName("已注册手机号 → 409 PHONE_EXISTS（冲突而非错误）")
        void conflictMapsTo409() throws Exception {
            when(authService.register(any()))
                    .thenThrow(new BizException(ErrorCode.PHONE_EXISTS));

            mvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800000000\",\"password\":\"secret123\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PHONE_EXISTS"));
        }
    }
}
