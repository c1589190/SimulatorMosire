package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RF2d 验收：受方 = operator 的自关系规则必须跳过，不铸 Transfer、不产生自债</b>。
 *
 * <p>判据出处：{@code docs/superpowers/plans/2026-10-20-d032-rf2d-self-relation-skip-taskbook.md} §6。
 *
 * <p>★ <b>判别力</b>：自规则若仍进入结算，会以 {@code Transfer} 两端相等被守卫拒绝（整次 advance 失败），或 静默吃 {@code paid}
 * 额度使后续规则少付。本类同时钉住"不出自腿"与"自规则不消耗已付额度"两条。
 */
class Rf2dSelfRelationSkipTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("rf2d-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-rf2d-farm");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final HouseholdId OTHER = HouseholdId.parse("hh-rf2d-other");

  private static final long SELF_AMOUNT = 100L;
  private static final long OTHER_AMOUNT = 200L;

  @Test
  void selfRuleIsSkippedWithoutTransferOrDebtAndDoesNotConsumePaidCapacity() {
    ProductionRules relation =
        new ProductionRules(
            UNIT,
            OPERATOR,
            new Payee.ToActor(OPERATOR),
            List.of(
                // ① 自规则：受方 = operator（坏数据），必须被跳过。
                fixedRent(new Payee.ToActor(OPERATOR), SELF_AMOUNT, 10),
                // ② 对照：正常的不同主体规则，必须按原顺序/金额支付。
                fixedRent(new Payee.ToHousehold(OTHER), OTHER_AMOUNT, 20)),
            OPERATOR,
            LaborSource.WAGE);
    ProductionSettlement.Facts facts =
        new ProductionSettlement.Facts(
            HEX,
            Map.of(GRAIN, 1_000L),
            Map.of(GRAIN, 1_000L),
            Map.of(),
            Map.of(OTHER, 100L),
            Map.of(GRAIN, 1L),
            Map.of(),
            Map.of(GRAIN, 1_000L));

    ProductionSettlement.Outcome outcome = ProductionSettlement.settle(relation, facts);

    assertThat(outcome.transfers()).as("★ 自规则不得铸 Transfer；只允许不同主体的那一条").hasSize(1);
    assertThat(outcome.transfers().get(0).to()).isEqualTo(HouseholdActors.of(OTHER));
    assertThat(outcome.transfers().get(0).goods())
        .as("★ 不同主体规则仍按原金额支付")
        .containsExactly(Map.entry(GRAIN, OTHER_AMOUNT));
    assertThat(outcome.transfers())
        .as("自支付不是发生额 ⇒ 不允许出现 to == operator 的腿")
        .noneMatch(transfer -> transfer.to().equals(OPERATOR));
    assertThat(outcome.ruleSettlements())
        .as("★ 自规则不进入 paid/owed/ruleSettlements 读数（只有正常规则一条）")
        .hasSize(1);
    assertThat(outcome.ruleSettlements().get(0).rule().type())
        .isEqualTo(RuleType.FIXED_IN_KIND_RENT);
    assertThat(outcome.ruleSettlements().get(0).rule().recipient())
        .isEqualTo(new Payee.ToHousehold(OTHER));
    assertThat(outcome.ruleSettlements().get(0).paidNow()).isEqualTo(OTHER_AMOUNT);
    assertThat(outcome.arrears()).as("★ 自规则不得产生自债（owed 不进 arrears）").isEmpty();
    assertThat(outcome.deferredMoney()).as("自规则也不得进货币待办").isEmpty();
  }

  private static CompensationRule fixedRent(Payee payee, long amount, int priority) {
    return new CompensationRule(
        RuleType.FIXED_IN_KIND_RENT,
        payee,
        Pool.FIXED_AMOUNT,
        Weight.NONE,
        0,
        amount,
        Optional.of(GRAIN),
        Optional.empty(),
        priority);
  }
}
