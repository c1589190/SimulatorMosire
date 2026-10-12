package io.mosire.simos.ga.command;

import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DmUsableCommand;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>命令权限的规则层 + 装配期 fail-closed 审计</b>（2026-10-12 批次 1，用户裁定「让 ga 统一鉴权，这本来就是规范问题」）。
 *
 * <p>★ <b>为什么是"派生 + 审计"而不是"直接决定注册进哪一面"</b>：本仓的命令是**显式 {@code new} 装配**的（{@code Shell} 里逐条 {@code
 * new XxxHandler()}），不是从磁盘发现的。若让声明直接决定"进哪个面"，那么<b>漏标一个 = 权限面静默变化</b>
 * ——这正是迁移前的老毛病（"默认放行"：不打标记就自动进令白名单）。⇒ 本层与 {@code ToolRoleAudit} 同款口径：
 * <b>声明是唯一的权限拼写点，装配面照旧显式写死，两者在装配完成处做一致性审计，不一致当场抛</b>。
 *
 * <p>★★ <b>权限方向 = 反默认</b>（用户裁定）：<b>默认仅 GM</b>；只有<b>显式</b>标了 {@link DmUsableCommand} 的命令 才进 DM 面（可嵌令
 * / 可入队）。★ <b>"不打标记"本身就是 GM-only 的声明</b> —— 故本仓**只有这一个**权限标记： 2026-10-12 批次 1 收尾（甲组第 2 条）已把旧的反向标记
 * {@code GmOnlyCommand} 整体退役，"两个标记说同一件事、 读者要查两处"的形态随之消失；"一条命令同时标两个互斥标记"这类自相矛盾<b>在类型上已不可表达</b>，
 * 相应的那条审计随之废止。
 *
 * <p>★ <b>三条审计</b>（全部 {@link IllegalStateException}，都在装配期、不静默）：
 *
 * <ol>
 *   <li><b>声明了未注册的类型</b>：声明面（DM 可用 ∪ 落点）⊄ 注册面 ⇒ 抛；
 *   <li><b>落点又是 DM 可用命令</b>：落点 ∩ DM 可用声明 ≠ ∅ ⇒ 抛（落点本身不得被嵌进令 / 入队——否则"决策落点"能被当效果再发一次 = 权限放大）；
 *   <li><b>结果集为空</b>：四个面任一为空 ⇒ 抛（空 = 谁都看不见 = 装配故障，不是"合法形态"）。
 * </ol>
 *
 * <p>★ <b>两段用法</b>（装配顺序逼出来的：{@code sd.RegisterEffect} 与 5 条 sd 决策命令要用派生结果才能构造， 而它们本身又在注册面上）：
 *
 * <pre>
 *   Declarations decl = CommandPermissionRules.declare(domainHandlers);   // ① 收声明（唯一读标记的地方）
 *   ... 用 decl.dmUsableTypes() 构造 sd.RegisterEffect / sd.IssueDirective，再并入注册面 ...
 *   CommandFaces faces = CommandPermissionRules.resolve(decl, allHandlers, landingPoints); // ② 四个面 + 三条审计
 * </pre>
 *
 * <p>★★ <b>不可协商</b>：本层只回答"令里能不能出现这个 type"，<b>一个字都不放松授权判据</b>—— <b>读不到归属 ⇒ deny-all</b>（{@code
 * DecisionScopeFunctions} 未注册类型 = deny-all、{@code NationScope} 类型不符即抛） 仍在各自的权限面里，一个字不改。
 */
public final class CommandPermissionRules {

  private CommandPermissionRules() {}

  /**
   * ① 声明收集：从注册面的 handler 上读权限标记接口（<b>唯一读标记的地方</b>）。
   *
   * <p>★ 反默认：只有 {@link DmUsableCommand} 一个标记 —— <b>打了 = 可 DM；不打 = 仅 GM</b>。
   *
   * @param registeredHandlers 注册面的 handler（构造顺序 = 注册顺序）
   */
  public static Declarations declare(List<CommandHandler> registeredHandlers) {
    Objects.requireNonNull(registeredHandlers, "registeredHandlers");
    Set<String> dmUsable = new LinkedHashSet<>();
    for (CommandHandler handler : registeredHandlers) {
      Objects.requireNonNull(handler, "handler");
      String type = requireType(handler.type());
      if (handler instanceof DmUsableCommand) {
        dmUsable.add(type);
      }
    }
    return new Declarations(dmUsable);
  }

