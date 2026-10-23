package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyEntrySettlement.EntryContext;
import static io.mosire.simos.economy.time.EntryEvaluationBook.DemandSignal;
import static io.mosire.simos.economy.time.EntryEvaluationBook.ownAssets;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Entry 拆分 helper（由 EconomyEntrySettlement 抽取；行为不变）。 */
final class EntryCandidateBook {

  private EntryCandidateBook() {}

  /**
   * ★★ <b>方案池</b>（裁定八）：{@code hexKeyOf(id)} 为空的<b>不绑地点</b>定义才是"可以被采用的方案"；带格键的定义 （{@code farm@0_0}
   * 一类，播种产物）是"某格已物化好的定义"，只由 {@link #planDefinitionFor} 沿用，不进本池。
   *
   * <p>★ 按 id 值升序返回（评估序是内容的纯函数）；★ 空池 = 今天没有任何可采用的方案 ⇒ 调用方连评估都不做。
   */
  static Map<IndustryId, IndustryDefinitionView> plans(
      Map<IndustryId, IndustryDefinitionView> industries) {
    Objects.requireNonNull(industries, "industries");
    List<IndustryId> ids = new ArrayList<>();
    for (IndustryId id : industries.keySet()) {
      if (io.mosire.simos.economy.api.id.IndustryHexKeys.hexKeyOf(id).isEmpty()) {
        ids.add(id);
      }
    }
    ids.sort(Comparator.comparing(IndustryId::value));
    Map<IndustryId, IndustryDefinitionView> out = new LinkedHashMap<>();
    for (IndustryId id : ids) {
      out.put(id, industries.get(id));
    }
    return out;
  }

  /**
   * ★★★ <b>方案定义 → 本格当刻有效定义</b>（§13.10.3 裁定五；B2c 按裁定八改成"按方案逐字段物化"）。
   *
   * <p>★★ <b>为什么要有"物化"这一步</b>：{@link #plans(Map)} 里的方案 id <b>不绑地点</b>，而实例引用的 {@link IndustryId}
   * 必须与资产份额的 {@code industry} 键（{@code <kind>@<q>_<r>}，读路径按格解析它）一致 —— 故本格定义 id 唯一拼写点是 {@link
   * IndustryHexKeys#id(String, int, int)}，并由 {@link
   * io.mosire.simos.production.contract.ProductionRuntime#registerPlan} 决定接不接受（合并/冲突规则在
   * production，见 {@code DefinitionRegistration}）。
   *
   * <p>★ <b>已存在的定义原样沿用</b>（不覆盖、不重写）；不存在则把方案的字段<b>逐值复制</b>过去、只换 id。 ★
   * 物化是**纯复制**：没有"第二份算式"、没有手写常量（旧候选口径的那套 slots/Split(500,500)/MoneyOutput.NONE 手工合成已随候选面删除）。
   */
  static IndustryDefinitionView planDefinitionFor(
      EntryContext context, IndustryDefinitionView plan, IndustryId industryId) {
    Optional<IndustryDefinitionView> existing = context.runtime.definitions().industry(industryId);
    if (existing.isPresent()) {
      return existing.get();
    }
    return new IndustryDefinitionView(
        industryId,
        plan.name(),
        plan.regime(),
        plan.skill(),
        plan.cycleDays(),
        plan.buildDays(),
        plan.capacityPerUnit(),
        plan.dailyInputPerUnit(),
        plan.dailyLaborPerUnit(),
        plan.laborPerUnit(),
        plan.outputPerUnit(),
        plan.cycleInputPerUnit(),
        plan.slots(),
        plan.allocation(),
        plan.moneyOutputPerUnit(),
        plan.experience(),
        plan.availability(),
        plan.acceptedRightKinds());
  }

