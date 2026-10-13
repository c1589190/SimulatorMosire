package io.mosire.simos.ga.decision;

import java.util.Set;

/**
 * **决策回合用到的工具名声明**（宿主注入）：运行流不认识任何"域工具"类，工具名一律由装配方给。
 *
 * <p>★★ <b>为什么注入而不是在 ga 里写字面量</b>：这些名字是**平台身份**（进 catalog、进白名单、进工具面的账）， 唯一拼写点是各工具类自己的 {@code NAME}
 * 常量。运行流若抄一份字面量，工具一改名就会**静默错位**—— 例如"提交工具在回合内只登记、由回合末统一结算"这条行为，靠的就是认出提交工具的名字；名字漂了它不会报错，只会悄悄不再发生。
 * 故本接口只声明"我需要哪些名字"，值由 {@code simos-app} 从各工具常量取（设计书 §4.5 I-G2：宿主事实构造期注入）。
 *
 * <p>★ <b>为什么是接口而不是一个多字段值对象</b>：10 个名字全是同型的字符串，位置式构造（一串字面量/常量按顺序传）**传串了不会有任何症状**；
 * 具名访问器让装配方逐个写清楚，读起来也不会数错位。
 *
 * <p>★ <b>两处"集合"是派生而不是第二次声明</b>：{@link #packetTools()} 与 {@link #statelessReads()} 由上面的访问器派生 ⇒
 * 改一个名字只需要改一处（与设计书 §4.4 "一处声明、三处派生"同一口径）。
 */
public interface DecisionToolNames {

  /** 命令/能力目录读工具（开场消息里"先查看"的第一件）。 */
  String catalog();

  /** 分支与 head 列表读工具（开场消息要它，因为 {@code expectedRevision} 必须填它读到的当下 head）。 */
  String branches();

  /** 时间轴节点清单读工具（冻结回合里属"不读世界 revision"的共享读口）。 */
  String timelineRevisions();

  /** 决策包：提议一次真实工具调用（结构命令的唯一表达）。 */
  String propose();

  /** 决策包：写自然语言决策。 */
  String packetIntent();

  /** 决策包：提交本 tick 的包（运行流对它有**回合内拦截**：只登记、回合末统一结算）。 */
  String packetSubmit();

  /** 决策包：查看自己的包。 */
  String myPacket();

  /** 旧单人回合的出令工具（开场消息在非冻结回合里仍要交代它的两条硬约束）。 */
  String issueDirective();

  /** 旧单人回合的裁决工具（同上）。 */
  String submitVerdict();

  /**
   * **宿主编排配置里"有没有视觉能力"那个键**（渲染工具按它决定 {@code format=auto} 落在图还是字符图）。
   *
   * <p>★ 键的拼写点在渲染工具那边（{@code MapRenderTool.VISION_CONFIG_KEY}）；运行流只把它透传给工具上下文，不解释它。
   */
  String visionConfigKey();

  /** **决策包四件套**（冻结回合里唯一允许的写；写的是各自的 packet 前缀，不是世界）。 */
  default Set<String> packetTools() {
    return Set.of(propose(), packetIntent(), packetSubmit(), myPacket());
  }

  /** **不读世界 revision、也不写世界的共享读口**（目录/分支列表/阶段列表）：冻结回合里原样放行。 */
  default Set<String> statelessReads() {
    return Set.of(catalog(), branches(), timelineRevisions());
  }
}
