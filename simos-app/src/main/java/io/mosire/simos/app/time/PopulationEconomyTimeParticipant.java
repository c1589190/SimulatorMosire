package io.mosire.simos.app.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ **人口—经济协调器**（R4）：**唯一同时看得见 {@code social} 与 {@code economy} 的推进参与者** —— 于是"人"第一次真的随时间变：**年龄推进
 * + 逐日生理压力 + 月度出生/死亡**，而且**死亡会同时反映到经济侧的阶层行与劳动配额上**。
 *
 * <p>★★ **为什么必须有它**（不是"图省事"，是结构上只能如此）：
 *
 * <ol>
 *   <li>**出生/死亡只能算在人口那一侧**（`年龄 × 性别 × 基础死亡率 × 生理压力` —— 年龄与性别是 {@link PopulationGroup}
 *       的属性），**而"吃得饱不饱"只算在经济那一侧**（需求与实得都在 {@code FlowRow} 里）⇒ 判定需要两侧同时在场；
 *   <li>**§十一 等价性**（一次推 N 天 == N 次单日）要求"逐日"这条语义落在**同一个参与者内部** —— 若让两个参与者各自读对方的**基态**，一次推 365
 *       天时社会侧只能看到第 0 天的经济状态， 而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**；
 *   <li>跨切片写要求"同一模块只能有一个写者"（{@code TimeProposalResolver} 的写-写检查）⇒ 同时写这两片的参与者**只能有一份**。
 * </ol>
 *
 * <p>★ 它住在 {@code simos-app}：设计稿 §8.2 的原文——"跨 {@code social}+{@code economy} 的协调器**必须住 {@code
 * simos-app}** （唯一认识所有模块的地方）"。★ 它**内联调用** {@link EconomySettlement} 的包内可见日结算入口， 从而与 {@code
 * EconomyTimeParticipant} 共用同一个结算实现（**只有一条真相**，不是两份公式）。
 *
 * <p>★★ **一天的次序**（缺一不可）：
 *
 * <pre>
 * ① 经济结算一天（消费/借粮/进度/劳动/周期末收获）      —— 行人口仍是"上个月末"的
 * ② 生理压力：读**当天**的发生额（需求与实得）⇒ 逐批次 stressAfter
 * ③ 每 30 天：月度结算（出生/死亡）⇒ 改社会侧的 count，并把同一份账回写经济侧（行人口/配额/流水）
 * </pre>
 *
 * <p>★★ **它是"人口守恒"的落点**：出生与死亡在这一处算出来、在两侧各落一次账（社会侧改 {@code count}、 经济侧改行人口与 {@code
 * FlowRow.births/deaths}）⇒ {@code Σ新人口 == Σ旧人口 + 出生 − 死亡} 逐值可核。
 *
 * <p>★ **未激活/无上界**：经济未激活（{@code meta} 空）⇒ **两侧都交不变变更集**（没有生活资料信号 ⇒ 人口不动， 这正是"世界还没播种"该有的样子）；{@code
 * range.to} 缺省 ⇒ 同样交不变变更集、不抛（该推进随后必被 Core 拒）。
 */
public final class PopulationEconomyTimeParticipant implements TimeParticipant {

