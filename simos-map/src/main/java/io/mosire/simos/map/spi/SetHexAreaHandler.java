package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreaKey;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.HexAreaOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code map.SetHexArea} 命令的处理器（2026-10-12 批次 2）：<b>一次改一格的一个可用地面积字段</b>（可扩展属性表）。
 *
 * <pre>{@code
 * {"hex":{"q":1,"r":2}, "key":"rural",              "op":"add", "value":-1000}
 * {"hex":{"q":1,"r":2}, "key":"urban",              "op":"set", "value":250000}
 * {"hex":{"q":1,"r":2}, "customKey":"盐田",          "op":"set", "value":500}
 * }</pre>
 *
 * <p>★ <b>载荷形状</b>（本模块的私事，C26）：{@code hex}（单格 {@code {q,r}}）+ <b>恰给一个</b>字段键 （{@code key} = 内置 enum
 * 标签 {@code urban}/{@code rural}；{@code customKey} = 自定义字段名，String）+ {@code op} （{@code set} 全量赋值 /
 * {@code add} 增量 ±）+ {@code value}（毫亩）。字段缺失、形态不符、两个键都给/都不给 ⇒ {@code Rejected}（载荷坏是命令的错，不是装配的错）。
 *
 * <p>★★ <b>GM-only：本类有意<b>不</b>实现 {@link io.mosire.simos.util.spi.DmUsableCommand}</b> ——
 * 反默认口径下"不打标记"就是 "仅 GM"（用户原话：「map 层级的命令、凭空造钱造人建军肯定不该给 DM 用啊，你的理解是对的」+ 派单书 §③.7「不许打 {@code
 * DmUsableCommand}」）。⇒ 它进 catalog 与 GM 提交面，但进不了令白名单 / {@code RegisterEffect} / 决策人 catalog； GM
 * 侧的窄工具 {@code MapSetHexAreaTool}（同名字符串）照常可用。
 *
 * <p>★ <b>域规则违反以 {@code Rejected} 面世</b>：{@link HexAreaOperations} 的 {@link
 * IllegalArgumentException} （图外 hex、负面积、增量落到负数、词表外的字段标签）在本边界折成拒绝理由，<b>零 revision</b>。
 *
 * <p>★ <b>不是时间命令</b> ⇒ 没有 {@code TimeProposal}、没有读写集。
 *
 * <p>★ <b>目标资源</b>（{@link CommandTargets}）：被改的那一格那一条 {@code map:<mapId>/hex/<q>_<r>}（util 的 {@link
 * ResourcePaths#hex}）。载荷坏 / 判不出目标 ⇒ 调用方 fail-closed 拒。
 */
public final class SetHexAreaHandler implements CommandHandler, CommandTargets {

  /** 命令类型字面量（窄工具 {@code MapSetHexAreaTool.NAME} 与它同字面，两处各自是唯一拼写点）。 */
  public static final String TYPE = "map.SetHexArea";

  /** ★ 目标资源：载荷点名的那一格（逐条判越权；一格一条）。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    HexCoord hex = MapPayloads.requireHex(payload, "hex");
    return List.of(ResourcePaths.hex(mapId, hex.q(), hex.r()));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map(); // 装配故障当场炸，不走拒绝路径
    String fieldForLog = null;
    String opForLog = null;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      HexCoord hex = MapPayloads.requireHex(payload, "hex");
      HexAreaKey key = MapPayloads.requireAreaKey(payload);
      HexAreaOperations.Op op = MapPayloads.requireAreaOp(payload);
      long value = MapPayloads.requireLong(payload, "value");
      fieldForLog = key.wireKey();
      opForLog = op.wire();
      var applied = HexAreaOperations.setArea(map, hex, key, op, value);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SET_HEX_AREA_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "hex",
                  hex,
                  "field",
                  key.wireKey(),
                  "op",
                  op.wire()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SET_HEX_AREA_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "field",
                  fieldForLog == null ? "-" : fieldForLog,
                  "op",
                  opForLog == null ? "-" : opForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
