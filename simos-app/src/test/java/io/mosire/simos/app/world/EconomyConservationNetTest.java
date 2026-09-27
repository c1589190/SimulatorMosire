package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.app.time.EconomyOwnershipTimeParticipant;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionSettlement;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>M0.5：三条守恒回归网</b>（先决条件；在它们绿之前不许动 {@code MarketSettlement} 与偿还链）。
 *
 * <p>★★ <b>它为什么必须存在</b>（实测结论，见 master plan §1.5 教训 2）：{@code MarketSettlement} <b>整类零测试</b>、
 * 偿还链（{@code repayDebts} / {@code LOAN_REPAYMENT}）<b>零测试</b>、{@code EconomySettlement} §6.1 的主守恒式
 * <b>只是注释</b>。改坏了没人拦得住 ⇒ 只能等一年期真档读数漂开才发现（那时已经不知道是哪一天坏的）。
 *
 * <p>★★ <b>三条判据（逐条与 master plan M0.5 对应）</b>：
 *
 * <ol>
 *   <li><b>关系守恒</b>：逐产业 × 逐商品，{@code 产出计提 == 毛产 − 损耗} 且 {@code 关系实付之和 == 净产} ⟺ <b>Σ各主体所得 =
 *       净产</b>（operator 自留 = 净产 − 转出，余额归 {@code residualOwner}）；
 *   <li><b>市场守恒</b>：逐笔 {@code MARKET_TRADE} 成交 <b>货腿钱腿成对</b>（货腿在前、钱腿紧随、买卖双方同向同格）， 货款 == {@code ⌈数量
 *       × 价 ÷ 1000⌉}；★ 且<b>逐日全账货币总额恒定</b>（钱只在账间搬，绝不生灭）；
 *   <li><b>债务守恒</b>：{@code ΔΣ本金 == Σ新借 + Σ计息 − Σ偿还}（逐日、世界级），且逐笔本金只按<b>计息那一条规则</b>增长。
 * </ol>
 *
 * <p>★★ <b>为什么驱动 {@link EconomyDayStepper} 而不是 {@code CoreSimos}</b>：判据要的原始事实（毛产 / 损耗 / 产出计提 /
 * <b>逐条转移</b>）只活在<b>当天</b>的 {@link ProductionLedger} 里 —— 协调器落完账就把它扔了。⇒ 这里照 {@code
 * EconomyOwnershipTimeParticipant} 的路子自己走一遍日循环（载入 → step → 落账），把每天的账接住。
 *
 * <p>★ <b>窗口 = 恰好一个周期（120 天）</b>：跨周期末会让 {@code FlowRow} 的"本期"读数归零（口径陷阱，见 master plan
 * M0.2），而债务那条要的"一个周期内一条债只记一次"也正是在这个窗口里成立。
 */
class EconomyConservationNetTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final long ONE_CYCLE_DAYS = 120L;
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  @TempDir Path tempDir;

  /** ★★ <b>一个周期跑满，三条守恒逐日核</b>（见类注）。★ 末尾四条"反空洞"断言：这三条链今天<b>真的在跑</b> —— 否则"恒真"的绿色什么都没守住。 */
  @Test
  void oneCycleKeepsRelationsMarketAndDebtConserved() {
    try (CoreSimos core = freshCore()) {
      EconomyData base = economy(core);
      ActorData books = actor(core);
      long genesisMoney = totalMoney(books);

      Map<CohortKey, Map<CommodityId, Long>> householdGoods =
          OwnershipBooks.loadHouseholdGoods(base, books);
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney =
          OwnershipBooks.loadHouseholdMoney(base, books);
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods =
          OwnershipBooks.loadOperatorGoods(base, books);
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney =
          OwnershipBooks.loadOperatorMoney(base, books);

      Set<DebtId> debtIdsSeen = new LinkedHashSet<>();
      long marketPairs = 0L;
      long relationTransfers = 0L;
      long loanTransfers = 0L;
      Map<CommodityId, Long> relationPaidByCommodity = new LinkedHashMap<>();
      // ★ 整周期的四项发生额（全局守恒式的分子/分母）——**逐日累加**，只在最后核一次。
      Map<CommodityId, Long> grossTotal = new LinkedHashMap<>();
      Map<CommodityId, Long> lossTotal = new LinkedHashMap<>();
      Map<CommodityId, Long> inputTotal = new LinkedHashMap<>();
      long principalAtStart = sumPrincipal(base);
      boolean capitalisedInterest = false;

      EconomyDayStepper stepper =
          new EconomyDayStepper(base, householdGoods, householdMoney, operatorGoods, operatorMoney);
      // ★★ **窗口对齐：`before` 取上一步之后的终态、`ledger` 与 `after` 同属这一步** ——
      //   写成 `before = stepper.data(); ledger = step(); after = stepper.data()` 会**错开一格**：
      //   `after` 里已经含着这一步算出的债/本金，而 `ledger` 是这一步的凭据 —— 但 `before` 是**上一步之后**的态，
      //   于是"昨日的本金"其实是"两步前"的，`chargeInterest` 那一笔的基数就对不上（实测差 83 就是这么来的）。
      EconomyData beforeDay = stepper.data();
      GoodsView openingGoods = GoodsView.of(stepper);
      GoodsView goodsBefore = openingGoods;
      for (long day = 1L; day <= ONE_CYCLE_DAYS; day++) {
        ProductionLedger ledger = stepper.step(day);
        EconomyData afterDay = stepper.data();
        GoodsView goodsAfter = GoodsView.of(stepper);

        // ① 关系守恒（逐产业 × 逐商品；余额归 operator 那一份由"账目实测"那一条单独钉）。
        assertRelationConservation(afterDay, ledger);
        assertOperatorResiduals(ledger, goodsBefore, goodsAfter);
        for (Transfer transfer : ledger.transfers()) {
          if (transfer.reason() != TransferReason.RELATION_PAYMENT) {
            continue;
          }
          relationTransfers++;
          for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
            relationPaidByCommodity.merge(leg.getKey(), leg.getValue(), Long::sum);
          }
        }
        for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
          for (CommodityId commodity : entry.getValue().keySet()) {
            grossTotal.merge(commodity, ledger.grossOf(entry.getKey(), commodity), Long::sum);
            lossTotal.merge(commodity, ledger.lossOf(entry.getKey(), commodity), Long::sum);
          }
        }
        for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.inputs().entrySet()) {
          for (Map.Entry<CommodityId, Long> leg : entry.getValue().entrySet()) {
            inputTotal.merge(leg.getKey(), leg.getValue(), Long::sum);
          }
        }

        // ② 市场守恒（成交对 + 货款算式 + 全账货币总额恒定）。
        marketPairs += countAndAssertMarketTrades(afterDay, ledger);
        ActorData landed = landAll(afterDay, books, stepper);
        assertThat(totalMoney(landed))
            .as("第 %d 天：全账货币总额 == 创世禀赋（钱只在账间搬、绝不生灭）", day)
            .isEqualTo(genesisMoney);
        books = landed;

        // ③ 债务守恒（逐笔：本金变化必有凭据；计息按利率自算）。
        debtIdsSeen.addAll(afterDay.debts().keySet());
        capitalisedInterest |= assertDebtConservation(day, beforeDay, afterDay, ledger);
        loanTransfers += countLoanTransfers(ledger);
        beforeDay = afterDay; // ★ 下一格的"昨"
        goodsBefore = goodsAfter;
      }

      // ④ ★★ **全局商品守恒**（M0.5 的收口；整周期一个窗口）：逐商品
      //     `Σ账本库存变化 == 毛产 − 生产投入 − 生产损耗`（家户 + 经营者两族账本都算进来）。
      //   ★ 期初那一份取**会话开头的快照**（`openingGoods`），不取 actor 侧的 `books` ——
      //     两族副本都是从 `books` **载入**来的，用 `books` 当"期初"会读到**载入前**的账
      //     （实测：fiber 期初读作 0、期末 63,490,860 ⇒ 等式左边恒 0，而右边是整周期的产出）。
      assertGlobalGoodsConservation(
          openingGoods, stepper, grossTotal, lossTotal, inputTotal, stepper.finish().flows());

      // ── 反空洞：这几条链今天真的在跑（否则绿色是恒真的）───────────────────────────────
      assertThat(genesisMoney).as("创世真的发了钱（货币守恒不是 0 == 0）").isPositive();
      assertThat(marketPairs).as("★ 一个周期里真的发生了市场成交（否则市场守恒恒真）").isPositive();
      assertThat(relationTransfers).as("★ 关系实付真的发生了（否则关系守恒恒真）").isPositive();
      assertThat(relationPaidByCommodity).as("★ 关系实付真的分了粮（不是只有空表）").containsKey(GRAIN);
      assertThat(debtIdsSeen).as("★ 真的借出了债（否则债务守恒恒真）").isNotEmpty();
      assertThat(loanTransfers).as("★ 真的放出了粮（否则'新债必有凭据'恒真）").isPositive();
      assertThat(sumPrincipal(stepper.data()))
          .as("★ 一个周期后债务本金变大（昨 %d → 今 %d）", principalAtStart, sumPrincipal(stepper.data()))
          .isGreaterThan(principalAtStart);
      assertThat(grossTotal).as("★ 真的产出了东西（否则全局守恒恒真）").isNotEmpty();
      assertThat(capitalisedInterest).as("★ 周期末真的把利息并入了本金（否则那条等式只走到「计息前」那一支）").isTrue();
    }
  }

  /**
   * ★★ <b>全局商品守恒</b>（整周期一个窗口；{@code EconomySettlement} §6.1 的 H1 式）：
   *
   * <pre>
   * 逐商品： ΔΣ账本库存 == Σ毛产 − Σ生产损耗 − Σ流水 consumed − 未并入 consumed 的投入
   *        ★ consumed 里含"从家户账扣的"投入（见 FlowRow 的类注），但不含"供方 == 经营者"那一支
   *        （recordOperatorInputDraw 刻意不记 —— 那一支只从账本变化里看得见）。两条支路都不许重复记。
   * </pre>
   *
   * <p>★★ <b>为什么它比"逐日对拍"结实</b>：式子两边都是**整周期的量**（左边是状态差、右边是逐日 ledger 的累加），
   * 不存在"发生额落在哪一天的哪一行"的窗口问题（那正是本批踩了三次的坑）。<b>逐商品</b>核 ⇒ 任何一种商品的产出/投入/损耗 只要有一笔记错边（记进 A 记漏 B），当场红。
   *
   * <p>★ <b>它覆盖的账本</b>：家户（{@code householdGoods} 副本的终值）+ 经营者（{@code operatorGoods} 副本的终值）—— 与协调器落回
   * actor 的那两族**同源**（{@link #landAll}）。★ 读数一律取**副本终值**而不是 actor 侧，避免把"落盘"这件事 混进守恒判据里（落盘本身由 {@code
   * OwnershipBooks} 的用例守）。
   */
  private static void assertGlobalGoodsConservation(
      GoodsView openingGoods,
      EconomyDayStepper stepper,
      Map<CommodityId, Long> grossTotal,
      Map<CommodityId, Long> lossTotal,
      Map<CommodityId, Long> inputTotal,
      Map<CohortKey, FlowRow> flows) {
    Map<CommodityId, Long> opening = goodsTotalOf(openingGoods);
    // ★ 期末：家户副本 + 经营者副本（两族都在会话里，见 EconomyDayStepper 的类注）。
    Map<CommodityId, Long> closing = goodsTotalOf(GoodsView.of(stepper));
    for (CommodityId commodity : grossTotal.keySet()) {
      long produced = grossTotal.getOrDefault(commodity, 0L);
      long lost = lossTotal.getOrDefault(commodity, 0L);
      long consumedAsInput = inputTotal.getOrDefault(commodity, 0L);
      long delta = closing.getOrDefault(commodity, 0L) - opening.getOrDefault(commodity, 0L);
      // ★★ H1 式里的"真正被吃掉的"= Σ流水 consumed − Σ现扣投入（consumed 里还含投入那一项，见 FlowRow 的类注）。
      long consumed = 0L;
      for (FlowRow flow : flows.values()) {
        consumed += flow.consumed().getOrDefault(commodity, 0L);
      }
      // ★★ 实测确认的两条支路（M0.2 的口径就是它）：`consumed` = 日耗 + **从家户账扣的**现扣投入。
      //   另一条支路（供方 == 经营者，见 EconomySettlement.recordOperatorInputDraw）**刻意不记 consumed**
      //   ⇒ 那一笔只能从"账本变化"里看见。两者都允许，但**不许两处都记**（那就重复扣一次）。
      long inputsInConsumed = Math.min(consumedAsInput, consumed);
      long inputsOutsideConsumed = consumedAsInput - inputsInConsumed;
      assertThat(consumed >= inputsInConsumed)
          .as("全局守恒：%s 的 consumed(%d) ≥ 已并入的投入(%d)", commodity.value(), consumed, inputsInConsumed)
          .isTrue();
      assertThat(delta)
          .as(
              "全局守恒：%s 的 Δ库存(%d) == 毛产(%d) − 损耗(%d) − Σconsumed(%d) − 未并入的投入(%d)",
              commodity.value(), delta, produced, lost, consumed, inputsOutsideConsumed)
          .isEqualTo(produced - lost - consumed - inputsOutsideConsumed);
    }
  }

  /** 把两族账本（家户 + 经营者）的库存按商品各自求和（缺失 = 0）。 */
  private static Map<CommodityId, Long> goodsTotalOf(GoodsView view) {
    Map<CommodityId, Long> total = new LinkedHashMap<>();
    for (Map<CommodityId, Long> balances : view.households().values()) {
      for (Map.Entry<CommodityId, Long> leg : balances.entrySet()) {
        total.merge(leg.getKey(), leg.getValue(), Long::sum);
      }
    }
    for (Map<CommodityId, Long> balances : view.operators().values()) {
      for (Map.Entry<CommodityId, Long> leg : balances.entrySet()) {
        total.merge(leg.getKey(), leg.getValue(), Long::sum);
      }
    }
    return total;
  }

  /** ★ 两族账本（家户 + 经营者）在某一步的**只读快照**（关系守恒的"期末−期初"读它）。 */
  private record GoodsView(
      Map<CohortKey, Map<CommodityId, Long>> households,
      Map<ActorRef, Map<CommodityId, Long>> operators) {

    static GoodsView of(EconomyDayStepper stepper) {
      Map<CohortKey, Map<CommodityId, Long>> households = new LinkedHashMap<>();
      for (Map.Entry<CohortKey, Map<CommodityId, Long>> entry :
          stepper.householdGoods().entrySet()) {
        households.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
      }
      Map<ActorRef, Map<CommodityId, Long>> operators = new LinkedHashMap<>();
      for (Map.Entry<ActorRef, Map<CommodityId, Long>> entry : stepper.operatorGoods().entrySet()) {
        operators.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
      }
      return new GoodsView(households, operators);
    }

    /**
     * 某主体在某商品上的余额（**先查家户副本，再查经营者副本**）。
     *
     * <p>★ <b>不能拿 {@link HouseholdActors#cohortOf(ActorRef)} 当"是不是家户"的判据</b>：它对非 {@code HOUSEHOLD}
     * <b>宁抛不静默</b>。而 {@code HOUSEHOLD} 这个种类里**不只有家户**（家庭纺织的经营主体 {@code weave@0_0} 也是它， 见 {@code
     * OwnershipBooks.loadHouseholdGoods} 的类注）⇒ 这里按"哪本副本里有键"取，找不到就是 0。
     */
    long goodsOf(ActorRef actor, CommodityId commodity) {
      for (Map.Entry<CohortKey, Map<CommodityId, Long>> entry : households.entrySet()) {
        if (HouseholdActors.of(entry.getKey()).equals(actor)) {
          return entry.getValue().getOrDefault(commodity, 0L);
        }
      }
      return operators.getOrDefault(actor, Map.of()).getOrDefault(commodity, 0L);
    }
  }

  /** 世界债务本金合计（毫粮）。 */
  private static long sumPrincipal(EconomyData data) {
    long sum = 0L;
    for (Debt debt : data.debts().values()) {
      sum += debt.principal();
    }
    return sum;
  }

  /** 当天放贷/还贷两类转移的条数（"反空洞"用）。 */
  private static long countLoanTransfers(ProductionLedger ledger) {
    long count = 0L;
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() == TransferReason.LOAN_PRINCIPAL
          || transfer.reason() == TransferReason.LOAN_REPAYMENT) {
        count++;
      }
    }
    return count;
  }

  // ── 判据 ①：关系守恒 ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>关系守恒（逐产业 × 逐商品）</b>：{@code Σ产出计提 == 毛产 − 损耗 == 净产}，且 {@code 0 ≤ 关系实付之和 ≤ 净产}。
   *
   * <p>★★ <b>这条判据被实测更正过一次（如实留痕）</b>：最初写成"{@code 关系实付之和 == 净产}"，在 {@code farm@0_0} 上得 70,929,264 vs
   * 201,469,000 —— <b>错的不是代码，是那条判据</b>。封建档的规则是"毛产 300‰ 地租 + 按劳动量的实物口粮" （见 {@code
   * RegimeRelations.feudalRules}），<b>余下的按定义留在 operator 手上</b>（{@code SELF_RETENTION} 不动、 余额归 {@code
   * residualOwner}）⇒ "Σ各主体所得 = 净产"里的 operator 自留那一份<b>本来就该在里面</b>。 ⇒
   * 逐产业这一层只钉两件事：计提恰等于净产；转出不超过它（**不许把别人的产出付出去**）。 "自留那一份真的留在 operator 账上"由 {@link
   * #assertOperatorResiduals} 用**账目实测**钉（不是从规则反推）。
   */
  private static void assertRelationConservation(EconomyData data, ProductionLedger ledger) {
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      IndustryId industry = entry.getKey();
      ActorRef operator = data.industries().get(industry).operator();
      HexCoord location = hexOf(industry);
      for (CommodityId commodity : entry.getValue().keySet()) {
        long net = ledger.grossOf(industry, commodity) - ledger.lossOf(industry, commodity);
        assertThat(accrued(ledger, operator, commodity))
            .as("关系守恒：产业 %s 的 %s 产出计提 == 毛产 − 损耗", industry.value(), commodity.value())
            .isEqualTo(net);
        assertThat(relationPaidFrom(ledger, operator, location, commodity))
            .as(
                "关系守恒：产业 %s 的 %s 关系实付之和 ∈ [0, 净产]（余下的归 %s 自留）",
                industry.value(), commodity.value(), operator.id())
            .isBetween(0L, net);
      }
    }
  }

  /** 产业所在格（账户 = {@code (actor, location)}；{@code IndustryHexKeys} 是"产业 id 里的格"的唯一拼写点）。 */
  private static HexCoord hexOf(IndustryId industry) {
    return HexCoord.parse(IndustryHexKeys.hexKeyOf(industry).orElseThrow());
  }

  /** 产出计提里 {@code (operator, 商品)} 的合计（毫单位）。 */
  private static long accrued(ProductionLedger ledger, ActorRef operator, CommodityId commodity) {
    long sum = 0L;
    for (ProductionSettlement.ActorEntry accrual : ledger.outputAccruals()) {
      if (accrual.actor().equals(operator) && accrual.commodity().equals(commodity)) {
        sum += accrual.delta();
      }
    }
    return sum;
  }

  /** 从 {@code operator} 在这一格转出的关系实付里某商品之和（毫单位）。 */
  private static long relationPaidFrom(
      ProductionLedger ledger, ActorRef operator, HexCoord location, CommodityId commodity) {
    long sum = 0L;
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() != TransferReason.RELATION_PAYMENT
          || !transfer.from().equals(operator)
          || !transfer.location().equals(location)) {
        continue;
      }
      sum += transfer.goods().getOrDefault(commodity, 0L);
    }
    return sum;
  }

  // ── 判据 ②：市场守恒 ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>市场守恒</b>：逐笔 {@code MARKET_TRADE} 成交核"货腿钱腿成对"—— 货腿（只带货、{@code from=卖方}）之后<b>紧随</b>它的钱腿
   * （只带钱、{@code from=买方}），两者 {@code (from, to, location)} 互为反向、同格，且货款 == {@code ⌈数量 × 价 ÷ 1000⌉}
   * （价取自该格市场的价表）。
   *
   * @return 本日核过的成交对数（"反空洞"用）
   */
  private static long countAndAssertMarketTrades(EconomyData data, ProductionLedger ledger) {
    long pairs = 0L;
    for (int i = 0; i < ledger.transfers().size(); i++) {
      Transfer goodsLeg = ledger.transfers().get(i);
      if (goodsLeg.reason() != TransferReason.MARKET_TRADE || goodsLeg.goods().isEmpty()) {
        continue;
      }
      assertThat(goodsLeg.money()).as("市场守恒：货腿不带钱（一笔买卖 = 一对转移）").isEmpty();
      assertThat(i + 1)
          .as("市场守恒：货腿之后必须紧随它的钱腿（成对铸出，见 MarketSettlement.trade）")
          .isLessThan(ledger.transfers().size());
      Transfer moneyLeg = ledger.transfers().get(i + 1);
      assertThat(moneyLeg.reason())
          .as("市场守恒：钱腿的原因也是 MARKET_TRADE")
          .isEqualTo(TransferReason.MARKET_TRADE);
      assertThat(moneyLeg.goods()).as("市场守恒：钱腿不带货（钱在 money 那一维）").isEmpty();
      assertThat(moneyLeg.from()).as("市场守恒：钱腿付方 = 货腿收方（买方）").isEqualTo(goodsLeg.to());
      assertThat(moneyLeg.to()).as("市场守恒：钱腿收方 = 货腿付方（卖方）").isEqualTo(goodsLeg.from());
      assertThat(moneyLeg.location()).as("市场守恒：一对转移同格").isEqualTo(goodsLeg.location());
      CurrencyId numeraire = numeraireOf(data, goodsLeg.location());
      assertThat(moneyLeg.money().keySet())
          .as("市场守恒：钱腿只有计价货币那一种（该格市场的 numeraire）")
          .containsExactly(numeraire);
      for (Map.Entry<CommodityId, Long> goods : goodsLeg.goods().entrySet()) {
        long price = priceOf(data, goodsLeg.location(), goods.getKey());
        long expected =
            (goods.getValue() * price + EconomySettlement.MILLI_PER_GRAIN - 1L)
                / EconomySettlement.MILLI_PER_GRAIN;
        assertThat(moneyLeg.money().get(numeraire))
            .as(
                "市场守恒：货款 == ⌈%d × %d ÷ 1000⌉（%s @ %s）",
                goods.getValue(), price, goods.getKey().value(), goodsLeg.location())
            .isEqualTo(expected);
        assertThat(expected).as("市场守恒：货款 ≥ 1 毫（向下取整会让小额成交白送）").isPositive();
      }
      pairs++;
    }
    return pairs;
  }

  /** 该格的计价货币（缺格 ⇒ 判死：没市场的格不该成交）。 */
  private static CurrencyId numeraireOf(EconomyData data, HexCoord hex) {
    Market market = data.markets().get(hex);
    assertThat(market).as("市场守恒：成交必须发生在有市场的格里（%s）", hex).isNotNull();
    return market.numeraire();
  }

  /** 该格某商品的本轮报价（价表里没有 ⇒ 判死：**没定价的商品不交易**）。 */
  private static long priceOf(EconomyData data, HexCoord hex, CommodityId commodity) {
    Market market = data.markets().get(hex);
    assertThat(market).as("市场守恒：成交必须发生在有市场的格里（%s）", hex).isNotNull();
    Long price = market.prices().get(commodity);
    assertThat(price).as("市场守恒：成交的商品必须是已定价的（%s @ %s）", commodity.value(), hex).isNotNull();
    return price;
  }

  // ── 判据 ③：债务守恒 ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>债务守恒</b>（逐笔，日级）：本金的变化<b>只能</b>由两件事解释 —— **今日放出/偿还的本金**（凭据在当天的 ledger 里）与**周期末计息**（按昨日本金 ×
   * 率 ÷ 1000，向下取整）。
   *
   * <pre>
   * 计息前： 本金_今 == 本金_昨 + 今日放出 − 今日偿还                     // 非关账日恒成立
   * 计息后： 本金_今 == 本金_昨 + 今日放出 − 今日偿还 + 计息               // 关账日（利息并入本金）
   * </pre>
   *
   * <p>★★ <b>两条等式里"计息那一笔"是自算的、不是抄来的</b>：{@code 息 = ⌊本金_昨 × 率 ÷ 1000⌋}。⇒ 若实现多记/少记
   * 一分（或把计息漏在某个分支外），两条都不成立、当场红。★ 今日放出/偿还取 <b>ledger 的转移</b>（钱的凭据）， 不取债务表自己的差 —— 那样这条就退化成"表等于它自己"。
   *
   * <p>★★ <b>本批实测的形状（写进判据，免得后来者以为是恒等式）</b>：本夹具（5 格、周期 120 天）里 <b>第 1~119 天全部走"计息前"那条</b>，第 120
   * 天走"计息后"那条 —— 即 {@code chargeInterest} 只在 "今天有产业关账"那天发生（{@code settleOneDay} 的第 5 步）。逐日 dump
   * 的实测（见台账 M0.5 节）： 第 1~6 天本金 4166 / 8333 / 12500 / 16666 / 20833 / 25000，与当天 {@code
   * LOAN_PRINCIPAL} 的粮腿**逐值相等**。
   *
   * @param ledger 当天的发生额（{@link EconomyDayStepper#step(long)} 的返回值 —— 债务凭据只在里面）
   * @return 本日是否真的把利息并入了本金（"反空洞"用）
   */
  private static boolean assertDebtConservation(
      long day, EconomyData before, EconomyData after, ProductionLedger ledger) {
    boolean capitalised = false;
    for (Map.Entry<DebtId, Debt> entry : after.debts().entrySet()) {
      Debt now = entry.getValue();
      Debt was = before.debts().get(entry.getKey());
      assertThat(now.debtor()).as("债务守恒：债务人 ≠ 债权人（自债是坏数据）").isNotEqualTo(now.creditor());
      if (was == null) {
        // ★ 今日新建的债条：本金 == 今日放出的本金（借一笔就记一条，账与凭据同源）。
        assertThat(now.principal())
            .as(
                "债务守恒（第%d天）：新债 %s 的本金(%d) == 今日放出的本金(%d)",
                day,
                entry.getKey().value(),
                now.principal(),
                principalMoved(ledger, now.debtor(), now.creditor(), LoanFlow.PRINCIPAL))
            .isEqualTo(principalMoved(ledger, now.debtor(), now.creditor(), LoanFlow.PRINCIPAL));
        continue;
      }
      long lent = principalMoved(ledger, now.debtor(), now.creditor(), LoanFlow.PRINCIPAL);
      long repaid = principalMoved(ledger, now.debtor(), now.creditor(), LoanFlow.REPAYMENT);
      long interest = was.principal() * now.ratePerMillePerCycle() / 1000L;
      long withoutInterest = was.principal() + lent - repaid;
      if (now.principal() != withoutInterest) {
        // ★ 差额必须是那一笔计息（一分不多、一分不少）—— 这里同时钉住"利息只并入本金、且按昨日本金算"。
        assertThat(now.principal())
            .as(
                "债务守恒（第%d天）：%s 的差额 == 计息 ⌊昨 %d × %d‰⌋=%d（今日放出 %d、偿还 %d）",
                day,
                entry.getKey().value(),
                was.principal(),
                now.ratePerMillePerCycle(),
                interest,
                lent,
                repaid)
            .isEqualTo(withoutInterest + interest);
        capitalised |= interest > 0L;
      }
      assertThat(now.dueCycle()).as("债务守恒：到期周期不因计息/偿还而变（它记的是'哪一周期借的'）").isEqualTo(was.dueCycle());
    }
    return capitalised;
  }

  /** 放贷/还贷两族的**语义方向**（借：债权人 → 债务人；还：债务人 → 债权人）。 */
  private enum LoanFlow {
    PRINCIPAL(TransferReason.LOAN_PRINCIPAL, true),
    REPAYMENT(TransferReason.LOAN_REPAYMENT, false);

    final TransferReason reason;
    final boolean creditorPays;

    LoanFlow(TransferReason reason, boolean creditorPays) {
      this.reason = reason;
      this.creditorPays = creditorPays;
    }
  }

  /**
   * ledger 里某一族贷款转移中、某一对债务关系的粮腿之和（毫粮）。
   *
   * <p>★★ <b>方向由 {@link LoanFlow} 说死，调用方只给"债务人、债权人"</b> —— 本判据在这里栽过一次： 早先的签名是 {@code (from,
   * to)}，调用处把两个 actor 的顺序写反，于是"放贷"那条**恒读到 0**， 而"恒 0"看起来就像"今天没放贷"（判据静默失效）。
   */
  private static long principalMoved(
      ProductionLedger ledger, CohortKey debtor, CohortKey creditor, LoanFlow flow) {
    ActorRef from = HouseholdActors.of(flow.creditorPays ? creditor : debtor);
    ActorRef to = HouseholdActors.of(flow.creditorPays ? debtor : creditor);
    long sum = 0L;
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() != flow.reason
          || !transfer.from().equals(from)
          || !transfer.to().equals(to)) {
        continue;
      }
      sum += transfer.goods().getOrDefault(GRAIN, 0L);
    }
    return sum;
  }

  /** ledger 里某一族贷款转移的粮腿总和（毫粮；不看方向）。 */
  private static long ledgerMoved(ProductionLedger ledger, LoanFlow flow) {
    long sum = 0L;
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() != flow.reason) {
        continue;
      }
      sum += transfer.goods().getOrDefault(GRAIN, 0L);
    }
    return sum;
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  private CoreSimos freshCore() {
    CoreSimos core =
        new CoreSimos(new CoreConfig(tempDir, CHECKPOINT_INTERVAL, SimosObjectMapper.create()));
    core.register(new EconomyCodec());
    core.register(new ActorCodec());
    core.register(new EconomyOwnershipTimeParticipant(EconomyTestWorld.MAP_ID));
    core.bootstrapGenesis(EconomyTestWorld.genesis());
    return core;
  }

  /**
   * ★★ <b>余额归 {@code residualOwner}（= operator）—— 由账目实测，不从规则反推</b>。
   *
   * <pre>
   * 逐 (operator, 格, 商品)： 期末余额 − 期初余额 == 产出计提 − 该产业转出的关系实付
   * </pre>
   *
   * <p>★★ <b>它替换掉的是一条错的判据</b>（如实留痕）：本用例最初写成"{@code 关系实付之和 == 净产}"， 实测在 {@code farm@0_0} 上得
   * 70,929,264 vs 201,469,000。<b>错的不是代码，是那条判据</b> —— 封建档的规则是 <b>毛产 300‰ 地租（{@code
   * FEUDAL_RENT_PER_MILLE}）+ 按劳动量的实物口粮</b>， <b>余下的（约 65% 的净产）按定义留在 operator 手上</b>（{@code
   * SELF_RETENTION} 不动、余额归 {@code residualOwner}）。⇒ 正确的说法是"<b>所得 = 净产</b>"（operator 自留 + 受方实付 =
   * 计提），而它由这条**账目实测**钉住： 计提与实付的差**必须恰好留在 operator 的账上**（不在别人的账上、也不会凭空消失）。
   *
   * <p>★ 判别力：把一条实付的受方改错（付给了别的 actor）、或者把自留那一份错记到某个家户头上，这条当场红。
   *
   * @param goodsBefore 本步之前的两族副本（家户 + 经营者）
   * @param goodsAfter 本步之后的两族副本
   */
  private static void assertOperatorResiduals(
      ProductionLedger ledger, GoodsView goodsBefore, GoodsView goodsAfter) {
    Map<ActorRef, Map<CommodityId, Long>> accruals = new LinkedHashMap<>();
    for (ProductionSettlement.ActorEntry accrual : ledger.outputAccruals()) {
      accruals
          .computeIfAbsent(accrual.actor(), key -> new LinkedHashMap<>())
          .merge(accrual.commodity(), accrual.delta(), Long::sum);
    }
    Map<ActorRef, Map<CommodityId, Long>> paid = new LinkedHashMap<>();
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() != TransferReason.RELATION_PAYMENT) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        paid.computeIfAbsent(transfer.from(), key -> new LinkedHashMap<>())
            .merge(leg.getKey(), leg.getValue(), Long::sum);
      }
    }
    for (Map.Entry<ActorRef, Map<CommodityId, Long>> entry : accruals.entrySet()) {
      ActorRef operator = entry.getKey();
      for (Map.Entry<CommodityId, Long> commodity : entry.getValue().entrySet()) {
        long delta =
            goodsAfter.goodsOf(operator, commodity.getKey())
                - goodsBefore.goodsOf(operator, commodity.getKey());
        long paidOut = paid.getOrDefault(operator, Map.of()).getOrDefault(commodity.getKey(), 0L);
        assertThat(delta)
            .as(
                "关系守恒（余额归 operator）：%s 的 %s 期末−期初(%d) == 计提(%d) − 转出(%d)",
                operator.id(), commodity.getKey().value(), delta, commodity.getValue(), paidOut)
            .isEqualTo(commodity.getValue() - paidOut);
      }
    }
  }

  /** 把四份会话副本按协调器的**同一顺序**落回 actor 切片（商品在前、货币在后，顺序不能反）。 */
  private static ActorData landAll(EconomyData data, ActorData books, EconomyDayStepper stepper) {
    ActorData landed = OwnershipBooks.landHouseholdGoods(books, stepper.householdGoods());
    landed = OwnershipBooks.landHouseholdMoney(landed, stepper.householdMoney());
    landed = OwnershipBooks.landOperatorGoods(data, landed, stepper.operatorGoods());
    return OwnershipBooks.landOperatorMoney(data, landed, stepper.operatorMoney());
  }

  private static EconomyData economy(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    EconomySnapshot slice =
        (EconomySnapshot)
            state.module("economy").orElseThrow(() -> new AssertionError("状态里没有 economy 切片"));
    return slice.data();
  }

  private static ActorData actor(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    ActorSnapshot slice =
        (ActorSnapshot)
            state.module("actor").orElseThrow(() -> new AssertionError("状态里没有 actor 切片"));
    return slice.data();
  }

  /** 全账货币总额（逐本 {@code GoodsAccount} 的货币表求和 —— 家户与经营者住在同一张表里）。 */
  private static long totalMoney(ActorData books) {
    long sum = 0L;
    for (GoodsAccount account : books.accounts().values()) {
      for (long balance : account.money().values()) {
        sum += balance;
      }
    }
    return sum;
  }
}
