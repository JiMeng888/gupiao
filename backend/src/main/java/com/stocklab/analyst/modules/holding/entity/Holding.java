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

    /** 加权平均成本（元/股）。 */
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
