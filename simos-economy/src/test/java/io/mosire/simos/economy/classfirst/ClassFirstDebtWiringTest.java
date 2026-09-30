package io.mosire.simos.economy.classfirst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.spi.EconomyGmAdjustments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GM 调整 lender 的**接线测试**（2026-09-30 阶段 1；用户点名要的"2~3 格、必然借债"小世界）。
 *
 * <p>★★ <b>为什么单写一条</b>：{@code setClassFirstLender} 改的是 {@code ClassFirstState.lenders}，而外部货币借款路径
 * 读的是 {@code config.lender()}。若 {@link ClassFirstPilotEngine#restore} 不把 state lender 写回 config，GM
 * 调整就会 "读口上改了、下一 tick 新建债仍用旧值"——本用例专门咬这条接线。
 *
 * <p>★ <b>小世界（3 个家户 = 3 个格）</b>：雇农/中农/佃农各一户，<b>零粮</b> ⇒ 当天必然出现 baseRationGap；其余池无余粮 ⇒ 民间借不到 ⇒
 * <b>必然走外部货币借款</b>（money-loan）。三家户放在 (0,0)/(1,0)/(2,0)，不依赖任何生产/收获。
 *
 * <p>★ <b>为什么先造 t0 状态、再调、再结算</b>：{@code settleOneDay(空态, …)} 走的是播种构造器，不经过 {@code restore}；只有"非空状态 +
 * settleOneDay"才会走 {@code ClassFirstPilotEngine.restore}，才咬得到接线。
 *
 * <p>★ 本用例属开发期"按用户要求提前写的接线测试"（偏离 AGENTS.md §三.0 的"测试留最后"）；其余测试仍统一留到最后。
 */
class ClassFirstDebtWiringTest {

  private static final String LENDER_ID = "GM-LENDER-TEST";

  @Test
  void lenderAdjustmentReachesTheNextNewDebtAndCollectionPowerIsRejected() throws Exception {
    PilotModel.CollectionPolicy collectionPolicy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            // ★ 阈值调到极高 = 本用例关闭催收：否则同一 tick 的 LIQUID_SEIZED 会再改写账户
            //   到期（实测 LABORER 从 77 被改成 tick+collectionInterval=91），把"新债创建时的接线"搅浑。
            1_000_000_000L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender seedLender =
        new PilotModel.Lender(LENDER_ID, 1_000_000L, Map.of(), 20L, 60L, 1000L);
    PilotConfig config =
        PilotConfig.tenancyAgriculture(
            seedLender, collectionPolicy, 90L, MobilityPolicy.tenancyDefaults());

    // ── 3 个格：三户零粮 ⇒ 必然缺口、必然外部借债 ─────────────────────────────
    List<PilotModel.Household> households = new ArrayList<>();
    households.add(grainlessHousehold("hh-0_0-laborer", PilotModel.LABORER_ID, 1000L));
    households.add(grainlessHousehold("hh-1_0-middle", PilotModel.MIDDLE_PEASANT_ID, 100L));
    households.add(grainlessHousehold("hh-2_0-tenant", PilotModel.TENANT_ID, 100L));

    // t0：不结算，只把引擎初态取出来（走的是播种构造器，不含 restore）。
    ClassFirstState t0 =
        new ClassFirstPilotEngine(config, households, List.of(seedLender)).snapshot();
    assertThat(t0.accounts()).as("t0 还没有任何债务账户").isEmpty();

    EconomyData base = EconomyData.empty().withClassFirst(t0);

    // ── GM 调参：利率 20 → 123、到期 60 → 77（走新白名单） ─────────────────────
    JsonNode adjustedParams =
        new ObjectMapper()
            .readTree(
                "{\"lenderId\":\""
                    + LENDER_ID
                    + "\",\"interestRatePerMille\":123,\"nextDueTick\":77}");
    EconomyGmAdjustments.Projection adjusted =
        EconomyGmAdjustments.project(
            base, "setClassFirstLender", adjustedParams, "接线测试：调高利率/提前到期", 0L);
    ClassFirstState adjustedState = adjusted.projected().classFirst();
    assertThat(adjustedState.lenders().get(ExternalLenderId.of(LENDER_ID)).interestRatePerMille())
        .as("调整已落进权威 state")
        .isEqualTo(123L);

    // collectionPower 是死旋钮（全引擎无消费点）⇒ 给到即具名拒绝。
    JsonNode deadKnob =
        new ObjectMapper().readTree("{\"lenderId\":\"" + LENDER_ID + "\",\"collectionPower\":5}");
    assertThatThrownBy(
            () -> EconomyGmAdjustments.project(base, "setClassFirstLender", deadKnob, "死旋钮", 0L))
        .as("collectionPower 当前无消费点，必须拒绝而不是静默接受")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collectionPower");

    // ── 第 1 天：非空状态 ⇒ 走 restore ⇒ 必然外部货币借款；新债必须用 GM 改后的值 ──
    ClassFirstSettlement.Result day1 =
        ClassFirstSettlement.settleOneDay(
            adjustedState, new ClassFirstSettlement.Inputs(1L, null, List.of(), List.of()));
    assertThat(day1.audit().transitions())
        .as("三家户零粮 ⇒ 必然产生外部货币借款")
        .anyMatch(
            transition ->
                transition.kind() == PilotModel.TransitionKind.BORROW_MONEY
                    && transition.debtReduced() > 0L);

    ClassFirstAccount debtor = moneyLoan(day1.state(), PilotModel.LABORER_ID, LENDER_ID);
    assertThat(debtor.interestRatePerMille()).as("新债利率必须用 GM 改后的值（接线断掉时会停在 20）").isEqualTo(123L);
    assertThat(debtor.nextDueTick()).as("新债到期必须用 GM 改后的值（接线断掉时会停在 60）").isEqualTo(77L);
    assertThat(debtor.cumulativeNet()).as("债务人侧净额为负（debt）").isNegative();

    ClassFirstAccount mirror = moneyLoan(day1.state(), LENDER_ID, PilotModel.LABORER_ID);
    assertThat(mirror.cumulativeNet())
        .as("镜像账户逐值相反（debt == claim）")
        .isEqualTo(-debtor.cumulativeNet());

    long debt = 0L;
    long claim = 0L;
    long net = 0L;
    for (ClassFirstAccount account : day1.state().accounts().values()) {
      if (account.cumulativeNet() < 0L) {
        debt += -account.cumulativeNet();
      }
      if (account.cumulativeNet() > 0L) {
        claim += account.cumulativeNet();
      }
      net += account.cumulativeNet();
    }
    assertThat(debt).as("Σ债务 == Σ债权").isEqualTo(claim);
    assertThat(net).as("Σ账户净额 == 0").isZero();
  }

  /**
   * 阶段 2 接线：{@code setProductionParameters} 改 {@code meta.config} ⇒ 下一 tick 的借款额按新参数算。
   *
   * <p>同一个 3 格零粮世界：出厂 {@code moneyPerGrain=10} ⇒ LABORER 缺口 3000 借 30,000；GM 改成 20 ⇒ 必须借 60,000。
   * {@code meta.config} 没被 restore 读到时仍会停在 30,000 ⇒ 本用例咬这条。
   */
  @Test
  void productionParameterAdjustmentReachesTheNextDebtAmount() throws Exception {
    PilotModel.Lender seedLender =
        new PilotModel.Lender(LENDER_ID, 1_000_000L, Map.of(), 20L, 60L, 1000L);
    PilotConfig config =
        PilotConfig.tenancyAgriculture(
            seedLender, noCollectionPolicy(), 90L, MobilityPolicy.tenancyDefaults());
    List<PilotModel.Household> households = new ArrayList<>();
    households.add(grainlessHousehold("hh-0_0-laborer", PilotModel.LABORER_ID, 1000L));
    households.add(grainlessHousehold("hh-1_0-middle", PilotModel.MIDDLE_PEASANT_ID, 100L));
    households.add(grainlessHousehold("hh-2_0-tenant", PilotModel.TENANT_ID, 100L));

    ClassFirstState t0 =
        new ClassFirstPilotEngine(config, households, List.of(seedLender)).snapshot();
    EconomyData base = EconomyData.empty().withClassFirst(t0);

    JsonNode doubled = new ObjectMapper().readTree("{\"moneyPerGrain\":20}");
    EconomyGmAdjustments.Projection adjusted =
        EconomyGmAdjustments.project(base, "setProductionParameters", doubled, "调高粮价", 0L);
    assertThat(adjusted.projected().classFirst().meta().config().moneyPerGrain())
        .as("生产参数已落进 meta.config")
        .isEqualTo(20L);

    // 越界与假旋钮必须具名拒绝。
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    "setCollectionPolicy",
                    new ObjectMapper().readTree("{\"collectionRatioPerMille\":1001}"),
                    "越界",
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collectionRatioPerMille");
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    "setCollectionPolicy",
                    new ObjectMapper().readTree("{\"seizurePriority\":\"LIQUID_THEN_LAND\"}"),
                    "单值枚举",
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seizurePriority");

    ClassFirstSettlement.Result day1 =
        ClassFirstSettlement.settleOneDay(
            adjusted.projected().classFirst(),
            new ClassFirstSettlement.Inputs(1L, null, List.of(), List.of()));
    ClassFirstAccount debtor = moneyLoan(day1.state(), PilotModel.LABORER_ID, LENDER_ID);
    assertThat(debtor.cumulativeNet())
        .as("借款额必须按 GM 改后的 moneyPerGrain=20 计算（未接线时是 -30000）")
        .isEqualTo(-60_000L);
  }

  /** 关闭催收的 CollectionPolicy（阈值极高）：新债创建值不被同 tick 的 LIQUID_SEIZED 改写。 */
  private static PilotModel.CollectionPolicy noCollectionPolicy() {
    return new PilotModel.CollectionPolicy(
        PilotModel.LANDLORD_ID,
        1_000_000_000L,
        6000L,
        250L,
        20L,
        PilotModel.SeizurePriority.LIQUID_THEN_LAND);
  }

  /** 零粮家户：`goods` 只放 0 粮 0 布；`population` 必须 ≥ 1。 */
  private static PilotModel.Household grainlessHousehold(
      String id, String classPositionId, long population) {
    return new PilotModel.Household(
        id, id, classPositionId, population, 500L, Map.of(), 0L, 0L, 0L, 1000L);
  }

  private static ClassFirstAccount moneyLoan(
      ClassFirstState state, String ownerId, String counterpartyId) {
    return state.accounts().values().stream()
        .filter(
            account ->
                account.ownerId().equals(ownerId)
                    && account.counterpartyId().equals(counterpartyId)
                    && account.unit().equals(PilotModel.MONEY))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "没有 owner="
                        + ownerId
                        + " / counterparty="
                        + counterpartyId
                        + " 的 money 账户: "
                        + state.accounts().keySet()));
  }
}
