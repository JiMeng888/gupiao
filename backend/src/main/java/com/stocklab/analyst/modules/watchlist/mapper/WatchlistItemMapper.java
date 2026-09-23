package com.stocklab.analyst.modules.watchlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自选股条目 Mapper。
 */
@Mapper
public interface WatchlistItemMapper extends BaseMapper<WatchlistItem> {
}
