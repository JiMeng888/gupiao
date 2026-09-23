package com.stocklab.analyst.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger UI）文档装配。
 *
 * <p>声明 Bearer 安全方案后，Swagger UI 上会多出「Authorize」按钮，
 * 填入 Access Token 即可直接调试需要登录的接口，
 * 不必再用 curl 手工拼 {@code Authorization} 头。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI stockAnalystOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("A 股行情分析平台 API")
                        .version("1.0.0")
                        .description("""
                                面向个人投资者的 A 股行情分析平台后端接口。
                                
                                功能覆盖：行情报价 / K 线 / 技术指标 / 财务基本面 / AI 深度分析 /
                                自选股 / 持仓盈亏 / 财经资讯 / WebSocket 实时推送。
                                
                                鉴权方式：先调用 /api/v1/auth/login 获取 accessToken，
                                再点击右上角 Authorize 填入（无需加 Bearer 前缀）。
                                """)
                        .contact(new Contact().name("stock-analyst"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access Token，由 /api/v1/auth/login 签发")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
