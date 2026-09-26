package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **V6：债务聚合（§7.2）+ 借贷三处规则（§7.1）** 的逐值用例。
 *
 * <p>★★ **夹具（除非某条用例另有说明）**：一格、一个**不产粮**的产业（{@code outputPerUnit} 空 ⇒ 收获恒 0）、两行 ——贫农（{@value
 * #PEASANT_POPULATION} 人、投入率 1000‰、**缸空**）+ 地主（0 人 ⇒ 本周期自需 0 ⇒ 余粮 = 全部库存）。
 * 产业**不产粮**是刻意的：缺口因此逐日恒等于当日口粮，债务/计息的字面量才不含收获那一笔。
 *
 * <pre>
 * 第 d 天贫农缺口 = EconomyVocabulary.dailyRationMilli(400, d)（逐日差分）
 * 头 n 天缺口合计 = EconomyVocabulary.cumulativeRationMilli(400, n)
 * </pre>
 *
 * <p>★ 多日口粮一律经 {@link EconomyVocabulary} 的公开纯函数表达（**不许**写 {@code n × 某一天的量}：日耗是逐日差分，乘不出来）。
 */
class EconomyDebtTest {

  private static final String FARM_KIND = "farm";
  private static final IndustryId FARM = IndustryHexKeys.id(FARM_KIND, 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 一个周期（天）：**10** —— 够长，能在周期内读"累加到同一条"，又够短到字面量手算得动。 */
  private static final long CYCLE_DAYS = 10L;

  private static final long PEASANT_POPULATION = 400L;

  /** 一个人的有效劳动（千分劳动）：与 §十"D4 默认"同（400 人 ⇒ 232,000）。 */
  private static final long LABOR_PER_PERSON = 580L;

  /** 放贷方的初始库存（毫粮）：够它自己吃很多个周期 ⇒ 它永远不会变成缺口行。 */
  private static final long LANDLORD_JAR = 5_000_000L;

  /** 头 {@code days} 天贫农的缺口合计（毫粮）—— 多日口粮只能用累计函数表达。 */
  private static long deficitOver(long days) {
    return EconomyVocabulary.cumulativeRationMilli(PEASANT_POPULATION, days);
  }

  /** 第 {@code day} 天贫农的缺口（毫粮）。 */
  private static long deficitOn(long day) {
    return EconomyVocabulary.dailyRationMilli(PEASANT_POPULATION, day);
  }

  // ── ① §7.2 聚合一：同周期内多次借入 ⇒ 一条、本金递增 ────────────────────────────────

  /**
   * ★★ **同一周期内借了 3 天 ⇒ 债务表里只有一条，本金 = 三次之和**（旧口径：每天各一条 ⇒ 3 条）。
   *
   * <p>★ 判别力（变异轮实测）：把 id 改回"逐日新建"（{@code debt-<day>-<seq>}）⇒ 本条先在"三天借入聚合成**一条**"那行红。
   */
  @Test
  void borrowingOnManyDaysOfOneCycleAggregatesIntoASingleDebtWithASummedPrincipal() {
    EconomyData next = EconomySettlement.settle(hex(CYCLE_DAYS, 0L, LANDLORD_JAR), 0L, 3L);

    List<DebtId> debts = next.classes().get(PEASANT_KEY).debts();
    assertThat(debts).as("三天借入聚合成**一条**（旧口径 3 条）").hasSize(1);
    Debt debt = next.debts().get(debts.get(0));
    assertThat(debt.principal())
        .as("本金 = 三次借入之和（逐日差分逐项相加）")
        .isEqualTo(deficitOn(1L) + deficitOn(2L) + deficitOn(3L));
    assertThat(debt.principal())
        .as("= cumulativeRationMilli(400, 3)（望远镜求和 ⇒ 两条写法必然同值）")
        .isEqualTo(deficitOver(3L));
    assertThat(debt.debtor()).isEqualTo(PEASANT_KEY);
    assertThat(debt.creditor()).isEqualTo(LANDLORD_KEY);
    assertThat(debt.commodity()).as("实物债（粮）").contains(GRAIN);
    assertThat(debt.ratePerMillePerCycle())
        .isEqualTo(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE);
    assertThat(debt.dueCycle()).as("到期周期 = 当前周期 + 1").isEqualTo(2L);
    assertThat(next.debts()).as("整场只此一条债").hasSize(1);
    assertThat(next.flows().get(PEASANT_KEY).newBorrowing())
        .as("流水的新借入 = 三天之和（流量口径）")
        .isEqualTo(deficitOver(3L));
    assertThat(next.debts().keySet().iterator().next().value())
        .as("★ id 由 (周期, 债务人, 债权人, 商品) 确定性算出")
        // ★ S1 阶段 1 手算重推（**不是抄实际值**）：格式 = debt-c<周期>-<债务人键>><债权人键>-<商品>；
        //   债务人键 = PEASANT_KEY = FARM|PEASANT = "farm@0_0" + "|" + "poor_peasant"（新词表）
        //   债权人键 = LANDLORD_KEY = "farm@0_0|landlord"（地主一词不变）；商品 = "grain"。
        .isEqualTo("debt-c1-farm@0_0|poor_peasant>farm@0_0|landlord-grain");
  }

  /**
   * ★★ **跨周期 ⇒ 新条，旧条仍在**（"哪一周期借的"这条信息不许被覆盖掉）。
   *
   * <pre>
   * 周期 1（第 1~10 天）：借 10 天 = cumulativeRationMilli(400, 10) = 333,333
   *      周期末计息 333,333 × 20‰ = 6,666（向下取整）⇒ 本金 339,999
   * 周期 2（第 11~13 天）：借 3 天 = cumulativeRationMilli(400, 13) − cumulativeRationMilli(400, 10) = 100,000
   * </pre>
   */
  @Test
  void aNewCycleOpensANewDebtAndKeepsTheOldOne() {
    EconomyData next =
        EconomySettlement.settle(hex(CYCLE_DAYS, 0L, LANDLORD_JAR), 0L, CYCLE_DAYS + 3L);

    assertThat(next.classes().get(PEASANT_KEY).debts()).as("两个周期各一条（行内两处引用）").hasSize(2);
    assertThat(next.debts()).as("债务表两条").hasSize(2);
    long cycleOne = deficitOver(CYCLE_DAYS);
    Debt first =
        next.debts().get(new DebtId("debt-c1-farm@0_0|poor_peasant>farm@0_0|landlord-grain"));
    Debt second =
        next.debts().get(new DebtId("debt-c2-farm@0_0|poor_peasant>farm@0_0|landlord-grain"));
    assertThat(first).as("★ 旧条没被新周期覆盖").isNotNull();
    assertThat(second).as("★ 新周期开新条（id 里的周期号不同）").isNotNull();
    assertThat(first.principal())
        .as("周期 1 的本金 = 10 天缺口 + 周期末计息 %d × 20‰", cycleOne)
        .isEqualTo(cycleOne + cycleOne * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L);
    assertThat(second.principal())
        .as("周期 2 的本金 = 第 11~13 天的缺口之和（尚未计息：周期 2 还没关账）")
        .isEqualTo(deficitOver(CYCLE_DAYS + 3L) - deficitOver(CYCLE_DAYS));
    assertThat(first.dueCycle()).as("周期 1 借的 ⇒ 到期周期 2").isEqualTo(2L);
    assertThat(second.dueCycle()).isEqualTo(3L);
  }

  /**
   * ★★ **id 的三条硬要求**（v2 spec §7.2）：确定性 / **不含 {@code "."}** / 跨周期不同。
   *
   * <p>★ 直接调 {@code debtIdOf}（包内可见的唯一拼写点）：同一元组两次调用**逐值相同**、换任一维度都不同。 不吃"再跑一遍结算对拍"的回环（那是同义反复）。
   */
  @Test
  void theDebtIdIsDeterministicCycleScopedAndFreeOfDots() {
    DebtId base = EconomySettlement.debtIdOf(1L, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN));

    assertThat(EconomySettlement.debtIdOf(1L, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN)))
        .as("确定性：同一 (周期, 债务人, 债权人, 商品) ⇒ 同一个 id")
        .isEqualTo(base);
    assertThat(base.value())
        .as("★ 不含 \".\"（debt.<id> 在 AddressParser 的**第一个点**处被切开）")
        .doesNotContain(".");
    assertThat(EconomySettlement.debtIdOf(2L, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN)))
        .as("跨周期必须不同（否则新周期会覆盖旧条）")
        .isNotEqualTo(base);
    assertThat(EconomySettlement.debtIdOf(1L, LANDLORD_KEY, PEASANT_KEY, Optional.of(GRAIN)))
        .as("债务人/债权人反过来 ⇒ 另一条债（方向是身份的一部分）")
        .isNotEqualTo(base);
    assertThat(
            EconomySettlement.debtIdOf(
                1L, PEASANT_KEY, LANDLORD_KEY, Optional.of(new CommodityId("timber"))))
        .as("商品不同 ⇒ 另一条债")
        .isNotEqualTo(base);
    assertThat(EconomySettlement.debtIdOf(1L, PEASANT_KEY, LANDLORD_KEY, Optional.empty()).value())
        .as("货币债（v1 不产生）走同一段位、不与之相撞")
        .isNotEqualTo(base.value());
  }

  /**
   * ★★ **确定性（重放可比）**：同一份输入结算两次 ⇒ **债务 id 集合与本金逐值相同**（含计息后的本金）。
   *
   * <p>★ 判别力与其**边界**（均实测）：它钉的是 {@code debtIdOf} **这个函数**的纯性 —— 实测变异"把结算里的调用点换成计数器式 id" （{@code
   * debt-<day>-<序号>}）时**本条仍绿**（函数没被改），那时红的是聚合/上界/端到端 id 三条。⇒ 调用点是否真的用它，由那三条守； 本条只守"函数本身确定性"。
   */
  @Test
  void theSameInputTwiceYieldsTheSameDebtIdsAndPrincipals() {
    EconomyData base = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);

    EconomyData once = EconomySettlement.settle(base, 0L, 2L * CYCLE_DAYS);
    EconomyData twice = EconomySettlement.settle(base, 0L, 2L * CYCLE_DAYS);

    assertThat(once.debts()).as("两次结算的债务表逐值相同（id 集合 + 本金）").isEqualTo(twice.debts());
    assertThat(once.debts().keySet()).as("id 集合").isEqualTo(twice.debts().keySet());
    assertThat(once.debts().values().stream().map(Debt::principal).sorted().toList())
        .as("本金逐值相同")
        .isEqualTo(twice.debts().values().stream().map(Debt::principal).sorted().toList());
    assertThat(once).as("★ 更强的形态：整份终态逐值相同（含流水）").isEqualTo(twice);
  }

  // ── ② §7.1① 放贷方留口粮 ──────────────────────────────────────────────────────────

  /**
   * ★★ **放贷方只贷余粮：{@code 可贷额 = 库存 − 本周期自需}**（v2 spec §7.1 第一处）。
   *
   * <pre>
   * 地主 10 人、周期 2 天：本周期自需 = cumulativeRationMilli(10, 2) = 1,666；缸 = 2,666
   * 第 1 天：先吃自己那一顿 dailyRationMilli(10, 1) = 833 ⇒ 1,833；可贷额 = 1,833 − 1,666 = **167** ⇒ 贷 167
   *     贫农缺口 dailyRationMilli(400, 1) = 33,333 ⇒ 贷到 167、欠 33,166
   * 第 2 天：地主吃 833 ⇒ 833；可贷额 = max(0, 833 − 1,666) = **0** ⇒ 不再贷
   * ⇒ 借出合计 = 167 = (2,666 − 833) − 1,666（**恰等于**"库存 − 自己那一顿 − 本周期自需"）
   * ⇒ 地主**缸里还剩 833**（= 它第 2 天那一顿），它自己**一天都没缺**（unmetNeed = 0）
   * </pre>
   *
   * <p>★ 判别力（变异轮实测）：把 {@link EconomySettlement#LENDER_SUBSISTENCE_RESERVE_PER_MILLE} 改回 0（= V1
   * 口径"消费后的全部库存都能借出"）⇒ 地主的 1,833 全被借走、它第 2 天自己缺 833 ⇒ **实测先在"缸里剩的恰是它第 2 天那一顿"那行红**
   * （同一变异下"地主一天都没缺"那条期望也**不成立** —— 用例在第一个断言处即止，故只报实测到的那一行）。
   */
  @Test
  void theLenderKeepsAWholeCyclesSubsistenceAndNeverGoesBankruptFirst() {
    long lenderJar = 2_666L;
    EconomyData next = EconomySettlement.settle(hex(2L, 0L, lenderJar), 0L, 2L);

    long lenderNeed = EconomyVocabulary.cumulativeRationMilli(10L, 2L);
    long lenderMealOnDayOne = EconomyVocabulary.dailyRationMilli(10L, 1L);
    assertThat(lenderNeed).as("地主本周期自需").isEqualTo(1_666L);
    assertThat(next.classes().get(LANDLORD_KEY).goods().get(GRAIN))
        .as("地主缸里剩的**恰是它第 2 天那一顿**（保留额没被贷出去）")
        .isEqualTo(lenderMealOnDayOne)
        .isEqualTo(833L);
    assertThat(next.flows().get(LANDLORD_KEY).unmetNeed().getOrDefault(GRAIN, 0L))
        .as("★ 地主一天都没缺（V1 的「地主先破产」不再发生）")
        .isZero();
    long lent = next.flows().get(PEASANT_KEY).newBorrowing();
    assertThat(lent)
        .as("借出合计 = 库存 − 自己那一顿 − 本周期自需（**逐值**，不是「不炸」）")
        .isEqualTo(lenderJar - lenderMealOnDayOne - lenderNeed)
        .isEqualTo(167L);
    assertThat(lent).as("★ 借出量 ≤ 库存 − 本周期自需").isLessThanOrEqualTo(lenderJar - lenderNeed);
    assertThat(next.flows().get(PEASANT_KEY).unmetNeed().getOrDefault(GRAIN, 0L))
        .as("贫农：总需求 − 借到的（缺口照记不误；★ R4 起 unmetNeed 逐商品，本条只说粮那一维）")
        .isEqualTo(deficitOver(2L) - lent);
  }

  // ── ③ §7.1③ 计息（并入本金 + 记 interestDue）────────────────────────────────────────

  /**
   * ★★ **周期末按 {@code principal × ratePerMillePerCycle ÷ 1000} 计一次、并入本金**，同额记入债务人本行流水的 {@code
   * interestDue}（向下取整；V6 明写"取整 = 整除"）。
   *
   * <pre>
   * 周期 1 末（第 10 天）：本金 333,333 ⇒ 利息 floor(333,333 × 20 ÷ 1000) = 6,666 ⇒ 本金 339,999
   * 周期 2 末（第 20 天）：① 周期 1 那条**再计一次**（复利）：floor(339,999 × 20 ÷ 1000) = 6,799 ⇒ 346,798
   *                    ② 周期 2 那条：本金 333,333 ⇒ +6,666 ⇒ 339,999
   *                    ③ 债务人本周期 interestDue = 6,666 + 6,799 = 13,465（两条都归它 ⇒ 合并入同一行流水）
   * </pre>
   *
   * <p>★ 判别力（变异轮实测）：①把"并入本金"改成"只记流水不动本金" ⇒ 本条先在"周期 1 的债在**两次**周期末各计一次"那行红。 ②"只在**新债**上计息"（拿 id
   * 里的周期号筛）**未做专属变异** —— 它是**推演**：那样周期 1 那条第二轮不再变 ⇒ 期望值 346,798 / 13,465 都不成立（值写死在这里，故这两条断言不是同义反复）。
   *
   * <p>★★ **本夹具只有一个产业，挡不住"计息挂进逐产业的关账分支"那一类错**（同一天关两个产业 ⇒ 每条债被计两遍）—— 那件事由 {@link
   * #interestIsChargedOncePerCycleEvenWhenSeveralIndustriesCloseTogether} 负责（两个产业、同一天关账、
   * 逐值断言只计一次）。★ 这条自检正是"判别力声明必须真的成立"要求的那一步：**没测过的不许写成挡得住**。
   */
  @Test
  void interestAccruesOncePerCycleAndIsCapitalisedIntoThePrincipal() {
    EconomyData next =
        EconomySettlement.settle(hex(CYCLE_DAYS, 0L, LANDLORD_JAR), 0L, 2L * CYCLE_DAYS);

    long perCycle = deficitOver(CYCLE_DAYS);
    long firstInterest = perCycle * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long firstAfterOne = perCycle + firstInterest;
    long secondInterest = firstAfterOne * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long secondCyclePrincipal = deficitOver(2L * CYCLE_DAYS) - deficitOver(CYCLE_DAYS);
    long secondInterestOwn =
        secondCyclePrincipal * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;

    Debt first =
        next.debts().get(new DebtId("debt-c1-farm@0_0|poor_peasant>farm@0_0|landlord-grain"));
    Debt second =
        next.debts().get(new DebtId("debt-c2-farm@0_0|poor_peasant>farm@0_0|landlord-grain"));
    assertThat(first.principal())
        .as("周期 1 的债在**两次**周期末各计一次（%d + %d + %d）", perCycle, firstInterest, secondInterest)
        .isEqualTo(firstAfterOne + secondInterest)
        .isEqualTo(346_798L);
    assertThat(second.principal())
        .as("周期 2 的债在周期 2 末计一次")
        .isEqualTo(secondCyclePrincipal + secondInterestOwn)
        .isEqualTo(339_999L);

    FlowRow peasantFlow = next.flows().get(PEASANT_KEY);
    assertThat(peasantFlow.interestDue())
        .as("★ 周期 2 的 interestDue = 两条债各自那一笔之和（同额记入**债务人**行）")
        .isEqualTo(secondInterestOwn + secondInterest)
        .isEqualTo(13_465L);
    assertThat(next.flows().get(LANDLORD_KEY).interestDue()).as("债权人行不记应付利息").isZero();
    assertThat(peasantFlow.netSurplus())
        .as("netSurplus = income(0) − 消费 − 利息（§3.3 的口径：并入本金的利息照样进赤字）")
        .isEqualTo(-(peasantFlow.consumed().get(GRAIN) + 13_465L));
    assertThat(next.debts().values().stream().mapToLong(Debt::principal).sum())
        .as("存量本金 = 两条之和（利息只进本金一次，没有影子字段）")
        .isEqualTo(346_798L + 339_999L);
  }

  /**
   * ★★ **一天里关账多个产业时，计息仍只计一次**（"计息挂在日级、不在逐产业的 for 里"这件事的判别力所在）。
   *
   * <pre>
   * 夹具：**两格**（两个产业、同一个 cycleDays = 10 ⇒ 同一天关账），每格各借出一个周期
   * 周期末：每格那条债各计一次 333,333 × 20‰ = 6,666 ⇒ 本金 339,999
   * ★ 变异轮实测：把计息挂进"逐产业的关账分支"（两个产业各跑一遍 ⇒ 每条债被计两次）⇒ 本条红（实得 346,798）。
   * </pre>
   */
  @Test
  void interestIsChargedOncePerCycleEvenWhenSeveralIndustriesCloseTogether() {
    EconomyData next = EconomySettlement.settle(multiHex(2, CYCLE_DAYS), 0L, CYCLE_DAYS);

    long perCycle = deficitOver(CYCLE_DAYS);
    long once = perCycle + perCycle * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long twice = once + once * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    assertThat(next.industries()).as("两个产业（两个格）确实在同一天关账").hasSize(2);
    assertThat(next.debts()).as("两条债（每格一条）").hasSize(2);
    assertThat(next.debts().values().stream().map(Debt::principal).toList())
        .as("★ 每条债只计一次息（计两遍会给 %d）", twice)
        .containsOnly(once)
        .doesNotContain(twice);
  }

  // ── ④ 上界：条数 ≤ 周期数 × 格数 × 债权人对数 ──────────────────────────────────────

  /**
   * ★★ **债务条数的上界**（v2 spec §7.2；brief 明令"写成断言，别只断言不炸"）：
   *
   * <pre>
   * 推 N 天（跨 M 个周期、H 格、每格 S 个槽位）⇒ debts ≤ M × H × S × (S − 1)
   * 本夹具：M = 3、H = 3、S = 2（贫农 + 地主）⇒ 上界 3 × 3 × 2 = 18
   * 实际：每格每周期**只有一对**债权债务人 ⇒ 3 × 3 × 1 = 9（逐值断言）
   * 旧口径（每天每对一条）：30 天 × 3 格 = **90** ⇒ 新条数必须**严格更小**（"不再随天数增长"的证据）
   * </pre>
   *
   * <p>★ 判别力（变异轮实测）：把聚合去掉（逐日新建）⇒ 实际条数从 9 变 90 ⇒ 本条先在**上界**那行红（90 > 18）⇒ 证明上界不是"不炸"式的空断言。
   */
  @Test
  void theDebtCountStaysBoundedByCyclesTimesHexesTimesCreditorPairs() {
    int hexes = 3;
    int cycles = 3;
    int days = (int) (cycles * CYCLE_DAYS);
    EconomyData base = multiHex(hexes, CYCLE_DAYS);

    EconomyData next = EconomySettlement.settle(base, 0L, days);

    int slots = 2; // 贫农 + 地主（本夹具的槽位数）
    int upperBound = cycles * hexes * slots * (slots - 1);
    assertThat(next.debts().size())
        .as("★ 上界：周期数 × 格数 × 债权人对数（%d）", upperBound)
        .isLessThanOrEqualTo(upperBound);
    assertThat(next.debts().size()).as("实际条数 = 每格每周期一对债权债务人").isEqualTo(cycles * hexes);
    assertThat(next.debts().size())
        .as("★ 旧口径（每天每对一条）会给 %d 条 ⇒ 聚合后必须严格更少", days * hexes)
        .isLessThan(days * hexes);
    assertThat(next.meta().orElseThrow().lastClosedCycle())
        .as("确实跨了 3 个周期（上界不是靠「没关账」达成的）")
        .hasValue(3L);
  }

  // ── ⑤ 守恒 + §十一 等价性（债务/计息都不得破坏它们）──────────────────────────────────

  /**
   * ★★ **借入是同格内部划转 ⇒ 全格粮总量守恒**：{@code Σ库存减少 == Σ流水消费 − Σ流水所得}（逐值恒等）。
   *
   * <p>★ **口径说清**：这条**只在格/全局上成立**，**逐行不成立** —— 放贷行出去的那一笔进的是**债务表**（债权）， 不是它的 {@code consumed}；而借入行的
   * {@code consumed} 把借到的那笔记足了（当日即被吃掉）。两处刚好抵消。
   *
   * <p>★ **窗口取一个周期**（{@code 0 → CYCLE_DAYS}）：流水是**本期**口径（§八.5，新周期第一天归零），跨周期的 库存差与单周期的流水对比会差出一整个周期
   * —— 那不是守恒式不成立，是窗口取错了。
   *
   * <p>★ 判别力（变异轮实测）：把借入量从缺口行的 {@code consumed} 里去掉 ⇒ 本条在守恒式那行红（差额比出 333,333）。
   */
  @Test
  void lendingIsAnInternalTransferSoTheHexLedgerStillBalances() {
    EconomyData base = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    EconomyData next = EconomySettlement.settle(base, 0L, CYCLE_DAYS);

    long stockBefore = grainTotal(base);
    long stockAfter = grainTotal(next);
    long consumed =
        next.flows().values().stream()
            .mapToLong(flow -> flow.consumed().getOrDefault(GRAIN, 0L))
            .sum();
    // ★ R3：income 是**逐商品**的表 ⇒ 守恒式也逐商品成立（本文件只结粮，故取 grain 那一维）。
    long income =
        next.flows().values().stream()
            .mapToLong(flow -> flow.income().getOrDefault(GRAIN, 0L))
            .sum();
    assertThat(next.flows().get(PEASANT_KEY).newBorrowing())
        .as("（非平凡：这场确实发生了借入）")
        .isEqualTo(deficitOver(CYCLE_DAYS));
    assertThat(stockBefore - stockAfter)
        .as("Σ库存减少 == Σ消费 − Σ所得（借入这一笔在两边各出现一次）")
        .isEqualTo(consumed - income);
  }

  /**
   * ★★ **§十一 等价性**：一次推 20 天 == 20 次单日推 ⇒ 终态**逐值相同**（债务表、本金、流水里的 interestDue 全在内）。
   *
   * <p>★ 判别力：本节是 **§十一 等价性**护栏（终态逐值，含债务表与流水）—— "一次 20 天 == 20 次单日"若不成立（例：计息
   * 按**推进次数**而不是按**关账日**算）这里就是红的。★ 其中"计息确实发生了"那一项由最后那条本金断言承担：**实测**（不并入本金 ⇒ 红，见 {@link
   * #interestAccruesOncePerCycleAndIsCapitalisedIntoThePrincipal}）；"按推进次数计息"本身**未做专属变异**。
   */
  @Test
  void twentyDaysAtOnceEqualsTwentyDailyStepsWithDebtsAndInterest() {
    EconomyData base = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);

    EconomyData once = EconomySettlement.settle(base, 0L, 2L * CYCLE_DAYS);
    EconomyData chained = base;
    for (long day = 1L; day <= 2L * CYCLE_DAYS; day++) {
      chained = EconomySettlement.settle(chained, day - 1L, day);
    }

    assertThat(once).as("§十一：一次 20 天 == 20 次单日（终态逐值，含债务表）").isEqualTo(chained);
    assertThat(once.flows()).as("流水逐值相同（含 interestDue）").isEqualTo(chained.flows());
    assertThat(once.debts().values().stream().mapToLong(Debt::principal).sum())
        .as("计息只发生两次（第 10、20 天），不是 20 次")
        .isEqualTo(346_798L + 339_999L);
  }

  /**
   * ★★ **放贷序列的每一档都必须真的放得出贷**（S1 阶段 1 补的判别力）。
   *
   * <p>★★ **它补的是什么**：{@code LENDER_SLOT_PRIORITY} 原先是裸词 {@code List.of("landlord","rich","middle")}
   * —— 词表换成 {@code rich_peasant}/{@code middle_peasant} 之后后两档**静默匹配不上**，债权序列退化成"只有地主"。
   * 本文件的债务夹具**只有贫农+地主两行**（放贷那行写死地主）⇒ 全绿；而 {@code middle_peasant} 这一档 **整仓没有任何夹具**用它做债权人。
   *
   * <p>★ 判别力（变异自证）：把 {@code LENDER_SLOT_PRIORITY} 里任一档改回旧词 ⇒ 那一轮 `hasSize(1)` 当场红 （`lenders` 为空 ⇒
   * 一条债都不建）。
   */
  @Test
  void eachLenderStratumInThePriorityListCanLend() {
    List<SocialClassId> strata =
        List.of(SocialClassId.LANDLORD, SocialClassId.RICH_PEASANT, SocialClassId.MIDDLE_PEASANT);

    for (SocialClassId lenderStratum : strata) {
      EconomyData next = EconomySettlement.settle(hexWithOnlyLender(lenderStratum), 0L, 1L);
      ClassKey lenderKey = new ClassKey(FARM, lenderStratum);

      assertThat(next.debts()).as("★ %s 必须真的放得出贷（匹配不上 ⇒ 这里一条债都没有）", lenderStratum).hasSize(1);
      Debt only = next.debts().values().iterator().next();
      assertThat(only.debtor()).as("%s 放贷时的债务人仍是贫农", lenderStratum).isEqualTo(PEASANT_KEY);
      assertThat(only.creditor()).as("★ 债权人 = %s 那一行", lenderStratum).isEqualTo(lenderKey);
      assertThat(only.principal())
          .as("本金 = 第 1 天缺口（%s 缸厚，够全额）", lenderStratum)
          .isEqualTo(deficitOn(1L));
    }
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  /** 一格两行、**放贷那行的阶层可指定**（S1 阶段 1 新增：放贷序列有三档，原夹具写死地主 ⇒ 只覆盖得到一档）。 */
  private static EconomyData hexWithOnlyLender(SocialClassId lenderStratum) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    addHex(industries, classes, FARM_KIND, 0, 0, CYCLE_DAYS, 0L, LANDLORD_JAR, lenderStratum);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyData(
        Optional.of(meta), industries, classes, Map.of(), Map.of(), Map.of(), Map.of());
  }

  /** 一格两行（贫农缸空 / 地主 {@code landlordJar}）、**不产粮**的产业。 */
  private static EconomyData hex(long cycleDays, long peasantJar, long landlordJar) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    addHex(industries, classes, FARM_KIND, 0, 0, cycleDays, peasantJar, landlordJar);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★ R2：本文件的产业**不产粮**（{@code outputPerUnit} 为空）⇒ 劳动投入的多少不改变任何一个字面量
    //   （收获恒 0、瓶颈无从谈起）⇒ 配额表留空即可。★ 空配额是**合法状态**（劳动是分配来的：没人发配额 = 没人上山干活），
    //   不是"兜底"——本文件断言的债务与利息与劳动无关。
    return new EconomyData(
        Optional.of(meta), industries, classes, Map.of(), Map.of(), Map.of(), Map.of());
  }

  /** {@code hexes} 格（同一形态：贫农缸空、地主缸厚）—— 供上界用例。 */
  private static EconomyData multiHex(int hexes, long cycleDays) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    for (int q = 0; q < hexes; q++) {
      addHex(industries, classes, FARM_KIND, q, 0, cycleDays, 0L, LANDLORD_JAR);
    }
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyData(
        Optional.of(meta), industries, classes, Map.of(), Map.of(), Map.of(), Map.of());
  }

  /**
   * 造一格：产业（两槽位、{@code Split(700, 300)}、**亩产为空** ⇒ 收获恒 0）+ 贫农行 + 地主行。
   *
   * <p>★ **亩产为空是刻意的**：本文件的字面量只谈债务与利息，一旦产粮，缺口里就掺进收获那一笔（周期末贫农有粮了 ⇒
   * 下个周期不再借），债务断言会变得又长又脆。**债务与收获无关**，夹具就把收获关掉。
   */
  private static void addHex(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      String kind,
      int q,
      int r,
      long cycleDays,
      long peasantJar,
      long landlordJar) {
    addHex(industries, classes, kind, q, r, cycleDays, peasantJar, landlordJar, LANDLORD);
  }

  /** 同 {@link #addHex(Map, Map, String, int, int, long, long, long)}，但**放贷那行的阶层可指定**。 */
  private static void addHex(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      String kind,
      int q,
      int r,
      long cycleDays,
      long peasantJar,
      long landlordJar,
      SocialClassId lenderStratum) {
    IndustryId id = IndustryHexKeys.id(kind, q, r);
    industries.put(
        id,
        new Industry(
            id,
            "农业",
            new RegimeId("feudal"),
            cycleDays,
            0L,
            // ★ R3：产能锚（规模单位 = 1 亩）与劳动那一路照常给；产出为空 ⇒ 不产粮（理由见上）。
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(), // ★ 不产粮（理由见上）
            Map.of(),
            // ★ 展示名直接用阶层自身的值（三档轮着用时，"地主"这种写死的名字会变成假标签）。
            List.of(
                new ClassSlot(PEASANT, "贫农", 1000),
                new ClassSlot(lenderStratum, lenderStratum.value(), 1000)),
            new AllocationRule.Split(700, 300),
            0L,
            Map.of()));
    classes.put(
        new ClassKey(id, PEASANT),
        row(new ClassKey(id, PEASANT), PEASANT_POPULATION, 1000, peasantJar));
    classes.put(
        new ClassKey(id, lenderStratum), row(new ClassKey(id, lenderStratum), 10L, 0, landlordJar));
  }

  private static ClassRow row(
      ClassKey key, long population, int participationPerMille, long grainStock) {
    return new ClassRow(
        key,
        population,
        population * LABOR_PER_PERSON,
        participationPerMille,
        Map.of(AssetKind.LAND, 0L),
        grainStock > 0L ? Map.of(GRAIN, grainStock) : Map.of(),
        0L,
        new ArrayList<>(),
        Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(population, 1L)),
        Map.of());
  }

  private static long grainTotal(EconomyData data) {
    return data.classes().values().stream()
        .mapToLong(row -> row.goods().getOrDefault(GRAIN, 0L))
        .sum();
  }
}
