package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>② 执行：回执</b>（D-032 约束设计书 §4.2「输出：实际消耗 · 实毛产（含铸币候选数量）· 进度变化 · <b>实际出工家户与各自贡献</b> · 精度余数」）。
 *
 * <p>★★ <b>它是"生产"与"经济关系"之间唯一的交接面</b>（边界裁决 §5）：economy 拿着它去算估值、工资/租金/ 分成，持账方拿着它去落账。★
 * 因此它必须<b>逐笔对得上</b>：{@link #consumedInputs()} 是实际扣掉的、 {@link #grossOutput()} 是实际产出的、{@link
 * #mintCandidates()} 是<b>候选</b>数量（发行与入账留 economy/持账方， I12）。
 *
 * <p>★★ <b>"实毛产"是毛产（gross），不是净得</b>（I9）：协作生产<b>只记一份总产出</b>， "出过工"不等于取得全部产品——产品怎么分是 economy 的 {@code
 * ProductionRules}/{@code ProductionSettlement} 的事（§4.3 第 6 段）。回执保留 {@link
 * #laborContributionsMilli()} 正是为了让"经验按实际贡献累加"可核对。
 *
 * <p>★★ <b>铸币是候选，不是发行</b>（I12）：{@link #mintCandidates()} 只是"技术上这一周期能铸出多少"； 发行资格、政府上限、发行记录与账户入账一律留
 * economy/持账方，且 economy <b>不得</b>再覆盖一次产出率。
 *
 * <p>★★ <b>A′ 批（§13.8 裁定一）：产出规模与投入消耗是两个口径</b>——{@link #scale()} / {@link #grossOutput()} / {@link
 * #mintCandidates()} 按<b>产出规模</b>（{@code finalScale} = 基准规模整体乘一次效率倍率）；{@link #consumedInputs()}
 * 是<b>实际扣到</b>的投入（按基准规模那一档， 不会因熟练度而多占）。⇒ 同样的投入产出更多，回执两侧各自可对账。 ★ {@link #experienceGrowth()} 是"本
 * tick 按<b>家户 × 经验条</b>归并后一次换算"的增量（不足一个整经验点 的部分结转在 {@code ProductionExperienceFeature.remainder}）。
 *
 * @param unit 实例稳定身份；不得为 null
 * @param plan 方案稳定 ID；不得为 null
 * @param cycleClosed 本次执行是否让周期关账（进度到达 {@code cycleDays}）
 * @param created 本次是否<b>新建</b>了实例（创建请求路径）
 * @param progressDaysBefore 推进前进度（天）；不得为负
 * @param progressDaysAfter 推进后进度（天）；不得为负（关账后回到 0）
 * @param scale 周期关账时算出的<b>实际产出</b>规模（{@code finalScale}）；未关账 ⇒ 0；不得为负
 * @param consumedInputs 实际消耗的原料（毫单位）；未关账 ⇒ 本次投入，关账 ⇒ 本周期累计投入；不得为 null
 * @param grossOutput 实毛产（物理量，毫单位；按 {@code scale} = {@code finalScale} 算）；未关账 ⇒ 空表；不得为 null
 * @param mintCandidates 铸币候选数量（毫货币）；未关账或无铸币路 ⇒ 空表；不得为 null
 * @param laborContributionsMilli ★ <b>本次 tick</b>逐 (家户×角色) 实际劳动（只含 &gt;0 项）；不得为 null。
 *     不得成为第二权威：按户合计只经 {@link #laborByHouseholdMilli()} 派生
 * @param cycleContributions ★★ <b>本周期</b>逐 (家户×角色) 实际贡献台账（含"按各 tick 当刻效率<b>精确加权</b>的劳动"）
 *     ——关账时它是整周期的事实（不是最后一天），未关账时它是"到本次为止"的累计； 不得为 null。★ 键必须与值内 家户/角色一致；同一户同角色多次出工累计、不同角色分行
 * @param cycleLaborMilli 本周期累计实际劳动（含本次）；不得为负
 * @param efficiencyState 推进后的效率精度余数（本域状态的回执读数；null = 缺行）
 * @param experienceGrowth 本次因实际贡献产生的经验增长（家户×经验条 → 增量）；不得为 null、值必须为正 （0 不进表——★ 未凑成整经验点的那部分在 {@code
 *     ProductionExperienceFeature.remainder} 里结转，不在这里虚报）
 * @param experienceGrowthLines ★ 可审计的逐角色经验增长明细（家户×角色×经验条×增量）；不得为 null。它按 {@code (household, track)}
 *     汇总后必须逐项等于 {@link #experienceGrowth()}
 * @param newlyUnlocked 本次新解锁的方案（永久记忆，I7）；不得为 null
 */
public record ProductionReceipt(
    ProductionUnitId unit,
    IndustryId plan,
    boolean cycleClosed,
    boolean created,
    long progressDaysBefore,
    long progressDaysAfter,
    long scale,
    Map<CommodityId, Long> consumedInputs,
    Map<CommodityId, Long> grossOutput,
    Map<CurrencyId, Long> mintCandidates,
    Map<ProductionParticipantKey, Long> laborContributionsMilli,
    Map<ProductionParticipantKey, CycleContribution> cycleContributions,
    long cycleLaborMilli,
    ProductionEfficiencyState efficiencyState,
    Map<HouseholdExperienceKey, Long> experienceGrowth,
    List<ProductionExperienceGrowthLine> experienceGrowthLines,
    java.util.Set<IndustryId> newlyUnlocked) {

  public ProductionReceipt {
    Objects.requireNonNull(unit, "ProductionReceipt.unit 不得为 null");
    Objects.requireNonNull(plan, "ProductionReceipt.plan 不得为 null");
    if (progressDaysBefore < 0L || progressDaysAfter < 0L) {
      throw new IllegalArgumentException("ProductionReceipt 的进度不得为负");
    }
    if (scale < 0L) {
      throw new IllegalArgumentException("ProductionReceipt.scale 不得为负: " + scale);
    }
    if (cycleLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionReceipt.cycleLaborMilli 不得为负: " + cycleLaborMilli);
    }
    if (consumedInputs == null
        || grossOutput == null
        || mintCandidates == null
        || laborContributionsMilli == null
        || cycleContributions == null
        || experienceGrowth == null
        || experienceGrowthLines == null
        || newlyUnlocked == null) {
      throw new IllegalArgumentException("ProductionReceipt 的各表都不得为 null（空用空表；efficiencyState 可缺）");
    }
    // ★★ B11：六张表**就地**逐字展开冻结（照 MarketTable.hexes / EconomyMeta 政策表 / ProductionData 的形制）。
    //   ★ 为什么必须写在本构造器里、不能抽私有 helper：SpotBugs 的 EI_EXPOSE_REP/EI_EXPOSE_REP2 **不做跨过程
    //     分析**，只认它在这一个方法里看得见的 Collections.unmodifiableMap ⇒ 抽成 helper 会让生成的 record
    //     访问器被报成"暴露内部表示"（本类 6 条）。★ 语义与抽 helper 时**逐字节相同**：同一保序拷贝、同一组
    //     守卫、同一批拒绝消息与同一判定次序。
    Map<CommodityId, Long> consumedCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : consumedInputs.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionReceipt.consumedInputs 的键与值都不得为 null");
      }
      long value = entry.getValue();
      if (value < 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.consumedInputs 的值越域（不得为负；0 不进表）: " + entry.getKey());
      }
      consumedCopy.put(entry.getKey(), value);
    }
    consumedInputs = Collections.unmodifiableMap(consumedCopy); // ★ 冻在赋值处

    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : grossOutput.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionReceipt.grossOutput 的键与值都不得为 null");
      }
      long value = entry.getValue();
      if (value < 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.grossOutput 的值越域（不得为负；0 不进表）: " + entry.getKey());
      }
      outputCopy.put(entry.getKey(), value);
    }
    grossOutput = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处

    Map<CurrencyId, Long> mintCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : mintCandidates.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionReceipt.mintCandidates 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.mintCandidates 不得为负：" + entry.getKey());
      }
      mintCopy.put(entry.getKey(), entry.getValue());
    }
    mintCandidates = Collections.unmodifiableMap(mintCopy); // ★ 冻在赋值处

    Map<ProductionParticipantKey, Long> laborCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Long> entry : laborContributionsMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionReceipt.laborContributionsMilli 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.laborContributionsMilli 的贡献必须为正（0 贡献的参与项不进表）: " + entry.getKey());
      }
      laborCopy.put(entry.getKey(), entry.getValue());
    }
    laborContributionsMilli = Collections.unmodifiableMap(laborCopy); // ★ 冻在赋值处

    Map<ProductionParticipantKey, CycleContribution> contributionCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, CycleContribution> entry :
        cycleContributions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionReceipt.cycleContributions 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().participantKey())) {
        throw new IllegalArgumentException(
            "ProductionReceipt.cycleContributions 的键必须与值内家户/角色一致：键="
                + entry.getKey()
                + "，值内="
                + entry.getValue().participantKey());
      }
      if (entry.getValue().laborMilli() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.cycleContributions 的劳动必须为正（0 贡献的家户不进表）: " + entry.getKey());
      }
      contributionCopy.put(entry.getKey(), entry.getValue());
    }
    cycleContributions = Collections.unmodifiableMap(contributionCopy); // ★ 冻在赋值处

    // ★ 回执自洽：周期劳动总数必须等于逐家户贡献的合计（存两个说法就必须当场对得上）。
    long contributionTotal = 0L;
    for (CycleContribution contribution : cycleContributions.values()) {
      contributionTotal = Math.addExact(contributionTotal, contribution.laborMilli());
    }
    if (contributionTotal != cycleLaborMilli) {
      throw new IllegalArgumentException(
          "ProductionReceipt.cycleLaborMilli 必须等于 cycleContributions 的合计: "
              + cycleLaborMilli
              + " != "
              + contributionTotal);
    }
    Map<HouseholdExperienceKey, Long> growthCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdExperienceKey, Long> entry : experienceGrowth.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionReceipt.experienceGrowth 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionReceipt.experienceGrowth 的增量必须为正（没涨的家户不进表）: " + entry.getKey());
      }
      growthCopy.put(entry.getKey(), entry.getValue());
    }
    experienceGrowth = Collections.unmodifiableMap(growthCopy); // ★ 冻在赋值处

    List<ProductionExperienceGrowthLine> growthLineCopy = new ArrayList<>();
    Map<HouseholdExperienceKey, Long> lineTotals = new LinkedHashMap<>();
    for (ProductionExperienceGrowthLine line : experienceGrowthLines) {
      if (line == null) {
        throw new IllegalArgumentException("ProductionReceipt.experienceGrowthLines 不得含 null");
      }
      growthLineCopy.add(line);
      HouseholdExperienceKey key = new HouseholdExperienceKey(line.household(), line.track());
      lineTotals.merge(key, line.delta(), Math::addExact);
    }
    experienceGrowthLines = Collections.unmodifiableList(growthLineCopy);
    if (!lineTotals.equals(experienceGrowth)) {
      throw new IllegalArgumentException(
          "ProductionReceipt.experienceGrowthLines 按 (household,track) 汇总必须等于 experienceGrowth: lines="
              + lineTotals
              + " growth="
              + experienceGrowth);
    }

    java.util.Set<IndustryId> unlockCopy = new java.util.LinkedHashSet<>();
    for (IndustryId unlocked : newlyUnlocked) {
      if (unlocked == null) {
        throw new IllegalArgumentException("ProductionReceipt.newlyUnlocked 不得含 null");
      }
      unlockCopy.add(unlocked);
    }
    newlyUnlocked = Collections.unmodifiableSet(unlockCopy); // ★ 冻在赋值处
  }

  /** ★ 是否发生了"真的产出了东西"（关账且有毛产）——经济侧据此决定要不要走收益分配。 */
  public boolean harvested() {
    return cycleClosed;
  }

  /** ★ <b>本 tick 出工劳动的按户派生合计</b>：同一户多角色合并；不另存第二份权威。 */
  public Map<HouseholdId, Long> laborByHouseholdMilli() {
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Long> entry : laborContributionsMilli.entrySet()) {
      byHousehold.merge(entry.getKey().household(), entry.getValue(), Math::addExact);
    }
    return Collections.unmodifiableMap(byHousehold);
  }
}
