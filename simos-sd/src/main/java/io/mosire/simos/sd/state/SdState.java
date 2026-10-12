package io.mosire.simos.sd.state;

import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.OutcomeOption;
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
 * <p>★★ <b>本树的收缩史（五次，逐批如实记）</b>：14 →（批次 3a）12 —— {@code decisionPackets} / {@code
 * mergedEffectPlans} 整表搬进 {@code simos-ga}；12 →（批次 3b-1）10 —— {@code directives} / {@code verdicts}
 * 同样搬走；10 →（批次 3b-2）8 —— {@code decisionMakers} 与 {@code info} 整表搬进 {@code GaState}；8 →（3c）5 ——
 * 外交三表（{@code nations} / {@code diplomaticRelations} / {@code diplomaticEvents}）连同四个 handler 搬进
 * {@code simos-gov}（用户裁定「<b>外交是 gov 的事</b>」＋「<b>NationId 落点 → GOV 模块</b>」）；<b>5 →（本批 3d-1）4</b> ——
 * {@code armies}（<b>军队身份</b>）连同 {@code Army} / {@code ArmyId} 两个类型与两条命令 （{@code army.CreateArmy} /
 * {@code army.SetArmyMasterGov}）搬进 {@code simos-army}（用户裁定「<b>战斗模块，包括阶段 之类的，归 Army 模块管</b>」＋「<b>sd
 * 整个弃用！</b>」）。 ⇒ 决策族的七张表里只剩 {@code effects} 仍住本模块（它要开 {@code ga → unit + map} 两条边，顺位在 3b-4）；战斗族四张表里
 * {@code combats} / {@code combatStates} / {@code lossRecords} 三张仍在（属 <b>3d-2</b>）。
 *
 * <p>★★ <b>随表搬走的构造期不变量（不是删除，是换家）</b>：① "同一 (决策人, tick) 至多一条生效令"（R4 末位生效）；② "{@code
 * Directive.verdict()} 指向的判决必须存在"；③ "{@code Directive.decisionMakerId} 指向的决策人必须存在" ——三条都只依赖已搬走的那几张表
 * ⇒ 整条住 {@link io.mosire.simos.ga.GaState} 的构造期。<b>仍跨切片的那一条（如实记）</b>：{@code Directive.effects()}
 * 指向的 {@code effects} 仍住本模块 ⇒ 状态期判不了，落点保持在<b>命令期</b>（{@code ga.IssueDirective} 逐条校验）。★ {@code
 * armies} 表在 sd 时代<b>本就没有构造期跨表不变量</b>（sd 只判键/值非 null），故本批没有不变量随之外迁；它的"键必须等于值内 id"守卫落在 {@link
 * io.mosire.simos.army.ArmyData} 的构造期。
 *
 * <p>★ <b>值类型仍住本模块</b>（{@code DecisionMaker} / {@code SdInfoEntry} 及其 id、{@code Directive} / {@code
 * Verdict}、{@code Combat} / {@code CombatState} / {@code LossRecord} 等）：它们仍被 sd 的其余组件与 sd 的测试引用，而
 * {@code sd} 不得依赖 {@code ga} / {@code gov} / {@code army}（成环）⇒ 反向引用是既有的 {@code ga → sd} 方向。★
 * <b>两处例外（逐个批次）</b>：{@code NationId} / {@code Nation} / 外交关系与事件（3c）与 {@code Army} / {@code
 * ArmyId}（本批）<b>不是</b>值类型层面的"留 sd"，而是<b>整体搬走</b>——因为 {@code Affiliation.Nation} 与 {@code
 * Affiliation.Army} 都已收窄为不透明引用（见那两个类型的类注）。
 *
 * <p>★ <b>4 个组件与 {@link io.mosire.simos.sd.change.SdChangeSet} 的 4 个组件一一对应</b>（铁律
 * 5）：任何新增组件都要同时进变更集， 由 {@code SdRoundTripTest} 的反射枚举把守。<b>本批 5 → 4 的同步点见 {@code SdChangeSet} /
 * {@code SdCodec} / {@code SdResolver} / {@code SdSnapshot} 几处的类注。</b>
 *
 * <p>★ **三张表的键都保序不可变**（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**禁用** {@code
 * Map.copyOf} ——迭代序不是内容的纯函数，M2 Task 5 实测）。冻结那一步**写在赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 只认它看得见的包装）。
 *
 * <p>★ <b>{@code sd} 侧仍有读 ga 三张表的地方</b>（{@code SdResolver} / {@code SdTimeParticipant}）：走注入的 {@link
 * io.mosire.simos.sd.spi.DecisionTablesLookup}（实现在 ga、组合根接线），见该接口的类注。
 */
