package io.mosire.simos.app.household;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>ClassRow.population 的 Social 家户投影（S3b，2026-10-09）</b>。
 *
 * <pre>
 * 对每个 (hex, 居住类型) 的家户组：
 *   Σ_{经济侧属于该组的 ClassRow.population}  ←  Social 家户成员批次之和
 * </pre>
 *
 * <p>★★ <b>为什么需要它</b>：{@code ClassRow.population} 是经济侧的行人口（月结账、劳动折算、债务减免都要读），而人口真值在 Social
 * 家户的成员批次里。本类在 app 组合根（唯一同时看得见 social 与 economy 的地方）把经济侧行人口**重投影**回家户事实：正常状态下两侧 逐值一致 ⇒
 * 零变化；出现漂移（社会侧少了人而经济侧没跟、或反向）⇒ 按现有行人口权重把该组总量拉回 Social 人数，并对每行劳动量同比例缩放。
 *
 * <p>★★ <b>这不是"第二本账转正"</b>：投影是**单向**的（Social → ClassRow）；经济侧仍然物理存着 {@code population} 字段供本切片计算，
 * 但它的**权威来源**是 Social。经济侧自己的月度出生/死亡写回仍保留（它同时负责劳动、债务、流水），本投影在推进入口做一次对齐。
 *
 * <p>★ <b>明确不做（具名缺口）</b>：{@code ClassRow} 的**身份**仍是经济侧合成的 {@code (格, 居住类型, 阶层)} 家户 id（一个 Social
 * 家户对应该组的 4 条阶层行），不是 Social 的 {@code HouseholdId}——把键改成 Social 家户需要改 {@code classes}/{@code flows}/
 * {@code Membership}/配额的主键结构与全部读口，超出本批；本类只保证**人口数值**以 Social 为准。
 *
 * <p>★ <b>fail-closed 口径</b>：只要存在"经济侧行组找不到对应 Social 家户"或"同一家户组有两个 Social 家户"这类无法无损投影的情况，本类
 * **不做任何修改**（返回原数据）并把原因放进 {@code unresolved}；调用方据此告警/拒绝，绝不静默把差额均摊掉。
 */
public final class HouseholdClassRowProjection {

  private static final Logger LOG = LoggerFactory.getLogger(HouseholdClassRowProjection.class);

  private HouseholdClassRowProjection() {}

  /** 投影结果：{@code projected=true} 时 {@code data} 是重投影后的经济状态；否则原样返回且带 {@code unresolved} 原因。 */
  public record Result(
      EconomyData data,
      boolean projected,
      int changedRows,
      long populationDelta,
      List<String> unresolved) {

    public Result {
      Objects.requireNonNull(data, "data");
      unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
    }
  }

  /** (格, 居住类型) 的复合键：Social 家户组与 ClassRow.view 在这里会合。 */
  private record HouseholdView(HexCoord hex, ResidenceKind residence) {}

  /**
   * 把 {@code economy.classes()} 的人口投影到 {@code social.households()} 的成员之和。
   *
   * <p>★ Social 家户为空（旧世界/未播种）⇒ 原样返回（投影无从谈起，不是坏数据）；经济侧为空同理。
   */
  public static Result project(EconomyData economy, SocialData social) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(social, "social");
    if (economy.classes().isEmpty() || social.households().isEmpty()) {
      return new Result(economy, false, 0, 0L, List.of());
    }
    List<String> unresolved = new ArrayList<>();

    // ① Social 侧：每个 HEX 家户 → (格, 居住类型) 目标人口。
    Map<HouseholdView, Long> targetPopulation = new LinkedHashMap<>();
    Map<HouseholdView, HouseholdId> householdOfView = new LinkedHashMap<>();
    List<Household> households = new ArrayList<>(social.households().values());
    households.sort(Comparator.comparing(household -> household.id().value()));
    for (Household household : households) {
      if (!(household.location() instanceof HouseholdLocation.Hex hex)) {
        continue; // UNIT 家户没有格行；它们的人口不落 ClassRow。
      }
      ResidenceKind residence = residenceOf(household);
      if (residence == null) {
        unresolved.add("家户 " + household.id() + " 的成员批次推不出居住类型（rural:/urban: 前缀）");
        continue;
      }
      HouseholdView view = new HouseholdView(hex.hex(), residence);
      HouseholdId previousHousehold = householdOfView.putIfAbsent(view, household.id());
      if (previousHousehold != null) {
        unresolved.add(
            "同一 (格 "
                + hex.hex()
                + ", 居住 "
                + residence.value()
                + ") 有多个 Social 家户: "
                + previousHousehold
                + " 与 "
                + household.id()
                + "（无法无损投影）");
        continue;
      }
      targetPopulation.put(view, social.householdPopulation(household.id()));
    }

