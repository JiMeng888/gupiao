package com.stocklab.analyst.config;

import com.stocklab.analyst.config.properties.LlmProperties;
import com.stocklab.analyst.config.properties.MarketDataProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * HTTP 客户端装配。
 *
 * <p>用 Spring 的 {@code RestClient}（Framework 6.1+ 引入的同步客户端）
 * 而不是 {@code RestTemplate}：RestClient 是声明式流式 API，且不再需要
 * 手工配置消息转换器列表，是当前官方推荐的同步调用方式。
 *
 * <p>底层用 JDK 自带的 {@code HttpClient}（{@code JdkClientHttpRequestFactory}），
 * 它默认支持 HTTP/2 与连接池复用，比 {@code SimpleClientHttpRequestFactory}
 * （每请求一个 {@code HttpURLConnection}）在高频调用下表现更好。
 *
 * <p><b>超时是必设项</b>：不设读超时时，上游接口挂起会让调用线程无限等待，
 * Tomcat 线程池会被逐渐耗尽，最终整个服务不可用。这里按外部数据源
 * 分别设置超时，避免一个慢接口拖垮全局。
 */
@Configuration
public class RestClientConfig {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final String EASTMONEY_REFERER = "https://finance.eastmoney.com/";

    private static final String SINA_REFERER = "https://finance.sina.com.cn/";

    /** 行情数据源客户端。 */
    @Bean("marketRestClient")
    public RestClient marketRestClient(MarketDataProperties properties) {
        return buildClient(properties.timeout(), EASTMONEY_REFERER);
    }

    /**
     * 财经资讯客户端。
     *
     * <p>单独一个 Bean，而不是复用行情客户端：两个源不同域（新浪 feed vs 东方财富 push2），
     * 共用一个实例会把 A 站的 Referer 发给 B 站。虽然实测新浪的 feed 接口不校验 Referer，
     * 但把「给谁发就带谁的 Referer」写清楚，比依赖对方的宽松策略更可靠。
     */
    @Bean("newsRestClient")
    public RestClient newsRestClient(MarketDataProperties properties) {
        return buildClient(properties.timeout(), SINA_REFERER);
    }

    /**
     * 腾讯行情客户端（备用真实源）。
     *
     * <p>独立于行情客户端的原因与资讯客户端相同：qt.gtimg.cn 是与东财
     * 完全不同的站点，不该把东财的 Referer 发给它。虽然实测腾讯行情
     * 接口不校验 Referer，但「给谁发就带谁的 Referer（或不带）」
     * 是比依赖对方宽松策略更可靠的做法。
     */
    @Bean("tencentRestClient")
    public RestClient tencentRestClient(MarketDataProperties properties) {
        return buildClient(properties.timeout(), null);
    }

    /**
     * 大模型客户端。
     *
     * <p>超时单独配置：大模型生成长文本通常需要 10~40 秒，
     * 沿用行情源的 6 秒超时会导致所有分析请求必然超时。
     */
    @Bean("llmRestClient")
    public RestClient llmRestClient(LlmProperties properties) {
        return buildClient(properties.timeout(), null);
    }

    /**
     * @param referer 传给对方的 Referer。<b>不要传 {@code null} 之外的占位值</b>：
     *                部分财经接口会校验 Referer，缺失时返回空数据而非报错，
     *                这种「静默失败」最难排查。大模型 API 则不需要 Referer。
     */
    private RestClient buildClient(java.time.Duration timeout, String referer) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        if (referer != null) {
            builder.defaultHeader(HttpHeaders.REFERER, referer);
        }
        return builder.build();
    }
}
