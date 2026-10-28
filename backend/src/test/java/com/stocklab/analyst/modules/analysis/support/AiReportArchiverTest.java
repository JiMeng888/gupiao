package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.config.properties.AnalysisProperties;
import com.stocklab.analyst.modules.analysis.dto.AnalysisReport;
import com.stocklab.analyst.modules.analysis.entity.AiReport;
import com.stocklab.analyst.modules.analysis.mapper.AiReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiReportArchiver} 单测。
 *
 * <p>这个类承担两条「不报错但会误导用户」的策略，本类把它们钉住：
 * <ol>
 *   <li><b>存档有时效</b>。报告正文是自然语言，写着「最新价 1685.00」——
 *       生成那一刻的价格被固化成了文字。没有时效窗口时，三天后的读接口
 *       仍然返回这份自称「最新」的报告。窗口条件必须真的传到 SQL 里，
 *       所以这里断言的是<b>传给 Mapper 的时间下限</b>，不是「返回了什么」。</li>
 *   <li><b>规则化报告不入档</b>。它零成本（纯本地计算），存档不但省不到钱，
 *       还会造出「配好 Key 后读接口仍返回旧规则报告」这个极难自查的状态。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiReportArchiverTest {

    private static final String CODE = "600519";

    @Mock
    private AiReportMapper aiReportMapper;

    private ObjectMapper objectMapper;
    private AnalysisProperties properties;
    private AiReportArchiver archiver;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new AnalysisProperties(Duration.ofHours(6), Duration.ofDays(3), Duration.ofHours(6));
        archiver = new AiReportArchiver(aiReportMapper, objectMapper, properties);
    }

    private AnalysisReport report(String model) {
        return new AnalysisReport(
                CODE, "贵州茅台", "2026-10-16T15:00:00+08:00", "最新价 1685.00，当日 +1.23%",
                List.of("看多"), List.of("看空"), List.of("风险"), "估值说明", "操作提示",
                model, false, List.of("实时报价"), null);
    }

    @Nested
    @DisplayName("读取存档的时效窗口")
    class ArchiveFreshness {

        @Test
        @DisplayName("时间下限按配置回溯：默认 6 小时")
        void passesCutoffDerivedFromConfiguredMaxAge() {
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong())).thenReturn(null);

            Instant before = Instant.now().minus(Duration.ofHours(6));
            archiver.loadLatest(CODE);
            Instant after = Instant.now().minus(Duration.ofHours(6));

            ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
            verify(aiReportMapper).selectLatestByCode(eq(CODE), cutoff.capture());

            // 允许执行耗时的误差：下限应落在「调用前算」与「调用后算」之间
            assertThat(cutoff.getValue())
                    .as("存档时效必须真的传给 SQL，否则等于没有时效")
                    .isBetween(before.getEpochSecond() - 1, after.getEpochSecond() + 1);
        }

        @Test
        @DisplayName("关闭时效时传 0：所有历史行都满足 created_at >= 0")
        void disabledFreshnessPassesZero() {
            AnalysisProperties noLimit =
                    new AnalysisProperties(Duration.ZERO, Duration.ofDays(3), Duration.ofHours(6));
            AiReportArchiver unlimited = new AiReportArchiver(aiReportMapper, objectMapper, noLimit);
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong())).thenReturn(null);

            unlimited.loadLatest(CODE);

            verify(aiReportMapper).selectLatestByCode(CODE, 0L);
        }

        @Test
        @DisplayName("过期存档被 SQL 过滤掉（Mapper 返回 null）时按「无存档」处理，不是报错")
        void expiredArchiveBehavesLikeMissing() {
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong())).thenReturn(null);

            assertThat(archiver.loadLatest(CODE)).isEmpty();
        }

        @Test
        @DisplayName("命中存档时打上 fromArchive 标记，让前端能区分「刚生成」与「读到的旧结论」")
        void marksArchivedReport() throws Exception {
            AiReport row = new AiReport();
            row.setCode(CODE);
            row.setReportJson(objectMapper.writeValueAsString(report("deepseek-chat")));
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong())).thenReturn(row);

            Optional<AnalysisReport> loaded = archiver.loadLatest(CODE);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().fromArchive()).isTrue();
        }

        @Test
        @DisplayName("存档 JSON 反序列化失败时当作没有存档，不让读接口 500")
        void brokenArchiveIsTreatedAsMissing() {
            AiReport row = new AiReport();
            row.setCode(CODE);
            // 用「根本无法解析成对象」的报文，而不是「缺字段的合法 JSON」：
            // 后者能否解析取决于 ObjectMapper 对未知字段的宽松设置，
            // 那样的断言会随 Jackson 配置变化而失去意义。
            row.setReportJson("{ this is not valid json");
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong())).thenReturn(row);

            assertThatCode(() -> assertThat(archiver.loadLatest(CODE)).isEmpty())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("读库异常也当作没有存档，只记日志")
        void readFailureIsTreatedAsMissing() {
            when(aiReportMapper.selectLatestByCode(anyString(), anyLong()))
                    .thenThrow(new IllegalStateException("连接池耗尽"));

            assertThat(archiver.loadLatest(CODE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("写入存档的策略")
    class ArchivePolicy {

        @Test
        @DisplayName("规则化报告不入档：它零成本，存档只会制造「配好 Key 还在返回旧规则报告」")
        void ruleBasedReportIsNotArchived() {
            archiver.archive(CODE, "user-1", report(RuleBasedReportGenerator.RULE_BASED_MODEL));

            verify(aiReportMapper, never()).insert(any(AiReport.class));
        }

        @Test
        @DisplayName("大模型报告正常入库，模型名一并落库（便于排查「这份报告是谁产出的」）")
        void llmReportIsArchivedWithModel() {
            archiver.archive(CODE, "user-1", report("deepseek-chat"));

            ArgumentCaptor<AiReport> captor = ArgumentCaptor.forClass(AiReport.class);
            verify(aiReportMapper).insert(captor.capture());

            AiReport saved = captor.getValue();
            assertThat(saved.getCode()).isEqualTo(CODE);
            assertThat(saved.getUserId()).isEqualTo("user-1");
            assertThat(saved.getModel()).isEqualTo("deepseek-chat");
            assertThat(saved.getReportJson()).contains("最新价 1685.00");
        }

        @Test
        @DisplayName("写库失败只记日志：报告已经生成好了，不能让归档抖动把结果丢掉")
        void writeFailureDoesNotPropagate() {
            when(aiReportMapper.insert(any(AiReport.class)))
                    .thenThrow(new IllegalStateException("表空间不足"));

            assertThatCode(() -> archiver.archive(CODE, null, report("qwen-plus")))
                    .doesNotThrowAnyException();
        }
    }
}