  /**
   * ★★ <b>B2c：方案的产出商品集</b>（裁定八：产出按商品逐项处理）——{@code outputPerUnit} 里<b>逐值 &gt; 0</b> 的那些键，按 id
   * 值升序（评估序是内容的纯函数）。
   *
   * <p>★ 为什么只认正数项：{@code outputPerUnit} 允许 0 值行（"这一项不产"），把它当成"产出商品"会让需求匹配与 取价凭空多出一个不产出的商品。★ 空集 =
   * 本方案不产任何东西 ⇒ 需求信号恒 NONE（该对被跳过，不具名拒绝）。
   */
  static List<CommodityId> outputCommodities(IndustryDefinitionView plan) {
    List<CommodityId> out = new ArrayList<>();
    // ★ U4b2c：读 staged 有效配方（最终 HARVEST）；legacy recipe.outputPerUnit() 逐值同旧。
    for (Map.Entry<CommodityId, Long> entry : plan.recipe().outputPerUnit().entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        out.add(entry.getKey());
      }
    }
    out.sort(Comparator.comparing(CommodityId::value));
    return out;
  }

  /**
   * ★★★ <b>对本条 (家户, 候选) 做一次无副作用试算</b>（§13.10.4 第 1 条；I5/V1/V2）。
   *
   * <p>★★ <b>入参只给不可变值</b>（§4.2）：家户条件（可用劳动 / 资产 / 原料 / 相关经验条的熟练度与经验）+ 外部属性事实。★ <b>价格与需求不进这里</b> ——
   * 那两件是 economy 的打分与排序（本方法一个价都不读）。
   *
   * <p>★★ <b>D-032 §15.6（R6）两个口径（本方法只在这里拼装）</b>：
   *
   * <ul>
   *   <li><b>劳动 = 本周期累计可用劳动</b> = {@link HouseholdEconomy#laborMilli()}（每 tick 的时间预算）× {@code
   *       plan.cycleDays()}。{@code probe} 内部按 {@code ProductionEfficiency} 的 ① <b>÷cycleDays</b>
   *       折回日均 ⇒ 传每日值会把规模低估 {@code cycleDays} 倍（R6 的 C/D 对照）。溢出走 {@code Math.multiplyExact} ⇒ 调用方
   *       {@code ARITHMETIC_OVERFLOW} 具名拒绝（fail-closed）。
   *   <li><b>资产占用 = 候选将要实占的资产</b> = 自有份额 ∪ 外部允许来源（{@code assetOccupancy} 由调用方按此口径 合并好传入；{@link
   *       io.mosire.simos.production.contract.ProductionProbeRequest} 的契约是"0 不进表"）。 旧口径给空表 ⇒ {@code
   *       probe.capacityScaleOf} 显式"空占用 ⇒ 0" ⇒ 一切候选必被拒（R6）。
   * </ul>
   *
   * <p>★ 熟练度与经验按方案点名的<b>两条经验条</b>（专门 a + 可共用 b）逐条读 {@link
   * io.mosire.simos.production.contract.ProductionRuntime#masteryOf} / {@code
   * experienceOf}（经验账的只读读口；缺 ⇒ 0 是真实世界状态，不是配置错误）。
   *
   * <p>★★ <b>D-032 §15.11（E1-A2）：{@code probePlanId} 与 {@code plan} 的分工</b> —— {@code probe} 按
   * {@code request.plan()} 查 production 的**当刻已登记定义**；本格物化 id 在 {@link #planDefinitionFor} 里
   * 只合成、不登记（登记只发生在 execute→applyIntent 的 {@link WriteMode#REAL}，assess/planEntries 必须只读）⇒
   * 未物化时调用方改传方案池里的 **unbound 方案 id**（物化是纯复制、两定义逐值相同），否则首次采用会被 {@code plan-unknown} 拒。{@code plan}
   * 仍用于读入参（经验条 / 投入键 / {@code cycleDays}）。
   */
  static io.mosire.simos.production.contract.ProductionProbeResult probeEntry(
      EntryContext context,
      HouseholdId household,
      IndustryId probePlanId,
      Map<AssetKind, Long> ownAssets,
      Map<AssetKind, Long> assetOccupancy,
      IndustryDefinitionView plan) {
    Map<ExperienceTrackId, Long> mastery = new LinkedHashMap<>();
    Map<ExperienceTrackId, Long> experience = new LinkedHashMap<>();
    for (Optional<ExperienceTrackId> track :
        List.of(plan.experience().primaryTrack(), plan.experience().sharedTrack())) {
      if (track.isEmpty()) {
        continue;
      }
      mastery.put(track.get(), context.runtime.masteryOf(household, track.get()));
      experience.put(track.get(), context.runtime.experienceOf(household, track.get()));
    }
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    // ★ U4b2c：staged 方案的有效投入是跨阶段求和；legacy recipe.inputPerUnit() 逐值同旧。
    for (CommodityId commodity : plan.recipe().inputPerUnit().keySet()) {
      inputs.put(commodity, stockOf(context.householdGoods, household, commodity));
    }
    HouseholdEconomy householdEconomy = context.householdEconomies.get(household);
    // ★★ D-032 §15.6（R6）：`probe` 的 `laborMilli` 是**本周期累计**劳动（内部 ÷cycleDays），
    //   而 `HouseholdEconomy.laborMilli()` 是**每 tick（日）**时间预算 ⇒ 必须乘 cycleDays。
    long laborPeriodMilli = Math.multiplyExact(householdEconomy.laborMilli(), plan.cycleDays());
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            household, laborPeriodMilli, ownAssets, inputs, mastery, experience);
    return context.runtime.probe(
        new io.mosire.simos.production.contract.ProductionProbeRequest(
            probePlanId,
            conditions,
            assetOccupancy,
            Optional.empty(),
            io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY));
  }

  /**
   * ★ <b>把 `probe` 的读数按规模线性换算到实际采用的规模</b>（{@code probeValue × scale ÷ probeScale}）。
   *
   * <p>★ 为什么是线性：`probe` 的三件需求（劳动/资产/原料）在**基准规模**上是线性量（§13.8 裁定一： {@code baseScale × laborPerUnit}
   * 一类），而产出在 {@code finalScale} 上也是线性的 —— 效率倍率只把 整个基准规模乘一次，不是逐单位变化的函数。⇒ 缩小规模不改变"每单位要多少"，只改变总量。
   *
   * <p>★ 除零与 {@code probeScale == 0} 的退化：那表示"可行、但一个规模单位都做不了"（调用方在此之前已拒）， 这里给 0（不猜、不放大）。
   */
  static long scaleLinearly(long probeValue, long scale, long probeScale) {
    if (probeValue <= 0L || scale <= 0L || probeScale <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(probeValue, scale) / probeScale;
  }

  /**
   * 本户所在格的需求信号（HEX 命中本格 / HOUSEHOLD 命中本户；窗口 ≥ horizon 或永久）。
   *
   * <p>★★ B2c（裁定八）：命中面 = 方案<b>任一产出商品</b>（"主产出"概念已删）；{@code outputs} 为空则恒 {@link
   * DemandSignal#NONE}（该对被跳过）。单产出方案与旧口径逐值相同。
   */
  static DemandSignal demandSignal(
      HouseholdId household,
      List<CommodityId> outputs,
      HexCoord hex,
      long horizon,
      EntryContext context) {
    if (outputs.isEmpty()) {
      return DemandSignal.NONE;
    }
    boolean sawAny = false;
    for (HouseholdDemand demand : context.householdDemands.values()) {
      if (!outputs.contains(demand.commodity()) || !demand.effectiveOn(context.day)) {
        continue;
      }
      if (demand.scope() == HouseholdDemand.DemandScope.HOUSEHOLD) {
        if (!demand.household().orElseThrow().equals(household)) {
          continue;
        }
      } else if (!demand.hex().orElseThrow().equals(hex)) {
        continue;
      }
      sawAny = true;
      if (demand.expiresDay() < 0L || demand.expiresDay() - context.day >= horizon) {
        return DemandSignal.OK;
      }
    }
    return sawAny ? DemandSignal.TOO_SHORT : DemandSignal.NONE;
  }

  static long stockOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId household,
      CommodityId commodity) {
    Map<CommodityId, Long> goods = householdGoods.get(household);
    return goods == null ? 0L : goods.getOrDefault(commodity, 0L);
  }
}
