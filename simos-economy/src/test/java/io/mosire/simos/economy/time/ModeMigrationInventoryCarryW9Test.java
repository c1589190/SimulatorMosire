package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>W9（2026-10-11）判据测试：商品库存随迁 —— 「默认带一部分库存过去」（用户原话①）+ 四类关键项的「守恒式」</b>。
 *
 * <p>★★ <b>判据出处</b>：
 *
 * <ol>
 *   <li><b>用户原话</b>：「<b>默认带一部分库存过去</b>」（设计书 v3.29 §7 第 1 条）；
 *   <li><b>派单 ③</b>：{@code carryGoodsForMove} —— 整户迁空取全部、否则按本笔人口比例 floor（余数留源户）； <b>走唯一写口</b>
 *       {@code EconomySettlement.applyTransfer}；<b>排除 HAUL</b>（运输服务不是可搬运实物）；
 *   <li><b>D-023 §4 的既有形制</b>：{@code ⌊剩余量 × 比例⌋}、余数留源户 —— 同一源户的多笔 <b>逐笔读活表</b>（不是对同一份初值各算一遍）。
 * </ol>
 *
 * <pre>
 * 守恒式（逐值）：逐商品 Σ(全表) 前后相同 ∧ 源减 == 目标增（同一条转移的两端）
 * 不丢失        ：转移登记在生产账本里、reason = MIGRATION_CARRY、id 形如 tr-&lt;day&gt;-&lt;seq&gt;
 * 取整          ：部分迁移 = ⌊可用量 × 本笔人口 ÷ 迁出前人口⌋（余数留源户）；整户迁空 = 全部
 * </pre>
 */
