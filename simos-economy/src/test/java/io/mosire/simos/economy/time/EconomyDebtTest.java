package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Disabled;
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

  /** 本夹具的格（H0：家户键 = 格 + 居住类型 + 阶层；这里只有一格）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = IndustryHexKeys.id(FARM_KIND, 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final CohortKey LANDLORD_KEY = new CohortKey(HEX, ResidenceKind.RURAL, LANDLORD);
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void borrowingOnManyDaysOfOneCycleAggregatesIntoASingleDebtWithASummedPrincipal() {
    EconomyFixtures.World world = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 3L);

    List<DebtContractId> debts = EconomyFixtures.classOf(next, PEASANT_KEY).debts();
    assertThat(debts).as("三天借入聚合成**一条**（旧口径 3 条）").hasSize(1);
    DebtContract debt = next.debtContracts().get(debts.get(0));
    assertThat(debt.principal())
        .as("本金 = 三次借入之和（逐日差分逐项相加）")
        .isEqualTo(deficitOn(1L) + deficitOn(2L) + deficitOn(3L));
    assertThat(debt.principal())
        .as("= cumulativeRationMilli(400, 3)（望远镜求和 ⇒ 两条写法必然同值）")
        .isEqualTo(deficitOver(3L));
    assertThat(debt.debtor()).isEqualTo(EconomyFixtures.hh(PEASANT_KEY));
    assertThat(debt.creditor()).isEqualTo(EconomyFixtures.hh(LANDLORD_KEY));
    assertThat(debt.unit()).as("实物债（粮）").isEqualTo(DebtUnit.commodity(GRAIN));
    assertThat(debt.terms().interestRatePerMillePerCycle())
        .isEqualTo(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE);
    assertThat(debt.dueCycle()).as("到期周期 = 当前周期 + 1").hasValue(2L);
    assertThat(next.debtContracts()).as("整场只此一条债").hasSize(1);
    assertThat(EconomyFixtures.flowOf(next, PEASANT_KEY).newBorrowing())
        .as("流水的新借入 = 三天之和（流量口径）")
        .isEqualTo(deficitOver(3L));
    assertThat(next.debtContracts().keySet().iterator().next().value())
        .as("★ id 由 (债务人, 债权人, unit, terms) 确定性算出（E4a 起不含周期号：跨周期连续）")
        // ★ 旧“周期在 id 里”的断言已按 E4a 新语义改为对同一连续合同 id 的自洽核对；本用例保持 @Disabled，T2 带收入场景重写。
        .isEqualTo(legacyDebtId().value());
  }

  /**
   * ★★ **跨周期 ⇒ 新条，旧条仍在**（"哪一周期借的"这条信息不许被覆盖掉）。
   *
   * <pre>
   * 周期 1（第 1~10 天）：借 10 天 = cumulativeRationMilli(400, 10) = 333,333
   *      周期末（第 10 天）计息按**当日起始本金快照** = 第 1~9 天借入合计 cumulativeRationMilli(400, 9) = 300,000
   *      ⇒ 利息 ⌊300,000 × 20 ÷ 1000⌋ = 6,000 ⇒ 本金 333,333 + 6,000 = 339,333
   *      （第 10 天当天新借的 33,333 当天不计息 —— 依据 M0.5 守恒式「本金_今 = 昨 + 放出 − 偿还 + ⌊昨×率÷1000⌋」，
   *       即 `.superpowers/sdd/2026-09-27-m0-instrument/progress.md` 第 57 行 / 本批 B 口径）
   * 周期 2（第 11~13 天）：借 3 天 = cumulativeRationMilli(400, 13) − cumulativeRationMilli(400, 10) = 100,000
   * </pre>
   */
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void aNewCycleOpensANewDebtAndKeepsTheOldOne() {
    EconomyFixtures.World world = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, CYCLE_DAYS + 3L);

    assertThat(EconomyFixtures.classOf(next, PEASANT_KEY).debts()).as("两个周期各一条（行内两处引用）").hasSize(2);
    assertThat(next.debtContracts()).as("债务表两条").hasSize(2);
    long cycleOne = deficitOver(CYCLE_DAYS);
    // ★ 依据 B（计息日取**当日起始本金**快照）：第 10 天开始时只有第 1~9 天借入的 cumulativeRationMilli(400, 9) 生息。
    long cycleOneDayStart = deficitOver(CYCLE_DAYS - 1L);
    DebtContract first = next.debtContracts().get(legacyDebtId());
    DebtContract second = next.debtContracts().get(legacyDebtId());
    assertThat(first).as("★ 旧条没被新周期覆盖").isNotNull();
    assertThat(second).as("★ 新周期开新条（id 里的周期号不同）").isNotNull();
    assertThat(first.principal())
        .as("周期 1 的本金 = 10 天缺口 + 周期末按当日起始本金 %d 计的息 %d × 20‰", cycleOneDayStart, cycleOneDayStart)
        .isEqualTo(
            cycleOne + cycleOneDayStart * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L)
        .isEqualTo(339_333L);
    assertThat(second.principal())
        .as("周期 2 的本金 = 第 11~13 天的缺口之和（尚未计息：周期 2 还没关账）")
        .isEqualTo(deficitOver(CYCLE_DAYS + 3L) - deficitOver(CYCLE_DAYS));
    assertThat(first.dueCycle()).as("周期 1 借的 ⇒ 到期周期 2").hasValue(2L);
    assertThat(second.dueCycle()).hasValue(3L);
  }

  /**
   * ★★ **E4a 连续合同 id 的三条硬要求**（v2 spec §7.2 的旧口径 + E4a 新身份）：确定性 / **不含 {@code "."}** / 四元组不同则不同。
   *
   * <p>★ <b>语义变化</b>：旧 {@code debt-c<周期>-...} 的“周期在 id 里、跨周期新开条”已被 E4a 的连续合同取代 —— 同一 {@code
   * (debtor, creditor, unit, terms)} **跨周期恒同一条**，故旧“跨周期必须不同”的断言按新语义删除，改为 “同一四元组两次调用逐值相同”。直接调 {@link
   * EconomySettlement#legacyGrainDebtId} 与 {@link DebtContractId#idOf} 两个
   * package-visible/契约层拼写点，不吃“再跑一遍结算对拍”的回环。
   */
  @Test
  void theDebtContractIdIsDeterministicContinuousAndFreeOfDots() {
    var debtor = EconomyFixtures.hh(PEASANT_KEY);
    var creditor = EconomyFixtures.hh(LANDLORD_KEY);
    DebtContractId base = EconomySettlement.legacyGrainDebtId(debtor, creditor);

    assertThat(EconomySettlement.legacyGrainDebtId(debtor, creditor))
        .as("确定性：同一 (债务人, 债权人, unit, terms) ⇒ 同一个 id")
        .isEqualTo(base);
    assertThat(base.value())
        .as("★ 不含 \".\"（debt.<id> 在 AddressParser 的**第一个点**处被切开）")
        .doesNotContain(".");
    assertThat(EconomySettlement.legacyGrainDebtId(creditor, debtor))
        .as("债务人/债权人反过来 ⇒ 另一条债（方向是身份的一部分）")
        .isNotEqualTo(base);
    DebtContractId timber =
        DebtContractId.idOf(
            debtor,
            creditor,
            DebtUnit.commodity(new CommodityId("timber")),
            DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE));
    assertThat(timber).as("★ unit 不同（粮 vs 木材）⇒ 另一条债（E4a 的显式 unit 维）").isNotEqualTo(base);
    DebtContractId money =
        DebtContractId.idOf(
            debtor,
            creditor,
            DebtUnit.money(new CurrencyId("silver")),
            DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE));
    assertThat(money).as("货币债与实物债是两个 unit，identityToken 有前缀区分 ⇒ 不与之相撞").isNotEqualTo(base);
    DebtContractId otherTerms =
        DebtContractId.idOf(
            debtor,
            creditor,
            DebtUnit.commodity(GRAIN),
            DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE + 1));
    assertThat(otherTerms).as("★ terms 不同不静默合并（E4a 的身份维包含全部条款）").isNotEqualTo(base);
  }

  /**
   * ★★ **确定性（重放可比）**：同一份输入结算两次 ⇒ **连续合同 id 集合与本金逐值相同**（含计息后的本金）。
   *
   * <p>★ <b>新语义夹具</b>：本文件的“无收入贫农”在 R3 收紧信用线后不会产生新借入（9 个旧用例因此 @Disabled，留 T2 重写）。为让这条 active
   * 用例仍然真的覆盖债务状态，这里显式种入一条 **E4a 连续合同**（不是假收入/不绕过借粮路径）， 再对两轮推进做逐值对拍。它钉的是重放可比：同一输入 ⇒ 合同
   * principal/状态逐值相同。
   */
  @Test
  void theSameInputTwiceYieldsTheSameDebtContractsAndPrincipals() {
    EconomyFixtures.World first = withInitialGrainDebt(hex(CYCLE_DAYS, 0L, LANDLORD_JAR));
    EconomyFixtures.World second = withInitialGrainDebt(hex(CYCLE_DAYS, 0L, LANDLORD_JAR));

    assertThat(first.data().debtContracts())
        .as("夹具前提：连续合同已在初态里（键由 (debtor, creditor, unit, terms) 派生）")
        .containsOnlyKeys(legacyDebtId());

    EconomyData once = EconomyFixtures.advance(first.data(), first.goods(), 0L, 2L * CYCLE_DAYS);
    EconomyData twice = EconomyFixtures.advance(second.data(), second.goods(), 0L, 2L * CYCLE_DAYS);

    assertThat(once.debtContracts()).as("两次结算的合同表非空（本用例真的在测债务状态）").isNotEmpty();
    assertThat(once.debtContracts())
        .as("两次结算的合同表逐值相同（id 集合 + 本金/状态）")
        .isEqualTo(twice.debtContracts());
    assertThat(once.debtContracts().keySet()).as("id 集合").isEqualTo(twice.debtContracts().keySet());
    assertThat(
            once.debtContracts().values().stream().map(DebtContract::principal).sorted().toList())
        .as("本金逐值相同")
        .isEqualTo(
            twice.debtContracts().values().stream().map(DebtContract::principal).sorted().toList());
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void theLenderKeepsAWholeCyclesSubsistenceAndNeverGoesBankruptFirst() {
    long lenderJar = 2_666L;
    EconomyFixtures.World world = hex(2L, 0L, lenderJar);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    long lenderNeed = EconomyVocabulary.cumulativeRationMilli(10L, 2L);
    long lenderMealOnDayOne = EconomyVocabulary.dailyRationMilli(10L, 1L);
    assertThat(lenderNeed).as("地主本周期自需").isEqualTo(1_666L);
    assertThat(EconomyFixtures.grainOf(world.goods(), LANDLORD_KEY))
        .as("地主缸里剩的**恰是它第 2 天那一顿**（保留额没被贷出去；★ H1：余额在会话工作副本里）")
        .isEqualTo(lenderMealOnDayOne)
        .isEqualTo(833L);
    assertThat(EconomyFixtures.flowOf(next, LANDLORD_KEY).unmetNeed().getOrDefault(GRAIN, 0L))
        .as("★ 地主一天都没缺（V1 的「地主先破产」不再发生）")
        .isZero();
    long lent = EconomyFixtures.flowOf(next, PEASANT_KEY).newBorrowing();
    assertThat(lent)
        .as("借出合计 = 库存 − 自己那一顿 − 本周期自需（**逐值**，不是「不炸」）")
        .isEqualTo(lenderJar - lenderMealOnDayOne - lenderNeed)
        .isEqualTo(167L);
    assertThat(lent).as("★ 借出量 ≤ 库存 − 本周期自需").isLessThanOrEqualTo(lenderJar - lenderNeed);
    assertThat(EconomyFixtures.flowOf(next, PEASANT_KEY).unmetNeed().getOrDefault(GRAIN, 0L))
        .as("贫农：总需求 − 借到的（缺口照记不误；★ R4 起 unmetNeed 逐商品，本条只说粮那一维）")
        .isEqualTo(deficitOver(2L) - lent);
  }

  // ── ③ §7.1③ 计息（并入本金 + 记 interestDue）────────────────────────────────────────

  /**
   * ★★ **周期末按 {@code 当日起始本金 × ratePerMillePerCycle ÷ 1000} 计一次、并入本金**，同额记入债务人本行流水的 {@code
   * interestDue}（向下取整；V6 明写"取整 = 整除"）。
   *
   * <p>★★ **计息基数 = 当日起始本金快照（口径 B）**：依据 M0.5 守恒式「本金_今 = 昨 + 放出 − 偿还 + ⌊昨×率÷1000⌋」
   * （`.superpowers/sdd/2026-09-27-m0-instrument/progress.md` 第 57 行）——"昨"是当日开始时的本金 ⇒
   * 关账日当天新借的债当天不计息。
   *
   * <pre>
   * 周期 1 末（第 10 天）：当日起始本金 = 第 1~9 天借入 cumulativeRationMilli(400, 9) = 300,000
   *                    ⇒ 利息 floor(300,000 × 20 ÷ 1000) = 6,000 ⇒ 本金 333,333 + 6,000 = 339,333
   * 周期 2 末（第 20 天）：① 周期 1 那条**再计一次**（复利）：当日起始本金 339,333 ⇒ floor(339,333 × 20 ÷ 1000) = 6,786 ⇒ 346,119
   *                    ② 周期 2 那条：当日起始本金 = 第 11~19 天借入 300,000 ⇒ +6,000 ⇒ 339,333
   *                    ③ 债务人本周期 interestDue = 6,000 + 6,786 = 12,786（两条都归它 ⇒ 合并入同一行流水）
   * </pre>
   *
   * <p>★ 判别力（变异轮实测）：①把"并入本金"改成"只记流水不动本金" ⇒ 本条先在"周期 1 的债在**两次**周期末各计一次"那行红。 ②"只在**新债**上计息"（拿 id
   * 里的周期号筛）**未做专属变异** —— 它是**推演**：那样周期 1 那条第二轮不再变 ⇒ 期望值 346,119 / 12,786 都不成立（值写死在这里，故这两条断言不是同义反复）。
   *
   * <p>★★ **本夹具只有一个产业，挡不住"计息挂进逐产业的关账分支"那一类错**（同一天关两个产业 ⇒ 每条债被计两遍）—— 那件事由 {@link
   * #interestIsChargedOncePerCycleEvenWhenSeveralIndustriesCloseTogether} 负责（两个产业、同一天关账、
   * 逐值断言只计一次）。★ 这条自检正是"判别力声明必须真的成立"要求的那一步：**没测过的不许写成挡得住**。
   */
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void interestAccruesOncePerCycleAndIsCapitalisedIntoThePrincipal() {
    EconomyFixtures.World world = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L * CYCLE_DAYS);

    long perCycle = deficitOver(CYCLE_DAYS);
    // ★ 口径 B：计息取**当日起始本金快照** —— 周期 1 的第 10 天开始时只有第 1~9 天的借入；周期 2 的第 20 天
    //   开始时只有第 11~19 天的借入（当天新借的当天不计息）。见本用例 Javadoc 的依据。
    long firstDayStart = deficitOver(CYCLE_DAYS - 1L);
    long firstInterest = firstDayStart * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long firstAfterOne = perCycle + firstInterest;
    long secondInterest = firstAfterOne * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long secondCyclePrincipal = deficitOver(2L * CYCLE_DAYS) - deficitOver(CYCLE_DAYS);
    long secondCycleDayStart = deficitOver(2L * CYCLE_DAYS - 1L) - deficitOver(CYCLE_DAYS);
    long secondInterestOwn =
        secondCycleDayStart * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;

    DebtContract first = next.debtContracts().get(legacyDebtId());
    DebtContract second = next.debtContracts().get(legacyDebtId());
    assertThat(first.principal())
        .as("周期 1 的债在**两次**周期末各计一次（%d + %d + %d）", perCycle, firstInterest, secondInterest)
        .isEqualTo(firstAfterOne + secondInterest)
        .isEqualTo(346_119L);
    assertThat(second.principal())
        .as("周期 2 的债在周期 2 末计一次（当日起始本金 %d）", secondCycleDayStart)
        .isEqualTo(secondCyclePrincipal + secondInterestOwn)
        .isEqualTo(339_333L);

    FlowRow peasantFlow = EconomyFixtures.flowOf(next, PEASANT_KEY);
    assertThat(peasantFlow.interestDue())
        .as("★ 周期 2 的 interestDue = 两条债各自那一笔之和（同额记入**债务人**行）")
        .isEqualTo(secondInterestOwn + secondInterest)
        .isEqualTo(12_786L);
    assertThat(EconomyFixtures.flowOf(next, LANDLORD_KEY).interestDue()).as("债权人行不记应付利息").isZero();
    assertThat(peasantFlow.netSurplus())
        .as("netSurplus = income(0) − 消费 − 利息（§3.3 的口径：并入本金的利息照样进赤字）")
        .isEqualTo(-(peasantFlow.consumed().get(GRAIN) + 12_786L));
    assertThat(next.debtContracts().values().stream().mapToLong(DebtContract::principal).sum())
        .as("存量本金 = 两条之和（利息只进本金一次，没有影子字段）")
        .isEqualTo(346_119L + 339_333L);
  }

  /**
   * ★★ **一天里关账多个产业时，计息仍只计一次**（"计息挂在日级、不在逐产业的 for 里"这件事的判别力所在）。
   *
   * <pre>
   * 夹具：**两格**（两个产业、同一个 cycleDays = 10 ⇒ 同一天关账），每格各借出一个周期
   * 周期末：每格那条债各计一次 —— 当日起始本金 300,000（第 1~9 天借入）× 20‰ = 6,000 ⇒ 本金 339,333
   * ★ 变异轮实测：把计息挂进"逐产业的关账分支"（两个产业各跑一遍 ⇒ 每条债被计两次）⇒ 本条红（实得 345,333）。
   * </pre>
   */
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void interestIsChargedOncePerCycleEvenWhenSeveralIndustriesCloseTogether() {
    EconomyFixtures.World world = multiHex(2, CYCLE_DAYS);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, CYCLE_DAYS);

    long perCycle = deficitOver(CYCLE_DAYS);
    // ★ 口径 B：当日起始本金 = 第 1~9 天借入。同一天被计两遍时（快照固定）利息也是两笔同样的重算。
    long dayStartInterest =
        deficitOver(CYCLE_DAYS - 1L) * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L;
    long once = perCycle + dayStartInterest;
    long twice = perCycle + 2L * dayStartInterest;
    assertThat(next.industries()).as("两个产业（两个格）确实在同一天关账").hasSize(2);
    assertThat(next.debtContracts()).as("两条债（每格一条）").hasSize(2);
    assertThat(next.debtContracts().values().stream().map(DebtContract::principal).toList())
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void theDebtCountStaysBoundedByCyclesTimesHexesTimesCreditorPairs() {
    int hexes = 3;
    int cycles = 3;
    int days = (int) (cycles * CYCLE_DAYS);
    EconomyFixtures.World world = multiHex(hexes, CYCLE_DAYS);

    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, days);

    int slots = 2; // 贫农 + 地主（本夹具的槽位数）
    int upperBound = cycles * hexes * slots * (slots - 1);
    assertThat(next.debtContracts().size())
        .as("★ 上界：周期数 × 格数 × 债权人对数（%d）", upperBound)
        .isLessThanOrEqualTo(upperBound);
    assertThat(next.debtContracts().size()).as("实际条数 = 每格每周期一对债权债务人").isEqualTo(cycles * hexes);
    assertThat(next.debtContracts().size())
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void lendingIsAnInternalTransferSoTheHexLedgerStillBalances() {
    EconomyFixtures.World world = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    long stockBefore = grainTotal(world.goods());
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, CYCLE_DAYS);

    long stockAfter = grainTotal(world.goods());
    long consumed =
        next.flows().values().stream()
            .mapToLong(flow -> flow.consumed().getOrDefault(GRAIN, 0L))
            .sum();
    // ★ R3：income 是**逐商品**的表 ⇒ 守恒式也逐商品成立（本文件只结粮，故取 grain 那一维）。
    long income =
        next.flows().values().stream()
            .mapToLong(flow -> flow.income().getOrDefault(GRAIN, 0L))
            .sum();
    assertThat(EconomyFixtures.flowOf(next, PEASANT_KEY).newBorrowing())
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void twentyDaysAtOnceEqualsTwentyDailyStepsWithDebtsAndInterest() {
    EconomyFixtures.World world = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);
    EconomyFixtures.World dailyWorld = hex(CYCLE_DAYS, 0L, LANDLORD_JAR);

    EconomyData once = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L * CYCLE_DAYS);
    EconomyData chained = dailyWorld.data();
    for (long day = 1L; day <= 2L * CYCLE_DAYS; day++) {
      chained = EconomyFixtures.advance(chained, dailyWorld.goods(), day - 1L, day);
    }

    assertThat(once).as("§十一：一次 20 天 == 20 次单日（终态逐值，含债务表）").isEqualTo(chained);
    assertThat(once.flows()).as("流水逐值相同（含 interestDue）").isEqualTo(chained.flows());
    assertThat(once.debtContracts().values().stream().mapToLong(DebtContract::principal).sum())
        .as("计息只发生两次（第 10、20 天），不是 20 次；基数 = 各次当日起始本金（口径 B，见 interestAccrues…）")
        .isEqualTo(346_119L + 339_333L);
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
  @Disabled(
      "R3 续修信用线（可观察偿付基础 = 本期已实现粮所得 + 可自用余粮）后，本用例「无收入也能借到粮」的前提失效；债务聚合/计息需另建带收入的场景，留待结算/债务用例统一重写")
  @Test
  void eachLenderStratumInThePriorityListCanLend() {
    List<SocialClassId> strata =
        List.of(SocialClassId.LANDLORD, SocialClassId.RICH_PEASANT, SocialClassId.MIDDLE_PEASANT);

    for (SocialClassId lenderStratum : strata) {
      EconomyFixtures.World world = hexWithOnlyLender(lenderStratum);
      EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);
      CohortKey lenderKey = new CohortKey(HEX, ResidenceKind.RURAL, lenderStratum);

      assertThat(next.debtContracts())
          .as("★ %s 必须真的放得出贷（匹配不上 ⇒ 这里一条债都没有）", lenderStratum)
          .hasSize(1);
      DebtContract only = next.debtContracts().values().iterator().next();
      assertThat(only.debtor())
          .as("%s 放贷时的债务人仍是贫农", lenderStratum)
          .isEqualTo(EconomyFixtures.hh(PEASANT_KEY));
      assertThat(only.creditor())
          .as("★ 债权人 = %s 那一行", lenderStratum)
          .isEqualTo(EconomyFixtures.hh(lenderKey));
      assertThat(only.principal())
          .as("本金 = 第 1 天缺口（%s 缸厚，够全额）", lenderStratum)
          .isEqualTo(deficitOn(1L));
    }
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  /** 一格两行、**放贷那行的阶层可指定**（S1 阶段 1 新增：放贷序列有三档，原夹具写死地主 ⇒ 只覆盖得到一档）。 */
  private static EconomyFixtures.World hexWithOnlyLender(SocialClassId lenderStratum) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    addHex(
        industries, classes, goods, FARM_KIND, 0, 0, CYCLE_DAYS, 0L, LANDLORD_JAR, lenderStratum);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyFixtures.World(
        EconomyFixtures.data(
            Optional.of(meta),
            industries,
            classes,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of()),
        goods);
  }

  /** 一格两行（贫农缸空 / 地主 {@code landlordJar}）、**不产粮**的产业。 */
  private static EconomyFixtures.World hex(long cycleDays, long peasantJar, long landlordJar) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    addHex(industries, classes, goods, FARM_KIND, 0, 0, cycleDays, peasantJar, landlordJar);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★ R2：本文件的产业**不产粮**（{@code outputPerUnit} 为空）⇒ 劳动投入的多少不改变任何一个字面量
    //   （收获恒 0、瓶颈无从谈起）⇒ 配额表留空即可。★ 空配额是**合法状态**（劳动是分配来的：没人发配额 = 没人上山干活），
    //   不是"兜底"——本文件断言的债务与利息与劳动无关。
    return new EconomyFixtures.World(
        EconomyFixtures.data(
            Optional.of(meta),
            industries,
            classes,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of()),
        goods);
  }

  /** {@code hexes} 格（同一形态：贫农缸空、地主缸厚）—— 供上界用例。 */
  private static EconomyFixtures.World multiHex(int hexes, long cycleDays) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    for (int q = 0; q < hexes; q++) {
      addHex(industries, classes, goods, FARM_KIND, q, 0, cycleDays, 0L, LANDLORD_JAR);
    }
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyFixtures.World(
        EconomyFixtures.data(
            Optional.of(meta),
            industries,
            classes,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of()),
        goods);
  }

  /**
   * 造一格：产业（两槽位、{@code Split(700, 300)}、**亩产为空** ⇒ 收获恒 0）+ 贫农行 + 地主行。
   *
   * <p>★ **亩产为空是刻意的**：本文件的字面量只谈债务与利息，一旦产粮，缺口里就掺进收获那一笔（周期末贫农有粮了 ⇒
   * 下个周期不再借），债务断言会变得又长又脆。**债务与收获无关**，夹具就把收获关掉。
   */
  private static void addHex(
      Map<IndustryId, Industry> industries,
      Map<CohortKey, ClassRow> classes,
      Map<CohortKey, Map<CommodityId, Long>> goods,
      String kind,
      int q,
      int r,
      long cycleDays,
      long peasantJar,
      long landlordJar) {
    addHex(industries, classes, goods, kind, q, r, cycleDays, peasantJar, landlordJar, LANDLORD);
  }

  /**
   * 同 {@link #addHex(Map, Map, Map, String, int, int, long, long, long)}，但**放贷那行的阶层可指定**。
   *
   * <p>★★ H1（K1）：缸里的粮（{@code peasantJar} / {@code landlordJar}）**不再写进行里** —— 它们进家户账**会话工作副本**
   * （{@code goods}，就地更新）；行里只剩人口/劳动/参与率/需求。
   */
  private static void addHex(
      Map<IndustryId, Industry> industries,
      Map<CohortKey, ClassRow> classes,
      Map<CohortKey, Map<CommodityId, Long>> goods,
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
        EconomyFixtures.industry(
            id,
            "农业",
            new RegimeId("feudal"),
            cycleDays,
            0L,
            // ★ R3：产能锚（规模单位 = 1 亩）与劳动那一路照常给；产出为空 ⇒ 不产粮（理由见上）。
            Map.of(AssetKind.LAND, 1_000L),
            // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
            Map.of(AssetKind.LAND, 0L),
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
            Map.of(),
            // ★ 通用夹具的 operator = **派生**（`feudal` ⇒ `ESTATE:<本产业的 id>`，id 由 kind/q/r 现算）。
            RegimeOperators.defaultOperator(new RegimeId("feudal"), id)));
    // ★★ H0：行的格取自**这个产业 id 的格键**（不是那个固定常量 HEX）—— 本夹具逐格建行（多格夹具里
    //   两格的同阶层行是**两家户**，键必须跟着 q/r 走；写死 (0,0) 会把多格并成一行）。
    CohortKey peasantKey = new CohortKey(new HexCoord(q, r), ResidenceKind.RURAL, PEASANT);
    CohortKey lenderKey = new CohortKey(new HexCoord(q, r), ResidenceKind.RURAL, lenderStratum);
    classes.put(peasantKey, row(peasantKey, PEASANT_POPULATION, 1000));
    classes.put(lenderKey, row(lenderKey, 10L, 0));
    // ★★ H1：缸里的粮进**家户账工作副本**（每个有人口的家户都有一张表；0 ⇒ 空表）。
    EconomyFixtures.hold(goods, peasantKey, GRAIN, peasantJar);
    EconomyFixtures.hold(goods, lenderKey, GRAIN, landlordJar);
  }

  private static ClassRow row(CohortKey key, long population, int participationPerMille) {
    return EconomyFixtures.classRow(
        key,
        population,
        population * LABOR_PER_PERSON,
        participationPerMille,
        0L,
        new ArrayList<>(),
        Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(population, 1L)),
        Map.of(),
        0L);
  }

  /**
   * ★ E4a 起粮债只有**一条连续合同**（同一 (债务人, 债权人, unit, terms) 跨周期恒同条）。本文件 9 个旧债务用例保持
   * {@code @Disabled}，仅借此让它们按新形状编译；具体语义由 T2 按带收入场景重写。
   */
  private static DebtContractId legacyDebtId() {
    return EconomySettlement.legacyGrainDebtId(
        EconomyFixtures.hh(PEASANT_KEY), EconomyFixtures.hh(LANDLORD_KEY));
  }

  /** 给测试世界种入一条贫农→地主的连续粮债（只用于 active 重放对拍；不制造任何库存/不绕过借粮路径）。 */
  private static EconomyFixtures.World withInitialGrainDebt(EconomyFixtures.World world) {
    DebtContractId id = legacyDebtId();
    DebtContract contract =
        new DebtContract(
            id,
            EconomyFixtures.hh(PEASANT_KEY),
            EconomyFixtures.hh(LANDLORD_KEY),
            DebtUnit.commodity(GRAIN),
            DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE),
            100_000L,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    return new EconomyFixtures.World(
        world.data().withDebtContracts(Map.of(id, contract)), world.goods());
  }

  /** 全部家户的粮余额之和（★ H1：从**会话工作副本**读 —— 行里没有 {@code goods} 了）。 */
  private static long grainTotal(Map<CohortKey, Map<CommodityId, Long>> goods) {
    return goods.values().stream().mapToLong(inner -> inner.getOrDefault(GRAIN, 0L)).sum();
  }
}
