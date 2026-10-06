package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetStateDescription} 命令的处理器（阶段 D1 / 用户设计 D-012，2026-10-02）：把单位的**当前回合状态**链接到
 * **任意状态描述地址**，一条命令 = upsert 或删除一条链接。
 *
 * <pre>{@code
 * {"id":"u-1","state":"ENGAGED","address":"army:combat.c-1"}   // upsert
 * {"id":"u-1","state":"ENGAGED"}                                // 缺省/null/空串 = 删除该链接
 * }</pre>
 *
 * <p>★★ <b>本模块不解析地址的目标域</b>（铁律 3）：{@code army:combat.c-1} 对 unit 而言只是一段 canonical 文本，它不知道那是交战记录、
 * 也不知道将来 {@code gov:...} 是公文；地址文本的 canonical 校验落在 {@link io.mosire.simos.unit.Unit} 构造期（全仓唯一拼写点走
 * util 的 {@code Address} 语法）。
 *
 * <p>★ <b>载荷形状</b>：{@code id}/{@code state} 必填非空白；{@code address} 允许缺省、{@code null} 或空串 =
 * 清除该状态链接。坏载荷与域规则违反都折成 {@code Rejected}（不留 revision）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：本命令**非 GmOnly**（D-012 的机制是通用的，后续政府模块也要用），因此它可以被
 * 嵌进决策令；目标就是载荷点名的**那一个单位**（与 {@code unit.SetStatus} 同款，判越权交调用方）。
 */
public final class SetStateDescriptionHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}，也是 {@code CatalogTool} 与 GM 窄工具的固定命令类型）。 */
  public static final String TYPE = "unit.SetStateDescription";

  /** ★ 目标资源（{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
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
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      unitForLog = id.value();
      String stateKey = UnitPayloads.requireText(payload, "state");
      // ★ 缺省 / null / 空串 = 删除该状态链接（空串与"空白串"同义，都是"没有地址"）。
      Optional<String> address = UnitPayloads.optionalText(payload, "address");
      UnitState next = UnitOperations.setStateDescription(snapshot.state(), id, stateKey, address);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_STATE_DESCRIPTION_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "stateKeyLength",
                  stateKey.length(),
                  "hasAddress",
                  address.isPresent()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_STATE_DESCRIPTION_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
