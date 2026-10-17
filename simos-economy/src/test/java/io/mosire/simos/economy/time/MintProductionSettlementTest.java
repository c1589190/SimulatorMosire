package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>W5（2026-10-11 测试批）：铸币生产方式的判据 ①–⑤</b>。
 *
 * <p>判据出处 = 约束设计书 v3.14 §6.1 的 <b>W5 行</b>（铸币生产方式：劳动/工具 → 货币；货币 = 发行政府的债务；每条发行有审计）+ ★★ <b>v3.18 §7
 * 的最新口径</b>（W5c 测试批迁到 W5-fix5 的铸币口径）：<b>实铸上限 = 政策旋钮 {@code mintScalePerCycle}</b>（"不夹"）；"上一轮印量"
 * <b>只是真读数</b>（DEBUG {@code MINT_LIMIT_WINDOW} / INFO {@code MINT_LAST_CYCLE_READOUT} / 状态 {@code
 * Government.mintedLastCyclePerCycle}）、<b>不参与任何 clamp</b>；"首轮启动额"特判已删（读数缺席 = {@code -1} / 空表）+ §6.3
 * 判据 3 的负向面； 实现口径见 W5 账本 {@code .superpowers/sdd/2026-10-11-w5-minting-into-market/impl-ledger.md}
 * §2 / §8（N-1 / N-3 / N-4 / N-11） 与其中的 <b>W5-fix5</b> 节（C-1…C-7）。
 *
 * <p>★ <b>用户裁定（逐字，W5-fix5 的依据）</b>：「<b>没事没事，铸币能进政府的家户，按照决策人预期被发行就行</b>」⇒ 决策人预期（旋钮）即权威，
 * 上限不再被"上一轮印量"夹住。
 *
 * <p>★★ <b>为什么走 {@link EconomyDayStepper} 而不是直接调 {@code MintSettlement}</b>：判据 ① 的要害是"<b>投入真实消耗</b>
 * + 产出 = 货币"是<b>同一次关账</b>的两面 —— 直接调结算口只能验"读到的 scale 变成了钱"，验不了"那笔投入真的被扣了"（那条链在 {@code
 * drawCycleInputs} + {@code evaluateHarvest} 里）。本类因此用真日循环，只把世界的形状做小（一格 / 一产业 / 一 unit / 一户）。
 *
 * <p>★★ <b>夹具的逐值口径（每个数字都当场算得出来，不是抄来的）</b>：产业 = 铸币（{@code cycleDays=1}、{@code
 * capacityPerUnit={WORKSHOP:1}}、{@code laborPerUnit=8,000}、{@code
 * cycleInputPerUnit={WORKSHOP:{tool:2,000}}}、{@code moneyOutputPerUnit={silver:500}}）；unit 的
 * operator = <b>政府家户</b>（= {@code Government.treasury}，即本批播种世界的形状： {@code
 * EconomySeeder.seedGovernmentMint} 的 {@code operator = HouseholdActors.of(govHousehold)}）；该户账上
 * {@code tool=8,000}、{@code silver=100}；产能 {@code WORKSHOP=1}（OWNED）；劳动配额 {@code 8,000} 千分劳动。
 *
 * <pre>
 * 规模 scale = min( 产能 1 ÷ 1 = 1,  劳动 8,000 ÷ 8,000 = 1,  投入 8,000 ÷ 2,000 = 4 ) = 1
 * 铸币     = scale × moneyOutputPerUnit[silver] = 1 × 500 = 500 毫（记进国库账户余额）
 * 投入消耗 = scale × cycleInputPerUnit[tool]    = 1 × 2,000 = 2,000 毫（工具 8,000 → 6,000）
 * 债务     = 500 毫（债务人 = 发行政府的国库户；债权人 = 铸币这一路的组织者 —— 播种形状下两者是同一户 ⇒ 自持记账事实）
 * </pre>
 *
 * <p>★★★ <b>本夹具唯一的一处装置，必须写明（W5b 账本 §BLOCKED-2）</b>：国库户的货币在会话里被<b>冻结</b>（{@code frozenMoney}）⇒
 * 同一日后半段的还债轮算出的可动货币是 0，因此<b>不会</b>去动那条铸币债。这不是设计选择 —— 真世界里国库的钱是活的，而"有库存就还"（W2 v3.6
 * 口径、无保留窗口）会立刻为这条<b>自持</b>债务铸一笔"自己付给自己"的转移 ⇒ {@code Transfer} 当场抛（自转移不是一条发生额）， 整天的 {@code advance}
 * 直接失败。⇒ 本类能验的是<b>铸币这一路本身</b>（判据①–⑤ 的全部数值事实）；"播种形状能不能跑完一天"这一条是本批的 **BLOCKED-2**（最小复现与调用栈见账本，方向 =
 * 还债轮应排除 {@code debtor == creditor} 的合同）。
 *
 * <p>★ <b>本类不落盘、不 replay</b>：只用会话内的工作副本 ⇒ 与"存档 codec"那条链无关（W5 的 codec 往返缺陷是 main 侧的独立缺陷，见 W5b 账本
 * §BLOCKED-1）。
 */
