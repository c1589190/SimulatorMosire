package io.mosire.simos.economy.migrate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>旧档迁移器（economy 侧）</b>：CohortKey 身份 → HouseholdId 身份 + 成员份额 + 劳动家户归属（S1）， 以及 R3B.2 的
 * 生产主体迁移（{@code Industry.operator/progress} → {@code ProductionProcess}；旧键 → unit 键）。
 *
 * <p>★★ <b>触发条件与幂等</b>（{@code EconomyData} 的构造期调用，也是本类的公开入口）：
 *
 * <pre>
 * needed = 任一 HouseholdLaborCommitment.household 是 pending 占位
 *       或无 units 却有 relations（旧键连一个 unit 都解析不到）
 *       或有 units 时 关系/条件的键不是已存在的 unit、或配额的 activity/actor 与 unit 不一致（旧键对齐）
 * </pre>
 *
 * <p>★★ <b>R3B.2 的三条搬运规则（逐条一对一，不凭空拆结构）</b>：
 *
 * <ol>
 *   <li><b>unit 不由本迁移器合成</b>：默认 unit 需要旧 {@code Industry} 的 {@code operator/capacity}，这条兼容路径在
 *       {@code EconomyData} 构造期归一化与 {@code EconomyCodec}/{@code EconomyPayloads} 的节点 reshape
 *       各走一次（同判据、 幂等）；本迁移器只接收已存在的 unit；
 *   <li><b>键与值同时对齐</b>：旧 {@code relations}/{@code operatorConditions} 的键是 {@code IndustryId} 串，
 *       {@code 值内 activity} 也是旧串。能按 {@code (industry, operator)}（条件按 {@code industry}）解析到<b>唯一</b>
 *       unit 的就改写； <b>解析不到 unit 的条目丢弃</b>（没有 unit = 这个产业本周期没有任何生产活动 ⇒ 行为与旧档"产能 0"逐值等价）； <b>解析到多个
 *       unit 的抛</b>（混合态说不清哪个，fail-closed，不猜）；
 *   <li><b>配额 activity 改写</b>：旧 {@code HouseholdLaborCommitment.activity} 是活动标签（{@code farm}/{@code
 *       weave}， 或旧档直接写 actor id）⇒ 按 {@code actor.id()} 当产业串 + 唯一 unit 改写为 unit id。解析不到（自由家户劳动，
 *       没有对应产业）⇒ 原样保留（它本来就不喂任何生产，旧档同义）。
 * </ol>
 *
 * <p>★★ <b>R3B.1 的实物份额口径（旧档迁移）</b>：旧档已有 UseRight 时 codec 已一对一整形成 OwnershipStake（只原样搬运）； 旧档没有
 * useRights 但有 {@code Industry.capacity} 时，由 <b>EconomyData 构造期归一化</b>（Timeline 直读旧 changeset 的兜底）或
 * <b>codec/载荷边缘</b>（节点 reshape）在构造新形状之前物化整额 OWNED 份额并合成默认 unit。★ 这些兼容位（B.2b 起又挂回 {@code Industry}
 * 末尾，只读）在本迁移器<b>之前</b>已被归一化清成中性，故本迁移器看不到 {@code capacity}； 本迁移器只做键/activity 对齐，不再从 {@code
 * OwnershipStake} 反推 unit（防停产复活）。
 */
public final class LegacyHouseholdMigration {

  /** 迁移来源标签（旧档没有 revision 上下文时用的具名值；有值则原样保留）。 */
  public static final String LEGACY_MIGRATION_SOURCE = "legacy-pre-modern-v1";

  private LegacyHouseholdMigration() {}

  /** ★★ 迁移结果（EconomyData 的构造期把七个参数整体换掉；R3B.2 起含 units/relations/operatorConditions）。 */
  // ★ 豁免 EI_EXPOSE_REP（R4a）：本 record 是 EconomyData 构造期的内部中间载体；六张表都由迁移器当场新建
  //   并只交给 EconomyData 立即冻结，不存在外部可变引用跨边界。为不改动迁移热路径的拷贝次数，
  //   这里按 ArmyPlan 的先例按类豁免。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "内部迁移载体；六张表由迁移器新建并立即交给 EconomyData 冻结，无外部可变引用跨边界")
  public record Result(
      Map<ProductionUnitId, ProductionProcess> units,
      Map<LaborAllocationId, HouseholdLaborCommitment> allocations,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Optional<EconomyMeta> meta) {}

