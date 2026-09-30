package io.mosire.simos.army;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 军队**视野辖区**的纯派生视图（阶段 12，用户裁定 6/8）：军队单位当前位置 + 它的 {@code visionRadius} ⇒ 六角半径内的格集。
 *
 * <p>★★ <b>这是“军队辖区 = 视野”的唯一算法</b>：{@code simos-app} 的 {@code ArmyScope} 不再内联同一算式，而是调用本类——
 * 一处拼写，免得半径/中心口径两处漂移。类注还钉住一条边界：视野**只按半径**，不做地形遮挡、河、敌情（那些是 unit 将来的“视野功能”）。
 *
 * <p>★★ <b>派生量不落盘</b>：本类不写 {@code UnitState}/{@code GameMap}，不新增状态切片，结果每次现算；也不产出任何
 * <b>战斗力/交战结果</b>——{@code simos-army} 一期明确不算战力（裁定 6），sd 的战斗仍由决策人/GM 按 {@code CombatStage} 裁定。
 *
 * <p>★ <b>算不出来就是“看不见”</b>（fail-closed）：单位不在 {@code UnitState} 里、或单位没有 {@code effectivePosition}
 * （“不知道在哪”，含根单位查无）⇒ {@link Optional#empty()}。调用方（{@code ArmyScope}）据此走 deny-all。
 *
 * <p>★ <b>中心走 {@code UnitState.effectivePosition}</b>（与 GUI/工具面同口径）：读 {@code unit.position()}
 * 会在“根单位自身无位置” 时得到空答案，而有效位置的口径是“按时刻解析该单位自己的位置序列”。{@code visionRadius} 的合法性由 {@link Unit} 构造期保证（≥
 * 0），故本方法不再重复夹取/校验。
 */
public final class ArmyVision {

  private ArmyVision() {}

  /**
   * 算给定军队单位的视野格集：{@code effectivePosition(armyUnit, at)} 为空 ⇒ {@link Optional#empty()}；否则 {@code
   * HexGrid.withinRadius(pos, unit.visionRadius())} 的全部格。
   *
   * @param units unit 切片（军队位置与视野半径的唯一真值来源）；不得为 null
   * @param armyUnit 军队单位 id；不得为 null
   * @param at 取有效位置的时刻；不得为 null
   * @return 有序（{@link TreeSet} 自然序）、不可变的格集；单位/位置查无 ⇒ {@code Optional.empty()}
   */
  public static Optional<Set<HexCoord>> visionHexes(
      UnitState units, UnitId armyUnit, SimosTimestamp at) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(armyUnit, "armyUnit");
    Objects.requireNonNull(at, "at");
    Unit unit = units.units().get(armyUnit);
    if (unit == null) {
      return Optional.empty();
    }
    Optional<HexCoord> position = units.effectivePosition(armyUnit, at);
    if (position.isEmpty()) {
      return Optional.empty();
    }
    Set<HexCoord> hexes = new TreeSet<>(HexGrid.withinRadius(position.get(), unit.visionRadius()));
    return Optional.of(Collections.unmodifiableSet(hexes));
  }
}
