package com.stocklab.analyst.news;

import com.stocklab.analyst.common.constant.CacheNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 新浪财经资讯源客户端。
 *
 * <p>选新浪 {@code feed.mix.sina.com.cn} 的理由：内部 JSON 接口（未获新浪授权）、无需鉴权、
 * 无 Cookie 与防爬拦截、结构稳定（已运行十年以上），覆盖财经要闻 / A 股 /
 * 港股 / 美股 / 全球市场五个频道。
 *
 * <h2>为什么失败时返回空列表而不是抛异常</h2>
 * 资讯是「锦上添花」型内容：拿不到新闻时页面应该是「暂无资讯」，
 * 而不是整页报错。这与行情、财务数据的处理策略不同——
 * 那两类数据缺失会让用户形成错误判断，必须显式暴露失败。
 * 因此这里吞掉上游异常、记录日志并打指标，由调用方原样返回空列表。
 *
 * <h2>缓存</h2>
 * 缓存粒度是「频道」而不是「频道 + 条数」：上游一次固定取 {@value #FETCH_SIZE} 条，
 * 条数裁剪在业务层完成。这样无论前端要 10 条还是 30 条，都只打一次上游。
 * TTL 由 {@code CacheConfig} 统一配置（60 秒），与原 FastAPI 实现一致。
 */
@Component
public class SinaNewsClient {

    private static final Logger log = LoggerFactory.getLogger(SinaNewsClient.class);

    private static final String FEED_URL =
            "https://feed.mix.sina.com.cn/api/roll/get?pageid=%s&lid=%s&num=%d&versionNumber=1.2.4&page=1";

    /** 单频道固定拉取条数。 */
    private static final int FETCH_SIZE = 30;

    /** 单条新闻保留的最大标签数（含频道名）。 */
    private static final int MAX_TAGS = 5;

    private static final String DEFAULT_MEDIA = "新浪财经";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(SHANGHAI);

    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,\\s]+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SinaNewsClient(@Qualifier("newsRestClient") RestClient restClient,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 拉取单个频道的最新资讯。
     *
     * <p>返回<b>不可变</b>列表：缓存直接持有这个对象引用，
     * 若返回 {@code ArrayList}，任何调用方一次 {@code add} 就会污染缓存里所有的后续读。
     *
     * @return 按发布时间倒序的列表；上游失败时为空列表
     */
    @Cacheable(cacheNames = CacheNames.NEWS, key = "'ch:' + #channel.name()", sync = true)
    public List<NewsItem> fetchChannel(NewsChannel channel) {
        String url = FEED_URL.formatted(channel.pageid(), channel.lid(), FETCH_SIZE);
        JsonNode root;
        try {
            String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("上游返回空响应");
            }
            root = objectMapper.readTree(body);
        } catch (RuntimeException ex) {
            markFailure(channel, ex);
            return List.of();
        }

        JsonNode data = root.path("result").path("data");
        if (!data.isArray()) {
            markFailure(channel, new IllegalStateException("响应结构异常，缺少 result.data 数组"));
            return List.of();
        }

        List<NewsItem> items = new ArrayList<>(data.size());
        for (JsonNode raw : data) {
            NewsItem item = normalize(raw, channel);
            if (item != null) {
                items.add(item);
            }
        }
        // 上游顺序大致是新的在前，但不保证；显式排序避免「置顶稿」把顺序打乱
        items.sort(Comparator.comparingLong(NewsItem::ctime).reversed());
        return List.copyOf(items);
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * 把上游条目映射为 {@link NewsItem}。
     *
     * <p>字段全部做缺失兜底。上游的字段命名在 PC 与 WAP 两套里不一致
     * （{@code url} / {@code wapurl}、{@code intro} / {@code wapsummary}），
     * 任一为空都是正常情况，不能当成错误。
     *
     * @return 标题为空的条目返回 {@code null}，由调用方跳过
     */
    private NewsItem normalize(JsonNode raw, NewsChannel channel) {
        String title = cleanText(readText(raw, "title"));
        if (title.isEmpty()) {
            // 标题是唯一必填字段：没有标题的条目在列表里无法展示也无法点击
            return null;
        }

        String url = readText(raw, "url");
        if (url.isEmpty()) {
            url = readText(raw, "wapurl");
        }

        String intro = cleanText(readText(raw, "intro"));
        if (intro.isEmpty()) {
            intro = cleanText(readText(raw, "wapsummary"));
        }

        String media = cleanText(readText(raw, "media_name"));
        if (media.isEmpty()) {
            media = DEFAULT_MEDIA;
        }

        long ctime = readLong(raw, "ctime", readLong(raw, "intime", 0L));

        String id = readText(raw, "docid");
        if (id.isEmpty()) {
            id = ctime + "-" + title;
        }

        return new NewsItem(
                id,
                title,
                url,
                intro,
                media,
                buildTags(raw, channel),
                ctime,
                ctime > 0 ? TIME_FORMAT.format(Instant.ofEpochSecond(ctime)) : "",
                channel.displayName());
    }

    /**
     * 组装标签：频道名固定排第一，随后是上游 keywords。
     *
     * <p>上游 keywords 用逗号或空格分隔，且可能为空、可能有重复。
     */
    private List<String> buildTags(JsonNode raw, NewsChannel channel) {
        List<String> tags = new ArrayList<>(MAX_TAGS);
        tags.add(channel.displayName());

        String keywords = readText(raw, "keywords");
        if (!keywords.isEmpty()) {
            for (String token : TAG_SEPARATOR.split(keywords)) {
                String tag = token.trim();
                if (!tag.isEmpty() && !tags.contains(tag) && tags.size() < MAX_TAGS) {
                    tags.add(tag);
                }
            }
        }
        return List.copyOf(tags);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读取字符串字段。
     *
     * <p>上游部分字段在不同栏目下会以数字下发（实测 {@code intime} 是字符串、
     * 少数栏目把 {@code ctime} 给成数字），因此对数字节点也做兼容。
     *
     * <p><b>不使用 {@code asString()}</b>：Jackson 3 的 {@code asString()} 对非字符串
     * 节点会直接抛异常（这是相对 Jackson 2 的 {@code asText()} 的行为变更），
     * 用一个宽容的读取器比在每个调用点判断类型更不容易出错。
     */
    private static String readText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isString()) {
            return value.asString().trim();
        }
        if (value.isNumber()) {
            return value.toString();
        }
        return "";
    }

    private static long readLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isString()) {
            String text = value.asString().trim();
            if (!text.isEmpty()) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    // 上游偶尔会给非数字的时间字段，走兜底比抛异常更合适
                }
            }
        }
        return fallback;
    }

    /**
     * 清理文本。
     *
     * <p>上游摘要里会混入 {@code &nbsp;} 这类 HTML 实体与多余空白。
     * 不做清理时前端会直接显示成 {@code &nbsp;}，看起来像乱码。
     * 这里只处理最常见的几个实体——完整实现该用 HTML 解析库，
     * 但为了几个实体引一个解析器不值得。
     */
    private static String cleanText(String text) {
        if (text.isEmpty()) {
            return "";
        }
        String cleaned = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        // 连续空白折叠成单个空格（上游摘要里常有多余换行与缩进）
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private void markFailure(NewsChannel channel, RuntimeException ex) {
        meterRegistry.counter("app.news.fetch.failure", "channel", channel.id()).increment();
        log.warn("资讯拉取失败 channel={} pageid={} lid={}：{}",
                channel.id(), channel.pageid(), channel.lid(), ex.toString());
    }

    /** 供监控端查看的频道元信息。 */
    public Map<String, String> channelIntrospection() {
        Map<String, String> map = new LinkedHashMap<>();
        for (NewsChannel channel : NewsChannel.values()) {
            map.put(channel.id(), channel.pageid() + "/" + channel.lid() + "/" + channel.displayName());
        }
        return map;
    }
}
