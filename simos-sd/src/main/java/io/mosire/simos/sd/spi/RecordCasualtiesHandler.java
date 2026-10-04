package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * {@code sd.RecordCasualties} 命令的处理器（spec §四，C3）：战损以 **delta（事件）** 记入 {@link
 * LossRecord}，人员/装备**双轨**。
 *
 * <pre>{@code
 * {"combatId":"c1","stageId":"s1",
 *  "deltas":[{"unit":"u-1","personnel":-10,"equipment":{"步枪":-5},"lossClass":"PERMANENT"}]}
 * }</pre>
 *
 * <p>★ **上界由代码判**（N3 / §八.4，绝不交给 AI）：{@code |Δ| ≤ 当前值}，当前值从 **unit 切片**读（只读，铁律 3）。越界**命令期拒绝**，
 * 不靠事后。
 *
 * <p>★ {@code RECOVERABLE} v1 **只记类别、不设回池**（spec §〇.3）。{@code atRevision} 落 revision ⇒ 可回放且逐值相等。
 */
public final class RecordCasualtiesHandler implements CommandHandler {

  private static final Logger LOG = SdLog.combat();

  @Override
  public String type() {
    return "sd.RecordCasualties";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    UnitState units = SdSnapshots.units(state);
    // ★ S3b：人员上界的唯一来源 = 该 unit 的家户人口现算（不再有 Unit.manpower 第二本账）。
    io.mosire.simos.social.SocialData social = SdSnapshots.social(state);
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      CombatStageId stageId = CombatStageId.parse(SdPayloads.requireText(payload, "stageId"));
      List<CasualtyDelta> deltas = SdPayloads.requireDeltas(payload, "deltas");
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return new HandlerOutcome.Rejected("交战不存在: " + combatId);
      }
      if (SdCombats.stageOrNull(combat, stageId) == null) {
        return new HandlerOutcome.Rejected("阶段不存在: " + stageId);
      }
      for (CasualtyDelta delta : deltas) {
        Unit unit = units.units().get(delta.unit());
        if (unit == null) {
          return new HandlerOutcome.Rejected("单位不存在: " + delta.unit());
        }
        requireWithinBound(delta, unit, social);
      }
      RevisionId atRevision = state.meta().ref().revision();
      LossRecordId recordId = new LossRecordId(combatId.value() + ":" + atRevision.value());
      if (base.lossRecords().containsKey(recordId)) {
        return new HandlerOutcome.Rejected("损失记录已存在: " + recordId);
      }
      LossRecord record = new LossRecord(recordId, combatId, stageId, atRevision, deltas);
      Map<LossRecordId, LossRecord> nextRecords = new LinkedHashMap<>(base.lossRecords());
      nextRecords.put(recordId, record);

      CombatState combatState = SdCombats.stateOrNull(base, combatId);
      Map<CombatStateId, CombatState> nextStates = new LinkedHashMap<>(base.combatStates());
      if (combatState != null) {
        Set<LossRecordId> losses = new LinkedHashSet<>(combatState.losses());
        losses.add(recordId);
        nextStates.put(
            combatState.id(),
            new CombatState(
                combatState.id(),
                combatState.combatId(),
                combatState.currentStage(),
                combatState.hex(),
                combatState.participants(),
                combatState.selectedOutcome(),
                losses));
      }
      SdState next = base.withLossRecords(nextRecords);
      if (combatState != null) {
        next = next.withCombatStates(nextStates);
      }
      LOG.info(
          "event=SD_CASUALTIES_RECORDED record={} combat={} stage={} deltas={} stateLinked={}",
          recordId.value(),
          combatId.value(),
          stageId.value(),
          deltas.size(),
          combatState != null);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 上界校验（N3 / P14）：人员按**人力合计**判（sd 这代战损记录仍是单一 {@code personnel}，不区分人力类型），装备按 {@link
   * Unit#equipment()} 的 type 逐项判。
   *
   * <p>★ D3a 适配（2026-10-02）：Unit 的 {@code equipment} 从 Map 改为有序条目列表 ⇒ 这里从"按键取值"改成"按 type 线性查"； sd
   * 的损失记录模型与命令载荷形状本阶段**不动**（D-012：旧 sd 战斗命令族清理另批），故 {@code personnel} 仍只报一个总量。
   */
  private static void requireWithinBound(
      CasualtyDelta delta, Unit unit, io.mosire.simos.social.SocialData social) {
    // ★ S3b：人员上界 = 该 unit 容纳家户的成员人口现算（Unit.manpower 已退役，不再有第二本 headcount）。
    long population = social.unitPopulation(unit.id().value());
    if (delta.personnel() < -population) {
      throw new IllegalArgumentException(
          "人员战损超出家户人口: " + population + " + (" + delta.personnel() + ")（S3b：人数唯一来源是 Social 家户）");
    }
    for (Map.Entry<String, Integer> entry : delta.equipment().entrySet()) {
      Long current = null;
      for (CompositionEntry equip : unit.equipment()) {
        if (equip.type().equals(entry.getKey())) {
          current = equip.amount();
          break;
        }
      }
      if (current == null) {
        throw new IllegalArgumentException("未知装备类型: " + entry.getKey());
      }
      if (entry.getValue() < -current) {
        throw new IllegalArgumentException(
            "装备战损超出当前值: " + entry.getKey() + "=" + current + " + (" + entry.getValue() + ")");
      }
    }
  }
}
