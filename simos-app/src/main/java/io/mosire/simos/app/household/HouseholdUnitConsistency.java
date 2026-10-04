package io.mosire.simos.app.household;

import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>Unit/Gov 的 households ↔ Social 家户位置的一致性（S3b，2026-10-09）</b>。
 *
 * <pre>
 * Unit.households 含 H  ⇔  Household(H).location == UNIT(unitId)
 * </pre>
 *
 * <p>★★ <b>为什么必须有这一处</b>：{@code Unit.households} 是 unit 侧"谁归我管"的人口关系；{@code Household.location} 是
 * Social 侧"这个家户在哪"的位置账。两条账必须同时成立，否则同一个家户会同时出现在两个单位/格上（或"有位置、没编制"）。S3a 只在 GM
 * 组合工具里成对写；本类把**校核**与**生产路径的自动同步**收在一处，供 app 组合根（推进参与者）在每轮推进前使用——生产代码不再依赖 GM 手工工具。
 *
 * <p>★★ <b>自动同步的方向（单向，具名）</b>：{@code Unit.households} 是本轮认定的**单位人口关系**入口；若它含 H 而 Social 位置不是 {@code
 * UNIT(本 unit)}，本类把 Social 位置同步成 {@code UNIT(本 unit)}（Social 侧写口，可进本轮 revision）。反向（Social 说 {@code
 * UNIT(u)} 但 unit 列表不含 H）不在本类单侧删除或添加——那需要同时改 unit 切片，属单元命令组合（{@code
 * simos.unit.assignHousehold}）；本类把它记成 {@code unresolved}，由调用方 fail-closed。
 *
 * <p>★ <b>只读 + 纯函数</b>：{@link #reconcileSocialToUnits} 不改入参，返回新 {@link SocialData}；单位不存在/家户不存在/反向孤儿
 * 一律进 {@code unresolved} 具名列表，不静默丢。
 */
public final class HouseholdUnitConsistency {

  private static final Logger LOG = LoggerFactory.getLogger(HouseholdUnitConsistency.class);

  private HouseholdUnitConsistency() {}

  /** 一行具名不一致（不抛，供调用方汇总；{@link #requireConsistent} 会把它们并成一条异常）。 */
  public record Mismatch(String kind, String detail) {

    public Mismatch {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
      return kind + ": " + detail;
    }
  }

  /**
   * 全量校核（只读）：返回所有不一致，按 {@code unit.households} 的稳定序遍历，结果只依赖状态内容。
   *
   * <p>查五类：① unit 列表含家户但家户不存在；② unit 列表含家户但 Social 位置不是 {@code UNIT(本 unit)}；③ Social 位置是 {@code
   * UNIT(u)} 但 unit {@code u} 不存在；④ Social 位置是 {@code UNIT(u)} 但 {@code u.households} 不含该家户； ⑤ unit
   * id 与 social household id 撞名（共用裸串空间）。
   */
  public static List<Mismatch> mismatches(SocialData social, UnitState units) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    List<Mismatch> out = new ArrayList<>();
    Set<String> unitIds = new LinkedHashSet<>();
    for (UnitId id : units.units().keySet()) {
      unitIds.add(id.value());
    }
    for (Unit unit : sortedUnits(units)) {
      if (social.households().containsKey(new HouseholdId(unit.id().value()))) {
        out.add(
            new Mismatch("ID_COLLISION", "unit id 与 social household id 撞名: " + unit.id().value()));
      }
      for (HouseholdId householdId : unit.households()) {
        Household household = social.households().get(householdId);
        if (household == null) {
          out.add(
              new Mismatch(
                  "UNIT_HOUSEHOLD_MISSING",
                  "unit " + unit.id() + " 的 households 含不存在的家户 " + householdId));
          continue;
        }
        if (!(household.location() instanceof HouseholdLocation.Unit location)
            || !location.unitId().equals(unit.id().value())) {
          out.add(
              new Mismatch(
                  "UNIT_LIST_WITHOUT_LOCATION",
                  "unit "
                      + unit.id()
                      + " 的 households 含家户 "
                      + householdId
                      + "，但 Social 位置是 "
                      + household.location()));
        }
      }
    }
    for (Household household : social.households().values()) {
      if (!(household.location() instanceof HouseholdLocation.Unit location)) {
        continue;
      }
      UnitId unitId = UnitId.parse(location.unitId());
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        out.add(
            new Mismatch(
                "LOCATION_WITHOUT_UNIT",
                "家户 " + household.id() + " 的位置是 " + household.location() + "，但该 unit 不存在"));
      } else if (!unit.households().contains(household.id())) {
        out.add(
            new Mismatch(
                "LOCATION_WITHOUT_UNIT_LIST_ENTRY",
                "家户 "
                    + household.id()
                    + " 的位置是 "
                    + household.location()
                    + "，但 unit "
                    + unitId
                    + " 的 households 列表不含它"));
      }
    }
    return List.copyOf(out);
  }

  /** 校核 + 具名 fail-closed（不一致 ⇒ {@link IllegalStateException}，消息最多列 5 条）。 */
  public static void requireConsistent(SocialData social, UnitState units) {
    List<Mismatch> mismatches = mismatches(social, units);
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "Unit.households 与 Social 家户位置不一致（S3b 不变量）："
              + mismatches.size()
              + " 处："
              + mismatches.subList(0, Math.min(5, mismatches.size())));
    }
  }

  /**
   * 自动同步的结果：{@code data} = 同步后的 Social 状态；{@code repaired} = 本轮修好的条目；{@code unresolved} = 单侧修不了的条目。
   */
  public record Reconciliation(SocialData data, List<String> repaired, List<String> unresolved) {

    public Reconciliation {
      Objects.requireNonNull(data, "data");
      repaired = List.copyOf(Objects.requireNonNull(repaired, "repaired"));
      unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
    }
  }

  /**
   * ★★ <b>生产路径的自动同步（Social 侧单向）</b>：把每个 unit 的 {@code households} 投影到该家户的 Social 位置上 （{@code
   * UNIT(unitId)}）；家户不存在、以及反向孤儿（Social 位置指向 unit 而 unit 列表没有它）进 {@code unresolved}， 由调用方
   * fail-closed（不静默丢家户，也不擅自把家户塞进 unit 列表）。
   *
   * <p>★ 没有任何需要修的条目 ⇒ 原样返回入参（不重建状态，零变更）。
   */
  public static Reconciliation reconcileSocialToUnits(SocialData social, UnitState units) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    List<String> repaired = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    Map<HouseholdId, Household> households = new LinkedHashMap<>(social.households());
    for (Unit unit : sortedUnits(units)) {
      for (HouseholdId householdId : unit.households()) {
        Household household = households.get(householdId);
        if (household == null) {
          unresolved.add("unit " + unit.id() + " 的 households 含不存在的家户 " + householdId);
          continue;
        }
        HouseholdLocation expected = new HouseholdLocation.Unit(unit.id().value());
        if (!expected.equals(household.location())) {
          households.put(householdId, household.withLocation(expected));
          repaired.add(
              "家户 "
                  + householdId
                  + " 位置 "
                  + household.location()
                  + " → "
                  + expected
                  + "（unit "
                  + unit.id()
                  + " 的 households 列表要求）");
        }
      }
    }
    // 反向孤儿：Social 位置指向 unit，但该 unit 列表不含它（本轮不单侧改 unit 列表 ⇒ 具名未决）。
    for (Household household : households.values()) {
      if (!(household.location() instanceof HouseholdLocation.Unit location)) {
        continue;
      }
      Unit unit = units.units().get(UnitId.parse(location.unitId()));
      if (unit == null) {
        unresolved.add("家户 " + household.id() + " 的位置指向不存在的 unit " + location.unitId());
      } else if (!unit.households().contains(household.id())) {
        unresolved.add(
            "家户 " + household.id() + " 的位置指向 unit " + location.unitId() + "，但该 unit 列表不含它");
      }
    }
    if (unresolved.isEmpty() && repaired.isEmpty()) {
      return new Reconciliation(social, List.of(), List.of());
    }
    if (!repaired.isEmpty()) {
      for (String entry : repaired) {
        LOG.info("event=HOUSEHOLD_LOCATION_AUTOSYNC {}", entry);
      }
    }
    SocialData data = repaired.isEmpty() ? social : social.withHouseholds(households);
    return new Reconciliation(data, repaired, unresolved);
  }

  /**
   * ★ <b>领导层 staff 与家户配置的只读投影读数</b>（S3b，兼容期）：某 GOV 的 {@code householdPosts} 非空时，按角色聚合这些
   * 家户的**人口**（人），作为 {@code staff} 的家户投影读数。★ 本方法**不判等、不写状态**：旧 {@code staff} 的数值口径 （在编人数 vs
   * 全部家庭成员）尚未由用户裁定，强制相等会臆造规则；调用方只把它作为具名读数/告警。
   */
  public static Map<String, Long> staffHouseholdProjection(SocialData social, UnitState units) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    Map<String, Long> projection = new LinkedHashMap<>();
    for (Unit unit : sortedUnits(units)) {
      if (!(unit.module().orElse(null) instanceof GovFormation gov)
          || gov.householdPosts().isEmpty()) {
        continue;
      }
      Map<String, Long> byRole = new LinkedHashMap<>();
      for (io.mosire.simos.unit.GovernmentHouseholdPost post : gov.householdPosts().values()) {
        long population = social.householdPopulation(post.householdId());
        byRole.merge(post.role().name(), population, Long::sum);
      }
      for (Map.Entry<String, Long> entry : byRole.entrySet()) {
        projection.put(unit.id().value() + ":" + entry.getKey(), entry.getValue());
      }
    }
    return projection;
  }

  /** 单位按 id 升序遍历（跨状态的输出顺序只依赖内容，不依赖 Map 迭代序）。 */
  private static List<Unit> sortedUnits(UnitState units) {
    List<Unit> sorted = new ArrayList<>(units.units().values());
    sorted.sort(Comparator.comparing(unit -> unit.id().value()));
    return sorted;
  }

  /** 编制配置键是否都在本单位 households 里的只读检查（{@link UnitState} 构造期已强判；这里供报告/诊断复用）。 */
  public static List<Mismatch> moduleConfigMismatches(UnitState units) {
    Objects.requireNonNull(units, "units");
    List<Mismatch> out = new ArrayList<>();
    for (Unit unit : sortedUnits(units)) {
      Set<HouseholdId> contained = new LinkedHashSet<>(unit.households());
      if (unit.module().orElse(null) instanceof GovFormation gov) {
        for (HouseholdId household : gov.householdPosts().keySet()) {
          if (!contained.contains(household)) {
            out.add(
                new Mismatch(
                    "GOV_POST_HOUSEHOLD_NOT_CONTAINED",
                    "unit " + unit.id() + " 的 householdPosts 含未容纳家户 " + household));
          }
        }
      } else if (unit.module().orElse(null) instanceof ArmyFormation army) {
        for (HouseholdId household : army.householdDuties().keySet()) {
          if (!contained.contains(household)) {
            out.add(
                new Mismatch(
                    "ARMY_DUTY_HOUSEHOLD_NOT_CONTAINED",
                    "unit " + unit.id() + " 的 householdDuties 含未容纳家户 " + household));
          }
        }
      }
    }
    return List.copyOf(out);
  }
}
