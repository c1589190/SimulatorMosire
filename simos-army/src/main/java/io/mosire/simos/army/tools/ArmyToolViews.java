package io.mosire.simos.army.tools;

import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import java.util.List;
import java.util.Map;

/**
 * 军队工具面的**视图口**：工具算事实，**形状由宿主给**。
 *
 * <p>★★ <b>为什么形状不写在工具里</b>（与 B3 试点 {@code simos-map} 的 {@code MapToolViews} 同一条纪律）： 这些视图（GUI / MCP
 * 共用的那一份）住在 app（{@code ApiViews} 与 {@code ToolSupport} 的视图装配），且本仓有一条既有硬纪律 ——"GUI 与 MCP
 * **共用同一个函数**，不许各写一份同形视图"（{@code ToolSupport.compositionView} 的类注：加字段就得记得改两处， 那是漂移的温床）。工具下放到 {@code
 * simos-army} 后，若把形状抄一份进领域模块，就正好复活那条纪律要消灭的形态 ⇒ 视图**注入**： 实现留在 app，逐条转调**同一份**既有函数（输出因此逐字节不变，I-G1）。
 *
 * <p>★ 本接口只能住在 {@code simos-army}：它的签名里有 {@link CombatRecord} / {@link HexCoord} / {@link
 * CompositionEntry} / {@link CompositionDelta} —— 契约层 {@code simos-ga-spi} 不许依赖任何领域模块（I-G2），
 * 所以"带领域类型的端口"必须由该领域自己声明、由 app 实现（依赖倒置）。
 */
public interface ArmyToolViews {

  /**
   * 一条交战记录的读视图（{@code simos.army.combat} / {@code simos.army.combats} 共用）：{@code
   * id/kind/tick/hex/participants/text/stages/losses}。
   *
   * <p>★ 详情读口与清单读口都调本方法（形状只有一份）⇒"同一资源的两个形状"在结构上不可能。
   */
  Map<String, Object> armyCombat(CombatRecord record);

  /** 单格坐标视图：{@code {q, r}}（插入序）。 */
  Map<String, Object> hexCoord(HexCoord coord);

  /**
   * 人力/装备**有序表** → JSON 视图（{@code [{type,amount}…]}，顺序原样）。
   *
   * <p>★ 命令载荷与读视图都从这一条走（{@code ToolSupport.compositionView} 是"全仓工具面的唯一转换点"）： {@code
   * unit.SetComposition} 载荷与 {@code formatUnit} 结果的前后对照因此不可能各拼一份。
   */
  List<Map<String, Object>> compositionView(List<CompositionEntry> entries);

  /** 有符号增量表 → JSON 视图（{@code [{type,amount}…]}；amount 可负，供 {@code unit.AdjustComposition} 载荷）。 */
  List<Map<String, Object>> compositionDeltaView(List<CompositionDelta> deltas);

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：{@code ga.PutInfo} 行动记录的地址（{@code formatUnit} 的唯一使用点）。
   *
   * <p>★ 走注入而不是在域内拼：该地址形态的既有唯一拼写点是 app 的 {@code RaiseUnitPlan.unitAddress} （{@code
   * Address.parse(...).canonical()}）——"单位 canonical"多拼一处就多一处可以漂移的语法。
   */
  String unitAddress(String unitId);
}
