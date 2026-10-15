package io.mosire.simos.ga;

import io.mosire.simos.ga.effect.Effect;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.id.DirectiveId;
import io.mosire.simos.ga.id.EffectId;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.id.VerdictId;
import io.mosire.simos.ga.model.CallStatus;
import io.mosire.simos.ga.model.DecisionInfoEntry;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.Directive;
import io.mosire.simos.ga.model.DirectiveStatus;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.model.Verdict;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>ga 切片的完整状态树</b>（2026-10-12 批次 3a 首建两组件；同批后续 <b>收为四组件</b>；批次 3b-2 <b>再收为六组件</b>； <b>本批 3b-4
 * 再收为七组件</b>）： {@code decisionPackets}（一决策人 × 一 tick 一个决策包）· {@code mergedEffectPlans}（合并效果集）·
 * {@code directives}（决策记录） · {@code verdicts}（判决）· {@code decisionMakers}（决策人身份）· {@code info}（感知层
 * INFO 覆盖层）· {@code effects}（决策层的「未来／条件动作队列」）。
 *
 * <p>★★ <b>为什么这七件是一族</b>：它们就是 sd 原 14 组件里<b>决策侧的 7 件</b>——3a 搬两张表、3b-1/3b-2 搬四张表， 最后一件 {@code
 * effects} 因要开 {@code ga → unit + ga → map} 两条边（{@code Trigger.UnitAtHex(UnitId, HexCoord)}）
 * 而顺位到本批（用户裁定「<b>那让它依赖unit呗</b>」＋「<b>sd 整个弃用！</b>」，见规划 §15 / §18.2 第 3 步）。批次 3b-2 起， {@code
 * directives.decisionMakerId} 那条外键<b>又回到了同一棵树里</b> ⇒ 它<b>整条</b>恢复为构造期不变量（见下）。
 *
 * <p>★★ <b>本批 3b-4 收口的"诚实边界"（如实记）</b>：3a–3b′ 期间 {@code Directive.effects()} 指向的 {@code effects}
 * 表<b>仍住 sd</b> ⇒ 那条外键是<b>跨切片</b>的（铁律 3：ga 不拥有 {@code effects}），状态期判不了、落点在<b>命令期</b>（{@code
 * ga.IssueDirective} 逐条校验效果存在）。<b>本批 {@code effects} 搬进本树 ⇒ 那个前提消失</b>。★ <b>本批有意不把
 * 该判据搬回构造期</b>：本批的红线是"2 条改名不改数 + effect 的求值/触发/落地语义逐字不变"，而命令期判据已覆盖全部写路径（{@code ga.IssueDirective}
 * 逐条 + {@code ga.RegisterEffect}/{@code ga.CancelEffect} 自身的不变量）⇒ 再加一条构造期判据会新开一个
 * "搬迁前能构造、搬迁后当场抛"的失败面，属<b>另一次决定</b>（记入台账，留 3e 或后续批次复核）。今天这条外键的落点仍是<b>命令期</b>， 与 {@code
 * army.CreateArmy} 的 {@code masterGovUnitId} 同款口径。
 *
 * <p>★★ <b>值类型仍留 {@code simos-sd}</b>（{@link DecisionPacket} / {@link MergedEffectPlan} / {@link
 * Directive} / {@link Verdict} / {@link DecisionMaker} / {@link DecisionInfoEntry} / {@code
 * EffectId} 及它们的 id）：它们仍被 sd 的其余组件与 <b>sd 的测试</b>引用，而 {@code simos-sd} 不得依赖 {@code simos-ga}
 * （那会与既有的 {@code ga → sd} 成环，模块边界 enforcer 建图期直接拒）⇒ 本模块<b>反向引用</b>它们 （既有方向，不新增边，零 pom 改动）。★
 * 本批唯一的例外是 {@link Effect} 整族（{@code Effect}/{@code EffectKind}/{@code EffectStatus}/{@code
 * Trigger}/{@code Action}）：它们<b>搬进本模块</b> （{@code io.mosire.simos.ga.effect}）—— 判据是"搬走后 sd
 * 侧是否仍引用它"：{@code Directive.effects()} 只引 {@code EffectId}（<b>留 sd</b>），其余四个类型 sd 侧零引用 ⇒ 搬它们不产生任何
 * {@code sd → ga} 边。
 *
 * <p>★ <b>七张表的键都必须等于值内 id</b>（{@code decisionMaker} 一条与其余五张同形；{@code info} 的键是 canonical 地址串、
 * 值是**该地址下的条目列表**——{@code FieldDelta} 的键约定是"各 key 类型的裸值 {@code toString()}"，而 {@code Address} 没有重写
 * {@code toString}（{@code SD} 侧原注：既有用例把那个"不等"钉住了）⇒ 键取 {@code Address#canonical()}， canonical
 * 唯一、可逆，谓词层不变）。任何绕过 handler 的构造（夹具、回放、分岔）造出键值不一致时，构造期当场抛。
 *
 * <p>★ <b>保序不可变</b>：七张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立（照 {@code GovState} 的既有注记）。
 *
 * <p>★ <b>缺键 = 空表</b>（照 {@code GovState} 的同款 fail-closed 读法）：Jackson 对缺失键的 record 缺参绑 null ⇒
 * 各自收成空表，<b>此处不抛</b>。
 *
 * <p>★ <b>写一张表必须原样带过另外六张</b>：七个 {@code with*} 各自保留其余六张；漏带 = 静默清空。
 *
 * @param decisionPackets 决策包表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param mergedEffectPlans 合并效果集表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param directives 决策记录表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param verdicts 判决表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param decisionMakers 决策人身份表（键 == 值内 {@code id}；保序不可变；缺键读成空表）
 * @param info 感知层 INFO 覆盖层（键 = canonical 地址串；保序不可变；缺键读成空表）
 * @param effects 效果表（键 == 值内 {@code id}；保序不可变；缺键读成空表）——★ 本批 3b-4 新收的第 7 组件（放末位 ⇒
 *     既有的六个键在线格式里的次序一字未动）
 */
public record GaState(
    Map<DecisionPacketId, DecisionPacket> decisionPackets,
    Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans,
    Map<DirectiveId, Directive> directives,
    Map<VerdictId, Verdict> verdicts,
    Map<DecisionMakerId, DecisionMaker> decisionMakers,
    Map<String, List<DecisionInfoEntry>> info,
    Map<EffectId, Effect> effects) {

  public GaState {
    // ★ 缺键（null）⇒ 空表，见类注释（fail-closed 方向）。
    decisionPackets = Collections.unmodifiableMap(copyPackets(decisionPackets));
    mergedEffectPlans = Collections.unmodifiableMap(copyPlans(mergedEffectPlans));
    directives = Collections.unmodifiableMap(copyDirectives(directives));
    verdicts = Collections.unmodifiableMap(copyVerdicts(verdicts));
    decisionMakers = Collections.unmodifiableMap(copyDecisionMakers(decisionMakers));
    info = Collections.unmodifiableMap(copyInfo(info));
    effects = Collections.unmodifiableMap(copyEffects(effects));
    requirePacketShape(decisionPackets);
    requireAtMostOneActiveDirective(directives);
    requireVerdictReferences(directives, verdicts);
    requireDecisionMakerReferences(directives, decisionMakers);
  }

  /** 往返用例与 handler 的起点：七张表全空（= 本世界还没有任何决策包 / 合并效果集 / 决策记录 / 判决 / 决策人 / INFO / 效果）。 */
  public static GaState empty() {
    return new GaState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code GovState} / {@code GameMap} 的形制）。
   *
   * <p>★★ <b>本方法保留合并效果集、决策记录、判决、决策人与 INFO / 效果</b>：它是 {@code UpsertDecisionPacket} / {@code
   * SubmitDecisionPacket} / {@code DecideDecisionPacket} 三条命令重写 {@code decisionPackets} 的推荐入口。
   */
  public GaState withDecisionPackets(Map<DecisionPacketId, DecisionPacket> value) {
    return new GaState(
        value, mergedEffectPlans, directives, verdicts, decisionMakers, info, effects);
  }

  /** 仅替换 {@code mergedEffectPlans}，其余六张表原样带过（{@code UpsertMergedEffectPlan} 的入口）。 */
  public GaState withMergedEffectPlans(Map<MergedEffectPlanId, MergedEffectPlan> value) {
    return new GaState(decisionPackets, value, directives, verdicts, decisionMakers, info, effects);
  }

  /**
   * 仅替换 {@code directives}，其余六张表原样带过（{@code ga.IssueDirective} / {@code ga.SetDirectiveStatus}
   * 两条命令的入口）。
   */
  public GaState withDirectives(Map<DirectiveId, Directive> value) {
    return new GaState(
        decisionPackets, mergedEffectPlans, value, verdicts, decisionMakers, info, effects);
  }

  /** 仅替换 {@code verdicts}，其余六张表原样带过（{@code ga.SubmitVerdict} 的入口）。 */
  public GaState withVerdicts(Map<VerdictId, Verdict> value) {
    return new GaState(
        decisionPackets, mergedEffectPlans, directives, value, decisionMakers, info, effects);
  }

  /** 仅替换 {@code decisionMakers}，其余六张表原样带过（{@code ga.CreateDecisionMaker} 等五条命令的入口）。 */
  public GaState withDecisionMakers(Map<DecisionMakerId, DecisionMaker> value) {
    return new GaState(
        decisionPackets, mergedEffectPlans, directives, verdicts, value, info, effects);
  }

  /**
   * 仅替换 {@code info}，其余六张表原样带过（{@code ga.PutInfo} / {@code ga.StartDecision} / {@code
   * ga.RunDecision} 与 {@code ga.IssueDirective} 的执行原文四条写路径的入口；★ 本批 3b-4 起 {@code GaTimeParticipant}
   * 的 {@code Action.PutInfo} 也走这里——它不再需要跨切片写口）。
   */
  public GaState withInfo(Map<String, List<DecisionInfoEntry>> value) {
    return new GaState(
        decisionPackets, mergedEffectPlans, directives, verdicts, decisionMakers, value, effects);
  }

  /**
   * 仅替换 {@code effects}，其余六张表原样带过（★ 本批 3b-4 新增：{@code ga.RegisterEffect} / {@code ga.CancelEffect}
   * 两条命令与 {@link io.mosire.simos.ga.effect.GaTimeParticipant} 的逐日推进入口）。
   */
  public GaState withEffects(Map<EffectId, Effect> value) {
    return new GaState(
        decisionPackets, mergedEffectPlans, directives, verdicts, decisionMakers, info, value);
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

  /** 决策人表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。 */
  private static Map<DecisionMakerId, DecisionMaker> copyDecisionMakers(
      Map<DecisionMakerId, DecisionMaker> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DecisionMakerId, DecisionMaker> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, DecisionMaker> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("decisionMakers 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "决策人键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /**
   * INFO 覆盖层拷贝 + 键/值非 null；null ⇒ 空表（缺键读法）。
   *
   * <p>★ 条目列表按 {@code List.copyOf} 冻结（自 {@code SdState.copyInfo} 整条搬来，语义一字不改）：条目只追加、不删改， 故"该地址下第 n
   * 条"的序号在写入侧天然注入。
   */
  private static Map<String, List<DecisionInfoEntry>> copyInfo(
      Map<String, List<DecisionInfoEntry>> source) {
    if (source == null) {
      return new LinkedHashMap<>();
    }
    Map<String, List<DecisionInfoEntry>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<DecisionInfoEntry>> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("info 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return copy;
  }

  /**
   * 效果表拷贝 + 键/值非 null + 键 == 值内 id；null ⇒ 空表（缺键读法）。
   *
   * <p>★★ <b>2026-10-12 批次 3b-4：本方法自 {@code SdState.copyOf} 的效果那一支整条搬来</b>（那次搬运把 {@code effects} 从
   * sd 的唯一组件变成本树的第 7 组件）：<b>键 == 值内 id 的构造期判据一字未改</b>（自 3a 起，六张表各自都有这一条）， 保序不可变（{@code
   * LinkedHashMap} + 赋值处冻结）与"绝不用 {@code Map.copyOf}"的纪律也逐字照旧。
   */
  private static Map<EffectId, Effect> copyEffects(Map<EffectId, Effect> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<EffectId, Effect> copy = new LinkedHashMap<>();
    for (Map.Entry<EffectId, Effect> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("effects 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "效果键必须等于值内 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
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
   * 外键（批次 3b-2 <b>整条恢复</b>）：{@code Directive.verdict()} 指向的判决必须存在——两张表都在本模块，故这条判据住在构造期。
   *
   * <p>★ 另外一条（{@code directive.effects()}）指向 sd 仍持有的 {@code effects} 表 ⇒ 跨切片，构造期判不了（铁律 3）。
   * 它的落点是命令期，见类注释的"诚实边界"。
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

  /**
   * 外键（自 {@code SdState} 原不变量整条搬回）：{@code Directive.decisionMakerId} 指向的决策人必须存在。
   *
   * <p>★★ <b>批次 3b-1 曾把它降级到命令期</b>（那时 {@code directives} 已搬 ga、{@code decisionMakers} 仍住 sd ⇒ 跨切片）；
   * 批次 3b-2 把 {@code decisionMakers} 也搬进本模块 ⇒ 两张表<b>又同在一棵树里</b>，这条判据<b>整条回到构造期</b>
   * （不再是"命令期唯一拦口"，而是"命令期 + 构造期双层"——{@code ga.DeleteDecisionMaker} 的"不级联"守卫仍留作命令期前置， 让拒因可读）。
   */
  private static void requireDecisionMakerReferences(
      Map<DirectiveId, Directive> directives, Map<DecisionMakerId, DecisionMaker> decisionMakers) {
    for (Directive directive : directives.values()) {
      if (!decisionMakers.containsKey(directive.decisionMakerId())) {
        throw new IllegalArgumentException(
            "引用完整性：Directive "
                + directive.id().value()
                + " 的 decisionMakerId "
                + directive.decisionMakerId().value()
                + " 不存在");
      }
    }
  }
}
