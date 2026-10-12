package io.mosire.simos.ga;

import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.Verdict;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>ga 切片的完整状态树</b>（2026-10-12 批次 3a 首建两组件；同批后续 <b>收为四组件</b>）： {@code decisionPackets}（一决策人 × 一
 * tick 一个决策包）· {@code mergedEffectPlans}（合并效果集）· {@code directives}（决策记录）· {@code verdicts}（判决）。
 *
 * <p>★★ <b>为什么这四件打头</b>：它们<b>不与 sd 的其余组件做构造期外键</b>到不可搬的程度 —— 决策包与合并效果集本来就没有（{@code
 * SdState.requireDecisionPacketIntegrity} 的形参 {@code decisionMakers} 在方法体里一次没用过）；{@code directives}
 * / {@code verdicts} 之间那条外键（{@code Directive.verdict()}
 * 指向的判决必须存在）<b>两张表都在本模块</b>，故随表一起搬进本模块的构造期（见下）。 ★ <b>诚实边界</b>：{@code Directive} 对 sd 侧 {@code
 * decisionMakers} / {@code effects} 的两条外键 <b>此后是跨切片的</b>（铁律 3：ga 不拥有那两张表）⇒
 * 构造期不再判，改由<b>命令期</b>判（{@code ga.IssueDirective} 逐条校验决策人与效果存在；与 {@code sd.CreateArmy} 的 {@code
 * masterGovUnitId} 同款口径）。这条是本批<b>唯一</b>的 状态期不变量收窄，记在报告里。
 *
 * <p>★★ <b>值类型留在 {@code simos-sd}</b>（{@link DecisionPacket} / {@link MergedEffectPlan} / {@link
 * Directive} / {@link Verdict} 与它们的 id）：它们仍被 sd 的其余组件与<b>sd 的测试</b>引用（{@code SdChangeSet} / {@code
 * SdCodec} / {@code SdResolver} / {@code Judgement} / {@code VerdictFreezer} / …），而 {@code
 * simos-sd} 不得依赖 {@code simos-ga} （那会与既有的 {@code ga → sd} 成环，模块边界 enforcer 建图期直接拒）⇒
 * 本模块<b>反向引用</b>它们 （既有方向，不新增边）。 本批搬的是<b>状态组件</b>（四张表本身），不是这些类型。
 *
 * <p>★ <b>四张表的键都必须等于值内 id</b>（{@code directive} / {@code verdict} 两条不变量自 {@code SdState} 的"键非
 * null"升格而来，与既有两张表同形）：任何绕过 handler 的构造（夹具、回放、分岔）造出键值不一致时，构造期当场抛。
 *
 * <p>★ <b>保序不可变</b>：四张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立（照 {@code GovState} 的既有注记）。
 *
 * <p>★ <b>缺键 = 空表</b>（照 {@code GovState} 的同款 fail-closed 读法）：Jackson 对缺失键的 record 缺参绑 null ⇒
 * 各自收成空表，<b>此处不抛</b>。
 *
 * <p>★ <b>写一张表必须原样带过另外三张</b>：四个 {@code with*} 各自保留其余三张；漏带 = 静默清空。
 *
 * @param decisionPackets 决策包表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param mergedEffectPlans 合并效果集表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param directives 决策记录表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param verdicts 判决表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 */
