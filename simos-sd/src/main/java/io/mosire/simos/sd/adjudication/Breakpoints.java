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
 * <p>★ **判决窄工具的裁决面**由 {@link #acceptsVerdictSubject} 表达，分**两个面**（它们是否产判决由 {@link #producesVerdict}
 * 表达）：
 *
 * <ul>
 *   <li><b>战斗判决面</b>：D1/D3/D6，subject 必须是 {@code army:combat.*}——spec §八.2 的这三行输出 schema 均以战斗为对象；
 *   <li><b>决策判决面</b>（2026-09-22 用户裁定）：D2，subject 必须是 {@code sd:decision-maker.*}——「**战斗是世界的一个状态，
 *       不是决策的前提**」 ⇒ 无战斗时决策照样可做，此时对**决策人本人**裁决（判决产物是决策记录文本）；战斗/情报只是判决的**输入**。
 * </ul>
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：战斗判决面的命名空间由 {@code sd} 改挂 {@code army}（判决面重挂，不可逆点）</b>——
 * 旧战斗族整族退役（用户裁定「战斗模块，包括阶段之类的，归 Army 模块管」＋「sd 整个弃用！」），交战记录的<b>唯一</b>载体是 {@code simos-army} 的 {@code
 * ArmyData.combats}，它的 canonical 地址族是 {@code army:combat.<id>}（{@code
 * io.mosire.simos.army.ArmyAddresses}）。⇒ 判决主体必须随之改挂，否则 {@code ga.SubmitVerdict} 的 D1/D3/D6
 * 判决会被本类<b>当场拒</b>（"subject 不在裁决面"），判决面就成了死的。
 *
 * <p>★ <b>改动面：一个字符串字面量（命名空间）</b>——{@code "sd"} → {@code "army"}；kind 判据（{@code combat}）与 D2
 * 那一支（{@code sd:decision-maker.*}）<b>一字未改</b>。★ 本类<b>不 import 任何 army 类型</b>（它只比对 {@link Address}
 * 的命名空间串与根段 kind 串）⇒ <b>不新增 {@code sd → army} 依赖边</b>，enforcer 的禁列原样成立。
 *
 * <p>★ **D2 为何也产判决**：spec §八.2 的 D2 行把它记为"出令"（最终落 {@code ga.IssueDirective}）。但按**三件事模型**
 * （「建议」≠「开始决策」≠「决策人出令」）， 「开始决策」触发的是**判决**（数据、落 revision、可回放），出令是**另一条**命令。故本类把 D2 的**判决记录**纳入
 * 判决面（subject = 决策人），出令仍走 {@code ga.IssueDirective}——两条路互不占名额（R4 的**末位生效名额**归后者）。
 *
 * <p>★ **D4/D5/D7/D8 不产判决**：它们的产出是**草案**，按 D7 的既定口径由决策 Agent / 人经 {@code ga.IssueDirective} 发出。
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

  private static final List<AdjudicationBreakpoint> COMBAT_VERDICT_BREAKPOINTS =
      List.of(D1, D3, D6);

  /** 对**决策人本人**裁决的断点（D2）：subject 形态 {@code sd:decision-maker.*}。 */
  private static final List<AdjudicationBreakpoint> DECISION_VERDICT_BREAKPOINTS = List.of(D2);

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

  /** 该断点是否产出判决（决定能否经 {@code ga.SubmitVerdict} 落盘）。 */
  public static boolean producesVerdict(AdjudicationBreakpoint breakpoint) {
    return COMBAT_VERDICT_BREAKPOINTS.contains(breakpoint)
        || DECISION_VERDICT_BREAKPOINTS.contains(breakpoint);
  }

  /** 判决主体是否落在该断点的裁决面：D1/D3/D6 = {@code army:combat.*}；D2 = {@code sd:decision-maker.*}。 */
  public static boolean acceptsVerdictSubject(AdjudicationBreakpoint breakpoint, Address subject) {
    Objects.requireNonNull(breakpoint, "breakpoint");
    Objects.requireNonNull(subject, "subject");
    // ★★ 2026-10-12 批次 3d-2：战斗判决面的命名空间 sd → army（旧战斗族退役、交战记录归 army）。
    //   只改这一个字面量：kind 判据与 D2 支一字未改；本类不引任何 army 类型 ⇒ 不新增 sd → army 依赖边。
    //   ★ 按断点分别判命名空间：D2（决策人）仍住 sd，不能统一换成 army。
    if (COMBAT_VERDICT_BREAKPOINTS.contains(breakpoint)) {
      if (!"army".equals(subject.namespace())) {
        return false;
      }
      return "combat".equals(rootEntityKind(subject).orElse(null));
    }
    if (DECISION_VERDICT_BREAKPOINTS.contains(breakpoint)) {
      if (!"sd".equals(subject.namespace())) {
        return false;
      }
      return "decision-maker".equals(rootEntityKind(subject).orElse(null));
    }
    return false;
  }

  private static Optional<String> rootEntityKind(Address address) {
    AddressSegment root = address.segments().get(1);
    if (root instanceof Entity entity) {
      return entity.kind();
    }
    return Optional.empty();
  }
}
