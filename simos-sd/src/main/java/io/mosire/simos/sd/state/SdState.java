package io.mosire.simos.sd.state;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.SdInfoEntry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sd 模块的完整状态树（spec §三.1，铁律 5 的"完整状态类型"）。
 *
 * <p>★ **树与切片分工照 map 的 {@code GameMap}（树）/ {@code MapSnapshot}（切片）**：本类是树，落盘切片是 {@link
 * SdSnapshot}。spec §三.1 把 {@code implements Snapshot} 写在了状态树头（设计形状的笔误），执行期按"树 / 切片分离"落地 ——
 * 记入台账取代说明。
 *
 * <p>★★ <b>2026-10-12 批次 3a：本树由 14 个组件收为 12 个</b>——{@code decisionPackets}（一决策人 × 一 tick 一个包）与
 * {@code mergedEffectPlans}（合并效果集）<b>整表搬进 {@code simos-ga}</b>（{@code GaState} + {@code GaCodec}，
 * 命名空间 {@code "ga"}）。<b>本批（3b′）：再收为 10 个</b>——{@code directives}（决策记录）与 {@code verdicts}（判决） 同样整表搬进
 * {@code simos-ga}（{@code GaState} 的第 3/4 组件），相应的三条命令改名 {@code sd.* → ga.*}、handler 随表迁入 {@code
 * simos-ga}。
 *
 * <p>★★ <b>随表搬走的两条构造期不变量（不是删除，是换家）</b>：① "同一 (决策人, tick) 至多一条生效令"（R4 末位生效）；② "{@code
 * Directive.verdict()} 指向的判决必须存在"。两条都只依赖那两张表 ⇒ 整条搬进 {@link io.mosire.simos.ga.GaState}
 * 的构造期。<b>收窄的那条（如实记）</b>：{@code Directive} 对 {@code decisionMakers} / {@code effects} 的外键此后是
 * <b>跨切片</b>的（铁律 3：ga 不拥有这两张表）⇒ 状态期判不了，改由 <b>命令期</b>判（{@code ga.IssueDirective} 逐条校验决策人与效果存在）。
 * 读取这两张表的两处 sd 侧调用点（{@code SdResolver} 的 {@code sd:directive.<id>}、{@code
 * DeleteDecisionMakerHandler} 的 "不级联"守卫）经注入的 {@link io.mosire.simos.sd.spi.DirectiveLookup} 读 ga
 * 切片（sd 不得依赖 ga ⇒ 端口注入）。
 *
 * <p>★ <b>值类型仍住本模块</b>（{@code Directive} / {@code Verdict} 及其 id、{@code DecisionPacket} / {@code
 * MergedEffectPlan} 及其 id）：它们仍被 sd 的其余组件与 sd 的测试引用，而 {@code sd} 不得依赖 {@code ga}（成环）⇒ 反向引用是既有的
 * {@code ga → sd} 方向。
 *
 * <p>★ **10 个组件与 {@link io.mosire.simos.sd.change.SdChangeSet} 的 10 个组件一一对应**（铁律 5）：任何新增组件都要同时进变更集，
 * 由 {@code SdRoundTripTest} 的反射枚举把守。★ D5（2026-10-02 / R6）新增两个：{@code diplomaticRelations}（D-003
 * 有向边） 与 {@code diplomaticEvents}（D-005 多国谈判逐 tick 记录）；★★ D2（2026-10-22 决策包计划）曾新增的两个、以及 {@code
 * directives}/{@code verdicts} 两个，已分别随批次 3a / 3b′ 搬走（见上）。
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
    Map<EffectId, Effect> effects,
    Map<LossRecordId, LossRecord> lossRecords,
    Map<String, List<SdInfoEntry>> info,
    Map<DiplomaticRelationKey, DiplomaticRelation> diplomaticRelations,
    Map<DiplomaticEventId, DiplomaticEvent> diplomaticEvents) {

  public SdState {
    nations = Collections.unmodifiableMap(copyOf(nations, "nations"));
    armies = Collections.unmodifiableMap(copyOf(armies, "armies"));
    combats = Collections.unmodifiableMap(copyOf(combats, "combats"));
    combatStates = Collections.unmodifiableMap(copyOf(combatStates, "combatStates"));
    decisionMakers = Collections.unmodifiableMap(copyOf(decisionMakers, "decisionMakers"));
    effects = Collections.unmodifiableMap(copyOf(effects, "effects"));
    lossRecords = Collections.unmodifiableMap(copyOf(lossRecords, "lossRecords"));
    info = Collections.unmodifiableMap(copyInfo(info));
    // ★ 老档兼容（D5 新增的两个组件之前落盘的快照没有这两个键）：Jackson 对缺失的 Map 绑 null
    //   ⇒ 缺省 = 空表（不是抛——抛了等于"整个世界打不开"，与 SdInfoEntry 的新字段同一条口径）。
    if (diplomaticRelations == null) {
      diplomaticRelations = Map.of();
    }
    if (diplomaticEvents == null) {
      diplomaticEvents = Map.of();
    }
    diplomaticRelations =
        Collections.unmodifiableMap(copyOf(diplomaticRelations, "diplomaticRelations"));
    diplomaticEvents = Collections.unmodifiableMap(copyOf(diplomaticEvents, "diplomaticEvents"));
    requireReferentialIntegrity(combats, combatStates, lossRecords);
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
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code armies}。 */
  public SdState withArmies(Map<ArmyId, Army> v) {
    return new SdState(
        nations,
        v,
        combats,
        combatStates,
        decisionMakers,
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code combats}。 */
  public SdState withCombats(Map<CombatId, Combat> v) {
    return new SdState(
        nations,
        armies,
        v,
        combatStates,
        decisionMakers,
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code combatStates}。 */
  public SdState withCombatStates(Map<CombatStateId, CombatState> v) {
    return new SdState(
        nations,
        armies,
        combats,
        v,
        decisionMakers,
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code decisionMakers}。 */
  public SdState withDecisionMakers(Map<DecisionMakerId, DecisionMaker> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        v,
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code effects}。 */
  public SdState withEffects(Map<EffectId, Effect> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        v,
        lossRecords,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code lossRecords}。 */
  public SdState withLossRecords(Map<LossRecordId, LossRecord> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        effects,
        v,
        info,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code info}（canonical 地址串 → 条目列表）。 */
  public SdState withInfo(Map<String, List<SdInfoEntry>> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        effects,
        lossRecords,
        v,
        diplomaticRelations,
        diplomaticEvents);
  }

  /** 仅替换 {@code diplomaticRelations}（D-003 有向边表；键 = (from,to)）。 */
  public SdState withDiplomaticRelations(Map<DiplomaticRelationKey, DiplomaticRelation> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        effects,
        lossRecords,
        info,
        v,
        diplomaticEvents);
  }

  /** 仅替换 {@code diplomaticEvents}（D-005 多国谈判/外交事件记录）。 */
  public SdState withDiplomaticEvents(Map<DiplomaticEventId, DiplomaticEvent> v) {
    return new SdState(
        nations,
        armies,
        combats,
        combatStates,
        decisionMakers,
        effects,
        lossRecords,
        info,
        diplomaticRelations,
        v);
  }

  // ── 构造期不变量（逐条配故意违规用例）────────────────────────────────────────────

  /**
   * 不变量：外键必须存在于同快照。
   *
   * <p>★ 阶段 12：Army 去 {@code NationId} 后不再有 {@code nations} 外键可查；{@code masterGovUnitId} 指向的是
   * <b>unit 切片</b>里的 GOV 单位（跨片），存在性由命令期（{@code sd.CreateArmy} / {@code unit.SetArmyFormation}）判， sd
   * 快照构造期不做跨片查询（铁律 3：sd 只拥有 sd 数据）。
   *
   * <p>★★ <b>2026-10-12 批次 3b′ 收窄（如实记）</b>：原来这里还判 {@code Directive} 的三条外键（{@code decisionMakerId} /
   * {@code effects} / {@code verdict}），{@code directives} 表搬进 {@code simos-ga}
   * 后——前两条成了<b>跨切片</b>（ga 不拥有 {@code decisionMakers} / {@code effects}）⇒ 状态期判不了，落点改在命令期（{@code
   * ga.IssueDirective}）；第三条整条搬进 {@code GaState} 的构造期（两张表都在那边）。
   */
  private static void requireReferentialIntegrity(
      Map<CombatId, Combat> combats,
      Map<CombatStateId, CombatState> combatStates,
      Map<LossRecordId, LossRecord> lossRecords) {
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
