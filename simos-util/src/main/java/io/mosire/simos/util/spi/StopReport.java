package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 急停的**原因系统**（约束设计书 §4.3.1，用户 2026-10-13 追加要求：「甚至要做一个**原因系统**，明确是**哪些模块**（可能不止一个模块要求停下呢？），
 * 最好**附上日志之类的**，方便 **GM 用 MCP 工具查**，也方便你调试」）。
 *
 * <pre>
 * StopReport {
 *   atTick       停在哪一天（世界已推进到这天，且已 drain）
 *   worldRef     落盘坐标 {branch, revision}（GM 可据此 replay 到这一刻）
 *   reasons[]    ★ 聚合：每个要求停的模块各一条，不是只取第一个
 * }
 * </pre>
 *
 * <p>★ **为什么聚合**：只报第一个原因会让 GM 看到**片面**信息（可能两个模块同一天各自都要停）。★ 本实现的粒度是"**一个 {@link Incident}
 * 一条原因**"：既不截断也不去重 —— "每个模块各一条"在"每个模块各报一个事件"时自然成立，而一个模块报了三件事时**丢掉两件** 才是真损失。
 *
 * <p>★ **为什么带 {@code worldRef}**：GM 要能 replay 到"停下的那一刻"，看完整世界。它是**急停路径落完 revision（并跑完
 * drain）之后**的真实坐标 ——不是推进开始时的坐标，也不是调用方的期望值。
 *
 * <p>★ **为什么带 {@code logRefs}**：用户原话"方便你调试" —— 从"第 5 天为什么停"直接跳到那天的日志。 它们**由模块自报**， util/core
 * **不解释**它们（不变量 I5：util 不认识领域语义）。
 *
 * <p>★ **住 util 的理由**（设计书未指定，如实记）：本类型被 **core 产出**、被 **app 的 GUI/MCP 读口**读、并被 **simos-army 的 MCP
 * 读工具**读（§7.2）。而 {@code simos-army} 的允许依赖**不含 core**（{@code AGENTS.md} §三：util+map+unit+social+gov）
 * ——若它住 core，B 的读工具当场编译不过。它只由 {@code atTick} / {@link StateRef} / 模块自报的三件构成，**零领域语义** ⇒ 落 {@code
 * util.spi}（"新增契约放本包"）不越界。它**不进 revision、不进世界状态**（过程观测，与 {@code DecisionRunRegistry} 同族）。
 *
 * <p>★ 组件**防御性拷贝且保序**：{@code reasons} 的次序 = 参与者字典序 + 各自上报次序（决定论；{@code TimeAdvance} 的参与者清单在构造期按
 * namespace 字典序定死）。包装写在字段赋值表达式上（SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析）。
 */
public record StopReport(long atTick, StateRef worldRef, List<Reason> reasons) {

  public StopReport {
    Objects.requireNonNull(worldRef, "worldRef");
    Objects.requireNonNull(reasons, "reasons");
    if (reasons.isEmpty()) {
      // ★ 空原因不是"没事发生"，是**构造错误**：没有原因就不是急停（那次推进根本不该停）。
      throw new IllegalArgumentException("reasons 不得为空：没有原因就不是急停（atTick=" + atTick + "）");
    }
    List<Reason> reasonsCopy = new ArrayList<>();
    for (Reason reason : reasons) {
      reasonsCopy.add(Objects.requireNonNull(reason, "reasons 的元素"));
    }
    reasons = Collections.unmodifiableList(reasonsCopy);
  }

  /**
   * ★ 由模块自报的 {@link Incident} 折出（core 只做**搬运**：不解释 {@code kind} 与 {@code payload}）。
   *
   * @param atTick 停在哪一天（{@code TimeAdvance} 已核对过每条 incident 的 {@code tick} 都等于它）
   * @param worldRef 急停路径落完 revision（并跑完 drain）之后的真实坐标
   * @param incidents 当天**全部**模块自报的突发事件（聚合，不截断）
   * @throws IllegalArgumentException {@code incidents} 为空（没有原因就不是急停）
   */
  public static StopReport of(long atTick, StateRef worldRef, List<Incident> incidents) {
    Objects.requireNonNull(incidents, "incidents");
    if (incidents.isEmpty()) {
      throw new IllegalArgumentException("incidents 不得为空：没有原因就不是急停（atTick=" + atTick + "）");
    }
    List<Reason> reasons = new ArrayList<>();
    for (Incident incident : incidents) {
      reasons.add(Reason.of(incident));
    }
    return new StopReport(atTick, worldRef, reasons);
  }

  /**
   * 一条原因：**哪个模块、报了什么种类、证据是什么、那天日志在哪**。
   *
   * <p>★ 它逐字保留模块自报的四件（{@code namespace} / {@code kind} / {@code payload} / {@code logRefs}），core
   * 一件都不加工 ——加工就会引入第二个拼写点（I5）。
   */
  public record Reason(
      String namespace, String kind, Map<String, Object> payload, List<String> logRefs) {

    public Reason {
      namespace = requireText(namespace, "namespace");
      kind = requireText(kind, "kind");
      Objects.requireNonNull(payload, "payload");
      LinkedHashMap<String, Object> payloadCopy = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : payload.entrySet()) {
        payloadCopy.put(
            Objects.requireNonNull(entry.getKey(), "payload 的键"),
            Objects.requireNonNull(entry.getValue(), "payload 的值"));
      }
      payload = Collections.unmodifiableMap(payloadCopy);
      Objects.requireNonNull(logRefs, "logRefs");
      List<String> refsCopy = new ArrayList<>();
      for (String ref : logRefs) {
        refsCopy.add(requireText(ref, "logRefs 的元素"));
      }
      logRefs = Collections.unmodifiableList(refsCopy);
    }

    /** 由一条模块自报的突发事件折出（**纯搬运**，不改一个字）。 */
    public static Reason of(Incident incident) {
      Objects.requireNonNull(incident, "incident");
      return new Reason(
          incident.namespace(), incident.kind(), incident.payload(), incident.logRefs());
    }
  }

  /** 非 null、非空白文本；失败消息点名参数（红点落在肇事点上）。 */
  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
    return value;
  }
}
