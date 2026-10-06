package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.CreateHousehold} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：新建一个空成员的家户。
 *
 * <pre>{@code
 * {"householdId":"hh-1",
 *  "location":{"type":"HEX","hex":{"q":1,"r":0}},
 *  "profile":{"name":"城东民户","description"?,"metadata"?},
 *  "vitalRates":[{"bracketId":"0-14","sex":"FEMALE","birthRatePerMillionPerTick":0,"deathRatePerMillionPerTick":5}],
 *  "reason":"创世播种"}
 * }</pre>
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#create}（家户创建的唯一落点）并把 {@code base → next} 包成 {@link
 * SocialChangeSet}；id 已存在、profile 形状不符、vitalRates 重复/负数都由域层具名拒（边界只折 {@code Rejected}）。 ★ 家户位置可以是
 * {@code HEX} 或 {@code UNIT}；{@code UNIT} 的 unit 存在性与 unit 侧列表由 app 组合工具同批保证（social 域不认识 unit，铁律
 * 3）。
 *
 * <p>★ <b>载荷 {@code reason}</b>：{@link HouseholdBook#create} 没有 reason 入参（创建事件固定），本命令仍要求非空白 ——
 * 与其余家户命令同一口径，避免"有的命令有原因、有的没有"。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：2026-10-20 起走跨命名空间 {@link #targetResources}——HEX 位置 ⇒
 * {@code social:<q>_<r>}；UNIT 位置 ⇒ {@code unit:<unitId>}。旧 {@link #targetPaths} 保留（HEX ⇒ 格路径；UNIT ⇒
 * 空列表，与升级前逐字一致），只服务既有测试/调用点。
 */
public final class CreateHouseholdHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.CreateHousehold";

  /** 创建型目标：按载荷 {@code location} 判（HEX ⇒ social / UNIT ⇒ unit）。家户此刻还不存在，故不查 SocialData。 */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HouseholdLocation location = SocialPayloads.requireLocation(payload, "location");
    return List.of(HouseholdCommandTargets.forLocation(location));
  }

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
      HouseholdProfile profile = SocialPayloads.requireProfile(payload, "profile");
      HouseholdVitalRates vitalRates =
          new HouseholdVitalRates(SocialPayloads.requireVitalRates(payload, "vitalRates"));
      SocialPayloads.requireReason(payload); // 校验非空白（create 本身不接 reason）
      SocialData next = HouseholdBook.create(base, id, location, profile, vitalRates);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
