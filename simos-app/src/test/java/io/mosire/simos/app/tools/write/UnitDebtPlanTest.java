package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link UnitDebtPlan} 纯推导（收尾期 T4a · 地方债工具面）：issue / repay 各自的逐条拒因、happy Plan 数字、确定性。不碰
 * {@code ToolContext} / {@code CoreSimos}（那是 {@code UnitDebtToolsTest} 的真 Shell 面）。
 *
 * <p>★ 判别力：happy 路径把 {@code debtBefore/After}、{@code lenderAvailableBefore/After}、
 * {@code outstandingBefore/After}、{@code treasuryAvailableBefore/After}（<b>余额 − 冻结</b>）与
 * {@code treasuryLocation} 逐值钉住——"少算一次扣款 / 把负数负债当正数读 / 忘减冻结 / 落点取错"都会当场红。拒因断言带具名 id、数字与指路词；
 * 状态损坏（镜像腿缺失 / 不对称）必须 {@link IllegalStateException}，不得被折成参数问题。
 */
class UnitDebtPlanTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");

  private static final String LENDER_ID = "GOV-LENDER";
  private static final String OTHER_LENDER_ID = "OTHER-LENDER";
  private static final String MONEY = PilotModel.MONEY;
  private static final String GRAIN = PilotModel.GRAIN;

  private static final CommodityId GRAIN_ID = new CommodityId(GRAIN);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final ActorRef TREASURY = new ActorRef(ActorKind.UNIT, "u-1");

  private static final PilotModel.Lender LENDER =
      new PilotModel.Lender(
          LENDER_ID, 10_000L, Map.of(GRAIN, 500L, "cloth", 7L), 20L, 60L, 1000L);
  private static final PilotModel.Lender OTHER_LENDER =
      new PilotModel.Lender(OTHER_LENDER_ID, 1L, Map.of(), 1L, 2L, 3L);

  // ── issue：逐条拒 ───────────────────────────────────────────────────────────────────

  @Test
  void issueRejectsUnknownUnit() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitAt(H11), economyWith(LENDER), treasury(1200L, 50L, 400L, 20L)),
                    "u-404",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在: u-404")
        .hasMessageContaining("unit.CreateUnit");
  }

  @Test
  void issueRejectsUnitWithoutAnEffectivePosition() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitWithNoPosition(), economyWith(LENDER), ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当刻没有有效位置")
        .hasMessageContaining("unit.PlaceAt");
  }

  @Test
  void issueRejectsEmptyClassFirst() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitAt(H11), EconomyData.empty(), ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("economy.UnitBorrow")
        .hasMessageContaining("只在 class-first 世界可用");
  }

  @Test
  void issueRejectsUnknownLenderAndListsExistingIds() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitAt(H11), economyWith(OTHER_LENDER), ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("放贷方不存在: " + LENDER_ID)
        .hasMessageContaining("现有放贷方")
        .hasMessageContaining(OTHER_LENDER_ID);
  }

  @Test
  void issueRejectsPrincipalAboveLendableMoneyOrGrain() {
    SimulationState state = issueState(unitAt(H11), economyWith(LENDER), ActorData.empty());

    assertThatThrownBy(
            () -> UnitDebtPlan.planIssue(state, "u-1", LENDER_ID, MONEY, 10_001L, 20L, 50L, "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("放贷方可贷 money 不足")
        .hasMessageContaining("principal=10001")
        .hasMessageContaining("available=10000");
    assertThatThrownBy(
            () -> UnitDebtPlan.planIssue(state, "u-1", LENDER_ID, GRAIN, 501L, 20L, 50L, "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("放贷方可贷 grain 不足")
        .hasMessageContaining("principal=501")
        .hasMessageContaining("available=500");
  }

  @Test
  void issueRejectsExistingUnsettledLeg() {
    EconomyData economy =
        economyWith(
            LENDER,
            leg(UNIT_MONEY_ID, "u-1", LENDER_ID, MONEY, -500L, 0L, PilotModel.AccountStatus.ACTIVE),
            leg(MIRROR_MONEY_ID, LENDER_ID, "u-1", MONEY, 500L, 0L, PilotModel.AccountStatus.ACTIVE));

    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitAt(H11), economy, ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在未结清的地方债")
        .hasMessageContaining("simos.unit.repayDebt")
        .hasMessageContaining("-500");
  }

  @Test
  void issueRejectsInconsistentMirrorLegAsIllegalState() {
    EconomyData economy =
        economyWith(
            LENDER,
            leg(MIRROR_MONEY_ID, LENDER_ID, "u-1", MONEY, 500L, 0L, PilotModel.AccountStatus.ACTIVE));

    assertThatThrownBy(
            () ->
                UnitDebtPlan.planIssue(
                    issueState(unitAt(H11), economy, ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L,
                    20L,
                    50L,
                    "unit-debt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("非 0");
  }

  // ── issue：happy Plan 数字 ──────────────────────────────────────────────────────────

  @Test
  void issuePlanCarriesEveryNumberAndTreasuryLocationForMoney() {
    UnitDebtPlan.IssuePlan plan =
        UnitDebtPlan.planIssue(
            issueState(unitAt(H11), economyWith(LENDER), treasury(1200L, 50L, 400L, 20L)),
            "u-1",
            LENDER_ID,
            MONEY,
            2000L,
            20L,
            50L,
            "unit-debt");

    assertThat(plan.unitId()).isEqualTo("u-1");
    assertThat(plan.lenderId()).isEqualTo(LENDER_ID);
    assertThat(plan.unit()).isEqualTo(MONEY);
    assertThat(plan.principal()).isEqualTo(2000L);
    assertThat(plan.interestRatePerMille()).isEqualTo(20L);
    assertThat(plan.nextDueTick()).isEqualTo(50L);
    assertThat(plan.terms()).isEqualTo("unit-debt");
    assertThat(plan.tick()).as("tick 取状态时刻").isEqualTo(7L);
    assertThat(plan.treasuryLocation()).as("国库落点 = 单位当刻有效位置").isEqualTo(H11);
    assertThat(plan.debtBefore()).as("之前无腿 ⇒ 0").isZero();
    assertThat(plan.debtAfter()).as("借后负债 = principal").isEqualTo(2000L);
    assertThat(plan.lenderAvailableBefore()).as("放贷方可贷 money = 现钱").isEqualTo(10_000L);
    assertThat(plan.lenderAvailableAfter()).as("借后可贷 = 借前 − principal").isEqualTo(8000L);
  }

  @Test
  void issuePlanCarriesGrainLendableFromGoods() {
    UnitDebtPlan.IssuePlan plan =
        UnitDebtPlan.planIssue(
            issueState(unitAt(H11), economyWith(LENDER), ActorData.empty()),
            "u-1",
            LENDER_ID,
            GRAIN,
            120L,
            7L,
            44L,
            "harvest-loan");

    assertThat(plan.unit()).isEqualTo(GRAIN);
    assertThat(plan.lenderAvailableBefore()).as("放贷方可贷 grain = goods.grain").isEqualTo(500L);
    assertThat(plan.lenderAvailableAfter()).isEqualTo(380L);
    assertThat(plan.debtBefore()).isZero();
    assertThat(plan.debtAfter()).isEqualTo(120L);
  }

  // ── repay：逐条拒 ───────────────────────────────────────────────────────────────────

  @Test
  void repayRejectsWhenThereIsNoUnsettledLeg() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planRepay(
                    state(
                        unitState(unitAt(H11)),
                        economyWith(LENDER),
                        treasury(1200L, 50L, 400L, 20L)),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有未结清的地方债")
        .hasMessageContaining(UNIT_MONEY_ID.value())
        .hasMessageContaining("simos.unit.issueDebt");
  }

  @Test
  void repayRejectsOverpayment() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planRepay(
                    state(
                        unitState(unitAt(H11)),
                        economyWith(
                            LENDER,
                            leg(
                                UNIT_MONEY_ID,
                                "u-1",
                                LENDER_ID,
                                MONEY,
                                -1000L,
                                7L,
                                PilotModel.AccountStatus.ACTIVE),
                            leg(
                                MIRROR_MONEY_ID,
                                LENDER_ID,
                                "u-1",
                                MONEY,
                                1000L,
                                42L,
                                PilotModel.AccountStatus.ACTIVE)),
                        treasury(1200L, 50L, 400L, 20L)),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    1001L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过未结清负债")
        .hasMessageContaining("amount=1001")
        .hasMessageContaining("负债=1000");
  }

  @Test
  void repayRejectsMissingTreasuryAccount() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planRepay(
                    state(
                        unitState(unitAt(H11)),
                        economyWith(
                            LENDER,
                            leg(
                                UNIT_MONEY_ID,
                                "u-1",
                                LENDER_ID,
                                MONEY,
                                -1000L,
                                0L,
                                PilotModel.AccountStatus.ACTIVE),
                            leg(
                                MIRROR_MONEY_ID,
                                LENDER_ID,
                                "u-1",
                                MONEY,
                                1000L,
                                0L,
                                PilotModel.AccountStatus.ACTIVE)),
                        ActorData.empty()),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("国库账不存在")
        .hasMessageContaining("UNIT:u-1")
        .hasMessageContaining("(1,1)")
        .hasMessageContaining("simos.unit.issueDebt");
  }

  @Test
  void repayRejectsInsufficientTreasuryAvailable() {
    assertThatThrownBy(
            () ->
                UnitDebtPlan.planRepay(
                    state(
                        unitState(unitAt(H11)),
                        economyWith(
                            LENDER,
                            leg(
                                UNIT_MONEY_ID,
                                "u-1",
                                LENDER_ID,
                                MONEY,
                                -1000L,
                                0L,
                                PilotModel.AccountStatus.ACTIVE),
                            leg(
                                MIRROR_MONEY_ID,
                                LENDER_ID,
                                "u-1",
                                MONEY,
                                1000L,
                                0L,
                                PilotModel.AccountStatus.ACTIVE)),
                        treasury(100L, 0L, 40L, 0L)),
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    1000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("国库可支配 money 不足")
        .hasMessageContaining("amount=1000")
        .hasMessageContaining("available=100")
        .hasMessageContaining("缺口=900");
  }

  @Test
  void repayRejectsMirrorMissingOrAsymmetricAsIllegalState() {
    SimulationState missingMirror =
        state(
            unitState(unitAt(H11)),
            economyWith(
                LENDER,
                leg(
                    UNIT_MONEY_ID,
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    -1000L,
                    0L,
                    PilotModel.AccountStatus.ACTIVE)),
            treasury(1200L, 0L, 400L, 0L));
    assertThatThrownBy(
            () -> UnitDebtPlan.planRepay(missingMirror, "u-1", LENDER_ID, MONEY, 100L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("不存在");

    SimulationState asymmetricMirror =
        state(
            unitState(unitAt(H11)),
            economyWith(
                LENDER,
                leg(
                    UNIT_MONEY_ID,
                    "u-1",
                    LENDER_ID,
                    MONEY,
                    -1000L,
                    0L,
                    PilotModel.AccountStatus.ACTIVE),
                leg(
                    MIRROR_MONEY_ID,
                    LENDER_ID,
                    "u-1",
                    MONEY,
                    900L,
                    0L,
                    PilotModel.AccountStatus.ACTIVE)),
            treasury(1200L, 0L, 400L, 0L));
    assertThatThrownBy(
            () -> UnitDebtPlan.planRepay(asymmetricMirror, "u-1", LENDER_ID, MONEY, 100L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("≠");
  }

  // ── repay：happy Plan 数字 ──────────────────────────────────────────────────────────

  /** ★ 部分还款：outstanding 1000→600、国库可支配 1150→750（余额 1200 − 冻结 50）、放贷方可贷 10000→10400。 */
  @Test
  void partialRepayPlanCarriesEveryNumber() {
    UnitDebtPlan.RepayPlan plan =
        UnitDebtPlan.planRepay(
            repayState(1000L, 1200L, 50L), "u-1", LENDER_ID, MONEY, 400L);

    assertThat(plan.unitId()).isEqualTo("u-1");
    assertThat(plan.lenderId()).isEqualTo(LENDER_ID);
    assertThat(plan.unit()).isEqualTo(MONEY);
    assertThat(plan.amount()).isEqualTo(400L);
    assertThat(plan.tick()).isEqualTo(7L);
    assertThat(plan.treasuryLocation()).isEqualTo(H11);
    assertThat(plan.outstandingBefore()).isEqualTo(1000L);
    assertThat(plan.outstandingAfter()).as("未结清 ⇒ 非 0").isEqualTo(600L);
    assertThat(plan.treasuryAvailableBefore()).as("余额 1200 − 冻结 50").isEqualTo(1150L);
    assertThat(plan.treasuryAvailableAfter()).isEqualTo(750L);
    assertThat(plan.lenderAvailableBefore()).isEqualTo(10_000L);
    assertThat(plan.lenderAvailableAfter()).isEqualTo(10_400L);
  }

  /** ★ 全额还款：outstanding 归 0（视图 settled=true 的算法就是它 == 0），国库与放贷方各自走完差额。 */
  @Test
  void fullRepayPlanSettlesOutstandingToZero() {
    UnitDebtPlan.RepayPlan plan =
        UnitDebtPlan.planRepay(
            repayState(1000L, 1200L, 50L), "u-1", LENDER_ID, MONEY, 1000L);

    assertThat(plan.outstandingBefore()).isEqualTo(1000L);
    assertThat(plan.outstandingAfter()).as("全额 ⇒ 0 = 视图 settled").isZero();
    assertThat(plan.treasuryAvailableBefore()).isEqualTo(1150L);
    assertThat(plan.treasuryAvailableAfter()).isEqualTo(150L);
    assertThat(plan.lenderAvailableAfter()).isEqualTo(11_000L);
  }

  /** ★ grain 维度走 goods 的可用量算法（余额 400 − 冻结 20 = 380）与放贷方 goods.grain 增量。 */
  @Test
  void grainRepayPlanUsesGoodsAvailableAndCreditsLenderGoods() {
    EconomyData economy =
        economyWith(
            LENDER,
            leg(
                GRAIN_DEBT_ID,
                "u-1",
                LENDER_ID,
                GRAIN,
                -200L,
                0L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                GRAIN_MIRROR_ID,
                LENDER_ID,
                "u-1",
                GRAIN,
                200L,
                0L,
                PilotModel.AccountStatus.ACTIVE));
    SimulationState state =
        state(unitState(unitAt(H11)), economy, treasury(1200L, 50L, 400L, 20L));

    UnitDebtPlan.RepayPlan plan =
        UnitDebtPlan.planRepay(state, "u-1", LENDER_ID, GRAIN, 120L);

    assertThat(plan.outstandingBefore()).isEqualTo(200L);
    assertThat(plan.outstandingAfter()).as("未结清（非全额）").isEqualTo(80L);
    assertThat(plan.treasuryAvailableBefore()).as("grain 余额 400 − 冻结 20").isEqualTo(380L);
    assertThat(plan.treasuryAvailableAfter()).isEqualTo(260L);
    assertThat(plan.lenderAvailableBefore()).as("放贷方可贷 grain = goods.grain 500").isEqualTo(500L);
    assertThat(plan.lenderAvailableAfter()).isEqualTo(620L);
  }

  // ── 确定性 ──────────────────────────────────────────────────────────────────────────

  /** ★ 同状态同参数两次 plan **逐字段**相等（不是只比整体 equals）。 */
  @Test
  void bothPlansAreDeterministicFieldByField() {
    SimulationState issueWorld = issueState(unitAt(H11), economyWith(LENDER), ActorData.empty());
    UnitDebtPlan.IssuePlan issue =
        UnitDebtPlan.planIssue(issueWorld, "u-1", LENDER_ID, MONEY, 2000L, 20L, 50L, "unit-debt");
    UnitDebtPlan.IssuePlan issueAgain =
        UnitDebtPlan.planIssue(issueWorld, "u-1", LENDER_ID, MONEY, 2000L, 20L, 50L, "unit-debt");
    assertThat(issueAgain.unitId()).isEqualTo(issue.unitId());
    assertThat(issueAgain.lenderId()).isEqualTo(issue.lenderId());
    assertThat(issueAgain.unit()).isEqualTo(issue.unit());
    assertThat(issueAgain.principal()).isEqualTo(issue.principal());
    assertThat(issueAgain.interestRatePerMille()).isEqualTo(issue.interestRatePerMille());
    assertThat(issueAgain.nextDueTick()).isEqualTo(issue.nextDueTick());
    assertThat(issueAgain.terms()).isEqualTo(issue.terms());
    assertThat(issueAgain.tick()).isEqualTo(issue.tick());
    assertThat(issueAgain.treasuryLocation()).isEqualTo(issue.treasuryLocation());
    assertThat(issueAgain.debtBefore()).isEqualTo(issue.debtBefore());
    assertThat(issueAgain.debtAfter()).isEqualTo(issue.debtAfter());
    assertThat(issueAgain.lenderAvailableBefore()).isEqualTo(issue.lenderAvailableBefore());
    assertThat(issueAgain.lenderAvailableAfter()).isEqualTo(issue.lenderAvailableAfter());
    assertThat(issueAgain).isEqualTo(issue);

    SimulationState repayState = repayState(1000L, 1200L, 50L);
    UnitDebtPlan.RepayPlan repay =
        UnitDebtPlan.planRepay(repayState, "u-1", LENDER_ID, MONEY, 400L);
    UnitDebtPlan.RepayPlan repayAgain =
        UnitDebtPlan.planRepay(repayState, "u-1", LENDER_ID, MONEY, 400L);
    assertThat(repayAgain.unitId()).isEqualTo(repay.unitId());
    assertThat(repayAgain.lenderId()).isEqualTo(repay.lenderId());
    assertThat(repayAgain.unit()).isEqualTo(repay.unit());
    assertThat(repayAgain.amount()).isEqualTo(repay.amount());
    assertThat(repayAgain.tick()).isEqualTo(repay.tick());
    assertThat(repayAgain.treasuryLocation()).isEqualTo(repay.treasuryLocation());
    assertThat(repayAgain.outstandingBefore()).isEqualTo(repay.outstandingBefore());
    assertThat(repayAgain.outstandingAfter()).isEqualTo(repay.outstandingAfter());
    assertThat(repayAgain.treasuryAvailableBefore()).isEqualTo(repay.treasuryAvailableBefore());
    assertThat(repayAgain.treasuryAvailableAfter()).isEqualTo(repay.treasuryAvailableAfter());
    assertThat(repayAgain.lenderAvailableBefore()).isEqualTo(repay.lenderAvailableBefore());
    assertThat(repayAgain.lenderAvailableAfter()).isEqualTo(repay.lenderAvailableAfter());
    assertThat(repayAgain).isEqualTo(repay);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static final ClassFirstAccountId UNIT_MONEY_ID =
      ClassFirstAccountId.idOf("u-1", LENDER_ID, MONEY);
  private static final ClassFirstAccountId MIRROR_MONEY_ID =
      ClassFirstAccountId.idOf(LENDER_ID, "u-1", MONEY);
  private static final ClassFirstAccountId GRAIN_DEBT_ID =
      ClassFirstAccountId.idOf("u-1", LENDER_ID, GRAIN);
  private static final ClassFirstAccountId GRAIN_MIRROR_ID =
      ClassFirstAccountId.idOf(LENDER_ID, "u-1", GRAIN);

  /** 有腿的 repay 世界：money 借款腿 -principal（+镜像），国库 money 账由参数给。 */
  private static SimulationState repayState(long principal, long silver, long frozenSilver) {
    EconomyData economy =
        economyWith(
            LENDER,
            leg(
                UNIT_MONEY_ID,
                "u-1",
                LENDER_ID,
                MONEY,
                -principal,
                7L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                MIRROR_MONEY_ID,
                LENDER_ID,
                "u-1",
                MONEY,
                principal,
                42L,
                PilotModel.AccountStatus.ACTIVE));
    return state(unitState(unitAt(H11)), economy, treasury(silver, frozenSilver, 400L, 20L));
  }

  /** issue 世界（只有 unit/economy/actor 切片；planIssue 不读 actor）。 */
  private static SimulationState issueState(Unit unit, EconomyData economy, ActorData actors) {
    return state(unitState(unit), economy, actors);
  }

  private static SimulationState state(
      UnitState units, EconomyData economy, ActorData actors) {
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of(
            "unit", new UnitSnapshot(REF, T7, units),
            "economy", new EconomySnapshot(REF, T7, economy),
            "actor", new ActorSnapshot(REF, T7, actors)),
        InMemoryInfoSystem.empty());
  }

  private static EconomyData economyWith(PilotModel.Lender lender, ClassFirstAccount... legs) {
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> accounts = new LinkedHashMap<>();
    for (ClassFirstAccount leg : legs) {
      accounts.put(leg.id(), leg);
    }
    return EconomyData.empty()
        .withClassFirst(classFirst(accounts, Map.of(ExternalLenderId.of(lender.id()), lender)));
  }

  private static ClassFirstState classFirst(
      Map<ClassFirstAccountId, ClassFirstAccount> accounts,
      Map<ExternalLenderId, PilotModel.Lender> lenders) {
    return new ClassFirstState(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        accounts,
        lenders,
        ClassFirstMeta.empty());
  }

  private static ClassFirstAccount leg(
      ClassFirstAccountId id,
      String owner,
      String counterparty,
      String unit,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    return new ClassFirstAccount(
        id, owner, counterparty, unit, "unit-debt", 20L, 50L, net, interestAccrued, status);
  }

  private static ActorData treasury(
      long silver, long frozenSilver, long grain, long frozenGrain) {
    GoodsAccount account =
        new GoodsAccount(
            new GoodsAccountKey(TREASURY, H11),
            Map.of(GRAIN_ID, grain),
            Map.of(SILVER, silver),
            frozenGrain == 0L ? Map.of() : Map.of(GRAIN_ID, frozenGrain),
            frozenSilver == 0L ? Map.of() : Map.of(SILVER, frozenSilver));
    return ActorData.empty().withAccount(account);
  }

  private static UnitState unitState(Unit unit) {
    LinkedHashMap<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(U1, unit);
    return new UnitState(units);
  }

  private static Unit unitAt(HexCoord position) {
    return unit(Optional.of(position));
  }

  private static Unit unitWithNoPosition() {
    return unit(Optional.empty());
  }

  private static Unit unit(Optional<HexCoord> position) {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty());
  }
}
