package io.mosire.simos.sd.state;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.Verdict;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sd 模块的完整状态树（spec §三.1，铁律 5 的"完整状态类型"）。
 *
 * <p>★ **树与切片分工照 map 的 {@code GameMap}（树）/ {@code MapSnapshot}（切片）**：本类是树，落盘切片是 {@link
 * SdSnapshot}。spec §三.1 把 {@code implements Snapshot} 写在了状态树头（设计形状的笔误），执行期按"树 / 切片分离"落地 ——
 * 记入台账取代说明。
 *
 * <p>★ **10 个组件与 {@link io.mosire.simos.sd.change.SdChangeSet} 的 10 个组件一一对应**（铁律 5）：任何新增组件都要同时进变更集，
 * 由 {@code SdRoundTripTest} 的反射枚举把守。
 *
 * <p>★ **两张表的键都保序不可变**（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**禁用** {@code
 * Map.copyOf} ——迭代序不是内容的纯函数，M2 Task 5 实测）。冻结那一步**写在赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 只认它看得见的包装）。
 *
 * <p>★ **{@code info} 的键是 canonical 地址串、值是该地址下的条目列表**：spec §三.1 写作 {@code Map<Address, …>}，但 {@code
 * FieldDelta} 的 key 约定是"各 key 类型的裸值 {@code toString()}"，而 {@code Address}（util 的类型）**没有**重写 {@code
 * toString}（它的 canonical 另有其法，且既有用例把这个"不等"钉住了）⇒ 用 {@code Address} 作变更集键会破往返。故键取 {@code
 * Address#canonical()}（canonical 唯一、可逆），谓词层不变。记入台账取代说明。
 */
public record SdState(
    Map<NationId, Nation> nations,
    Map<ArmyId, Army> armies,
    Map<CombatId, Combat> combats,
    Map<CombatStateId, CombatState> combatStates,
    Map<DecisionMakerId, DecisionMaker> decisionMakers,
    Map<DirectiveId, Directive> directives,
    Map<EffectId, Effect> effects,
    Map<VerdictId, Verdict> verdicts,
    Map<LossRecordId, LossRecord> lossRecords,
    Map<String, List<SdInfoEntry>> info) {

  public SdState {
    nations = Collections.unmodifiableMap(copyOf(nations, "nations"));
    armies = Collections.unmodifiableMap(copyOf(armies, "armies"));
    combats = Collections.unmodifiableMap(copyOf(combats, "combats"));
    combatStates = Collections.unmodifiableMap(copyOf(combatStates, "combatStates"));
    decisionMakers = Collections.unmodifiableMap(copyOf(decisionMakers, "decisionMakers"));
    directives = Collections.unmodifiableMap(copyOf(directives, "directives"));
    effects = Collections.unmodifiableMap(copyOf(effects, "effects"));
    verdicts = Collections.unmodifiableMap(copyOf(verdicts, "verdicts"));
    lossRecords = Collections.unmodifiableMap(copyOf(lossRecords, "lossRecords"));
    info = Collections.unmodifiableMap(copyInfo(info));

    requireAtMostOneActiveDirective(directives);
    requireReferentialIntegrity(
        combats, combatStates, decisionMakers, directives, effects, verdicts, lossRecords);
    requireOutcomeConsistency(combats, combatStates);
    requireStageChains(combats);
    requireLossConsistency(combatStates, lossRecords);
  }

  /** 往返用例与 handlers 的起点。 */
  public static SdState empty() {
    return new SdState(
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of());
  }

  // ── 逐组件替换（一个组件一个 with，照 GameMap 的形制）────────────────────────────────

  /** 仅替换 {@code nations}，其余 9 个组件原样。 */
  public SdState withNations(Map<NationId, Nation> v) {
    return new SdState(
        v,
        armies,
        combats,
        combatStates,
        decisionMakers,
        directives,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code armies}。 */
  public SdState withArmies(Map<ArmyId, Army> v) {
    return new SdState(
        nations,
        v,
        combats,
        combatStates,
        decisionMakers,
        directives,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code combats}。 */
  public SdState withCombats(Map<CombatId, Combat> v) {
    return new SdState(
        nations,
        armies,
        v,
        combatStates,
        decisionMakers,
        directives,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code combatStates}。 */
  public SdState withCombatStates(Map<CombatStateId, CombatState> v) {
    return new SdState(
        nations,
        armies,
        combats,
        v,
        decisionMakers,
        directives,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code decisionMakers}。 */
  public SdState withDecisionMakers(Map<DecisionMakerId, DecisionMaker> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        v,
        directives,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code directives}。 */
  public SdState withDirectives(Map<DirectiveId, Directive> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        v,
        effects,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code effects}。 */
  public SdState withEffects(Map<EffectId, Effect> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        directives,
        v,
        verdicts,
        lossRecords,
        info);
  }

  /** 仅替换 {@code verdicts}。 */
  public SdState withVerdicts(Map<VerdictId, Verdict> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        directives,
        effects,
        v,
        lossRecords,
        info);
  }

  /** 仅替换 {@code lossRecords}。 */
  public SdState withLossRecords(Map<LossRecordId, LossRecord> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        directives,
        effects,
        verdicts,
        v,
        info);
  }

  /** 仅替换 {@code info}（canonical 地址串 → 条目列表）。 */
  public SdState withInfo(Map<String, List<SdInfoEntry>> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        directives,
        effects,
        verdicts,
        lossRecords,
        v);
  }

  // ── 构造期不变量（逐条配故意违规用例）────────────────────────────────────────────

  /**
   * 不变量 1（R4 的**末位生效**形态，2026-09-23 用户裁定）：同一 ({@code decisionMakerId}, {@code tick}) 下**至多一条生效**
   * （{@code PLANNED}/{@code ISSUED}）。
   *
   * <p>★★ **为什么不是"唯一"**：用户裁定「同一次决策，不管有多少细条目，都只能放在一段文本里；但是又没说**不可以打回重写**」—— 重写 = 产生新的一版（新的 {@link
   * DirectiveId}），旧的转 {@link DirectiveStatus#SUPERSEDED}（终态，留痕但不再生效）。故同一 ({@code decisionMakerId},
   * {@code tick}) **允许多条 Directive 并存**，只是**生效的至多一条**。
   *
   * <p>★ 这条不变量把"末位生效"从命令期的实现细节升级成**状态层的结构性事实**：任何绕过 {@code IssueDirectiveHandler}
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
   * 不变量 2：外键必须存在于同快照。
   *
   * <p>★ 阶段 12：Army 去 {@code NationId} 后不再有 {@code nations} 外键可查；{@code masterGovUnitId} 指向的是
   * <b>unit 切片</b>里的 GOV 单位（跨片），存在性由命令期（{@code sd.CreateArmy} / {@code unit.SetArmyFormation}）判， sd
   * 快照构造期不做跨片查询（铁律 3：sd 只拥有 sd 数据）。
   */
  private static void requireReferentialIntegrity(
      Map<CombatId, Combat> combats,
      Map<CombatStateId, CombatState> combatStates,
      Map<DecisionMakerId, DecisionMaker> decisionMakers,
      Map<DirectiveId, Directive> directives,
      Map<EffectId, Effect> effects,
      Map<VerdictId, Verdict> verdicts,
      Map<LossRecordId, LossRecord> lossRecords) {
    for (Directive directive : directives.values()) {
      if (!decisionMakers.containsKey(directive.decisionMakerId())) {
        throw new IllegalArgumentException(
            "引用完整性：Directive "
                + directive.id().value()
                + " 的 decisionMakerId "
                + directive.decisionMakerId().value()
                + " 不存在");
      }
      for (EffectId effectId : directive.effects()) {
        if (!effects.containsKey(effectId)) {
          throw new IllegalArgumentException(
              "引用完整性：Directive " + directive.id().value() + " 的效果引用 " + effectId + " 不存在");
        }
      }
      if (directive.verdict().isPresent() && !verdicts.containsKey(directive.verdict().get())) {
        throw new IllegalArgumentException(
            "引用完整性：Directive "
                + directive.id().value()
                + " 的判决 "
                + directive.verdict().get()
                + " 不存在");
      }
    }
    for (CombatState state : combatStates.values()) {
      Combat combat = combats.get(state.combatId());
      if (combat == null) {
        throw new IllegalArgumentException(
            "引用完整性：CombatState "
                + state.id().value()
                + " 的 combatId "
                + state.combatId().value()
                + " 不存在");
      }
      if (stageOf(combat, state.currentStage()) == null) {
        throw new IllegalArgumentException(
            "引用完整性：CombatState "
                + state.id().value()
                + " 的 currentStage "
                + state.currentStage()
                + " 不在 Combat "
                + combat.id().value()
                + " 的阶段链里");
      }
      for (LossRecordId lossId : state.losses()) {
        if (!lossRecords.containsKey(lossId)) {
          throw new IllegalArgumentException(
              "引用完整性：CombatState " + state.id().value() + " 的损失记录 " + lossId + " 不存在");
        }
      }
    }
    for (LossRecord record : lossRecords.values()) {
      Combat combat = combats.get(record.combat());
      if (combat == null) {
        throw new IllegalArgumentException(
            "引用完整性：LossRecord "
                + record.id().value()
                + " 的 combat "
                + record.combat().value()
                + " 不存在");
      }
      if (stageOf(combat, record.stage()) == null) {
        throw new IllegalArgumentException(
            "引用完整性：LossRecord "
                + record.id().value()
                + " 的 stage "
                + record.stage()
                + " 不在该 Combat 的阶段链里");
      }
    }
  }

  /** 不变量 3：任一 {@code CombatState.selectedOutcome} 必须是其 {@code Combat} 的某阶段 outcomeTable 里的条目。 */
  private static void requireOutcomeConsistency(
      Map<CombatId, Combat> combats, Map<CombatStateId, CombatState> combatStates) {
    for (CombatState state : combatStates.values()) {
      if (state.selectedOutcome().isEmpty()) {
        continue;
      }
      Combat combat = combats.get(state.combatId());
      if (combat == null || !containsOutcome(combat, state.selectedOutcome().get())) {
        throw new IllegalArgumentException(
            "结局一致性：CombatState "
                + state.id().value()
                + " 选定的结局 "
                + state.selectedOutcome().get()
                + " 不在其 Combat 的任一阶段 outcomeTable 里");
      }
    }
  }

  /** 不变量 4：{@code Combat} 的阶段链满足"上一阶段 exit == 下一阶段 entry"（N1）。 */
  private static void requireStageChains(Map<CombatId, Combat> combats) {
    for (Combat combat : combats.values()) {
      List<CombatStage> stages = combat.stages();
      for (int i = 0; i + 1 < stages.size(); i++) {
        if (!stages.get(i).exit().equals(stages.get(i + 1).entry())) {
          throw new IllegalArgumentException(
              "阶段链断裂：Combat "
                  + combat.id().value()
                  + " 的 "
                  + stages.get(i).id()
                  + ".exit 与 "
                  + stages.get(i + 1).id()
                  + ".entry 不等");
        }
      }
    }
  }

  /**
   * 不变量 5（可判子集）：损失记录自洽 —— 每条记录的 {@code deltas} 非空、且 CombatState 引用的损失记录必须同属一个 Combat。
   *
   * <p>★ **诚实边界**：spec §三.1.5 的"|Δ| ≤ 记录时的当前值"**在 {@code SdState} 构造期不可判** —— sd 不拥有 unit 强度（铁律
   * 3），且 {@code LossRecord} 未携带"记录时的当前值"（spec §三.4 的形状）。该上界真正的落点是**命令期**（C3：从 unit 切片读当前值）。
   * 本条落地的是构造期可判的那部分，记入台账取代说明。
   */
  private static void requireLossConsistency(
      Map<CombatStateId, CombatState> combatStates, Map<LossRecordId, LossRecord> lossRecords) {
    for (LossRecord record : lossRecords.values()) {
      if (record.deltas().isEmpty()) {
        throw new IllegalArgumentException("损失记录 " + record.id().value() + " 的 deltas 不得为空");
      }
    }
    for (CombatState state : combatStates.values()) {
      for (LossRecordId lossId : state.losses()) {
        LossRecord record = lossRecords.get(lossId);
        if (record != null && !record.combat().equals(state.combatId())) {
          throw new IllegalArgumentException(
              "损失记录归属：CombatState "
                  + state.id().value()
                  + " 引用的损失记录 "
                  + lossId
                  + " 属于另一个 Combat（"
                  + record.combat().value()
                  + "）");
        }
      }
    }
  }

  private static boolean containsOutcome(Combat combat, CombatOutcomeId outcome) {
    for (CombatStage stage : combat.stages()) {
      for (OutcomeOption option : stage.outcomes().options()) {
        if (option.id().equals(outcome)) {
          return true;
        }
      }
    }
    return false;
  }

  private static CombatStage stageOf(Combat combat, CombatStageId stageId) {
    for (CombatStage stage : combat.stages()) {
      if (stage.id().equals(stageId)) {
        return stage;
      }
    }
    return null;
  }

  private static <K, V> Map<K, V> copyOf(Map<K, V> source, String name) {
    if (source == null) {
      throw new IllegalArgumentException(name + " 不得为 null");
    }
    Map<K, V> copy = new LinkedHashMap<>();
    for (Map.Entry<K, V> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(name + " 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  private static Map<String, List<SdInfoEntry>> copyInfo(Map<String, List<SdInfoEntry>> source) {
    if (source == null) {
      throw new IllegalArgumentException("info 不得为 null");
    }
    Map<String, List<SdInfoEntry>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<SdInfoEntry>> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("info 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return copy;
  }
}