public record SdState(
    Map<CombatId, Combat> combats,
    Map<CombatStateId, CombatState> combatStates,
    Map<EffectId, Effect> effects,
    Map<LossRecordId, LossRecord> lossRecords) {

  public SdState {
    combats = Collections.unmodifiableMap(copyOf(combats, "combats"));
    combatStates = Collections.unmodifiableMap(copyOf(combatStates, "combatStates"));
    effects = Collections.unmodifiableMap(copyOf(effects, "effects"));
    lossRecords = Collections.unmodifiableMap(copyOf(lossRecords, "lossRecords"));
    requireReferentialIntegrity(combats, combatStates, lossRecords);
    requireOutcomeConsistency(combats, combatStates);
    requireStageChains(combats);
    requireLossConsistency(combatStates, lossRecords);
  }

  /** 往返用例与 handlers 的起点。 */
  public static SdState empty() {
    return new SdState(Map.of(), Map.of(), Map.of(), Map.of());
  }

  // ── 逐组件替换（一个组件一个 with，照 GameMap 的形制）────────────────────────────────

  /** 仅替换 {@code combats}，其余 3 个组件原样。 */
  public SdState withCombats(Map<CombatId, Combat> v) {
    return new SdState(v, combatStates, effects, lossRecords);
  }

  /** 仅替换 {@code combatStates}。 */
  public SdState withCombatStates(Map<CombatStateId, CombatState> v) {
    return new SdState(combats, v, effects, lossRecords);
  }

  /** 仅替换 {@code effects}。 */
  public SdState withEffects(Map<EffectId, Effect> v) {
    return new SdState(combats, combatStates, v, lossRecords);
  }

  /** 仅替换 {@code lossRecords}。 */
  public SdState withLossRecords(Map<LossRecordId, LossRecord> v) {
    return new SdState(combats, combatStates, effects, v);
  }

  // ── 构造期不变量（逐条配故意违规用例）────────────────────────────────────────────

  /**
   * 不变量：外键必须存在于同快照。
   *
   * <p>★ 阶段 12：Army 去 {@code NationId} 后不再有 {@code nations} 外键可查；{@code masterGovUnitId} 指向的是
   * <b>unit 切片</b>里的 GOV 单位（跨片），存在性由命令期（{@code army.CreateArmy} / {@code unit.SetArmyFormation}）判，
   * 军队快照构造期不做跨片查询（铁律 3：谁的数据谁拥有）。
   *
   * <p>★★ <b>批次 3b-1 / 3b-2 / 3c 的收窄（如实记）</b>：原来这里还判 {@code Directive} 的三条外键（{@code
   * decisionMakerId} / {@code effects} / {@code verdict}）。{@code directives} 表搬进 {@code simos-ga}
   * 后：{@code decisionMakerId} 那条随 {@code decisionMakers} 在 3b-2 也搬进 ga ⇒ 回归 {@link
   * io.mosire.simos.ga.GaState} 的构造期；{@code verdict} 那条早在 3b-1 就整条搬过去；<b>只剩 {@code effects} 一条</b>因
   * {@code effects} 仍住本模块而成为跨切片外键 ⇒ 状态期判不了， 落点在命令期（{@code ga.IssueDirective}）。
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
}
