import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.spi.SetTerrainHandler;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** T3 真档探针：真 CoreSimos + 真 store（副本）+ 真 map.SetTerrain。 */
public final class T3RealArchiveProbe {

  private static final BranchId MAIN = new BranchId("main");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  public static void main(String[] args) throws Exception {
    Path storeA = Path.of(args[0]);
    Path storeB = Path.of(args[1]);

    GameMap genesis = load(storeA, 1);
    System.out.println("hexCount=" + genesis.hexes().size());
    System.out.println("blockCount=" + genesis.terrainBlocks().size());
    System.out.println("histogramBefore=" + histogram(genesis));

    List<HexCoord> ordered = new ArrayList<>(genesis.hexes().keySet());
    Set<HexCoord> targets = new LinkedHashSet<>(ordered.subList(0, 10));
    String current = genesis.terrainAt(ordered.get(0));
    String terrain = firstDifferent(current);
    System.out.println("targetHexes=" + targets);
    System.out.println("terrainBefore(first)=" + current + " terrain=" + terrain);

    // ── A：提交 + 重放 + 分割不变式 + 地形逐值 + 高度逐值 ──────────────────────
    GameMap after;
    long revisionsBefore;
    try (CoreSimos core = open(storeA)) {
      revisionsBefore = core.revisions(MAIN).size();
      long t0 = System.nanoTime();
      CommandResult result = core.submit(envelope(revisionsBefore, targets, terrain));
      long elapsed = System.nanoTime() - t0;
      System.out.println("submitResult=" + result + " elapsedMs=" + (elapsed / 1_000_000.0));
      after = mapAt(core, revisionsBefore + 1);
      System.out.println("headAfter=" + core.head(MAIN).orElseThrow().value());

      // 负例三连：不留 revision
      long rows = core.revisions(MAIN).size();
      System.out.println("negEmpty=" + core.submit(envelope(rows, Set.of(), terrain)));
      System.out.println("negOutsideCatalog=" + core.submit(envelope(rows, targets, "forest")));
      System.out.println("negMissingHex=" + core.submit(envelope(rows, Set.of(new HexCoord(9999, 9999)), terrain)));
      System.out.println("revisionsRowsUnchanged=" + (core.revisions(MAIN).size() == rows)
          + " rows=" + core.revisions(MAIN).size());

      // 重放一致：再次 replay 同一坐标，逐字节相同
      System.out.println("replayByteIdentical="
          + mapAt(core, revisionsBefore + 1).terrainBlocks().toString().equals(after.terrainBlocks().toString()));
    }

    int changed = 0;
    int heightMismatch = 0;
    for (HexCoord hex : genesis.hexes().keySet()) {
      if (!genesis.terrainAt(hex).equals(after.terrainAt(hex))) {
        changed++;
      }
      HexCell a = genesis.hexes().get(hex);
      HexCell b = after.hexes().get(hex);
      if (a.height() != b.height()) {
        heightMismatch++;
      }
    }
    System.out.println("changedHexes=" + changed);
    System.out.println("heightMismatches=" + heightMismatch);
    boolean targetsOk = true;
    for (HexCoord hex : targets) {
      targetsOk &= terrain.equals(after.terrainAt(hex));
    }
    System.out.println("allTargetsTerrain=" + targetsOk);
    System.out.println("hexesComponentUnchangedHeightCount=" + after.hexes().size());
    TerrainBlocks.requirePartition(after.hexes(), after.terrainBlocks());
    System.out.println("partitionAfter=OK");
    System.out.println("histogramAfter=" + histogram(after));
    System.out.println("blockCountAfter=" + after.terrainBlocks().size());

    // ── B：同一 base + 同一命令两次（两个独立副本）⇒ 逐字节相同 ────────────────
    GameMap fromA = after;
    GameMap fromB;
    try (CoreSimos core = open(storeB)) {
      long rows = core.revisions(MAIN).size();
      core.submit(envelope(rows, targets, terrain));
      fromB = mapAt(core, rows + 1);
    }
    System.out.println("twoRunsByteIdentical="
        + fromA.terrainBlocks().toString().equals(fromB.terrainBlocks().toString()));
    System.out.println("twoRunsBlockIdSetsEqual="
        + fromA.terrainBlocks().keySet().equals(fromB.terrainBlocks().keySet()));
    System.out.println("blockTableHeadA=" + firstBlocks(fromA));
    System.out.println("blockTableHeadB=" + firstBlocks(fromB));
  }

  private static String firstBlocks(GameMap map) {
    List<String> out = new ArrayList<>();
    for (Map.Entry<io.mosire.simos.map.block.BlockId, ?> e : map.terrainBlocks().entrySet()) {
      if (out.size() == 3) {
        break;
      }
      out.add(e.getKey().toString());
    }
    return out.toString();
  }

  private static Map<String, Integer> histogram(GameMap map) {
    Map<String, Integer> hist = new TreeMap<>();
    for (String t : map.terrainIndex().values()) {
      hist.merge(t, 1, Integer::sum);
    }
    return hist;
  }

  private static String firstDifferent(String current) {
    for (String key : TerrainCatalog.KEYS) {
      if (!key.equals(current)) {
        return key;
      }
    }
    throw new IllegalStateException("词表只有一种地形？");
  }

  private static CoreSimos open(Path dir) {
    return new CoreSimos(new CoreConfig(dir, 100, MAPPER))
        .register(new MapCodec())
        .register(new SocialCodec())
        .register(new UnitCodec())
        .register(new SetTerrainHandler());
  }

  private static GameMap load(Path dir, long revision) {
    try (CoreSimos core = open(dir)) {
      return mapAt(core, revision);
    }
  }

  private static GameMap mapAt(CoreSimos core, long revision) {
    return ((MapSnapshot) core.replay(new StateRef(MAIN, new RevisionId(revision))).module("map").orElseThrow())
        .map();
  }

  private static CommandEnvelope envelope(long expectedRevision, Set<HexCoord> hexes, String terrain) {
    StringBuilder array = new StringBuilder("[");
    for (HexCoord hex : hexes) {
      if (array.length() > 1) {
        array.append(',');
      }
      array.append("{\"q\":").append(hex.q()).append(",\"r\":").append(hex.r()).append('}');
    }
    array.append(']');
    String payload = "{\"hexes\":" + array + ",\"terrain\":\"" + terrain + "\"}";
    String id = "cmd-" + Math.abs(payload.hashCode());
    return new CommandEnvelope(id, id, "probe:t3", MAIN, new RevisionId(expectedRevision), "map.SetTerrain", payload);
  }

  private T3RealArchiveProbe() {}
}
