package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P8 迁移写口</b>：纯 {@link LotMigrationBook#apply} 的守恒/取整/fail-closed 断言。
 *
 * <ul>
 *   <li>人口/劳动/债务按源行比例迁移，<b>两头合计不变</b>（人口、劳动、本金三个守恒式逐值）；
 *   <li>债务逐笔 {@code ⌊principal × count ÷ sourcePopulation⌋}，余数留在源合同；
 *   <li>目标行缺失 ⇒ 按 {@link HouseholdId#ofSeed} 规范身份<b>新建</b>（P8 契约：目标 lot 可由调用方创建/合并）；
 *   <li>目标家户 id 已被不同视图占用、源行不存在、迁移人数超源人口 ⇒ <b>具名 fail-closed</b>，绝不静默丢人/丢债。
 * </ul>
 *
 * <p>★ 本夹具只用 {@code classes}/{@code debtContracts} 两张表，配已迁移 meta 避免构造期旧档迁移改写输入。
 */
class LotMigrationBookTest {

  private static final HexCoord RURAL_HEX = new HexCoord(0, 0);
  private static final HexCoord CREDITOR_HEX = new HexCoord(0, 1);
  private static final HexCoord CITY_HEX = new HexCoord(1, 0);
  private static final CityId CITY = new CityId("c-1");
  private static final PeopleLotId SOURCE_LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final PeopleLotId TARGET_LOT = new PeopleLotId("urban:0_0:MALE:1");
  private static final HouseholdId SOURCE =
      HouseholdIds.ofSeed(RURAL_HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final HouseholdId CREDITOR =
      HouseholdIds.ofSeed(CREDITOR_HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final DebtTerms TERMS = DebtTerms.legacyDefault();
  private static final DebtContractId SOURCE_DEBT =
      DebtContractId.idOf(SOURCE, CREDITOR, DebtUnit.commodity(GRAIN), TERMS);

  private static final long SOURCE_POPULATION = 100L;
  private static final long SOURCE_LABOR = 70_000L;
  private static final long SOURCE_PRINCIPAL = 1_000_000L;

  @Test
  void migrationMovesPopulationLaborAndDebtProportionallyAndCreatesMissingTarget() {
    EconomyData base = base(Map.of());
    LotMigration migration = migration(20L);

    EconomyData after = LotMigrationBook.apply(base, List.of(migration), 7L);

    HouseholdId target =
        HouseholdIds.ofSeed(CITY_HEX, ResidenceKind.URBAN, SocialClassId.POOR_PEASANT);
    assertThat(after.classes()).as("目标行缺失 ⇒ 新建规范身份行").containsKey(target);

    HouseholdEconomy sourceRow = after.classes().get(SOURCE);
    HouseholdEconomy targetRow = after.classes().get(target);
    assertThat(sourceRow.population()).as("源行人口 100−20").isEqualTo(80L);
    assertThat(targetRow.population()).as("目标行人口 20").isEqualTo(20L);
    assertThat(sourceRow.population() + targetRow.population())
        .as("人口守恒")
        .isEqualTo(SOURCE_POPULATION);

    assertThat(sourceRow.laborMilli()).as("劳动按比例 ⌊70,000×20÷100⌋ = 14,000 迁走").isEqualTo(56_000L);
    assertThat(targetRow.laborMilli()).as("目标行收到同一份劳动").isEqualTo(14_000L);
    assertThat(sourceRow.laborMilli() + targetRow.laborMilli()).as("劳动守恒").isEqualTo(SOURCE_LABOR);

    assertThat(after.debtContracts().get(SOURCE_DEBT).principal())
        .as("源合同 ⌊1,000,000×20÷100⌋ 减少")
        .isEqualTo(800_000L);
    DebtContractId targetDebt =
        DebtContractId.idOf(target, CREDITOR, DebtUnit.commodity(GRAIN), TERMS);
    assertThat(after.debtContracts()).as("目标侧按同一四元组新建债务").containsKey(targetDebt);
    assertThat(after.debtContracts().get(targetDebt).principal())
        .as("债务随行 200,000")
        .isEqualTo(200_000L);
    assertThat(after.debtContracts().get(targetDebt).openedDay()).as("目标合同开账日 = 迁移日").isEqualTo(7L);
    assertThat(after.debtContracts().get(targetDebt).status()).isEqualTo(DebtStatus.NORMAL);
    assertThat(DebtStock.totalPrincipal(after.debtContracts()))
        .as("本金总量守恒（200,000 + 800,000 = 1,000,000）")
        .isEqualTo(SOURCE_PRINCIPAL);
    assertThat(sourceRow.debts()).as("源行派生引用仍在").contains(SOURCE_DEBT);
    assertThat(targetRow.debts()).as("目标行派生引用已补上").contains(targetDebt);
  }

  @Test
  void migrationMergesIntoExistingTargetRowWithoutLosingPeopleOrDebt() {
    HouseholdId target =
        HouseholdIds.ofSeed(CITY_HEX, ResidenceKind.URBAN, SocialClassId.POOR_PEASANT);
    HouseholdEconomy existingTarget =
        new HouseholdEconomy(
            target,
            new CohortKey(CITY_HEX, ResidenceKind.URBAN, SocialClassId.POOR_PEASANT),
            5L,
            3_500L,
            700,
            0L,
            List.of(),
            Map.of(),
            Map.of(),
            0L);
    EconomyData base = base(Map.of(target, existingTarget));

    EconomyData after = LotMigrationBook.apply(base, List.of(migration(20L)), 3L);

    HouseholdEconomy targetRow = after.classes().get(target);
    assertThat(targetRow.population()).as("既有目标行合并：5+20").isEqualTo(25L);
    assertThat(targetRow.laborMilli()).as("既有目标行合并：3500+14000").isEqualTo(17_500L);
    assertThat(after.classes().get(SOURCE).population()).as("源行同步减少").isEqualTo(80L);
    assertThat(DebtStock.totalPrincipal(after.debtContracts()))
        .as("合并目标行不丢本金")
        .isEqualTo(SOURCE_PRINCIPAL);
  }

  @Test
  void countAboveSourcePopulationFailsClosedWithNamedMessage() {
    EconomyData base = base(Map.of());

    assertThatThrownBy(() -> LotMigrationBook.apply(base, List.of(migration(101L)), 1L))
        .as("迁移人数超过源行可迁人口必须具名拒绝，不得把行抽成负")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("可迁人口不足");
  }

  @Test
  void missingSourceRowsFailClosedInsteadOfSilentlyDroppingPeople() {
    EconomyData base = base(Map.of());
    LotMigration fromEmptyHex =
        new LotMigration(
            new PeopleLotId("rural:9_9:MALE:1"),
            TARGET_LOT,
            new HexCoord(9, 9),
            CITY,
            CITY_HEX,
            5L,
            "测试：源格在 economy 侧没有家户行");

    assertThatThrownBy(() -> LotMigrationBook.apply(base, List.of(fromEmptyHex), 1L))
        .as("源行找不到时不许静默丢人")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有对应家户行");
  }

  @Test
  void targetHouseholdIdOccupiedByDifferentViewFailsClosed() {
    HouseholdId target =
        HouseholdIds.ofSeed(CITY_HEX, ResidenceKind.URBAN, SocialClassId.POOR_PEASANT);
    // 同一个规范 id 已被一个不同视图（农村居住）占用 ⇒ 新建目标行会覆盖既有身份，必须拒绝。
    HouseholdEconomy occupied =
        new HouseholdEconomy(
            target,
            new CohortKey(CITY_HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            5L,
            3_500L,
            700,
            0L,
            List.of(),
            Map.of(),
            Map.of(),
            0L);
    EconomyData base = base(Map.of(target, occupied));

    assertThatThrownBy(() -> LotMigrationBook.apply(base, List.of(migration(20L)), 1L))
        .as("目标 id 被不同视图占用 ⇒ 拒绝覆盖")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已被不同视图占用");
  }

  // ── 最小夹具 ─────────────────────────────────────────────────────────────────────────

  private static LotMigration migration(long count) {
    return new LotMigration(SOURCE_LOT, TARGET_LOT, RURAL_HEX, CITY, CITY_HEX, count, "测试：城市化迁移");
  }

  private static EconomyData base(Map<HouseholdId, HouseholdEconomy> extraRows) {
    HouseholdEconomy source =
        new HouseholdEconomy(
            SOURCE,
            new CohortKey(RURAL_HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
            SOURCE_POPULATION,
            SOURCE_LABOR,
            700,
            500L,
            List.of(SOURCE_DEBT),
            Map.of(),
            Map.of(),
            0L);
    HouseholdEconomy creditor =
        new HouseholdEconomy(
            CREDITOR,
            new CohortKey(CREDITOR_HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD),
            10L,
            0L,
            0,
            0L,
            List.of(),
            Map.of(),
            Map.of(),
            0L);
    DebtContract debt =
        new DebtContract(
            SOURCE_DEBT,
            SOURCE,
            CREDITOR,
            DebtUnit.commodity(GRAIN),
            TERMS,
            SOURCE_PRINCIPAL,
            0L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    LinkedHashMap<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(SOURCE, source);
    rows.put(CREDITOR, creditor);
    rows.putAll(extraRows);

    return EconomyData.empty()
        .withMeta(
            Optional.of(
                new EconomyMeta(
                    "lot-migration-book",
                    0L,
                    OptionalLong.empty(),
                    EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                    Optional.empty())))
        .withHouseholdEconomies(rows)
        .withDebtContracts(Map.of(SOURCE_DEBT, debt));
  }
}
