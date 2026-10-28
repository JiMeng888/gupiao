package com.stocklab.analyst.modules.analysis.support;

import com.stocklab.analyst.config.properties.AnalysisProperties;
import com.stocklab.analyst.modules.analysis.mapper.AnalysisTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalysisTaskCleanupJob} 单测。
 *
 * <p>清理任务最容易出的不是「删错了」，而是<b>看起来在跑、其实什么都没删</b>：
 * 时间下限算错、保留期配成 0、异常被吞掉。这些情况在日志里都很安静，
 * 表现要等到半年后「表为什么这么大」才会被发现。所以这里断言的是
 * <b>传给 SQL 的时间下限</b>与<b>关闭开关时确实没执行删除</b>。
 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskCleanupJobTest {

    @Mock
    private AnalysisTaskMapper taskMapper;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private AnalysisTaskCleanupJob jobWith(AnalysisProperties properties) {
        return new AnalysisTaskCleanupJob(taskMapper, properties, meterRegistry);
    }

    private static AnalysisProperties properties(Duration retention) {
        return new AnalysisProperties(Duration.ofHours(6), retention, Duration.ofHours(6));
    }

    @Test
    @DisplayName("时间下限按保留期回溯：默认 3 天前的终态任务才删")
    void purgesWithCutoffDerivedFromRetention() {
        when(taskMapper.deleteTerminalOlderThan(anyLong())).thenReturn(7);

        Instant before = Instant.now().minus(Duration.ofDays(3));
        jobWith(properties(Duration.ofDays(3))).purgeExpiredTasks();
        Instant after = Instant.now().minus(Duration.ofDays(3));

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(taskMapper).deleteTerminalOlderThan(cutoff.capture());
        assertThat(cutoff.getValue())
                .as("保留期必须真的参与计算，否则清理会删掉还在轮询中的任务")
                .isBetween(before.getEpochSecond() - 1, after.getEpochSecond() + 1);
        assertThat(meterRegistry.counter("app.analysis.task.purged").count()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("保留期配成 0 时完全不执行删除（关闭开关要真的关掉，而不是退化成删全部）")
    void disabledRetentionSkipsDeletion() {
        jobWith(properties(Duration.ZERO)).purgeExpiredTasks();

        verify(taskMapper, never()).deleteTerminalOlderThan(anyLong());
    }

    @Test
    @DisplayName("负数保留期同样视为关闭，不会算出「未来时间」把新任务删掉")
    void negativeRetentionSkipsDeletion() {
        jobWith(properties(Duration.ofDays(-1))).purgeExpiredTasks();

        verify(taskMapper, never()).deleteTerminalOlderThan(anyLong());
    }

    @Test
    @DisplayName("没有需要清理的行时不累加指标，也不报错")
    void nothingToPurgeIsQuiet() {
        when(taskMapper.deleteTerminalOlderThan(anyLong())).thenReturn(0);

        jobWith(properties(Duration.ofDays(3))).purgeExpiredTasks();

        assertThat(meterRegistry.counter("app.analysis.task.purged").count()).isZero();
    }

    @Test
    @DisplayName("删除失败只记日志：清理是维护动作，失败不该影响任何功能")
    void deleteFailureDoesNotPropagate() {
        when(taskMapper.deleteTerminalOlderThan(anyLong()))
                .thenThrow(new IllegalStateException("数据库只读"));

        assertThatCode(() -> jobWith(properties(Duration.ofDays(3))).purgeExpiredTasks())
                .doesNotThrowAnyException();
    }
}
