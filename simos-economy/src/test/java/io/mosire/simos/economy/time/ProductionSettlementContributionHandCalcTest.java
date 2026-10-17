package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.CycleContribution;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：V4 / N14 的「工资 / 租金 / 分成按实际贡献」腿（手算逐值）</b>。
 *
 * <p>★★ <b>判据原文</b>（设计书 §14.4 N14 / I9）：多户协作时<b>只记一份总产出</b>；工资/租金/分成按<b>实际贡献</b> 计算。I9
 * 还要求回执保留"实际出工家户与各自贡献"（{@link ProductionReceipt#cycleContributions()}）。
 *
 * <p>★★ <b>本用例的两段证据链（不是从被测代码反算期望）</b>：
 *
 * <ol>
 *   <li><b>回执段</b>：真 {@link ProductionRuntimeEngine} 用<b>两户不同贡献</b>（H1 = 600,000 毫劳动、H2 = 400,000
 *       毫劳动） 执行一个 1 天周期 ⇒ 回执的 {@code cycleContributions} 逐户保留实际贡献、{@code cycleLaborMilli} =
 *       1,000,000 （600 + 400），毛产只有<b>一份</b> 5,000 毫粮、实扣投入 1,000 毫粮。
 *   <li><b>结算段</b>：用<b>回执里的那张贡献表</b>（{@code cycleContributions}，不是另算一份）构造 {@link
 *       ProductionSettlement.Facts}，按制度规则结算 ⇒ 逐户金额可逐笔手算。
 * </ol>
 *
 * <p>★★ <b>纸面算式（每一分都从回执的 5,000 / 1,000 / 600,000 / 400,000 四个数推出来）</b>：
 *
 * <pre>
 * 毛产 gross = 1 × 5 × 1000 = 5,000 毫粮；实扣投入 inputs = 1 × 1,000 = 1,000 毫粮
 * 净产 net   = 5,000 − 1,000 = 4,000 毫粮（★ 只有这一份池子；协作不产生第二份毛产）
 * Σ劳动      = 600,000 + 400,000 = 1,000,000 毫劳动
 *
 * ① 计件工资（FIXED_IN_KIND_PER_LABOR，每 1,000 毫劳动 2 毫粮 ⇒ 每 1 劳动日 2 毫）：
 *    H1 ⌊600,000 ÷ 1,000⌋ × 2 = 600 × 2 = 1,200
 *    H2 ⌊400,000 ÷ 1,000⌋ × 2 = 400 × 2 =   800
 * ② 分成（OUTPUT_SHARE × NET_AFTER_INPUTS × LABOR_AMOUNT，率 300‰，按实际劳动占比）：
 *    分成池 = ⌊4,000 × 300 ÷ 1000⌋ = 1,200
 *    H1 = 1,200 × 600,000 ÷ 1,000,000 = 720
 *    H2 = 1,200 × 400,000 ÷ 1,000,000 = 480     （720 : 480 = 600,000 : 400,000 = 3 : 2）
 * ③ 固定实物租（FIXED_IN_KIND_RENT，每周期 500；与劳动无关，按合约一笔）：
 *    地主 = 500
 * 实付合计 = 1,200 + 800 + 720 + 480 + 500 = 3,700 ≤ 净产 4,000
 * 经营者剩余（residualOwner，不产生转移）= 4,000 − 3,700 = 300
 * 守恒核对：投入 1,000 + 实付 3,700 + 剩余 300 = 5,000 = 毛利（★ 只记一份总产出，没有第二份 5,000）
 * </pre>
 *
 * <p>★ <b>判别力</b>：若结算不读实际贡献（例如按"格子全体家户行"或"最后一天出工者"分），H1/H2 的 720/480 与 1,200/800
 * 会立刻变成别的数；若把毛产记两份，实付合计会超过 4,000（本用例会红在守恒核对）。
 */
class ProductionSettlementContributionHandCalcTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final String HEX_KEY = "0_0";
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-farm-collab");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final HouseholdId H1 = HouseholdId.parse("hh-collab-a");
  private static final HouseholdId H2 = HouseholdId.parse("hh-collab-b");
  private static final HouseholdId LANDLORD = HouseholdId.parse("hh-collab-landlord");

  /** 两户的实际贡献（毫劳动；600 + 400 = 1,000 个劳动日）。 */
  private static final long LABOR_H1 = 600_000L;

  private static final long LABOR_H2 = 400_000L;

  @Test
  void wagesRentAndSharesAreHandComputedFromTheReceiptsActualContributions() {
    // ── ① 回执段：真引擎按两户实际投入执行一个生产日 ──────────────────────────────────
    IndustryDefinitionView plan =
        IndustryViews.industry(
            FARM,
            "协作农场（V4/N14 手算夹具）",
            new RegimeId("tenant"),
            1L, // cycleDays = 1 ⇒ 本次执行即关账
            Map.of(AssetKind.LAND, 1_000L), // 每 1 规模要 1,000 毫亩 ⇒ 资产通道规模 1
            Map.of(),
            0L,
            1_000L, // 每 1 规模要 1,000 毫劳动 ⇒ 劳动通道规模 1,000
            Map.of(GRAIN, 5L), // 每 1 规模产 5 单位 ⇒ 毛产 5 × 1000 = 5,000 毫
            Map.of(AssetKind.LAND, Map.of(GRAIN, 1_000L)), // 每 1 规模吃 1,000 毫粮
            List.of(new ClassSlot(SocialClassId.ARTISAN, "农工", 1_000)),
            new AllocationRule.Split(1_000, 0));
    ProductionProcess instance =
        new ProductionProcess(
            UNIT,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            OPERATOR,
            FARM.value(),
            0L,
            Map.of(),
            Map.of());
    ProductionRuntimeEngine engine =
        new ProductionRuntimeEngine(
            ProductionData.empty().withIndustry(plan).withInstance(instance));

    ProductionExecutionOutcome outcome =
        engine.execute(
            new ProductionExecutionRequest(
                Optional.of(UNIT),
                Optional.empty(),
                1L, // grantedScale = 基准规模上限 1（资产/投入两路都恰是 1）
                List.of(
                    new ParticipantContribution(H1, ProductionRoleId.parse("operator"), LABOR_H1),
                    new ParticipantContribution(H2, ProductionRoleId.parse("operator"), LABOR_H2)),
                Map.of(AssetKind.LAND, 1_000L),
                Map.of(GRAIN, 1_000L),
                ExternalAttributeFacts.EMPTY,
                Map.of(),
                Map.of(H1, LABOR_H1, H2, LABOR_H2),
                Map.of()));
    assertThat(outcome)
        .as("两户协作必须被执行（不是业务拒绝）")
        .isInstanceOf(ProductionExecutionOutcome.Executed.class);
    ProductionReceipt receipt = ((ProductionExecutionOutcome.Executed) outcome).receipt();

    assertThat(receipt.cycleClosed()).as("1 天周期 ⇒ 本次关账").isTrue();
    assertThat(receipt.scale()).as("中性效率：finalScale = baseScale = 1").isEqualTo(1L);
    assertThat(receipt.grossOutput())
        .as("★ 只记一份总产出：毛产 = 1 × 5 × 1000 = 5,000 毫粮")
        .containsExactly(Map.entry(GRAIN, 5_000L));
    assertThat(receipt.consumedInputs())
        .as("实扣投入 = 1 × 1,000 = 1,000 毫粮")
        .containsExactly(Map.entry(GRAIN, 1_000L));
    assertThat(receipt.cycleLaborMilli()).as("Σ实际劳动 = 600,000 + 400,000").isEqualTo(1_000_000L);
    assertThat(contributionLabor(receipt))
        .as("★★ 回执保留逐户实际贡献（I9 的交接面）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(H1, LABOR_H1, H2, LABOR_H2));

    // ── ② 结算段：Facts 的贡献表**直接取回执**（不另算一份）────────────────────────────
    Map<CommodityId, Long> net = new LinkedHashMap<>(receipt.grossOutput());
    receipt
        .consumedInputs()
        .forEach((commodity, amount) -> net.merge(commodity, -amount, Long::sum));
    Map<CommodityId, Long> payerStock = Map.of(GRAIN, 4_600L); // 开缸 600 + 本周期净产 4,000

    ProductionRules relation =
        new ProductionRules(
            UNIT,
            OPERATOR,
            new Payee.ToActor(OPERATOR),
            List.of(
                // ① 计件工资：两户都按各自实际劳动拿（每 1,000 毫劳动 2 毫粮）
                perLaborRule(H1, 10, 2L),
                perLaborRule(H2, 10, 2L),
                // ② 分成：净产的 300‰ 按实际劳动占比分给两户
                shareRule(H1, 20, 300),
                shareRule(H2, 20, 300),
                // ③ 固定实物租：每周期一笔，与劳动/产出无关
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    new Payee.ToActor(HouseholdActors.of(LANDLORD)),
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    500L,
                    Optional.of(GRAIN),
                    Optional.empty(),
                    30)),
            OPERATOR,
            LaborSource.WAGE);
    ProductionSettlement.Facts facts =
        new ProductionSettlement.Facts(
            HEX,
            receipt.grossOutput(),
            net,
            receipt.consumedInputs(),
            contributionLabor(receipt), // ★ 回执里的实际出工家户与贡献
            plan.outputPerUnit(),
            Map.of(),
            payerStock);

    ProductionSettlement.Outcome settled = ProductionSettlement.settle(relation, facts);

    // ── ③ 逐值手算断言（见类注的纸面算式）────────────────────────────────────────────
    assertThat(goodsByPayee(settled.transfers()))
        .as("★★ 手算：H1 工资 1,200 + 分成 720 = 1,920；H2 工资 800 + 分成 480 = 1,280；地主租 500")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                HouseholdActors.of(H1), 1_920L,
                HouseholdActors.of(H2), 1_280L,
                HouseholdActors.of(LANDLORD), 500L));
    assertThat(settled.transfers()).as("每一笔实付一条转移（4 条工资/分成 + 1 条租；0 实付不铸）").hasSize(5);
    assertThat(settled.transfers().stream().mapToLong(t -> t.goods().get(GRAIN)).sum())
        .as("★ 实付合计 = 1,200 + 800 + 720 + 480 + 500 = 3,700（不超过净产 4,000）")
        .isEqualTo(3_700L);
    assertThat(net.get(GRAIN) - 3_700L)
        .as("经营者剩余 = 4,000 − 3,700 = 300（余额归 residualOwner，不产生转移）")
        .isEqualTo(300L);
    assertThat(receipt.consumedInputs().get(GRAIN) + 3_700L + 300L)
        .as("★★ 守恒：投入 1,000 + 实付 3,700 + 剩余 300 = 毛利 5,000（只记一份总产出）")
        .isEqualTo(receipt.grossOutput().get(GRAIN));
    assertThat(settled.ruleSettlements()).as("逐规则读数齐全（5 条，含应付/实付；实付 0 的也报）").hasSize(5);
    assertThat(settled.ruleSettlements())
        .as("全部规则都付清（没有欠款）")
        .allMatch(reading -> reading.owed() == 0L);
    assertThat(settled.ruleSettlements().stream().mapToLong(reading -> reading.paidNow()).sum())
        .as("逐规则实付之和 = 转移合计 = 3,700（两条口径对得上）")
        .isEqualTo(3_700L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  /** 计件工资规则（每 1,000 毫劳动 fixedAmount 毫粮）。 */
  private static CompensationRule perLaborRule(
      HouseholdId household, int priority, long fixedAmountPerLaborDay) {
    return new CompensationRule(
        RuleType.FIXED_IN_KIND_PER_LABOR,
        // ★ 劳动账按家户 identity 查（Payee.ToActor 在本阶段没有劳动账 ⇒ 应付恒 0）
        new Payee.ToHousehold(household),
        Pool.NET_AFTER_INPUTS,
        Weight.LABOR_AMOUNT,
        0,
        fixedAmountPerLaborDay,
        Optional.of(GRAIN),
        Optional.empty(),
        priority);
  }

  /** 分成规则（净产 × ratePerMille 按实际劳动占比分）。 */
  private static CompensationRule shareRule(HouseholdId household, int priority, int ratePerMille) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE,
        new Payee.ToHousehold(household),
        Pool.NET_AFTER_INPUTS,
        Weight.LABOR_AMOUNT,
        ratePerMille,
        0L,
        Optional.of(GRAIN),
        Optional.empty(),
        priority);
  }

  /** 回执的逐户周期贡献 → 家户 → 实际劳动（毫），保持回执的迭代序。 */
  private static Map<HouseholdId, Long> contributionLabor(ProductionReceipt receipt) {
    Map<HouseholdId, Long> labor = new LinkedHashMap<>();
    for (CycleContribution contribution : receipt.cycleContributions().values()) {
      labor.put(contribution.household(), contribution.laborMilli());
    }
    return labor;
  }

  /** 按受方汇总转移里的粮（同一受方多笔合并；本用例每条规则各一笔，合并只为读法稳定）。 */
  private static Map<ActorRef, Long> goodsByPayee(List<Transfer> transfers) {
    Map<ActorRef, Long> byPayee = new LinkedHashMap<>();
    for (Transfer transfer : transfers) {
      byPayee.merge(transfer.to(), transfer.goods().getOrDefault(GRAIN, 0L), Long::sum);
    }
    return byPayee;
  }
}
