import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import io.mosire.simos.map.region.RegionBoundary;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * M8-S 判据 14 的**权威边界探针**：直接调 Java 的 {@link RegionBoundary#of}，把环打成标签串。
 *
 * <p>只读、不改仓内 Java；用编译好的 {@code simos-map} classes 起。输出格式：每条环 {@code u:w,u:w,…}，
 * 环之间以 {@code ;} 分隔。客户端把渲染顶点换算成同样的 {@code (u,w)} 标签后逐环比对。
 */
public final class BoundaryProbe {
  private BoundaryProbe() {}

  public static void main(String[] args) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (String arg : args) {
      String[] parts = arg.split(",");
      hexes.add(new HexCoord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
    }
    RegionBoundary boundary = RegionBoundary.of(hexes);
    StringBuilder out = new StringBuilder();
    for (List<HexVertex> ring : boundary.rings()) {
      if (out.length() > 0) {
        out.append(';');
      }
      boolean first = true;
      for (HexVertex vertex : ring) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(vertex.u()).append(':').append(vertex.w());
      }
    }
    System.out.println(out);
  }
}
