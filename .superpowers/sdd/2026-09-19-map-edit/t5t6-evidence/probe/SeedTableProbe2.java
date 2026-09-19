import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.ops.RandomizeOperations;
import java.util.*;

public final class SeedTableProbe2 {
  static final String THIRD = "mountains";
  static final int RADIUS = 10;
  public static void main(String[] a) {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> sel = selectionOf(base);
    for (long seed : new long[] {0L, 1L, 2L, 3L, 4L, 5L, 7L, 8L}) {
      GameMap after = MapChangeSet.apply(RandomizeOperations.randomize(base, sel, seed), base);
      Map<String, Integer> hist = histogram(after);
      List<String> ids = new ArrayList<>(after.terrainBlocks().keySet().stream().map(BlockId::toString).toList());
      System.out.println("seed=" + seed + " desert=" + hist.get("desert") + " mountains=" + hist.get("mountains")
          + " plains=" + hist.get("plains") + " blocks=" + ids.size());
      if (seed == 7L) { System.out.println("  IDS7=" + ids); }
      if (seed == 8L) { System.out.println("  IDS8=" + ids); }
    }
  }
  static Set<HexCoord> selectionOf(GameMap base) {
    List<HexCoord> o = new ArrayList<>(base.hexes().keySet());
    o.sort(HexCoord::compareTo);
    return new LinkedHashSet<>(o.subList(5, o.size()));
  }
  static Map<String, Integer> histogram(GameMap map) {
    Map<String, Integer> h = new TreeMap<>();
    for (String t : map.terrainIndex().values()) { h.merge(t, 1, Integer::sum); }
    return h;
  }
  static GameMap graphOf(int radius, String terrain) {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), radius);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) { cells.put(hex, new HexCell(h)); h = h >= 0.9 ? 0.3 : h + 0.03; }
    return new GameMap(cells, TerrainBlocks.uniform(all, terrain), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), GenerationSpec.defaults(0L));
  }
  private SeedTableProbe2() {}
}
