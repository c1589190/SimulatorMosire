package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.migrate.ProductionRoleResolver;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.ResourcePaths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>P2-B：家户经济配置命令（{@code economy.SetHouseholdClass} / {@code SetHouseholdParticipation} /
 * {@code SetHouseholdLabor} / {@code UpdateDemand}）共用的载荷/目标/家户解析</b>。
 *
 * <p>★ <b>为什么收在一处</b>：四条命令都吃"一个家户 + 可选 {@code at} 坐标 + 可选的拒绝原因"，且 {@link
 * io.mosire.simos.util.spi.CommandTargets#targetPaths} 的签名拿不到状态、只能从载荷里读声明目标。四处各写一遍
 * "坐标→路径""家户是否存在""standing 是否在场"必然漂开；本类把它们钉成一份。
 *
 * <p>★ <b>{@code at} 的语义（诚实边界）</b>：家户资源在 {@code economy} 命名空间里以"格"为可寻址单位 （{@link
 * ResourcePaths#economy(int, int)}）。{@code CommandTargets} 看不到状态 ⇒ 决策令必须**显式带上**目标格； handler
 * 再核对"这个格就是该家户当刻所在的格"，因此声明目标与实在目标不可能错位。GM 直接提交可以省略 {@code at} （{@code targetPaths} 返回空 ⇒ 决策令路径
 * fail-closed，直通不受影响）。
 */
final class HouseholdEconomyCommands {

  private HouseholdEconomyCommands() {}

  /** 可选 {@code at} 坐标（接受 {@code {q,r}} 或 {@code "q_r"}）；不给 ⇒ 空。 */
  static Optional<HexCoord> optionalAt(String command, JsonNode payload) {
    return EconomyCommandPayloads.optionalHex(command, payload, "at");
  }

  /** 若给了 {@code at}，它必须逐值等于该家户当前居住格（防止决策令"声明辖区内的格、实际改辖区外的家户"）。 不给 ⇒ 不判（GM 直通路径）。 */
  static void requireAtMatchesHousehold(
      String command, EconomyData base, HouseholdId household, Optional<HexCoord> at) {
    if (at.isEmpty()) {
      return;
    }
    HouseholdEconomy householdEconomy = base.classes().get(household);
    if (householdEconomy == null) {
      throw new IllegalArgumentException(command + " 的家户不存在: " + household.value());
    }
    if (!householdEconomy.view().hex().equals(at.get())) {
      throw new IllegalArgumentException(
          command
              + " 的 at 必须等于家户当刻居住格：家户="
              + household.value()
              + " 居住格="
              + householdEconomy.view().hex()
              + "，at="
              + at.get());
    }
  }

  /** 目标声明：给了 {@code at} ⇒ 该格的 economy 路径；没给 ⇒ 空（fail-closed，不假装有目标）。 */
  static List<String> targetPaths(Optional<HexCoord> at) {
    if (at.isEmpty()) {
      return List.of();
    }
    HexCoord hex = at.get();
    return List.of(ResourcePaths.economy(hex.q(), hex.r()));
  }

  /** 家户必须存在（经济行 = 家户身份；S1 起键就是稳定身份）。 */
  static HouseholdId requireHousehold(String command, EconomyData base, JsonNode payload) {
    HouseholdId household =
        HouseholdId.parse(EconomyCommandPayloads.requireText(command, payload, "household"));
    if (!base.classes().containsKey(household)) {
      throw new IllegalArgumentException(command + " 的家户不存在: " + household.value());
    }
    return household;
  }

  /** 位置必须已存在于 {@code classPositions}（引用完整性由命令边界给出可读拒绝；构造期守卫是第二道）。 */
  static ProductionRole requirePosition(String command, EconomyData base, String positionText) {
    ClassPositionId id = ClassPositionId.parse(positionText);
    ProductionRole position = base.classPositions().get(id);
    if (position == null) {
      throw new IllegalArgumentException(command + " 的阶层位置不存在: " + id.value());
    }
    return position;
  }

  /**
   * {@code modes} 字段 → 该 mode 结构下的全部位置（"可参与生产方式"的粗粒度配置）。mode 必须已存在；没有结构 ⇒ 拒绝。 ★ 具体哪些位置真的生产由组织阶段的
   * {@code shouldProduce} 过滤（本类不复制那份判据）。
   */
  static Set<ClassPositionId> expandModes(String command, EconomyData base, JsonNode payload) {
    JsonNode modes = payload.get("modes");
    if (modes == null || modes.isNull()) {
      return Set.of();
    }
    if (!modes.isArray()) {
      throw new IllegalArgumentException(command + " 的 modes 必须是数组: " + modes);
    }
    Set<ClassPositionId> positions = new LinkedHashSet<>();
    for (JsonNode element : modes) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(command + " 的 modes 元素必须是非空白文本: " + element);
      }
      ProductionModeId modeId = ProductionModeId.parse(element.asText());
      ProductionMode mode = base.modes().get(modeId);
      if (mode == null) {
        throw new IllegalArgumentException(command + " 的生产方式不存在: " + modeId.value());
      }
      ClassStructure structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        throw new IllegalArgumentException(command + " 的生产方式没有阶层结构，无法展开位置: " + modeId.value());
      }
      positions.addAll(structure.positions().keySet());
    }
    return positions;
  }

  /** {@code positions} 字段 → 位置集合（每项必须已存在）。 */
  static Set<ClassPositionId> parsePositions(String command, EconomyData base, JsonNode payload) {
    JsonNode nodes = payload.get("positions");
    if (nodes == null || nodes.isNull()) {
      return Set.of();
    }
    if (!nodes.isArray()) {
      throw new IllegalArgumentException(command + " 的 positions 必须是数组: " + nodes);
    }
    Set<ClassPositionId> positions = new LinkedHashSet<>();
    for (JsonNode element : nodes) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(command + " 的 positions 元素必须是非空白文本: " + element);
      }
      positions.add(requirePosition(command, base, element.asText()).id());
    }
    return positions;
  }

  /**
   * 该家户的 {@link HouseholdClassMembership}；没有就用 {@link ProductionRoleResolver#resolveCurrent}（旧档
   * stratum 映射） 播种一条"只参与当前位置"的归属。解析不出位置 ⇒ 具名拒绝（不伪造归属）。
   */
  static HouseholdClassMembership requireStandingOrSeed(
      String command, EconomyData base, HouseholdId household, String reason, long day) {
    HouseholdClassMembership existingClassMembership = base.classStandings().get(household);
    if (existingClassMembership != null) {
      return existingClassMembership;
    }
    ClassPositionId current =
        ProductionRoleResolver.resolveCurrent(base, household)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        command
                            + " 的家户没有阶层归属、也解析不出旧 stratum 对应位置；请先用 economy.SetHouseholdClass: "
                            + household.value()));
    return new HouseholdClassMembership(
        household, current, current, Set.of(), Map.of(), 0L, day, reason);
  }

  /** 只读：把位置 id 清单按字典序规范化（报告/日志用）。 */
  static List<String> sortedPositionValues(Set<ClassPositionId> positions) {
    List<String> values = new ArrayList<>();
    for (ClassPositionId position : positions) {
      values.add(position.value());
    }
    values.sort(String::compareTo);
    return values;
  }
}
