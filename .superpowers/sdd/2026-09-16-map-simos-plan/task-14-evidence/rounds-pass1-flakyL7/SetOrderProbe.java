import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 探针：模拟 L7 的 JSON 往返里 Region.hexes 的集合序——Set.of(p,q) 的迭代序经
 * LinkedHashSet 再 Set.copyOf 后是否翻转。输出两列：旧组{(0,0),(5,5)}、候选组{(0,0),(-3,2)}。 */
public class SetOrderProbe {

  record C(int q, int r) {}

  public static void main(String[] args) {
    C b = new C(0, 0);
    C oldPair = new C(5, 5);
    C newPair = new C(-3, 2);
    System.out.println(flip(b, oldPair) + " " + flip(b, newPair));
  }

  static boolean flip(C p, C q) {
    List<C> order1 = List.copyOf(Set.of(p, q));
    Set<C> back = Set.copyOf(new LinkedHashSet<>(order1));
    return !order1.equals(List.copyOf(back));
  }
}