  /** ★ 是否还需迁移（见类注的三类触发条件；幂等的判据）。 */
  public static boolean needed(
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Optional<EconomyMeta> meta) {
    if (laborCommitments != null) {
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        if (laborCommitment != null && HouseholdIds.isPending(laborCommitment.household())) {
          return true;
        }
      }
    }
    // ★★ R3B.2：旧档没有 units 组件、但有旧键关系需要对齐（或按旧产业存在性丢弃）⇒ 需要迁移。
    //   ★ 触发只看 relations：**不**因为"有 assetShares 而无 units"就迁移 —— 份额是财产事实、unit 是生产活动，
    //     "资产闲置、没有经营者开工"是合法状态（退出/闲置），从份额反推 unit 等于让停产产业凭空复活。
    boolean hasUnits = units != null && !units.isEmpty();
    if (!hasUnits && relations != null && !relations.isEmpty()) {
      return true;
    }
    if (hasUnits) {
      // 关系/条件的键不是已存在的 unit ⇒ 需要对齐。
      if (relations != null) {
        for (Map.Entry<ProductionUnitId, ProductionRules> entry : relations.entrySet()) {
          ProductionProcess unit = units.get(entry.getKey());
          if (unit == null
              || !entry.getValue().activity().equals(entry.getKey())
              || !entry.getValue().operator().equals(unit.operator())) {
            return true;
          }
        }
      }
      if (operatorConditions != null) {
        for (Map.Entry<ProductionUnitId, OperatorCondition> entry : operatorConditions.entrySet()) {
          ProductionProcess unit = units.get(entry.getKey());
          if (unit == null || !unit.industry().equals(entry.getValue().industry())) {
            return true;
          }
        }
      }
      // 配额的 activity 能解析到唯一 unit、却还不是 unit id ⇒ 需要对齐（旧 label / 旧 industry 串）。
      Map<String, List<ProductionProcess>> byIndustry = unitsByIndustry(units.values());
      if (laborCommitments != null) {
        for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
          if (laborCommitment == null) {
            continue;
          }
          ProductionUnitId resolved =
              resolveLaborCommitmentUnit(laborCommitment, byIndustry, units.keySet());
          if (resolved != null) {
            ProductionProcess unit = units.get(resolved);
            if (!resolved.value().equals(laborCommitment.activity())
                || (unit != null && !unit.operator().equals(laborCommitment.actor()))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  /** ★★ <b>执行迁移</b>：不修改入参；返回全新的七张表。失败一律抛具名异常（不静默丢劳动/丢人口）。 */
  public static Result migrate(
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Optional<EconomyMeta> meta) {
    Map<LaborAllocationId, HouseholdLaborCommitment> migratedLaborCommitments =
        new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentEntry :
        laborCommitments.entrySet()) {
      HouseholdLaborCommitment laborCommitment = laborCommitmentEntry.getValue();
      Optional<HexCoord> maybeHex = industryHexOf(industries, laborCommitment.actor());
      if (maybeHex.isEmpty()) {
        if (!HouseholdIds.isPending(laborCommitment.household())) {
          // 非 pending 的新档配额（actor 不是产业 id 也能合法存在 —— 家户自营）：原样带过。
          migratedLaborCommitments.put(laborCommitmentEntry.getKey(), laborCommitment);
          continue;
        }
        throw new IllegalStateException(
            "旧档迁移失败：配额 "
                + laborCommitmentEntry.getKey()
                + " 的 actor 既不在产业表里、id 也不带格键，无法定位它的格: "
                + laborCommitment.actor());
      }
      HexCoord hex = maybeHex.get();
      ResidenceKind residence = ResidenceKind.ofLot(laborCommitment.group());
      if (!HouseholdIds.isPending(laborCommitment.household())) {
        migratedLaborCommitments.put(laborCommitmentEntry.getKey(), laborCommitment);
        continue;
      }
      List<HouseholdEconomy> candidateHouseholdEconomies =
          candidateHouseholdEconomies(householdEconomies, hex, residence);
      if (candidateHouseholdEconomies.isEmpty()) {
        throw new IllegalStateException(
            "旧档迁移失败：配额 "
                + laborCommitmentEntry.getKey()
                + " 指向 "
                + laborCommitment.actor()
                + "（格 "
                + hex
                + "，居住 "
                + residence.value()
                + "）在该格没有任何人口非 0 的家户行 —— 无法把劳动归属到真实家户（拒绝静默丢劳动）");
      }
      long totalWeight = 0L;
      long[] weights = new long[candidateHouseholdEconomies.size()];
      for (int i = 0; i < candidateHouseholdEconomies.size(); i++) {
        weights[i] = candidateHouseholdEconomies.get(i).population();
        totalWeight = Math.addExact(totalWeight, weights[i]);
      }
      long[] parts =
          ProportionalSplit.byDenominator(laborCommitment.laborMilli(), weights, totalWeight);
      IndustryId industry = industryIdOf(industries, laborCommitment.actor(), hex);
      for (int i = 0; i < candidateHouseholdEconomies.size(); i++) {
        HouseholdId household = candidateHouseholdEconomies.get(i).id();
        LaborAllocationId newId =
            HouseholdLaborCommitment.idOf(industry, laborCommitment.group(), household);
        if (migratedLaborCommitments.putIfAbsent(
                newId,
                new HouseholdLaborCommitment(
                    newId,
                    laborCommitment.group(),
                    household,
                    laborCommitment.actor(),
                    laborCommitment.activity(),
                    parts[i],
                    laborCommitment.period(),
                    laborCommitment.kind()))
            != null) {
          throw new IllegalStateException("旧档迁移产生了重复的劳动配额 id: " + newId);
        }
      }
    }
    Map<AssetShareId, OwnershipStake> migratedOwnershipStakes = new LinkedHashMap<>();
    if (assetShares != null) {
      // ★★ R3B.1：旧档已有 UseRight 时，codec 已按"一对一把 holder 填成 owner=operator"整形成 OwnershipStake
      //   （见 EconomyCodec 的旧节点整形）；这里只原样搬运 ⇒ id 字符串不改写、不重算、不拆地主/佃户。
      migratedOwnershipStakes.putAll(assetShares);
    }
    // ── R3B.2：生产主体 ───────────────────────────────────────────────────────────────
    // ★★ units **不由本迁移器合成**：默认 unit 需要旧 {@code Industry.operator/capacity}。B.2b 起这条兼容路径在
    //   {@code EconomyData} 构造期归一化（Timeline 直读旧 changeset 的兜底）与 {@code EconomyCodec}/{@code
    // EconomyPayloads}
    //   （节点 reshape）各走一次，判据同一、幂等；本迁移器只做"键/activity 与既有 unit 对齐"（旧关系/条件/配额），
    //   以及在 unit 不存在时按旧产业存在性丢弃。
    //   ★ 为什么不从 OwnershipStake 反推 unit：份额是**财产事实**，unit 是**实际生产活动** —— 一个已被 owner 持有、
    //     但没有经营者开工的产业（退出/闲置）也会有份额；从份额反推 unit 等于让停产产业凭空复活。
    Map<ProductionUnitId, ProductionProcess> migratedUnits = new LinkedHashMap<>();
    if (units != null) {
      migratedUnits.putAll(units);
    }
    Map<ProductionUnitId, ProductionRules> migratedRelations =
        canonicalizeRelations(relations, migratedUnits, industries);
    Map<ProductionUnitId, OperatorCondition> migratedConditions =
        canonicalizeConditions(operatorConditions, migratedUnits, industries);
    migratedLaborCommitments =
        canonicalizeLaborCommitmentActivities(migratedLaborCommitments, migratedUnits);
    Optional<EconomyMeta> migratedMeta = migrateMeta(meta);
    return new Result(
        migratedUnits,
        migratedLaborCommitments,
        migratedOwnershipStakes,
        migratedRelations,
        migratedConditions,
        migratedMeta);
  }

  /**
   * ★★ <b>关系键/activity 对齐</b>：旧键（industry 串）与旧 activity 都是同一件事的两种拼法 ⇒ 能解析到唯一 unit 的改写， 解析不到 unit
   * 的丢弃（没有 unit = 没有生产活动），解析到多个的抛（混合态不猜）。
   */
  private static Map<ProductionUnitId, ProductionRules> canonicalizeRelations(
      Map<ProductionUnitId, ProductionRules> raw,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries) {
    Map<ProductionUnitId, ProductionRules> out = new LinkedHashMap<>();
    if (raw == null) {
      return out;
    }
    Map<String, List<ProductionProcess>> byIndustry = unitsByIndustry(units.values());
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : raw.entrySet()) {
      ProductionRules relation = entry.getValue();
      ProductionProcess direct = units.get(entry.getKey());
      if (direct != null) {
        if (!direct.operator().equals(relation.operator())) {
          throw new IllegalStateException(
              "关系与它指名的 unit 的 operator 不一致（同一件事两处拼写）：relation="
                  + relation.operator()
                  + "，unit="
                  + direct.operator()
                  + "（"
                  + entry.getKey()
                  + "）");
        }
        out.put(
            entry.getKey(),
            relation.activity().equals(entry.getKey())
                ? relation
                : relation.withActivity(entry.getKey()));
        continue;
      }
      ProductionUnitId resolved =
          resolveUnit(byIndustry, relation.activity().value(), relation.operator());
      if (resolved == null) {
        resolved = resolveUnit(byIndustry, entry.getKey().value(), relation.operator());
      }
      if (resolved == null) {
        if (legacyIndustryExists(industries, entry.getKey().value())
            || legacyIndustryExists(industries, relation.activity().value())) {
          continue; // 旧产业确实存在、但一个 unit 都没有（旧档产能 0）⇒ 行为等价：丢弃这条关系
        }
        // 产业都不存在 ⇒ 这不是"旧档键"，是坏数据：原样留下，由 EconomyData 的守卫 fail-closed。
        out.put(entry.getKey(), relation);
        continue;
      }
      out.put(resolved, relation.withActivity(resolved));
    }
    return out;
  }

  /** ★★ <b>条件键对齐</b>：条件值内只有 {@code industry}（没有 operator）⇒ 按该产业下的唯一 unit 改写；多个抛。 */
  private static Map<ProductionUnitId, OperatorCondition> canonicalizeConditions(
      Map<ProductionUnitId, OperatorCondition> raw,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries) {
    Map<ProductionUnitId, OperatorCondition> out = new LinkedHashMap<>();
    if (raw == null) {
      return out;
    }
    Map<String, List<ProductionProcess>> byIndustry = unitsByIndustry(units.values());
    for (Map.Entry<ProductionUnitId, OperatorCondition> entry : raw.entrySet()) {
      OperatorCondition condition = entry.getValue();
      ProductionProcess direct = units.get(entry.getKey());
      if (direct != null) {
        if (!direct.industry().equals(condition.industry())) {
          throw new IllegalStateException(
              "经营者条件值内的 industry 与它指名的 unit.industry 不一致（同一件事两处拼写）：条件="
                  + condition.industry()
                  + "，unit="
                  + direct.industry()
                  + "（"
                  + entry.getKey()
                  + "）");
        }
        out.put(entry.getKey(), condition);
        continue;
      }
      List<ProductionProcess> candidates =
          byIndustry.getOrDefault(condition.industry().value(), List.of());
      if (candidates.isEmpty()) {
        if (legacyIndustryExists(industries, condition.industry().value())) {
          continue; // 旧产业存在但一个 unit 都没有（旧档产能 0）⇒ 行为等价：丢弃条件
        }
        out.put(entry.getKey(), condition); // 产业不存在 ⇒ 坏数据，交给守卫 fail-closed
        continue;
      }
      if (candidates.size() > 1) {
        throw new IllegalStateException(
            "旧档迁移失败：经营者条件 "
                + entry.getKey()
                + " 指向产业 "
                + condition.industry()
                + "，但该产业下有多条 unit ⇒ 说不清是哪一个（拒绝猜）: "
                + candidates);
      }
      out.put(candidates.get(0).id(), condition);
    }
    return out;
  }

  /** ★★ <b>配额 activity 对齐</b>：能解析到唯一 unit 的改写为 unit id；解析不到的自由家户劳动原样保留。 */
  private static Map<LaborAllocationId, HouseholdLaborCommitment>
      canonicalizeLaborCommitmentActivities(
          Map<LaborAllocationId, HouseholdLaborCommitment> rawLaborCommitment,
          Map<ProductionUnitId, ProductionProcess> units) {
    if (rawLaborCommitment == null) {
      return new LinkedHashMap<>();
    }
    Map<String, List<ProductionProcess>> byIndustry = unitsByIndustry(units.values());
    Map<LaborAllocationId, HouseholdLaborCommitment> outLaborCommitments = new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentEntry :
        rawLaborCommitment.entrySet()) {
      HouseholdLaborCommitment laborCommitment = laborCommitmentEntry.getValue();
      ProductionUnitId resolved =
          resolveLaborCommitmentUnit(laborCommitment, byIndustry, units.keySet());
      ProductionProcess unit = resolved == null ? null : units.get(resolved);
      if (resolved == null
          || (resolved.value().equals(laborCommitment.activity())
              && unit != null
              && unit.operator().equals(laborCommitment.actor()))) {
        outLaborCommitments.put(laborCommitmentEntry.getKey(), laborCommitment);
        continue;
      }
      // ★★ R3B.2：activity 与 actor 一起对齐到 unit —— 旧口径下"劳动力按 actor.id()（=产业 id）归集、产出归
      //   industry.operator"；新口径下这条劳动属于该 unit，收劳动的主体就是 unit.operator（守卫要求两者一致）。
      outLaborCommitments.put(
          laborCommitmentEntry.getKey(),
          new HouseholdLaborCommitment(
              laborCommitment.id(),
              laborCommitment.group(),
              laborCommitment.household(),
              unit == null ? laborCommitment.actor() : unit.operator(),
              resolved.value(),
              laborCommitment.laborMilli(),
              laborCommitment.period(),
              laborCommitment.kind()));
    }
    return outLaborCommitments;
  }

  /**
   * 一条配额的 activity → unit：① 已是现存 unit id ⇒ 它本身；② {@code actor.id()} 当产业串命中唯一 unit ⇒ 那条； ③ {@code
   * activity} 当产业串命中唯一 unit ⇒ 那条；都不命中 ⇒ null（自由家户劳动，不喂任何生产）。
   */
  private static ProductionUnitId resolveLaborCommitmentUnit(
      HouseholdLaborCommitment laborCommitment,
      Map<String, List<ProductionProcess>> byIndustry,
      Set<ProductionUnitId> unitIds) {
    ProductionUnitId byActivity = new ProductionUnitId(laborCommitment.activity());
    if (unitIds.contains(byActivity)) {
      return byActivity;
    }
    if (laborCommitment.activity().startsWith("unit-")) {
      // ★ 看起来是 unit id 但不存在 ⇒ 悬空引用：不按 actor/产业重定向（交给 EconomyData 守卫 fail-closed）。
      return null;
    }
    ProductionUnitId byActor =
        resolveUnit(byIndustry, laborCommitment.actor().id(), laborCommitment.actor());
    if (byActor != null) {
      return byActor;
    }
    return resolveUnit(byIndustry, laborCommitment.activity(), laborCommitment.actor());
  }

  /**
   * 在"产业串 → unit 列表"索引里解析唯一 unit：优先 {@code unit.operator == operator} 的那条；否则该产业下只有一个 unit 时用它。多个候选且
   * operator 不唯一 ⇒ 抛（混合态拒绝猜）。
   */
  private static ProductionUnitId resolveUnit(
      Map<String, List<ProductionProcess>> byIndustry, String industryValue, ActorRef operator) {
    List<ProductionProcess> candidates = byIndustry.getOrDefault(industryValue, List.of());
    if (candidates.isEmpty()) {
      return null;
    }
    List<ProductionProcess> matchingOperator = new ArrayList<>();
    for (ProductionProcess candidate : candidates) {
      if (candidate.operator().equals(operator)) {
        matchingOperator.add(candidate);
      }
    }
    if (matchingOperator.size() == 1) {
      return matchingOperator.get(0).id();
    }
    if (candidates.size() == 1) {
      return candidates.get(0).id();
    }
    throw new IllegalStateException(
        "旧档迁移失败：产业串 "
            + industryValue
            + " 对应多条 unit "
            + candidates
            + "，operator="
            + operator
            + " ⇒ 无法确定是哪一条（拒绝猜）");
  }

  /** 该产业串是不是一个已登记的产业模板 id（用来区分"旧档键"与"坏数据"）。 */
  private static boolean legacyIndustryExists(Map<IndustryId, Industry> industries, String value) {
    return industries != null && industries.containsKey(new IndustryId(value));
  }

  /** 产业串 → 该产业的全部 unit（保序：unit 表插入序）。 */
  private static Map<String, List<ProductionProcess>> unitsByIndustry(
      Iterable<ProductionProcess> units) {
    Map<String, List<ProductionProcess>> byIndustry = new LinkedHashMap<>();
    for (ProductionProcess unit : units) {
      byIndustry.computeIfAbsent(unit.industry().value(), ignored -> new ArrayList<>()).add(unit);
    }
    return byIndustry;
  }

  /** 家户行的候选：同格 + 同居住类型 + population &gt; 0，按 HouseholdId 字典序（残差顺序确定）。 */
  private static List<HouseholdEconomy> candidateHouseholdEconomies(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HexCoord hex,
      ResidenceKind residence) {
    List<HouseholdEconomy> candidateHouseholdEconomies = new ArrayList<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (householdEconomy.view().hex().equals(hex)
          && householdEconomy.view().residence() == residence
          && householdEconomy.population() > 0L) {
        candidateHouseholdEconomies.add(householdEconomy);
      }
    }
    candidateHouseholdEconomies.sort(
        Comparator.comparing(householdEconomy -> householdEconomy.id().value()));
    return candidateHouseholdEconomies;
  }

