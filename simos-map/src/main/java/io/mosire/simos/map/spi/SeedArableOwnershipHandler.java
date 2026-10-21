package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreas;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * {@code map.SeedArableOwnership} 命令处理器（D-032 L4）：把一份逐 hex 的 owner→quantity 账本写进 Map 的 arable
 * component {@code owner} 属性。
 *
 * <pre>{@code
 * {"entries":[
 *   {"hex":{"q":0,"r":0},
 *    "owners":[{"owner":"hh-0_0-rural-landed_peasant","quantityMilliMu":3100000}]}
 * ]}
 * }</pre>
 *
 * <p>★ 这是 GM-only 的世界构造命令（不实现 {@code DmUsableCommand}），命令面可见、决策令/RegisterEffect 不可嵌入。
 *
 * <p>★ 逐 hex 调 {@link LandComponentOperations#setArableOwners}；未知/重复 hex、空白 owner、非正数量、同 hex 重复
 * owner、以及 {@code Σ ownerQuantity != Map 该 hex 现有 arable 总量} 都具名拒绝（{@code Rejected}，零 revision）。
 */
public final class SeedArableOwnershipHandler implements CommandHandler, CommandTargets {

  /** 命令类型字面量。 */
  public static final String TYPE = "map.SeedArableOwnership";

  private static final Logger LOG = MapLog.edit();

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    JsonNode entries = requireEntries(payload);
    List<String> targets = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("entries 的元素必须是对象: " + entry);
      }
      HexCoord hex = MapPayloads.requireHex(entry, "hex");
      targets.add(ResourcePaths.hex(mapId, hex.q(), hex.r()));
    }
    return List.copyOf(targets);
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap base = MapSnapshots.of(state).map();
    int hexesForLog = 0;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      JsonNode entries = requireEntries(payload);
      hexesForLog = entries.size();
      GameMap applied = applyOwners(base, entries);
      long ownersForLog = 0L;
      for (JsonNode entry : entries) {
        ownersForLog += entry.get("owners").size();
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MAP_SEED_ARABLE_OWNERSHIP_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "hexes",
                  entries.size(),
                  "owners",
                  ownersForLog));
      return new HandlerOutcome.Applied(MapChangeSet.between(base, applied));
    } catch (IllegalArgumentException | IllegalStateException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MAP_SEED_ARABLE_OWNERSHIP_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "hexes",
                  hexesForLog == 0 ? "-" : hexesForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static JsonNode requireEntries(JsonNode payload) {
    JsonNode entries = payload.get("entries");
    if (entries == null || entries.isNull() || !entries.isArray()) {
      throw new IllegalArgumentException("字段 entries 必须是 [{hex,owners}…] 数组: " + payload);
    }
    return entries;
  }

  private static GameMap applyOwners(GameMap base, JsonNode entries) {
    GameMap current = base;
    Set<HexCoord> seen = new LinkedHashSet<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw new IllegalArgumentException("entries 的元素必须是对象: " + entry);
      }
      HexCoord hex = MapPayloads.requireHex(entry, "hex");
      if (!seen.add(hex)) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-duplicate-hex: 同一 hex 重复: " + hex);
      }
      HexCell cell = current.hexes().get(hex);
      if (cell == null) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-unknown-hex: hex 不在 Map 中: " + hex);
      }
      Map<String, Long> ownerQuantities = parseOwners(hex, entry);
      long expected = HexAreas.arableMilliMu(cell, current.terrainAt(hex));
      long actual = 0L;
      try {
        for (long quantity : ownerQuantities.values()) {
          actual = Math.addExact(actual, quantity);
        }
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-total-overflow: owner 数量合计溢出: hex=" + hex, overflow);
      }
      if (actual != expected) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-arable-mismatch: ΣownerQuantity="
                + actual
                + " != Map arable 总量="
                + expected
                + "，hex="
                + hex);
      }
      var land = LandComponentOperations.setArableOwners(cell.land(), hex, ownerQuantities);
      Map<HexCoord, HexCell> hexes = new LinkedHashMap<>(current.hexes());
      hexes.put(hex, cell.withLand(land));
      current = current.withHexes(hexes);
      if (LOG.isTraceEnabled()) {
        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "MAP_SEED_ARABLE_OWNERSHIP_HEX",
                    MapLogSource.MAP_EDIT,
                    "hex",
                    hex,
                    "owners",
                    ownerQuantities.size(),
                    "quantityMilliMu",
                    actual));
      }
    }
    return current;
  }

  private static Map<String, Long> parseOwners(HexCoord hex, JsonNode entry) {
    JsonNode owners = entry.get("owners");
    if (owners == null || owners.isNull() || !owners.isArray()) {
      throw new IllegalArgumentException(
          "seed-arable-ownership-owners-shape: owners 必须是 [{owner,quantityMilliMu}…] 数组: hex="
              + hex);
    }
    Map<String, Long> result = new LinkedHashMap<>();
    for (JsonNode node : owners) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-owner-shape: owners 的元素必须是 {owner,quantityMilliMu} 对象: hex="
                + hex
                + " node="
                + node);
      }
      JsonNode ownerNode = node.get("owner");
      if (ownerNode == null || !ownerNode.isTextual() || ownerNode.asText().isBlank()) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-owner-blank: owner 必须是非空白字符串: hex=" + hex);
      }
      JsonNode quantityNode = node.get("quantityMilliMu");
      if (quantityNode == null
          || !quantityNode.isIntegralNumber()
          || !quantityNode.canConvertToLong()) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-quantity-shape: quantityMilliMu 必须是整数: hex=" + hex);
      }
      long quantity = quantityNode.asLong();
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-quantity-nonpositive: quantityMilliMu 必须 > 0: hex="
                + hex
                + " owner="
                + ownerNode.asText()
                + " quantity="
                + quantity);
      }
      String owner = ownerNode.asText();
      if (result.putIfAbsent(owner, quantity) != null) {
        throw new IllegalArgumentException(
            "seed-arable-ownership-duplicate-owner: 同一 hex 重复 owner: hex="
                + hex
                + " owner="
                + owner);
      }
    }
    return result;
  }
}
