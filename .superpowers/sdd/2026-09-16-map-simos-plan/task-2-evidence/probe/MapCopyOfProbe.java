import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 探针：Map.copyOf 的迭代序 (a) 同一 JVM 内两次调用是否相同 (b) 跨 JVM 是否相同。 */
public class MapCopyOfProbe {
  public static void main(String[] args) {
    List<String> keys =
        List.of("ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");
    Map<String, String> m = new LinkedHashMap<>();
    for (String k : keys) {
      m.put(k, k);
    }
    System.out.println("插入序(LinkedHashMap) = " + List.copyOf(m.keySet()));
    System.out.println("copyOf 第 1 次        = " + List.copyOf(Map.copyOf(m).keySet()));
    System.out.println("copyOf 第 2 次        = " + List.copyOf(Map.copyOf(m).keySet()));
    System.out.println("两次相同?             = " + List.copyOf(Map.copyOf(m).keySet()).equals(List.copyOf(Map.copyOf(m).keySet())));
  }
}