class ModeMigrationInventoryCarryW9Test {

  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final long DAY = 30L;
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);
  private static final CommodityId HAUL = MerchantTradeTemplate.HAUL_COMMODITY;
  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final ProductionModeId FAMILY = DefaultProductionModes.FAMILY_FARM;
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-w9-carry-source");
  private static final HouseholdId HOST = HouseholdId.parse("hh-w9-carry-host");

  /** ★★★ <b>部分迁移：按本笔人口比例 floor 取整，源减 == 目标增，逐商品总量一字不变</b>。 */
  @Test
  void partialMoveCarriesThePopulationRatioAndConservesEveryCommodity() {
    CarryRun run = run(40L, Map.of(GRAIN, 1_000_000L, CLOTH, 7L, HAUL, 5_000L));

    // 逐商品取整：⌊1,000,000 × 40 ÷ 100⌋ = 400,000；⌊7 × 40 ÷ 100⌋ = 2；运输服务**不搬**。
    assertThat(run.targetGoods()).as("目标户拿到的商品").containsOnlyKeys(GRAIN, CLOTH);
    assertThat(run.targetGoods().get(GRAIN))
        .as("⌊1,000,000 × 40 ÷ 100⌋ = 400,000")
        .isEqualTo(400_000L);
    assertThat(run.targetGoods().get(CLOTH)).as("⌊7 × 40 ÷ 100⌋ = 2（余数 5 留源户）").isEqualTo(2L);
    assertThat(run.sourceGoods().get(GRAIN)).as("源户留 600,000").isEqualTo(600_000L);
    assertThat(run.sourceGoods().get(CLOTH)).as("源户留 5（余数留源户，不悬空）").isEqualTo(5L);
    assertThat(run.sourceGoods())
        .as("★ 守恒式：源减 == 目标增（逐商品）")
        .containsEntry(GRAIN, 1_000_000L - run.targetGoods().get(GRAIN))
        .containsEntry(CLOTH, 7L - run.targetGoods().get(CLOTH));
    assertThat(run.totalGoods())
        .as("★ 守恒式：逐商品 Σ(全表) 前后逐值相同（没有任何凭空造/销毁的腿）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1_000_000L, CLOTH, 7L, HAUL, 5_000L));

    // ★ D2 的具名边界：运输服务随人不随行（它是"在本格能交付的运力"现货，跨格搬它 = 凭空承诺别处的服务）。
    assertThat(run.sourceGoods().get(HAUL)).as("★ 运输服务留在源格（不随迁）").isEqualTo(5_000L);
    assertThat(run.targetGoods()).as("★ 目标户不得凭空多出运输服务").doesNotContainKey(HAUL);
    assertThat(ModeMigrationSettlement.isMobileCommodity(HAUL)).as("★ 唯一拼写点：HAUL 不可搬运").isFalse();
    assertThat(ModeMigrationSettlement.isMobileCommodity(GRAIN)).isTrue();

    // ★ 不丢失：转移登记在当天账本里，reason 具名、两端是同一个源户与目标户。
    assertThat(run.carries()).as("★ 一条 move ⇒ 恰好一条 MIGRATION_CARRY 转移").hasSize(1);
    Transfer carry = run.carries().get(0);
    assertThat(carry.reason()).isEqualTo(TransferReason.MIGRATION_CARRY);
    assertThat(carry.from()).isEqualTo(HouseholdActors.of(SOURCE));
    assertThat(carry.to()).isEqualTo(HouseholdActors.of(HOST));
    assertThat(carry.day()).isEqualTo(DAY);
    assertThat(carry.goods())
        .as("★ 账本里登记的就是那两腿商品（不丢、不多）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 400_000L, CLOTH, 2L));
    assertThat(carry.id().value())
        .as("真实 tick 的 id 形状 = tr-<day>-<seq>（唯一铸造口 mint）")
        .startsWith("tr-" + DAY + "-");
    assertThat(TransferReason.MIGRATION_CARRY.value())
        .as("词表项（读账时'随人搬走'与'买卖'分得开）")
        .isEqualTo("migration_carry");

    // ★ 可读面：INFO 具名（谁、哪天、带了多少）。
    assertThat(run.logLines())
        .as("★ 必须有一条具名 INFO：day/source/target/goods 都在")
        .anyMatch(
            line ->
                line.contains("MIGRATION_INVENTORY_CARRIED")
                    && line.contains("day=" + DAY)
                    && line.contains("source=" + SOURCE.value())
                    && line.contains("target=" + HOST.value())
                    && line.contains("goods="));
  }

  /** ★★★ <b>整户迁空：冻结之外的剩余量**全部**随最后一笔走，源侧不留悬空库存</b>。 */
  @Test
  void fullMoveCarriesEverythingAndLeavesNothingBehind() {
    CarryRun run = run(100L, Map.of(GRAIN, 1_000_000L, CLOTH, 7L));

    assertThat(run.targetGoods())
        .as("★ 整户迁空 ⇒ 全部带走（含 7 毫布的奇数余量，不留悬空）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1_000_000L, CLOTH, 7L));
    assertThat(run.sourceGoods())
        .as("★ 源户商品账**清空**（不是留一个 0 人壳户挂着库存）")
        .doesNotContainKeys(GRAIN, CLOTH);
    assertThat(run.totalGoods())
        .as("★ 守恒式：总量逐值不变")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1_000_000L, CLOTH, 7L));
    assertThat(run.carries()).as("整户迁空也是一条转移（不拆成两条）").hasSize(1);
  }

  /**
   * ★★★ <b>同一源户的多笔：<b>逐笔读活表</b>（前面的笔已经把库存带走了），不是对同一份初值各算一遍</b>。
   *
   * <pre>
   * 源户 100 人、粮 1,000,000：第一笔 30 人 ⇒ ⌊1,000,000 × 30 ÷ 100⌋ = 300,000（余 700,000）
   *                            第二笔 70 人（迁空）⇒ 700,000 **全部**
   * </pre>
   */
  @Test
  void twoMovesFromOneSourceReadTheLiveTableBetweenMoves() {
    CarryRun run = run(List.of(30L, 70L), Map.of(GRAIN, 1_000_000L));

    assertThat(run.movesRecorded()).as("两笔 move ⇒ 两条随迁转移").hasSize(2);
    assertThat(run.movesRecorded().get(0)).as("第一笔：按比例 floor").containsEntry(GRAIN, 300_000L);
    assertThat(run.movesRecorded().get(1))
        .as("第二笔：读的是**第一笔之后的活表** ⇒ 余量全部走（不是 700,000 × 70 ÷ 70 之外的算法）")
        .containsEntry(GRAIN, 700_000L);
    assertThat(run.sourceGoods()).as("★ 两笔之后源户清空、余数不悬空").doesNotContainKey(GRAIN);
    assertThat(run.totalGoods()).as("★ 守恒式：总量逐值不变").containsEntry(GRAIN, 1_000_000L);
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────────

  private record CarryRun(
      Map<CommodityId, Long> sourceGoods,
      Map<CommodityId, Long> targetGoods,
      Map<CommodityId, Long> totalGoods,
      List<Transfer> carries,
      List<Map<CommodityId, Long>> movesRecorded,
      List<String> logLines) {}

  private static CarryRun run(long population, Map<CommodityId, Long> goods) {
    return run(List.of(population), goods);
  }

  /** 跑一次迁移执行器（走真实 {@code ModeMigrationSettlement.apply} + 真实账本铸造口）。 */
  private static CarryRun run(List<Long> populations, Map<CommodityId, Long> goods) {
    EconomyData base = base();
    EconomySession session = new EconomySession(base);
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(SOURCE, H1, goods, Map.of(), Map.of(), Map.of());
    accounts.registerHousehold(HOST, H2, Map.of(), Map.of(), Map.of(), Map.of());

    List<ModeMigrationPolicy.MigrationMove> moves = new java.util.ArrayList<>();
    long ordinal = 0L;
    for (long population : populations) {
      moves.add(
          new ModeMigrationPolicy.MigrationMove(
              SOURCE,
              HOST,
              H2,
              FAMILY,
              10L,
              population,
              0L,
              0L,
              "fixture:inventory-carry-" + (ordinal++),
              Map.of()));
    }
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(DAY);
    List<String> logLines;
    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MIGRATION_LOGGER_NAME, Level.INFO)) {
      ModeMigrationSettlement.apply(
          session, accounts, new ModeMigrationPolicy.MigrationPlan(moves), base, DAY, ledger);
      logLines = List.copyOf(log.lines());
    }

    Map<CommodityId, Long> sourceGoods = new LinkedHashMap<>();
    Map<CommodityId, Long> targetGoods = new LinkedHashMap<>();
    Map<CommodityId, Long> totalGoods = new LinkedHashMap<>();
    for (Map.Entry<io.mosire.simos.social.api.id.HouseholdId, Map<CommodityId, Long>> entry :
        accounts.householdGoods().entrySet()) {
      Map<CommodityId, Long> row = entry.getValue() == null ? Map.of() : entry.getValue();
      if (entry.getKey().equals(SOURCE)) {
        sourceGoods.putAll(row);
      } else if (entry.getKey().equals(HOST)) {
        targetGoods.putAll(row);
      }
      for (Map.Entry<CommodityId, Long> inner : row.entrySet()) {
        if (inner.getValue() != null && inner.getValue() != 0L) {
          totalGoods.merge(inner.getKey(), inner.getValue(), Long::sum);
        }
      }
    }

    List<Transfer> carries =
        ledger.toLedger().transfers().stream()
            .filter(transfer -> transfer.reason() == TransferReason.MIGRATION_CARRY)
            .toList();
    return new CarryRun(
        sourceGoods,
        targetGoods,
        totalGoods,
        carries,
        carries.stream().map(Transfer::goods).toList(),
        logLines);
  }

  private static EconomyData base() {
    ClassPositionId position = familyPosition();
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(SOURCE, row(SOURCE, H1, 100L));
    rows.put(HOST, row(HOST, H2, 100L));
    Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
    for (HouseholdId household : List.of(SOURCE, HOST)) {
      memberships.put(
          household,
          new HouseholdClassMembership(
              household, position, position, Set.of(), Map.of(), 0L, 0L, "w9-carry-test"));
    }
    return EconomyData.empty()
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(rows)
        .withClassMemberships(memberships);
  }

  private static ClassPositionId familyPosition() {
    return DefaultProductionModes.positionId(
            DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
        .orElseThrow();
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long population) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }
}
