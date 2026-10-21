package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.HouseholdOwnershipLookup;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 C2：同 key 合并计划</b>。
 *
 * <p>判据：mergeKey = (hex, cultureKey, ownershipClass)，不含 productionMode/classNature/currentPosition；
 * survivor = 人口降序 → 本 hex LAND 降序 → HouseholdId 升序；0 人口壳不参与；不同 key 不混组； 缺失 culture key 用显式 {@code
 * missing()}，不猜；无 class membership/mode 的户仍按资产派生 class 合并。
 */
class HouseholdMergeBookTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);
  private static final HouseholdId A = HouseholdId.parse("hh-merge-a");
  private static final HouseholdId B = HouseholdId.parse("hh-merge-b");
  private static final HouseholdCultureKey CULTURE =
      new HouseholdCultureKey("latium", "roman", List.of("patrilineal"), "本地稻农");
  private static final HouseholdCultureKey OTHER_CULTURE =
      new HouseholdCultureKey("graecia", "roman", List.of(), "山地牧民");
  private static final HouseholdCultureKeyLookup SAME_CULTURE = household -> Optional.of(CULTURE);

  @Test
  void sameOwnershipClassMergesAndSurvivorIsLargestPopulationThenLandThenId() {
    Fixture base =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 80L)), land(Map.of(A, 20L, B, 10L)));
    List<HouseholdMergeBook.MergePlan> plans = plan(base);

    assertThat(plans).as("同 key 两户 ⇒ 恰一个计划").hasSize(1);
    HouseholdMergeBook.MergePlan plan = plans.get(0);
    assertThat(plan.key().hex()).isEqualTo(HEX);
    assertThat(plan.key().ownershipClass()).isEqualTo(OwnershipClass.OWNER);
    assertThat(
            Arrays.stream(HouseholdMergeBook.MergeKey.class.getRecordComponents())
                .map(component -> component.getName()))
        .as("merge key 只有 hex/cultureKey/ownershipClass，不含 productionMode")
        .containsExactly("hex", "cultureKey", "ownershipClass");
    assertThat(plan.survivor()).as("人口最多者 survival").isEqualTo(A);
    assertThat(plan.members())
        .extracting(HouseholdMergeBook.Member::household)
        .containsExactly(A, B);

    Fixture tiePop =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 100L)), land(Map.of(A, 10L, B, 50L)));
    assertThat(plan(tiePop).get(0).survivor()).as("同人口 ⇒ 本 hex LAND 多者 survival").isEqualTo(B);

    Fixture tieAll = base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 100L)), land(Map.of()));
    assertThat(plan(tieAll).get(0).survivor())
        .as("同人口同 LAND ⇒ HouseholdId 小者 survival")
        .isEqualTo(A);
  }

  @Test
  void differentCultureHexOrOwnershipClassDoesNotMerge() {
    Fixture base = base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 80L)), land(Map.of()));
    HouseholdCultureKeyLookup different =
        household -> Optional.of(household.equals(A) ? CULTURE : OTHER_CULTURE);
    assertThat(HouseholdMergeBook.plan(base.data(), base.land(), different, ownership(base)))
        .as("不同 cultureKey 不合并")
        .isEmpty();

    Fixture otherHex =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, OTHER_HEX, 80L)), land(Map.of()));
    assertThat(plan(otherHex)).as("不同 hex 不合并").isEmpty();

    Fixture differentOwnership =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 80L)), land(Map.of(A, 10L)));
    assertThat(plan(differentOwnership)).as("不同 ownershipClass（OWNER vs PROLETARIAN）不合并").isEmpty();
  }

  @Test
  void zeroPopulationShellsAreExcludedButMissingClassMembershipIsNotAClassSource() {
    Fixture zero = base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 0L)), land(Map.of()));
    assertThat(plan(zero)).as("0 人口壳不得进入 merge 候选/survivor").isEmpty();

    Fixture noMembership =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 80L)), land(Map.of(A, 10L, B, 10L)));
    assertThat(plan(noMembership))
        .as("无 class membership/currentPosition 的户仍按 LAND 派生的 OWNER 合并")
        .hasSize(1);
  }

  @Test
  void missingCultureLookupUsesExplicitMissingKeyNotAGuess() {
    Fixture base =
        base(Map.of(A, row(A, HEX, 100L), B, row(B, HEX, 80L)), land(Map.of(A, 10L, B, 10L)));
    List<HouseholdMergeBook.MergePlan> plans =
        HouseholdMergeBook.plan(
            base.data(), base.land(), HouseholdCultureKeyLookup.unavailable(), ownership(base));
    assertThat(plans).hasSize(1);
    assertThat(plans.get(0).key().cultureKey())
        .as("culture lookup 缺失 ⇒ 显式 missing key，不用 SocialClassId/生产方式猜")
        .isEqualTo(HouseholdCultureKey.missing());
  }

  @Test
  void planIsStableUnderInputMapIterationOrder() {
    Map<HouseholdId, HouseholdEconomy> forward = new LinkedHashMap<>();
    forward.put(A, row(A, HEX, 100L));
    forward.put(B, row(B, HEX, 80L));
    Map<HouseholdId, HouseholdEconomy> backward = new LinkedHashMap<>();
    backward.put(B, row(B, HEX, 80L));
    backward.put(A, row(A, HEX, 100L));
    Map<LandHoldingKey, LandHolding> holdings = land(Map.of(A, 10L, B, 10L));

    HouseholdMergeBook.MergePlan first = plan(base(forward, holdings)).get(0);
    HouseholdMergeBook.MergePlan second = plan(base(backward, holdings)).get(0);
    assertThat(second).as("打乱输入 Map 序 ⇒ 成员/survivor 不变").isEqualTo(first);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private record Fixture(EconomyData data, Map<LandHoldingKey, LandHolding> land) {}

  private static List<HouseholdMergeBook.MergePlan> plan(Fixture fixture) {
    return HouseholdMergeBook.plan(
        fixture.data(), fixture.land(), SAME_CULTURE, ownership(fixture));
  }

  /** 测试专用派生读口：正 LAND ⇒ OWNER；否则 PROLETARIAN。 */
  private static HouseholdOwnershipLookup ownership(Fixture fixture) {
    return household ->
        fixture.land().values().stream()
                    .filter(holding -> holding.owner().equals(household))
                    .mapToLong(LandHolding::quantityMilliMu)
                    .sum()
                > 0L
            ? OwnershipClass.OWNER
            : OwnershipClass.PROLETARIAN;
  }

  private static Fixture base(
      Map<HouseholdId, HouseholdEconomy> rows, Map<LandHoldingKey, LandHolding> holdings) {
    EconomyData data =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(rows);
    return new Fixture(data, holdings);
  }

  private static Map<LandHoldingKey, LandHolding> land(Map<HouseholdId, Long> byHousehold) {
    Map<LandHoldingKey, LandHolding> holdings = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : byHousehold.entrySet()) {
      if (entry.getValue() > 0L) {
        holdings.put(
            new LandHoldingKey(HEX, entry.getKey()),
            new LandHolding(entry.getKey(), HEX, entry.getValue()));
      }
    }
    return holdings;
  }

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long population) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }
}
