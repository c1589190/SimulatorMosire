package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SetArmyPayPolicy} 命令的处理器（P4b，2026-10-15）：给带 {@link io.mosire.simos.unit.ArmyFormation}
 * 的单位整体设置军俸政策。
 *
 * <pre>{@code
 * {"unitId":"army-1","periodDays":3,"phaseDay":0,"startsOnDay":0,"expiresOnDay":null,
 *  "grainPerHouseholdPerCycle":{"hh-unit:army-1":300},
 *  "clothPerHouseholdPerCycle":{},
 *  "moneyPerHouseholdPerCycle":{"hh-unit:army-1":50}}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>（P4b 计划 §2.3）：{@code unitId} 必填；{@code periodDays}/{@code phaseDay}/{@code
 * startsOnDay} 必填；{@code expiresOnDay} 缺失或 {@code null} = 永久；三张逐家户表的缺省表 = 空表，<b>三表全空 =
 * {@link MilitaryPayPolicy#disabled()}（允许，表示停发）</b>。可选 {@code enabled} 只用于显式边界：{@code enabled=true} +
 * 三表全空 ⇒ 具名拒（要发就至少一条腿）；{@code enabled=false} + 非空腿 ⇒ 具名拒。同类型重复设置 = 整体替换 policy。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitPayloads} / {@link MilitaryPayPolicy} / {@link UnitOperations#setArmyPayPolicy}
 * / {@link UnitState} 给出）：单位不存在；单位不带 {@code ArmyFormation}；排期/逐值/至少一腿/家户键 ⊆
 * {@code Unit.households} 任一不成立。命令边界一律折成 {@code HandlerOutcome.Rejected}（零 revision）。
 *
 * <p>★ <b>非 GmOnly</b>：unit 域日常政策命令，未来决策人可嵌令（P4c 的工具/白名单另批）；命令只写 unit 命名空间。
 */
public final class SetArmyPayPolicyHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、catalog 提示与烟测都从这里取/对齐）。 */
  public static final String TYPE = "unit.SetArmyPayPolicy";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      MilitaryPayPolicy policy = UnitPayloads.requireMilitaryPayPolicy(payload);
      UnitState next = UnitOperations.setArmyPayPolicy(snapshot.state(), id, policy);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