  /** 参与者身份（**不是模块名**：它同时写 {@code social} 与 {@code economy} 两个模块）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";

  private final String mapId;

  public PopulationEconomyTimeParticipant(String mapId) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    EconomyData economy = economyOf(state);
    SocialData social = socialOf(state);

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    for (IndustryId id : economy.industries().keySet()) {
      reads.add(economyAddress("industry", id.value()));
      writes.add(economyAddress("industry", id.value()));
    }
    for (ClassKey key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.industry().value() + "." + key.slot().value()));
      writes.add(economyAddress("class", key.industry().value() + "." + key.slot().value()));
    }
    for (ClassKey key : economy.flows().keySet()) {
      writes.add(economyAddress("flow", key.industry().value() + "." + key.slot().value()));
    }
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }

    Optional<io.mosire.simos.util.time.SimosTimestamp> to = range.to();
    if (to.isEmpty() || economy.meta().isEmpty()) {
      // 无上界推进 / 经济未激活 ⇒ 两侧都不动（但**交的是不变变更集，不是空提案**：契约原文）。
      return new WorldTimeProposal(
          NAMESPACE,
          Map.of(
              ECONOMY, EconomyChangeSet.between(economy, economy),
              SOCIAL, SocialChangeSet.between(social, social)),
          reads,
          writes);
    }

    Map<PeopleLotId, List<IndustryId>> industriesOf = industriesOf(economy);
    EconomyDayStepper stepper = new EconomyDayStepper(economy);
    SocialData currentSocial = social;
    for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
      LinkedHashMap<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> unmetBefore =
          unmetOf(stepper.flows());
      stepper.step(day);
      // ② 逐日生理压力（读**当天**的需求与实得 —— 两者都在刚结算完的账上）。
      currentSocial =
          applyDailyStress(
              stepper.data(), currentSocial, stepper.flows(), unmetBefore, industriesOf);
      // ③ 月度结算：出生/死亡 → 先改人口（真值源），再按同一份账回写经济侧。
      if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L) {
        PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(currentSocial, day);
        currentSocial = outcome.data();
        if (!outcome.isEmpty()) {
          stepper.applyPopulationChange(outcome.changeList());
        }
      }
    }
    EconomyData currentEconomy = stepper.finish();
    return new WorldTimeProposal(
        NAMESPACE,
        Map.of(
            ECONOMY, EconomyChangeSet.between(economy, currentEconomy),
            SOCIAL, SocialChangeSet.between(social, currentSocial)),
        reads,
        writes);
  }

  // ── 逐日生理压力（社会侧唯一的日常写点）────────────────────────────────────────────────

  /**
   * ★★ **把当天的生活资料满足情况折成每个批次的压力**（spec §七："短期缺粮加一些、恢复供给后逐渐消退"）。
   *
   * <pre>
   * 逐产业：粮/布的"当日需求"  = Σ该产业各行 {@code naturalNeeds[商品]}（结算当天写回的那一份 ⇒ 与结算同源）
   *          粮/布的"当日实得"  = 需求 − 当日新记进 {@code FlowRow.unmetNeed[商品]} 的那一笔
   * 逐批次：把**它供给的那些产业**汇总（权重 = 各产业的当日需求）⇒ 满足率‰ ⇒ {@link PopulationDynamics#stressAfter}
   * </pre>
   *
   * <p>★★ **批次 ↔ 产业的对应只从 {@code LaborAllocation} 来**（人供给谁，就吃谁的饭）：真档里农村批次供给 农业+家庭纺织 （纺织行人口为 0 ⇒
   * 其需求也是 0）⇒ 满足率只由农业行决定；城镇批次供给手工业 ⇒ 同理。**没有第二张映射表。**
   *
   * <p>★ **没有需求的批次不动**（{@code 需求 == 0} ⇒ 满足率按 1000‰ 计，压力照常消退）："这一天没记账"不等于"饿了一天"。
   *
   * @param unmetBefore 当日结算**之前**的 {@code FlowRow.unmetNeed} 快照（用于取"当天新增的那一笔"）
   */
  private static SocialData applyDailyStress(
      EconomyData economy,
      SocialData social,
      Map<ClassKey, FlowRow> flows,
      Map<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> unmetBefore,
      Map<PeopleLotId, List<IndustryId>> industriesOf) {
    if (social.groups().isEmpty() || economy.classes().isEmpty()) {
      return social; // 没有批次/没有经济 ⇒ 没有可算的人
    }
    Map<IndustryId, long[]> byIndustry = dailyProvisioning(economy, flows, unmetBefore);
    Map<PeopleLotId, PopulationGroup> next = new LinkedHashMap<>(social.groups());
    for (PopulationGroup group : social.groups().values()) {
      List<IndustryId> targets = industriesOf.getOrDefault(group.id(), List.of());
      if (targets.isEmpty()) {
        continue;
      }
      long grainNeed = 0L;
      long grainGot = 0L;
      long clothNeed = 0L;
      long clothGot = 0L;
      for (IndustryId id : targets) {
        long[] row = byIndustry.get(id);
        if (row == null) {
          continue;
        }
        grainNeed += row[0];
        grainGot += row[1];
        clothNeed += row[2];
        clothGot += row[3];
      }
      long stress =
          PopulationDynamics.stressAfter(
              group.physiologicalStress(),
              satisfactionPerMille(grainGot, grainNeed),
              satisfactionPerMille(clothGot, clothNeed));
      if (stress != group.physiologicalStress()) {
        next.put(group.id(), group.withPhysiologicalStress(stress));
      }
    }
    return social.withGroups(next);
  }

