package com.stocklab.analyst.modules.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.analysis.entity.AiReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 报告 Mapper。
 *
 * <p><b>为什么这里要手写 SQL</b>：{@code ai_reports} 在原 FastAPI 版本里是一张
 * <b>死表</b>——ORM 模型定义了它，但没有任何代码写入或读取，每次分析都是现调大模型。
 * 本实现真正把它用起来：分析结果归档，重复查看同一只标的时不再产生费用。
 * 归档查询需要「按代码取最新一条」与「取历史记录」，这两个语义用
 * {@code QueryWrapper} 表达不如直接写 SQL 清楚，且能顺便把索引意图写进注释。
 */
@Mapper
public interface AiReportMapper extends BaseMapper<AiReport> {

    /**
     * 取某只标的的某时间点之后生成的最新一份报告。
     *
     * <p>不带用户条件：同一只股票的分析结论对所有用户是一样的，
     * 按标的复用可以让一次大模型调用的成本服务全部用户。
     * 报告正文不含任何用户数据，因此共享不涉及隐私。
     * 表上建了 {@code idx_code_created (code, created_at)} 支撑这个查询。
     *
     * <p><b>{@code minCreatedAt} 不是可选项</b>。报告正文是自然语言，
     * 里面写着「最新价 1685.00，当日 +1.23%」——把生成那一刻的价格固化成了文字。
     * 没有时间窗时这份报告会被无限期当作「最新结论」返回，
     * 用户看到的是一句自称「最新」的历史数据。窗口放在 SQL 里而不是查出来再判断，
     * 是为了让 {@code idx_code_created} 真正生效（取出来再过滤等于回表全扫）。
     *
     * @param code         股票代码（6 位数字）
     * @param minCreatedAt 最早可接受的生成时间（epoch 秒）；传 0 表示不限
     */
    @Select("""
            SELECT * FROM ai_reports
            WHERE code = #{code} AND created_at >= #{minCreatedAt}
            ORDER BY created_at DESC LIMIT 1
            """)
    AiReport selectLatestByCode(@Param("code") String code,
                                @Param("minCreatedAt") long minCreatedAt);

    /** 取某只标的历史报告列表，按时间倒序。 */
    @Select("SELECT * FROM ai_reports WHERE code = #{code} ORDER BY created_at DESC LIMIT #{limit}")
    List<AiReport> selectHistoryByCode(@Param("code") String code, @Param("limit") int limit);
}