    // ② 经济侧：按 (格, 居住类型) 分组 ClassRow（保序）。
    Map<HouseholdView, List<HouseholdId>> rowsByView = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      ClassRow row = entry.getValue();
      HouseholdView view = new HouseholdView(row.view().hex(), row.view().residence());
      rowsByView.computeIfAbsent(view, ignored -> new ArrayList<>()).add(entry.getKey());
    }
    for (HouseholdView view : rowsByView.keySet()) {
      if (!targetPopulation.containsKey(view)) {
        unresolved.add(
            "经济侧有 (格 "
                + view.hex()
                + ", 居住 "
                + view.residence().value()
                + ") 的 ClassRow，但没有对应的 HEX Social 家户");
      }
    }
    // ★ 投影只在"HEX 家户总人口 == 经济侧行人口总和"时进行：UNIT 家户 / 未接线人口会让两侧总量不等，
    //   那种状态下强行按比例投影会把差额摊掉（静默丢人）⇒ 这里记未决、原样返回，让后面的跨切片守恒校验
    //   （MembershipWriteback）去 fail-closed。
    long targetTotal = 0L;
    for (long population : targetPopulation.values()) {
      targetTotal = Math.addExact(targetTotal, population);
    }
    long rowTotal = 0L;
    for (ClassRow row : economy.classes().values()) {
      rowTotal = Math.addExact(rowTotal, row.population());
    }
    if (targetTotal != rowTotal) {
      unresolved.add(
          "HEX Social 家户总人口("
              + targetTotal
              + ") != 经济侧 ClassRow 行人口总和("
              + rowTotal
              + ")（可能有 UNIT 家户或尚未接线的人口）");
    }
    if (!unresolved.isEmpty()) {
      LOG.warn(
          "event=CLASSROW_POPULATION_PROJECTION_SKIPPED unresolved={} first={}",
          unresolved.size(),
          unresolved.get(0));
      return new Result(economy, false, 0, 0L, unresolved);
    }

    // ③ 逐组重投影：现有行人口作权重，最大余数法分配 Social 总数；劳动量同比例缩放。
    Map<HouseholdId, ClassRow> next = new LinkedHashMap<>(economy.classes());
    int changedRows = 0;
    long populationDelta = 0L;
    for (Map.Entry<HouseholdView, List<HouseholdId>> group : rowsByView.entrySet()) {
      List<HouseholdId> keys = group.getValue();
      long currentTotal = 0L;
      long[] weights = new long[keys.size()];
      for (int i = 0; i < keys.size(); i++) {
        long population = next.get(keys.get(i)).population();
        weights[i] = population;
        currentTotal = Math.addExact(currentTotal, population);
      }
      long target = targetPopulation.get(group.getKey());
      if (currentTotal == target) {
        continue;
      }
      long[] parts = ProportionalSplit.byDenominator(target, weights, currentTotal);
      for (int i = 0; i < keys.size(); i++) {
        HouseholdId key = keys.get(i);
        ClassRow row = next.get(key);
        long newPopulation = parts[i];
        if (newPopulation == row.population()) {
          continue;
        }
        long newLabor =
            row.population() == 0L
                ? row.laborMilli()
                : row.laborMilli() * newPopulation / row.population();
        next.put(key, row.withPopulationAndLabor(newPopulation, newLabor));
        changedRows++;
        populationDelta += Math.abs(newPopulation - row.population());
      }
    }
    if (changedRows == 0) {
      return new Result(economy, false, 0, 0L, List.of());
    }
    EconomyData projected = economy.withClasses(next);
    LOG.info(
        "event=CLASSROW_POPULATION_PROJECTED households={} changedRows={} absPopulationDelta={}",
        targetPopulation.size(),
        changedRows,
        populationDelta);
    return new Result(projected, true, changedRows, populationDelta, List.of());
  }

  /** 家户的居住类型：取第一个成员批次的 {@code rural:/urban:} 前缀（成员批次为空 ⇒ null，不猜）。 */
  private static ResidenceKind residenceOf(Household household) {
    for (PeopleLotId lot : household.memberLots()) {
      try {
        return ResidenceKind.ofLot(lot);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }
}
