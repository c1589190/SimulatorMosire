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
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
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
            OwnershipStake.idOf(FARM, AssetKind.LAND, OPERATOR, OPERATOR, AssetRightKind.OWNED, 0L),
            FARM,
            AssetKind.LAND,
            OPERATOR,
            OPERATOR,
            1_000L,
            AssetRightKind.OWNED);
    Map<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> stakes =
        Map.of(farmLand.id(), farmLand);

    IndustryDefinitionView farmPlan = plan(FARM);
    IndustryDefinitionView weavePlan = plan(WEAVE);
    ProductionProcess farmUnit = unit(FARM, "unit-n9-farm");
    ProductionProcess weaveUnit = unit(WEAVE, "unit-n9-weave");

    assertThat(ProductionProcessBook.capacityScaleOf(farmUnit, farmPlan, stakes))
        .as("farm：1,000 千分亩 ÷ 每规模 1,000 = 规模 1")
        .isEqualTo(1L);
    assertThat(ProductionProcessBook.capacityScaleOf(weaveUnit, weavePlan, stakes))
        .as("★★ weave 看到同一张 LAND 数量表却拿不到它（份额的 industry 是 FARM）⇒ 规模 0" + "（同一份资产没有被第二个方案再算一遍）")
        .isZero();
    assertThat(
            ProductionProcessBook.capacityScaleOf(farmUnit, farmPlan, stakes)
                + ProductionProcessBook.capacityScaleOf(weaveUnit, weavePlan, stakes))
        .as("两份'产能规模'之和 = 1 + 0（不是 1 + 1：只有一份 1,000 LAND）")
        .isEqualTo(1L);
  }

  // ── ④ R2 具名未修：同方案两实例重复获得整份资产（统一预算缺口）────────────────────────────

  /**
   * ★★★ <b>R2（§15.2）的缺陷现状载体 —— 本判据断言"当前<b>仍</b>是缺陷"，<b>不</b>修</b>。
   *
   * <p>★★ <b>为什么它必须是红的判据而不是被改宽</b>：§15.2 的复现是「同一 LAND 份额、同经营者两个实例 ⇒ 每个 unit 各得整份数量， 合计 200（实有
   * 100）」，违反 I8「同一份资产不得被两个方案<b>或两个实例</b>重复占用」。本判据把现状逐值钉住： 一旦 R2 修好（Phase A
   * 的统一分配落地），这条<b>当场红</b>，届时按新语义改写期望值 —— 不许提前删掉/改宽。
   *
   * <p>★ <b>范围（重规划 v2 §4 Phase F）</b>：F3 只覆盖 R1/R3/R4/R5/R6，<b>不含 R2</b>；R2 关闭在 Phase A。 ⇒
   * 本判据不是"为绿灯改掉缺陷判据"，而是"保留具名'未修复'证据"。
   *
   * <p>★ <b>手算</b>：{@code SettlementIndex.usableAssets} 按 {@code (industry, operator)}
   * 聚合后把<b>整份</b>数量复制给同 scope 的每个 unit（本文件 ③ 只测了"不同方案 id"那一腿，靠 industry 不等挡住；同方案多实例这一腿没有判据）。 一份
   * 1,000 千分亩 LAND、{@code capacityPerUnit LAND=1,000} ⇒ 每个 unit 的可用资产 = 1,000、产能规模各 = 1、 合计 2 ——
   * 实有只有 1,000（= 规模 1）。
   */
  @Test
  void r2SamePlanTwoInstancesStillDoubleBookTheSameAssetStake() {
    OwnershipStake sharedLand =
        new OwnershipStake(
            OwnershipStake.idOf(FARM, AssetKind.LAND, OPERATOR, OPERATOR, AssetRightKind.OWNED, 0L),
            FARM,
            AssetKind.LAND,
            OPERATOR,
            OPERATOR,
            1_000L,
            AssetRightKind.OWNED);
    Map<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> stakes =
        Map.of(sharedLand.id(), sharedLand);

    // ★ 同一方案、同一经营者、两个实例（unit id 不同；industry/operator 同 scope）。
    ProductionProcess first = unit(FARM, "unit-r2-first");
    ProductionProcess second = unit(FARM, "unit-r2-second");
    SettlementIndex index =
        SettlementIndex.build(
            Map.of(first.id(), first, second.id(), second),
            Map.of(FARM, plan(FARM)),
            stakes,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    assertThat(index.usableAssetsOf(first))
        .as("R2 现状：两个实例各拿到整份 1,000（不是各 500）")
        .containsEntry(AssetKind.LAND, 1_000L);
    assertThat(index.usableAssetsOf(second)).containsEntry(AssetKind.LAND, 1_000L);

    long firstScale = ProductionProcessBook.capacityScaleOf(first, plan(FARM), stakes);
    long secondScale = ProductionProcessBook.capacityScaleOf(second, plan(FARM), stakes);
    assertThat(firstScale).as("每个实例的产能规模各算 1（1,000 ÷ 1,000）").isEqualTo(1L);
    assertThat(secondScale).isEqualTo(1L);
    assertThat(firstScale + secondScale)
        .as(
            "★★ R2 未修复：Σ产能规模 = 2 > 实有规模 1 —— 同一份 1,000 LAND 被两个实例各占一遍（I8 被违反；"
                + "修复点 = §15.2 / Phase A 的统一占用分配）")
        .isEqualTo(2L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private static LaborQueueBook.Offer offer(String unit, long netPerLaborScaled, long absorbable) {
    return new LaborQueueBook.Offer(
        new ProductionUnitId(unit),
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
