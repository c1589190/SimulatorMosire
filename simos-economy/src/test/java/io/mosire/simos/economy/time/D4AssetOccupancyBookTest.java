package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D4 §5 / P1–P3 / P6 / P8–P12 / P23：统一资产预算的整数守恒、具名拒绝与释放回填。 */
class D4AssetOccupancyBookTest {

  private static final IndustryId FARM = IndustryHexKeys.id("d4-occupancy-farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-d4-occupancy");
  private static final ProductionLocation L0 = new ProductionLocation("0_0");
  private static final ProductionLocation L3 = new ProductionLocation("3_4");
  private static final ProductionUnitId A = new ProductionUnitId("d4-unit-a");
  private static final ProductionUnitId B = new ProductionUnitId("d4-unit-b");
  private static final ProductionProcess UNIT_A = unit(A, L0);
  private static final ProductionProcess UNIT_B = unit(B, L0);
  private static final OwnershipStake LAND_100 = share(AssetKind.LAND, L0, 100L, 0L);

  @Test
  void singleUnitGetsTheWholePoolAndNeverChangesOwnershipStake() {
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(A, L0, 0L, 1L, Map.of(AssetKind.LAND, 30L))),
            Map.of(),
            Map.of(LAND_100.id(), LAND_100),
            Map.of(A, Set.of(AssetRightKind.OWNED)),
            Map.of(A, UNIT_A),
            0L);

    assertThat(budget.allocationOf(A))
        .as("P1：单实例得到整池 100，而不是只得到请求量 30")
        .containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(budget.grantsToWrite()).hasSize(1);
    assertThat(budget.grantsToWrite().get(0).quantity()).isEqualTo(100L);
    assertThat(budget.unallocatedOf(scope(0L, 1L, AssetKind.LAND)))
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
    assertThat(budget.rejectionOf(A)).isEmpty();
    assertThat(LAND_100.quantity()).as("D4-I1：grant 不写回份额数量").isEqualTo(100L);
  }

  @Test
  void twoUnitsShareTheSamePoolByTheirExplicitRequestAmounts() {
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(
                request(A, L0, 0L, 3L, Map.of(AssetKind.LAND, 60L)),
                request(B, L0, 0L, 3L, Map.of(AssetKind.LAND, 40L))),
            Map.of(),
            Map.of(LAND_100.id(), LAND_100),
            Map.of(),
            Map.of(A, UNIT_A, B, UNIT_B),
            0L);

    assertThat(budget.allocationOf(A)).containsExactly(Map.entry(AssetKind.LAND, 60L));
    assertThat(budget.allocationOf(B)).containsExactly(Map.entry(AssetKind.LAND, 40L));
    assertThat(sum(budget, A, B)).as("P6：Σ = 100，不是每个 unit 各 100").isEqualTo(100L);
    assertThat(budget.unallocatedOf(scope(0L, 3L, AssetKind.LAND)))
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
    assertThat(budget.grantsToWrite()).hasSize(2);
    assertThat(budget.grantsToWrite())
        .allSatisfy(g -> assertThat(g.shareId()).isEqualTo(LAND_100.id()));
    assertThat(budget)
        .as("D4-I13：同输入同输出")
        .isEqualTo(
            AssetOccupancyBook.allocate(
                List.of(
                    request(B, L0, 0L, 3L, Map.of(AssetKind.LAND, 40L)),
                    request(A, L0, 0L, 3L, Map.of(AssetKind.LAND, 60L))),
                Map.of(),
                Map.of(LAND_100.id(), LAND_100),
                Map.of(),
                Map.of(B, UNIT_B, A, UNIT_A),
                0L));
  }

  @Test
  void twoUnitsWithoutRequestFactsGetNamedMissingAndNeverGetThePool() {
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(A, L0, 0L, 3L, Map.of()), request(B, L0, 0L, 3L, Map.of())),
            Map.of(),
            Map.of(LAND_100.id(), LAND_100),
            Map.of(),
            Map.of(A, UNIT_A, B, UNIT_B),
            0L);

    assertThat(budget.rejectionOf(A)).contains("asset-occupancy-request-missing:" + A.value());
    assertThat(budget.rejectionOf(B)).contains("asset-occupancy-request-missing:" + B.value());
    assertThat(budget.allocationOf(A)).as("缺请求 ⇒ 不默认 1/不复制整池").isEmpty();
    assertThat(budget.allocationOf(B)).isEmpty();
    assertThat(budget.grantsToWrite()).isEmpty();
    assertThat(budget.unallocatedOf(scope(0L, 3L, AssetKind.LAND)))
        .as("P23：整池保留在 unallocated")
        .containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(LAND_100.quantity()).isEqualTo(100L);
  }

  @Test
  void activeGrantsAreCommittedBeforeNewDemand() {
    AssetShareId share = LAND_100.id();
    AssetOccupancyGrant existing =
        grant(A, share, 40L, 0L, 3L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(
                request(A, L0, 0L, 3L, Map.of(AssetKind.LAND, 40L)),
                request(B, L0, 0L, 3L, Map.of(AssetKind.LAND, 60L))),
            Map.of(existing.id(), existing),
            Map.of(share, LAND_100),
            Map.of(),
            Map.of(A, UNIT_A, B, UNIT_B),
            0L);

    assertThat(budget.committedOf(A)).containsExactly(Map.entry(AssetKind.LAND, 40L));
    assertThat(budget.allocationOf(A)).containsExactly(Map.entry(AssetKind.LAND, 40L));
    assertThat(budget.allocationOf(B)).containsExactly(Map.entry(AssetKind.LAND, 60L));
    assertThat(budget.grantsToWrite())
        .as("已有 ACTIVE 计入 committed ⇒ 本批只为 B 生成增量")
        .hasSize(1)
        .allSatisfy(g -> assertThat(g.unitId()).isEqualTo(B));
    assertThat(budget.occupied().get(scope(0L, 3L, AssetKind.LAND)))
        .containsEntry(AssetKind.LAND, 40L);
  }

  @Test
  void committedGrantsFromTwoUnitsOnTheSameShareAreAllowed() {
    AssetShareId share = LAND_100.id();
    AssetOccupancyGrant first = grant(A, share, 50L, 0L, 3L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyGrant second = grant(B, share, 50L, 0L, 3L, AssetOccupancyStatus.ACTIVE, -1L, 1L);

    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(
                request(A, L0, 0L, 3L, Map.of(AssetKind.LAND, 50L)),
                request(B, L0, 0L, 3L, Map.of(AssetKind.LAND, 50L))),
            Map.of(first.id(), first, second.id(), second),
            Map.of(share, LAND_100),
            Map.of(),
            Map.of(A, UNIT_A, B, UNIT_B),
            0L);

    assertThat(budget.committedOf(A)).containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(budget.committedOf(B)).containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(budget.allocationOf(A)).containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(budget.allocationOf(B)).containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(budget.unallocatedOf(scope(0L, 3L, AssetKind.LAND)))
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
  }

  @Test
  void sameUnitSameShareOverlapIsNamedRejected() {
    OwnershipStake share200 = share(AssetKind.LAND, L0, 200L, 1L);
    AssetOccupancyGrant existing =
        grant(A, share200.id(), 100L, 0L, 3L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(A, L0, 0L, 3L, Map.of(AssetKind.LAND, 100L))),
            Map.of(existing.id(), existing),
            Map.of(share200.id(), share200),
            Map.of(),
            Map.of(A, UNIT_A),
            0L);

    assertThat(budget.rejectionOf(A))
        .contains("asset-occupancy-overlap:" + share200.id().value() + ":" + A.value());
    assertThat(budget.grantsToWrite()).isEmpty();
    assertThat(budget.unallocatedOf(scope(0L, 3L, AssetKind.LAND)))
        .containsExactly(Map.entry(AssetKind.LAND, 100L));
  }

  @Test
  void horizonUsesHalfOpenTickAndCycleIntervals() {
    AssetOccupancyHorizonPolicy.Interval cycle =
        AssetOccupancyHorizonPolicy.intervalOf(AssetKind.LAND, 0L, 0L, 3L);
    AssetOccupancyHorizonPolicy.Interval tick =
        AssetOccupancyHorizonPolicy.intervalOf(AssetKind.SMALL_TOOL, 0L, 0L, 3L);

    assertThat(cycle).isEqualTo(new AssetOccupancyHorizonPolicy.Interval(0L, 3L));
    assertThat(tick).isEqualTo(new AssetOccupancyHorizonPolicy.Interval(0L, 1L));
    assertThat(
            new AssetOccupancyScope(FARM, OPERATOR, L0, AssetKind.SMALL_TOOL, 0L, 1L)
                .overlaps(1L, 2L))
        .as("半开区间 [0,1) 与 [1,2) 不重叠")
        .isFalse();
  }

  @Test
  void expiryReleasesAndBackfillsUnallocatedBeforeNextAllocation() {
    AssetShareId share = LAND_100.id();
    AssetOccupancyGrant oldGrant =
        grant(A, share, 100L, 0L, 3L, AssetOccupancyStatus.ACTIVE, -1L, 0L);

    AssetOccupancyBudget afterRelease =
        AssetOccupancyBook.allocate(
            List.of(requestZero(B, L0, 3L, 6L)),
            Map.of(oldGrant.id(), oldGrant),
            Map.of(share, LAND_100),
            Map.of(),
            Map.of(B, UNIT_B),
            3L);

    assertThat(afterRelease.grantsToRelease()).hasSize(1);
    AssetOccupancyGrant released = afterRelease.grantsToRelease().get(0);
    assertThat(released.id()).isEqualTo(oldGrant.id());
    assertThat(released.status()).isEqualTo(AssetOccupancyStatus.RELEASED);
    assertThat(released.releasedAtTick()).isEqualTo(3L);
    assertThat(released.quantity()).isEqualTo(100L);
    assertThat(afterRelease.unallocatedOf(scope(3L, 6L, AssetKind.LAND)))
        .as("P9/P12：到期释放量回到后续预算的 unallocated")
        .containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(LAND_100.quantity()).isEqualTo(100L);

    AssetOccupancyBudget next =
        AssetOccupancyBook.allocate(
            List.of(request(B, L0, 3L, 6L, Map.of(AssetKind.LAND, 100L))),
            Map.of(),
            Map.of(share, LAND_100),
            Map.of(),
            Map.of(B, UNIT_B),
            3L);
    assertThat(next.allocationOf(B)).containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(next.grantsToWrite()).hasSize(1);
    assertThat(next.grantsToWrite().get(0).quantity()).isEqualTo(100L);
    assertThat(next.unallocatedOf(scope(3L, 6L, AssetKind.LAND)))
        .containsExactly(Map.entry(AssetKind.LAND, 0L));
  }

  @Test
  void budgetMapsAreReadOnlyAndDeterministicallyOrdered() {
    AssetOccupancyBudget budget =
        AssetOccupancyBook.allocate(
            List.of(request(B, L0, 0L, 3L, Map.of(AssetKind.LAND, 100L))),
            Map.of(),
            Map.of(LAND_100.id(), LAND_100),
            Map.of(),
            Map.of(B, UNIT_B),
            0L);
    assertThatThrownBy(() -> budget.allocations().put(B, Map.of(AssetKind.LAND, 1L)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(budget.grantsToWrite()).isUnmodifiable();
  }

  private static long sum(AssetOccupancyBudget budget, ProductionUnitId... units) {
    long total = 0L;
    for (ProductionUnitId unit : units) {
      total += budget.allocationOf(unit).getOrDefault(AssetKind.LAND, 0L);
    }
    return total;
  }

  private static AssetOccupancyRequest request(
      ProductionUnitId unit,
      ProductionLocation location,
      long from,
      long to,
      Map<AssetKind, Long> quantities) {
    return new AssetOccupancyRequest(
        unit,
        FARM,
        OPERATOR,
        location,
        from,
        to,
        quantities,
        OptionalLong.empty(),
        Optional.empty());
  }

  private static AssetOccupancyRequest requestZero(
      ProductionUnitId unit, ProductionLocation location, long from, long to) {
    return new AssetOccupancyRequest(
        unit, FARM, OPERATOR, location, from, to, Map.of(), OptionalLong.of(0L), Optional.empty());
  }

  private static AssetOccupancyScope scope(long from, long to, AssetKind kind) {
    return new AssetOccupancyScope(FARM, OPERATOR, L0, kind, from, to);
  }

  private static OwnershipStake share(
      AssetKind kind, ProductionLocation location, long quantity, long sequence) {
    return new OwnershipStake(
        OwnershipStake.idOf(
            FARM, kind, OPERATOR, OPERATOR, AssetRightKind.OWNED, location, sequence),
        FARM,
        kind,
        OPERATOR,
        OPERATOR,
        quantity,
        AssetRightKind.OWNED,
        location);
  }

  private static ProductionProcess unit(ProductionUnitId id, ProductionLocation location) {
    return new ProductionProcess(
        id,
        FARM,
        location,
        OPERATOR,
        FARM.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static AssetOccupancyGrant grant(
      ProductionUnitId unit,
      AssetShareId share,
      long quantity,
      long from,
      long to,
      AssetOccupancyStatus status,
      long releasedAt,
      long sequence) {
    return new AssetOccupancyGrant(
        new AssetOccupancyGrantId("grant-" + unit.value() + "-" + sequence),
        share,
        unit,
        quantity,
        from,
        to,
        status,
        releasedAt);
  }
}
