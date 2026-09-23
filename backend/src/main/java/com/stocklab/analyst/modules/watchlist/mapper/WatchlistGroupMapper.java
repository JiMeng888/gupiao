package com.stocklab.analyst.modules.watchlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 自选股分组 Mapper。
 */
@Mapper
public interface WatchlistGroupMapper extends BaseMapper<WatchlistGroup> {

    /**
     * 校验分组是否属于该用户。
     *
     * <p>新增自选股前必须调用：否则用户可以把自己股票加进<b>别人的</b>
     * 分组 ID，造成数据越权写入。这类「对象级授权」漏洞不会因为
     * 前端隐藏了入口就消失，必须服务端校验。
     */
    @Select("SELECT COUNT(1) FROM watchlist_groups WHERE id = #{groupId} AND user_id = #{userId}")
    int countOwnedBy(@Param("groupId") String groupId, @Param("userId") String userId);
}
