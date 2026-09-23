package com.stocklab.analyst.modules.watchlist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 自选股分组。
 *
 * <p>{@code userId} 是数据隔离字段：所有查询都必须带上它，
 * 否则会出现「A 用户能看到并删除 B 用户分组」的越权。
 */
@TableName("watchlist_groups")
public class WatchlistGroup {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String name;

    private Integer sortOrder;

    /** 父分组 ID，支持两级分组；为 null 表示顶级分组。 */
    private String parentId;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
}
