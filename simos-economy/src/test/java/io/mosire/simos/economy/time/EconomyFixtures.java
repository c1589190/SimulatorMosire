package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
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
   * <p>★★ <b>H1：家户账是会话状态</b>（裁定 K1）⇒ 每个调用方必须自己带一份**工作副本**（{@link #householdGoods()}）进来：
   * 它<b>就地更新</b>，推进结束后调用方从它读家户余额（{@code ClassRow} 里已经没有库存了）。
   *
   * @param base 结算前的状态（它必须**已经在** {@code fromTick} 那一刻）
   * @param goods 家户账工作副本（**就地更新**；每个 {@code population > 0} 的家户都必须有键，否则结算当场抛）
   */
  static EconomyData advance(
      EconomyData base, Map<CohortKey, Map<CommodityId, Long>> goods, long fromTick, long toTick) {
    return advance(base, goods, fromTick, toTick, EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * 同 {@link #advance(EconomyData, Map, long, long)}，但**致死率可注入**（{@link EconomyDayStepper}
   * 的包内可见旋钮）。
   */
  static EconomyData advance(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> goods,
      long fromTick,
      long toTick,
      int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    if (base.meta().isEmpty()) {
      return base; // 未激活：不做任何公式（§6.6）—— 与 EconomySettlement.settle 的早退同款
    }
    // ★ H4（裁定 K14）：日推进入了"两份会话副本"的时代 —— 货币副本必须显式给。
    //   ★ 本助手服务的是**不量货币**的那些夹具（它们连市场表都没有）⇒ 给"每个家户一本空钱包"的
    //     合法状态（世界的货币总量 = 0），而不是悄悄借道"缺省即 0"。
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            goods,
            EconomySettlement.emptyMoneyAccountsFor(base.classes().keySet()),
            true,
            famineMortalityPerMille);
    for (long day = fromTick + 1L; day <= toTick; day++) {
      stepper.step(day);
    }
    return stepper.finish();
  }

  // ── H1：家户账工作副本的夹具助手 ────────────────────────────────────────────────────

  /**
   * ★★ <b>一份夹具 = 经济状态 + 它的家户账工作副本</b>（H1；裁定 K1/K2）。
   *
   * <p>★★ <b>为什么必须成对交出来</b>：H1 之后"某家户有多少粮"这件事**不在** {@code EconomyData} 里（行里没有 {@code goods}）——
   * 它住在会话工作副本里。夹具若只交出状态，每个用例都得自己再抄一遍期初库存 ⇒ 两份数字必然漂开（而漂开不会报错， 只会让期望值悄悄错）。成对交出 ⇒ "期初库存"只有一个拼写点。
   */
  record World(EconomyData data, Map<CohortKey, Map<CommodityId, Long>> goods) {}

  /**
   * ★★ <b>一份空的家户账工作副本</b>（H1 的会话状态；裁定 K1）：键 = 家户身份、值 = 商品余额。
   *
   * <p>★ 它的形状与生产代码**逐字相同**（{@code EconomyDayStepper} 的入参）—— 夹具不另造一种写法，否则"哪一份副本"这件事
   * 会在两处各有一个答案（而写歪了不会报错，只会让字面量悄悄错）。
   */
  static LinkedHashMap<CohortKey, Map<CommodityId, Long>> householdGoods() {
    return new LinkedHashMap<>();
  }

  /**
   * 给某个家户在某商品上放一笔余额（{@code amount <= 0} ⇒ **不落键**，保持"空商品表"的纯形态）。
   *
   * <p>★ 同一个家户可以逐个商品调用（内层表是**替换**式更新，与生产代码的 {@code setStock} 同口径）。
   */
  static void hold(
      Map<CohortKey, Map<CommodityId, Long>> goods,
      CohortKey key,
      CommodityId commodity,
      long amount) {
    LinkedHashMap<CommodityId, Long> inner = new LinkedHashMap<>(goods.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      inner.remove(commodity);
    } else {
      inner.put(commodity, amount);
    }
    goods.put(key, inner);
  }

  /** 某个家户在某商品上的余额（读口；没有这个键 ⇒ 0）。 */
  static long stockOf(
      Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key, CommodityId commodity) {
    return goods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 粮的余额（{@link #stockOf} 的粮特化 —— 绝大多数夹具只量粮）。 */
  static long grainOf(Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key) {
    return stockOf(goods, key, new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID));
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
                      EconomySettlement.hexOfIndustry(id),
                      ResidenceKind.RURAL,
                      new SocialClassId(PEASANT_SLOT))),
              Pool.NET_AFTER_INPUTS,
              Weight.LABOR_AMOUNT,
              1000,
              0L,
              Optional.of(new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID)),
              Optional.empty(),
              10);
      relations.put(id, new ProductionRelation(id, operator, null, List.of(rule), operator));
    }
    return relations;
  }

  /** 受方槽位：贫农（与 {@code EconomyVocabulary} 的阶层词表同源）。 */
  private static final String PEASANT_SLOT = "poor_peasant";
}
