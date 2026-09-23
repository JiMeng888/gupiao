package com.stocklab.analyst.modules.watchlist.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stocklab.analyst.common.exception.BizException;
import com.stocklab.analyst.common.exception.ErrorCode;
import com.stocklab.analyst.market.MarketDataProvider;
import com.stocklab.analyst.market.model.Quote;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistGroupView;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemCreateRequest;
import com.stocklab.analyst.modules.watchlist.dto.WatchlistItemView;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistGroup;
import com.stocklab.analyst.modules.watchlist.entity.WatchlistItem;
import com.stocklab.analyst.modules.watchlist.mapper.WatchlistGroupMapper;
import com.stocklab.analyst.modules.watchlist.mapper.WatchlistItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WatchlistService} 单测。
 *
 * <p><b>本类最值得测的不是业务流程，是越权防护。</b>
 * 服务类注释第一句就是「越权防护是本类的第一要务」——所有查询与写入
 * 都必须带 {@code user_id} 条件。这件事用 Mockito 的 {@code any()}
 * <b>根本验证不了</b>：{@code any()} 只关心「调用发生了」，不关心
 * WHERE 子句里到底有没有 {@code user_id}。少写一个 {@code .eq(userId)}，
 * 测试照样全绿，而线上就能读到别人的自选股。
 *
 * <p>所以这里做了两件事：
 * <ol>
 *   <li>手动初始化 MyBatis-Plus 的 {@code TableInfoHelper}，让
 *       {@code LambdaQueryWrapper} 能在没有 Spring 上下文时解析列名；</li>
 *   <li>把 wrapper 捕获下来，断言<b>真实 SQL 片段</b>与<b>运行时参数</b>。</li>
 * </ol>
 *
 * <p>为什么必须手动初始化：MyBatis-Plus 解析 {@code LambdaQueryWrapper}
 * 的列名是<b>延迟</b>的，但一旦调用 {@code getSqlSegment()} 就需要
 * lambda 缓存（由 {@code TableInfoHelper} 填充）。没有它直接抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}——
 * 这也正是项目里其它服务测试只能写 {@code any()} 的原因。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("自选股服务")
class WatchlistServiceTest {

    private static final String USER = "u-1";
    private static final String OTHER_USER = "u-other";

