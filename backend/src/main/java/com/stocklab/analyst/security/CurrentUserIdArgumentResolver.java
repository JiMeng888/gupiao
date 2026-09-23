package com.stocklab.analyst.security;

import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link CurrentUserId} 与 {@link CurrentSessionId} 标注的参数解析为
 * 请求属性中的当前用户 ID / 会话 ID。
 *
 * <p>数据来源是 {@link JwtAuthenticationFilter}，本解析器只负责读取、
 * 「必需性」校验与错误码翻译，不重复解析令牌。
 *
 * <p><b>错误码翻译</b>：过滤器在令牌解析失败时会把具体的
 * {@link BizException}（{@code AUTH_EXPIRED} / {@code AUTH_INVALID}）
 * 放进请求属性。本解析器在参数必需且取不到值时优先抛出那个异常，
 * 而不是一律抛 {@code AUTH_REQUIRED}。原因是前端的处理策略完全不同：
 * <ul>
 *   <li>{@code AUTH_EXPIRED} → 令牌自然过期，可用 refreshToken 静默续期，用户无感；</li>
 *   <li>{@code AUTH_INVALID} → 令牌被伪造或会话已注销，静默刷新没有意义，必须跳登录页；</li>
 *   <li>{@code AUTH_REQUIRED} → 压根没带令牌，按未登录处理。</li>
 * </ul>
 * 若统一返回 {@code AUTH_REQUIRED}，前端只能一律跳登录页，
 * 每 30 分钟把用户踢出去一次。
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!String.class.isAssignableFrom(parameter.getParameterType())) {
            return false;
        }
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                || parameter.hasParameterAnnotation(CurrentSessionId.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                 ModelAndViewContainer mavContainer,
                                 NativeWebRequest webRequest,
                                 WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);

        CurrentUserId userIdAnnotation = parameter.getParameterAnnotation(CurrentUserId.class);
        if (userIdAnnotation != null) {
            String userId = request == null
                    ? null
                    : (String) request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);
            if (userId == null && userIdAnnotation.required()) {
                throw failureOrRequired(request);
            }
            return userId;
        }

        CurrentSessionId sessionAnnotation = parameter.getParameterAnnotation(CurrentSessionId.class);
        String sessionId = request == null
                ? null
                : (String) request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE);
        if (sessionId == null && sessionAnnotation.required()) {
            throw failureOrRequired(request);
        }
        return sessionId;
    }

    /**
     * 取不到登录态时的异常：优先还原过滤器记录的真实原因，
     * 没有记录（例如压根没带 Authorization 头）才用 {@code AUTH_REQUIRED}。
     */
    private BizException failureOrRequired(HttpServletRequest request) {
        if (request != null) {
            Object failure = request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_ATTRIBUTE);
            if (failure instanceof BizException biz) {
                return biz;
            }
        }
        return new BizException(ErrorCode.AUTH_REQUIRED);
    }
}
