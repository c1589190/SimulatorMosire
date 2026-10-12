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
 * {@code gov.CreateNation} 命令的处理器（spec §四）。
 *
 * <pre>{@code
 * {"nationId":"n1","name":"甲国","homeRegionId":"r1","adminBudgetPerTick":10}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code homeRegionId} 不存在；该 Region **无国家 tag**（R13，{@link NationTag}）。
 *
 * <p>★ {@code adminBudgetPerTick} 是**每日**行政动作预算（单位：日，2026-09-24 日制裁定；非国库余额，**无消费方**——见 {@link
 * Nation}）。
 *
 * <p>★★ <b>2026-10-12 批次 3c：本类自 {@code simos-sd/sd.spi} 迁入 {@code simos-gov/spi}，命令名 {@code
 * sd.CreateNation} → {@code gov.CreateNation}</b>（用户裁定「外交是 gov 的事」＋「sd 整个弃用！」）。语义/载荷/拒绝文案一字未改，
 * 变的只有： ①落点（变更集走 {@code GovChangeSet} 的前三个新组件里的 {@code nations}）；②载荷/切片助手改本模块的 {@link
 * GovPayloads}/{@link GovSnapshots}；③日志门面 {@code SdLog.nation()} → {@link
 * GovLog#nation()}（<b>事件名与字段逐字保留</b>）。
 *
 * <p>★ <b>GM-only</b>（反默认口径）：不打 {@code DmUsableCommand} ⇒ 只进 catalog 与 GM 提交面/窄工具。
 */
public final class CreateNationHandler implements CommandHandler {

  @Override
  public String type() {
    return "gov.CreateNation";
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
      String name = GovPayloads.requireText(payload, "name");
      RegionId homeRegion = RegionId.parse(GovPayloads.requireText(payload, "homeRegionId"));
      regionForLog = homeRegion.value();
      int budget = GovPayloads.requireInt(payload, "adminBudgetPerTick");
      if (base.nations().containsKey(id)) {
        return rejected("国家已存在: " + id, "nation", nationForLog);
      }
      GameMap map = GovSnapshots.map(state);
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
      EventLog.channel(GovLog.nation())
          .info(
              LogEvent.of(
                  "SD_NATION_CREATED",
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

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GovPayloads} 的日志安全截断，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GovPayloads.logRejected(
        GovLog.nation(), GovLogSource.GOV_NATION, "SD_CREATE_NATION_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
