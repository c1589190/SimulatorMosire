package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.SetHouseholdLocation} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：把家户在 {@code HEX ↔ UNIT} 之间移动。
 *
 * <pre>{@code
 * {"householdId":"hh-1","location":{"type":"UNIT","unitId":"gov-central"},"reason":"编入官府"}
 * }</pre>
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#setLocation}（位置唯一真值源）；家户不存在、位置形状不符 ⇒ 具名拒。
 * ★ <b>UNIT 的 unit 侧一致性</b>（{@code unit.households} 同步）由 app 组合工具同批保证（架构 §3.3）——本命令只动 Social 切片，
 * 不认识 unit（铁律 3）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：HEX 位置 ⇒ 那一格的 social 资源路径；UNIT 位置 ⇒ 空列表。
 */
public final class SetHouseholdLocationHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SetHouseholdLocation";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HouseholdLocation location = SocialPayloads.requireLocation(payload, "location");
    if (location instanceof HouseholdLocation.Hex hex) {
      return List.of(ResourcePaths.social(hex.hex().q(), hex.hex().r()));
    }
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      HouseholdId id = SocialPayloads.requireHouseholdId(payload, "householdId");
      HouseholdLocation location = SocialPayloads.requireLocation(payload, "location");
      String reason = SocialPayloads.requireReason(payload);
      SocialData next = HouseholdBook.setLocation(base, id, location, reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
