package io.mosire.simos.map.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 地图工具面的**寻路口**（{@code simos.map.path} 专用）：从某单位在给定状态下的**有效位置**起，到目标格的 A* 试算。
 *
 * <p>★★ <b>为什么它必须注入</b>：寻路要用 {@code simos-unit} 的 {@code PathFinder} / {@code TerrainMovementCost}
 * 与 {@code UnitState}（地形移动成本是单位域的规则），而 {@code simos-map} 的 enforcer **禁止**依赖 unit （依赖方向固定 {@code
 * util → map → … → unit}；地图永远不知道单位存在——铁律 3）。工具名却按域归属留在 map （设计书 §4.3 的 15 个 {@code Map*}）⇒ 由认识两侧的
 * app 注入实现。
 *
 * <p>★ <b>四分支必须分开表达</b>：今天 {@code simos.map.path} 的结果里，"单位不存在"是 {@code NOT_FOUND}， "没有有效位置"与"到不了"都是
 * {@code {reachable: false}}。把四者折成 {@code Optional} 或布尔会让**拒绝码/载荷 漂移**（I-G1）⇒ 端口用密封结局把四条路各自钉住。
 */
public interface MapPathPort {

  /** 一次寻路试算的结局（四条互斥的路，与工具今天的四段行为逐条对应）。 */
  sealed interface Outcome {

    /** 单位 id 在 unit 切片里查无此单位 ⇒ 工具发既有文案的 {@code NOT_FOUND}。 */
    record UnitMissing() implements Outcome {}

    /** 单位在，但该时刻**没有有效位置**（在途/未落位）⇒ {@code reachable: false}。 */
    record NoStart() implements Outcome {}

    /** 起点在，但目标格到不了（含目标格在图外）⇒ {@code reachable: false}。 */
    record Unreachable() implements Outcome {}

    /**
     * 到达（{@code path} 是路径；实现按 {@code PathFinder} 的原样返回给，不重排）。
     *
     * <p>★ <b>保序冻结在赋值处</b>（照仓内 {@code GovSalaryRuleBridge} / {@code NationSummary}
     * 的既成形制）：路径是**有序**事实， 不能用 {@code List.copyOf}（其迭代序不是内容的纯函数——本仓明文禁用该族），故用 {@code
     * Collections.unmodifiableList(new ArrayList<>(…))}；这同时满足 SpotBugs 的 {@code EI_EXPOSE_REP} /
     * {@code EI_EXPOSE_REP2}（记录类型的防御性副本）。
     */
    record Reachable(List<HexCoord> path) implements Outcome {

      public Reachable {
        Objects.requireNonNull(path, "path");
        path = Collections.unmodifiableList(new ArrayList<>(path));
      }
    }
  }

  /**
   * 试算路径：起点由实现按该单位的**有效位置**取（调用方只给终点——与 GUI 同一口径，避免第二份真相）。
   *
   * <p>★ 单位 id 的**合法性校验属于 unit 域**（{@code new UnitId(text)} 当场抛）⇒ 坏 id 由实现抛 {@link
   * IllegalArgumentException}，调用方按既有口径折成 {@code BAD_REQUEST}（文案逐字不变）。
   *
   * @param state 查询到的状态（起点取该状态下的有效位置）
   * @param unitId 单位 id 文本（模型给的载荷，不受信）
   * @param goal 终点格
   * @return 四结局之一
   */
  Outcome findPath(SimulationState state, String unitId, HexCoord goal);
}
