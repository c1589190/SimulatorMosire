package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：N9「统一预算 / 不得重复占用」的独立载体</b>（设计书 §14.4 的 N9 行 + I8 的统一预算口径）。
 *
 * <p>★★ <b>判据</b>：同一份劳动 / 资产 / 库存不得被两个方案<b>同时</b>占用。三件资源各自的权威落点不同，本文件逐件给出 一个可逐值手算的载体（★
 * 不建"大而全"的整链夹具： 每件资源的唯一分配口就在下面这三处）：
 *
 * <table border="1">
 *   <caption>资源 → 分配口 → 本文件的判据</caption>
 *   <tr><th>资源</th><th>分配口</th><th>判据</th></tr>
 *   <tr><td>劳动（家户时间预算）</td><td>{@link LaborQueueBook#plan}</td><td>预算 1,000：A 吸收 700、B 想吸收 1,000 ⇒ B 只拿 300（Σ = 1,000，不是 1,700）</td></tr>
 *   <tr><td>库存（账户商品余额）</td><td>{@link AccountSession#commit} 的转移落账</td><td>同一缸 1,000 粮：第一笔 800 成功（余 200）、第二笔 800 被具名拒（余额不得为负）</td></tr>
 *   <tr><td>资产（产权份额）</td><td>{@link ProductionProcessBook#capacityScaleOf}</td><td>同一份 1,000 LAND 份额只服务它所属产业：farm 规模 1、weave 规模 0（不能给第二个方案再算一遍）</td></tr>
 * </table>
 *
 * <p>★ <b>手算</b>：劳动 700+300 = 1,000；库存 800（第一笔）+ 200（余）= 1,000，第二笔 800 若被接受 会变成 1,600
 * 支出（本仓把"透支"判为发行，不是库存）；资产 1,000 ÷ 1,000 = 1 与 0（份额的 industry 键不匹配 ⇒ 可用资产 0）。
 */
class UnifiedBudgetNoDoubleBookingTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId H = HouseholdId.parse("hh-n9-budget");
  private static final HouseholdId A = HouseholdId.parse("hh-n9-plan-a");
  private static final HouseholdId B = HouseholdId.parse("hh-n9-plan-b");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-n9");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId WEAVE = IndustryHexKeys.id("weave", 0, 0);

  // ── ① 劳动：同一份家户时间预算被两个方案争用 ──────────────────────────────────────────

  @Test
  void twoPlansCannotBothSpendTheSameHouseholdLaborBudget() {
    // 预算 1,000；A 的收益率 500‰（排第 1）最多吸收 700；B 的收益率 300‰（排第 2）最多吸收 1,000。
    LaborQueueBook.Plan plan =
        LaborQueueBook.plan(
            H,
            1_000L,
            0L,
            List.of(offer("unit-n9-a", 500L, 700L), offer("unit-n9-b", 300L, 1_000L)));

    assertThat(plan.decisions().get(0).grantedLaborMilli()).as("A 拿满自己的上限 700").isEqualTo(700L);
    assertThat(plan.decisions().get(1).grantedLaborMilli())
        .as("★★ B 只能拿预算剩下的 300（不是它想吸收的 1,000）——同一份劳动没有被两个方案各花一遍")
        .isEqualTo(300L);
    assertThat(plan.decisions().stream().mapToLong(d -> d.grantedLaborMilli()).sum())
        .as("★ Σ发放 = 700 + 300 = 1,000 = 预算（既不许超发，也不许把 700 再借给 B）")
        .isEqualTo(1_000L);
  }

  // ── ② 库存：同一缸粮被两笔投入各要 800 ──────────────────────────────────────────────

  @Test
  void twoPlansCannotBothDrawTheSameInventoryBalance() {
    AccountSession session = AccountSession.empty();
    session.registerHousehold(H, HEX, Map.of(GRAIN, 1_000L), Map.of(), Map.of(), Map.of());
    session.registerHousehold(A, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    session.registerHousehold(B, HEX, Map.of(), Map.of(), Map.of(), Map.of());

    // 第一笔：H → A 800 粮（合法：还剩 200）。
    session.commit(
        List.of(TransferIntent.of(SettlementStage.INPUT_DRAW, 0, 0, draw(H, A, 800L, "tr-n9-1"))));
    assertThat(session.account(H).goods().get(GRAIN))
        .as("第一笔之后同一缸只剩 200（1,000 − 800 手算）")
        .isEqualTo(200L);

    // 第二笔：H → B 800 粮（同一缸、同一份库存）⇒ 余额会变负 ⇒ 拒绝（不得静默透支）。
    assertThatThrownBy(
            () ->
                session.commit(
                    List.of(
                        TransferIntent.of(
                            SettlementStage.INPUT_DRAW, 0, 1, draw(H, B, 800L, "tr-n9-2")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("负数")
        .hasMessageContaining("grain");
    assertThat(session.account(H).goods().get(GRAIN))
        .as("★★ 被拒的那一笔一个字节都不落：H 仍是 200（第二笔 800 没有被记成支出）")
        .isEqualTo(200L);
    assertThat(session.account(B).goods().getOrDefault(GRAIN, 0L))
        .as("B 没有收到任何粮（同一份库存没有被两个方案重复占用）")
        .isZero();
    assertThat(session.account(A).goods().get(GRAIN)).as("A 的第一笔如实落账").isEqualTo(800L);
  }

  // ── ③ 资产：同一份 LAND 份额只服务它所属的产业 ─────────────────────────────────────────

  @Test
  void theSameAssetStakeCannotBackTwoPlans() {
    // 一份 1,000 千分亩的 LAND 份额，industry = FARM（份额身份带产业键）。
    OwnershipStake farmLand =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.LAND,
                OPERATOR,
                OPERATOR,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            FARM,
            AssetKind.LAND,
            OPERATOR,
            OPERATOR,
            1_000L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    Map<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> stakes =
        Map.of(farmLand.id(), farmLand);

    IndustryDefinitionView farmPlan = plan(FARM);
    IndustryDefinitionView weavePlan = plan(WEAVE);
    ProductionProcess farmUnit = unit(FARM, "unit-n9-farm");
    ProductionProcess weaveUnit = unit(WEAVE, "unit-n9-weave");

    AssetOccupancyBudget budget =
        ProductionProcessBook.assetOccupancyBudget(
            Map.of(farmUnit.id(), farmUnit, weaveUnit.id(), weaveUnit),
            stakes,
            Map.of(),
            Map.of(FARM, farmPlan, WEAVE, weavePlan),
            Map.of(),
            0L);
    assertThat(ProductionProcessBook.capacityScaleOf(farmUnit, farmPlan, budget))
        .as("farm：1,000 千分亩 ÷ 每规模 1,000 = 规模 1")
        .isEqualTo(1L);
    assertThat(ProductionProcessBook.capacityScaleOf(weaveUnit, weavePlan, budget))
        .as("★★ weave 看到同一张 LAND 数量表却拿不到它（份额的 industry 是 FARM）⇒ 规模 0" + "（同一份资产没有被第二个方案再算一遍）")
        .isZero();
    assertThat(
            ProductionProcessBook.capacityScaleOf(farmUnit, farmPlan, budget)
                + ProductionProcessBook.capacityScaleOf(weaveUnit, weavePlan, budget))
        .as("两份'产能规模'之和 = 1 + 0（不是 1 + 1：只有一份 1,000 LAND）")
        .isEqualTo(1L);
  }

  // ── ④ R2 最小修复：同方案两实例不再重复获得整份资产 ──────────────────────────────────────

  /**
   * ★★★ <b>R2 的 D4-aware 载体（D-032 D4 §5.4 P3/P22）</b>。
   *
   * <p>★★ <b>语义已变（旧断言必须改写，不是放宽）</b>：改前这条用例断言「同一 LAND 份额、同经营者两个实例 ⇒ 每个 unit 各得整份数量，合计 200（实有
   * 100）」，那是<b>缺陷现状</b>。D4-Ib2a 后两个实例都带显式非资产劳动事实（规模 5），统一预算必须按请求量比例 真实分配：{@code LAND=1000 ⇒
   * 500/500}，{@code unallocated=0}，两条 grant 引用同一 share 且合计 1000，不超占。
   *
   * <p>★ <b>判别力</b>：若实现把整池复制回每个 unit，两个 allocation 都会是 1000、Σ=2000 ⇒ 本条当场红。
   */
  @Test
  void r2SamePlanTwoInstancesNoLongerDoubleBookTheSameAssetStake() {
    OwnershipStake sharedLand =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.LAND,
                OPERATOR,
                OPERATOR,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            FARM,
            AssetKind.LAND,
            OPERATOR,
            OPERATOR,
            1_000L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    Map<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> stakes =
        Map.of(sharedLand.id(), sharedLand);

    // ★ D4-aware 构建：两个实例都有显式非资产劳动事实（5,000 毫劳动 ÷ 1,000 毫/规模 = 规模 5），
    //   因此不是「无请求事实」路径；D4 必须按请求量比例真实分配，而不是复制整池。
    ProductionProcess first = unit(FARM, "unit-r2-first");
    ProductionProcess second = unit(FARM, "unit-r2-second");
    PeopleLotId lot = new PeopleLotId("n9:rural:0_0:MALE:1");
    Map<LaborAllocationId, HouseholdLaborCommitment> labor = new LinkedHashMap<>();
    labor.put(
        new LaborAllocationId("alloc-n9-first"),
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-n9-first"),
            lot,
            H,
            OPERATOR,
            first.id().value(),
            5_000L,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator"))));
    labor.put(
        new LaborAllocationId("alloc-n9-second"),
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-n9-second"),
            lot,
            H,
            OPERATOR,
            second.id().value(),
            5_000L,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator"))));

    SettlementIndex index =
        SettlementIndex.build(
            Map.of(first.id(), first, second.id(), second),
            Map.of(FARM, plan(FARM)),
            stakes,
            labor,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            0L);

    assertThat(index.d4AssetOccupancyBudget()).as("D4-Ib2a 统一占用预算必须在场").isPresent();
    AssetOccupancyBudget d4 =
        index.d4AssetOccupancyBudget().orElseThrow(() -> new AssertionError("missing D4 budget"));
    assertThat(d4.allocationOf(first.id()))
        .as("D4 真实分配：共享 LAND=1000 按 1:1 请求量分给两个实例")
        .containsExactly(Map.entry(AssetKind.LAND, 500L));
    assertThat(d4.allocationOf(second.id())).containsExactly(Map.entry(AssetKind.LAND, 500L));
    assertThat(
            d4.allocationOf(first.id()).getOrDefault(AssetKind.LAND, 0L)
                + d4.allocationOf(second.id()).getOrDefault(AssetKind.LAND, 0L))
        .as("★★ Σ实占 = 1000 = 实有量（不是 1000+1000=2000）")
        .isEqualTo(1_000L);
    assertThat(d4.grantsToWrite()).hasSize(2);
    assertThat(d4.grantsToWrite().stream().mapToLong(g -> g.quantity()).sum())
        .as("两条 grant 合计引用同一份 1000，不超占")
        .isEqualTo(1_000L);
    assertThat(d4.unallocated().values())
        .as("P3：比例分配后 unallocated=0（不是整池留在旧 guard）")
        .allSatisfy(kinds -> assertThat(kinds).containsEntry(AssetKind.LAND, 0L));
    assertThat(index.assetRejectionOf(first.id()))
        .as("真实分配路径不应再以旧 asset-scope-shared:awaiting-D4 作为结论")
        .isEmpty();
    assertThat(index.assetRejectionOf(second.id())).isEmpty();
    assertThat(stakes.get(sharedLand.id()))
        .as("D4-I1：OwnershipStake 的 quantity/owner/operator/kind/location 一字不改")
        .isEqualTo(sharedLand);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private static LaborQueueBook.Offer offer(String unit, long netPerLaborScaled, long absorbable) {
    return new LaborQueueBook.Offer(
        new ProductionUnitId(unit),
        ProductionRoleId.parse("operator"),
        Optional.empty(),
        unit,
        absorbable,
        true,
        netPerLaborScaled,
        0L,
        netPerLaborScaled,
        1_000L,
        netPerLaborScaled,
        "n9-no-double-booking");
  }

  private static Transfer draw(HouseholdId from, HouseholdId to, long grain, String id) {
    return new Transfer(
        new io.mosire.simos.economy.api.id.TransferId(id),
        1L,
        HouseholdActors.of(from),
        HouseholdActors.of(to),
        HEX,
        Map.of(GRAIN, grain),
        Map.of(),
        TransferReason.INPUT_REQUISITION,
        Optional.empty());
  }

  private static IndustryDefinitionView plan(IndustryId industry) {
    return IndustryViews.industry(
        industry,
        "N9 资产份额夹具",
        new RegimeId("tenant"),
        1L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0));
  }

  private static ProductionProcess unit(IndustryId industry, String unitId) {
    return new ProductionProcess(
        new ProductionUnitId(unitId),
        industry,
        ProductionLocation.ofPlan(industry).orElseThrow(),
        OPERATOR,
        industry.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }
}
