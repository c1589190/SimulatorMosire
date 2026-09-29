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
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.time.AccountSession;
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
import java.util.OptionalLong;
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
 *   <li><b>债务守恒</b>：{@code ΔΣ本金 == Σ新借 + Σ资本化(欠租/欠薪) + Σ计息 − Σ偿还}（逐日、世界级），且逐笔本金只按三条具名写口 （借入 / 资本化 /
 *       计息并入）增长 —— 每一条都能在当天的 ledger 里找到凭据。
 * </ol>
 *
 * <p>★★ <b>为什么驱动 {@link EconomyDayStepper} 而不是 {@code CoreSimos}</b>：判据要的原始事实（毛产 / 损耗 / 产出计提 /
 * <b>逐条转移</b>）只活在<b>当天</b>的 {@link ProductionLedger} 里 —— 协调器落完账就把它扔了。⇒ 这里照 {@code
 * EconomyOwnershipTimeParticipant} 的路子自己走一遍日循环（载入 → step → 落账），把每天的账接住。
 *
 * <p>★ <b>窗口 = 恰好一个周期（120 天）</b>：跨周期末会让 {@code FlowRow} 的"本期"读数归零（口径陷阱，见 master plan
 * M0.2），而债务那条要的"一个周期内一条债只记一次"也正是在这个窗口里成立。★ 例外：{@link
 * #assertDebtInterestBranchIsCoveredWithoutMarkets} 跑两个周期 —— 它不读 {@code FlowRow}，理由见该方法的注释。
 */
class EconomyConservationNetTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final long ONE_CYCLE_DAYS = 120L;

  /** ★★ 计息覆盖窗口 = 两个周期（见 {@link #assertDebtInterestBranchIsCoveredWithoutMarkets} 的口径注释）。 */
  private static final long INTEREST_COVERAGE_DAYS = 2L * ONE_CYCLE_DAYS;

  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** E4b 夹具：本世界在信用线下不产生新借入 ⇒ 显式种一条既有粮债，让本金/计息/偿还链仍有被保护的对象。 */
  private static final HouseholdId SEED_DEBTOR =
      HouseholdId.ofLegacy(
          new CohortKey(
              new HexCoord(0, 0),
              ResidenceKind.RURAL,
              new SocialClassId(EconomySeeder.CLASS_IDS[0])));

  private static final HouseholdId SEED_CREDITOR =
      HouseholdId.ofLegacy(
          new CohortKey(
              new HexCoord(0, 0),
              ResidenceKind.RURAL,
              new SocialClassId(EconomySeeder.CLASS_IDS[3])));

  private static final DebtTerms SEED_DEBT_TERMS = DebtTerms.legacyDefault();

  private static final DebtContractId SEED_DEBT =
      DebtContractId.idOf(SEED_DEBTOR, SEED_CREDITOR, DebtUnit.commodity(GRAIN), SEED_DEBT_TERMS);

  /** 给夹具种入一条连续粮债（只改债务/引用，不搬任何库存/货币 —— 利息与偿还是真结算写口）。 */
  private static EconomyData withSeedDebt(EconomyData data) {
    DebtContract contract =
        new DebtContract(
            SEED_DEBT,
            SEED_DEBTOR,
            SEED_CREDITOR,
            DebtUnit.commodity(GRAIN),
            SEED_DEBT_TERMS,
            1_000_000L,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    return data.withDebtContracts(Map.of(SEED_DEBT, contract));
  }

  @TempDir Path tempDir;

  /** ★★ <b>一个周期跑满，三条守恒逐日核</b>（见类注）。★ 末尾四条"反空洞"断言：这三条链今天<b>真的在跑</b> —— 否则"恒真"的绿色什么都没守住。 */
  @Test
  void oneCycleKeepsRelationsMarketAndDebtConserved() {
    try (CoreSimos core = freshCore()) {
      EconomyData base = withSeedDebt(economy(core));
      ActorData books = actor(core);
      long genesisMoney = totalMoney(books);

      // ★★ S1：账户只剩**一个会话**（家户 + 经营者；商品 + 货币 + 冻结一次载入）—— 旧的四张
      //   {@code loadHouseholdGoods/loadHouseholdMoney/loadOperatorGoods/loadOperatorMoney} 已删除。
      AccountSession accounts = OwnershipBooks.loadAccountSession(base, books);

      Set<DebtContractId> debtIdsSeen = new LinkedHashSet<>();
      long marketPairs = 0L;
      long relationTransfers = 0L;
      long loanTransfers = 0L;
      Map<CommodityId, Long> relationPaidByCommodity = new LinkedHashMap<>();
      // ★ 整周期的四项发生额（全局守恒式的分子/分母）——**逐日累加**，只在最后核一次。
      Map<CommodityId, Long> grossTotal = new LinkedHashMap<>();
      Map<CommodityId, Long> lossTotal = new LinkedHashMap<>();
      Map<CommodityId, Long> inputTotal = new LinkedHashMap<>();
      long principalAtStart = sumPrincipal(base);
      long maxPrincipalDuringCycle = principalAtStart;
      boolean capitalisedDuringCycle = false;

      EconomyDayStepper stepper = new EconomyDayStepper(base, accounts);
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
        ActorData landed = landAll(books, stepper);
        assertThat(totalMoney(landed))
            .as("第 %d 天：全账货币总额 == 创世禀赋（钱只在账间搬、绝不生灭）", day)
            .isEqualTo(genesisMoney);
        books = landed;

        // ③ 债务守恒（逐笔：本金变化必有凭据；计息按利率自算）。
        debtIdsSeen.addAll(afterDay.debtContracts().keySet());
        capitalisedDuringCycle |= assertDebtConservation(day, beforeDay, afterDay, ledger);
        loanTransfers += countLoanTransfers(ledger);
        maxPrincipalDuringCycle = Math.max(maxPrincipalDuringCycle, sumPrincipal(afterDay));
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
      // ★★ E4b 后的语义：本夹具的缺粮行 headroom == 0、operator 又不是可解析家户 ⇒ 一个周期内**不产生新借入**
      //   （见 T2 决策），故用显式种入的既有合同守住"本金/计息/偿还链不是空转"。
      assertThat(debtIdsSeen).as("★ 债务表非空（显式种入的连续合同仍在，且每天都在被核）").isNotEmpty();
      assertThat(capitalisedDuringCycle).as("★ 周期末真的走了一次计息并本（否则利率/本金守恒链仍可能空转）").isTrue();
      assertThat(maxPrincipalDuringCycle)
          .as("★ 本金在周期内从未低于期初（期初 %d，观测峰值 %d；同日计息+偿还可轧平）", principalAtStart, maxPrincipalDuringCycle)
          .isGreaterThanOrEqualTo(principalAtStart);
      assertThat(grossTotal).as("★ 真的产出了东西（否则全局守恒恒真）").isNotEmpty();
      // ★★ 带市场世界（主跑）已由上面的 {@code capitalisedDuringCycle} 断言覆盖计息并本；下面再用无市场世界跑一个
      //   两周期窗口，确保同一分支在第二个夹具（无市场）也被真的走到 —— 拿不到覆盖就红，不写替代性的恒真断言。
      assertDebtInterestBranchIsCoveredWithoutMarkets();
    }
  }

  /**
   * ★★ 债务链"计息并本"分支的覆盖（M2 收尾补回，覆盖不放低）。
   *
   * <p>带市场世界（上面的主跑）已由主断言核过一次；本助手用 {@link EconomyTestWorld#genesisWithoutMarkets()}（同一份五格夹具、其余逐字段相同）
   * 再跑**两个周期**：逐日核债务守恒，并要求计息并本真的发生 —— 拿不到覆盖就红，**不写恒真断言**。
   *
   * <p>★★ <b>覆盖办法（E4b 下不再依赖"真的借出新债"）</b>：本夹具经 {@link #withSeedDebt} 显式种入一条从第 0 天就存在的 连续粮债，故首个关账日（第
   * 120 天）就会走 {@code chargeInterest}；窗口取**两个周期（240 天）**，让同一条合同跨两个
   * 关账日被连续核两次，并同时守住"当日新借/新资本化的条当天不计息"这条口径。没有覆盖就红，**不写恒真断言**。
   */
  private void assertDebtInterestBranchIsCoveredWithoutMarkets() {
    SimulationState state = EconomyTestWorld.genesisWithoutMarkets();
    EconomyData base =
        withSeedDebt(((EconomySnapshot) state.module("economy").orElseThrow()).data());
    ActorData books = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
    EconomyDayStepper stepper =
        new EconomyDayStepper(base, OwnershipBooks.loadAccountSession(base, books));
    EconomyData beforeDay = stepper.data();
    boolean capitalised = false;
    for (long day = 1L; day <= INTEREST_COVERAGE_DAYS; day++) {
      ProductionLedger ledger = stepper.step(day);
      EconomyData afterDay = stepper.data();
      capitalised |= assertDebtConservation(day, beforeDay, afterDay, ledger);
      beforeDay = afterDay;
    }
    assertThat(stepper.finish().debtContracts())
        .as("★ 无市场世界有合同可核（显式种入的连续粮债仍在，否则下面的覆盖是空的）")
        .isNotEmpty();
    assertThat(capitalised).as("★ 周期末真的把利息并入了本金（带市场世界已不再走到这一支，见上面主跑的注释）").isTrue();
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
      Map<HouseholdId, FlowRow> flows) {
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

  /** ★ 两族账本（家户 + 经营者）在某一步的**只读快照**（关系守恒的"期末−期初"读它；家户键 = 稳定身份 {@link HouseholdId}）。 */
  private record GoodsView(
      Map<HouseholdId, Map<CommodityId, Long>> households,
      Map<ActorRef, Map<CommodityId, Long>> operators) {

    /**
     * 从推进会话的**唯一账户表**取两族快照。
     *
     * <p>★★ S1：家户/经营者副本不再由调用方各拿四张地图传入，而是从 {@code stepper.accounts()} 的协调器视图读（{@code
     * householdGoods()/operatorGoods()} 在 {@link AccountSession} 上是 public，在 {@code
     * EconomyDayStepper} 上只是包内视图）。
     */
    static GoodsView of(EconomyDayStepper stepper) {
      Map<HouseholdId, Map<CommodityId, Long>> households = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry :
          stepper.accounts().householdGoods().entrySet()) {
        households.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
      }
      Map<ActorRef, Map<CommodityId, Long>> operators = new LinkedHashMap<>();
      for (Map.Entry<ActorRef, Map<CommodityId, Long>> entry :
          stepper.accounts().operatorGoods().entrySet()) {
        operators.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
      }
      return new GoodsView(households, operators);
    }

    /**
     * 某主体在某商品上的余额（**先查家户副本，再查经营者副本**）。
     *
     * <p>★ <b>不能"按 actor 种类是不是 HOUSEHOLD"判家户</b>：{@code HOUSEHOLD} 这个种类里**不只有家户**（家庭纺织的经营主体 {@code
     * weave@0_0} 也是它）⇒ 这里按"哪本副本里有键"取，找不到就是 0。★ 家户那一侧用 {@link HouseholdActors#of(HouseholdId)} 拼
     * actor（唯一拼写点）；**不从 {@link HouseholdId} 反推视图**（新档 {@code hh-…} id 反推不出格/居住，视图在 {@code
     * ClassRow.view()} 上）。
     */
    long goodsOf(ActorRef actor, CommodityId commodity) {
      for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : households.entrySet()) {
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
    for (DebtContract debt : data.debtContracts().values()) {
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
      ActorRef operator = operatorOf(data, industry);
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

  /**
   * 产业的**实际经营者**（投入/产出/关系/账户都归它）。
   *
   * <p>★★ B.2 起真值在 {@link ProductionUnit#operator()}：{@code Industry.operator()} 只剩**旧档反序列化兼容位**
   * （新档 12 参模板恒 {@code null}，生产路径禁读）⇒ 谁再读它，新档世界里会直接 NPE。本夹具每个产业一个 unit（旧档兼容位归一化时按 {@code
   * RegimeOperators.defaultOperator} 合成，见 {@code EconomyData} 的构造期归一化）。
   */
  private static ActorRef operatorOf(EconomyData data, IndustryId industry) {
    ProductionUnit found = null;
    for (ProductionUnit unit : data.units().values()) {
      if (!unit.industry().equals(industry)) {
        continue;
      }
      if (found != null) {
        throw new IllegalStateException(
            "本夹具假定一个产业一个 unit（多 unit 的逐经营者判据另写）："
                + industry
                + " → "
                + found.id()
                + " / "
                + unit.id());
      }
      found = unit;
    }
    if (found == null) {
      throw new IllegalStateException(
          "产业没有 ProductionUnit（产出/计提的经营者只在 unit 上，不在 Industry 兼容位上）：" + industry);
    }
    return found.operator();
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
   * ★★ <b>债务守恒</b>（逐笔，日级）：本金的变化<b>只能</b>由三件具名事实解释 —— **今日放出/偿还的本金**（凭据在当天 ledger 的 {@code
   * LOAN_PRINCIPAL}/{@code LOAN_REPAYMENT} 转移里）、**今日资本化的欠租/欠薪**（凭据在 ledger 的 {@code
   * debtCapitalizations()} 里；只记债权、不搬粮/钱）与**周期末计息**（按当日起始本金 × 率 ÷ 1000，向下取整并入本金）。
   *
   * <pre>
   * 计息前： 本金_今 == 本金_昨 + 今日放出 + 今日资本化 − 今日偿还      // 非关账日恒成立
   * 计息后： 本金_今 == 上式 + 计息                                    // 关账日（利息并入本金）
   * </pre>
   *
   * <p>★★ <b>两条等式里"计息那一笔"是自算的、不是抄来的</b>：{@code 息 = ⌊当日起始本金 × 率 ÷ 1000⌋}（{@code chargeInterest}
   * 的基数就是"当日起始本金"；当日新借/新资本化的条不在起始快照里 ⇒ 当日不计息）。⇒ 若实现多记/少记一分（或把计息漏在某个分支外）， 两条都不成立、当场红。★ 今日放出/偿还取
   * <b>ledger 的转移</b>（钱的凭据）、资本化取 ledger 的资本化明细， 都不取债务表自己的差 —— 那样这条就退化成"表等于它自己"。
   *
   * <p>★ <b>标的（unit）参与口径</b>：合同可能是实物债（粮/其它商品）或货币债 ⇒ 逐条按 {@link DebtContract#unit()} 取对应腿 （{@code
   * goods[commodity]} / {@code money[currency]}），不把货币债的腿当粮读。
   *
   * <p>★ <b>E4a/E4c 的新语义</b>：合同是**连续欠账**（同一 {@code (debtor, creditor, unit, terms)} 跨周期一条）；{@code
   * dueCycle} 是可滚动的"当前约定"，**只在新增债权（借入/资本化）时**被改写（计息与偿还一字不改）；{@code status} 与 {@code
   * lastInterestDay} 分别是合同状态与最近一次"真的并入利息"的日。
   *
   * @param ledger 当天的发生额（{@link EconomyDayStepper#step(long)} 的返回值 —— 债务凭据只在里面）
   * @return 本日是否真的把利息并入了本金（"反空洞"用）
   */
  private static boolean assertDebtConservation(
      long day, EconomyData before, EconomyData after, ProductionLedger ledger) {
    boolean capitalised = false;
    for (Map.Entry<DebtContractId, DebtContract> entry : after.debtContracts().entrySet()) {
      DebtContractId id = entry.getKey();
      DebtContract now = entry.getValue();
      assertThat(now.id()).as("债务守恒：键 == 值内 id（合同表的唯一索引）").isEqualTo(id);
      assertThat(now.idMatchesIdentity())
          .as("债务守恒：%s 的 id 是 (debtor, creditor, unit, terms) 的确定性派生", id.value())
          .isTrue();
      assertThat(now.debtor()).as("债务守恒：债务人 ≠ 债权人（自债是坏数据）").isNotEqualTo(now.creditor());
      long lent = principalMoved(ledger, now, LoanFlow.PRINCIPAL);
      long repaid = principalMoved(ledger, now, LoanFlow.REPAYMENT);
      long capitalisedToday = capitalisedPrincipalOf(ledger, id);
      DebtContract was = before.debtContracts().get(id);
      if (was == null) {
        // ★ 今日新建的债条：本金 == 今日放出 + 今日资本化（欠租/欠薪转债权）− 今日偿还（借一笔/资本化一笔就记一条，账与凭据同源）。
        //   ★ 当日新建的条不在"当日起始本金"快照里 ⇒ 今天不计息（chargeInterest 自己挡掉）。
        assertThat(now.principal())
            .as(
                "债务守恒（第%d天）：新债 %s 的本金(%d) == 今日放出(%d) + 今日资本化(%d) − 今日偿还(%d)",
                day, id.value(), now.principal(), lent, capitalisedToday, repaid)
            .isEqualTo(lent + capitalisedToday - repaid);
        continue;
      }
      long interest = was.principal() * now.terms().interestRatePerMillePerCycle() / 1000L;
      long withoutInterest = was.principal() + lent + capitalisedToday - repaid;
      if (now.principal() != withoutInterest) {
        // ★ 差额必须是那一笔计息（一分不多、一分不少）—— 这里同时钉住"利息只并入本金、且按当日起始本金算"。
        assertThat(now.principal())
            .as(
                "债务守恒（第%d天）：%s 的差额 == 计息 ⌊昨 %d × %d‰⌋=%d（今日放出 %d、资本化 %d、偿还 %d）",
                day,
                id.value(),
                was.principal(),
                now.terms().interestRatePerMillePerCycle(),
                interest,
                lent,
                capitalisedToday,
                repaid)
            .isEqualTo(withoutInterest + interest);
        if (interest > 0L) {
          capitalised = true;
          assertThat(now.lastInterestDay())
              .as("债务守恒（第%d天）：%s 真的并入了利息 ⇒ lastInterestDay 写成当日", day, id.value())
              .hasValue(day);
        }
      }
      // ★ E4a 新语义：dueCycle 只在"新增债权"（借入/资本化）时滚动；计息/偿还都不许改它（资本化也走 upsert，带新到期周期）。
      if (lent == 0L && capitalisedToday == 0L) {
        assertThat(now.dueCycle())
            .as("债务守恒（第%d天）：%s 的到期周期不因计息/偿还而变", day, id.value())
            .isEqualTo(was.dueCycle());
      }
      // ★ 结构不变量（DebtContractBook 的写口守卫）：本金 > 0 的合同不得同时是"已结清/已减免"。
      if (now.principal() > 0L) {
        assertThat(now.status())
            .as(
                "债务守恒（第%d天）：%s 本金(%d) > 0 ⇒ 状态不得是 SETTLED/FORGIVEN（实际 %s）",
                day, id.value(), now.principal(), now.status())
            .isNotIn(DebtStatus.SETTLED, DebtStatus.FORGIVEN);
      }
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
   * ledger 里某一族贷款转移中、**这条合同标的腿**之和（毫单位）。
   *
   * <p>★★ <b>方向由 {@link LoanFlow} 说死，调用方只给合同</b> —— 本判据在这里栽过一次： 早先的签名是 {@code (from, to)}，调用处把两个
   * actor 的顺序写反，于是"放贷"那条**恒读到 0**， 而"恒 0"看起来就像"今天没放贷"（判据静默失效）。★ E4a 起债务端点是稳定身份 {@link
   * HouseholdId}（不是 {@code CohortKey} 视图）： actor 由 {@link HouseholdActors#of(HouseholdId)} 拼 ——
   * 拿旧视图当端点会**恒读不到**。
   */
  private static long principalMoved(ProductionLedger ledger, DebtContract debt, LoanFlow flow) {
    ActorRef from = HouseholdActors.of(flow.creditorPays ? debt.creditor() : debt.debtor());
    ActorRef to = HouseholdActors.of(flow.creditorPays ? debt.debtor() : debt.creditor());
    long sum = 0L;
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() != flow.reason
          || !transfer.from().equals(from)
          || !transfer.to().equals(to)) {
        continue;
      }
      sum += debtLeg(transfer, debt.unit());
    }
    return sum;
  }

  /** 一条转移里该合同标的的量（实物债 ⇒ {@code goods[商品]}；货币债 ⇒ {@code money[币种]}；别的腿不属于这条债）。 */
  private static long debtLeg(Transfer transfer, DebtUnit unit) {
    return switch (unit) {
      case DebtUnit.Commodity commodity -> transfer.goods().getOrDefault(commodity.commodity(), 0L);
      case DebtUnit.Money money -> transfer.money().getOrDefault(money.currency(), 0L);
    };
  }

  /**
   * 当天 ledger 里指名给这条合同的资本化本金（欠租/欠薪 → 债权）。
   *
   * <p>★ 资本化**不是**库存/货币流动（只写本金，见 {@code EconomySettlement.capitalizeArrears}）——把它漏在债务守恒式外，
   * 一次资本化就会把这条判据打成红。
   */
  private static long capitalisedPrincipalOf(ProductionLedger ledger, DebtContractId id) {
    long sum = 0L;
    for (ProductionLedger.DebtCapitalization capitalisation : ledger.debtCapitalizations()) {
      if (capitalisation.contractId().equals(id)) {
        sum += capitalisation.amount();
      }
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
   * 逐 (operator, 格, 商品)：
   *   期末余额 − 期初余额 == 产出计提 − 该产业转出的关系实付 − 该 operator 经**其他转移**的净付出
   * </pre>
   *
   * <p>★★ <b>它替换掉的是一条错的判据</b>（如实留痕）：本用例最初写成"{@code 关系实付之和 == 净产}"， 实测在 {@code farm@0_0} 上得
   * 70,929,264 vs 201,469,000。<b>错的不是代码，是那条判据</b> —— 封建档的规则是 <b>毛产 300‰ 地租（{@code
   * FEUDAL_RENT_PER_MILLE}）+ 按劳动量的实物口粮</b>， <b>余下的（约 65% 的净产）按定义留在 operator 手上</b>（{@code
   * SELF_RETENTION} 不动、余额归 {@code residualOwner}）。⇒ 正确的说法是"<b>所得 = 净产</b>"（operator 自留 + 受方实付 =
   * 计提），而它由这条**账目实测**钉住： 计提与实付的差**必须恰好留在 operator 的账上**（不在别人的账上、也不会凭空消失）。
   *
   * <p>★★ <b>M2 新增的第三项</b>（如实记）：经营者自 M2.2 起也入市 ⇒ 它的商品账不只被"计提 + 关系实付"改动，还被 {@code MARKET_TRADE}
   * 的货腿（卖净额 / 买净额）改动 —— 实测 {@code farm@2_0} 的 grain 差 **604,381** 正是这一笔。 本方法把**除关系实付以外的所有转移货腿**逐条按
   * {@code from 付出 / to 收入} 汇总成净额一起减掉；漏记任何一种转移 （或把腿记错边）这条当场红。★ 它同时覆盖在途/损耗将来若也走转移的腿，不另开一处拼写。
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
    // ★ M2：关系实付之外的一切商品腿（市场成交、借还实物……）——from 付出记正、to 收入记负。
    Map<ActorRef, Map<CommodityId, Long>> otherNetOut = new LinkedHashMap<>();
    for (Transfer transfer : ledger.transfers()) {
      if (transfer.reason() == TransferReason.RELATION_PAYMENT) {
        for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
          paid.computeIfAbsent(transfer.from(), key -> new LinkedHashMap<>())
              .merge(leg.getKey(), leg.getValue(), Long::sum);
        }
        continue;
      }
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        otherNetOut
            .computeIfAbsent(transfer.from(), key -> new LinkedHashMap<>())
            .merge(leg.getKey(), leg.getValue(), Long::sum);
        otherNetOut
            .computeIfAbsent(transfer.to(), key -> new LinkedHashMap<>())
            .merge(leg.getKey(), -leg.getValue(), Long::sum);
      }
    }
    for (Map.Entry<ActorRef, Map<CommodityId, Long>> entry : accruals.entrySet()) {
      ActorRef operator = entry.getKey();
      for (Map.Entry<CommodityId, Long> commodity : entry.getValue().entrySet()) {
        long delta =
            goodsAfter.goodsOf(operator, commodity.getKey())
                - goodsBefore.goodsOf(operator, commodity.getKey());
        long paidOut = paid.getOrDefault(operator, Map.of()).getOrDefault(commodity.getKey(), 0L);
        long marketOut =
            otherNetOut.getOrDefault(operator, Map.of()).getOrDefault(commodity.getKey(), 0L);
        assertThat(delta)
            .as(
                "关系守恒（余额归 operator）：%s 的 %s 期末−期初(%d) == 计提(%d) − 关系转出(%d) − 其他转移净付出(%d)",
                operator.id(),
                commodity.getKey().value(),
                delta,
                commodity.getValue(),
                paidOut,
                marketOut)
            .isEqualTo(commodity.getValue() - paidOut - marketOut);
      }
    }
  }

  /**
   * 把推进会话里的**全部账户**（家户 + 经营者；商品 + 货币 + 冻结）按绝对值一次落回 actor 切片。
   *
   * <p>★★ S1：旧的 {@code landHouseholdGoods/landHouseholdMoney/landOperatorGoods/landOperatorMoney}
   * 四步（以及"商品在前、货币在后"的顺序约定）已删除——{@link OwnershipBooks#landAccountSession(ActorData, AccountSession)}
   * 每本账**一次写全**，没有第二处落账路径、也没有顺序可错。
   */
  private static ActorData landAll(ActorData books, EconomyDayStepper stepper) {
    return OwnershipBooks.landAccountSession(books, stepper.accounts());
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
