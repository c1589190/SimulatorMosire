package io.mosire.simos.util.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次时间推进里**某个模块自报的突发事件**（2026-10-13 急停协议，用户原话：「让 util/core 之类的模块维护一个急停协议，如果正在推进
 * tick，且**任意模块报突发事件**，那么自动停下」）。
 *
 * <p>★★ **职责切分的落点**：{@code util} 出**容器与时刻**，模块出**种类与载荷**。
 *
 * <ul>
 *   <li>{@code kind} —— **字符串**，不是 util 的枚举（**不变量 I5**）：util **不认识**"两军相遇"，判据种类由模块自己定义 （Army 报
 *       {@code army.contact}、Unit 报单位接触、…）。util/core **不得**出现任何具体 kind 的字面量判断—— 否则判据就有第二个拼写点。
 *   <li>{@code tick} —— **发生在哪一天**（急停就停在这天）。{@code TimeAdvance} 会机械核对它与正在推进的当天一致 ——
 *       不一致说明模块报错了日子，宁可当场拒，也不要产出一份 {@code atTick} 与事件日对不上的 {@link StopReport}。
 *   <li>{@code namespace} —— 哪个参与者报的（{@code ga} / {@code population} / {@code army} / …）：原因系统按它归并。
 *   <li>{@code payload} —— 结构化证据（如 {@code {armies:[a,b], hex:"3_0"}}）。**不许放 null**（键、值都不许，沿用既有纪律）；
 *       ★ 地图/负荷明文**不进日志**（日志纪律），故它只进 {@link StopReport} 的结构化读口，不进文本日志。
 *   <li>{@code logRefs} —— 这一天的**日志锚**，**由模块自报**（util/core 不解释它们，与 I5 同一条纪律）：GM/调试据此从"第 5
 *       天为什么停"跳到那天的日志。
 * </ul>
 *
 * <p>★★ **它不是世界事实**：不进 revision、不许被 AAR 引用为稳定实体 ⇒ **不带 {@code Id} 类型**、不参与铁律 1 的稳定实体体系。
 * 这是**有意**的：它是**过程观测**，与 {@code DecisionRunRegistry} 的"进程内账"同族。★ 若将来要 AAR 引用它，**另开**一份世界事实，
 * 不许就地把它塞进 revision。
 *
 * <p>★ **形状的加法**：约束设计书 §4.1 冻结的形状是四件 {@code (kind, tick, namespace, payload)}，而 §4.3.1 又要求 {@code
 * StopReport.reasons[].logRefs} **由模块自报**（并据此写了"`Incident.logRefs`"）——两者只有一处能落。 这里按"**加字段 +
 * 兼容构造器**" （与 {@link TimeProposal} 的 I7 同款纪律）处理：{@code logRefs} 追加为**末位组件**， 四参构造器**逐字保留**、默认空表 ⇒
 * §4.1 的写法继续可用，既有参数顺序一位未动。
 *
 * <p>★ 组件**防御性拷贝且保序**：{@code payload} 用 {@code LinkedHashMap}、{@code logRefs} 用 {@code ArrayList}
 * 包一层不可变视图 ——落盘/读口的字节要可复现，而 {@code Map.copyOf} 的迭代序不是内容的纯函数（M2 Task 5 实测 30 次）。 包装写在**字段赋值表达式上** （与
 * {@link TimeProposal} 同一条纪律：SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析）。
 */
public record Incident(
    String kind, long tick, String namespace, Map<String, Object> payload, List<String> logRefs) {

  public Incident {
    kind = requireText(kind, "kind");
    namespace = requireText(namespace, "namespace");
    Objects.requireNonNull(payload, "payload");
    LinkedHashMap<String, Object> payloadCopy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      payloadCopy.put(
          Objects.requireNonNull(entry.getKey(), "payload 的键"),
          Objects.requireNonNull(entry.getValue(), "payload 的值"));
    }
    payload = Collections.unmodifiableMap(payloadCopy);
    logRefs = Collections.unmodifiableList(orderedCopy(logRefs, "logRefs"));
  }

  /**
   * ★ **四参兼容构造器**（约束设计书 §4.1 冻结的形状）：没有日志锚 ⇒ 空表。
   *
   * <p>它与五参规范构造器**并存**，正是"既有写法零改动"的兑现：模块只报 {@code (kind, tick, namespace, payload)} 时不必知道 {@code
   * logRefs} 的存在。
   */
  public Incident(String kind, long tick, String namespace, Map<String, Object> payload) {
    this(kind, tick, namespace, payload, List.of());
  }

  /** 非 null、非空白文本；失败消息点名参数（红点落在肇事点上）。 */
  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
    return value;
  }

  /** 逐元素 null/空白校验的**保序**拷贝（与 {@link TimeProposal} 同法，失败消息形如 "{@code <name>} 的元素"）。 */
  private static List<String> orderedCopy(List<String> source, String name) {
    Objects.requireNonNull(source, name);
    List<String> copy = new ArrayList<>();
    for (String element : source) {
      copy.add(requireText(element, name + " 的元素"));
    }
    return copy;
  }
}
