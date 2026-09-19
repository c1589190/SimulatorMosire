import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.ops.RandomizeOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** T6 种子表探针：与 RandomizeOperationsTest 完全同形的夹具，打印 seed 1..5 的逐值结果。 */
public final class SeedTableProbe {

  private static final String THIRD = "mountains";
  private static final int RADIUS = 10;

  public static void main(String[] args) {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);
    System.out.println("selectionSize=" + selection.size() + " totalHexes=" + base.hexes().size());
    for (long seed = 1L; seed <= 5L; seed++) {
      GameMap after = MapChangeSet.apply(RandomizeOperations.randomize(base, selection, seed), base);
      Map<String, Integer> hist = histogram(after);
      System.out.println(
          "seed="
              + seed
              + " hist="
              + hist
              + " blocks="
              + after.terrainBlocks().size()
              + " terrains="
              + new TreeMap<>(after.terrainIndex()).size());
    }
    // 顺带：不同 seed 的直方图是否两两不同
    for (long a = 1L; a <= 5L; a++) {
      for (long b = a + 1L; b <= 5L; b++) {
        Map<String, Integer> ha =
            histogram(MapChangeSet.apply(RandomizeOperations.randomize(base, selection, a), base));
        Map<String, Integer> hb =
            histogram(MapChangeSet.apply(RandomizeOperations.randomize(base, selection, b), base));
        System.out.println("distinct(" + a + "," + b + ")=" + !ha.equals(hb));
      }
    }
  }

  private static Set<HexCoord> selectionOf(GameMap base) {
    List<HexCoord> ordered = new ArrayList<>(base.hexes().keySet());
    ordered.sort(HexCoord::compareTo);
    return new LinkedHashSet<>(ordered.subList(5, ordered.size()));
  }

  private static Map<String, Integer> histogram(GameMap map) {
    Map<String, Integer> hist = new TreeMap<>();
    for (String terrain : map.terrainIndex().values()) {
      hist.merge(terrain, 1, Integer::sum);
    }
    return hist;
  }

  private static GameMap graphOf(int radius, String terrain) {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), radius);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      cells.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(all, terrain),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private SeedTableProbe() {}
}