    /**
     * 让 {@code LambdaQueryWrapper} 能在纯 JUnit 环境下解析列名。
     *
     * <p>只初始化本类用到的两个实体。{@code initTableInfo} 内部有缓存，
     * 重复调用是幂等的。
     */
    @BeforeAll
    static void initMyBatisPlusMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WatchlistGroup.class);
        TableInfoHelper.initTableInfo(assistant, WatchlistItem.class);
    }

    @Mock
    private WatchlistGroupMapper groupMapper;

    @Mock
    private WatchlistItemMapper itemMapper;

    @Mock
    private MarketDataProvider marketDataProvider;

    private WatchlistService service;

    @BeforeEach
    void setUp() {
        service = new WatchlistService(groupMapper, itemMapper, marketDataProvider);
    }

    // ==================================================================
    // 断言助手

    /** 取出 wrapper 实际生成的 SQL 片段（会强制求值，从而填充参数表）。 */
    private static String sqlOf(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSegment();
    }

    /** 取出 wrapper 里实际参与 WHERE 的参数值。 */
    private static Collection<Object> paramsOf(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
    }

    @SuppressWarnings("rawtypes")
    private Wrapper<WatchlistGroup> capturedGroupQuery() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(groupMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("rawtypes")
    private Wrapper<WatchlistItem> capturedItemQuery() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectList(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("rawtypes")
    private Wrapper<WatchlistItem> capturedItemCountQuery() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectCount(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("rawtypes")
    private Wrapper<WatchlistItem> capturedItemDeleteQuery() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).delete(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("rawtypes")
    private Wrapper<WatchlistGroup> capturedGroupDeleteQuery() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(groupMapper).delete(captor.capture());
        return captor.getValue();
    }

    /** 让 mock 的 insert 回填主键（真实实现里 ASSIGN_UUID 会这么做）。 */
    private void stubGroupInsertAssigningId(String id) {
        when(groupMapper.insert(any(WatchlistGroup.class))).thenAnswer(inv -> {
            inv.getArgument(0, WatchlistGroup.class).setId(id);
            return 1;
        });
    }

    private void stubItemInsertAssigningId(String id) {
        when(itemMapper.insert(any(WatchlistItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, WatchlistItem.class).setId(id);
            return 1;
        });
    }

    // ==================================================================
    // 构造数据

    private static WatchlistGroup group(String id, String name, int sortOrder) {
        WatchlistGroup g = new WatchlistGroup();
        g.setId(id);
        g.setUserId(USER);
        g.setName(name);
        g.setSortOrder(sortOrder);
        return g;
    }

    private static WatchlistItem item(String id, String groupId, String code) {
        WatchlistItem i = new WatchlistItem();
        i.setId(id);
        i.setUserId(USER);
        i.setGroupId(groupId);
        i.setCode(code);
        i.setSortOrder(0);
        i.setCreatedAt(1_700_000_000L);
        return i;
    }

    private static Quote quote(String code, String name, double price) {
        return new Quote(code, name, "SH", price, 1.0, 0.5,
                price, price, price, price - 1, 0L, 0.0, null, 1_700_000_000L);
    }

    /**
     * 断言调用抛出 {@link BizException} 并返回它。
     *
     * <p>参数类型必须是 {@code ThrowingCallable} 而不是 {@code Runnable}：
     * {@code catchThrowableOfType} 只接受前者。写成 {@code Runnable}
     * 会在编译期报「找不到合适的方法——java.lang.Runnable 无法转换为
     * java.lang.Class&lt;THROWABLE&gt;」，而不是运行期失败。
     */
    private static BizException biz(ThrowingCallable action) {
        return catchThrowableOfType(action, BizException.class);
    }

    // ==================================================================
    @Nested
    @DisplayName("分组列表与懒创建")
    class ListGroups {

        @Test
        @DisplayName("用户没有任何分组时，懒创建一个默认分组并返回它")
        void lazilyCreatesDefaultGroup() {
            WatchlistGroup created = group("g-1", WatchlistService.DEFAULT_GROUP_NAME, 0);
            when(groupMapper.selectList(any()))
                    .thenReturn(List.of())              // 首次：没有分组
                    .thenReturn(List.of(created));      // 建完再查：有 1 个
            stubGroupInsertAssigningId("g-1");
            when(itemMapper.selectList(any())).thenReturn(List.of());

            List<WatchlistGroupView> views = service.listGroups(USER);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).name()).isEqualTo(WatchlistService.DEFAULT_GROUP_NAME);
            assertThat(views.get(0).id()).isEqualTo("g-1");
        }

        @Test
        @DisplayName("懒创建的分组归属正确、排序值为 0")
        void defaultGroupIsOwnedAndOrdered() {
            when(groupMapper.selectList(any()))
                    .thenReturn(List.of())
                    .thenReturn(List.of(group("g-1", WatchlistService.DEFAULT_GROUP_NAME, 0)));
            stubGroupInsertAssigningId("g-1");
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listGroups(USER);

            ArgumentCaptor<WatchlistGroup> captor = ArgumentCaptor.forClass(WatchlistGroup.class);
            verify(groupMapper).insert(captor.capture());
            assertThat(captor.getValue().getUserId())
                    .as("新建的分组必须挂在当前用户名下，否则下次查询会查不到、并反复重建")
                    .isEqualTo(USER);
            assertThat(captor.getValue().getSortOrder()).isZero();
        }

        @Test
        @DisplayName("已有分组时不创建（不产生冗余写）")
        void doesNotCreateWhenGroupsExist() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-1", "我的自选", 0)));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listGroups(USER);

            verify(groupMapper, never()).insert(any(WatchlistGroup.class));
        }

        /**
         * 并发首次访问：两个请求同时发现「没有分组」，同时尝试创建。
         *
         * <p>靠唯一索引 {@code uk_watchlist_group_user_name} 兜底，
         * 后到者捕获 {@link DuplicateKeyException}。这里必须<b>吞掉异常并重查</b>，
         * 而不是让 500 冒出去——用户第一次点开自选股就报错是最糟的体验。
         */
        @Test
        @DisplayName("并发首次访问：唯一索引冲突被吞掉，改为返回对方建好的分组")
        void toleratesConcurrentDefaultGroupCreation() {
            WatchlistGroup winnersGroup = group("g-winner", WatchlistService.DEFAULT_GROUP_NAME, 0);
            when(groupMapper.selectList(any()))
                    .thenReturn(List.of())
                    .thenReturn(List.of(winnersGroup));
            when(groupMapper.insert(any(WatchlistGroup.class)))
                    .thenThrow(new DuplicateKeyException("uk_watchlist_group_user_name"));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            List<WatchlistGroupView> views = service.listGroups(USER);

            assertThat(views)
                    .as("冲突方应该看到并发对手建好的那个分组，而不是报错或返回空")
                    .hasSize(1);
            assertThat(views.get(0).id()).isEqualTo("g-winner");
        }

        @Test
        @DisplayName("冲突后仍查不到分组时不抛异常（返回空列表）")
        void concurrentConflictWithNoVisibleGroupDoesNotThrow() {
            when(groupMapper.selectList(any())).thenReturn(List.of());
            when(groupMapper.insert(any(WatchlistGroup.class)))
                    .thenThrow(new DuplicateKeyException("uk"));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            assertThat(service.listGroups(USER)).isEmpty();
        }

        @Test
        @DisplayName("条目数按分组分别聚合")
        void aggregatesItemCountPerGroup() {
            when(groupMapper.selectList(any())).thenReturn(List.of(
                    group("g-1", "我的自选", 0),
                    group("g-2", "观察池", 1)));
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("i-1", "g-1", "600519"),
                    item("i-2", "g-1", "000001"),
                    item("i-3", "g-2", "300750")));

            List<WatchlistGroupView> views = service.listGroups(USER);

            assertThat(views).extracting(WatchlistGroupView::id, WatchlistGroupView::itemCount)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("g-1", 2L),
                            org.assertj.core.groups.Tuple.tuple("g-2", 1L));
        }

        @Test
        @DisplayName("没有条目的分组 itemCount 为 0（不是 null）")
        void emptyGroupHasZeroCount() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-1", "空组", 0)));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            assertThat(service.listGroups(USER).get(0).itemCount()).isZero();
        }

        // ---------------------------------------------------------------
        // 越权防护：这一组才是本类的重点

        @Test
        @DisplayName("【越权】分组查询必须带 user_id 条件")
        void groupQueryIsScopedToUser() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-1", "我的自选", 0)));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listGroups(USER);

            Wrapper<WatchlistGroup> query = capturedGroupQuery();
            assertThat(sqlOf(query))
                    .as("少了 user_id 条件，任何登录用户都能读到别人的全部分组")
                    .contains("user_id");
            assertThat(paramsOf(query)).contains(USER);
        }

        @Test
        @DisplayName("【越权】条目计数查询必须带 user_id 条件")
        void itemCountQueryIsScopedToUser() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-1", "我的自选", 0)));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listGroups(USER);

            Wrapper<WatchlistItem> query = capturedItemQuery();
            assertThat(sqlOf(query))
                    .as("统计口径若不加 user_id，会把别人的条目也算进我的角标")
                    .contains("user_id");
            assertThat(paramsOf(query)).contains(USER);
        }

        @Test
        @DisplayName("分组按 sort_order 升序、name 升序排列")
        void groupsAreOrdered() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-1", "我的自选", 0)));
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listGroups(USER);

            assertThat(sqlOf(capturedGroupQuery()))
                    .contains("ORDER BY sort_order ASC,name ASC");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("创建分组")
    class CreateGroup {

        @Test
        @DisplayName("名称为 null -> INVALID_PARAMS，且不写库")
        void rejectsNullName() {
            BizException ex = biz(() -> service.createGroup(USER,
                    new WatchlistGroupCreateRequest(null, 0, null)));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_PARAMS.name());
            verify(groupMapper, never()).insert(any(WatchlistGroup.class));
        }

        @Test
        @DisplayName("名称只有空白 -> INVALID_PARAMS，且不写库")
        void rejectsBlankName() {
            BizException ex = biz(() -> service.createGroup(USER,
                    new WatchlistGroupCreateRequest("   ", 0, null)));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_PARAMS.name());
            verify(groupMapper, never()).insert(any(WatchlistGroup.class));
        }

        /**
         * trim 必须在 Service 里做。
         *
         * <p>Bean Validation 的 {@code @Size(min = 1)} 对 {@code "  "} 是通过的
         * （长度 2），所以「全是空格的分组名」只能靠这里拦住。
         * 顺带把前后空白去掉，避免「我的自选」和「我的自选 」被当成两个分组。
         */
        @Test
        @DisplayName("名称前后空白被去掉后入库")
        void trimsName() {
            stubGroupInsertAssigningId("g-1");

            service.createGroup(USER, new WatchlistGroupCreateRequest("  观察池  ", 0, null));

            ArgumentCaptor<WatchlistGroup> captor = ArgumentCaptor.forClass(WatchlistGroup.class);
            verify(groupMapper).insert(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("观察池");
        }

        @Test
        @DisplayName("sortOrder 为 null 时落库为 0")
        void defaultsSortOrderToZero() {
            stubGroupInsertAssigningId("g-1");

            service.createGroup(USER, new WatchlistGroupCreateRequest("观察池", null, null));

            ArgumentCaptor<WatchlistGroup> captor = ArgumentCaptor.forClass(WatchlistGroup.class);
            verify(groupMapper).insert(captor.capture());
            assertThat(captor.getValue().getSortOrder()).isZero();
        }

        @Test
        @DisplayName("归属与 parentId 原样落库")
        void persistsOwnershipAndParent() {
            stubGroupInsertAssigningId("g-1");

            service.createGroup(USER, new WatchlistGroupCreateRequest("子分组", 5, "g-parent"));

            ArgumentCaptor<WatchlistGroup> captor = ArgumentCaptor.forClass(WatchlistGroup.class);
            verify(groupMapper).insert(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER);
            assertThat(captor.getValue().getSortOrder()).isEqualTo(5);
            assertThat(captor.getValue().getParentId()).isEqualTo("g-parent");
        }

        @Test
        @DisplayName("同名分组冲突 -> DUPLICATE_ITEM（不能让 500 穿透）")
        void translatesDuplicateName() {
            when(groupMapper.insert(any(WatchlistGroup.class)))
                    .thenThrow(new DuplicateKeyException("uk_watchlist_group_user_name"));

            BizException ex = biz(() -> service.createGroup(USER,
                    new WatchlistGroupCreateRequest("观察池", 0, null)));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_ITEM.name());
        }

        @Test
        @DisplayName("新建分组的 itemCount 为 0")
        void newGroupHasZeroCount() {
            stubGroupInsertAssigningId("g-1");

            WatchlistGroupView view = service.createGroup(USER,
                    new WatchlistGroupCreateRequest("观察池", 7, "g-parent"));

            assertThat(view.id()).isEqualTo("g-1");
            assertThat(view.name()).isEqualTo("观察池");
            assertThat(view.sortOrder()).isEqualTo(7);
            assertThat(view.parentId()).isEqualTo("g-parent");
            assertThat(view.itemCount()).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("删除分组")
    class DeleteGroup {

        /**
         * 越权删除的正面拦截。
         *
         * <p>没有这道校验，攻击者可以拿别人的 {@code groupId} 直接调删除接口，
         * 把别人的自选股清空。前端隐藏入口<strong>不算</strong>防护。
         */
        @Test
        @DisplayName("【越权】分组不属于当前用户 -> GROUP_NOT_FOUND，且一条数据都不删")
        void rejectsGroupOwnedByAnotherUser() {
            when(groupMapper.countOwnedBy("g-someone-else", USER)).thenReturn(0);

            BizException ex = biz(() -> service.deleteGroup(USER, "g-someone-else"));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.GROUP_NOT_FOUND.name());
            verify(itemMapper, never()).delete(any());
            verify(groupMapper, never()).delete(any());
        }

        @Test
        @DisplayName("归属正确时先删条目、再删分组")
        void deletesItemsBeforeGroup() {
            when(groupMapper.countOwnedBy("g-1", USER)).thenReturn(1);

            service.deleteGroup(USER, "g-1");

            InOrder order = inOrder(itemMapper, groupMapper);
            order.verify(itemMapper).delete(any());
            order.verify(groupMapper).delete(any());
        }

        @Test
        @DisplayName("【越权】删条目的条件同时含 user_id 与 group_id")
        void itemDeleteIsDoubleScoped() {
            when(groupMapper.countOwnedBy("g-1", USER)).thenReturn(1);

            service.deleteGroup(USER, "g-1");

            Wrapper<WatchlistItem> query = capturedItemDeleteQuery();
            assertThat(sqlOf(query)).contains("user_id").contains("group_id");
            assertThat(paramsOf(query)).contains(USER, "g-1");
        }

        @Test
        @DisplayName("【越权】删分组的条件同时含 id 与 user_id")
        void groupDeleteIsDoubleScoped() {
            when(groupMapper.countOwnedBy("g-1", USER)).thenReturn(1);

            service.deleteGroup(USER, "g-1");

            Wrapper<WatchlistGroup> query = capturedGroupDeleteQuery();
            assertThat(sqlOf(query)).contains("id").contains("user_id");
            assertThat(paramsOf(query)).contains("g-1", USER);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("列出条目")
    class ListItems {

        @Test
        @DisplayName("没有任何条目时直接返回空列表，不请求行情源")
        void emptyListSkipsMarketData() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            assertThat(service.listItems(USER, null)).isEmpty();
            verify(marketDataProvider, never()).getQuote(anyString());
        }

        @Test
        @DisplayName("【越权】条目查询必须带 user_id")
        void itemQueryIsScopedToUser() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listItems(USER, null);

            Wrapper<WatchlistItem> query = capturedItemQuery();
            assertThat(sqlOf(query)).contains("user_id");
            assertThat(paramsOf(query)).contains(USER);
        }

        /**
         * 条件过滤：{@code groupId} 为空时不应产生 {@code group_id = null}。
         *
         * <p>写成 {@code .eq(getGroupId, null)}（不加 boolean 条件）会让
         * SQL 变成 {@code group_id = null}，在 SQL 语义下永远不成立，
         * 结果是「查全部」变成「查不到任何东西」。
         */
        @Test
        @DisplayName("groupId 为空时不加分组条件（不会退化成 group_id = null）")
        void omitsGroupFilterWhenBlank() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listItems(USER, null);

            assertThat(sqlOf(capturedItemQuery()))
                    .as("group_id = null 在 SQL 里恒不成立，会让列表变成永远为空")
                    .doesNotContain("group_id");
        }

        @Test
        @DisplayName("groupId 只传空白同样不复加分组条件")
        void omitsGroupFilterWhenWhitespace() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listItems(USER, "   ");

            assertThat(sqlOf(capturedItemQuery())).doesNotContain("group_id");
        }

        @Test
        @DisplayName("groupId 非空时加上分组条件")
        void appliesGroupFilterWhenPresent() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listItems(USER, "g-1");

            Wrapper<WatchlistItem> query = capturedItemQuery();
            assertThat(sqlOf(query)).contains("group_id");
            assertThat(paramsOf(query)).contains(USER, "g-1");
        }

        @Test
        @DisplayName("按 sort_order 升序、created_at 升序排列")
        void itemsAreOrdered() {
            when(itemMapper.selectList(any())).thenReturn(List.of());

            service.listItems(USER, null);

            assertThat(sqlOf(capturedItemQuery()))
                    .contains("ORDER BY sort_order ASC,created_at ASC");
        }

        @Test
        @DisplayName("同一代码只查一次行情（不同分组可能持有同一只股票）")
        void deduplicatesQuoteLookups() {
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("i-1", "g-1", "600519"),
                    item("i-2", "g-2", "600519"),
                    item("i-3", "g-1", "600519")));
            when(marketDataProvider.getQuote("600519")).thenReturn(quote("600519", "贵州茅台", 1700));

            List<WatchlistItemView> views = service.listItems(USER, null);

            assertThat(views).hasSize(3);
            verify(marketDataProvider, times(1)).getQuote("600519");
            assertThat(views).allSatisfy(v -> assertThat(v.name()).isEqualTo("贵州茅台"));
        }

        @Test
        @DisplayName("行情源异常时该条行情字段为 null，但条目本身照常返回")
        void toleratesQuoteFailure() {
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("i-1", "g-1", "600519"),
                    item("i-2", "g-1", "000001")));
            when(marketDataProvider.getQuote("600519")).thenReturn(quote("600519", "贵州茅台", 1700));
            when(marketDataProvider.getQuote("000001")).thenThrow(new RuntimeException("上游超时"));

            List<WatchlistItemView> views = service.listItems(USER, null);

            assertThat(views)
                    .as("自选列表是高频页面，不能因为上游抖动就整页失败")
                    .hasSize(2);
            assertThat(views.get(0).price()).isEqualTo(1700.0);
            assertThat(views.get(1).code()).isEqualTo("000001");
            assertThat(views.get(1).price()).isNull();
            assertThat(views.get(1).name()).isNull();
        }

        @Test
        @DisplayName("行情失败过的代码不重复请求（失败也被缓存）")
        void cachesFailedLookups() {
            when(itemMapper.selectList(any())).thenReturn(List.of(
                    item("i-1", "g-1", "000001"),
                    item("i-2", "g-2", "000001")));
            when(marketDataProvider.getQuote("000001")).thenThrow(new RuntimeException("上游超时"));

            service.listItems(USER, null);

            verify(marketDataProvider, times(1)).getQuote("000001");
        }

        /**
         * 行情条数上限。
         *
         * <p>{@code MAX_QUOTE_ENRICH = 100}：一次请求最多打 100 次上游。
         * 这是必要的保护——用户有 500 条自选时，一次列表请求若逐条取价，
         * 就是 500 次上游调用，会把自己的服务连同上游一起打垮。
         */
        @Test
        @DisplayName("同一批里最多取 100 条行情（150 个不同代码只打 100 次上游）")
        void capsQuoteEnrichmentAt100() {
            List<WatchlistItem> many = IntStream.range(0, 150)
                    .mapToObj(i -> item("i-" + i, "g-1", String.format("%06d", i)))
                    .toList();
            when(itemMapper.selectList(any())).thenReturn(many);
            when(marketDataProvider.getQuote(anyString()))
                    .thenAnswer(inv -> quote(inv.getArgument(0), "名称", 10.0));

            List<WatchlistItemView> views = service.listItems(USER, null);

            assertThat(views).hasSize(150);
            verify(marketDataProvider, times(100)).getQuote(anyString());
            // 前 100 条有行情，后 50 条没有
            assertThat(views.get(99).price()).isEqualTo(10.0);
            assertThat(views.get(100).price()).isNull();
            assertThat(views.get(149).price()).isNull();
        }

        @Test
        @DisplayName("有行情的条目带上名称/价格/涨跌额/涨跌幅")
        void mapsQuoteFields() {
            when(itemMapper.selectList(any())).thenReturn(List.of(item("i-1", "g-1", "600519")));
            when(marketDataProvider.getQuote("600519")).thenReturn(quote("600519", "贵州茅台", 1700.5));

            WatchlistItemView view = service.listItems(USER, null).get(0);

            assertThat(view.id()).isEqualTo("i-1");
            assertThat(view.code()).isEqualTo("600519");
            assertThat(view.groupId()).isEqualTo("g-1");
            assertThat(view.name()).isEqualTo("贵州茅台");
            assertThat(view.price()).isEqualTo(1700.5);
            assertThat(view.change()).isEqualTo(1.0);
            assertThat(view.changePct()).isEqualTo(0.5);
            assertThat(view.createdAt()).isEqualTo(1_700_000_000L);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("添加条目")
    class AddItem {

        @Test
        @DisplayName("【越权】显式指定的分组不属于当前用户 -> GROUP_NOT_FOUND，且不写库")
        void rejectsForeignGroup() {
            when(groupMapper.countOwnedBy("g-someone-else", USER)).thenReturn(0);

            BizException ex = biz(() -> service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", "g-someone-else", null)));

            assertThat(ex.getCode())
                    .as("不校验归属就能把股票写进别人的分组，这是越权写入")
                    .isEqualTo(ErrorCode.GROUP_NOT_FOUND.name());
            verify(itemMapper, never()).insert(any(WatchlistItem.class));
        }

        @Test
        @DisplayName("显式分组归属正确时使用它")
        void usesOwnedGroup() {
            when(groupMapper.countOwnedBy("g-2", USER)).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(quote("600519", "贵州茅台", 1700));

            WatchlistItemView view = service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", "g-2", 3));

            assertThat(view.groupId()).isEqualTo("g-2");
            assertThat(view.sortOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("不指定分组时落到懒创建的默认分组")
        void fallsBackToLazilyCreatedDefaultGroup() {
            when(groupMapper.selectList(any()))
                    .thenReturn(List.of())
                    .thenReturn(List.of(group("g-default", WatchlistService.DEFAULT_GROUP_NAME, 0)));
            stubGroupInsertAssigningId("g-default");
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(quote("600519", "贵州茅台", 1700));

            WatchlistItemView view = service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", null, null));

            assertThat(view.groupId())
                    .as("原 FastAPI 版本硬编码 'g-default'，而真实分组 ID 是 UUID——"
                            + "导致写入一个悬空的分组 ID，数据写得进去但按分组永远查不到")
                    .isEqualTo("g-default");
            verify(groupMapper).insert(any(WatchlistGroup.class));
        }

        @Test
        @DisplayName("已有分组时落到排序最靠前的那个（不重复创建默认分组）")
        void fallsBackToFirstExistingGroup() {
            when(groupMapper.selectList(any())).thenReturn(List.of(group("g-first", "我的自选", 0)));
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            WatchlistItemView view = service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", null, null));

            assertThat(view.groupId()).isEqualTo("g-first");
            verify(groupMapper, never()).insert(any(WatchlistGroup.class));
        }

        @Test
        @DisplayName("股票代码去掉交易所后缀")
        void stripsExchangeSuffix() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(quote("600519", "贵州茅台", 1700));

            service.addItem(USER, new WatchlistItemCreateRequest("600519.SH", "g-1", null));

            ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
            verify(itemMapper).insert(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo("600519");
        }

        @Test
        @DisplayName("股票代码首尾空白被去掉")
        void trimsCode() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            service.addItem(USER, new WatchlistItemCreateRequest("  600519  ", "g-1", null));

            ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
            verify(itemMapper).insert(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo("600519");
        }

        @Test
        @DisplayName("数量达到上限 -> INVALID_PARAMS，且不写库")
        void rejectsWhenAtLimit() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(500L);

            BizException ex = biz(() -> service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", "g-1", null)));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_PARAMS.name());
            assertThat(ex.getMessage()).contains("上限");
            verify(itemMapper, never()).insert(any(WatchlistItem.class));
        }

        @Test
        @DisplayName("正好 499 条时仍允许添加")
        void allowsJustBelowLimit() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(499L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            service.addItem(USER, new WatchlistItemCreateRequest("600519", "g-1", null));

            verify(itemMapper).insert(any(WatchlistItem.class));
        }

        @Test
        @DisplayName("【越权】数量统计必须带 user_id（否则会用别人的条数把自己卡住）")
        void countQueryIsScopedToUser() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            service.addItem(USER, new WatchlistItemCreateRequest("600519", "g-1", null));

            Wrapper<WatchlistItem> query = capturedItemCountQuery();
            assertThat(sqlOf(query)).contains("user_id");
            assertThat(paramsOf(query)).contains(USER);
        }

        @Test
        @DisplayName("重复添加 -> DUPLICATE_ITEM（靠唯一索引，不靠先查后插）")
        void translatesDuplicateItem() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            when(itemMapper.insert(any(WatchlistItem.class)))
                    .thenThrow(new DuplicateKeyException("uk_watchlist_item"));

            BizException ex = biz(() -> service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", "g-1", null)));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_ITEM.name());
        }

        @Test
        @DisplayName("新增后取价失败仍返回条目，行情字段为 null")
        void toleratesQuoteFailureAfterInsert() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote("600519")).thenThrow(new RuntimeException("上游超时"));

            WatchlistItemView view = service.addItem(USER,
                    new WatchlistItemCreateRequest("600519", "g-1", null));

            assertThat(view.id()).isEqualTo("i-1");
            assertThat(view.code()).isEqualTo("600519");
            assertThat(view.price()).isNull();
        }

        @Test
        @DisplayName("sortOrder 为 null 时落库为 0")
        void defaultsSortOrderToZero() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            service.addItem(USER, new WatchlistItemCreateRequest("600519", "g-1", null));

            ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
            verify(itemMapper).insert(captor.capture());
            assertThat(captor.getValue().getSortOrder()).isZero();
            assertThat(captor.getValue().getUserId()).isEqualTo(USER);
        }

        /**
         * 【特征化】代码格式校验在 DTO 层，Service 不做二次校验。
         *
         * <p>这不是断言「这样对」，而是把当前的契约边界钉住：
         * {@code @Pattern("^\d{6}([.](SH|SZ|BJ))?$")} 挂在
         * {@code WatchlistItemCreateRequest} 上，只有经过 {@code @Valid}
         * 的 Controller 入口才会生效。任何绕过 Controller 直接调用
         * Service 的新代码路径（比如将来的批量导入、定时任务），
         * 都会把非法代码原样写库。
         *
         * <p>将来若在 Service 里补上校验，这条会红——那时应该更新它，
         * 而不是把校验删掉。
         */
        @Test
        @DisplayName("【特征化】Service 不校验代码格式，全靠 DTO 层的 @Pattern")
        void serviceDoesNotValidateCodeFormat() {
            when(groupMapper.countOwnedBy(anyString(), anyString())).thenReturn(1);
            when(itemMapper.selectCount(any())).thenReturn(0L);
            stubItemInsertAssigningId("i-1");
            when(marketDataProvider.getQuote(anyString())).thenReturn(null);

            service.addItem(USER, new WatchlistItemCreateRequest("NOT-A-CODE", "g-1", null));

            ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
            verify(itemMapper).insert(captor.capture());
            assertThat(captor.getValue().getCode())
                    .as("当前行为：非法代码会被原样写入。校验只存在于 DTO 层的 @Valid 路径上")
                    .isEqualTo("NOT-A-CODE");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("删除条目")
    class RemoveItem {

        @Test
        @DisplayName("受影响行数为 0 -> ITEM_NOT_FOUND")
        void reportsMissingItem() {
            when(itemMapper.delete(any())).thenReturn(0);

            BizException ex = biz(() -> service.removeItem(USER, "i-404"));

            assertThat(ex.getCode()).isEqualTo(ErrorCode.ITEM_NOT_FOUND.name());
        }

        @Test
        @DisplayName("删除成功不抛异常")
        void deletesSuccessfully() {
            when(itemMapper.delete(any())).thenReturn(1);

            service.removeItem(USER, "i-1");

            verify(itemMapper).delete(any());
        }

        /**
         * 删条目与「判断归属」必须是<b>同一条语句</b>。
         *
         * <p>写成「先按 id 查出来、再比较 user_id 是否相等」需要额外代码路径，
         * 任何一处忘记判断就是越权。这里断言 WHERE 同时含 {@code id} 与
         * {@code user_id}，受影响行数为 0 即等价于「不存在或不属于你」。
         */
        @Test
        @DisplayName("【越权】删除条件同时含 id 与 user_id，一条语句完成归属判断")
        void deleteIsDoubleScoped() {
            when(itemMapper.delete(any())).thenReturn(1);

            service.removeItem(USER, "i-1");

            Wrapper<WatchlistItem> query = capturedItemDeleteQuery();
            assertThat(sqlOf(query)).contains("id").contains("user_id");
            assertThat(paramsOf(query)).contains("i-1", USER);
        }

        /**
         * 【特征化】不存在与不属于你返回同一个错误码。
         *
         * <p>故意不区分：若「不存在」返回 404 而「不属于你」返回 403，
         * 攻击者就能用错误码差异探测别人条目 ID 的存在性。
         */
        @Test
        @DisplayName("【特征化】不区分「不存在」与「不属于你」，统一 404")
        void doesNotDistinguishMissingFromForeign() {
            when(itemMapper.delete(any())).thenReturn(0);

            BizException asForeign = biz(() -> service.removeItem(USER, "i-of-other"));
            BizException asMissing = biz(() -> service.removeItem(USER, "i-not-exist"));

            assertThat(asForeign.getCode()).isEqualTo(asMissing.getCode());
            assertThat(asForeign.getMessage()).isEqualTo(asMissing.getMessage());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("常量契约")
    class Constants {

        @Test
        @DisplayName("默认分组名称为「我的自选」")
        void defaultGroupNameIsStable() {
            assertThat(WatchlistService.DEFAULT_GROUP_NAME)
                    .as("前端侧栏会按这个名称做默认选中，改名属于破坏性变更")
                    .isEqualTo("我的自选");
        }
    }
}
