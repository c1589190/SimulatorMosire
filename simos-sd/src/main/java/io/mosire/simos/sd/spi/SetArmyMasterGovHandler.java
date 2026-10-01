package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.SetArmyMasterGov} 命令的处理器：给已存在的 Army **改派 / 解除认领 GOV**（阶段 12 的后续赋值操作缺口）。
 *
 * <pre>{@code
 * {"armyId":"a1","masterGovUnitId":"g1"}   // 改派
 * {"armyId":"a1"}                          // 解除认领（键缺席）
 * {"armyId":"a1","masterGovUnitId":null}   // 解除认领（显式 null）
 * {"armyId":"a1","masterGovUnitId":""}     // 解除认领（空串）
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code masterGovUnitId} 缺席 / {@code null} / 空串（含空白串）= 解除认领；给了非空白值必须存在、 且带 {@link
 * GovFormation}（与 {@code sd.CreateArmy} / {@code unit.SetArmyFormation} 同口径，认主子只认 GOV）。armyId 不存在 ⇒
 * 具名拒。
 *
 * <p>★ <b>只改一个字段</b>：成功时只换 {@link Army#masterGovUnitId()}，{@code id}/{@code rootUnit}/{@code name}
 * 原样保留；变更经 {@link SdChangeSet#between} 派生（铁律 2 + 5）。本命令<b>不碰 unit 切片</b>——{@code
 * ArmyFormation.masterGov} 的双边同步是 {@code simos.army.assignGov} 组合工具的职责（本命令只做 sd 侧的单一事实落点， 供 GM 直接提交
 * / 组合工具复用）。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ handler 照常注册、GM 的 {@code simos.command.submit}
 * 照常可用，但排除出决策令白名单 / {@code sd.RegisterEffect} / 决策人目录（与 {@code economy.SwitchMode} 等同制）。
 */
public final class SetArmyMasterGovHandler implements CommandHandler, GmOnlyCommand {

  @Override
  public String type() {
    return "sd.SetArmyMasterGov";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      ArmyId id = ArmyId.parse(SdPayloads.requireText(payload, "armyId"));
      Optional<UnitId> masterGovUnitId =
          SdPayloads.optionalText(payload, "masterGovUnitId")
              .filter(text -> !text.isBlank())
              .map(UnitId::parse);
      Army existing = base.armies().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("军队不存在: " + id);
      }
      if (masterGovUnitId.isPresent()) {
        Unit masterGov = SdSnapshots.units(state).units().get(masterGovUnitId.get());
        if (masterGov == null) {
          return new HandlerOutcome.Rejected("masterGovUnitId 不存在: " + masterGovUnitId.get());
        }
        if (!(masterGov.module().orElse(null) instanceof GovFormation)) {
          return new HandlerOutcome.Rejected("masterGovUnitId 不是 GOV 单位: " + masterGovUnitId.get());
        }
      }
      Map<ArmyId, Army> next = new LinkedHashMap<>(base.armies());
      next.put(id, new Army(id, masterGovUnitId, existing.rootUnit(), existing.name()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withArmies(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
