import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * ECharts 按需注册（全项目唯一入口）。
 *
 * <p>改成按需之前，各个图表组件里各自 {@code import * as echarts from 'echarts'}，
 * 打包后 echarts chunk 是 1117 kB / gzip 370 kB——而项目只用到蜡烛图、
 * 折线、柱状三种系列。按需注册后只打包实际用到的模块。
 *
 * <h2>这份清单必须与视图里的 option 一一对应</h2>
 * <b>漏一个的后果是运行时报错而不是编译报错</b>：
 * 少注册 {@code CandlestickChart} 会得到
 * 「Series candlestick is used but not imported」；
 * 少注册 {@code GridComponent} 则整个直角坐标系不渲染（图表一片空白）。
 * 因此新增图表类型时，除了写 option，还要回到这里补注册。
 *
 * <h2>为什么集中到一个文件</h2>
 * 注册是<b>全局副作用</b>（往 echarts 的全局表里塞模块）。
 * 分散到多个组件里时，谁先加载谁决定注册结果，
 * 某个组件漏注册就会被别的组件「顺带补上」而掩盖问题——
 * 直到那个组件被单独引入时突然崩掉。集中在唯一入口就不会有这种隐式依赖。
 *
 * @see components/BaseChart.vue        —— 单图容器（生命周期管理）
 * @see components/StockChart.vue       —— 多 grid 联动行情图
 */
echarts.use([
  BarChart,
  CandlestickChart,
  LineChart,
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
  CanvasRenderer,
])

export { echarts }
