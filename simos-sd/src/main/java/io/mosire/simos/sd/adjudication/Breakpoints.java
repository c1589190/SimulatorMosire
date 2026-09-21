package io.mosire.simos.sd.adjudication;

import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AI 决策断点表（spec §八.2，D1~D8）：八个稳定断点常量 + **D1/D3 合并为同一次调用**。
 *
 * <p>★ **合并调用**由 {@link #callGroups()} 表达：本 tick 的调用组有 **7** 组（D1 与 D3 同组，其余各一组）—— 依赖"合并"的判据查这里，
 * 而不是查"8 个常量"。
 *
 * <p>★ **判决窄工具的裁决面**由 {@link #acceptsVerdictSubject} 表达：只有 D1/D3/D6 产判决（其余断点经 {@code
 * sd.IssueDirective} 表达），且 subject 必须是 {@code sd:combat.*} 地址（spec §八.2 的输出 schema 均以战斗为对象）。
 */
public final class Breakpoints {

  public static final AdjudicationBreakpoint D1 = new AdjudicationBreakpoint("D1");
  public static final AdjudicationBreakpoint D2 = new AdjudicationBreakpoint("D2");
  public static final AdjudicationBreakpoint D3 = new AdjudicationBreakpoint("D3");
  public static final AdjudicationBreakpoint D4 = new AdjudicationBreakpoint("D4");
  public static final AdjudicationBreakpoint D5 = new AdjudicationBreakpoint("D5");
  public static final AdjudicationBreakpoint D6 = new AdjudicationBreakpoint("D6");
  public static final AdjudicationBreakpoint D7 = new AdjudicationBreakpoint("D7");
  public static final AdjudicationBreakpoint D8 = new AdjudicationBreakpoint("D8");

  private static final List<AdjudicationBreakpoint> VERDICT_BREAKPOINTS = List.of(D1, D3, D6);

  private Breakpoints() {}

  /** 本 tick 的调用分组：**D1 与 D3 同组**（spec §八.2 明写合并），故为 7 组而非 8 组。 */
  public static List<List<AdjudicationBreakpoint>> callGroups() {
    return List.of(
        List.of(D1, D3),
        List.of(D2),
        List.of(D4),
        List.of(D5),
        List.of(D6),
        List.of(D7),
        List.of(D8));
  }

  /** 本 tick 需要的调用次数（合并后）。 */
  public static int callCount() {
    return callGroups().size();
  }

  /** D1 与 D3 是否由同一次调用承担。 */
  public static boolean sharesCall(AdjudicationBreakpoint left, AdjudicationBreakpoint right) {
    for (List<AdjudicationBreakpoint> group : callGroups()) {
      if (group.contains(left) && group.contains(right)) {
        return true;
      }
    }
    return false;
  }

  /** 该断点是否产出判决（决定能否经 {@code sd.SubmitVerdict} 落盘）。 */
  public static boolean producesVerdict(AdjudicationBreakpoint breakpoint) {
    return VERDICT_BREAKPOINTS.contains(breakpoint);
  }

  /** 判决主体是否落在该断点的裁决面：D1/D3/D6 = {@code sd:combat.*}。 */
  public static boolean acceptsVerdictSubject(AdjudicationBreakpoint breakpoint, Address subject) {
    Objects.requireNonNull(breakpoint, "breakpoint");
    Objects.requireNonNull(subject, "subject");
    if (!producesVerdict(breakpoint)) {
      return false;
    }
    if (!"sd".equals(subject.namespace())) {
      return false;
    }
    return "combat".equals(rootEntityKind(subject).orElse(null));
  }

  private static Optional<String> rootEntityKind(Address address) {
    AddressSegment root = address.segments().get(1);
    if (root instanceof Entity entity) {
      return entity.kind();
    }
    return Optional.empty();
  }
}
