package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>阶段 6.3 长期税纯函数（{@link JurisdictionDailyTax#collect}）的逐值判据</b>（同包，不经 Core）：
 *
 * <ol>
 *   <li><b>空转恒等</b>：无 jurisdiction / 效率表查不到该单位（无 GOV 读数）/ 全区域 rate=0 / 无可税家户 ⇒ 返回<b>同一 ActorData
 *       实例</b>且 {@code Report.isEmpty()}；显式 efficiency=0 与只有缺口时仍是同一实例、但 Report 非空；
 *   <li><b>手算逐值</b>：2 户 × 1 单位 × 粮/钱，含一个 {@code REGION_MISSING}、一个 {@code stockShortfall}（冻结吃掉的
 *       可支配）与 {@code adminShortfall}（efficiency=500‰）；逐户余额、国库账（五参新建、冻结表空）、Report 四量、计数、账键序、0
 *       余额保留、meta/actors 原样；
 *   <li><b>NO_POSITION</b>：单位无有效位置 ⇒ 同实例 + 具名缺口，一笔不征；
 *   <li><b>重叠管辖</b>：两单位同区同户，按 unitId 升序，第二个见税后余额；
 *   <li><b>确定性</b>：同输入两次逐字段相等；账户/单位/map 插入序打乱后 Report 与账值不变；
 *   <li><b>守恒式</b>：逐维 {@code Σ家户减少 == 国库增加}（冻结表逐值不动）；
 *   <li><b>efficiency &gt;1000‰</b>：{@code attainable > assessed} ⇒ {@code adminShortfall}
 *       为负的逐值用例（粮 / 钱各判一次恒等式）。
 * </ol>
 *
 * <p>★ 数值全部由本文件用冻结字面量算好写在断言里，不建 Golden、不调私有算式。
 */
class JurisdictionDailyTaxTest {

  private static final long TICK = 1L;
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);

  /** 一个无管辖、无账户的闲置区（只用来打乱 {@code map.regions()} 的插入序）。 */
  private static final HexCoord H3 = new HexCoord(5, 5);

  private static final RegionId R1 = new RegionId("r-1");

  /** 地图 {@code regions()} 里没有的区域（REGION_MISSING 的靶子）。 */
  private static final RegionId GHOST = new RegionId("r-ghost");

  private static final UnitId U1 = new UnitId("u-1");
  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;
  private static final ActorRef HH_A = new ActorRef(ActorKind.HOUSEHOLD, "hh-a");
  private static final ActorRef HH_B = new ActorRef(ActorKind.HOUSEHOLD, "hh-b");
  private static final GoodsAccountKey A_KEY = new GoodsAccountKey(HH_A, H1);
  private static final GoodsAccountKey B_KEY = new GoodsAccountKey(HH_B, H2);

  // ── 空转恒等 ───────────────────────────────────────────────────────────────

  @Test
  void missingEfficiencyOrZeroRateOrNoTaxableHouseholdReturnsSameInstance() {
    GameMap map = twoHexMap();
    ActorData taxed = actor(accountsWithAAndB());

    UnitState noJurisdiction = unitsOf(unit(U1, Optional.of(H1), Optional.empty()));
    assertNoOp(
        taxed,
        JurisdictionDailyTax.collect(taxed, noJurisdiction, map, TICK, Map.of()),
        "单位没有 jurisdiction");

    // ★ 阶段 11b 的口径：有管辖但效率表查不到该单位（没有 GovFormation/没有 GOV 读数）⇒ 整单位跳过、不征。
    Jurisdiction full = new Jurisdiction(rateMap(R1, 200L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState collected = unitsOf(unit(U1, Optional.of(H1), Optional.of(full)));
    assertNoOp(
        taxed,
        JurisdictionDailyTax.collect(taxed, collected, map, TICK, Map.of()),
        "效率表查不到该单位（无 GOV 读数）⇒ 整单位跳过、不征");

    // ★ 显式 efficiency=0 是**真读数**（不是没有数据）：不动 actor，但 Report 必须暴露全额 adminShortfall。
    JurisdictionDailyTax.Collected zeroEfficiency =
        JurisdictionDailyTax.collect(taxed, collected, map, TICK, Map.of(U1, 0L));
    assertThat(zeroEfficiency.actor()).as("efficiency=0 ⇒ 无实收、actor 同实例").isSameAs(taxed);
    assertThat(zeroEfficiency.report().isEmpty()).as("efficiency=0 不是『无数据』⇒ Report 必须非空").isFalse();
    assertThat(zeroEfficiency.report().grain())
        .as("粮：assessed=80、可达到=0、adminShortfall=80")
        .isEqualTo(new JurisdictionDailyTax.Dimension(80L, 0L, 80L, 0L));
    assertThat(zeroEfficiency.report().money())
        .as("钱：assessed=14、可达到=0、adminShortfall=14")
        .isEqualTo(new JurisdictionDailyTax.Dimension(14L, 0L, 14L, 0L));
    assertThat(zeroEfficiency.report().unitsCharged()).isZero();
    assertThat(zeroEfficiency.report().householdsCharged()).isZero();
    assertThat(zeroEfficiency.report().gaps()).isEmpty();

    Jurisdiction rateZero = new Jurisdiction(rateMap(R1, 0L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState withRateZero = unitsOf(unit(U1, Optional.of(H1), Optional.of(rateZero)));
    assertNoOp(
        taxed,
        JurisdictionDailyTax.collect(taxed, withRateZero, map, TICK, Map.of(U1, 1_000L)),
        "全区域 rate=0（整段跳过）");

    ActorData noHouseholds = actor(new LinkedHashMap<>());
    assertNoOp(
        noHouseholds,
        JurisdictionDailyTax.collect(noHouseholds, collected, map, TICK, Map.of(U1, 1_000L)),
        "管辖有效但 actor 里没有任何 HOUSEHOLD 账");

    Map<GoodsAccountKey, GoodsAccount> zeroBook = new LinkedHashMap<>();
    zeroBook.put(A_KEY, account(HH_A, H1, 0L, 0L));
    ActorData zeroHousehold = actor(zeroBook);
    assertNoOp(
        zeroHousehold,
        JurisdictionDailyTax.collect(zeroHousehold, collected, map, TICK, Map.of(U1, 1_000L)),
        "有家户账但两个维度余额都是 0（≤0 跳过）");
  }

  @Test
  void missingRegionWithoutAnyExistingRegionReturnsSameInstanceWithNamedGap() {
    GameMap map = twoHexMap();
    ActorData before = actor(accountsWithAAndB());
    Jurisdiction jurisdiction =
        new Jurisdiction(rateMap(GHOST, 300L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState units = unitsOf(unit(U1, Optional.of(H1), Optional.of(jurisdiction)));

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(before, units, map, TICK, Map.of(U1, 1_000L));

    assertThat(collected.actor()).as("只有缺口 ⇒ 仍是入参同一实例").isSameAs(before);
    JurisdictionDailyTax.Report report = collected.report();
    assertThat(report.isEmpty()).as("只有缺口时 Report 非空（缺口必须可见）").isFalse();
    assertThat(report.grain()).isEqualTo(JurisdictionDailyTax.Dimension.zero());
    assertThat(report.money()).isEqualTo(JurisdictionDailyTax.Dimension.zero());
    assertThat(report.unitsCharged()).isZero();
    assertThat(report.householdsCharged()).isZero();
    assertThat(report.gaps())
        .containsExactly(JurisdictionDailyTax.Gap.regionMissing(U1, GHOST, 300L));
  }

  // ── 手算逐值例 ─────────────────────────────────────────────────────────────

  /**
   * 装置（rate=1000‰、GOV efficiency=500‰）：
   *
   * <pre>
   * 户甲 @H1：粮 100（冻结 60 ⇒ 可支配 40）、钱 200、布 0（0 键必须保留）
   * 户乙 @H2：粮 300、钱 0（0 键必须保留）
   * 管辖：R1(1000‰) + r-ghost(300‰，图上不存在)；单位 u-1 在 H1
   * </pre>
   *
   * 手算：甲粮 assessed=floor(100×1000/1000)=100、attainable=floor(100×500/1000)=50、可支配 40 不够 ⇒
   * collected=min(50,40)=40、adminShortfall=50、stockShortfall=10；甲钱 200/100/100；乙粮 300/150/150； 乙钱余额
   * 0 ⇒ 全 0 跳过。合计：粮(400,190,200,10)、钱(200,100,100,0)、unitsCharged=1、
   * householdsCharged=2、缺口=REGION_MISSING(r-ghost,300)。
   */
  @Test
  void manualTwoHouseholdsOneUnitCaseMatchesHandComputedValues() {
    GameMap map = twoHexMap();

    Map<CommodityId, Long> aBalances = new LinkedHashMap<>();
    aBalances.put(GRAIN, 100L);
    aBalances.put(CLOTH, 0L);
    Map<CurrencyId, Long> aMoney = new LinkedHashMap<>();
    aMoney.put(SILVER, 200L);
    Map<CommodityId, Long> aFrozen = new LinkedHashMap<>();
    aFrozen.put(GRAIN, 60L);
    GoodsAccount accountA = new GoodsAccount(A_KEY, aBalances, aMoney, aFrozen, Map.of());

    Map<CommodityId, Long> bBalances = new LinkedHashMap<>();
    bBalances.put(GRAIN, 300L);
    Map<CurrencyId, Long> bMoney = new LinkedHashMap<>();
    bMoney.put(SILVER, 0L);
    GoodsAccount accountB = new GoodsAccount(B_KEY, bBalances, bMoney, Map.of(), Map.of());

    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, accountA);
    accounts.put(B_KEY, accountB);
    ActorData before = actor(accounts);

    // 税率表插入序故意与 id 序相反：r-ghost 在前、r-1 在后。
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(GHOST, 300L);
    rates.put(R1, 1_000L);
    Jurisdiction jurisdiction = new Jurisdiction(rates, 1_000L, 1_000L, 1_000L, 500L);
    UnitState units = unitsOf(unit(U1, Optional.of(H1), Optional.of(jurisdiction)));

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(before, units, map, TICK, Map.of(U1, 500L));
    ActorData after = collected.actor();
    JurisdictionDailyTax.Report report = collected.report();

    assertThat(after).as("有征收 ⇒ 换 accounts 组件的新实例").isNotSameAs(before);
    assertThat(after.meta()).as("meta 原样").isEqualTo(before.meta());
    assertThat(after.actors()).as("actors 原样").isEqualTo(before.actors());

    // Report 四量 × 两维 + 计数 + 缺口，逐项手算值。
    assertThat(report.grain())
        .as("粮：(assessed=400, collected=190, adminShortfall=200, stockShortfall=10)")
        .isEqualTo(new JurisdictionDailyTax.Dimension(400L, 190L, 200L, 10L));
    assertThat(report.money())
        .as("钱：(assessed=200, collected=100, adminShortfall=100, stockShortfall=0)")
        .isEqualTo(new JurisdictionDailyTax.Dimension(200L, 100L, 100L, 0L));
    assertThat(report.unitsCharged()).isEqualTo(1L);
    assertThat(report.householdsCharged()).isEqualTo(2L);
    assertThat(report.gaps())
        .as("r-ghost 不在图上 ⇒ 具名 REGION_MISSING 缺口（带税率 300‰）")
        .containsExactly(JurisdictionDailyTax.Gap.regionMissing(U1, GHOST, 300L));
    assertThat(
            report.grain().collected()
                + report.grain().adminShortfall()
                + report.grain().stockShortfall())
        .as("粮：collected + adminShortfall + stockShortfall == assessed")
        .isEqualTo(report.grain().assessed());
    assertThat(
            report.money().collected()
                + report.money().adminShortfall()
                + report.money().stockShortfall())
        .as("钱：collected + adminShortfall + stockShortfall == assessed")
        .isEqualTo(report.money().assessed());

    // 账键序：原有两本原序带过，新国库账追加在末尾。
    GoodsAccountKey treasuryKey = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, U1.value()), H1);
    assertThat(after.accounts().keySet())
        .as("账键序 = 原键序 + 首次创建的国库账")
        .containsExactly(A_KEY, B_KEY, treasuryKey);

    GoodsAccount taxedA = after.accounts().get(A_KEY);
    assertThat(taxedA.balances())
        .as("户甲粮 100−40=60；0 余额的布键原样保留")
        .containsEntry(GRAIN, 60L)
        .containsEntry(CLOTH, 0L);
    assertThat(taxedA.money()).as("户甲钱 200−100=100").containsEntry(SILVER, 100L);
    assertThat(taxedA.frozenBalances()).as("冻结表逐值不动").containsExactly(entry(GRAIN, 60L));
    assertThat(taxedA.frozenMoney()).isEmpty();

    GoodsAccount taxedB = after.accounts().get(B_KEY);
    assertThat(taxedB.balances()).as("户乙粮 300−150=150").containsEntry(GRAIN, 150L);
    assertThat(taxedB.money()).as("户乙钱 0 键保留（0 余额是事实，不归并）").containsEntry(SILVER, 0L);
    assertThat(taxedB.frozenBalances()).isEmpty();
    assertThat(taxedB.frozenMoney()).isEmpty();

    GoodsAccount treasury = after.accounts().get(treasuryKey);
    assertThat(treasury.balances()).as("国库粮 += 190").containsExactly(entry(GRAIN, 190L));
    assertThat(treasury.money()).as("国库钱 += 100").containsExactly(entry(SILVER, 100L));
    assertThat(treasury.frozenBalances()).as("五参新建 ⇒ 商品冻结表空").isEmpty();
    assertThat(treasury.frozenMoney()).as("五参新建 ⇒ 货币冻结表空").isEmpty();

    // 守恒式：Σ家户减少 == 国库增加（逐维；冻结不在减少项里）。
    long householdGrainReduction =
        (100L - taxedA.balances().get(GRAIN)) + (300L - taxedB.balances().get(GRAIN));
    long householdSilverReduction =
        (200L - taxedA.money().get(SILVER)) + (0L - taxedB.money().get(SILVER));
    assertThat(householdGrainReduction).as("Σ家户粮减少 == 税粮 190").isEqualTo(190L);
    assertThat(householdSilverReduction).as("Σ家户钱减少 == 税银 100").isEqualTo(100L);
    assertThat(treasury.balances().get(GRAIN))
        .as("国库粮增加 == Σ家户粮减少")
        .isEqualTo(householdGrainReduction);
    assertThat(treasury.money().get(SILVER))
        .as("国库钱增加 == Σ家户钱减少")
        .isEqualTo(householdSilverReduction);

    // 纯函数不写入参：调用后原账本逐值不动。
    assertThat(accountA.balances()).containsEntry(GRAIN, 100L).containsEntry(CLOTH, 0L);
    assertThat(accountA.money()).containsEntry(SILVER, 200L);
    assertThat(accountB.balances()).containsEntry(GRAIN, 300L);
    assertThat(accountB.money()).containsEntry(SILVER, 0L);
    assertThat(before.accounts()).hasSize(2);
  }

  // ── efficiency > 1000‰：adminShortfall 为负，恒等式仍成立 ─────────────────────────────

  /**
   * ★ efficiency=1100‰（超编加成）⇒ {@code attainable > assessed}、{@code adminShortfall} 为负；{@code
   * collected = min(attainable, available)} 仍逐值，恒等式 {@code collected + adminShortfall +
   * stockShortfall == assessed} 粮 / 钱各成立一次。
   *
   * <pre>
   * 户甲 @H1：粮 200（冻结 195 ⇒ 可支配 5）、钱 200（无冻结 ⇒ 可支配 200）；布 0 键保留。
   * 管辖 R1=50‰、GOV efficiency=1100‰。
   * 粮：assessed=floor(200×50/1000)=10、attainable=floor(10×1100/1000)=11、collected=min(11,5)=5
   *     ⇒ adminShortfall=10−11=−1、stockShortfall=11−5=6；5+(−1)+6=10。
   * 钱：assessed=10、attainable=11、collected=min(11,200)=11
   *     ⇒ adminShortfall=10−11=−1、stockShortfall=11−11=0；11+(−1)+0=10。
   * </pre>
   */
  @Test
  void efficiencyAboveOneThousandMakesAdminShortfallNegativeAndKeepsTheIdentity() {
    GameMap map = twoHexMap();

    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 200L);
    balances.put(CLOTH, 0L);
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, 200L);
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    frozenBalances.put(GRAIN, 195L);
    GoodsAccount accountA = new GoodsAccount(A_KEY, balances, money, frozenBalances, Map.of());
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, accountA);
    ActorData before = actor(accounts);

    Jurisdiction jurisdiction = new Jurisdiction(rateMap(R1, 50L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState units = unitsOf(unit(U1, Optional.of(H1), Optional.of(jurisdiction)));

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(before, units, map, TICK, Map.of(U1, 1_100L));
    ActorData after = collected.actor();
    JurisdictionDailyTax.Report report = collected.report();

    assertThat(after).as("有征收 ⇒ 换 accounts 组件的新实例").isNotSameAs(before);
    assertThat(report.grain())
        .as("粮：(assessed=10, collected=5, adminShortfall=−1, stockShortfall=6) 逐值")
        .isEqualTo(new JurisdictionDailyTax.Dimension(10L, 5L, -1L, 6L));
    assertThat(report.grain().adminShortfall())
        .as("粮 adminShortfall == assessed − attainable = 10 − 11 = −1（为负）")
        .isNegative()
        .isEqualTo(-1L);
    assertThat(
            report.grain().collected()
                + report.grain().adminShortfall()
                + report.grain().stockShortfall())
        .as("粮恒等式：collected + adminShortfall + stockShortfall == assessed")
        .isEqualTo(report.grain().assessed());

    assertThat(report.money())
        .as("钱：(assessed=10, collected=11, adminShortfall=−1, stockShortfall=0) 逐值")
        .isEqualTo(new JurisdictionDailyTax.Dimension(10L, 11L, -1L, 0L));
    assertThat(report.money().adminShortfall())
        .as("钱 adminShortfall == assessed − attainable = 10 − 11 = −1（为负）")
        .isNegative()
        .isEqualTo(-1L);
    assertThat(
            report.money().collected()
                + report.money().adminShortfall()
                + report.money().stockShortfall())
        .as("钱恒等式：collected + adminShortfall + stockShortfall == assessed")
        .isEqualTo(report.money().assessed());

    assertThat(report.unitsCharged()).as("恰一个单位被征").isEqualTo(1L);
    assertThat(report.householdsCharged()).as("恰一户被征").isEqualTo(1L);
    assertThat(report.gaps()).as("本用例无具名缺口").isEmpty();

    // 家户 / 国库两侧按 collected 逐值。
    GoodsAccount taxed = after.accounts().get(A_KEY);
    assertThat(taxed.balances())
        .as("户甲粮 200−5=195；布 0 键原样保留")
        .containsEntry(GRAIN, 195L)
        .containsEntry(CLOTH, 0L);
    assertThat(taxed.money()).as("户甲钱 200−11=189").containsEntry(SILVER, 189L);
    assertThat(taxed.frozenBalances()).as("冻结表逐值不动").containsExactly(entry(GRAIN, 195L));
    assertThat(taxed.frozenMoney()).isEmpty();

    GoodsAccountKey treasuryKey = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, U1.value()), H1);
    GoodsAccount treasury = after.accounts().get(treasuryKey);
    assertThat(treasury.balances()).as("国库粮 += collected=5").containsExactly(entry(GRAIN, 5L));
    assertThat(treasury.money()).as("国库钱 += collected=11").containsExactly(entry(SILVER, 11L));
    assertThat(treasury.frozenBalances()).as("新建国库账：商品冻结表空").isEmpty();
    assertThat(treasury.frozenMoney()).as("新建国库账：货币冻结表空").isEmpty();

    long grainReduction = 200L - taxed.balances().get(GRAIN);
    long silverReduction = 200L - taxed.money().get(SILVER);
    assertThat(grainReduction).as("Σ家户粮减少 == collected 5").isEqualTo(report.grain().collected());
    assertThat(silverReduction).as("Σ家户钱减少 == collected 11").isEqualTo(report.money().collected());
    assertThat(treasury.balances().get(GRAIN)).as("国库粮增加 == Σ家户粮减少").isEqualTo(grainReduction);
    assertThat(treasury.money().get(SILVER)).as("国库钱增加 == Σ家户钱减少").isEqualTo(silverReduction);
  }

  // ── NO_POSITION ────────────────────────────────────────────────────────────

  @Test
  void noPositionReturnsSameInstanceWithGapAndNoCharge() {
    GameMap map = twoHexMap();
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, account(HH_A, H1, 100L, 100L));
    ActorData before = actor(accounts);

    Jurisdiction jurisdiction = new Jurisdiction(rateMap(R1, 200L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState units = unitsOf(unit(U1, Optional.empty(), Optional.of(jurisdiction)));

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(before, units, map, TICK, Map.of(U1, 1_000L));

    assertThat(collected.actor()).as("无有效位置 ⇒ 不征、同实例").isSameAs(before);
    JurisdictionDailyTax.Report report = collected.report();
    assertThat(report.grain()).isEqualTo(JurisdictionDailyTax.Dimension.zero());
    assertThat(report.money()).isEqualTo(JurisdictionDailyTax.Dimension.zero());
    assertThat(report.unitsCharged()).isZero();
    assertThat(report.householdsCharged()).isZero();
    assertThat(report.gaps())
        .as("NO_POSITION 是单位级缺口：number = 因此跳过的既有区域条数")
        .containsExactly(
            new JurisdictionDailyTax.Gap(
                JurisdictionDailyTax.GapKind.NO_POSITION, U1.value(), null, 1L));
  }

  // ── 重叠管辖 ───────────────────────────────────────────────────────────────

  @Test
  void overlappingJurisdictionsChargeInUnitIdOrderAndSecondSeesTaxedBalance() {
    GameMap map = twoHexMap();
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, account(HH_A, H1, 1_000L, 1_000L));
    ActorData before = actor(accounts);

    Jurisdiction rate = new Jurisdiction(rateMap(R1, 100L), 1_000L, 1_000L, 1_000L, 1_000L);
    Unit first = unit(new UnitId("a-unit"), Optional.of(H1), Optional.of(rate));
    Unit second = unit(new UnitId("b-unit"), Optional.of(H2), Optional.of(rate));
    // 插入序故意反着来：应仍按 UnitId.value() 升序征（a-unit 先、b-unit 后见税后余额）。
    UnitState units = unitsOf(second, first);

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(
            before,
            units,
            map,
            TICK,
            Map.of(new UnitId("a-unit"), 1_000L, new UnitId("b-unit"), 1_000L));
    ActorData after = collected.actor();
    JurisdictionDailyTax.Report report = collected.report();

    // 家户粮/钱各 1000：a-unit 抽 100 ⇒ 900；b-unit 见 900 抽 90 ⇒ 810。
    assertThat(report.grain()).isEqualTo(new JurisdictionDailyTax.Dimension(190L, 190L, 0L, 0L));
    assertThat(report.money()).isEqualTo(new JurisdictionDailyTax.Dimension(190L, 190L, 0L, 0L));
    assertThat(report.unitsCharged()).isEqualTo(2L);
    assertThat(report.householdsCharged()).isEqualTo(1L);
    assertThat(report.gaps()).isEmpty();

    GoodsAccountKey treasuryOfA = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, "a-unit"), H1);
    GoodsAccountKey treasuryOfB = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, "b-unit"), H2);
    assertThat(after.accounts().keySet())
        .as("国库账按处理序（= unitId 升序）首次创建")
        .containsExactly(A_KEY, treasuryOfA, treasuryOfB);

    GoodsAccount household = after.accounts().get(A_KEY);
    assertThat(household.balances()).containsEntry(GRAIN, 810L);
    assertThat(household.money()).containsEntry(SILVER, 810L);
    assertThat(after.accounts().get(treasuryOfA).balances()).containsEntry(GRAIN, 100L);
    assertThat(after.accounts().get(treasuryOfB).balances()).containsEntry(GRAIN, 90L);
    assertThat(after.accounts().get(treasuryOfA).money()).containsEntry(SILVER, 100L);
    assertThat(after.accounts().get(treasuryOfB).money()).containsEntry(SILVER, 90L);
  }

  // ── 确定性 ─────────────────────────────────────────────────────────────────

  @Test
  void sameInputIsStableAndAccountUnitInsertionOrderDoesNotMatter() {
    GameMap map = twoHexMapWithIdleRegion(false);

    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, account(HH_A, H1, 500L, 300L));
    accounts.put(B_KEY, account(HH_B, H2, 700L, 0L));
    ActorData before = actor(accounts);

    Jurisdiction rateA = new Jurisdiction(rateMap(R1, 125L), 1_000L, 1_000L, 1_000L, 1_000L);
    Jurisdiction rateB = new Jurisdiction(rateMap(R1, 75L), 1_000L, 1_000L, 1_000L, 1_000L);
    Unit unitA = unit(new UnitId("a-unit"), Optional.of(H1), Optional.of(rateA));
    Unit unitB = unit(new UnitId("b-unit"), Optional.of(H2), Optional.of(rateB));
    UnitState units = unitsOf(unitB, unitA);

    JurisdictionDailyTax.Collected once =
        JurisdictionDailyTax.collect(
            before,
            units,
            map,
            3L,
            Map.of(new UnitId("a-unit"), 1_000L, new UnitId("b-unit"), 1_000L));
    JurisdictionDailyTax.Collected twice =
        JurisdictionDailyTax.collect(
            before,
            units,
            map,
            3L,
            Map.of(new UnitId("a-unit"), 1_000L, new UnitId("b-unit"), 1_000L));
    assertThat(twice.report()).as("同输入两次 Report 逐字段相等").isEqualTo(once.report());
    assertThat(twice.actor()).as("同输入两次 ActorData 逐字段相等").isEqualTo(once.actor());
    assertThat(new ArrayList<>(twice.actor().accounts().keySet()))
        .as("同输入两次账键序也相同")
        .isEqualTo(new ArrayList<>(once.actor().accounts().keySet()));

    // 打乱插入序：区域表倒序、账户表倒序、单位表正序、税率表换序。
    GameMap shuffledMap = twoHexMapWithIdleRegion(true);
    Map<GoodsAccountKey, GoodsAccount> shuffledAccounts = new LinkedHashMap<>();
    shuffledAccounts.put(B_KEY, account(HH_B, H2, 700L, 0L));
    shuffledAccounts.put(A_KEY, account(HH_A, H1, 500L, 300L));
    Map<RegionId, Long> shuffledRates = new LinkedHashMap<>();
    shuffledRates.put(R1, 125L);
    Jurisdiction shuffledRateA = new Jurisdiction(shuffledRates, 1_000L, 1_000L, 1_000L, 1_000L);
    Unit shuffledUnitA = unit(new UnitId("a-unit"), Optional.of(H1), Optional.of(shuffledRateA));
    JurisdictionDailyTax.Collected shuffled =
        JurisdictionDailyTax.collect(
            actor(shuffledAccounts),
            unitsOf(shuffledUnitA, unitB),
            shuffledMap,
            3L,
            Map.of(new UnitId("a-unit"), 1_000L, new UnitId("b-unit"), 1_000L));

    assertThat(shuffled.report()).as("区域/账户/单位/税率表插入序不影响 Report（含缺口表序）").isEqualTo(once.report());
    assertThat(shuffled.actor()).as("结果按内容相等（与 Map 插入序无关）").isEqualTo(once.actor());
    assertThat(shuffled.actor().accounts().get(A_KEY))
        .isEqualTo(once.actor().accounts().get(A_KEY));
    assertThat(shuffled.actor().accounts().get(B_KEY))
        .isEqualTo(once.actor().accounts().get(B_KEY));
  }

  // ── 已有国库账：保留两张冻结表 ──────────────────────────────────────────────

  @Test
  void existingTreasuryAccountKeepsItsFrozenTablesAndAccumulates() {
    GameMap map = twoHexMap();
    GoodsAccountKey treasuryKey = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, U1.value()), H1);

    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 7L);
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, 3L);
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    frozenBalances.put(GRAIN, 2L);
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(SILVER, 1L);
    GoodsAccount treasury =
        new GoodsAccount(treasuryKey, balances, money, frozenBalances, frozenMoney);

    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, account(HH_A, H1, 100L, 0L));
    accounts.put(treasuryKey, treasury);
    ActorData before = actor(accounts);

    Jurisdiction jurisdiction = new Jurisdiction(rateMap(R1, 100L), 1_000L, 1_000L, 1_000L, 1_000L);
    UnitState units = unitsOf(unit(U1, Optional.of(H1), Optional.of(jurisdiction)));

    JurisdictionDailyTax.Collected collected =
        JurisdictionDailyTax.collect(before, units, map, TICK, Map.of(U1, 1_000L));
    GoodsAccount afterTreasury = collected.actor().accounts().get(treasuryKey);

    assertThat(collected.actor().accounts().get(A_KEY).balances()).containsEntry(GRAIN, 90L);
    assertThat(afterTreasury.balances()).as("已有国库账：余额 7+10=17").containsExactly(entry(GRAIN, 17L));
    assertThat(afterTreasury.money()).containsExactly(entry(SILVER, 3L));
    assertThat(afterTreasury.frozenBalances())
        .as("已有国库账的商品冻结表原样保留")
        .containsExactly(entry(GRAIN, 2L));
    assertThat(afterTreasury.frozenMoney())
        .as("已有国库账的货币冻结表原样保留")
        .containsExactly(entry(SILVER, 1L));
    assertThat(collected.actor().accounts().keySet())
        .as("已有国库账保持原键位（不追加到末尾）")
        .containsExactly(A_KEY, treasuryKey);
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────

  /** 无征税/缺口必须返回入参同一实例且 Report 为空。 */
  private static void assertNoOp(
      ActorData before, JurisdictionDailyTax.Collected result, String tag) {
    assertThat(result.actor()).as("%s：必须返回入参同一 ActorData 实例", tag).isSameAs(before);
    assertThat(result.report().isEmpty()).as("%s：Report.isEmpty()", tag).isTrue();
  }

  private static Map<GoodsAccountKey, GoodsAccount> accountsWithAAndB() {
    Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    accounts.put(A_KEY, account(HH_A, H1, 100L, 50L));
    accounts.put(B_KEY, account(HH_B, H2, 300L, 20L));
    return accounts;
  }

  private static ActorData actor(Map<GoodsAccountKey, GoodsAccount> accounts) {
    Map<ActorRef, Actor> actors = new LinkedHashMap<>();
    actors.put(HH_A, new Actor(HH_A, "家户甲"));
    actors.put(HH_B, new Actor(HH_B, "家户乙"));
    return new ActorData(
        Optional.of(new ActorMeta("tax-test-map", 0L, "tax-test-rules")), actors, accounts);
  }

  /** 一本账：指定粮/钱余额，无冻结。 */
  private static GoodsAccount account(ActorRef owner, HexCoord at, long grain, long silver) {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, grain);
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, silver);
    return new GoodsAccount(new GoodsAccountKey(owner, at), balances, money, Map.of(), Map.of());
  }

  private static Map<RegionId, Long> rateMap(RegionId region, long ratePerMille) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(region, ratePerMille);
    return rates;
  }

  private static GameMap twoHexMap() {
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(R1, Region.of(R1, "甲区", Set.of(H1, H2), RegionMeta.empty()));
    return mapOf(Set.of(H1, H2), regions);
  }

  /** 在两张图的语义相同（闲置区 R-IDLE 无管辖、无账户）下，把 {@code regions} 的插入序正反各造一张。 */
  private static GameMap twoHexMapWithIdleRegion(boolean idleFirst) {
    RegionId idleId = new RegionId("r-idle");
    Region idle = Region.of(idleId, "闲置区", Set.of(H3), RegionMeta.empty());
    Region taxed = Region.of(R1, "甲区", Set.of(H1, H2), RegionMeta.empty());
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    if (idleFirst) {
      regions.put(idleId, idle);
      regions.put(R1, taxed);
    } else {
      regions.put(R1, taxed);
      regions.put(idleId, idle);
    }
    return mapOf(Set.of(H1, H2, H3), regions);
  }

  private static GameMap mapOf(Set<HexCoord> hexes, Map<RegionId, Region> regions) {
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(HexCoord::compareTo);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    for (HexCoord hex : ordered) {
      cells.put(hex, new HexCell(0.5));
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(hexes, "plains"),
        regions,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static UnitState unitsOf(Unit... units) {
    Map<UnitId, Unit> map = new LinkedHashMap<>();
    for (Unit unit : units) {
      map.put(unit.id(), unit);
    }
    return new UnitState(map);
  }

  private static Unit unit(
      UnitId id, Optional<HexCoord> position, Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction);
  }
}