  /** 逐产业的当日 {@code [粮需求, 粮实得, 布需求, 布实得]}（毫单位）。 */
  private static Map<IndustryId, long[]> dailyProvisioning(
      EconomyData economy,
      Map<ClassKey, FlowRow> flows,
      Map<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> unmetBefore) {
    Map<IndustryId, long[]> byIndustry = new LinkedHashMap<>();
    for (Map.Entry<ClassKey, ClassRow> entry : economy.classes().entrySet()) {
      long[] row = byIndustry.computeIfAbsent(entry.getKey().industry(), ignored -> new long[4]);
      long grainNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
      long clothNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.CLOTH, 0L);
      row[0] += grainNeed;
      row[2] += clothNeed;
      row[1] += grainNeed - dayUnmet(flows, unmetBefore, entry.getKey(), EconomySettlement.GRAIN);
      row[3] += clothNeed - dayUnmet(flows, unmetBefore, entry.getKey(), EconomySettlement.CLOTH);
    }
    return byIndustry;
  }

  /** 某行某商品**当天新增**的未满足需求（= 结算后 − 结算前）。 */
  private static long dayUnmet(
      Map<ClassKey, FlowRow> flows,
      Map<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> unmetBefore,
      ClassKey key,
      io.mosire.simos.economy.api.id.CommodityId commodity) {
    FlowRow after = flows.get(key);
    long now = after == null ? 0L : after.unmetNeed().getOrDefault(commodity, 0L);
    Map<io.mosire.simos.economy.api.id.CommodityId, Long> before = unmetBefore.get(key);
    long was = before == null ? 0L : before.getOrDefault(commodity, 0L);
    return Math.max(0L, now - was);
  }

  /** 满足率（‰）：{@code 需求 == 0 ⇒ 1000}（"这一天没记账"不等于"饿了一天"）；否则 {@code 实得 × 1000 ÷ 需求}，封顶 1000。 */
  static long satisfactionPerMille(long got, long need) {
    if (need <= 0L) {
      return 1000L;
    }
    return Math.min(1000L, Math.max(0L, got) * 1000L / need);
  }

  /** 每个批次供给哪些产业（保序、去重；只认真的落在一个已存在产业上的 {@code actor.id}）。 */
  private static Map<PeopleLotId, List<IndustryId>> industriesOf(EconomyData economy) {
    Map<PeopleLotId, List<IndustryId>> byGroup = new LinkedHashMap<>();
    for (var allocation : economy.allocations().values()) {
      IndustryId id = new IndustryId(allocation.actor().id());
      if (!economy.industries().containsKey(id)) {
        continue;
      }
      List<IndustryId> list =
          byGroup.computeIfAbsent(allocation.group(), ignored -> new ArrayList<>());
      if (!list.contains(id)) {
        list.add(id);
      }
    }
    return byGroup;
  }

  /** 各行的 {@code unmetNeed} 快照（当日结算前）——只读一份，供"当天新增"的差分用。 */
  private static LinkedHashMap<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>>
      unmetOf(Map<ClassKey, FlowRow> flows) {
    LinkedHashMap<ClassKey, Map<io.mosire.simos.economy.api.id.CommodityId, Long>> copy =
        new LinkedHashMap<>();
    for (Map.Entry<ClassKey, FlowRow> entry : flows.entrySet()) {
      copy.put(entry.getKey(), entry.getValue().unmetNeed());
    }
    return copy;
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static SocialData socialOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(SOCIAL)
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  private String socialAddressRoot() {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId))).canonical();
  }

  private String economyAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String socialAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }
}