class MintProductionSettlementTest {

  private static final HexCoord HEX = new HexCoord(0, 0);

  /** 铸币产业（{@code mint@0_0}；kind 字面与 {@code EconomySeeder.MINT} 同源）。 */
  private static final IndustryId MINT = IndustryHexKeys.id("mint", 0, 0);

  private static final GovernmentId GOV = new GovernmentId("gov-mint");

  /** 政府家户（= 国库 = 铸币组织者；{@code Government.treasury} 与 unit 的 operator 是同一户 —— 播种世界的形状）。 */
  private static final HouseholdId GOV_HOUSEHOLD = HouseholdId.parse("hh-gov-mint");

  private static final ActorRef TREASURY = HouseholdActors.of(GOV_HOUSEHOLD);

  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(MINT, TREASURY);

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-mint-fixture");

  private static final PeopleLotId LOT = new PeopleLotId("urban:0_0:MALE:1");

  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.URBAN, SocialClassId.OFFICIAL);

  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  /** 越权币种（政府 {@code issuable} 只有 silver）—— 判"不许静默铸一种它无权发行的币"。 */
  private static final CurrencyId COPPER = new CurrencyId("copper");

  private static final CommodityId TOOL = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /** 铸币产出率（毫/规模·周期）：与 {@code EconomySeeder.MINT_MILLI_PER_FURNACE_CYCLE} 同值（标定项）。 */
  private static final long MINT_PER_UNIT = 500L;

  /** 每座铸炉每周期的工具投入（毫）：与 {@code EconomySeeder.MINT_TOOL_MILLI_PER_FURNACE} 同值。 */
  private static final long TOOL_PER_UNIT = 2_000L;

  /** 每座铸炉每周期的劳动（千分劳动）：与 {@code EconomySeeder.MINT_LABOR_MILLI_PER_FURNACE} 同值。 */
  private static final long LABOR_PER_UNIT = 8_000L;

  /** 国库户的铸币工具库存（毫）：与 {@code EconomySeeder} 的种子口径同量级（够跑 4 个周期）。 */
  private static final long SEED_TOOL_MILLI = 8_000L;

  /** 国库户的初始货币（毫）：★ 刻意非 0 —— 守恒断言在"总量 = 0"的世界里等于什么都没验。 */
  private static final long SEED_MONEY_MILLI = 100L;

  /**
   * 国库户货币的冻结额（毫）：≫ 任何用例铸出的钱 ⇒ 还债轮的"可动货币"恒为 0。
   *
   * <p>★ 它只服务本类的夹具（见类注：真世界里这笔钱是活的，那时 BLOCKED-2 的自转移会当场抛）。
   */
  private static final long FROZEN_MONEY_MILLI = 1_000_000L;

  // ── 判据 ① + ④ + ⑤（正向）：生产账逐值对账 + 审计具名 + 货币只多出发行记录那笔 ────────────

  @Test
  @DisplayName("判据①④⑤：铸币有生产账（投入真扣 2,000 / 产出 = 1×500 毫）+ 发行审计具名 + 货币增量恰好等于发行记录")
  void mintIsAProductionProcessWithAuditAndNoMoneyFromNowhere() {
    Run run = runOneDay(mintWorld(1L, 2_000L, OptionalLong.empty(), Map.of(), SILVER), 1L);

    // ── 前提守卫：世界确实在跑（否则后面的断言是空转） ──
    assertThat(run.ledger().hasOutput()).as("铸币不产商品 ⇒ hasOutput 为假是预期（钱不是商品）").isFalse();
    assertThat(run.ledger().inputOf(MINT, TOOL))
        .as("★ 投入真实消耗：本周期真的从国库户账上扣了 scale × 2,000 = 2,000 毫工具")
        .isEqualTo(TOOL_PER_UNIT);
    assertThat(run.goodsAfter().get(GOV_HOUSEHOLD).get(TOOL))
        .as("账户口径交叉验证：工具 8,000 − 2,000 = 6,000 毫（投入不是记在纸上的）")
        .isEqualTo(SEED_TOOL_MILLI - TOOL_PER_UNIT);

    // ── 判据 ④：每条发行都有 MoneyIssuanceRecord（kind 具名 + id 确定 + 周期/政府/币种/金额逐值） ──
    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances = run.data().moneyIssuances();
    assertThat(issuances).as("本日恰一条铸币发行记录").hasSize(1);
    MoneyIssuanceRecord record = issuances.values().iterator().next();
    assertThat(record.id())
        .as("★ 发行 id = 确定性派生 mi-mint-<gov>-<day>-<cur>（可重放；与 mi- / mi-wd- 两条前缀互不重叠）")
        .isEqualTo(MoneyIssuanceId.forMintProduction(GOV.value(), 1L, SILVER));
    assertThat(record.id().value()).as("id 逐字").isEqualTo("mi-mint-gov-mint-1-silver");
    assertThat(record.kind())
        .as("★ 具名类别 MINT_PRODUCTION（不复用 FISCAL_ISSUE —— 后者是'透支差额'，与'产出一批钱'是两件事）")
        .isEqualTo(MoneyIssuanceKind.MINT_PRODUCTION);
    assertThat(record.kind().issuance()).as("它是一条发行（方向 = 加钱）").isTrue();
    assertThat(record.governmentId()).isEqualTo(GOV);
    assertThat(record.currency()).isEqualTo(SILVER);
    assertThat(record.day()).isEqualTo(1L);
    assertThat(record.period()).as("period = 本产业周期（判据③ 的'上一轮'窗口就按它现算）").isEqualTo(1L);
    assertThat(record.amount()).as("★ 产出 = scale(1) × perUnit(500) = 500 毫").isEqualTo(500L);
    assertThat(record.reason())
        .as("审计的 reason 自带逐值算术（scale / perUnit / 上限）⇒ 排障不必回读代码")
        .contains("scale=1", "perUnit=500", "上限=2000");

    // ── 产出 = 货币：走国库账户余额（不是货物账、也不 ×1000） ──
    assertThat(run.moneyAfter().get(GOV_HOUSEHOLD))
        .as("★ 国库余额 100 + 500 = 600 毫（是'加'不是'覆盖'）")
        .containsEntry(SILVER, SEED_MONEY_MILLI + 500L);

    // ── 货币 = 发行政府的债务（判据④ 的反面：钱是欠出来的） ──
    assertThat(run.data().debtContracts()).as("每条铸币同时落一条债务合同").hasSize(1);
    DebtContract debt = run.data().debtContracts().values().iterator().next();
    assertThat(debt.debtor()).as("债务人是发行政府的国库户").isEqualTo(GOV_HOUSEHOLD);
    assertThat(debt.creditor())
        .as("债权人是铸币这一路的组织者（播种形状下与国库同一户 ⇒ 自持的记账事实，见账本 §BLOCKED-2）")
        .isEqualTo(GOV_HOUSEHOLD);
    assertThat(debt.unit()).isEqualTo(new DebtUnit.Money(SILVER));
    assertThat(debt.openedDay()).as("合同就是这一天开的").isEqualTo(1L);
    assertThat(debt.dueCycle()).as("到期 = 当前周期 + 1 = 2").hasValue(2L);
    assertThat(debt.principal()).as("负债额 = 本笔铸币额 500 毫（它就是'印出来的钱'那一笔，不是重复计债）").isEqualTo(500L);
    assertThat(debt.status()).as("夹具把国库货币冻结 ⇒ 这条债当日不被还债轮动，仍是 NORMAL").isEqualTo(DebtStatus.NORMAL);
    FlowRow treasuryFlow = run.data().flows().get(GOV_HOUSEHOLD);
    assertThat(treasuryFlow).as("国库户当日有流水行").isNotNull();
    assertThat(treasuryFlow.repaidMoney())
        .as("★ 夹具装置自证：国库可动货币 = 0 ⇒ 当日货币腿还款为空（否则就是被还掉了，余额读数会变）")
        .isEmpty();

    // ── 判据 ⑤（运行读数那一半）：凭空加钱 = 0 —— 货币总量增量**恰好**等于发行记录之和 ──
    assertThat(run.moneyDeltaTotal())
        .as("★ Σ全部账户货币增量必须 == Σ MINT_PRODUCTION = 500：多一毫就是第二条凭空加钱路径")
        .isEqualTo(500L);
    assertThat(run.issuanceTotalByKind(MoneyIssuanceKind.MINT_PRODUCTION))
        .isEqualTo(run.moneyDeltaTotal());
    assertThat(run.nonMintIssuanceTotal())
        .as(
            "非 GM 世界里不得出现 FISCAL_ISSUE / INITIAL_ENDOWMENT / WITHDRAWAL（simos.gov.issueMoney 那条腿要 DM 授权 + 审批）")
        .isZero();
  }

  @Test
  @DisplayName("判据①：规模放大逐值跟随（2 座铸炉 ⇒ 产出 1,000 毫、投入 4,000 毫）")
  void mintOutputScalesWithTheProductionScale() {
    Run run = runOneDay(mintWorld(2L, 2_000L, OptionalLong.empty(), Map.of(), SILVER), 1L);

    assertThat(run.ledger().inputOf(MINT, TOOL))
        .as("投入 = scale(2) × 2,000 = 4,000 毫")
        .isEqualTo(4_000L);
    assertThat(run.goodsAfter().get(GOV_HOUSEHOLD).get(TOOL))
        .as("工具 8,000 − 4,000 = 4,000")
        .isEqualTo(SEED_TOOL_MILLI - 4_000L);
    MoneyIssuanceRecord record = run.data().moneyIssuances().values().iterator().next();
    assertThat(record.amount())
        .as("★ 产出 = scale(2) × perUnit(500) = 1,000 毫（逐值跟随规模，不是常数）")
        .isEqualTo(1_000L);
    assertThat(record.reason()).contains("scale=2", "perUnit=500");
    assertThat(run.moneyAfter().get(GOV_HOUSEHOLD))
        .containsEntry(SILVER, SEED_MONEY_MILLI + 1_000L);
    assertThat(run.moneyDeltaTotal()).as("货币总量增量 = 1,000").isEqualTo(1_000L);
  }

  // ── 判据 ②（负向）：政策旋钮 0 ⇒ 新钱恰好 0 毫 ────────────────────────────────────────

  @Test
  @DisplayName("判据②：mintScalePerCycle=0 ⇒ 新钱 0 毫（无发行记录 / 无货币债务 / 国库余额一毫不动）")
  void zeroMintScaleMeansExactlyZeroNewMoney() {
    Run run;
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.DEBUG)) {
      run = runOneDay(mintWorld(1L, 0L, OptionalLong.empty(), Map.of(), SILVER), 1L);
      assertThat(log.lines())
          .as("政策明写 0 = 具名状态（DEBUG MINT_POLICY_ZERO 说清'为什么没铸'，不是静默跳过）")
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains(
                          "MINT_POLICY_ZERO",
                          "government=gov-mint",
                          "reason=mint-scale-zero",
                          "minted=0"));
      assertThat(log.lines())
          .as("★ 关掉的政策不是一次拒绝 ⇒ 不得发 INFO/ERROR 刷屏")
          .noneSatisfy(line -> assertThat(line).contains("MINT_LIMIT_REJECTED", "MINT_SETTLED"));
    }

    assertThat(run.data().moneyIssuances()).as("★ 规模 0 ⇒ 一条发行记录都没有（不做'0 毫的发行'）").isEmpty();
    assertThat(run.data().debtContracts()).as("无铸币 ⇒ 无货币债务").isEmpty();
    assertThat(run.moneyAfter().get(GOV_HOUSEHOLD))
        .as("★ 国库余额一毫不动（100 ⇒ 100）")
        .containsEntry(SILVER, SEED_MONEY_MILLI);
    assertThat(run.moneyDeltaTotal()).as("★ 货币总量增量 = 0 毫（货币守恒）").isZero();
  }

  // ── 判据 ③（W5c 迁移：上限 = 政策旋钮；"上一轮印量"只是真读数，不夹） ──────────────────

  @Test
  @DisplayName("判据③（读数面·不夹）：上一轮印 300 只是读数 ⇒ 旋钮 2,000 下实铸 500、无拒绝、国库 100+500=600，且那个 300 是真读数")
  void previousCycleMintedIsAReadoutNotAClamp() {
    // 上一轮（period=1，落在 day=1）印了 300：夹具手工放一条 MINT_PRODUCTION 记录。
    // ⇒ 本轮（lastClosedCycle=1 ⇒ currentCycle=2）的上限 = 政策旋钮 2,000（**不再**是 min(政策 2,000, 上一轮 300) =
    // 300）。
    MoneyIssuanceRecord previous = previousMintRecord(300L);
    Run run;
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.DEBUG)) {
      run =
          runOneDay(
              mintWorld(1L, 2_000L, OptionalLong.of(1L), Map.of(previous.id(), previous), SILVER),
              2L);

      // ── 读数面 ①（DEBUG MINT_LIMIT_WINDOW）：上一轮 = day 1、印 300（真值）；上限来自旋钮，不是那个 300 ──
      assertThat(log.lines())
          .as("★ 上一轮印量仍是**真读数**（窗口 = 最近一个严格早于本轮的铸币日）")
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains(
                          "MINT_LIMIT_WINDOW",
                          "government=gov-mint",
                          "previousRoundDay=1",
                          "previousCycleMinted=300",
                          "mintScalePerCycle=2000",
                          "limit=2000",
                          "limitSource=mint-scale-policy",
                          "mintedLastCycleReadout={silver=300}",
                          "mintedLastCycleReadoutChanged=true",
                          "reason=previous-mint-round-day-window-readout-only"));

      // ── 读数面 ②（INFO MINT_LAST_CYCLE_READOUT）：把上一轮的 300 覆盖进 Government.mintedLastCyclePerCycle ──
      assertThat(log.lines())
          .as("★ 读数写口：状态里那一栏落的是'上一轮'的 300（不是本轮的 500）")
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains(
                          "MINT_LAST_CYCLE_READOUT",
                          "government=gov-mint",
                          "previousRoundDay=1",
                          "previousCycleMinted=300",
                          "before={}",
                          "after={silver=300}"));

      // ── 不夹 ⇒ 本轮没有任何超额可言（请求 500 ≤ 旋钮 2,000） ──
      assertThat(log.lines())
          .as("★ 上限不夹 ⇒ 不得出现 MINT_LIMIT_REJECTED（上一轮只印 300 也拦不住本轮）")
          .noneSatisfy(line -> assertThat(line).contains("MINT_LIMIT_REJECTED"));
    }

    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances = run.data().moneyIssuances();
    assertThat(issuances).as("本轮的发行记录 + 夹具那条上一轮记录").hasSize(2);
    MoneyIssuanceRecord minted =
        issuances.get(MoneyIssuanceId.forMintProduction(GOV.value(), 2L, SILVER));
    assertThat(minted).as("本轮记录按 (政府, 日=2, 币种) 派生，与上一轮那条不撞 id").isNotNull();
    assertThat(minted.amount()).as("★★ 实铸 = 请求 500（**不再**被上一轮印量 300 夹住）").isEqualTo(500L);
    assertThat(minted.period()).as("它属于本轮（period=2）⇒ 下一轮的读数窗口就取这一条").isEqualTo(2L);
    assertThat(minted.day()).as("绝对日 = 本轮那一天（读数窗口按它排序）").isEqualTo(2L);
    assertThat(minted.reason())
        .as("审计的 reason 自带逐值算术（scale / perUnit / 上限 = 旋钮）")
        .contains("scale=1", "perUnit=500", "上限=2000", "政策旋钮=2000");
    assertThat(run.moneyAfter().get(GOV_HOUSEHOLD))
        .as("★ 国库余额 100 + 500 = 600 毫（是'加'不是'覆盖'）")
        .containsEntry(SILVER, SEED_MONEY_MILLI + 500L);
    assertThat(run.moneyDeltaTotal())
        .as("★ Σ全部账户货币增量必须 == Σ MINT_PRODUCTION = 500：多一毫就是第二条凭空加钱路径")
        .isEqualTo(500L);
    assertThat(run.data().debtContracts()).as("每条铸币同时落一条债务合同").hasSize(1);
    assertThat(run.data().debtContracts().values().iterator().next().principal())
        .as("债务 = 实铸额 500（不是上一轮那个 300、也不是请求额别的数）")
        .isEqualTo(500L);
    // 投入与产出各自独立：投入按规模真扣满（2,000），只有**货币产出**受上限（这里是旋钮，且没触发）约束。
    assertThat(run.ledger().inputOf(MINT, TOOL)).as("上限闸门只截产出，不改投入口径").isEqualTo(TOOL_PER_UNIT);
    // ── 读数面 ③：状态里那一栏（GUI 读的就是它）逐值 == 上一轮实印 300 ──
    assertThat(run.data().governments().get(GOV).mintedLastCyclePerCycle())
        .as("★ Government.mintedLastCyclePerCycle 关账后 == 上一轮实印 300（真值，且 ≠ 本轮的 500）")
        .containsExactlyEntriesOf(Map.of(SILVER, 300L));
  }

  @Test
  @DisplayName(
      "判据③（拒发面）：政策旋钮 300 < 请求 500 ⇒ 实铸 300 + INFO MINT_LIMIT_REJECTED（limit=300、rejected=200、reason=above-mint-scale-policy）")
  void thePolicyKnobIsTheOnlyCapAndTheExcessIsRejectedByName() {
    Run run;
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.INFO)) {
      run = runOneDay(mintWorld(1L, 300L, OptionalLong.empty(), Map.of(), SILVER), 1L);

      assertThat(log.lines())
          .as("★ 真拒面：旋钮就是上限（不是'上一轮印量'）⇒ 超额具名拒（INFO：业务拒绝不降级、也不升级成 ERROR）")
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains(
                          "MINT_LIMIT_REJECTED",
                          "government=gov-mint",
                          "requested=500",
                          "limit=300",
                          "previousCycleMinted=-1", // ★ 首轮特判已删：没有上一轮 ⇒ 读数缺席（-1），不再是"启动额 = 旋钮"
                          "mintScalePerCycle=300",
                          "rejected=200",
                          "reason=above-mint-scale-policy"));
    }

    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances = run.data().moneyIssuances();
    assertThat(issuances).as("本日恰一条发行记录（夹具没预置任何上一轮记录）").hasSize(1);
    MoneyIssuanceRecord minted =
        issuances.get(MoneyIssuanceId.forMintProduction(GOV.value(), 1L, SILVER));
    assertThat(minted).isNotNull();
    assertThat(minted.amount()).as("★ 实铸 = min(请求 500, 旋钮 300) = 300 毫 —— 旋钮是唯一闸门").isEqualTo(300L);
    assertThat(minted.reason()).contains("上限=300", "政策旋钮=300", "产出请求=500");
    assertThat(run.moneyAfter().get(GOV_HOUSEHOLD))
        .as("国库 100 + 300 = 400 毫")
        .containsEntry(SILVER, 400L);
    assertThat(run.moneyDeltaTotal()).as("★ 增量 = 300（= 发行之和，无第二条加钱路径）").isEqualTo(300L);
    assertThat(run.data().debtContracts()).as("每条铸币同时落一条债务合同").hasSize(1);
    assertThat(run.data().debtContracts().values().iterator().next().principal())
        .as("债务 = 实铸额 300（不是请求额 500）")
        .isEqualTo(300L);
    assertThat(run.ledger().inputOf(MINT, TOOL)).as("上限闸门只截产出，不改投入口径").isEqualTo(TOOL_PER_UNIT);
  }

  @Test
  @DisplayName("读数面（连推两轮）：第 2 轮关账后 mintedLastCyclePerCycle == 第 1 轮实印（不是本轮）；旋钮 0 的那轮不清它")
  void mintedLastCycleReadoutTracksThePreviousRoundAndSurvivesAZeroKnobRound() {
    // 三轮真日循环（同一条账户会话）：第 1 轮旋钮 300 ⇒ 实印 300；第 2 轮旋钮 2,000 ⇒ 实印 500；第 3 轮旋钮 0 ⇒ 实印 0。
    // ★ 三轮的实印互不相同（300 / 500 / 0）⇒ "读数 = 上一轮"与"读数 = 本轮"、"读数 = 空表"三者当场可分。
    List<EconomyData> states =
        runRounds(
            mintWorld(1L, 300L, OptionalLong.empty(), Map.of(), SILVER), 1L, 300L, 2_000L, 0L);
    EconomyData afterRound1 = states.get(0);
    EconomyData afterRound2 = states.get(1);
    EconomyData afterRound3 = states.get(2);

    // ── 前提守卫：三轮确实按预期发生（否则后面的读数断言是空转） ──
    assertThat(mintAmount(afterRound1, 1L)).as("前提：第 1 轮真的印了 300（旋钮 300 截住请求 500）").isEqualTo(300L);
    assertThat(mintAmount(afterRound2, 2L))
        .as("前提：第 2 轮真的印了 500（旋钮 2,000 不被上一轮的 300 夹住）")
        .isEqualTo(500L);
    assertThat(afterRound3.moneyIssuances().size())
        .as("前提：旋钮 0 的第 3 轮没有新发行记录")
        .isEqualTo(afterRound2.moneyIssuances().size());
    assertThat(afterRound3.moneyIssuances().keySet())
        .as("前提：第 3 轮那条按 (政府, 日=3, 币种) 派生的 id 不存在")
        .doesNotContain(MoneyIssuanceId.forMintProduction(GOV.value(), 3L, SILVER));

    assertThat(afterRound1.governments().get(GOV).mintedLastCyclePerCycle())
        .as("第 1 轮没有上一轮 ⇒ 空表（★ 首轮特判已删；空表 = '从未铸过' 这个合法读数，不是 0 毫）")
        .isEmpty();
    assertThat(afterRound2.governments().get(GOV).mintedLastCyclePerCycle())
        .as("★★ 第 2 轮关账后 == 第 1 轮实印 300（不是本轮自己的 500 ⇒ 这条判据有判别力）")
        .containsExactlyEntriesOf(Map.of(SILVER, 300L));
    assertThat(afterRound3.governments().get(GOV).mintedLastCyclePerCycle())
        .as("★★ 旋钮 0 的那一轮**不清**读数：它写的是'最近一个真印过的轮' = 第 2 轮的 500（既不是空表、也不是 0 毫）")
        .containsExactlyEntriesOf(Map.of(SILVER, 500L));
  }

  // ── 越权负向：产出币种不在 issuable ⇒ fail-closed（ERROR，不静默铸） ────────────────────

  @Test
  @DisplayName("越权负向：配方铸一种该政府无权发行的币 ⇒ 当场抛（不静默铸、不静默改币种）")
  void mintingACurrencyTheGovernmentCannotIssueFailsClosed() {
    EconomyData world = mintWorld(1L, 2_000L, OptionalLong.empty(), Map.of(), COPPER);
    AccountSession accounts = accountsFor();
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            world,
            mintDefs(COPPER),
            runtimeOf(mintDefs(COPPER)),
            accounts,
            MarketTopology.singleHex(world.marketTables(), world.marketZones()));
    try (TestLogCapture log =
        TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.INFO)) {
      assertThatThrownBy(() -> stepper.step(1L))
          .as("★ 权限不放大：说不出'这是我发的钱'就不许铸")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("不在该政府的 issuable 集合内");
      assertThat(log.lines())
          .as("契约故障 ⇒ ERROR 且不降级（§一.9）")
          .anySatisfy(
              line -> assertThat(line).contains("MINT_CURRENCY_NOT_ISSUABLE", "currency=copper"));
    } finally {
      stepper.close();
    }
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 一次推进的读数（会话终态 + 当天流水 + 账户终值）。 */
  private record Run(
      EconomyData data,
      ProductionLedger ledger,
      Map<HouseholdId, Map<CommodityId, Long>> goodsAfter,
      Map<HouseholdId, Map<CurrencyId, Long>> moneyAfter,
      long moneyDeltaTotal) {

    long issuanceTotalByKind(MoneyIssuanceKind kind) {
      long total = 0L;
      for (MoneyIssuanceRecord record : data.moneyIssuances().values()) {
        if (record.kind() == kind) {
          total += record.amount();
        }
      }
      return total;
    }

    long nonMintIssuanceTotal() {
      long total = 0L;
      for (MoneyIssuanceRecord record : data.moneyIssuances().values()) {
        if (record.kind() != MoneyIssuanceKind.MINT_PRODUCTION && record.kind().issuance()) {
          total += record.amount();
        }
      }
      return total;
    }
  }

  /**
   * 推一天（真 {@link EconomyDayStepper}；产业 {@code cycleDays=1} ⇒ 被推的那一天就是关账日）。
   *
   * <p>★ 货币增量按"全部账户终值之和 − 初始货币之和"读 ⇒ 直接可比发行记录之和（转移不改总量）。
   */
  private static Run runOneDay(EconomyData base, long day) {
    AccountSession accounts = accountsFor();
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            mintDefs(SILVER),
            runtimeOf(mintDefs(SILVER)),
            accounts,
            MarketTopology.singleHex(base.marketTables(), base.marketZones()));
    try {
      ProductionLedger ledger = stepper.step(day);
      EconomyData data = stepper.finish();
      Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
      Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
      long delta = 0L;
      for (AccountSession.ActorAccount account : accounts.accounts().values()) {
        goods.put(account.household(), Map.copyOf(account.goods()));
        money.put(account.household(), Map.copyOf(account.money()));
        for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
          delta += entry.getValue();
        }
      }
      return new Run(data, ledger, goods, money, delta - SEED_MONEY_MILLI);
    } finally {
      stepper.close();
    }
  }

  /**
   * 连推 {@code knobPerRound.length} 轮（真 {@link EconomyDayStepper}；产业 {@code cycleDays=1} ⇒ 一天 = 一轮 =
   * 一个关账日）。
   *
   * <p>★★ <b>与 {@link #runOneDay} 的差别只有两点，都是判据要的</b>：① <b>同一条</b> {@link AccountSession} 跨轮复用（国库余额与
   * 冻结装置都延续，世界不是每轮重造）；② 每轮开推前把政策旋钮设成 {@code knobPerRound[轮]}（读数面判据要"旋钮逐轮不同"才有判别力）。
   *
   * <p>★ <b>返回值 = 每轮关账后的状态</b>（{@code index 0} = 第 1 轮）—— 读数面判据读的是 {@code
   * Government.mintedLastCyclePerCycle}，它只活在状态里。
   *
   * @param base 第 1 轮的基态（旋钮会被 {@code knobPerRound[0]} 覆盖）
   * @param firstDay 第 1 轮的绝对日（第 2 轮 = {@code firstDay + 1}，依此类推）
   * @param knobPerRound 逐轮的政策旋钮（毫/周期；长度 = 轮数）
   */
  private static List<EconomyData> runRounds(
      EconomyData base, long firstDay, long... knobPerRound) {
    AccountSession accounts = accountsFor();
    List<EconomyData> states = new ArrayList<>();
    EconomyData current = base;
    for (int round = 0; round < knobPerRound.length; round++) {
      long day = firstDay + round;
      LinkedHashMap<GovernmentId, Government> governments =
          new LinkedHashMap<>(current.governments());
      governments.put(GOV, governments.get(GOV).withMintScalePerCycle(knobPerRound[round]));
      current = current.withGovernments(governments);
      EconomyDayStepper stepper =
          new EconomyDayStepper(
              current,
              mintDefs(SILVER),
              runtimeOf(mintDefs(SILVER)),
              accounts,
              MarketTopology.singleHex(current.marketTables(), current.marketZones()));
      try {
        stepper.step(day);
        current = stepper.finish();
      } finally {
        stepper.close();
      }
      states.add(current);
    }
    return states;
  }

  /** 某一日那个政府的铸币额（没有 ⇒ 0；只服务读数面用例的前提守卫，不参与任何判据）。 */
  private static long mintAmount(EconomyData data, long day) {
    MoneyIssuanceRecord record =
        data.moneyIssuances().get(MoneyIssuanceId.forMintProduction(GOV.value(), day, SILVER));
    return record == null ? 0L : record.amount();
  }

  /** 夹具的"上一轮印量"记录（{@code day=1}、{@code period=1}、{@code MINT_PRODUCTION}；判据③ 用它给出"上一轮印了多少"）。 */
  private static MoneyIssuanceRecord previousMintRecord(long amount) {
    return new MoneyIssuanceRecord(
        MoneyIssuanceId.forMintProduction(GOV.value(), 1L, SILVER),
        GOV,
        1L,
        1L,
        SILVER,
        amount,
        MoneyIssuanceKind.MINT_PRODUCTION,
        "夹具：上一轮印量 " + amount);
  }

  /**
   * 最小自洽的铸币世界：一格 / 一产业（铸币）/ 一 unit / 一户（政府家户 = 国库 = 铸币组织者，与播种同形）。
   *
   * @param furnaces 铸炉数（= 产能规模上限，也是劳动配额与产能份额的倍数）
   * @param mintScalePerCycle 铸币政策旋钮（毫/周期；0 = 政策不许铸币）
   * @param lastClosedCycle 已经关过账的周期序号（空 = 本轮是第 1 个周期 ⇒ ★ <b>与上限无关</b>：上限恒 = 政策旋钮；它只决定 {@code
   *     currentCycle} 与"读数是不是缺席" —— 没有上一轮 ⇒ {@code previousCycleMinted=-1} / {@code
   *     mintedLastCyclePerCycle} 空表）
   * @param preIssuances 预置的发行记录（判据③ 用它给出"上一轮印了多少"）
   * @param currency 铸币产出的币种（越权用例给它一个政府无权发行的币）
   */
  /** 铸币产业定义（P1：定义权威在 production）。 */
  private static IndustryDefinitionView mint(CurrencyId currency) {
    return new IndustryDefinitionView(
        MINT,
        "铸币",
        new RegimeId("government_mint"),
        java.util.Optional.empty(),
        1L,
        0L, // ★ D-032 T1：新增第 6 个组件 buildDays（本夹具取中性 0，与 testing.IndustryViews 同款）
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        LABOR_PER_UNIT,
        Map.of(),
        Map.of(AssetKind.WORKSHOP, Map.of(TOOL, TOOL_PER_UNIT)),
        List.of(new ClassSlot(SocialClassId.OFFICIAL, "官府", 1000)),
        new AllocationRule.Split(1000, 0),
        new MoneyOutput(Map.of(currency, MINT_PER_UNIT)),
        io.mosire.simos.production.contract.ProductionExperienceUse.NONE, // ★ D-032 T1：第 16 组件
        List.of(), // ★ D-032 T1：第 17 组件 availability
        java.util.Set.of(io.mosire.simos.economy.api.asset.AssetRightKind.OWNED)); // ★ 第 18 组件
  }

  private static ProductionData mintDefs(CurrencyId currency) {
    // ★★ D-032 T5（a 桶夹具欠账）：实例的唯一权威在 production（I1）⇒ 夹具必须把 UNIT **落进权威实例表**。
    //   旧稿只在 mintWorld(...) 里造了一个同形的 ProductionProcess 局部量却从不落表 ⇒ runtime 实例表空 ⇒
    //   driveProductionDay 无步可执行 ⇒ 投入/产出恒 0（7 条红即此形态）。实例 id / operator / location 与
    //   mintWorld(...) 的劳动配额 activity=UNIT.value()、份额 owner=TREASURY 同一个。
    return ProductionData.empty().withIndustry(mint(currency)).withInstance(mintUnit());
  }

  /** 铸币 unit（与 {@link #mintWorld} 的配额/份额/经营者同一个 id 与形状）。 */
  private static ProductionProcess mintUnit() {
    return new ProductionProcess(
        UNIT,
        MINT,
        ProductionLocation.ofPlan(MINT).orElseThrow(),
        TREASURY,
        MINT.value(),
        0L,
        Map.of(),
        Map.of());
  }

  private static EconomyData mintWorld(
      long furnaces,
      long mintScalePerCycle,
      OptionalLong lastClosedCycle,
      Map<MoneyIssuanceId, MoneyIssuanceRecord> preIssuances,
      CurrencyId currency) {
    Government government =
        new Government(GOV, "world", TREASURY, Set.of(SILVER), mintScalePerCycle);
    IndustryDefinitionView mint = mint(currency);
    HouseholdEconomy row =
        new HouseholdEconomy(GOV_HOUSEHOLD, VIEW, 0L, 100_000L, 1000, 0L, Map.of(), Map.of(), 0L);
    OwnershipStake workshops =
        new OwnershipStake(
            OwnershipStake.idOf(
                MINT,
                AssetKind.WORKSHOP,
                TREASURY,
                TREASURY,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            MINT,
            AssetKind.WORKSHOP,
            TREASURY,
            TREASURY,
            furnaces,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            ALLOCATION,
            LOT,
            GOV_HOUSEHOLD,
            TREASURY,
            UNIT.value(),
            LABOR_PER_UNIT * furnaces,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator")));
    return EconomyData.empty()
        .withMeta(Optional.of(meta(lastClosedCycle)))
        .withGovernments(Map.of(government.id(), government))
        .withHouseholdEconomies(Map.of(GOV_HOUSEHOLD, row))
        .withOwnershipStakes(Map.of(workshops.id(), workshops))
        .withLaborCommitments(Map.of(ALLOCATION, commitment))
        .withMoneyIssuances(preIssuances);
  }

  /** 国库户的账：工具 {@value #SEED_TOOL_MILLI} 毫 + 货币 {@value #SEED_MONEY_MILLI} 毫，货币整额冻结（见类注的夹具装置说明）。 */
  private static AccountSession accountsFor() {
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        GOV_HOUSEHOLD,
        HEX,
        Map.of(TOOL, SEED_TOOL_MILLI),
        Map.of(SILVER, SEED_MONEY_MILLI),
        Map.of(),
        Map.of(SILVER, FROZEN_MONEY_MILLI));
    return accounts;
  }

  private static EconomyMeta meta(OptionalLong lastClosedCycle) {
    return new EconomyMeta(
        "mint-production-test",
        0L,
        lastClosedCycle,
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  /** ★★ D-032 B2b/B3：会话/日推进器必须显式拿到 production 运行时（{@code runtime=null} 的旧构造器已删）。 */
  private static io.mosire.simos.production.contract.ProductionRuntime runtimeOf(
      io.mosire.simos.production.ProductionData definitions) {
    return new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
  }
}
