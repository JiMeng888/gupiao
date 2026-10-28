package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.config.properties.AnalysisProperties;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.entity.AiReport;
import com.stocklab.analyst.modules.analysis.mapper.AiReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AI 报告存档。
 *
 * <h2>它解决的是一笔真金白银的问题</h2>
 * 一次大模型调用是要计费的。原 FastAPI 版本每次请求都重新生成，
 * 连语义上是「读接口」的 {@code GET /analysis/{code}} 也会触发一次新的模型调用。
 * 这里把「读写存档」收在一处，让上层能按语义区分：读接口优先返回存档，
 * 写接口才显式重新生成。
 *
 * <h2>存档是有时效的（相对首版的关键修正）</h2>
 * 首版 {@code loadLatest} 取的是「最新一条」，不带任何时间条件，
 * 于是有两类问题：
 * <ol>
 *   <li><b>报告里的价格是死的，措辞却是「最新」。</b>正文由
 *       {@code RuleBasedReportGenerator} 拼出「最新价 1685.00，当日 +1.23%」，
 *       原样存进 {@code report_json}。三天后再 GET，页面仍显示「最新价 1685.00」，
 *       而报告本身没有任何「数据已过期」的标注。</li>
 *   <li><b>规则化降级报告会被永久固化。</b>没配 Key 时生成的
 *       {@code model=rule-based} 报告也会入库，之后即使配好了 Key，
 *       读接口仍然返回那份旧报告，必须用户手动点「重新生成」才换得掉——
 *       对「上了 Key 但结果还是规则化」的排查者来说极难定位。</li>
 * </ol>
 * 现在的对策是把两件事都收在本类里：读取按 {@code app.analysis.archive-max-age}
 * 限定时效，写入则直接跳过规则化报告（见 {@link #archive}）。
 *
 * <h2>一个贯穿全类的原则：存档失败不影响主流程</h2>
 * 读失败当作没有存档（重新生成即可），写失败只记日志（报告已经生成好了）。
 * 报告结构在迭代中会演进，历史行可能不再兼容，此时正确行为是
 * 「当作没有存档」，而不是让整个接口 500。
 */
@Component
public class AiReportArchiver {

    private static final Logger log = LoggerFactory.getLogger(AiReportArchiver.class);

    private final AiReportMapper aiReportMapper;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties properties;

    public AiReportArchiver(AiReportMapper aiReportMapper,
                            ObjectMapper objectMapper,
                            AnalysisProperties properties) {
        this.aiReportMapper = aiReportMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 取时效内的最新存档；不存在、已过期或读取失败时返回空。
     *
     * <p>「返回空」对调用方的语义是「重新生成一份」——这一点很重要：
     * 过期与缺失走同一条路径，调用方不需要区分。
     */
    public Optional<AnalysisReport> loadLatest(String code) {
        long minCreatedAt = earliestAcceptableEpochSecond();
        AiReport row;
        try {
            row = aiReportMapper.selectLatestByCode(code, minCreatedAt);
        } catch (RuntimeException ex) {
            log.warn("读取最新报告失败 code={}：{}", code, ex.getMessage());
            return Optional.empty();
        }
        if (row == null || row.getReportJson() == null || row.getReportJson().isBlank()) {
            return Optional.empty();
        }
        AnalysisReport parsed = deserialize(row.getReportJson(), code);
        return parsed == null ? Optional.empty() : Optional.of(withArchiveFlag(parsed, true));
    }

    /**
     * 计算存档的「最早可接受生成时间」。
     *
     * @return epoch 秒；不限时效时返回 0（所有历史行都满足 {@code created_at >= 0}）
     */
    private long earliestAcceptableEpochSecond() {
        if (!properties.archiveMaxAgeEnabled()) {
            return 0L;
        }
        return Instant.now().minus(properties.archiveMaxAge()).getEpochSecond();
    }

    /** 历史报告列表（按时间倒序）；查询失败时返回空列表而非抛错。 */
    public List<AnalysisReport> selectHistory(String code, int limit) {
        List<AiReport> rows;
        try {
            rows = aiReportMapper.selectHistoryByCode(code, limit);
        } catch (RuntimeException ex) {
            log.warn("查询历史报告失败 code={}：{}", code, ex.getMessage());
            return List.of();
        }
        List<AnalysisReport> result = new ArrayList<>();
        for (AiReport row : rows) {
            AnalysisReport parsed = deserialize(row.getReportJson(), code);
            if (parsed != null) {
                result.add(withArchiveFlag(parsed, true));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 反序列化存档报告。
     *
     * <p>失败必须吞掉并返回 {@code null}：让调用方「当作没有存档，重新生成」。
     */
    private AnalysisReport deserialize(String json, String code) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AnalysisReport.class);
        } catch (RuntimeException ex) {
            log.warn("历史报告反序列化失败 code={}，将忽略该条记录：{}", code, ex.getMessage());
            return null;
        }
    }

    /**
     * 归档一份报告；失败只记日志，不向调用方抛出。
     *
     * <p><b>规则化报告不入库</b>。存档的唯一价值是「省下一次大模型调用」，
     * 而 {@code rule-based} 报告是纯本地计算，生成它不花钱、不耗时
     * （就是几次算术与字符串拼接）。把它存下来不但省不到成本，
     * 反而制造出「配好 Key 之后读接口仍返回旧规则报告」这个极难自查的状态。
     * 不存，读接口每次重算一份，结果同样稳定、且永远反映当前数据。
     *
     * <p>注意判定放在这里而不是放在调用方：写入路径目前有两条
     * （同步的 {@code AnalysisService} 与异步的 {@code AnalysisTaskExecutor}），
     * 放在调用方就要写两遍，将来加第三条路径时一定会漏。
     */
    public void archive(String code, String userId, AnalysisReport report) {
        if (RuleBasedReportGenerator.RULE_BASED_MODEL.equals(report.model())) {
            log.debug("规则化报告不入存档 code={}（无成本，无需复用）", code);
            return;
        }
        try {
            AiReport entity = new AiReport();
            entity.setUserId(userId);
            entity.setCode(code);
            entity.setModel(report.model());
            entity.setReportJson(objectMapper.writeValueAsString(report));
            aiReportMapper.insert(entity);
        } catch (RuntimeException ex) {
            // 归档失败不该影响已经生成好的报告
            log.warn("AI 报告归档失败 code={}：{}", code, ex.getMessage());
        }
    }

    /**
     * 拷贝报告并改写 {@code fromArchive} 标记。
     *
     * <p>record 是不可变的，因此只能重建。这里显式列出每个字段而不用反射，
     * 好处是未来新增字段时编译器会强制你在这里做决定。
     */
    static AnalysisReport withArchiveFlag(AnalysisReport r, boolean fromArchive) {
        if (r.fromArchive() == fromArchive) {
            return r;
        }
        return new AnalysisReport(
                r.code(), r.name(), r.generatedAt(), r.thesis(),
                r.bullCase(), r.bearCase(), r.risks(),
                r.valuationNote(), r.actionHint(), r.model(),
                fromArchive, r.dataBasis(), r.notice());
    }
}
