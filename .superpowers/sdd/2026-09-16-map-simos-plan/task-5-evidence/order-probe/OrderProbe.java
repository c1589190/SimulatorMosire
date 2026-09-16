import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.RegionId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量"保序钉子到底钉不钉得住"：把 GameMap/City 夹具的那几组键喂给 Map.copyOf（= 变异体 m5v-3/m5v-7 的实现），
 * 看它多大概率**恰好落回插入序**——落回时变异体打不响任何用例，就是**假绿**。
 *
 * <p>{@code Map.copyOf} 的迭代序按 JVM 启动时的 SALT 加盐，故**每次运行只算一个样本**：
 * 跑 N 次 `java` 收 N 个样本。
 *
 * <p>编译运行（需先 `./mvnw -q -pl simos-map -am compile`）：
 * {@code javac -cp simos-map/target/classes -d . OrderProbe.java && for i in $(seq 1 30); do java -cp simos-map/target/classes:. OrderProbe; done}
 */
public class OrderProbe {

  static String check(String name, List<?> keys) {
    Map<Object, Object> src = new LinkedHashMap<>();
    for (Object k : keys) {
      src.put(k, 1);
    }
    Object[] before = src.keySet().toArray();
    Object[] after = Map.copyOf(src).keySet().toArray();
    return name + "=" + (Arrays.equals(before, after) ? "PRESERVED" : "SCRAMBLED");
  }

  public static void main(String[] args) {
    HexCoord hA = new HexCoord(5, 5);
    HexCoord hB = new HexCoord(0, 0);
    HexCoord hC = new HexCoord(-3, 2);
    HexCoord hD = new HexCoord(2, -4);
    System.out.println(
        String.join(
            " ",
            // 3 键 = 改动前的夹具（留作对照），4 键 = 改动后的夹具
            check("hexes3", List.of(hA, hB, hC)),
            check("hexes4", List.of(hA, hB, hC, hD)),
            check("regions3", List.of(new RegionId("r2"), new RegionId("r10"), new RegionId("r1"))),
            check(
                "regions4",
                List.of(new RegionId("r2"), new RegionId("r10"), new RegionId("r1"), new RegionId("r5"))),
            check("cities3", List.of(new CityId("c9"), new CityId("c1"), new CityId("c5"))),
            check(
                "cities4",
                List.of(new CityId("c9"), new CityId("c1"), new CityId("c5"), new CityId("c3"))),
            check("terrain3", List.of("plains", "ocean", "mountains")),
            check("terrain4", List.of("plains", "ocean", "mountains", "desert")),
            check(
                "pathways3",
                List.of(new PathwayId("p3"), new PathwayId("p1"), new PathwayId("p2"))),
            check(
                "pathways4",
                List.of(
                    new PathwayId("p3"), new PathwayId("p1"), new PathwayId("p2"), new PathwayId("p7"))),
            check("groups3", List.of("road", "river", "rail")),
            check("groups4", List.of("road", "river", "rail", "sea")),
            check(
                "edges3",
                List.of(
                    new EdgeRef(hA, hB), new EdgeRef(hB, hC), new EdgeRef(hA, hC))),
            check(
                "edges4",
                List.of(
                    new EdgeRef(hA, hB),
                    new EdgeRef(hB, hC),
                    new EdgeRef(hA, hC),
                    new EdgeRef(hB, hD))),
            // City.props：3 键 = 改动前（m5v-7 第 2 次跑就是栽在它身上），6 键 = 改动后
            check("cityProps3", List.of("population", "owner", "founded")),
            check(
                "cityProps6",
                List.of("population", "owner", "founded", "walls", "trade", "port"))));
  }
}
