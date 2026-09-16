import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import java.util.Set;

/** 逐输入打印 `parse` 抛出的异常类型，并直接判"断言 isInstanceOf(IAE) 会不会翻"。 */
public class ProbeR1 {
  public static void main(String[] args) {
    String[] inputs = {"", "12", "_", "1_", "_2", "a_b"};
    for (String s : inputs) {
      String shown = s.isEmpty() ? "\"\"" : "\"" + s + "\"";
      try {
        HexCoord.parse(s);
        System.out.println("PROBE parse(" + shown + ") -> 未抛异常");
      } catch (Throwable t) {
        boolean isIae = t instanceof IllegalArgumentException;
        System.out.println(
            "PROBE parse("
                + shown
                + ") -> "
                + t.getClass().getName()
                + (isIae ? "  [IS IAE] 断言不翻" : "  [NOT IAE] 断言会翻"));
      }
    }
    // F4 裁定（不加守卫）所依据的实测：HexGrid 三处 null 入参到底抛什么。
    probe("HexGrid.of(null)", () -> HexGrid.of(null));
    probe("HexGrid.of(Set.of()).contains(null)", () -> HexGrid.of(Set.of()).contains(null));
    probe("HexGrid.withinRadius(null, 0)", () -> HexGrid.withinRadius(null, 0));
  }

  private static void probe(String label, Runnable body) {
    try {
      body.run();
      System.out.println("PROBE " + label + " -> 未抛异常");
    } catch (Throwable t) {
      System.out.println("PROBE " + label + " -> " + t.getClass().getName() + ": " + t.getMessage());
    }
  }
}
