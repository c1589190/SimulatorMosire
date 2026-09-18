package io.mosire.simos.core.advance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 一次推进里被检出的**读写冲突**（C14/C15 的落点，spec §5.3）。
 *
 * <p>两种形态用同一个载体，靠 {@link #kind} 区分：
 *
 * <ul>
 *   <li>{@link #WRITE_WRITE} —— 两个参与者的 {@code writes} 相交。**拒绝整次推进**（{@code Rejected}）， 不产生任何
 *       revision。{@link #namespaces()} 是那两个参与者，{@link #addresses()} 是相交的地址。
 *   <li>{@link #READ_WRITE} —— 甲的 {@code reads} 与乙的 {@code writes} 相交。**放行**，只发一条事件留痕。 {@link
 *       #namespaces()} 是 {@code [读方, 写方]}（**有向**：两个方向是两条独立的风险）。
 * </ul>
 *
 * <p>★★ **地址排序，namespace 不排序——两者规则不同，这不是疏漏**：
 *
 * <ol>
 *   <li>{@link #addresses()} 是**内容**（C15 明写「冲突报告按字典序排序后落事件」）：它是集合，序无意义， 排了才能让事件逐字节可比。而 {@code
 *       Set.copyOf} / {@code HashSet} 的迭代序**不是键集的纯函数** （M2 Task 5 实测：3 键有 7%~40%
 *       恰好落回插入序），故**必须**在这里一次定死——收进构造器 ⇒ **只有一个地方可错**，R10 的序断言正好钉在它上面（变异体 m4 打的就是这一行）。
 *   <li>{@link #addresses()} 之外，{@link #namespaces()} 是**关系**（谁读谁写）：对它排序会把**方向抹掉**。
 * </ol>
 *
 * <p>★★ **"排序要把方向抹掉"不是推演，是 Task 12 实测出来的真缺陷**：本记录的构造器一度对两个字段都调 {@code sortedCopy}，于是 {@code [alpha,
 * beta]}（alpha 读 beta 写的）与 {@code [beta, alpha]}（beta 读 alpha 写的）
 * 落成**同一个值**——两条本应独立保留的风险，在事件表里变成**两条逐字节相同**的行，读事件的人再也分不出 哪条是哪个方向。被 {@code
 * TimeProposalResolverTest.bothReadWriteDirectionsAreReportedAsTwoWarnings} 当场抓住 （它断言两条 {@code
 * namespaces} 分别是 {@code [alpha,beta]} 与 {@code [beta,alpha]}，排序后该断言必红）。 ⇒
 * **凡"顺手也排一下"的字段，先问它承载的是内容还是关系。**
 *
 * <p>★ 代价与对策：{@link #namespaces()} 的序因此**由调用方负责**（唯一的调用方是 {@code TimeProposalResolver}，
 * 它只有两个构造点：写-写按 {@code i<j}、读-写按 {@code [读方, 写方]}）。两处各被一条用例钉住 （{@code
 * writeWriteIntersectionIsBlocked} / {@code onlyTheReverseReadWriteDirectionIsReported}）。
 * 这是**有意的取舍**：让"方向"这个语义活着，比让"序由一个构造器兜底"更值。
 */
public record AdvanceConflict(String kind, List<String> namespaces, List<String> addresses) {

  /** 写-写：拒绝整次推进。 */
  public static final String WRITE_WRITE = "write_write";

  /** 读-写：放行，只留痕。 */
  public static final String READ_WRITE = "read_write";

  public AdvanceConflict {
    Objects.requireNonNull(kind, "kind");
    if (WRITE_WRITE.equals(kind) == READ_WRITE.equals(kind)) {
      throw new IllegalArgumentException("kind 只能是 write_write 或 read_write 之一，实得: " + kind);
    }
    // ★★ **包装调用必须写在构造器体里，不能收进下面那两个辅助方法**——这不是风格偏好，是 SpotBugs 的硬约束：
    //   `EI_EXPOSE_REP` 只认**构造函数体内直接可见**的包装调用，**不做跨过程分析**。
    //   Task 12 的两次探针（都在 `task-12-evidence/logs/`，都是**跑在当时的字节上**的 spotbugs 轮）：
    //     ① 包装用 `Collections.unmodifiableList`、放在辅助方法里 ⇒ `BugInstance size is 2`
    //        （record 的两个 accessor 各一条 "may expose internal representation"，都指到构造器那一行）；
    //     ② 包装用 `List.copyOf`、写在构造器体里 ⇒ 5 个模块全 `BugInstance size is 0`（含 simos-core）。
    //   ⇒ **被测的是"包装在哪"，不是"用哪个 API"**——①与②同时换了这两个因素，故**不能**据它断定
    //     `unmodifiableList` 放进构造器体也会判 0（那一格**没测过**，别当结论）。
    //   ★ 同模块的 WorldChangeSet 一直是"包装写在构造器体里"，故它从未报过——**同形不必然同判，别照抄形状**。
    //   ⇒ 将来要抽公共辅助方法时，抽"拷贝 + 逐元素校验"可以，**包装那一层必须留在调用点**。
    namespaces = List.copyOf(sortedCheckedCopy(namespaces, "namespaces")); // 变异 m5：把方向抹掉（Task 12 真实犯过的错）
    addresses = List.copyOf(sortedCheckedCopy(addresses, "addresses"));
  }

  /**
   * 逐元素 null 校验的**保序**拷贝——**有意不排序**（调用方给的序就是**方向**，见类注释）， 也**有意不在这里做不可变化** （那一层留在构造器体里，理由见上）。
   *
   * <p>元素级 {@code requireNonNull} 与 {@link #sortedCheckedCopy} 同由：{@code List.copyOf} 遇 null 也会抛
   * NPE， 但消息里**没有字段名**（CLAUDE.md 形态 2 的另一面）。
   */
  private static List<String> checkedCopy(List<String> values, String name) {
    Objects.requireNonNull(values, name);
    List<String> copied = new ArrayList<>(values);
    for (int i = 0; i < copied.size(); i++) {
      Objects.requireNonNull(copied.get(i), name + " 的元素");
    }
    return copied;
  }

  /**
   * 字典序去重拷贝（**只用于地址**）——同样只拷贝、不包装（理由见构造器）。
   *
   * <p>★ 元素级 {@code requireNonNull} 是**有意的**：{@code new TreeSet<>(list)} 遇到 null 元素也会抛 NPE， 但消息里
   * **没有字段名**（CLAUDE.md 形态 2 的另一面）——这类"抛了但说不清是谁"的守卫，出事时定位成本极高。
   */
  private static List<String> sortedCheckedCopy(List<String> values, String name) {
    Objects.requireNonNull(values, name);
    TreeSet<String> sorted = new TreeSet<>();
    for (String value : values) {
      sorted.add(Objects.requireNonNull(value, name + " 的元素"));
    }
    return new ArrayList<>(sorted);
  }
}
