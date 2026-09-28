package io.mosire.simos.economy.migrate;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>S1 旧档迁移器（economy 侧）：CohortKey 身份 → HouseholdId 身份 + 成员份额 + 劳动家户归属</b>。
 *
 * <p>★★ <b>触发条件与幂等</b>（{@code EconomyData} 的构造期调用，也是本类的公开入口）：
 *
 * <pre>
 * needed = 任一 LaborAllocation.household 是 pending 占位
 *       或 classes 非空而 memberships 为空（旧档没有成员份额组件）
 *       或 industries 非空而 assetShares 为空（旧档没有资产份额组件 ⇒ 一次性按旧 capacity 物化，见下）
 * </pre>
 *
 * <p>★★ <b>R3B.1 的实物份额口径（旧档迁移）</b>：
 *
 * <ul>
 *   <li><b>旧档已有 UseRight</b>：codec 读入时一对一整形成 {@code AssetShare(owner=operator=旧 holder,
 *       industry=旧 activity, quantity/kind/id 原样)} —— 这里只原样搬运，<b>不</b>凭空拆出地主/佃户/多生产单位；
 *   <li><b>旧档没有 useRights 组件但有 Industry</b>：对每个 {@code capacity > 0} 的项生成一条 {@code
 *       AssetShare(owner=operator=旧 Industry.operator, quantity=capacity, kind=OWNED)}。★ 这是旧档的
 *       <b>一次性初始实物份额物化</b>；{@code Industry.capacity} 从此只是过渡技术字段，<b>不是</b>持续上界。
 * </ul>
 *
 * <p>迁移执行后：所有 allocation 的 household 都是真实家户（∈ classes）、{@code memberships} 补齐； 再调用 {@link #needed}
 * 为 false ⇒ <b>不会二次迁移</b>。{@link io.mosire.simos.economy.EconomyData} 的构造期 末尾还有一层守卫（pending
 * 不得进入已建成的状态），所以本类漏迁会在构造期当场炸，不会静默落盘。
 *
 * <p>★★ <b>旧 LaborAllocation → 新 LaborAllocation 的拆分规则</b>（计划 S1.5）：
 *
 * <pre>
 * 对每条旧配额：取它指向产业所在格 + ResidenceKind.ofLot(lot) 的既有家户行（population &gt; 0）
 * 按行人口最大余数法把 laborMilli 拆到各行对应的 HouseholdId；残差按下标序（家户 id 字典序排序后）
 * Σ laborMilli 逐值不变；新 id = alloc-&lt;industry&gt;-&lt;lot&gt;-&lt;household&gt;
 * </pre>
 *
 * <p>★★ <b>Membership 的反推规则与一处如实记的近似</b>：
 *
 * <pre>
 * 每个 (格, 居住类型) 上，各 lot 的供给权重 = 它在该处全部旧配额的 laborMilli 之和
 * 该处的每个家户行，按权重把 row.population 拆到这些 lot ⇒ Membership(lot, household, count)
 * </pre>
 *
 * <p>★ <b>没有配额供给的家户行</b>（例如只有 0-14 岁批次、创世不给它发配额）：用一个确定性的 {@code legacy-<view>} 合成 lot 承载，保证 {@code
 * Σ Membership.count == Σ ClassRow.population} 这条 economy 侧可判的守恒成立。★ 它<b>不等于</b> social 侧 {@code
 * PopulationGroup.count}，本迁移器也不声称逐 lot 相等：旧档的跨切片对账由 <b>app 协调器</b>在首次推进前完成 （{@code
 * MembershipWriteback.rebuildLegacy} 按 social 真实批次重建份额并丢弃合成 lot；片区总量对不上 ⇒ fail-closed）。
 * 本迁移器只负责"economy 内部能过构造期守卫、劳动/资产份额不丢"。
 *
 * <p>★ <b>旧 DebtId 原样保留</b>（不重算）：本类只改 LaborAllocation/Membership；Debt 两端由 codec 的 {@code
 * HouseholdId} 反序列化器按视图映射，id 不动。
 */
public final class LegacyHouseholdMigration {

  /** 合成 lot 的前缀（只覆盖"旧档没有任何配额供给"的家户行，见类注）。 */
  public static final String SYNTHETIC_LOT_PREFIX = "legacy-";

  /** 迁移来源标签（旧档没有 revision 上下文时用的具名值；有值则原样保留）。 */
  public static final String LEGACY_MIGRATION_SOURCE = "legacy-pre-modern-v1";

  private LegacyHouseholdMigration() {}

  /** 迁移结果（EconomyData 的构造期把三个参数整体换掉）。 */
  public record Result(
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<MembershipId, Membership> memberships,
      Map<AssetShareId, AssetShare> assetShares,
      Optional<EconomyMeta> meta) {}

  /** ★ 迁移是否已完成（meta 的 rulesVersion 标记；无 meta 视为未迁移）。 */
  private static boolean isMigrated(Optional<EconomyMeta> meta) {
    return meta != null
        && meta.isPresent()
        && EconomyMeta.RULES_VERSION_PRE_MODERN_V1.equals(meta.get().rulesVersion());
  }

  /** ★ 是否还需迁移（见类注的两条触发条件；幂等的判据）。 */
  public static boolean needed(
      Map<IndustryId, Industry> industries,
      Map<HouseholdId, ClassRow> classes,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<MembershipId, Membership> memberships,
      Map<AssetShareId, AssetShare> assetShares,
      Optional<EconomyMeta> meta) {
    // ★ 迁移完成的判据 = meta.rulesVersion 已升到 pre-modern-v1（幂等标记）；它保证"没有产能 ⇒ 生成的资产份额表
    //   仍为空"的状态不会被日复一日地重复迁移。
    boolean migrated = isMigrated(meta);
    if (!migrated
        && classes != null
        && !classes.isEmpty()
        && (memberships == null || memberships.isEmpty())) {
      return true;
    }
    if (!migrated
        && industries != null
        && !industries.isEmpty()
        && (assetShares == null || assetShares.isEmpty())) {
      return true; // 旧档没有资产份额组件 ⇒ 按旧 Industry.capacity + operator 一次性物化整额 OWNED
    }
    if (allocations != null) {
      for (LaborAllocation allocation : allocations.values()) {
        if (allocation != null && allocation.household().isPending()) {
          return true;
        }
      }
    }
    return false;
  }

  /** ★★ <b>执行迁移</b>：不修改入参；返回全新的三张表。失败一律抛具名异常（不静默丢劳动/丢人口）。 */
  public static Result migrate(
      Map<IndustryId, Industry> industries,
      Map<HouseholdId, ClassRow> classes,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<MembershipId, Membership> memberships,
      Map<AssetShareId, AssetShare> assetShares,
      Optional<EconomyMeta> meta) {
    Map<LaborAllocationId, LaborAllocation> migratedAllocations = new LinkedHashMap<>();
    // 每条旧配额在迁移前的“位置/居住/权重”快照，供 Membership 反推用（迁移后 id 与原表已不同）。
    List<FormerAllocation> former = new ArrayList<>();
    // ★ M4：本次迁移是否会重建 memberships —— 能定位格的配额全量进 former（见下）；定位不了的非 pending
    //   配额若同时存在，就是无法逐 lot 对账的混合态 ⇒ 立刻 fail-closed，不静默按权重近似。
    boolean rebuildMemberships = classes != null && !classes.isEmpty();
    for (Map.Entry<LaborAllocationId, LaborAllocation> entry : allocations.entrySet()) {
      LaborAllocation allocation = entry.getValue();
      Optional<HexCoord> maybeHex = industryHexOf(industries, allocation.actor());
      if (maybeHex.isEmpty()) {
        if (!allocation.household().isPending()) {
          if (rebuildMemberships) {
            throw new IllegalStateException(
                "旧档迁移失败：配额 "
                    + entry.getKey()
                    + " 的 actor "
                    + allocation.actor()
                    + " 无法定位产业格，而本次迁移要重建 memberships ⇒ 该 lot 进不了权重表、无法逐 lot 对账"
                    + "（混合态拒绝静默近似：请先补齐产业格键，或不要在同一批里混入无法定位的配额）");
          }
          // 非 pending 的新档配额（actor 不是产业 id 也能合法存在 —— 家户自营）：本次不重建 memberships ⇒ 原样带过。
          migratedAllocations.put(entry.getKey(), allocation);
          continue;
        }
        throw new IllegalStateException(
            "旧档迁移失败：配额 "
                + entry.getKey()
                + " 的 actor 既不在产业表里、id 也不带格键，无法定位它的格: "
                + allocation.actor());
      }
      HexCoord hex = maybeHex.get();
      ResidenceKind residence = ResidenceKind.ofLot(allocation.group());
      // ★★ M4：凡**能定位产业格**的配额（pending 或非 pending）都进 former —— deriveMemberships 的
      //   lot 权重表必须覆盖全部可定位的劳动归属，不能只收 pending 那一半。非 pending 的配额另外照旧原样进
      //   migratedAllocations（它已有真实家户，不再拆分）；但它的 lot 权重同样参与本次成员份额反推。
      former.add(new FormerAllocation(allocation, hex, residence));
      if (!allocation.household().isPending()) {
        migratedAllocations.put(entry.getKey(), allocation);
        continue;
      }
      List<ClassRow> candidates = candidateRows(classes, hex, residence);
      if (candidates.isEmpty()) {
        throw new IllegalStateException(
            "旧档迁移失败：配额 "
                + entry.getKey()
                + " 指向 "
                + allocation.actor()
                + "（格 "
                + hex
                + "，居住 "
                + residence.value()
                + "）在该格没有任何人口非 0 的家户行 —— 无法把劳动归属到真实家户（拒绝静默丢劳动）");
      }
      long totalWeight = 0L;
      long[] weights = new long[candidates.size()];
      for (int i = 0; i < candidates.size(); i++) {
        weights[i] = candidates.get(i).population();
        totalWeight = Math.addExact(totalWeight, weights[i]);
      }
      long[] parts = ProportionalSplit.byDenominator(allocation.laborMilli(), weights, totalWeight);
      IndustryId industry = industryIdOf(industries, allocation.actor(), hex);
      for (int i = 0; i < candidates.size(); i++) {
        HouseholdId household = candidates.get(i).id();
        LaborAllocationId newId = LaborAllocation.idOf(industry, allocation.group(), household);
        if (migratedAllocations.putIfAbsent(
                newId,
                new LaborAllocation(
                    newId,
                    allocation.group(),
                    household,
                    allocation.actor(),
                    allocation.activity(),
                    parts[i],
                    allocation.period()))
            != null) {
          throw new IllegalStateException("旧档迁移产生了重复的劳动配额 id: " + newId);
        }
      }
    }
    Map<MembershipId, Membership> migratedMemberships = new LinkedHashMap<>();
    if (classes != null && !classes.isEmpty()) {
      deriveMemberships(classes, former, migratedMemberships);
    }
    Map<AssetShareId, AssetShare> migratedAssetShares = new LinkedHashMap<>();
    if (assetShares != null) {
      // ★★ R3B.1：旧档已有 UseRight 时，codec 已按"一对一把 holder 填成 owner=operator"整形成 AssetShare
      //   （见 EconomyCodec 的旧节点整形）；这里只原样搬运 ⇒ id 字符串不改写、不重算、不拆地主/佃户。
      migratedAssetShares.putAll(assetShares);
    }
    if (migratedAssetShares.isEmpty() && industries != null) {
      generateOwnedAssetShares(industries, migratedAssetShares);
    }
    Optional<EconomyMeta> migratedMeta = migrateMeta(meta);
    return new Result(migratedAllocations, migratedMemberships, migratedAssetShares, migratedMeta);
  }

  /**
   * ★★ <b>旧档：按 {@code Industry.capacity + operator} 生成整额 {@code OWNED} 实物份额</b>（S1.5 明文；R3B.1 换型）。
   *
   * <p>★★ <b>这是一次性初始化，不是持续上界</b>：旧档没有 UseRight 组件时，用旧的 {@code Industry.capacity} 给该产业的
   * operator 物化一份初始实物账（{@code owner == operator == 旧 Industry.operator}）；此后实物总量由 AssetShare
   * 自己说话，{@code EconomyData} <b>不</b>再拿 capacity 当上界。
   *
   * <p>★ 只生成 {@code capacity > 0} 的项（0 产能 ⇒ 没有要登记的实物）；{@code sequence = 0}（每 {@code (industry,
   * asset, owner, operator, kind)} 在旧档里至多一条）。
   */
  private static void generateOwnedAssetShares(
      Map<IndustryId, Industry> industries, Map<AssetShareId, AssetShare> out) {
    for (Industry industry : industries.values()) {
      for (Map.Entry<io.mosire.simos.actor.api.asset.AssetKind, Long> capacity :
          industry.capacity().entrySet()) {
        if (capacity.getValue() <= 0L) {
          continue;
        }
        AssetShareId id =
            AssetShare.idOf(
                industry.id(),
                capacity.getKey(),
                industry.operator(),
                industry.operator(),
                AssetShare.RightKind.OWNED,
                0L);
        out.put(
            id,
            new AssetShare(
                id,
                industry.id(),
                capacity.getKey(),
                industry.operator(),
                industry.operator(),
                capacity.getValue(),
                AssetShare.RightKind.OWNED));
      }
    }
  }

  /** 旧配额快照（拆 id 之后仍需要它来推成员份额）。 */
  private record FormerAllocation(
      LaborAllocation allocation, HexCoord hex, ResidenceKind residence) {}

  /**
   * ★★ <b>每个 (格, 居住类型) 上的供给权重</b>：lot → Σ laborMilli。
   *
   * <p>★ <b>M4 的口径</b>：{@code former} 由 {@link #migrate} <b>全量</b>收集“凡能定位产业格”的配额（pending 与
   * non-pending 都算），不再只收 pending；因此每个可对账的 lot 都会进入本权重表。无法定位格的非 pending 配额 在 {@code migrate} 里已作为混合态
   * fail-closed，不会走到这里被静默略过。
   */
  private static void deriveMemberships(
      Map<HouseholdId, ClassRow> classes,
      List<FormerAllocation> former,
      Map<MembershipId, Membership> out) {
    Map<ViewKey, Map<PeopleLotId, Long>> weightsByView = new LinkedHashMap<>();
    for (FormerAllocation allocation : former) {
      weightsByView
          .computeIfAbsent(
              new ViewKey(allocation.hex(), allocation.residence()),
              ignored -> new LinkedHashMap<>())
          .merge(
              allocation.allocation().group(),
              allocation.allocation().laborMilli(),
              Math::addExact);
    }
    for (ClassRow row : classes.values()) {
      ViewKey view = new ViewKey(row.view().hex(), row.view().residence());
      Map<PeopleLotId, Long> weights = weightsByView.get(view);
      if (weights == null || weights.isEmpty()) {
        // ★ 没有配额供给的家户行：用确定性的合成 lot 承载，保证 Σ 成员 == Σ 行人口（见类注的近似）。
        PeopleLotId synthetic = new PeopleLotId(SYNTHETIC_LOT_PREFIX + row.view());
        addMembership(out, synthetic, row.id(), row.population());
        continue;
      }
      List<PeopleLotId> lots = new ArrayList<>(weights.keySet());
      lots.sort(Comparator.comparing(PeopleLotId::value));
      long[] lotWeights = new long[lots.size()];
      long totalWeight = 0L;
      for (int i = 0; i < lots.size(); i++) {
        lotWeights[i] = weights.get(lots.get(i));
        totalWeight = Math.addExact(totalWeight, lotWeights[i]);
      }
      long[] parts = ProportionalSplit.byDenominator(row.population(), lotWeights, totalWeight);
      for (int i = 0; i < lots.size(); i++) {
        addMembership(out, lots.get(i), row.id(), parts[i]);
      }
    }
  }

  private static void addMembership(
      Map<MembershipId, Membership> out, PeopleLotId lot, HouseholdId household, long count) {
    MembershipId id = Membership.idOf(lot, household);
    Membership previous = out.get(id);
    if (previous == null) {
      out.put(id, new Membership(id, lot, household, count));
      return;
    }
    out.put(id, new Membership(id, lot, household, Math.addExact(previous.count(), count)));
  }

  /** 家户行的候选：同格 + 同居住类型 + population &gt; 0，按 HouseholdId 字典序（残差顺序确定）。 */
  private static List<ClassRow> candidateRows(
      Map<HouseholdId, ClassRow> classes, HexCoord hex, ResidenceKind residence) {
    List<ClassRow> candidates = new ArrayList<>();
    for (ClassRow row : classes.values()) {
      if (row.view().hex().equals(hex)
          && row.view().residence() == residence
          && row.population() > 0L) {
        candidates.add(row);
      }
    }
    candidates.sort(Comparator.comparing(row -> row.id().value()));
    return candidates;
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

  /** (格, 居住类型) 的复合键（Membership 反推的分组，不参与持久化）。 */
  private record ViewKey(HexCoord hex, ResidenceKind residence) {}
}
