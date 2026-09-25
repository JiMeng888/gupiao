package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.entity.AiReport;
import com.stocklab.analyst.modules.analysis.mapper.AiReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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

    public AiReportArchiver(AiReportMapper aiReportMapper, ObjectMapper objectMapper) {
        this.aiReportMapper = aiReportMapper;
        this.objectMapper = objectMapper;
    }

    /** 取最新存档；不存在或读取失败时返回空。 */
    public Optional<AnalysisReport> loadLatest(String code) {
        AiReport row;
        try {
            row = aiReportMapper.selectLatestByCode(code);
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

    /** 归档一份报告；失败只记日志，不向调用方抛出。 */
    public void archive(String code, String userId, AnalysisReport report) {
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
