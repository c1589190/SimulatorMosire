package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code economy.UnitRepay} 处理器（收尾期 T4a · 地方债第二腿）：命令面 / targetPaths 边界、部分还款（money/grain，tick
 * 未到 vs 已到期的 ACTIVE/DUE）、全额清偿 ⇒ 双腿 {@code SETTLED} 且各自 fields 原样、逐条具名拒因、以及"镜像腿缺失 / 不对称 =
 * 状态损坏 ⇒ {@link IllegalStateException}（不是 {@code Rejected}）"。
 *
 * <p>★ 判别力：两条腿用**不同的** terms/rate/due/interestAccrued（而不是抄同一份），"只改净额、把另一条腿的 fields 抄过去 /
 * 把 accrued 归零 / 状态按错的那条腿判"都会当场红；守恒面把 lender 余额的加减与双腿净额变化逐值钉住。
 */
class UnitRepayHandlerTest {

  private static final UnitRepayHandler HANDLER = new UnitRepayHandler();
  private static final EconomyCodec CODEC = new EconomyCodec();
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final String UNIT_ID = "u-1";
  private static final String LENDER_ID = "GOV-LENDER";
  private static final String MONEY = PilotModel.MONEY;
  private static final String GRAIN = PilotModel.GRAIN;

  private static final ClassFirstAccountId DEBT_MONEY_ID =
      ClassFirstAccountId.idOf(UNIT_ID, LENDER_ID, MONEY);
  private static final ClassFirstAccountId MIRROR_MONEY_ID =
      ClassFirstAccountId.idOf(LENDER_ID, UNIT_ID, MONEY);
  private static final ClassFirstAccountId DEBT_GRAIN_ID =
      ClassFirstAccountId.idOf(UNIT_ID, LENDER_ID, GRAIN);
  private static final ClassFirstAccountId MIRROR_GRAIN_ID =
      ClassFirstAccountId.idOf(LENDER_ID, UNIT_ID, GRAIN);

  private static final PilotModel.Lender LENDER =
      new PilotModel.Lender(
          LENDER_ID, 8000L, Map.of(GRAIN, 100L, "cloth", 7L), 20L, 60L, 1000L);

  // ── 命令面 ──────────────────────────────────────────────────────────────────────────

  @Test
  void typeIsEconomyUnitRepay() {
    assertThat(HANDLER.type()).isEqualTo("economy.UnitRepay");
    assertThat(UnitRepayHandler.TYPE).isEqualTo("economy.UnitRepay");
  }

  /** ★ 裸账目原语：标 {@link GmOnlyCommand}，合法载荷返回空目标表（没有 economy 格键目标）。 */
  @Test
  void handlerIsGmOnlyAndTargetPathsAreEmptyForValidPayload() {
    assertThat(HANDLER).isInstanceOf(GmOnlyCommand.class);
    assertThat(HANDLER).isInstanceOf(CommandTargets.class);
    assertThat(HANDLER.targetPaths("Map1", repayJson(UNIT_ID, LENDER_ID, MONEY, 100L)))
        .isEmpty();
    assertThat(HANDLER.targetPaths("Map1", repayJson(UNIT_ID, LENDER_ID, GRAIN, 100L)))
        .isEmpty();
  }

  /** ★ 坏载荷（非法 JSON / 缺字段 / 词表外 unit / amount 越界）在 targetPaths 就抛。 */
  @Test
  void targetPathsThrowOnBadPayloads() {
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> HANDLER.targetPaths("Map1", repayJson(UNIT_ID, LENDER_ID, "silver", 100L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit");
    assertThatThrownBy(
            () -> HANDLER.targetPaths("Map1", repayJson(UNIT_ID, LENDER_ID, MONEY, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount");
  }

  // ── happy：部分 / 全额 ──────────────────────────────────────────────────────────────

  /**
   * ★ money 部分还款、tick &lt; due ⇒ 双腿仍 ACTIVE：借款腿 -1000→-600、镜像腿 +1000→+600、lender 8000→8400；两条腿各自的
   * terms/rate/due/interestAccrued 原样保留（不许互相抄）。
   */
  @Test
  void partialMoneyRepaymentBelowDueKeepsActiveAndCreditsLender() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -1000L, 7L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, MONEY, "lender-copy", 99L, 90L, 1000L, 42L,
                PilotModel.AccountStatus.ACTIVE));

    EconomyData target = apply(repayJson(UNIT_ID, LENDER_ID, MONEY, 400L), base, 10L);

    assertLeg(
        target.classFirst().accounts().get(DEBT_MONEY_ID),
        DEBT_MONEY_ID, UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -600L, 7L,
        PilotModel.AccountStatus.ACTIVE);
    assertLeg(
        target.classFirst().accounts().get(MIRROR_MONEY_ID),
        MIRROR_MONEY_ID, LENDER_ID, UNIT_ID, MONEY, "lender-copy", 99L, 90L, 600L, 42L,
        PilotModel.AccountStatus.ACTIVE);
    assertThat(target.classFirst().accounts().get(DEBT_MONEY_ID).cumulativeNet()
            + target.classFirst().accounts().get(MIRROR_MONEY_ID).cumulativeNet())
        .as("还款后 Σ=0")
        .isZero();

    PilotModel.Lender after = lenderOf(target);
    assertThat(after.money()).as("lender money 8000 + 400").isEqualTo(8400L);
    assertThat(after.goods()).as("money 还款不动 goods").isEqualTo(LENDER.goods());
  }

  /**
   * ★ grain 部分还款、tick == nextDueTick ⇒ 双腿 DUE（到期日是"到或过"）；lender goods.grain 100→300，其它商品键原样。
   */
  @Test
  void partialGrainRepaymentAtDueMarksBothLegsDueAndCreditsGoods() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, GRAIN, "unit-debt", 5L, 10L, -500L, 3L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, GRAIN, "unit-debt", 5L, 10L, 500L, 3L,
                PilotModel.AccountStatus.ACTIVE));

