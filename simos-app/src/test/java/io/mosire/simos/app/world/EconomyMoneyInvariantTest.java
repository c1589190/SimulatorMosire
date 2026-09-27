package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>H6：app 侧"钱"的两条真档判据 —— Σ货币恒定 + 逐本落盘一致</b>。
 *
 * <p>★★ <b>为什么需要本文件</b>（H5 收口时如实记下的缺口）：经济侧 H0–H5 六批改造之后，货币是<b>创世禀赋</b>、世界上<b>没有发行路径</b> （{@code
 * MoneyAuthority} 只有接口、零实现者）⇒ <b>逐币种总量恒定</b>是硬判据；而 app 侧两次落账的唯一入口是 {@link OwnershipBooks}（{@code
 * loadHouseholdMoney} / {@code landHouseholdMoney} / {@code loadOperatorMoney} / {@code
 * landOperatorMoney}），协调器是 {@code PopulationEconomyTimeParticipant} / {@code
 * EconomyOwnershipTimeParticipant}。此前"钱"只由**一次性探针**核过，真档夹具**不量钱** —— 本文件把那条判据变成常驻护栏。
 *
 * <p>★★ <b>为什么必须是真档规模</b>（同 {@link EconomyRealScaleClothTest} 的理由）：织机数、作坊数、口粮、货币禀赋全都按人口派生 ⇒ 5
 * 格小夹具上的钱证明不了真档的任何事。本文件用**真播种器载荷**（{@link EconomySeeder#payload} → 真 {@link EconomySeedHandler}）造一格
 * 14,806 人的平原格 + 一座 1,777 人的城，家户账与经营者账都从**同一份 plan** 建（★ {@link HouseholdSeeder#books(Map, Map,
 * List)} 那个**三参**重载 —— 两参重载不播经营者账），再用**真协调器**（{@link EconomyOwnershipFixture#advance}）推 3 个周期（360
 * 天）。
 *
 * <p>★★ <b>判据一览（逐条对着一种"真的会坏"的实现）</b>：
 *
 * <ol>
 *   <li><b>Σ货币恒定</b>（逐币种、逐周期）：创世 Σ == 每个周期末的 Σ。判别力 = 任何"凭空铸钱"或"静默抹钱"的落账 （例如用 {@code GoodsAccount}
 *       的两参构造器写回、把货币表清零；或漏掉经营者那一侧的落回）；<b>非平凡</b>由"创世量 = 人口 × 每人出厂值 + 经营者钱包"这一条独立算式钉住（不是"0 == 0"）。
 *   <li><b>逐本落盘一致</b>：账本**一本不多、一本不少**（键集 == 家户行集 ∪ 经营者集 —— "落错键"会当场冒出一本幻影账）；没有任何一本账为负；
 *       家户侧与经营者侧的**总和**与"跨侧唯一那条腿"逐值相符（见下），且两侧之和 == 创世总量 ⇒ "落回"没有丢、也没有凭空加。
 *   <li><b>钱真的动了</b>：真档世界有 {@code markets} 载荷；3 个周期后 Σ 恒定，且**至少有一本家户账 ≠ 它的创世值**（买方付出 / 卖方收进 /
 *       城镇家户领到工钱）。
 *   <li><b>计价货币自洽</b>：格级 {@code market.numeraire} 必须出现在该格钱的币种集合里（{@code ApiViews.economyHex} 的
 *       {@code actorMoneyTotal} 的键）。
 * </ol>
 *
 * <p>★★ <b>"家户侧与经营者侧分别守恒"的准确形态（实测口径，别照抄直觉）</b>：本世界里两侧**不是各自恒定**的 —— {@code handicraft} 的四条 {@code
 * FIXED_MONEY_WAGE} 把工钱从**经营者**搬到**家户**（{@code Transfer.from = relation.operator()}）⇒ 家户侧 Σ 上浮、经营者侧
 * Σ 下沉， 而**两者之和**逐币种恒定。故本文件的断言是：① 两侧之和 == 创世总量；② 经营者侧只减不增、家户侧只增不减（本世界没有反向的货币腿：市场那两条腿是 **家户 ↔
 * 家户**）；③ 家户侧多出来的那部分**逐值等于**经营者侧少掉的那部分（"落回"丢一笔或多一笔，这三条当场红）。
 */
class EconomyMoneyInvariantTest {

  /** 真档每格人口（11,830,000 ÷ 799，与 {@code EconomyRealScaleClothTest} 同口径）。 */
  private static final long POPULATION_PER_HEX = 11_830_000L / 799L;

  /** 该格的城市人口（城市占总人口约 12%，取真档报告里的量级）。 */
  private static final long CITY_POPULATION = 1_777L;

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String MAP_ID = "econ-money";

  /** 本世界的计价货币（唯一拼写点 = {@code EconomySeeder.MARKET_NUMERAIRE}，夹具不复述 "silver" 字面量）。 */
  private static final CurrencyId SILVER = EconomySeeder.MARKET_NUMERAIRE;

  /** 一个周期 / 三个周期（日制，与 {@code EconomyRealScaleClothTest} 同口径）。 */
  private static final long CYCLE_DAYS = EconomySeeder.CYCLE_DAYS;

  private static final long ALL_DAYS = CYCLE_DAYS * 3L;

  // ── 夹具（@BeforeAll 跑一次：真档 360 天只推一遍，四条判据共用同一份世界）────────────────────────

  /** 创世（播种后、推进前）的两片。 */
  private static EconomyData genesisEconomy;

  private static ActorData genesisBooks;

  /** 创世时**经营者侧**的 Σ 银（来自同一份 {@code Seed.operators()}，不是从账本反推）。 */
  private static long genesisOperatorSilver;

  /** 创世时**家户侧**的 Σ 银（= 人口 × {@link EconomySeeder#genesisMoneyMilliPerCapita()}）。 */
  private static long genesisHouseholdSilver;

  private static EconomyOwnershipFixture.Result cycle1;
  private static EconomyOwnershipFixture.Result cycle2;
  private static EconomyOwnershipFixture.Result cycle3;

  @BeforeAll
  static void seedRealScaleWorldAndAdvanceThreeCycles() {
    SettlementPlan plan =
        new SettlementPlan(
            Map.of(HEX, POPULATION_PER_HEX),
            List.of(
                new PlannedCity(
                    "c-0_0",
                    "c-0_0",
                    HEX,
                    PlannedCity.TIER_TOWN,
                    CITY_POPULATION,
                    1,
                    0.0,
                    1.0,
                    1.0,
                    "test")),
            Map.of(),
            250L,
            0L);
    String payload =
        EconomySeeder.payload(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    SimulationState emptyState =
        new SimulationState(
            stateMeta(), snapshots(EconomyData.empty()), InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(emptyState, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    genesisEconomy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());

    // ★★ H1/H4/H5：家户账 + 经营者账都由**同一份 plan** 建（真路径里这是同批的第二条命令 actor.Seed）。
    //   ★★ 必须用**三参**重载（带 operators）：两参重载播出的世界里经营者一本账都没有 ⇒ "经营者侧"整条判据会退化成 0 == 0。
    EconomySeeder.Seed seeding =
        EconomySeeder.plan(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    genesisBooks =
        HouseholdSeeder.books(
            seeding.householdStocks(), seeding.householdMoney(), seeding.operators());

    long people = POPULATION_PER_HEX + CITY_POPULATION;
    genesisHouseholdSilver = people * EconomySeeder.genesisMoneyMilliPerCapita();
    genesisOperatorSilver = 0L;
    for (EconomySeeder.OperatorSeed operator : seeding.operators()) {
      genesisOperatorSilver += operator.money().getOrDefault(SILVER, 0L);
    }

    // ★ 逐周期串联推进（存量口径：拿相邻两个时点做差才量得出"这一周期动了多少"）。
    cycle1 = EconomyOwnershipFixture.advance(genesisEconomy, genesisBooks, MAP_ID, 0L, CYCLE_DAYS);
    cycle2 =
        EconomyOwnershipFixture.advance(
            cycle1.economy(), cycle1.actor(), MAP_ID, CYCLE_DAYS, CYCLE_DAYS * 2L);
    cycle3 =
        EconomyOwnershipFixture.advance(
            cycle2.economy(), cycle2.actor(), MAP_ID, CYCLE_DAYS * 2L, ALL_DAYS);

    // ── 探针（读数用；定稿时把真数写进断言）──────────────────────────────────────────────
    System.out.println("== H6 探针：货币 ==");
    System.out.println("创世 Σ银 = " + moneyTotals(genesisBooks));
    System.out.println(
        "  其中 家户侧 = "
            + genesisHouseholdSilver
            + "（人口 "
            + people
            + " × "
            + EconomySeeder.genesisMoneyMilliPerCapita()
            + "）；经营者侧 = "
            + genesisOperatorSilver
            + "；账本数 = "
            + genesisBooks.accounts().size());
    probe("创世", genesisEconomy, genesisBooks);
    probe("第1周期末", cycle1.economy(), cycle1.actor());
    probe("第2周期末", cycle2.economy(), cycle2.actor());
    probe("第3周期末", cycle3.economy(), cycle3.actor());
  }

  private static void probe(String when, EconomyData economy, ActorData books) {
    List<String> down = new ArrayList<>();
    List<String> up = new ArrayList<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      GoodsAccountKey key = OwnershipBooks.accountKeyOf(cohort);
      GoodsAccount account = books.accounts().get(key);
      GoodsAccount opening = genesisBooks.accounts().get(key);
      if (account == null || opening == null) {
        continue;
      }
      long now = account.money().getOrDefault(SILVER, 0L);
      long was = opening.money().getOrDefault(SILVER, 0L);
      if (now < was) {
        down.add(cohort + "=" + now + "(创世 " + was + ")");
      } else if (now > was) {
        up.add(cohort + "=" + now + "(创世 " + was + ")");
      }
    }
    System.out.println(
        "["
            + when
            + "] Σ银="
            + moneyTotals(books)
            + " | 家户侧="
            + silverOf(householdAccounts(economy, books))
            + " 经营者侧="
            + silverOf(operatorAccounts(economy, books))
            + " | 变少的家户="
            + down.size()
            + " "
            + (down.size() > 4 ? down.subList(0, 4) + " …" : down)
            + " | 变多的家户="
            + up.size()
            + " "
            + (up.size() > 4 ? up.subList(0, 4) + " …" : up));
  }

  // ── ① Σ货币恒定（逐币种、3 个周期都核）──────────────────────────────────────────────

  /** ★★ <b>创世量 = 人口 × 每人出厂值 + 经营者钱包</b>（独立算式）—— 它同时是"非平凡"的护栏：Σ 不是 0，也不是"碰巧相等"。 */
  @Test
  void genesisMoneyEqualsTheSeederFormulaNotJustItsOwnBooks() {
    assertThat(moneyTotals(genesisBooks))
        .as("★ 创世的钱只有计价货币那一种（逐币种表）")
        .containsOnlyKeys(SILVER.value());
    assertThat(moneyTotals(genesisBooks).get(SILVER.value()))
        .as(
            "★ 独立算式：人口 %d × 每人 %d 毫 + 经营者钱包 %d",
            POPULATION_PER_HEX + CITY_POPULATION,
            EconomySeeder.genesisMoneyMilliPerCapita(),
            genesisOperatorSilver)
        .isEqualTo(genesisHouseholdSilver + genesisOperatorSilver);
    assertThat(genesisHouseholdSilver)
        .as("★ 非平凡下限：家户侧真有一大笔钱（不是 0）")
        .isEqualTo((POPULATION_PER_HEX + CITY_POPULATION) * 12L);
    assertThat(genesisOperatorSilver).as("★ 非平凡下限：经营者侧也有钱（作坊的货币工资储备；工钱只能从这本账出）").isEqualTo(4_800L);
  }

  /**
   * ★★ <b>逐币种总量恒定</b>：{@code Σ(所有 GoodsAccount.money[C])} 在创世与 3 个周期末**逐值相同**。
   *
   * <p>判别力：任何"造钱 / 抹钱"的落账都会当场红 —— 例如用两参 {@code GoodsAccount} 写回（钱清零）、只落家户那一侧、或把工钱的货币腿
   * 既折成条目又按绝对值落一遍。★ 本批没有发行人（{@code MoneyAuthority} 零实现者）⇒ 这条是硬判据，不是"最好如此"。
   */
  @Test
  void moneyTotalPerCurrencyIsConstantThroughAllThreeCycles() {
    Map<String, Long> genesis = moneyTotals(genesisBooks);
    assertThat(moneyTotals(cycle1.actor())).as("第 1 个周期末（120 天）").isEqualTo(genesis);
    assertThat(moneyTotals(cycle2.actor())).as("第 2 个周期末（240 天）").isEqualTo(genesis);
    assertThat(moneyTotals(cycle3.actor())).as("第 3 个周期末（360 天）").isEqualTo(genesis);
    assertThat(genesis.get(SILVER.value())).as("★ 非平凡：恒定的那个数不是 0").isEqualTo(203_796L);
  }

  // ── ② 逐本落盘一致 ─────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>逐本落盘一致</b>：3 个周期之后，账本**一本不多、一本不少**、没有一本为负，且家户侧 / 经营者侧的总和与"跨侧唯一那条腿"逐值相符。
   *
   * <p>★★ <b>为什么断言的是"两侧之和"而不是"两侧各自恒定"</b>（实测口径）：{@code handicraft} 的 {@code FIXED_MONEY_WAGE}
   * 把工钱从经营者搬到受方 cohort（{@code Transfer.from = relation.operator()}）⇒
   * 家户侧**上浮**、经营者侧**下沉**。故"分别守恒"的准确形态是： ① 两侧之和 == 创世总量（落回没丢、没凭空加）；② 方向只能是 经营者 → 家户（本世界没有反向货币腿：市场是家户
   * ↔ 家户）；③ 家户多出来的 == 经营者少掉的。
   */
  @Test
  void everyBookLandsItsOwnMoneyWithoutLosingOrMintingAny() {
    for (EconomyOwnershipFixture.Result result : List.of(cycle1, cycle2, cycle3)) {
      EconomyData economy = result.economy();
      ActorData books = result.actor();
      Map<GoodsAccountKey, GoodsAccount> households = householdAccounts(economy, books);
      Map<GoodsAccountKey, GoodsAccount> operators = operatorAccounts(economy, books);

      // ① 账本**一本不多、一本不少**：落错键会冒出一本幻影账（原账还留着创世值），落漏一本会少一本。
      assertThat(books.accounts().keySet())
          .as("★ 账本键集 == 家户行集 ∪ 经营者集（没有幻影账、也没有掉的账）")
          .containsExactlyInAnyOrderElementsOf(expectedAccountKeys(economy));

      // ② 没有任何一本账出现负数（家户 + 经营者，**逐本**查，不看总和）。
      assertThat(negativeBalances(books))
          .as("★ 逐本非负：透支是信用，不是库存/货币（%d 本账）", books.accounts().size())
          .isEmpty();

      // ③ 两侧的总和逐值相符（落回没有丢、也没有凭空加）。
      long householdSide = silverOf(households);
      long operatorSide = silverOf(operators);
      assertThat(householdSide + operatorSide)
          .as("★ 家户侧 + 经营者侧 == 创世总量（逐币种守恒落到每一侧）")
          .isEqualTo(genesisHouseholdSilver + genesisOperatorSilver);
      assertThat(operatorSide)
          .as("★ 经营者侧只减不增（唯一跨侧的腿是工钱，方向 经营者 → 家户）")
          .isBetween(0L, genesisOperatorSilver);
      assertThat(householdSide)
          .as("★ 家户侧只增不减（市场那两条腿是家户 ↔ 家户 ⇒ 侧内相消）")
          .isGreaterThanOrEqualTo(genesisHouseholdSilver);
      assertThat(householdSide - genesisHouseholdSilver)
          .as("★ 家户侧多出来的那部分**逐值等于**经营者侧少掉的那部分（丢一笔/多一笔 ⇒ 当场红）")
          .isEqualTo(genesisOperatorSilver - operatorSide);
    }

    // ④ 非平凡：至少有一本账**真的变了**（否则"市场没成交"会让上面三条假绿）。
    List<CohortKey> changedHouseholds = changedHouseholds(cycle3.economy(), cycle3.actor());
    assertThat(changedHouseholds).as("★ 至少一本家户账的钱 ≠ 创世值（本判据的判别力全靠它）").isNotEmpty();
    assertThat(operatorChanged(cycle3.economy(), cycle3.actor()))
        .as("★ 经营者那一侧也真的动过（工钱付出去了 —— 否则'经营者侧只减不增'是空转）")
        .isTrue();
  }

  // ── ③ 钱真的动了（跨家户）──────────────────────────────────────────────────────────

  /**
   * ★★ <b>钱真的动了，而且是在家户之间搬</b>：真档世界有 {@code markets} 载荷，3 个周期后 Σ 恒定，且既**有家户变少**（买方付出货款）又
   * **有家户变多**（卖方收进货款 / 城镇家户领到工钱）。
   *
   * <p>★ 判别力：把市场的货币腿接反（买方收货却不付钱）、或让落账把某一侧按"只增不减"处理 ⇒ 这条红；把 Σ 恒定那条一起读，就排除了"总的没变但账记错了"。
   */
  @Test
  void moneyMovesBetweenHouseholdsWhileItsTotalStaysConstant() {
    assertThat(genesisEconomy.markets())
        .as("★ 真档世界确实播出了 markets 载荷（否则'会开市'这个前提就是假的）")
        .containsKey(HEX);
    assertThat(genesisEconomy.markets().get(HEX).numeraire()).isEqualTo(SILVER);

    List<CohortKey> down = new ArrayList<>();
    List<CohortKey> up = new ArrayList<>();
    for (CohortKey cohort : cycle3.economy().classes().keySet()) {
      GoodsAccount before = genesisBooks.accounts().get(OwnershipBooks.accountKeyOf(cohort));
      GoodsAccount after = cycle3.actor().accounts().get(OwnershipBooks.accountKeyOf(cohort));
      long was = before == null ? 0L : before.money().getOrDefault(SILVER, 0L);
      long now = after == null ? 0L : after.money().getOrDefault(SILVER, 0L);
      if (now < was) {
        down.add(cohort);
      } else if (now > was) {
        up.add(cohort);
      }
    }
    assertThat(down).as("★ 至少一本家户账**少了钱**（有人真的掏了货款 —— '钱动了'的付方那一半）").isNotEmpty();
    assertThat(up).as("★ 至少一本家户账**多了钱**（收方那一半）").isNotEmpty();
    assertThat(moneyTotals(cycle3.actor()))
        .as("★ 而且总账一分不多、一分不少")
        .isEqualTo(moneyTotals(genesisBooks));
  }

  // ── ④ 计价货币自洽（格级市场 ↔ 该格钱的币种集合）──────────────────────────────────────

  /**
   * ★★ <b>格级 {@code market.numeraire} 必须出现在该格钱的币种集合里</b>（{@code ApiViews.economyHex} 的 {@code
   * actorMoneyTotal} 的键）。
   *
   * <p>★ 判别力：计价货币是**价格表的分母**（"多少钱一单位"），若这一格根本没有那种钱，价表与账本就在两个世界里 —— 这条读的是**真读口**，
   * 所以顺带钉住"视图那一栏真的读到了钱"。
   */
  @Test
  void theHexMoneyCurrenciesCoverTheMarketNumeraire() {
    Market market = cycle3.economy().markets().get(HEX);
    assertThat(market).as("这一格有市场").isNotNull();

    Map<String, Object> view = ApiViews.economyHex(HEX, cycle3.economy(), cycle3.actor());
    assertThat(view.containsKey("money"))
        .as(
            "★★ H6：格级那个读 {@code Σ ClassRow.money()}（结构性 0）的旧栏**已删** —— 钱的真值是逐币种的 {@code actorMoneyTotal}")
        .isFalse();
    Object raw = view.get("actorMoneyTotal");
    assertThat(raw).as("★ 真值那一栏在场（删旧栏不是把钱的读数删掉）").isInstanceOf(Map.class);
    // ★ 键集要**显式收成 `Set<String>`** 再断言：`Map<?, ?>#keySet()` 是 `Set<?>`，AssertJ 的
    //   `contains(ELEMENT...)` 在通配符捕获下会退化成 `contains(capture#1 of ?...)` ⇒ **编译不过**
    //   （实测两次：`varargs mismatch; String cannot be converted to capture#1 of ?`）；
    //   栏的语义就是"币种名 → 金额"，收窄是**读口自己的口径**，断言内容一个字没改。
    Map<String, Object> actorMoneyTotal = asStringKeyedMap(raw);
    assertThat(actorMoneyTotal.keySet())
        .as("★ 该格钱的币种集合必须含该格的计价货币")
        .contains(market.numeraire().value());
    assertThat(((Number) actorMoneyTotal.get(market.numeraire().value())).longValue())
        .as("★ 非平凡：这一格真的有钱（0 也'包含'不了这个键的语义）")
        .isPositive();
  }

  /**
   * 把 {@code actorMoneyTotal} 那一栏收成 {@code Map<String, Object>}（★ 见调用处注释：通配符捕获会让 AssertJ
   * 的变参断言编译不过）。
   *
   * <p>★ 逐键**不是 String 就当场失败**（不做静默丢键）：这一栏的键按写入侧就是"币种名"（{@code Map<String, Long>}），
   * 出现别的类型说明读口换了形状，那时本用例该红、而不是被这条收窄悄悄滤掉。
   */
  private static Map<String, Object> asStringKeyedMap(Object raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
      assertThat(entry.getKey())
          .as("★ actorMoneyTotal 的键必须是币种名（String）")
          .isInstanceOf(String.class);
      out.put((String) entry.getKey(), entry.getValue());
    }
    return out;
  }

  // ── 读数 ────────────────────────────────────────────────────────────────────────────

  /** Σ 全部账本（家户 + 经营者）的逐币种余额 —— 守恒式左边。 */
  private static Map<String, Long> moneyTotals(ActorData books) {
    Map<String, Long> totals = new TreeMap<>();
    for (GoodsAccount account : books.accounts().values()) {
      for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
        totals.merge(entry.getKey().value(), entry.getValue(), Long::sum);
      }
    }
    return totals;
  }

  /** 账本的**期望键集**：家户行集（经 {@link OwnershipBooks#accountKeyOf}，不复述 key 的形状）∪ 经营者集。 */
  private static List<GoodsAccountKey> expectedAccountKeys(EconomyData economy) {
    List<GoodsAccountKey> keys = new ArrayList<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      keys.add(OwnershipBooks.accountKeyOf(cohort));
    }
    for (Map.Entry<ActorRef, HexCoord> entry :
        OwnershipBooks.operatorLocations(economy).entrySet()) {
      keys.add(new GoodsAccountKey(entry.getKey(), entry.getValue()));
    }
    return keys;
  }

  /** 该格**家户侧**的账本（键 = 家户身份；缺失 ⇒ 当场红 —— 家户账是日结算的唯一读口，缺了不能当 0）。 */
  private static Map<GoodsAccountKey, GoodsAccount> householdAccounts(
      EconomyData economy, ActorData books) {
    Map<GoodsAccountKey, GoodsAccount> out = new LinkedHashMap<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      GoodsAccountKey key = OwnershipBooks.accountKeyOf(cohort);
      GoodsAccount account = books.accounts().get(key);
      assertThat(account).as("家户账一本都不能少：%s", key).isNotNull();
      out.put(key, account);
    }
    return out;
  }

  /** 该格**经营者侧**的账本（驱动集 = 产业 → operator，唯一的拼写点是 {@link OwnershipBooks#operatorLocations}）。 */
  private static Map<GoodsAccountKey, GoodsAccount> operatorAccounts(
      EconomyData economy, ActorData books) {
    Map<GoodsAccountKey, GoodsAccount> out = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, HexCoord> entry :
        OwnershipBooks.operatorLocations(economy).entrySet()) {
      GoodsAccountKey key = new GoodsAccountKey(entry.getKey(), entry.getValue());
      GoodsAccount account = books.accounts().get(key);
      assertThat(account).as("经营者账一本都不能少：%s", key).isNotNull();
      out.put(key, account);
    }
    return out;
  }

  /** 一批账本的 Σ 某币种。 */
  private static long silverOf(Map<GoodsAccountKey, GoodsAccount> accounts) {
    long sum = 0L;
    for (GoodsAccount account : accounts.values()) {
      sum += account.money().getOrDefault(SILVER, 0L);
    }
    return sum;
  }

  /** 全部账本里**任何**为负的余额（币种与商品都查：两种余额同住一本账，负哪一个都是坏账）。 */
  private static List<String> negativeBalances(ActorData books) {
    List<String> bad = new ArrayList<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : books.accounts().entrySet()) {
      for (Map.Entry<CurrencyId, Long> money : entry.getValue().money().entrySet()) {
        if (money.getValue() < 0L) {
          bad.add(entry.getKey() + " 钱 " + money.getKey() + " = " + money.getValue());
        }
      }
    }
    return bad;
  }

  /** 3 个周期后钱与创世值不同的家户（判据 ②/③ 的"非平凡"那一半）。 */
  private static List<CohortKey> changedHouseholds(EconomyData economy, ActorData books) {
    List<CohortKey> changed = new ArrayList<>();
    for (CohortKey cohort : economy.classes().keySet()) {
      GoodsAccount before = genesisBooks.accounts().get(OwnershipBooks.accountKeyOf(cohort));
      GoodsAccount after = books.accounts().get(OwnershipBooks.accountKeyOf(cohort));
      long was = before == null ? 0L : before.money().getOrDefault(SILVER, 0L);
      long now = after == null ? 0L : after.money().getOrDefault(SILVER, 0L);
      if (now != was) {
        changed.add(cohort);
      }
    }
    return changed;
  }

  /** 经营者那一侧有没有动过（工钱真的付出去了）。 */
  private static boolean operatorChanged(EconomyData economy, ActorData books) {
    for (Map.Entry<ActorRef, HexCoord> entry :
        OwnershipBooks.operatorLocations(economy).entrySet()) {
      GoodsAccountKey key = new GoodsAccountKey(entry.getKey(), entry.getValue());
      GoodsAccount before = genesisBooks.accounts().get(key);
      GoodsAccount after = books.accounts().get(key);
      long was = before == null ? 0L : before.money().getOrDefault(SILVER, 0L);
      long now = after == null ? 0L : after.money().getOrDefault(SILVER, 0L);
      if (now != was) {
        return true;
      }
    }
    return false;
  }

  private static StateMeta stateMeta() {
    return new StateMeta(
        new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0));
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put(
        "economy",
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0), data));
    return modules;
  }
}