  /** 配额指向的产业格：优先按 actor id 查产业表；拿不到格键 ⇒ 空（调用方按 pending/非 pending 分流）。 */
  private static Optional<HexCoord> industryHexOf(
      Map<IndustryId, Industry> industries, ActorRef actor) {
    IndustryId id = new IndustryId(actor.id());
    Industry industry = industries == null ? null : industries.get(id);
    if (industry != null) {
      Optional<String> key = IndustryHexKeys.hexKeyOf(industry.id());
      if (key.isPresent()) {
        return Optional.of(HexCoord.parse(key.get()));
      }
    }
    return IndustryHexKeys.hexKeyOf(id).map(HexCoord::parse);
  }

  /** 产业身份：actor id 必须命中产业表（迁移不改制度，拒绝把无法解析的 actor 静默造一条新产业）。 */
  private static IndustryId industryIdOf(
      Map<IndustryId, Industry> industries, ActorRef actor, HexCoord hex) {
    IndustryId id = new IndustryId(actor.id());
    if (industries == null || !industries.containsKey(id)) {
      throw new IllegalStateException("旧档迁移失败：劳动配额的 actor " + actor + "（格 " + hex + "）不是已存在的产业");
    }
    return id;
  }

  private static Optional<EconomyMeta> migrateMeta(Optional<EconomyMeta> meta) {
    if (meta == null || meta.isEmpty()) {
      return meta;
    }
    EconomyMeta value = meta.get();
    return Optional.of(
        new EconomyMeta(
            value.mapId(),
            value.activatedDay(),
            value.lastClosedCycle(),
            EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
            value.migrationSource().isPresent()
                ? value.migrationSource()
                : Optional.of(LEGACY_MIGRATION_SOURCE)));
  }
}
