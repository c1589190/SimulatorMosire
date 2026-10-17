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
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D4 §7.0 / P27：AssetOccupancyLookup 的 revision/作用域 fail-closed 与只读视图稳定序。 */
class D4LookupReadoutTest {

  private static final IndustryId FARM = IndustryHexKeys.id("d4-lookup-farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-d4-lookup");
  private static final ProductionLocation L0 = new ProductionLocation("0_0");
  private static final ProductionLocation L3 = new ProductionLocation("3_4");
  private static final ProductionUnitId A = new ProductionUnitId("d4-lookup-unit-a");
  private static final ProductionUnitId B = new ProductionUnitId("d4-lookup-unit-b");
  private static final ProductionProcess UNIT_A = unit(A, L0);
  private static final ProductionProcess UNIT_B = unit(B, L0);
  private static final RevisionId R7 = new RevisionId(7L);

  @Test
  void lookupReturnsAuthoritativeSnapshotForMatchingRevision() {
    OwnershipStake share = share(L0, 100L, 0L);
    AssetOccupancyGrant grant =
        grant(A, share.id(), 100L, 0L, 1L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyLookupImpl lookup =
        new AssetOccupancyLookupImpl(
            Map.of(grant.id(), grant), Map.of(share.id(), share), Map.of(A, UNIT_A), R7);

    AssetOccupancySnapshot snapshot = lookup.occupancyOf(A, R7, 0L, 1L).orElseThrow();
    assertThat(snapshot.revision()).isEqualTo(R7);
    assertThat(snapshot.unit()).isEqualTo(A);
    assertThat(snapshot.location()).isEqualTo(L0);
    assertThat(snapshot.fromTick()).isZero();
    assertThat(snapshot.toTickExclusive()).isEqualTo(1L);
    assertThat(snapshot.allocation()).containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(snapshot.committed()).containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(snapshot.unallocated()).containsExactly(Map.entry(AssetKind.LAND, 0L));
    assertThat(snapshot.rejection()).isEmpty();
  }

  @Test
  void revisionMismatchIsNamedContractFault() {
    OwnershipStake share = share(L0, 100L, 0L);
    AssetOccupancyLookupImpl lookup =
        new AssetOccupancyLookupImpl(Map.of(), Map.of(share.id(), share), Map.of(A, UNIT_A), R7);

    assertThatThrownBy(() -> lookup.occupancyOf(A, new RevisionId(8L), 0L, 1L))
        .as("P27：跨 revision 的读数不得通过")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("asset-occupancy-snapshot-mismatch");
  }

  @Test
  void missingUnitFactsReturnEmptyAndEmptyAllocationIsALegalZero() {
    OwnershipStake share = share(L0, 100L, 0L);
    AssetOccupancyLookupImpl lookup =
        new AssetOccupancyLookupImpl(Map.of(), Map.of(share.id(), share), Map.of(A, UNIT_A), R7);

    assertThat(lookup.occupancyOf(new ProductionUnitId("d4-lookup-missing"), R7, 0L, 1L))
        .as("P27：无 unit 事实 ⇒ Optional.empty，不能读成 0")
        .isEmpty();

    AssetOccupancySnapshot snapshot = lookup.occupancyOf(A, R7, 0L, 1L).orElseThrow();
    assertThat(snapshot.allocation()).as("有权威结果但没有 grant ⇒ 合法空 Map").isEmpty();
    assertThat(snapshot.committed()).isEmpty();
    assertThat(snapshot.unallocated()).containsExactly(Map.entry(AssetKind.LAND, 100L));
    assertThat(snapshot.rejection()).isEmpty();
  }

  @Test
  void grantLocationMismatchIsNamedSnapshotMismatch() {
    OwnershipStake wrongLocation = share(L3, 100L, 0L);
    AssetOccupancyGrant grant =
        grant(A, wrongLocation.id(), 100L, 0L, 1L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyLookupImpl lookup =
        new AssetOccupancyLookupImpl(
            Map.of(grant.id(), grant),
            Map.of(wrongLocation.id(), wrongLocation),
            Map.of(A, UNIT_A),
            R7);

    assertThatThrownBy(() -> lookup.occupancyOf(A, R7, 0L, 1L))
        .as("P27：grant share 与 unit location 不一致 ⇒ ERROR snapshot-mismatch，不得静默跳过")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("asset-occupancy-snapshot-mismatch");
  }

  @Test
  void lookupUsesHalfOpenIntervalsWhenComputingCommitted() {
    OwnershipStake share = share(L0, 100L, 0L);
    AssetOccupancyGrant before =
        grant(A, share.id(), 50L, 0L, 1L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyGrant after =
        grant(A, share.id(), 50L, 1L, 2L, AssetOccupancyStatus.ACTIVE, -1L, 1L);
    AssetOccupancyLookupImpl lookup =
        new AssetOccupancyLookupImpl(
            Map.of(before.id(), before, after.id(), after),
            Map.of(share.id(), share),
            Map.of(A, UNIT_A),
            R7);

    assertThat(lookup.occupancyOf(A, R7, 0L, 1L).orElseThrow().committed())
        .as("半开 [0,1) 只计第一段")
        .containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(lookup.occupancyOf(A, R7, 1L, 2L).orElseThrow().committed())
        .as("半开 [1,2) 只计第二段")
        .containsExactly(Map.entry(AssetKind.LAND, 50L));
    assertThat(lookup.occupancyOf(A, R7, 2L, 3L).orElseThrow().committed()).as("区间外不计已占").isEmpty();
  }

  @Test
  void occupancyViewsAreStableAndReadOnly() {
    OwnershipStake share = share(L0, 100L, 0L);
    AssetOccupancyGrant grantB =
        grant(B, share.id(), 40L, 0L, 1L, AssetOccupancyStatus.ACTIVE, -1L, 0L);
    AssetOccupancyGrant grantA =
        grant(A, share.id(), 60L, 0L, 1L, AssetOccupancyStatus.ACTIVE, -1L, 1L);
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants = new LinkedHashMap<>();
    grants.put(grantB.id(), grantB);
    grants.put(grantA.id(), grantA);

    List<AssetOccupancyView> views =
        AssetOccupancyView.listOf(grants, Map.of(share.id(), share), Map.of(A, UNIT_A, B, UNIT_B));

    assertThat(views).extracting(AssetOccupancyView::unitId).containsExactly(A, B);
    assertThat(views.get(0).location()).isEqualTo(L0);
    assertThatThrownBy(() -> views.add(views.get(0)))
        .isInstanceOf(UnsupportedOperationException.class);

    AssetOccupancySnapshot snapshot =
        new AssetOccupancyLookupImpl(
                grants, Map.of(share.id(), share), Map.of(A, UNIT_A, B, UNIT_B), R7)
            .occupancyOf(A, R7, 0L, 1L)
            .orElseThrow();
    assertThatThrownBy(() -> snapshot.allocation().put(AssetKind.LAND, 1L))
        .as("P27：snapshot 的只读 map 不得被消费方改写")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static OwnershipStake share(ProductionLocation location, long quantity, long sequence) {
    return new OwnershipStake(
        OwnershipStake.idOf(
            FARM, AssetKind.LAND, OPERATOR, OPERATOR, AssetRightKind.OWNED, location, sequence),
        FARM,
        AssetKind.LAND,
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
