import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionBoundary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Probe2 {
  public static void main(String[] args) {
    StringBuilder diverging = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      Set<HexCoord> raw = new HashSet<>();
      for (int j = 0; j < 1000; j++) {
        raw.add(new HexCoord(i * 1000 + j, 0));
      }
      Set<HexCoord> cp = Set.copyOf(raw);
      List<HexCoord> rawView = List.copyOf(raw);
      List<HexCoord> cpView = List.copyOf(cp);
      boolean eq = RegionBoundary.of(raw).equals(RegionBoundary.of(cp));
      if (!eq) {
        diverging.append(i).append(',');
      }
      if (i == 0 || i == 16 || i == 74) {
        System.out.println(
            "i="
                + i
                + " 迭代序同="
                + rawView.equals(cpView)
                + " raw首格="
                + rawView.getFirst()
                + " copy首格="
                + cpView.getFirst());
      }
    }
    System.out.println("PROBE2 分叉的 i = [" + diverging + "]");
  }
}
