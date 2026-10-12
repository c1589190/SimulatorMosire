package io.mosire.simos.ga;

import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffectPlan;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>ga 切片的完整状态树</b>（2026-10-12 批次 3a，用户裁定「ga 应当维护标准的一般决策人……储存可以交给 Core」）： <b>恰两个组件</b> ——
 * {@code decisionPackets}（一决策人 × 一 tick 一个决策包）与 {@code mergedEffectPlans}（合并效果集）。
 *
 * <p>★★ <b>为什么是这两件打头</b>：它们<b>不与 {@code decisionMakers} 做构造期外键</b> （{@code
 * SdState.requireDecisionPacketIntegrity} 的形参 {@code decisionMakers} 在方法体里一次没用过，只有 javadoc 提过）， ⇒
 * 可以整表搬进本模块而不牵动 sd 的引用完整性不变量。proposer / participant 允许指向<b>已被删除</b>的决策人（历史包不级联删； 新写入的存在性由 handler
 * 判）。
 *
 * <p>★★ <b>值类型留在 {@code simos-sd}</b>（{@link DecisionPacket} / {@link MergedEffectPlan} / 两个 id）：
 * 它们仍被 sd 的其余组件与<b>sd 的测试</b>引用，而 {@code simos-sd} 不得依赖 {@code simos-ga} （那会与既有的 {@code ga → sd}
 * 成环，模块边界 enforcer 建图期直接拒）⇒ 本模块<b>反向引用</b>它们（既有方向，不新增边）。 本批搬的是<b>状态组件</b>（两张表本身），不是这些类型。
 *
 * <p>★ <b>两张表的键都必须等于值内 id</b>（照 {@code SdState} 的原不变量，语义一字不改）：这条不变式随表一起搬进本模块 ⇒ 任何绕过 handler
 * 的构造（夹具、回放、分岔）造出键值不一致时，构造期当场抛。
 *
 * <p>★ <b>保序不可变</b>：两张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立（照 {@code GovState} 的既有注记）。
 *
 * <p>★ <b>缺键 = 空表</b>（照 {@code GovState} 的同款 fail-closed 读法）：Jackson 对缺失键的 record 缺参绑 null ⇒
 * 各自收成空表，<b>此处不抛</b>。
 *
 * <p>★ <b>写一张表必须原样带过另一张</b>：{@link #withDecisionPackets(Map)} / {@link #withMergedEffectPlans(Map)}
 * 各自保留另一张表；漏带 = 静默清空。
 *
 * @param decisionPackets 决策包表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param mergedEffectPlans 合并效果集表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 */
public record GaState(
    Map<DecisionPacketId, DecisionPacket> decisionPackets,
    Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans) {

  public GaState {
    // ★ 缺键（null）⇒ 空表，见类注释（fail-closed 方向）。
    decisionPackets = Collections.unmodifiableMap(copyPackets(decisionPackets));
    mergedEffectPlans = Collections.unmodifiableMap(copyPlans(mergedEffectPlans));
    requirePacketShape(decisionPackets);
  }

  /** 往返用例与 handler 的起点：两张表全空（= 本世界还没有任何决策包 / 合并效果集）。 */
  public static GaState empty() {
    return new GaState(Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code GovState} / {@code GameMap} 的形制）。
   *
   * <p>★★ <b>本方法保留合并效果集</b>：它是 {@code UpsertDecisionPacket} / {@code SubmitDecisionPacket} / {@code
   * DecideDecisionPacket} 三条命令重写 {@code decisionPackets} 的推荐入口。
   */
  public GaState withDecisionPackets(Map<DecisionPacketId, DecisionPacket> value) {
    return new GaState(value, mergedEffectPlans);
  }

  /** 仅替换 {@code mergedEffectPlans}，决策包表原样带过（{@code UpsertMergedEffectPlan} 的入口）。 */
  public GaState withMergedEffectPlans(Map<MergedEffectPlanId, MergedEffectPlan> value) {
    return new GaState(decisionPackets, value);
  }

  /** 决策包表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。 */
  private static Map<DecisionPacketId, DecisionPacket> copyPackets(
      Map<DecisionPacketId, DecisionPacket> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DecisionPacketId, DecisionPacket> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionPacketId, DecisionPacket> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("decisionPackets 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "决策包键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 合并效果集表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。 */
  private static Map<MergedEffectPlanId, MergedEffectPlan> copyPlans(
      Map<MergedEffectPlanId, MergedEffectPlan> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<MergedEffectPlanId, MergedEffectPlan> copy = new LinkedHashMap<>();
    for (Map.Entry<MergedEffectPlanId, MergedEffectPlan> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("mergedEffectPlans 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "合并效果集键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /**
   * 包内形状：{@code MERGED} 的 call 必须带 {@code mergedPlanId}（自 {@code SdState} 的原不变量整条搬来）。
   *
   * <p>★ 其余两条原不变量（键 == 值内 id、{@code callIndex} 严格递增）已在拷贝或 {@link DecisionPacket} 自己的构造期判， 不在此重复。
   */
  private static void requirePacketShape(Map<DecisionPacketId, DecisionPacket> packets) {
    for (DecisionPacket packet : packets.values()) {
      for (FormattedCall call : packet.calls()) {
        if (call.status() == CallStatus.MERGED && call.mergedPlanId().isEmpty()) {
          throw new IllegalArgumentException(
              "MERGED 的 FormattedCall 必须带 mergedPlanId: "
                  + packet.id().value()
                  + ":"
                  + call.callIndex());
        }
      }
    }
  }
}
