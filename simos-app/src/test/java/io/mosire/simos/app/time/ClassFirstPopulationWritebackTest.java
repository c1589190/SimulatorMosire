package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.classfirst.ClassFirstPilotEngine;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>R2c：出生/死亡接回 classfirst 的逐值判据</b>（纯函数，不经 Core）：
 *
 * <ol>
 *   <li>死亡按人口权重最大余数法摊到 {@code (格, 居住类型)} 组的家户账户，逐户不出现负人口；
 *   <li>出生落到同组既有账户，不新建家户；
 *   <li>{@code Σclassfirst 人口 == 旧人口 + 出生 − 死亡}，且池 {@code population/labor} 与家户账户逐值一致；
 *   <li>死亡按比例缩 {@code laborUnits}、出生不加劳动（与旧协调器同口径）。
 * </ol>
 */
class ClassFirstPopulationWritebackTest {

  private static final HexCoord HEX = new HexCoord(0, 0);

  @Test
  void birthsAndDeathsAreAppliedToHouseholdAccountsAndPools() {
    ClassFirstState before = state(100L, 300L);
    EconomyData economy = economyWithClasses(before);
    long beforePopulation = poolPopulation(before);
    long beforeLabor = poolLabor(before);
    assertThat(beforePopulation).isEqualTo(400L);

    ClassFirstPopulationWriteback.Applied applied =
        ClassFirstPopulationWriteback.apply(
            before,
            economy,
            List.of(new LotChange(new PeopleLotId("rural:0_0:MALE:1"), HEX, 10L, 30L)));

    assertThat(applied.births()).as("落账的出生合计").isEqualTo(10L);
    assertThat(applied.deaths()).as("落账的死亡合计").isEqualTo(30L);
    ClassFirstState after = applied.state();
    assertThat(poolPopulation(after))
        .as("Σ人口 == 旧人口 + 出生 − 死亡")
        .isEqualTo(beforePopulation + applied.births() - applied.deaths());
    assertThat(poolPopulation(after)).as("不得为负").isNotNegative();
    assertThat(poolLabor(after)).as("出生不加劳动、死亡缩劳动 ⇒ 总劳动下降").isLessThan(beforeLabor);

    long accountPopulation = 0L;
    long accountLabor = 0L;
    for (HouseholdProductionAccount account : after.householdAccounts().values()) {
      HouseholdProductionAccount previous = before.householdAccounts().get(account.id());
      assertThat(previous).as("人口回写不得新建/删除账户").isNotNull();
      assertThat(account.population()).as("逐户人口不得为负").isNotNegative();
      assertThat(account.laborUnits()).as("逐户劳动不得为负").isNotNegative();
      if (previous.population() == 0L) {
        assertThat(account.population()).as("空池占位账户不受生死影响").isZero();
      } else {
        assertThat(account.population()).as("出生只能加、死亡只能减").isNotEqualTo(previous.population());
      }
      accountPopulation += account.population();
      accountLabor += account.laborUnits();
    }
    assertThat(accountPopulation).as("Σ家户账户人口 == Σ池人口").isEqualTo(poolPopulation(after));
    assertThat(accountLabor).as("Σ家户账户劳动 == Σ池劳动").isEqualTo(poolLabor(after));
    for (var entry : after.classPools().entrySet()) {
      long members = 0L;
      for (HouseholdProductionAccount account : after.householdAccounts().values()) {
        if (entry.getKey().equals(account.poolId())) {
          members += account.population();
        }
      }
      assertThat(entry.getValue().population())
          .as("池人口 == Σ成员: %s", entry.getKey())
          .isEqualTo(members);
    }
  }

  @Test
  void deathsCannotMakeAnyHouseholdNegative() {
    ClassFirstState before = state(1L, 1L);
    EconomyData economy = economyWithClasses(before);
    ClassFirstPopulationWriteback.Applied applied =
        ClassFirstPopulationWriteback.apply(
            before,
            economy,
            List.of(new LotChange(new PeopleLotId("rural:0_0:MALE:1"), HEX, 0L, 2L)));
    assertThat(applied.deaths()).isEqualTo(2L);
    assertThat(poolPopulation(applied.state())).as("两个人死一个 1 人户 + 一个 1 人户 ⇒ 0").isZero();
    for (HouseholdProductionAccount account : applied.state().householdAccounts().values()) {
      assertThat(account.population()).as("不得出现负人口").isZero();
      assertThat(account.laborUnits()).as("人口归零 ⇒ 劳动归零").isZero();
    }
    assertThat(poolLabor(applied.state())).isZero();
  }

  private static long poolPopulation(ClassFirstState state) {
    long total = 0L;
    for (var pool : state.classPools().values()) {
      total += pool.population();
    }
    return total;
  }

  private static long poolLabor(ClassFirstState state) {
    long total = 0L;
    for (var pool : state.classPools().values()) {
      total += pool.labor();
    }
    return total;
  }

  private static ClassFirstState state(long firstPopulation, long secondPopulation) {
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            900L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotModel.Lender lender =
        new PilotModel.Lender("TEST-LENDER", 1_000L, Map.of(), 20L, 60L, 1000L);
    PilotConfig config = PilotConfig.tenancyAgriculture(lender, policy);
    Map<String, Long> goods = new LinkedHashMap<>();
    goods.put(PilotModel.GRAIN, 100_000L);
    return new ClassFirstPilotEngine(
            config,
            List.of(
                new PilotModel.Household(
                    "H1",
                    "H1",
                    PilotModel.LABORER_ID,
                    firstPopulation,
                    500L,
                    goods,
                    0L,
                    0L,
                    0L,
                    1000L),
                new PilotModel.Household(
                    "H2",
                    "H2",
                    PilotModel.LABORER_ID,
                    secondPopulation,
                    500L,
                    goods,
                    0L,
                    0L,
                    0L,
                    1000L)))
        .snapshot();
  }

  /** 从 classfirst 家户账户造出 {@code economy.classes} 的 seed 映射（位置只用于定位，人口不是权威）。 */
  private static EconomyData economyWithClasses(ClassFirstState state) {
    Map<HouseholdId, ClassRow> rows = new LinkedHashMap<>();
    for (HouseholdProductionAccount account : state.householdAccounts().values()) {
      HouseholdId id = HouseholdId.parse(account.householdId());
      var pool = state.classPools().get(account.poolId());
      rows.put(
          id,
          new ClassRow(
              id,
              new CohortKey(HEX, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
              account.population(),
              account.laborUnits(),
              (int) account.participationSharePerMille(),
              0L,
              List.of(),
              Map.of(),
              Map.of(),
              0L));
      assertThat(pool).as("账户指名的池必须存在").isNotNull();
    }
    return EconomyData.empty().withClasses(rows);
  }
}
