package io.mosire.simos.economy.land;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L4：economy 瞬态 {@link LandComponentLedger} 的正例/负例。
 *
 * <p>Map component.owner 是 LAND 唯一权威；本账本只把逐 component 的 owner 聚合成 {@code (hex, owner) →
 * quantity}，并从 arable component 求和派生每 hex 总量。缺 owner 必须具名 fail-closed。
 */
class LandComponentLedgerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HouseholdId A = HouseholdId.parse("hh-a");
  private static final HouseholdId B = HouseholdId.parse("hh-b");

  @Test
  void fromAggregatesOwnerComponentsAndDerivesArableTotals() {
    Map<HexCoord, HexLand> lands = new LinkedHashMap<>();
    lands.put(
        H0,
        new HexLand(
            Map.of(
                LandTypeId.ARABLE,
                new LandTypeArea(
                    LandTypeId.ARABLE,
                    List.of(
                        component("arable@0_0:0", 300L, "hh-a"),
                        component("arable@0_0:1", 200L, "hh-a"),
                        component("arable@0_0:2", 500L, "hh-b")),
                    3L))));
    lands.put(H1, LandComponentOperations.seedSingle(H1, LandTypeId.ARABLE, 700L, owner("hh-b")));

    LandComponentLedger ledger = LandComponentLedger.from(lands);

    assertThat(ledger.arableSupply().arableOfHex(H0)).hasValue(1_000L);
    assertThat(ledger.arableSupply().arableOfHex(H1)).hasValue(700L);
    assertThat(ledger.holdingsOrEmpty())
        .containsEntry(new LandHoldingKey(H0, A), new LandHolding(A, H0, 500L))
        .containsEntry(new LandHoldingKey(H0, B), new LandHolding(B, H0, 500L))
        .containsEntry(new LandHoldingKey(H1, B), new LandHolding(B, H1, 700L));
    assertThat(ledger.ownerQuantitiesAt(H0))
        .containsExactly(Map.entry("hh-a", 500L), Map.entry("hh-b", 500L));
    assertThat(ledger.hasOwnerAttrs()).isTrue();
  }

  @Test
  void missingOwnerFailsClosed() {
    Map<HexCoord, HexLand> lands = new LinkedHashMap<>();
    lands.put(
        H0,
        new HexLand(
            Map.of(
                LandTypeId.ARABLE,
                new LandTypeArea(
                    LandTypeId.ARABLE,
                    List.of(new LandComponent(new LandComponentId("arable@0_0:0"), 100L, Map.of())),
                    1L))));

    assertThatThrownBy(() -> LandComponentLedger.from(lands))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("land-owner-missing");
  }

  @Test
  void replaceHoldingsRejectsKeyMismatchAndPerHexConservationBreak() {
    LandComponentLedger ledger =
        LandComponentLedger.from(
            Map.of(
                H0,
                LandComponentOperations.seedSingle(H0, LandTypeId.ARABLE, 100L, owner("hh-a"))));

    LandHolding mismatchKey = new LandHolding(A, H1, 100L);
    assertThatThrownBy(() -> ledger.replaceHoldings(Map.of(new LandHoldingKey(H0, A), mismatchKey)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("land-holdings-key-mismatch");

    LandHolding tooMuch = new LandHolding(A, H0, 101L);
    assertThatThrownBy(() -> ledger.replaceHoldings(Map.of(tooMuch.key(), tooMuch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("land-arable-mismatch");
  }

  @Test
  void emptyLedgerIsLegalAndExposesNoHoldings() {
    LandComponentLedger empty = LandComponentLedger.empty();
    assertThat(empty.componentsByHex()).isEmpty();
    assertThat(empty.holdingsOrEmpty()).isEmpty();
    assertThat(empty.hasOwnerAttrs()).isTrue();
  }

  private static Map<LandAttributeKey, LandAttributeValue> owner(String owner) {
    return Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner));
  }

  private static LandComponent component(String id, long quantity, String owner) {
    return new LandComponent(new LandComponentId(id), quantity, owner(owner));
  }
}