    EconomyData target = apply(repayJson(UNIT_ID, LENDER_ID, GRAIN, 200L), base, 10L);

    assertLeg(
        target.classFirst().accounts().get(DEBT_GRAIN_ID),
        DEBT_GRAIN_ID, UNIT_ID, LENDER_ID, GRAIN, "unit-debt", 5L, 10L, -300L, 3L,
        PilotModel.AccountStatus.DUE);
    assertLeg(
        target.classFirst().accounts().get(MIRROR_GRAIN_ID),
        MIRROR_GRAIN_ID, LENDER_ID, UNIT_ID, GRAIN, "unit-debt", 5L, 10L, 300L, 3L,
        PilotModel.AccountStatus.DUE);

    assertThat(lenderOf(target).goods())
        .as("lender grain 100 + 200，cloth 原样")
        .containsEntry(GRAIN, 300L)
        .containsEntry("cloth", 7L)
        .hasSize(2);
  }

  /**
   * ★ 全额还款 ⇒ 双腿 {@code SETTLED}、净额为 0，且各自 terms/rate/due/interestAccrued **原样**（不归零、不重写）；lender 收回全额。
   */
  @Test
  void fullRepaymentSettlesBothLegsAndPreservesEveryField() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -1000L, 7L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, MONEY, "lender-copy", 99L, 90L, 1000L, 42L,
                PilotModel.AccountStatus.ACTIVE));

    EconomyData target = apply(repayJson(UNIT_ID, LENDER_ID, MONEY, 1000L), base, 10L);

    assertLeg(
        target.classFirst().accounts().get(DEBT_MONEY_ID),
        DEBT_MONEY_ID, UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, 0L, 7L,
        PilotModel.AccountStatus.SETTLED);
    assertLeg(
        target.classFirst().accounts().get(MIRROR_MONEY_ID),
        MIRROR_MONEY_ID, LENDER_ID, UNIT_ID, MONEY, "lender-copy", 99L, 90L, 0L, 42L,
        PilotModel.AccountStatus.SETTLED);
    assertThat(lenderOf(target).money()).as("lender 8000 + 1000").isEqualTo(9000L);
  }

  // ── 拒因：逐条（Rejected，不是抛） ──────────────────────────────────────────────────

  @Test
  void rejectsWhenClassFirstIsEmpty() {
    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 100L), EconomyData.empty(), 10L);

    assertThat(reason).contains("economy.UnitRepay").contains("只在 class-first 世界可用");
  }

  @Test
  void rejectsUnknownLenderAndListsExistingIds() {
    PilotModel.Lender other = new PilotModel.Lender("OTHER", 100L, Map.of(), 1L, 2L, 3L);
    EconomyData base =
        economy(classFirst(Map.of(), Map.of(ExternalLenderId.of("OTHER"), other)));

    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 100L), base, 10L);

    assertThat(reason)
        .contains("放贷方不存在")
        .contains(LENDER_ID)
        .contains("现有放贷方")
        .contains("OTHER");
  }

  @Test
  void rejectsWhenDebtLegIsMissing() {
    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 100L), economyWith(LENDER), 10L);

    assertThat(reason)
        .contains("没有未结清的地方债")
        .contains(DEBT_MONEY_ID.value());
  }

  @Test
  void rejectsWhenDebtLegIsAlreadySettled() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, 0L, 0L,
                PilotModel.AccountStatus.SETTLED));

    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 100L), base, 10L);

    assertThat(reason)
        .contains("没有未结清的地方债")
        .contains(DEBT_MONEY_ID.value());
  }

  @Test
  void rejectsAmountBelowOne() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -500L, 0L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, MONEY, "unit-debt", 20L, 50L, 500L, 0L,
                PilotModel.AccountStatus.ACTIVE));

    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 0L), base, 10L);

    assertThat(reason).contains("amount").contains(">= 1").contains("0");
  }

  @Test
  void rejectsOverpaymentWithoutChange() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -1000L, 0L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, MONEY, "unit-debt", 20L, 50L, 1000L, 0L,
                PilotModel.AccountStatus.ACTIVE));

    String reason = rejection(repayJson(UNIT_ID, LENDER_ID, MONEY, 1001L), base, 10L);

    assertThat(reason)
        .contains("超过未结清负债")
        .contains("amount=1001")
        .contains("负债=1000");
  }

  // ── 状态损坏：IllegalStateException，不得折成 Rejected ───────────────────────────────

  /** ★ 借款腿存在（净额 &lt; 0）而镜像腿缺失 ⇒ Σ=0 被破坏，必须当场炸。 */
  @Test
  void missingMirrorLegThrowsIllegalStateExceptionNotRejected() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -500L, 0L,
                PilotModel.AccountStatus.ACTIVE));

    assertThatThrownBy(
            () -> HANDLER.handle(state(base, 10L), repayJson(UNIT_ID, LENDER_ID, MONEY, 100L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("不存在")
        .hasMessageContaining("-500");
  }

  /** ★ 两条腿净额不互为相反数 ⇒ Σ=0 被破坏，必须当场炸（不从 400 静默补差额）。 */
  @Test
  void asymmetricMirrorLegThrowsIllegalStateExceptionNotRejected() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID, LENDER_ID, MONEY, "unit-debt", 20L, 50L, -500L, 0L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID, UNIT_ID, MONEY, "unit-debt", 20L, 50L, 400L, 0L,
                PilotModel.AccountStatus.ACTIVE));

    assertThatThrownBy(
            () -> HANDLER.handle(state(base, 10L), repayJson(UNIT_ID, LENDER_ID, MONEY, 100L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("≠")
        .hasMessageContaining("500");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 执行一次 repay：Applied ⇒ 变更集 ⇒ 直连 apply + JSON 线往返各重建一次，二者必须与 target 逐值相等。 */
  private static EconomyData apply(String payload, EconomyData base, long tick) {
    HandlerOutcome outcome = HANDLER.handle(state(base, tick), payload);
    assertThat(outcome).as("happy 载荷必须 Applied").isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.classFirst().changed()).as("classFirst 组件必须被报成改变").isTrue();
    EconomyData target = EconomyChangeSet.apply(changeSet, base);

    EconomyChangeSet decoded =
        (EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(EconomyChangeSet.apply(decoded, base))
        .as("变更集过 JSON 线往返后重建逐值不变")
        .isEqualTo(target);
    return target;
  }

  private static String rejection(String payload, EconomyData base, long tick) {
    HandlerOutcome outcome = HANDLER.handle(state(base, tick), payload);
    assertThat(outcome)
        .as("该载荷必须走 Rejected（而不是抛异常）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static void assertLeg(
      ClassFirstAccount leg,
      ClassFirstAccountId id,
      String owner,
      String counterparty,
      String unit,
      String terms,
      long rate,
      long due,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    assertThat(leg).as("腿必须存在: %s", id.value()).isNotNull();
    assertThat(leg.id()).isEqualTo(id);
    assertThat(leg.id()).isEqualTo(ClassFirstAccountId.idOf(owner, counterparty, unit));
    assertThat(leg.ownerId()).isEqualTo(owner);
    assertThat(leg.counterpartyId()).isEqualTo(counterparty);
    assertThat(leg.unit()).isEqualTo(unit);
    assertThat(leg.terms()).isEqualTo(terms);
    assertThat(leg.interestRatePerMille()).isEqualTo(rate);
    assertThat(leg.nextDueTick()).isEqualTo(due);
    assertThat(leg.cumulativeNet()).isEqualTo(net);
    assertThat(leg.interestAccrued()).isEqualTo(interestAccrued);
    assertThat(leg.status()).isEqualTo(status);
  }

  private static PilotModel.Lender lenderOf(EconomyData data) {
    return data.classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
  }

  private static EconomyData economyWith(PilotModel.Lender lender, ClassFirstAccount... legs) {
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> accounts = new LinkedHashMap<>();
    for (ClassFirstAccount leg : legs) {
      accounts.put(leg.id(), leg);
    }
    return economy(classFirst(accounts, Map.of(ExternalLenderId.of(lender.id()), lender)));
  }

  private static EconomyData economy(ClassFirstState classFirst) {
    return EconomyData.empty().withClassFirst(classFirst);
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
      String owner,
      String counterparty,
      String unit,
      String terms,
      long rate,
      long due,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    return new ClassFirstAccount(
        ClassFirstAccountId.idOf(owner, counterparty, unit),
        owner,
        counterparty,
        unit,
        terms,
        rate,
        due,
        net,
        interestAccrued,
        status);
  }

  private static SimulationState state(EconomyData data, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new SimulationState(
        new StateMeta(REF, at),
        Map.of("economy", new EconomySnapshot(REF, at, data)),
        InMemoryInfoSystem.empty());
  }

  private static String repayJson(String unitId, String lenderId, String unit, long amount) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unitId);
    payload.put("lenderId", lenderId);
    payload.put("unit", unit);
    payload.put("amount", amount);
    return json(payload);
  }

  private static String json(Map<String, Object> fields) {
    try {
      return JSON.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new AssertionError("测试载荷序列化失败", e);
    }
  }
}
