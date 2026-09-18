package io.mosire.simos.core.advance;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两阶段推进的 **③ Resolve**（spec §5.3，C14/C15）：拿一批**提案**，判它们的读写集相不相交，并汇总成 {@link WorldChangeSet}。
 *
 * <p>★ **纯函数**：不碰库、不碰时钟、不改入参。故它可以被单独喂、单独断言，不必装配 {@code Timeline}。
 *
 * <p>★★ **为什么返回值不是一个 {@code Either}**（裁定 43）：本仓**没有** {@code Either} 这个类型，也不为它引一个依赖。 用 {@link
 * Outcome} 这个 **sealed 两case 型**代替：{@code switch} 穷尽、不写 {@code default} ⇒ 将来加第三个 case
 * 会**编译不过**，而不是在运行时悄悄漏掉一支。
 *
 * <p>★ **两个方向的读-写都要查**（spec §5.3 最后一条）：{@code reads(A) ∩ writes(B)} 与 {@code reads(B) ∩ writes(A)}
 * 是**两条独立的风险**，只查一个方向会漏掉一半。故 {@link Resolved#warnings()} 是**按有向对**列的（{@code namespaces = [读方,
 * 写方]}），A、B 互相读对方写的会留下**两条**。 变异体 m1（只查一个方向）打的就是这里——**用例必须构造出"只有反方向命中"的场景**，否则该变异恒绿。
 *
 * <p>★ **自交不算冲突**：同一参与者的 {@code reads ∩ writes} 是它自己的事（它先读后写自己那块），跳过。
 */
public final class TimeProposalResolver {

  private TimeProposalResolver() {}

  /** ③ 的结局：只有这两支，没有第三种。 */
  public sealed interface Outcome {

    /** 有写-写相交 ⇒ **拒绝整次推进**。 */
    record Blocked(AdvanceConflict conflict) implements Outcome {
      public Blocked {
        Objects.requireNonNull(conflict, "conflict");
      }
    }

    /**
     * 可推进。
     *
     * @param changeSet 汇总后的世界变更集（按入参顺序插入 ⇒ 调用方给的是 C25 的字典序，落盘即字典序）
     * @param warnings 读-写相交的留痕，**已按 (namespaces, addresses) 字典序定序**（决定论）
     */
    record Resolved(WorldChangeSet changeSet, List<AdvanceConflict> warnings) implements Outcome {
      public Resolved {
        Objects.requireNonNull(changeSet, "changeSet");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
      }
    }
  }

  /**
   * @param proposals 提案清单。★ 调用方须已按 namespace **字典序**排好（C25）——汇总出的 {@link WorldChangeSet}
   *     保留这个顺序，而它会被原样落进 {@code changeset_json}
   */
  public static Outcome resolve(List<TimeProposal> proposals) {
    Objects.requireNonNull(proposals, "proposals");

    // 变异 m2：warnings 提前声明，好让写-写也能往里塞
    List<AdvanceConflict> warnings = new ArrayList<>();
    for (int i = 0; i < proposals.size(); i++) {
      for (int j = i + 1; j < proposals.size(); j++) {
        TimeProposal left = proposals.get(i);
        TimeProposal right = proposals.get(j);
        Set<String> shared = intersection(left.writes(), right.writes());
        if (!shared.isEmpty()) {
          warnings.add(
              new AdvanceConflict(
                  AdvanceConflict.WRITE_WRITE,
                  List.of(left.namespace(), right.namespace()),
                  List.copyOf(shared)));
        }
      }
    }

    // ── 读-写：**有向**对，两个方向各查一次 ────────────────────────────────────────────
    for (TimeProposal reader : proposals) {
      for (TimeProposal writer : proposals) {
        if (reader.namespace().equals(writer.namespace())) {
          continue; // 自交不算冲突
        }
        Set<String> shared = intersection(reader.reads(), writer.writes());
        if (!shared.isEmpty()) {
          warnings.add(
              new AdvanceConflict(
                  AdvanceConflict.READ_WRITE,
                  List.of(reader.namespace(), writer.namespace()),
                  List.copyOf(shared)));
        }
      }
    }
    // ★ 定序：入参若没排过（本方法不假设调用方守约），warnings 的顺序就会随它变。addresses 已排序、
    //   namespaces 保序，故 AdvanceConflict 可被确定地排序 ⇒ 输出是入参顺序的**纯函数**之外的额外保证。
    // ★ **namespaces 不参与"规范化"是必须的**：它承载**方向**（[读方, 写方]），排序会让 alpha→beta 与
    //   beta→alpha 两条独立的风险退化成同一个值（Task 12 实测的缺陷，详见 AdvanceConflict 类注释）。
    // ★ 按 (namespaces, addresses) 的**逐元素**字典序比——List<String> 不是 Comparable，Comparator.comparing
    //   在这里编不过（实测），故显式写比较器。
    warnings.sort(
        (left, right) -> {
          int byNamespaces = compareElementWise(left.namespaces(), right.namespaces());
          return byNamespaces != 0
              ? byNamespaces
              : compareElementWise(left.addresses(), right.addresses());
        });

    // ── 汇总 ─────────────────────────────────────────────────────────────────────────
    LinkedHashMap<String, ChangeSet> modules = new LinkedHashMap<>();
    for (TimeProposal proposal : proposals) {
      modules.put(proposal.namespace(), proposal.changeSet());
    }
    return new Outcome.Resolved(new WorldChangeSet(modules), warnings);
  }

  /** 交集，**保留 left 的迭代序**（外层再排序，故此处不额外定序）。 */
  private static Set<String> intersection(Set<String> left, Set<String> right) {
    LinkedHashSet<String> shared = new LinkedHashSet<>(left);
    shared.retainAll(new TreeSet<>(right));
    return shared;
  }

  /** 两个有序串表的**逐元素**字典序比较；前缀相同时短者在前（{@code List} 不是 {@code Comparable}）。 */
  private static int compareElementWise(List<String> left, List<String> right) {
    int common = Math.min(left.size(), right.size());
    for (int i = 0; i < common; i++) {
      int byElement = left.get(i).compareTo(right.get(i));
      if (byElement != 0) {
        return byElement;
      }
    }
    return Integer.compare(left.size(), right.size());
  }
}
