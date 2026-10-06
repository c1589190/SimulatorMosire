package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.CreateNation} 命令的处理器（spec §四）。
 *
 * <pre>{@code
 * {"nationId":"n1","name":"甲国","homeRegionId":"r1","adminBudgetPerTick":10}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code homeRegionId} 不存在；该 Region **无国家 tag**（R13，{@link NationTag}）。
 *
 * <p>★ {@code adminBudgetPerTick} 是**每日**行政动作预算（单位：日，2026-09-24 日制裁定；非国库余额，**当前无消费方**——见 {@link
 * Nation}）。
 */
public final class CreateNationHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CreateNation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String nationForLog = null;
    String regionForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      NationId id = NationId.parse(SdPayloads.requireText(payload, "nationId"));
      nationForLog = id.value();
      String name = SdPayloads.requireText(payload, "name");
      RegionId homeRegion = RegionId.parse(SdPayloads.requireText(payload, "homeRegionId"));
      regionForLog = homeRegion.value();
      int budget = SdPayloads.requireInt(payload, "adminBudgetPerTick");
      if (base.nations().containsKey(id)) {
        return rejected("国家已存在: " + id, "nation", nationForLog);
      }
      GameMap map = SdSnapshots.map(state);
      Region region = map.regions().get(homeRegion);
      if (region == null) {
        return rejected(
            "homeRegion 不存在: " + homeRegion, "nation", nationForLog, "region", regionForLog);
      }
      if (!NationTag.isNationTag(region.meta().tag())) {
        return rejected(
            "Region " + homeRegion + " 无国家 tag（R13：需以 " + NationTag.PREFIX + " 开头的 tag）",
            "nation",
            nationForLog,
            "region",
            regionForLog);
      }
      Map<NationId, Nation> next = new LinkedHashMap<>(base.nations());
      next.put(id, new Nation(id, name, homeRegion, budget));
      EventLog.channel(SdLog.nation())
          .info(
              LogEvent.of(
                  "SD_NATION_CREATED",
                  SdLogSource.SD_NATION,
                  "id",
                  id.value(),
                  "name",
                  name,
                  "homeRegion",
                  homeRegion.value(),
                  "adminBudget",
                  budget,
                  "nations",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withNations(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "nation", nationForLog, "region", regionForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.nation(), SdLogSource.SD_NATION, "SD_CREATE_NATION_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
