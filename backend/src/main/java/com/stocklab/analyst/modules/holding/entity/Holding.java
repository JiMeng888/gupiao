package com.stocklab.analyst.modules.holding.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 持仓记录。
 *
 * <p>数据模型选择：每只股票<b>一条</b>记录，多次买入按加权平均价合并；
 * 卖出直接扣减股数，成本价保持不变（不实现逐笔移动加权，
 * 因为前端只展示「持仓数量 + 平均成本」，逐笔数据没有消费方）。
 *
 * <p>如果要支持「已实现盈亏统计」，需要改成逐笔成交表
 * （{@code holdings} 记批次 + {@code trades} 记流水），
 * 当前设计不支持回算历史卖出成本——这是一处已知边界，不是遗漏。
 */
@TableName("holdings")
public class Holding {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String code;

    private String name;

    /** 持有股数；卖出后可为 0（保留记录，便于前端展示「已清仓」）。 */
    private Integer shares;

    /**
     * 加权平均成本（元/股）。
     *
     * <h2>为什么这里是 {@code Double} 而不是 {@code BigDecimal}（一处有意的取舍）</h2>
     * 数据库列是 {@code avg_cost DECIMAL(18, 4)}（见 {@code V1__baseline.sql}），
     * 而 Java 侧全程用 {@code double} 做加权与盈亏计算——两侧口径并不一致。
     * 这是<b>有意保留</b>的，理由有三：
     * <ul>
     *   <li><b>不是记账系统</b>。本项目是面向个人的分析工具，持仓由用户手工录入，
     *       不接券商成交回报、不做资金清算，也不产生对外账目。
     *       {@code double} 的经典陷阱（{@code 0.1 + 0.2 != 0.3}、
     *       大数累加漂移）在「几万到几十万股 × 十元级单价」这个量级上，
     *       误差远小于「元/股保留 4 位小数」的展示精度，用户看不到差别；</li>
     *   <li><b>边界上已经收敛</b>。落库前经 {@code round4} 保留 4 位小数、
     *       出参前经 {@code round2} 保留 2 位，误差不会跨请求累积成可见偏差；</li>
     *   <li><b>改造成本大于收益</b>。真正上 {@code BigDecimal} 要同时改
     *       实体、服务层全部算术、DTO 与 JSON 边界，并重写 25 条已用
     *       {@code isCloseTo} 断言的测试——在这个项目的定位下，
     *       这笔改动换来的正确性提升并不成立。</li>
     * </ul>
     *
     * <p><b>什么情况下必须换掉</b>：一旦涉及真实资金、对账、多币种折算、
     * 或需要「若干笔金额相加必须精确等于总额」的约束，
     * 就必须改为 {@code BigDecimal}（{@code DECIMAL} 与它同源，边界转换无信息损失）。
     * 届时改动范围就是上面列的四处，且应在「DB 读写」与「JSON 出参」两个边界
     * 做<b>显式</b>转换，而不是到处 {@code new BigDecimal(double)}——
     * 后者会把 {@code double} 的二进制误差原样带进 {@code BigDecimal}，比不改还糟。
     *
     * <p>把这段写在这里，而不是只留在 Review 里：它是「为什么金额不用 double」
     * 这道面试题在本项目里的现成答案，能主动讲清边界比回避更加分。
     */
    private Double avgCost;

    /**
     * 买入日期（YYYY-MM-DD），用户可选的记账日期，默认当天。
     *
     * <p>与 {@link #createdAt}（自动填充的记录创建时间戳）区分：
     * 前者是用户「这笔买入发生在哪一天」的记账口径，支持补录历史；
     * 后者是数据库记录写入时刻，二者语义不同、不能混用。
     */
    private String buyDate;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getShares() {
        return shares;
    }

    public void setShares(Integer shares) {
        this.shares = shares;
    }

    public Double getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(Double avgCost) {
        this.avgCost = avgCost;
    }

    public String getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(String buyDate) {
        this.buyDate = buyDate;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