public record GaState(
    Map<DecisionPacketId, DecisionPacket> decisionPackets,
    Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans,
    Map<DirectiveId, Directive> directives,
    Map<VerdictId, Verdict> verdicts) {

  public GaState {
    // ★ 缺键（null）⇒ 空表，见类注释（fail-closed 方向）。
    decisionPackets = Collections.unmodifiableMap(copyPackets(decisionPackets));
    mergedEffectPlans = Collections.unmodifiableMap(copyPlans(mergedEffectPlans));
    directives = Collections.unmodifiableMap(copyDirectives(directives));
    verdicts = Collections.unmodifiableMap(copyVerdicts(verdicts));
    requirePacketShape(decisionPackets);
    requireAtMostOneActiveDirective(directives);
    requireVerdictReferences(directives, verdicts);
  }

  /** 往返用例与 handler 的起点：四张表全空（= 本世界还没有任何决策包 / 合并效果集 / 决策记录 / 判决）。 */
  public static GaState empty() {
    return new GaState(Map.of(), Map.of(), Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code GovState} / {@code GameMap} 的形制）。
   *
   * <p>★★ <b>本方法保留合并效果集、决策记录与判决</b>：它是 {@code UpsertDecisionPacket} / {@code SubmitDecisionPacket}
   * / {@code DecideDecisionPacket} 三条命令重写 {@code decisionPackets} 的推荐入口。
   */
  public GaState withDecisionPackets(Map<DecisionPacketId, DecisionPacket> value) {
    return new GaState(value, mergedEffectPlans, directives, verdicts);
  }

  /** 仅替换 {@code mergedEffectPlans}，其余三张表原样带过（{@code UpsertMergedEffectPlan} 的入口）。 */
  public GaState withMergedEffectPlans(Map<MergedEffectPlanId, MergedEffectPlan> value) {
    return new GaState(decisionPackets, value, directives, verdicts);
  }

  /**
   * 仅替换 {@code directives}，其余三张表原样带过（{@code ga.IssueDirective} / {@code ga.SetDirectiveStatus}
   * 两条命令的入口）。
   */
  public GaState withDirectives(Map<DirectiveId, Directive> value) {
    return new GaState(decisionPackets, mergedEffectPlans, value, verdicts);
  }

  /** 仅替换 {@code verdicts}，其余三张表原样带过（{@code ga.SubmitVerdict} 的入口）。 */
  public GaState withVerdicts(Map<VerdictId, Verdict> value) {
    return new GaState(decisionPackets, mergedEffectPlans, directives, value);
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

  /** 决策记录表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。 */
  private static Map<DirectiveId, Directive> copyDirectives(Map<DirectiveId, Directive> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DirectiveId, Directive> copy = new LinkedHashMap<>();
    for (Map.Entry<DirectiveId, Directive> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("directives 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "决策记录键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 判决表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。 */
  private static Map<VerdictId, Verdict> copyVerdicts(Map<VerdictId, Verdict> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<VerdictId, Verdict> copy = new LinkedHashMap<>();
    for (Map.Entry<VerdictId, Verdict> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("verdicts 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "判决键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
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

  /**
   * 不变量（自 {@code SdState} 整条搬来，语义一字不改）：同一 ({@code decisionMakerId}, {@code tick}) 下<b>至多一条生效</b>
   * （{@code PLANNED}/{@code ISSUED}）。
   *
   * <p>★★ **为什么不是"唯一"**（2026-09-23 用户裁定）：同一次决策可以"打回重写"—— 重写 = 产生新的一版（新的 {@link DirectiveId}），旧的转
   * {@link DirectiveStatus#SUPERSEDED}（终态，留痕但不再生效）。故同一 ({@code decisionMakerId}, {@code tick})
   * **允许多条 Directive 并存**，只是**生效的至多一条**。
   *
   * <p>★ 这条不变量把"末位生效"从命令期的实现细节升级成**状态层的结构性事实**：任何绕过 {@code ga.IssueDirective}
   * 的构造（测试夹具、回放、分岔）若造出两条同时生效的令，构造期当场抛。
   */
  private static void requireAtMostOneActiveDirective(Map<DirectiveId, Directive> directives) {
    Set<String> seen = new LinkedHashSet<>();
    for (Directive directive : directives.values()) {
      if (!isActive(directive.status())) {
        continue; // 终态（EXECUTED/CANCELLED/SUPERSEDED）不参与"生效名额"。
      }
      String key = directive.decisionMakerId().value() + "@" + directive.tick();
      if (!seen.add(key)) {
        throw new IllegalArgumentException(
            "R4 违反：决策人 "
                + directive.decisionMakerId().value()
                + " 在 tick "
                + directive.tick()
                + " 已有一条**生效中**的 Directive"
                + "（同一 (决策人, tick) 至多一条生效；重写应把旧令翻成 "
                + DirectiveStatus.SUPERSEDED
                + "）");
      }
    }
  }

  /** 该状态是否"生效中"（{@code PLANNED}/{@code ISSUED}）——"末位生效"只认这两档。 */
  private static boolean isActive(DirectiveStatus status) {
    return status == DirectiveStatus.PLANNED || status == DirectiveStatus.ISSUED;
  }

  /**
   * 外键（自 {@code SdState} 整条搬来，且是本批<b>唯一</b>能整条保住的那条）：{@code Directive.verdict()}
   * 指向的判决必须存在——两张表都在本模块，故这条判据仍住在构造期。
   *
   * <p>★ 另外两条外键（{@code directive.decisionMakerId} / {@code directive.effects()}）指向 sd 的表 ⇒
   * 跨切片，构造期判不了（铁律 3）。它们的落点是命令期，见类注释的"诚实边界"。
   */
  private static void requireVerdictReferences(
      Map<DirectiveId, Directive> directives, Map<VerdictId, Verdict> verdicts) {
    for (Directive directive : directives.values()) {
      if (directive.verdict().isPresent() && !verdicts.containsKey(directive.verdict().get())) {
        throw new IllegalArgumentException(
            "引用完整性：Directive "
                + directive.id().value()
                + " 的判决 "
                + directive.verdict().get()
                + " 不存在");
      }
    }
  }
}
