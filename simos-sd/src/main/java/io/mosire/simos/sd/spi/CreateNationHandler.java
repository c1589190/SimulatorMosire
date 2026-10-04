package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

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

  private static final Logger LOG = SdLog.nation();

  @Override
  public String type() {
    return "sd.CreateNation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      NationId id = NationId.parse(SdPayloads.requireText(payload, "nationId"));
      String name = SdPayloads.requireText(payload, "name");
      RegionId homeRegion = RegionId.parse(SdPayloads.requireText(payload, "homeRegionId"));
      int budget = SdPayloads.requireInt(payload, "adminBudgetPerTick");
      if (base.nations().containsKey(id)) {
        return new HandlerOutcome.Rejected("国家已存在: " + id);
      }
      GameMap map = SdSnapshots.map(state);
      Region region = map.regions().get(homeRegion);
      if (region == null) {
        return new HandlerOutcome.Rejected("homeRegion 不存在: " + homeRegion);
      }
      if (!NationTag.isNationTag(region.meta().tag())) {
        return new HandlerOutcome.Rejected(
            "Region " + homeRegion + " 无国家 tag（R13：需以 " + NationTag.PREFIX + " 开头的 tag）");
      }
      Map<NationId, Nation> next = new LinkedHashMap<>(base.nations());
      next.put(id, new Nation(id, name, homeRegion, budget));
      LOG.info(
          "event=SD_NATION_CREATED id={} name={} homeRegion={} adminBudget={}",
          id.value(),
          name,
          homeRegion.value(),
          budget);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withNations(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