  /**
   * ② 四个面 + 三条审计（在**完整注册面**上收口）。
   *
   * @param declarations ① 的声明（装配自身新增 handler <b>之前</b>收集的那份）
   * @param registeredHandlers <b>完整</b>注册面的 handler（含装配自身新增者）
   * @param landingPoints 决策落点（组合根把 {@code ChannelAdmission.LANDING_POINTS} 送进来）
   * @throws IllegalStateException 三条审计任一不过，或装配面在声明收集之后又混进了带标记的 handler
   */
  public static CommandFaces resolve(
      Declarations declarations,
      List<CommandHandler> registeredHandlers,
      Set<String> landingPoints) {
    Objects.requireNonNull(declarations, "declarations");
    Objects.requireNonNull(registeredHandlers, "registeredHandlers");
    // ★ 装配面新增的 handler（sd.RegisterEffect 与 5 条 sd 决策命令）在 ① 之后才进列表 ⇒ 它们的标记不会被 ① 看到。
    //   本步重收一次并要求**逐条相等**：多一条声明 = 有人给装配自身新增的 handler 打了标记（那标记会被静默忽略）
    //   ⇒ 当场抛，而不是悄悄不管。
    Declarations full = declare(registeredHandlers);
    if (!full.equals(declarations)) {
      throw new IllegalStateException(
          "命令权限装配故障：装配面在声明收集之后又出现了带标记的 handler（收集于 "
              + declarations
              + "，完整注册面为 "
              + full
              + "）—— 后加的标记不会被看到。要么别给它打标记，要么把声明收集挪到它之后。");
    }
    return derive(typesOf(registeredHandlers), declarations.dmUsableTypes(), landingPoints);
  }

  /**
   * 等价形态：直接喂 {@code type} + 声明（不经 handler 列表）。装配面用的是 {@link #resolve}，本形供装配期审计与用例 直接构造声明面（"type +
   * 声明的等价形式"）。
   *
   * @param registeredTypes 注册面（全部已注册命令类型）
   * @param dmUsableTypes 标了 {@link DmUsableCommand} 的类型（★ 未列入者 = 仅 GM）
   * @param landingPoints 决策落点
   * @throws IllegalStateException 三条审计任一不过
   */
  public static CommandFaces derive(
      Set<String> registeredTypes, Set<String> dmUsableTypes, Set<String> landingPoints) {
    Objects.requireNonNull(registeredTypes, "registeredTypes");
    Set<String> registered = Collections.unmodifiableSet(new LinkedHashSet<>(registeredTypes));
    Objects.requireNonNull(dmUsableTypes, "dmUsableTypes");
    Set<String> dmUsable = Collections.unmodifiableSet(new LinkedHashSet<>(dmUsableTypes));
    Objects.requireNonNull(landingPoints, "landingPoints");
    Set<String> landing = Collections.unmodifiableSet(new LinkedHashSet<>(landingPoints));
    for (String type : registered) {
      requireType(type);
    }
    for (String type : dmUsable) {
      requireType(type);
    }
    for (String type : landing) {
      requireType(type);
    }

    // ① 声明了未注册的类型：声明面必须 ⊆ 注册面（fail-closed 的方向：注册面是执行面的事实）
    Set<String> unknown = new LinkedHashSet<>(dmUsable);
    unknown.addAll(landing);
    unknown.removeAll(registered);
    if (!unknown.isEmpty()) {
      throw new IllegalStateException("命令权限装配故障：声明了未注册的命令类型（声明面必须 ⊆ 注册面，否则声明的是执行不了的东西）：" + unknown);
    }

    // ② 落点不得是 DM 可用命令：落点本身不能被嵌进令 / 入队 —— 否则"决策落点"可以被当效果再发一次 = 权限放大。
    //   ★ 今天两条落点（sd.IssueDirective / sd.SubmitVerdict）都不打 DmUsableCommand ⇒ 本审计不抛；
    //     它守的是"将来有人给落点补上 DmUsableCommand"这一步。
    Set<String> landingInDmFace = new LinkedHashSet<>(landing);
    landingInDmFace.retainAll(dmUsable);
    if (!landingInDmFace.isEmpty()) {
      throw new IllegalStateException(
          "命令权限装配故障：决策落点同时被声明为 DM 可用命令（落点本身不得可嵌令/入队 —— 否则决策人能把落点当效果再发一次，权限放大）：" + landingInDmFace);
    }

    // 四个面：注册面 = catalog；后两个面 = 同一份声明的"可嵌令 / 可入队"两种用法
    Set<String> catalog = registered;
    Set<String> directive = new LinkedHashSet<>(dmUsable);
    Set<String> drainable = new LinkedHashSet<>(dmUsable);

    // ③ 结果集为空：空 = 谁都看不见 = 装配故障（不是"合法形态"）
    requireNonEmpty(catalog, "catalogTypes（注册面）");
    requireNonEmpty(directive, "directiveTypes（可嵌决策令面）");
    requireNonEmpty(drainable, "drainableTypes（可入效果队列面）");
    requireNonEmpty(landing, "landingPoints（决策落点面）");

    return new CommandFaces(catalog, directive, drainable, landing);
  }

