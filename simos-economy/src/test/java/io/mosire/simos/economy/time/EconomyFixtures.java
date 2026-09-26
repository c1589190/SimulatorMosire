package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>本包用例的推进写法</b>（S1 阶段 4+5 Task 4 的迁移）：把"推 N 天"写成 <b>{@link EconomyDayStepper} 的会话形态</b>。
 *
 * <p>★★ <b>为什么必须换写法</b>（裁定 E7 / 计划 R4）：{@code EconomySettlement.settle(base, from, to)} 这个多日静态入口
 * 自本阶段起 <b>fail-closed</b> —— 它没有产权落账口，一旦跨过周期末就<b>当场抛</b>（产出离开 {@code ClassRow} 之后 必须由同时看得见 {@code
 * economy} 与 {@code actor} 的地方落账）。而"逐日跑、每天交回当天的账"这件事 只有会话形态做得到 ⇒ 单模块用例一律走它。
 *
 * <p>★ <b>为什么抽成一个方法而不是每处各写三行</b>："一次 N 天 == N 次单日"这条语义只能有一个载体 —— 十几处各写一遍 必然有一处写歪（少跑一天、日号从 0 起、忘了
 * {@code finish()}），而写歪<b>不会报错</b>，只会让字面量悄悄错。
 *
 * <p>★ <b>包内可见</b>（{@code final class} + 私有构造，同本仓夹具的形制）：它只服务本包用例，不是生产 API。
 */
final class EconomyFixtures {

  private EconomyFixtures() {}

  /**
   * 从 {@code fromTick + 1} 逐日推到 {@code toTick}，交出终态 —— 与 {@code EconomySettlement.settle(base,
   * from, to)} 的**等价路径**（那边的日循环调的就是这里调的东西）。
   *
   * @param base 结算前的状态（它必须**已经在** {@code fromTick} 那一刻）
   */
  static EconomyData advance(EconomyData base, long fromTick, long toTick) {
    return advance(base, fromTick, toTick, EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /** 同 {@link #advance(EconomyData, long, long)}，但**致死率可注入**（{@link EconomyDayStepper} 的包内可见旋钮）。 */
  static EconomyData advance(
      EconomyData base, long fromTick, long toTick, int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    if (base.meta().isEmpty()) {
      return base; // 未激活：不做任何公式（§6.6）—— 与 EconomySettlement.settle 的早退同款
    }
    EconomyDayStepper stepper = new EconomyDayStepper(base, true, famineMortalityPerMille);
    for (long day = fromTick + 1L; day <= toTick; day++) {
      stepper.step(day);
    }
    return stepper.finish();
  }

  /**
   * ★★ <b>本包夹具的共同约定</b>：给每个产业一条「<b>净产按劳动全给该格贫农 cohort</b>」的关系 （{@code OUTPUT_SHARE × LABOR_AMOUNT}
   * 1000‰，粮）。
   *
   * <p>★★ <b>为什么夹具必须显式给这一条</b>（而不是留空关系表）：产出自 T4 起<b>不再写进阶层行</b> —— 行里唯一还有实物的通道是关系结算的 cohort
   * 入账。空关系表会让这些夹具<b>静默变成"行一粒不收"</b>， 而它们本来量的是日耗 / 播种 / 周期边界 —— 那等于把被测物换掉了（本仓纪律：<b>夹具违反新口径要修夹具</b>，
   * 但**不许**顺手把断言放宽成"行是 0"）。
   *
   * <p>★ <b>为什么取 1000‰</b>：{@code OUTPUT_SHARE × LABOR_AMOUNT} 的量 = {@code net × rate ÷ 1000 × own
   * ÷ Σ劳动}， 而这些夹具里<b>只有一行有人口</b> ⇒ {@code own ÷ Σ = 1} ⇒ <b>实付恰好等于净产</b>。于是"单行夹具"的既有收获字面量
   * <b>一字不改</b>（改的只是分布口径：{@code Split} → 关系规则），而"两行夹具"里拿不到产出的那一行也有了明确语义 （它不在任何 cohort 的受方里 —— 见
   * {@code classRowsOfCohort} 的 {@code population > 0}）。
   *
   * <p>★ <b>operator 取自产业自己</b>（{@code EconomyData} 的跨表守卫要求两者一致）：夹具不另写一遍"谁经营"。
   */
  static Map<IndustryId, ProductionRelation> laborShareToPeasant(
      Map<IndustryId, Industry> industries) {
    Map<IndustryId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      IndustryId id = entry.getKey();
      ActorRef operator = entry.getValue().operator();
      CompensationRule rule =
          new CompensationRule(
              RuleType.OUTPUT_SHARE,
              new Recipient.ToCohort(
                  new CohortKey(
                      EconomySettlement.hexOfIndustry(id), new SocialClassId(PEASANT_SLOT))),
              Basis.LABOR_AMOUNT,
              1000,
              0L,
              Optional.of(new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID)),
              10);
      relations.put(id, new ProductionRelation(id, operator, List.of(rule), operator));
    }
    return relations;
  }

  /** 受方槽位：贫农（与 {@code EconomyVocabulary} 的阶层词表同源）。 */
  private static final String PEASANT_SLOT = "poor_peasant";
}
