package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.CorsProperties;
import com.stocklab.analyst.security.CurrentUserIdArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 装配：跨域策略 + 自定义参数解析器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;
    private final CorsProperties corsProperties;

    public WebMvcConfig(CurrentUserIdArgumentResolver currentUserIdArgumentResolver,
                        CorsProperties corsProperties) {
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
        this.corsProperties = corsProperties;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }

    /**
     * 跨域配置。
     *
     * <p>这里使用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}：
     * 按 CORS 规范，当 {@code allowCredentials=true} 时响应头
     * {@code Access-Control-Allow-Origin} 不允许为 {@code *}，浏览器会直接拒绝。
     * {@code allowedOriginPatterns} 会按请求的 Origin 回显具体值，既能匹配通配符
     * 又满足携带凭证的要求。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsProperties.allowedOriginPatterns().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-RateLimit-Remaining")
                .allowCredentials(corsProperties.allowCredentials())
                .maxAge(corsProperties.maxAgeSeconds());

        // WebSocket 握手端点：部分浏览器在跨域场景会对握手请求做预检
        registry.addMapping("/ws/**")
                .allowedOriginPatterns(corsProperties.allowedOriginPatterns().toArray(String[]::new))
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(corsProperties.allowCredentials())
                .maxAge(corsProperties.maxAgeSeconds());
    }
}