  /** 注册面 ⇒ catalog 面（保序去重），与 {@link #derive} 的 catalog 面同一口径。 */
  public static Set<String> catalogTypes(List<CommandHandler> registeredHandlers) {
    return typesOf(registeredHandlers);
  }

  private static Set<String> typesOf(List<CommandHandler> handlers) {
    Objects.requireNonNull(handlers, "handlers");
    Set<String> types = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      Objects.requireNonNull(handler, "handler");
      types.add(requireType(handler.type()));
    }
    return types;
  }

  private static void requireNonEmpty(Set<String> face, String name) {
    if (face.isEmpty()) {
      throw new IllegalStateException(
          "命令权限装配故障："
              + name
              + " 为空 —— 空集 = 谁都看不见 = 装配故障（四个面都不得为空）。"
              + "若确实是新装配面的第一次落地，请显式声明该面上的命令（DmUsableCommand / landingPoints）。");
    }
  }

  private static String requireType(String type) {
    if (type == null || type.isBlank()) {
      throw new IllegalStateException("命令权限装配故障：命令类型不得为 null/空白: " + type);
    }
    return type;
  }

  /**
   * ① 的产物：注册面上读到的<b>唯一</b>声明集合（{@link DmUsableCommand}）。
   *
   * <p>★ <b>未列入 {@code dmUsableTypes} 的类型 = 仅 GM</b>（反向标记已随批次 1 收尾整体退役 ⇒ 不需要第二个集合）。 ★ {@code
   * dmUsableTypes} 同时是"可嵌令面"与"可入队面"的来源（{@code DmUsableCommand} 的语义覆盖两者， 见 {@link CommandFaces}
   * 的类注）——装配期在 ② 之前要用它构造 {@code sd.RegisterEffect} 与 {@code sd.IssueDirective}，故这里先把它露出来。
   *
   * <p>★★ 冻结写在紧凑构造器体里（<b>不抽辅助方法</b>）：SpotBugs 的 {@code EI_EXPOSE_REP} 只认看得见的包装调用 —— 出处见 {@link
   * CommandFaces} 的类注。
   *
   * @param dmUsableTypes 标了 {@link DmUsableCommand} 的命令类型（保序）
   */
  public record Declarations(Set<String> dmUsableTypes) {

    public Declarations {
      Objects.requireNonNull(dmUsableTypes, "dmUsableTypes");
      dmUsableTypes = Collections.unmodifiableSet(new LinkedHashSet<>(dmUsableTypes));
    }
  }
}
