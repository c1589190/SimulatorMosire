package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.spi.EconomyTransferOwnershipStakeHandler;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** D4 §4.2 / P24b / P25：地点必填、规范格式、身份与份额合并键。 */
class D4OwnershipStakeLocationTest {

  private static final IndustryId FARM = IndustryHexKeys.id("d4-location-farm", 0, 0);
  private static final IndustryId FARM_NEXT = IndustryHexKeys.id("d4-location-farm-next", 3, 4);
  private static final ActorRef OWNER =
      new ActorRef(ActorKind.ORGANIZATION, "org-d4-location-owner");
  private static final ActorRef TENANT =
      new ActorRef(ActorKind.ORGANIZATION, "org-d4-location-tenant");
  private static final ProductionLocation L0 = new ProductionLocation("0_0");
  private static final ProductionLocation L1 = new ProductionLocation("3_4");

  @Test
  void rejectsMissingUnspecifiedAndNonCanonicalLocations() {
    for (String bad : List.of("foo", "1", "1_", "a_b", "01_2", "+1_2", "-0_1")) {
      assertThatThrownBy(() -> stake(new ProductionLocation(bad), AssetKind.LAND, 100L, 0L))
          .as("D4 §4.2/R-15：非规范地点必须具名拒绝: %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("asset-location-missing");
    }

    assertThatThrownBy(() -> stake(null, AssetKind.LAND, 100L, 0L))
        .as("地点缺失（null）⇒ asset-location-missing")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asset-location-missing");
    assertThatThrownBy(() -> stake(ProductionLocation.UNSPECIFIED, AssetKind.LAND, 100L, 0L))
        .as("地点 UNSPECIFIED ⇒ asset-location-missing")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asset-location-missing");

    assertThat(stake(L0, AssetKind.LAND, 100L, 0L).location()).isEqualTo(L0);
    ProductionLocation negative = new ProductionLocation("-3_2");
    assertThat(stake(negative, AssetKind.LAND, 100L, 1L).location())
        .as("负坐标的规范 <q>_<r> 必须合法")
        .isEqualTo(negative);
  }

  @Test
  void assetShareIdentityIncludesTheLocation() {
    AssetShareId atZero = idAt(L0, 0L);
    AssetShareId atThreeFour = idAt(L1, 0L);

    assertThat(atZero)
        .as("同一 (industry, asset, owner, operator, kind) 在不同地点必须得到不同 AssetShareId")
        .isNotEqualTo(atThreeFour);
    assertThat(OwnershipStake.idEmbedsLocation(atZero, L0)).isTrue();
    assertThat(OwnershipStake.idEmbedsLocation(atThreeFour, L1)).isTrue();
    assertThat(OwnershipStake.idEmbedsLocation(atZero, L1)).isFalse();
  }

  @Test
  void moveAndSplitDoNotMergeCrossLocationStakes() {
    OwnershipStake source = stake(L0, AssetKind.LAND, 100L, 0L);
    OwnershipStake differentLocation =
        stake(TENANT, OWNER, AssetRightKind.TENANCY, FARM, L1, 70L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(source.id(), source);
    shares.put(differentLocation.id(), differentLocation);

    AssetShareId moved =
        OwnershipStakeBook.transfer(
            shares, Map.of(), source.id(), 50L, TENANT, OWNER, AssetRightKind.TENANCY);

    assertThat(moved)
        .as("Move 的目标合并键包含 location ⇒ 不得落到 3_4 的同 tuple 行")
        .isNotEqualTo(differentLocation.id());
    assertThat(shares).hasSize(3);
    assertThat(shares.get(moved).location()).isEqualTo(L0);
    assertThat(shares.get(moved).quantity()).isEqualTo(50L);
    assertThat(shares.get(source.id()).location()).isEqualTo(L0);
    assertThat(shares.get(source.id()).quantity()).isEqualTo(50L);
    assertThat(shares.get(differentLocation.id()))
        .as("3_4 的原有同 tuple 份额逐值不动")
        .isEqualTo(differentLocation);

    Map<AssetShareId, OwnershipStake> splitShares = new LinkedHashMap<>();
    splitShares.put(source.id(), source);
    splitShares.put(differentLocation.id(), differentLocation);
    AssetShareId split =
        OwnershipStakeBook.split(
            splitShares, Map.of(), source.id(), 40L, TENANT, OWNER, AssetRightKind.TENANCY);

    assertThat(split).isNotEqualTo(differentLocation.id());
    assertThat(splitShares).hasSize(3);
    assertThat(splitShares.get(split).location()).isEqualTo(L0);
    assertThat(splitShares.get(split).quantity()).isEqualTo(40L);
    assertThat(splitShares.get(source.id()).quantity()).isEqualTo(60L);
    assertThat(splitShares.get(differentLocation.id())).isEqualTo(differentLocation);
  }

  @Test
  void rebuildKeepsTheSourceLocationAndDoesNotMergeCrossLocationStakes() {
    OwnershipStake source = stake(L0, AssetKind.LAND, 100L, 0L);
    OwnershipStake existingDifferentLocation =
        stake(TENANT, OWNER, AssetRightKind.OWNED, FARM_NEXT, L1, 80L, 1L);
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    shares.put(source.id(), source);
    shares.put(existingDifferentLocation.id(), existingDifferentLocation);
    Map<IndustryId, IndustryDefinitionView> industries =
        Map.of(FARM, plan(FARM), FARM_NEXT, plan(FARM_NEXT));

    List<AssetShareId> rebuilt =
        OwnershipStakeBook.rebuild(
            shares,
            industries,
            List.of(
                new OwnershipStakeBook.RebuildMove(
                    source.id(), 100L, FARM_NEXT, TENANT, OWNER, AssetRightKind.OWNED)));

    assertThat(rebuilt).hasSize(1);
    AssetShareId destination = rebuilt.get(0);
    assertThat(destination)
        .as("Rebuild 的目标合并键包含 location ⇒ 不得合并到 3_4 的既有份额")
        .isNotEqualTo(existingDifferentLocation.id());
    assertThat(shares.get(destination).location()).isEqualTo(L0);
    assertThat(shares.get(destination).quantity()).isEqualTo(100L);
    assertThat(shares.get(existingDifferentLocation.id()))
        .as("另一地点的同 tuple 份额逐值不动")
        .isEqualTo(existingDifferentLocation);
  }

  @Test
  void transferCommandRejectsRelocationByName() {
    // ★ D-032 RA1b2b：LAND 不再允许出现在 EconomyData.assetShares（唯一权威是
    //   landArableSupply/landHoldings）。本用例只验证“命令改地点 ⇒ 具名拒绝”，与资产种类无关 ⇒
    //   改用普通非 LAND 资产（CATTLE）构造持久份额，判据强度不变。
    OwnershipStake source =
        new OwnershipStake(
            OwnershipStake.idOf(FARM, AssetKind.CATTLE, OWNER, OWNER, AssetRightKind.OWNED, L0, 0L),
            FARM,
            AssetKind.CATTLE,
            OWNER,
            OWNER,
            100L,
            AssetRightKind.OWNED,
            L0);
    EconomyData data =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "d4-location-command",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty())))
            .withOwnershipStakes(Map.of(source.id(), source));
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1L));
    SimulationState state =
        new SimulationState(
            new StateMeta(ref, SimosTimestamp.of(0L)),
            Map.of("economy", new EconomySnapshot(ref, SimosTimestamp.of(0L), data)),
            InMemoryInfoSystem.empty());

    HandlerOutcome outcome =
        new EconomyTransferOwnershipStakeHandler()
            .handle(
                state,
                "{\"share\":\""
                    + source.id().value()
                    + "\",\"quantity\":50,\"toOwner\":{\"kind\":\"ORGANIZATION\",\"id\":\"org-next\"},"
                    + "\"location\":\"3_4\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("TransferAssetShare 请求改地点必须具名拒绝，不得静默 relocate")
        .contains("asset-relocation-unsupported");
    assertThat(data.assetShares().get(source.id())).isEqualTo(source);
  }

  private static OwnershipStake stake(
      ProductionLocation location, AssetKind asset, long quantity, long sequence) {
    return stake(OWNER, OWNER, AssetRightKind.OWNED, FARM, location, quantity, sequence);
  }

  private static OwnershipStake stake(
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      IndustryId industry,
      ProductionLocation location,
      long quantity,
      long sequence) {
    AssetShareId id;
    if (location == null || !location.isSpecified()) {
      id = new AssetShareId("share-invalid-location-" + sequence);
    } else {
      try {
        id =
            OwnershipStake.idOf(
                industry, AssetKind.LAND, owner, operator, kind, location, sequence);
      } catch (IllegalArgumentException invalidLocation) {
        id = new AssetShareId("share-invalid-location-" + sequence);
      }
    }
    return new OwnershipStake(
        id, industry, AssetKind.LAND, owner, operator, quantity, kind, location);
  }

  private static AssetShareId idAt(ProductionLocation location, long sequence) {
    return OwnershipStake.idOf(
        FARM, AssetKind.LAND, OWNER, OWNER, AssetRightKind.OWNED, location, sequence);
  }

  private static IndustryDefinitionView plan(IndustryId industry) {
    return IndustryViews.industry(
        industry,
        "D4 location fixture",
        new RegimeId("tenant"),
        1L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "poor", 1_000)),
        new AllocationRule.Split(1_000, 0));
  }
}
