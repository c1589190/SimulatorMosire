package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code gov.UpdateNation}：只改既有 {@link Nation} 的指定字段（名称 / homeRegion / 行政预算），其余保持。
 *
 * <pre>{@code
 * {"nationId":"n1","name":"新名","homeRegionId":"r2","adminBudgetPerTick":12}
 * }</pre>
 *
 * <p>★★ <b>为什么不复用 {@code gov.CreateNation}</b>：create 是"只增不改"，而改名/换 homeRegion 是既有国家的编辑；
 * 用删除再创建会丢掉决策人归属、外交关系与事件对 NationId 的引用（那些表只认 id，但删除仍会先被守卫拒）。本命令只写 {@code nations}
 * 一张表：不碰决策人、外交、区域或单位。
 *
 * <p>★★ <b>homeRegion 的 tag 必须精确匹配</b>：{@code region.meta().tag()} 必须<b>逐字等于</b> {@link
 * NationTag#tagFor(NationId)}，不是前缀匹配（{@code nation:FRAX} 不是 {@code nation:FRA}）。旧 create
 * 只判前缀，本批一起收紧。
 *
 * <p>★ <b>GM-only</b>（不打 {@code DmUsableCommand}）。
 */
public final class UpdateNationHandler implements CommandHandler {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "gov.UpdateNation";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GovState base = GovSnapshots.of(state);
    String nationForLog = null;
    String regionForLog = null;
    try {
      JsonNode payload = GovPayloads.parse(payloadJson);
      NationId id = NationId.parse(GovPayloads.requireText(payload, "nationId"));
      nationForLog = id.value();
      Nation existing = base.nations().get(id);
      if (existing == null) {
        return rejected("国家不存在: " + id, "nation", nationForLog);
      }
      boolean any = false;
      String name = existing.name();
      if (payload.has("name") && !payload.get("name").isNull()) {
        name = GovPayloads.requireText(payload, "name");
        any = true;
      }
      RegionId homeRegion = existing.homeRegion();
      if (payload.has("homeRegionId") && !payload.get("homeRegionId").isNull()) {
        homeRegion = RegionId.parse(GovPayloads.requireText(payload, "homeRegionId"));
        any = true;
      }
      int budget = existing.adminBudgetPerTick();
      if (payload.has("adminBudgetPerTick") && !payload.get("adminBudgetPerTick").isNull()) {
        budget = GovPayloads.requireInt(payload, "adminBudgetPerTick");
        any = true;
      }
      if (!any) {
        throw new IllegalArgumentException(
            "gov.UpdateNation 至少要给 name / homeRegionId / adminBudgetPerTick 之一");
      }
      regionForLog = homeRegion.value();
      GameMap map = GovSnapshots.map(state);
      Region region = map.regions().get(homeRegion);
      if (region == null) {
        return rejected(
            "homeRegionId 不存在: " + homeRegion, "nation", nationForLog, "region", regionForLog);
      }
      String expectedTag = NationTag.tagFor(id);
      if (!expectedTag.equals(region.meta().tag())) {
        return rejected(
            "homeRegion 的 tag 必须逐字等于 "
                + expectedTag
                + "，实际是 "
                + region.meta().tag()
                + "（Region "
                + homeRegion
                + " 不属于国家 "
                + id
                + "）",
            "nation",
            nationForLog,
            "region",
            regionForLog);
      }
      Map<NationId, Nation> next = new LinkedHashMap<>(base.nations());
      next.put(id, new Nation(id, name, homeRegion, budget));
      EventLog.channel(GovLog.nation())
          .info(
              LogEvent.of(
                  "GOV_NATION_UPDATED",
                  GovLogSource.GOV_NATION,
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
      return new HandlerOutcome.Applied(GovChangeSet.between(base, base.withNations(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "nation", nationForLog, "region", regionForLog);
    }
  }

  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GovPayloads.logRejected(
        GovLog.nation(),
        GovLogSource.GOV_NATION,
        "GOV_UPDATE_NATION_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
