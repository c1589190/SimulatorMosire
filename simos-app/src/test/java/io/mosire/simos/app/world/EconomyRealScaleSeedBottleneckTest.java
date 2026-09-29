package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionSettlement.ActorEntry;
import io.mosire.simos.economy.time.ProductionUnitBook;
import io.mosire.simos.map.hex.HexCoord;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **Task 7：真档规模下"第三路瓶颈"真的在起作用**（v2 spec §九 V3 判据 3：缺种子 ⇒ 投入面积缩 ⇒ 减产）。
 *
 * <p>★★ **为什么需要这个文件**：{@code EconomyTestWorld} 那 5 格夹具是 **1,000 人/格**的（真档是 **14,806 人/格**， 差 15
 * 倍——spec §九 点名的"夹具规模决定判别力"），而**每亩需种是按亩计的**：同样的 3,100 亩，1,000 人的格只有 65 天口粮 （5,395,000 毫粮）⇒
 * 付不起满种（24,800,000 毫粮）⇒ 那个夹具上跑"配了种子"的世界只会得到"全员把口粮当种子播下去"的
 * 病态结果，**它证明不了真档的任何事**。故真档可见性必须**在真档量级上**验收：本文件用**真播种器的载荷** （{@link EconomySeeder#payload} → 真
 * {@link EconomySeedHandler}）造一格 14,806 人的平原格，再跑真结算。
 *
 * <p>★ 三条读数的算式（全部整数、毫粮；亩 = 千分亩 {@code / 1000}）：
 *
 * <pre>
 * 人口 14,806 按 450/350/150/50 切 ⇒ 6,663 / 5,182 / 2,221 / 740
 * 产能：农业 LAND 合计 3,100,000 千分亩；R4-B.3a 把产业拆成"主 unit + 四户副 unit"后，
 *       每个 unit 的 capacityScaleOf 各自向下取整 ⇒ Σ = 2,170 + 46 + 325 + 418 + 139 = 3,098 亩
 * 满种种子 = 3,098 亩 × 8,000 毫粮/亩 = 24,784,000 毫粮
 * 第 1 天重排后：纺织按**每个 unit** 的 min(可用 TOOL, 实扣纤维 ÷ 30,000)
 *               = 434 + 9 + 65 + 83 + 27 = 618 台 ⇒ 618,000 千分劳动
 *              农业 = 6,351,734 + (590,865 − 618,000) = 6,324,599
 * 收获：3,098 亩 × 67 粮/亩 × 1000 = 207,566,000 毛产 ⇒ 净（扣饲料 0‰ + 折旧 30‰）201,339,020
 * ⇒ ★★ <b>H3 起"种子"不再是瓶颈，最紧的那一路是【按 unit 拆分后的土地产能 3,098 亩】</b>
 *   （口径变化，如实记）：H3 把投入改成由 {@code relation.inputSupplier} 按产能规模一次取足；
 *   R4-B.3a 又把产业拆成主 unit + 四户副 unit，capacityScaleOf 在**每个 unit** 各向下取整一次
 *   ⇒ 本档满种量 = 3,098 亩（比整格 3,100 亩少 2 亩的逐 unit 取整损失）。★ 但"缺料 ⇒ 面积缩 ⇒ 减产"
 *   这条机构没有被丢掉：它由 economy 模块的
 *   {@code EconomySowingTest.eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow} 守着
 *   （佃农家户缸空 ⇒ 0 亩、缸足 ⇒ 满种，逐值判据）。本文件因此改述为「真档规模下最紧那一路是谁」。
 * </pre>
 *
 * <p>★ 缸全空时：播种日扣 0 ⇒ 可支撑 0 亩 ⇒ **颗粒无收**；而**未配种子的对照格**照常按 unit 口径的产能满产 3,098 亩 （净 201,339,020 毫粮）——
 * 这两条并排就是"种子是第三路瓶颈"的判别力。
 */
class EconomyRealScaleSeedBottleneckTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final String MAP_ID = "econ-real-scale";

  /** 真档每格人口（11,830,000 ÷ 799，与 {@code EconomySeederTest} 同口径）。 */
  private static final long POPULATION_PER_HEX = 11_830_000L / 799L; // = 14,806

  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
  private static final IndustryId WEAVE = IndustryHexKeys.id(EconomySeeder.WEAVE, 0, 0);
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final CommodityId FIBER = new CommodityId(EconomySeeder.COMMODITY_FIBER);

  /** 一格平原、真档人口（**真播种器载荷**：{@code economy.Seed} 经真 handler 落成状态）。 */
  private static EconomyData realScaleHex() {
    String payload =
        EconomySeeder.payload(MAP_ID, PopulationSeeder.groups(realScalePlan(), 0L), at -> "plains");
    SimulationState emptyState =
        new SimulationState(
            stateMeta(), snapshots(EconomyData.empty()), InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(emptyState, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet change = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return EconomyChangeSet.apply(change, EconomyData.empty());
  }

  /**
   * ★★ <b>H1：同一个 plan 交出的家户账本</b>（真播种路径是 {@code economy.Seed} + {@code actor.Seed} 同批； 本夹具只走
   * handler，故账本在这里从**同一份** {@link EconomySeeder#plan} 的 {@code householdStocks} 建）。
   *
   * <p>★ 与 {@link HouseholdSeeder#books} 同一条路（id 由 {@code HouseholdActors} 拼、空账也建、经营主体账同源）⇒ 与真档同形。
   */
  private static ActorData realScaleBooks() {
    // ★★ H4：货币禀赋与开缸库存同源（同一份 plan）—— 账本要带上钱，否则与真播种路径不同形。
    EconomySeeder.Seed seeding =
        EconomySeeder.plan(MAP_ID, PopulationSeeder.groups(realScalePlan(), 0L), at -> "plains");
    // ★★ H5（⑤）：经营主体的开缸账与家户同源（同一次 plan）。
    return HouseholdSeeder.books(
        seeding.householdLocations(),
        seeding.householdStocks(),
        seeding.householdMoney(),
        seeding.operators());
  }

  private static SettlementPlan realScalePlan() {
    return new SettlementPlan(Map.of(HEX, POPULATION_PER_HEX), List.of(), Map.of(), 250L, 0L);
  }

  /**
   * 该格**农村家户行**（H0.2 起行 = {@code (格, 居住类型, 阶层)}，**行里没有产业了**）。
   *
   * <p>★ 本夹具一格、无城 ⇒ {@code (0,0)} 上的农村四行**就是**供给农业与家庭纺织的那两批人（旧版 {@code farm@0_0} 那四行逐值对应）； 该格没有城镇批次
   * ⇒ 没有 {@code URBAN} 行。★ 真播种器的 {@code hh-…} id 不能反推视图 ⇒ 一律读 {@link ClassRow#view()}。
   */
  private static List<ClassRow> farmRows(EconomyData data) {
    List<ClassRow> rows = new ArrayList<>();
    for (ClassRow row : data.classes().values()) {
      if (row.view().hex().equals(HEX) && row.view().residence() == ResidenceKind.RURAL) {
        rows.add(row);
      }
    }
    return rows;
  }

  /**
   * 该格农业的规模（亩）= **逐 unit** {@code capacityScaleOf} 之和：R4-B.3a 把农业拆成主 unit + 四户副 unit， {@link
   * ProductionUnitBook#capacityScaleOf} 在每个 unit 上各向下取整一次 ⇒ 真档这格是 2,170 + 46 + 325 + 418 + 139 =
   * 3,098 亩。
   *
   * <p>★ 这与"投入按整个产业一次取足"的 H3 口径并不矛盾：所谓"按产能规模一次取足"是**对 unit 而言**；多 unit 拆分后的 取整损失因此仍是逐 unit
   * 的，本文件如实把它写进断言（不再把 3,100 当成实际规模）。
   */
  private static long farmScaleMu(EconomyData data) {
    Industry farm = data.industries().get(FARM);
    if (farm == null) {
      throw new AssertionError("真档农业模板必须存在: " + FARM);
    }
    long scaleMu = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(FARM)) {
        scaleMu += ProductionUnitBook.capacityScaleOf(unit, farm, data.assetShares());
      }
    }
    return scaleMu;
  }

  /** 某产业名下**全部 unit** 的初始配额之和（H5/R4-B.3a 起按 unit.activity 判，不能再按 actor.id 认产业）。 */
  private static long quotasOf(EconomyData data, IndustryId industry) {
    Map<String, IndustryId> industryByUnit = new LinkedHashMap<>();
    for (ProductionUnit unit : data.units().values()) {
      industryByUnit.put(unit.id().value(), unit.industry());
    }
    long quota = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      IndustryId actual = industryByUnit.get(allocation.activity());
      if (actual == null) {
        throw new AssertionError("配额的 activity 不是现存 unit: " + allocation);
      }
      if (actual.equals(industry)) {
        quota += allocation.laborMilli();
      }
    }
    return quota;
  }

  /** 某产业名下**全部 unit** 本周期累计的实际劳动（千分劳动·日）。 */
  private static long sumCycleLabor(EconomyData data, IndustryId industry) {
    long labor = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        labor += unit.cycleLaborMilli();
      }
    }
    return labor;
  }

  /**
   * 纺织"用得上"的劳动：**逐 unit** {@code min(可用 TOOL, 本周期实扣纤维 ÷ 每台用量) × 每台劳动} 之和。
   *
   * <pre>
   * 主 unit：min(518, 13,020,000 ÷ 30,000) = 434
   * 副 unit：min(11,9)、min(78,65)、min(100,83)、min(33,27) ⇒ 9 / 65 / 83 / 27
   * 合计 618 台 = 618,000 千分劳动
   * </pre>
   */
  private static long weaveLaborNeed(EconomyData seeded, EconomyData afterOneDay) {
    long labour = 0L;
    for (ProductionUnit unit : afterOneDay.units().values()) {
      if (!unit.industry().equals(WEAVE)) {
        continue;
      }
      long looms =
          ProductionUnitBook.usableAssets(unit, seeded.assetShares())
              .getOrDefault(AssetKind.TOOL, 0L);
      long fiberUsed = unit.cycleInputUsedMilli().getOrDefault(FIBER, 0L);
      long usableLooms =
          Long.min(
              looms,
              fiberUsed
                  / (EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH));
      labour += usableLooms * EconomySeeder.LABOR_MILLI_PER_LOOM;
    }
    return labour;
  }

  /**
   * ★★ <b>结算推进：与 {@code EconomyOwnershipTimeParticipant} 的日循环同形，但额外交回逐日 ledger</b>—— 产出自 R5/T4
   * 起不再写进 {@link ClassRow}，本文件要独立核"净产"就必须读当天的 {@link ProductionLedger}； 而断言需要的终态又要经真账户会话落回 actor
   * 账。本助手只组合既有公开口（{@link OwnershipBooks#loadAccountSession}、 {@link EconomyDayStepper#step}、{@link
   * OwnershipBooks#fold}/{@link OwnershipBooks#apply}/{@link
   * OwnershipBooks#landAccountSession}），不发明第二套结算。
   */
  private static Advanced advanceCapturing(EconomyData base, ActorData books, long days) {
    AccountSession accounts = OwnershipBooks.loadAccountSession(base, books);
    Set<AccountPartitionKey> sessionAccounts = new LinkedHashSet<>(accounts.accounts().keySet());
    List<ProductionLedger> ledgers = new ArrayList<>();
    ActorData current = books;
    EconomyData next;
    try (EconomyDayStepper stepper = new EconomyDayStepper(base, accounts)) {
      for (long day = 1L; day <= days; day++) {
        ProductionLedger ledger = stepper.step(day);
        ledgers.add(ledger);
        List<ActorEntry> entries = OwnershipBooks.fold(ledger, OwnershipBooks.REASONS_NOT_FOLDED);
        if (!entries.isEmpty()) {
          current = OwnershipBooks.apply(current, entries, sessionAccounts);
        }
        current = OwnershipBooks.landAccountSession(current, stepper.accounts());
      }
      next = stepper.finish();
    }
    return new Advanced(next, current, ledgers);
  }

  /** 推进结果 + 逐日 ledger。 */
  private record Advanced(EconomyData economy, ActorData actor, List<ProductionLedger> ledgers) {}

  /** 本周期农业**毛产**的粮那一维（毫粮）。 */
  private static long grainGrossOf(List<ProductionLedger> ledgers) {
    long gross = 0L;
    for (ProductionLedger ledger : ledgers) {
      gross += ledger.grossOf(FARM, GRAIN);
    }
    return gross;
  }

  /** 本周期农业**净产**的粮那一维 = 毛产 − 损耗（毫粮）。 */
  private static long grainNetOf(List<ProductionLedger> ledgers) {
    long net = 0L;
    for (ProductionLedger ledger : ledgers) {
      net += ledger.grossOf(FARM, GRAIN) - ledger.lossOf(FARM, GRAIN);
    }
    return net;
  }

  /** Σ 农业各 unit 的"本周期实际扣到的种子"（毫粮；周期关账后归零）。 */
  private static long totalSown(EconomyData data) {
    long sown = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(FARM)) {
        sown += unit.cycleInputUsedMilli().getOrDefault(GRAIN, 0L);
      }
    }
    return sown;
  }

  // ── ①′ M1.8/R2：真档规模上"配额 ≤ 行折算基准 + 第 1 天按缺口重排" ─────────────────────

  /**
   * ★★ **M1.8（9521bd00）+ R2/R3：真档规模上，逐批预算封顶后的两条配额实发值、以及第 1 天的缺口重排**。
   *
   * <pre>
   * Σ 行折算 = Σ_i 行 laborMilli_i × 槽位投入率_i ÷ 1000        （= 7,385,816；逐批折算后的池上限）
   * Σ 配额   = 农业 6,351,734 + 纺织 590,865 = 6,942,599 ≤ Σ 行折算   （M1.8：分不满合法）
   * 第 1 天重排 = 纺织用得上 618,000（逐 unit 取整：434+9+65+83+27 台），原配额 590,865
   *              ⇒ 从农业未吸收的配额补 27,135
   *              ⇒ 农业 6,324,599 + 纺织 618,000 = 6,942,599（劳动只在产业之间搬，一毫不增不减）
   * </pre>
   *
   * <p>★ 判别力：把预算改回毛额（男青壮不再撞 236,500）⇒ 两条配额之和变 7,385,816 ⇒ 逐值红； 重排若不守恒（多给或少给一分）⇒ 第三条红。
   */
  @Test
  void realScaleQuotasAreCappedAndSettledLaborRedistributes() {
    EconomyData seeded = realScaleHex();

    long farmQuota = quotasOf(seeded, FARM);
    long weaveQuota = quotasOf(seeded, WEAVE);
    long rowSum = 0L;
    for (ClassRow row : farmRows(seeded)) {
      rowSum += row.laborMilli() * row.participationPerMille() / 1000L;
    }
    assertThat(farmQuota + weaveQuota)
        .as("M1.8（9521bd00）：两条配额之和 == 逐批预算封顶后的实发（≤ 各行折算日劳动；分不满合法）")
        .isEqualTo(6_942_599L)
        .isLessThanOrEqualTo(rowSum);
    assertThat(weaveQuota)
        .as("★★ R3 的判据：农村批次**真的**给了纺织一条非零配额（M1.8 后 590,865，比名义的 738,581 少）")
        .isEqualTo(590_865L)
        .isPositive();

    EconomyData afterOneDay = advanceCapturing(seeded, realScaleBooks(), 1L).economy();
    // ★★ **H5 ③（裁定 C2）+ M1.8 之后的第 1 天重排，逐条算式（数字由本文件独立复算，不抄实际）**：
    //   劳动配额周期第一天按**缺口信号**在产业之间重排（未吸收的劳动回池、有缺口的产业优先吸收）。
    //   本格（一格、无城）只有两个产业、共 10 个 unit：
    //
    //   ① 纺织的"用得上"的劳动 = Σ_unit min(可用织机, 本 unit 扣到的纤维 ÷ 每台用量 30000) × 1000
    //       主 unit 518 台 + 四户副 unit 9/65/83/27 台 = 618 台 ⇒ **618,000**
    //   ② 但纺织**原配额只有 590,865** ⇒ 缺口 27,135 从农业未吸收的配额里补上
    //   ③ 农业自己的需求（3,098 亩 × 143）≈ 443,014 < 它封顶后的配额 6,351,734 ⇒ 剩余回池
    //      ⇒ 农业第 1 天 = 6,351,734 − (618,000 − 590,865) = **6,324,599**
    //      ★ 守恒：6,324,599 + 618,000 = 6,942,599 = 两条封顶后配额之和（劳动只在产业之间搬，一毫不增不减）。
    long weaveLaborNeed = weaveLaborNeed(seeded, afterOneDay);
    assertThat(weaveLaborNeed).as("① 纺织用得上 618 台（逐 unit 取整后的纤维那一路最紧）").isEqualTo(618_000L);
    assertThat(sumCycleLabor(afterOneDay, WEAVE))
        .as("② 纺织第 1 天累加的是**它用得上的**那一条（H5 ③：缺口从农业补，不再是原配额）")
        .isEqualTo(weaveLaborNeed);
    assertThat(sumCycleLabor(afterOneDay, FARM))
        .as("③ 农业第 1 天累加的是它封顶后的配额 − 补纺织缺口的那一份（最后雇主）")
        .isEqualTo(farmQuota + (weaveQuota - weaveLaborNeed));
    assertThat(sumCycleLabor(afterOneDay, FARM) + sumCycleLabor(afterOneDay, WEAVE))
        .as("★★ H5 ③ 的守恒：重排之后**两条配额之和一份不少**（劳动只在产业之间搬）")
        .isEqualTo(farmQuota + weaveQuota);
  }

  /**
   * ★ **每条配额都在它的批次可支配劳动的范围内**（真档规模上的守恒；{@code Σ ≤ available} 逐组成立）。
   *
   * <p>★ 判别力：播种器若把同一批人的劳动同时算给两个产业（或忘了按毛额折算），本条的 {@code ≤} 会红。
   */
  @Test
  void everyRealScaleQuotaStaysWithinItsBatch() {
    EconomyData seeded = realScaleHex();

    Map<PeopleLotId, Long> allocated = new LinkedHashMap<>();
    for (LaborAllocation allocation : seeded.allocations().values()) {
      allocated.merge(allocation.group(), allocation.laborMilli(), Long::sum);
    }
    assertThat(allocated).as("有配额 ⇒ 必有供给").isNotEmpty();
    for (Map.Entry<PeopleLotId, Long> entry : allocated.entrySet()) {
      LaborSupply supply = seeded.laborSupply().get(entry.getKey());
      assertThat(supply).as("批次 %s 必须有供给记录", entry.getKey()).isNotNull();
      assertThat(entry.getValue())
          .as("批次 %s：Σ 配额 ≤ 可用劳动", entry.getKey())
          .isLessThanOrEqualTo(supply.availableLabor());
      assertThat(allocationPeriod(seeded, entry.getKey())).as("配额与供给同期").isEqualTo(supply.period());
    }
  }

  private static long allocationPeriod(EconomyData data, PeopleLotId group) {
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.group().equals(group)) {
        return allocation.period();
      }
    }
    throw new AssertionError("没有配额: " + group);
  }

  // ── ① 真档真的配了种子、且真的扣了（3,098 亩）─────────────────────────────────────────

  /**
   * ★★ **真档载荷里带着定案数**（8 粮/亩），且**每一户都付得起自己那份** ⇒ 播种日扣满 {@code 3,098 亩 × 8,000 = 24,784,000
   * 毫粮}（真档的"标定实质不变"就建立在"种子买得起"这一点上）。
   *
   * <p>★★ <b>H3 起：满种量 = 逐 unit 产能规模之和 3,098 亩</b>（口径变化，如实记）—— 改前"逐行人口份额 + 逐行⌊⌋"只到 3,098 亩；现在由
   * {@code relation.inputSupplier} 对**每个 unit** 按产能规模一次取足，而真档家户付得起 ⇒ 满种。 ★
   * 方向仍安全：第三路只**缩**面积、永不放大（付不起时按实扣算）。
   */
  @Test
  void theRealScaleHexSowsEveryMuItHasMoneyForOnTheSowingDay() {
    EconomyData seeded = realScaleHex();
    assertThat(seeded.industries()).as("一格、无城 ⇒ 农业 + 家庭纺织两个产业（R3 起有农村人口的格都有织机）").hasSize(2);
    assertThat(seeded.units()).as("R4-B.3a：农业与纺织各 1 主 unit + 4 户副 unit").hasSize(10);

    // ★ R3：投入表的值侧带商品维度（{"LAND":{"grain":8000}}）⇒ 断言落在**内层**那张商品表上；
    //   且只对**农业**断言（家庭纺织的投入挂在 TOOL 上、耗的是纤维，不是每亩需种）。
    Industry farm = seeded.industries().get(FARM);
    assertThat(farm.cycleInputPerUnit().get(AssetKind.LAND))
        .as("播种器给真档的农业配了每亩需种")
        .containsEntry(GRAIN, EconomySeeder.SEED_MILLI_PER_MU);

    // ★★ H0.3（K3）+ R4-B.3a：规模逐 unit 从 AssetShare 派生，各行没有土地。
    long scaleMu = farmScaleMu(seeded);
    List<ClassRow> rows = farmRows(seeded);
    ActorData books = realScaleBooks();
    long needMilli = scaleMu * EconomySeeder.SEED_MILLI_PER_MU;
    // ★ H1/H3：储备住在 actor 侧的账本上（行里没有 goods 这一栏）；供方 = relation.inputSupplier
    //   （四档默认 = 经营者；真档主 unit 的经营者在创世没有商品账 ⇒ 结算回落到该产业名下家户账）⇒ 这里按**合计**核。
    long payable = 0L;
    for (ClassRow row : rows) {
      payable += householdGoods(books, row.id(), row.view().hex(), GRAIN);
    }
    assertThat(payable)
        .as("该产业名下家户的粮合计必须付得起满种量（%d 毫粮）", needMilli)
        .isGreaterThanOrEqualTo(needMilli);
    assertThat(needMilli).as("满种量 = 3,098 亩 × 8,000 毫粮/亩").isEqualTo(24_784_000L);

    EconomyData sowingDay = advanceCapturing(seeded, books, 1L).economy();

    assertThat(totalSown(sowingDay)).as("播种日扣满（第三路瓶颈**存在**：它决定投入面积）").isEqualTo(needMilli);
    assertThat(totalSown(sowingDay)).as("★ 判别力：与「没配种子」（恒 0）必须不同").isNotZero();
  }

  /**
   * ★★ **收获面积由"实际扣到的种子"决定**：可支撑 {@code 24,784,000 ÷ 8,000 = 3,098 亩}，等于逐 unit 的产能规模之和。
   *
   * <p>★★ <b>H3 + R4-B.3a 起：3,098 就是 3,098</b>（产业整体按产能取足，但每个 unit 各取整一次）。
   * 种子付得起时，最紧的一路是土地；种子付不起时，本档的"缺料 ⇒ 面积缩 ⇒ 减产"由 economy 模块的 {@code
   * EconomySowingTest.eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow} 守住。
   */
  @Test
  void theSownSeedIsTheBottleneckThatDecidesTheHarvestArea() {
    EconomyData seeded = realScaleHex();
    long scaleMu = farmScaleMu(seeded);
    assertThat(scaleMu).as("可支撑亩 = 逐 unit 产能规模之和（3,098）").isEqualTo(3_098L);

    Advanced afterHarvest = advanceCapturing(seeded, realScaleBooks(), EconomySeeder.CYCLE_DAYS);

    // ★★ T4 起**净产**要在两处合读：ledger 的毛产/损耗 + operator 账上的落点已由真协调器统一。
    //   本文件改读**逐日 ledger**（产出离开 ClassRow 之后的唯一权威发生额），断言毛产与净产两条闭式。
    long grossGrain = grainGrossOf(afterHarvest.ledgers());
    assertThat(grossGrain)
        .as("毛产 = 3,098 亩 × 67 粮/亩 × 1000")
        .isEqualTo(scaleMu * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L);
    assertThat(grainNetOf(afterHarvest.ledgers()))
        .as("净产 = 毛产 × 0.97（饲料 0‰ + 折旧 30‰）")
        .isEqualTo(grossGrain * 970L / 1000L);
    assertThat(grainNetOf(afterHarvest.ledgers()))
        .as("★ 满种 ⇒ 净产恰等于【逐 unit 产能那一档】（3,098 亩）")
        .isEqualTo(201_339_020L);
  }

  // ── ② 缸空 ⇒ 颗粒无收；未配种子的对照格照常收获 ───────────────────────────────────────

  /**
   * ★★ **"冬春把缸吃空 ⇒ 播种日扣不到 ⇒ 减产"在真档量级上的极端形态**：把这一格各行的粮清空 ⇒ 播种日扣 0 ⇒ {@code seedCapMu = 0} ⇒
   * **颗粒无收**；而**同样清空、但没配种子**的对照格照常按 unit 产能满产 （3,098 亩 × 67 × 1000 × 0.97 = 201,339,020 毫粮）——
   * 两条并排即"种子确实是第三路瓶颈"的证据。
   */
  @Test
  void anEmptyJarYieldsNothingInTheRealWorldWhileTheUnseededControlStillHarvests() {
    EconomyData seeded = realScaleHex();
    EconomyData emptied = seeded;
    EconomyData emptiedUnseeded = withoutSeedRate(seeded);
    // ★★ H1：**清空的是账本**（"冬春把缸吃空"）—— 行里已经没有 goods 这一栏了。
    ActorData emptiedBooks = withEmptyJars(realScaleBooks());
    ActorData emptiedUnseededBooks = emptiedBooks;

    // ★ 前提：确实清空了（否则下一条断言测的是别的东西）
    for (ClassRow row : emptied.classes().values()) {
      assertThat(accountBalances(emptiedBooks, row.id(), row.view().hex()))
          .as("家户 %s 的缸已清空", row.id())
          .isEmpty();
    }

    Advanced starved = advanceCapturing(emptied, emptiedBooks, EconomySeeder.CYCLE_DAYS);
    Advanced control =
        advanceCapturing(emptiedUnseeded, emptiedUnseededBooks, EconomySeeder.CYCLE_DAYS);

    assertThat(grainNetOf(starved.ledgers())).as("扣不到种 ⇒ 0 亩 ⇒ 不产粮").isZero();
    assertThat(hexGrain(starved.actor(), starved.economy())).as("缸本来空、又不产粮 ⇒ 终态为 0").isZero();
    assertThat(starved.economy().units().values())
        .allSatisfy(
            unit -> assertThat(unit.cycleInputUsedMilli()).as("周期已关账 ⇒ unit 累加器清零").isEmpty());

    assertThat(grainNetOf(control.ledgers()))
        .as("★ 未配种子的对照格：第三路不施加约束 ⇒ 按 unit 产能 3,098 亩满产的**净额**（扣饲料 0‰ + 折旧 30‰）")
        .isEqualTo(201_339_020L);
    assertThat(hexGrain(control.actor(), control.economy()))
        .as("家户侧的入账非零（T4 起产出两处落：关系实付 + operator 计提）")
        .isPositive();
  }

  // ── 夹具与读数 ───────────────────────────────────────────────────────────────────────

  private static StateMeta stateMeta() {
    return new StateMeta(REF, SimosTimestamp.of(0));
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, SimosTimestamp.of(0), data));
    return modules;
  }

  /**
   * ★★ <b>"冬春把缸吃空"</b>（H1）：把每一本家户账的余额清空 —— ★ <b>账本本身留着</b>（0 余额保留是本仓既定口径：
   * "这个家户在这一格有一本账"与"它现在有东西"是两件事；删掉账本会让日结算 load 时抛）。
   */
  private static ActorData withEmptyJars(ActorData books) {
    LinkedHashMap<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>();
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : books.accounts().entrySet()) {
      // ★★ H4：清空的是**商品**那半 —— 货币那半原样带过（"冬春把缸吃空"不是"把钱也烧了"）。
      accounts.put(
          entry.getKey(), new GoodsAccount(entry.getKey(), Map.of(), entry.getValue().money()));
    }
    return books.withAccounts(accounts);
  }

  /** 把每个产业的 {@code cycleInputPerUnit} 清空（= 未配投入的对照格）；其余模板字段原样带过。 */
  private static EconomyData withoutSeedRate(EconomyData data) {
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : data.industries().entrySet()) {
      Industry industry = entry.getValue();
      industries.put(
          entry.getKey(),
          new Industry(
              industry.id(),
              industry.name(),
              industry.regime(),
              industry.cycleDays(),
              industry.capacityPerUnit(),
              industry.dailyInputPerUnit(),
              industry.dailyLaborPerUnit(),
              industry.laborPerUnit(),
              industry.outputPerUnit(),
              // 清投入这一张模板表；unit 与 AssetShare（产能/经营者的真值）原样保留。
              Map.of(),
              industry.slots(),
              industry.allocation()));
    }
    return data.withIndustries(industries);
  }

  /** 该格 Σ 家户粮库存（毫粮）—— H1：从 actor 侧的账本读。 */
  private static long hexGrain(ActorData books, EconomyData data) {
    long grain = 0L;
    for (ClassRow row : data.classes().values()) {
      grain += householdGoods(books, row.id(), row.view().hex(), GRAIN);
    }
    return grain;
  }

  /** 某个家户账上某商品的余额（H1）；账户键经 {@link OwnershipBooks#accountKeyOf} 拼（不复述格式）。 */
  private static long householdGoods(
      ActorData books, HouseholdId household, HexCoord location, CommodityId commodity) {
    return accountBalances(books, household, location).getOrDefault(commodity, 0L);
  }

  /** 某个家户账本上的余额表（缺席 ⇒ 空表）。 */
  private static Map<CommodityId, Long> accountBalances(
      ActorData books, HouseholdId household, HexCoord location) {
    GoodsAccount account = books.accounts().get(OwnershipBooks.accountKeyOf(household, location));
    return account == null ? Map.of() : account.balances();
  }
}
