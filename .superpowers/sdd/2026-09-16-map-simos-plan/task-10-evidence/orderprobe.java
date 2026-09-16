import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 量判别力：Map.copyOf(7 键词表) 与 Set.copyOf(半径 80 的 19441 格) 的迭代序，
 *  在多少个独立 JVM（各自哈希盐）里恰好等于插入序/自然序。 */
public class OrderProbe {
  public static void main(String[] args) {
    Map<String, ?> cat = TerrainCatalog.defaults();
    Map<String, ?> copy = Map.copyOf(cat);
    boolean catSame = new ArrayList<>(copy.keySet()).equals(new ArrayList<>(cat.keySet()));

    Set<HexCoord> set = HexGrid.withinRadius(new HexCoord(0, 0), 80);
    boolean hexSame = new ArrayList<>(set).equals(set.stream().sorted().toList());
    List<HexCoord> l = new ArrayList<>(set);
    System.out.println(
        "catalog SAME_ORDER=" + catSame
            + " keys=" + cat.size()
            + " | hexesSorted=" + hexSame
            + " size=" + set.size()
            + " first3=" + l.subList(0, 3));
  }
}
