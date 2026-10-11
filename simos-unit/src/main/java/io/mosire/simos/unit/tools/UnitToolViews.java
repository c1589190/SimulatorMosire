package io.mosire.simos.unit.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * 单位工具面的**视图口**：工具算事实，**形状由宿主给**。
 *
 * <p>★★ <b>为什么形状不写在工具里</b>（与 B3 试点 {@code simos-map} 的 {@code MapToolViews}、第二域 army 的 {@code
 * ArmyToolViews} 同一条纪律）：这些视图（GUI / MCP 共用的那一份）住在 app（{@code ApiViews} 与 {@code ToolSupport}
 * 的视图装配），且本仓有一条既有硬纪律——"GUI 与 MCP **共用同一个函数**，不许各写一份同形视图"（{@code ToolSupport.unit}/{@code
 * compositionView} 的类注：加字段就得记得改两处，那是漂移的温床）。工具下放到 {@code simos-unit} 后，若把形状抄一份进领域模块，就正好复活那条纪律要消灭的形态
 * ⇒ 视图**注入**：实现留在 app，逐条转调**同一份**既有函数（输出因此逐字节不变，I-G1）。
 *
 * <p>★ 本接口只能住在 {@code simos-unit}：它的签名里有 {@link Unit} / {@link UnitState} / {@link HexCoord} /
 * {@link CompositionEntry}——契约层 {@code simos-ga-spi} 不许依赖任何领域模块（I-G2），所以"带领域类型的端口" 必须由该领域自己声明、由 app
 * 实现（依赖倒置）。
 *
 * <p>★ <b>两个 {@code State} 参数的边界</b>：{@code state}（完整状态）原样交给宿主——单位视图要的 {@code sd} 切片与 {@code
 * CalendarService} 都在 app 侧，unit **结构上够不着**（sd 在禁列；calendar 只是传递可见，不显式声明）。 工具只多给宿主已经算出来的 {@code
 * units} 切片（同一次查询窗口的同一份读数）。
 */
public interface UnitToolViews {

  /** 单格坐标视图：{@code {q, r}}（插入序）。 */
  Map<String, Object> hexCoord(HexCoord coord);

  /**
   * 人力/装备**有序表** → JSON 视图（{@code [{type,amount}…]}，顺序原样）。
   *
   * <p>★ 命令载荷与读视图都从这一条走（{@code ToolSupport.compositionView} 是"全仓工具面的唯一转换点"）。
   */
  List<Map<String, Object>> compositionView(List<CompositionEntry> entries);

  /**
   * 工具入参 {@code {type:数量}} → 有序 {@link CompositionEntry} 表（{@code LinkedHashMap} 的迭代序原样保留）。
   *
   * <p>★ 这是 combo 工具输入面（{@code raiseUnit} / {@code spawnArmy} 的 {@code equipment}）到新 Unit 表形状的唯一桥
   * （{@code ToolSupport.compositionEntries}）——域内另写一份就是同一个转换的第二处拼写点。
   */
  List<CompositionEntry> compositionEntries(Map<String, Integer> counts);

  /**
   * 单个单位详情视图（{@code simos.unit.get}）：{@code ApiViews.unit} 那一份，含 {@code module} / {@code
   * jurisdiction} / {@code stateDescriptions} / {@code households[]} / {@code population}。
   *
   * @param unit 目标单位（工具已判它存在且可见）
   * @param units 同一次读数里的 unit 切片（父链 / 位置解析用它）
   * @param state 同一次读数里的完整状态（宿主从它取 sd / social 切片与查询时刻）
   */
  Map<String, Object> unit(Unit unit, UnitState units, SimulationState state);

  /**
   * 单位清单视图（{@code simos.unit.list}）：**逐单位按调用者现算的范围筛**（越界的单位不进结果，与 {@code unit.get} 的 NOT_FOUND
   * 同一口径）——过滤谓词只有一处实现（app 的 {@code ToolSupport.unitVisible}），故判据以 {@code context} 原样交给宿主。
   *
   * @param units 同一次读数里的 unit 切片
   * @param context 调用上下文（可见性围栏按它现算）
   * @param state 同一次读数里的完整状态
   */
  List<Map<String, Object>> units(UnitState units, ToolContext context, SimulationState state);
}
