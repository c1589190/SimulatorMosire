package io.mosire.simos.sd.state;

import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Effect;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sd 模块的完整状态树（spec §三.1，铁律 5 的"完整状态类型"）。
 *
 * <p>★ **树与切片分工照 map 的 {@code GameMap}（树）/ {@code MapSnapshot}（切片）**：本类是树，落盘切片是 {@link
 * SdSnapshot}。spec §三.1 把 {@code implements Snapshot} 写在了状态树头（设计形状的笔误），执行期按"树 / 切片分离"落地 ——
 * 记入台账取代说明。
 *
 * <p>★★ <b>本树的收缩史（六次，逐批如实记）</b>：14 →（批次 3a）12 —— {@code decisionPackets} / {@code
 * mergedEffectPlans} 整表搬进 {@code simos-ga}；12 →（批次 3b-1）10 —— {@code directives} / {@code verdicts}
 * 同样搬走；10 →（批次 3b-2）8 —— {@code decisionMakers} 与 {@code info} 整表搬进 {@code GaState}；8 →（3c）5 ——
 * 外交三表（{@code nations} / {@code diplomaticRelations} / {@code diplomaticEvents}）连同四个 handler 搬进
 * {@code simos-gov}（用户裁定「<b>外交是 gov 的事</b>」＋「<b>NationId 落点 → GOV 模块</b>」）；5 →（3d-1）4 —— {@code
 * armies}（<b>军队身份</b>）连同 {@code Army} / {@code ArmyId} 两个类型与两条命令搬进 {@code simos-army}；<b>4 →（本批
 * 3d-2）1</b> —— 旧战斗族三张表（{@code combats} / {@code combatStates} / {@code lossRecords}）
 * <b>整族退役</b>（用户裁定「<b>战斗模块，包括阶段之类的，归 Army 模块管</b>」＋「<b>sd 整个弃用！</b>」＋ D-012 「交战的具体记录移到 Army
 * 模块记录与处理」＋「旧 sd 战斗命令族不再作为交战记录的载体」）。
 *
 * <p>★★ <b>本批是「退役」不是「搬迁」（如实记）</b>：这三张表<b>没有</b>搬进 {@code simos-army} —— army 自阶段 D1
 * 起就<b>已有</b>自己的交战记录模型（{@code ArmyData.combats: Map<CombatRecordId, CombatRecord>} + {@code
 * RecordCombat} / {@code AppendCombatStage} / {@code ResolveCombatStage} 三条命令 + {@code
 * CombatResolution} 的投骰与复核 + {@code army:combat.<id>} 地址族）。旧的 sd 战斗族<b>没有随机源</b> （{@code
 * OutcomeOption.weight} 全仓零消费者、结局靠调用方显式指定），与 D-010「Army = 流程编排 / 概率与随机化层」相冲 ⇒ 留着只会是第二本交战账。故按
 * §一.11「<b>旧设计和数据类型直接重建不用留，删就删了</b>」整族删除，<b>不写兼容、不留旧档读取</b>。
 *
 * <p>★ <b>值类型仍住本模块</b>（{@code DecisionMaker} / {@code SdInfoEntry} 及其 id、{@code Directive} / {@code
 * Verdict}、{@code Effect} 等）：它们仍被 sd 的其余组件与 sd 的测试引用，而 {@code sd} 不得依赖 {@code ga} / {@code gov} /
 * {@code army}（成环）⇒ 反向引用是既有的 {@code ga → sd} 方向。★ <b>三处例外（逐个批次）</b>： {@code NationId} / {@code
 * Nation} / 外交关系与事件（3c）、{@code Army} / {@code ArmyId}（3d-1）、旧的 {@code Combat} / {@code CombatState}
 * / {@code LossRecord} 一族（本批 3d-2）<b>不是</b>值类型层面的"留 sd"。
 *
 * <p>★ <b>1 个组件与 {@link io.mosire.simos.sd.change.SdChangeSet} 的 1 个组件一一对应</b>（铁律
 * 5）：任何新增组件都要同时进变更集， 由 {@code SdRoundTripTest} 的反射枚举把守。<b>本批 4 → 1 的同步点见 {@code SdChangeSet} /
 * {@code SdCodec} / {@code SdResolver} / {@code SdSnapshot} 几处的类注。</b>
 *
 * <p>★ <b>{@code effects} 表保序不可变</b>（{@code LinkedHashMap} + {@code
 * Collections.unmodifiableMap}，<b>禁用</b> {@code Map.copyOf} ——迭代序不是内容的纯函数，M2 Task 5
 * 实测）。冻结那一步<b>写在赋值处</b>（SpotBugs 的 {@code EI_EXPOSE_REP} 只认它看得见的包装）。
 *
 * <p>★ <b>随三张表一起消失的构造期不变量（如实记）</b>：① {@code CombatState} 的 {@code combatId} / {@code currentStage}
 * / {@code losses} 外键；② 结局一致性（{@code selectedOutcome} ∈ 某阶段 outcomeTable）；③ 阶段链 （{@code
 * stages[i].exit == stages[i+1].entry}）；④ 损失记录自洽（{@code deltas} 非空、归属同一交战）。四条都只依赖已退役的三张表 ⇒
 * 与其一起退役，<b>不是</b>搬到 army —— army 的等价守卫落在它自己的类型上（{@code CombatRecord} 的阶段 id 判重 / {@code
 * CombatStage} 的 {@code selectedOutcomeId} ∈ 本阶段 outcomes 等）。
 *
 * <p>★ <b>{@code sd} 侧仍有读 ga 三张表的地方</b>（{@code SdResolver} / {@code SdTimeParticipant}）：走注入的 {@link
 * io.mosire.simos.sd.spi.DecisionTablesLookup}（实现在 ga、组合根接线），见该接口的类注。
 */
public record SdState(Map<EffectId, Effect> effects) {

  public SdState {
    effects = Collections.unmodifiableMap(copyOf(effects, "effects")); // ★ 冻在赋值处
  }

  /** 往返用例与 handlers 的起点。 */
  public static SdState empty() {
    return new SdState(Map.of());
  }

  // ── 逐组件替换（一个组件一个 with，照 GameMap 的形制）────────────────────────────────

  /** 仅替换 {@code effects}。 */
  public SdState withEffects(Map<EffectId, Effect> v) {
    return new SdState(v);
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
