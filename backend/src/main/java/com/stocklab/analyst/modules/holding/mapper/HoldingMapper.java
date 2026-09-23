package com.stocklab.analyst.modules.holding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stocklab.analyst.modules.holding.entity.Holding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 持仓 Mapper。
 */
@Mapper
public interface HoldingMapper extends BaseMapper<Holding> {

    /**
     * 加行级排他锁读取持仓。
     *
     * <p><b>为什么需要它</b>：买入与卖出都是「读出现有股数与成本 → 计算 → 写回」
     * 的复合操作。不加锁时两个并发请求（例如用户手快点了两次买入，
     * 或前端重试触发了重复提交）会读到同一份旧值，各自算出结果后写回，
     * 后写入的覆盖先写入的，导致<b>一笔买入凭空消失</b>（经典的丢失更新）。
     *
     * <p>{@code FOR UPDATE} 让第二个事务阻塞到第一个提交，
     * 从而拿到最新值再计算。必须配合 {@code @Transactional} 使用——
     * 没有事务包裹时，语句一执行完锁就释放了，起不到任何保护作用。
     *
     * <p>代价是并发写入同一只股票会被串行化。对本场景（单用户操作自己的持仓）
     * 完全可以接受；如果是高并发撮合场景，应改为「追加成交流水 + 异步聚合」
     * 的模型，而不是在余额上直接做读改写。
     */
    @Select("SELECT id, user_id, code, name, shares, avg_cost, created_at, updated_at "
            + "FROM holdings WHERE user_id = #{userId} AND code = #{code} FOR UPDATE")
    Holding selectByUserAndCodeForUpdate(@Param("userId") String userId,
                                         @Param("code") String code);

    /**
     * 无锁查询（只读场景使用，避免不必要地持有行锁）。
     */
    @Select("SELECT id, user_id, code, name, shares, avg_cost, created_at, updated_at "
            + "FROM holdings WHERE user_id = #{userId} AND code = #{code}")
    Holding selectByUserAndCode(@Param("userId") String userId,
                                @Param("code") String code);
}
