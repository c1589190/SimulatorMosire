package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ {@code actor.ClearRegion}（P1b1，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 区域 actor 账本清空命令</b>——
 * 只清目标 Region hex 集内的账本与因此失去全部账本的主体，不动其它切片/结构。
 *
 * <pre>{@code
 * {"regionId":"701"}
 * }</pre>
 *
 * <p>★★ <b>清空边界</b>：
 *
 * <ul>
 *   <li>{@code ActorData.accounts}：{@code HouseholdAccountKey.location} 落在目标 hex 集的账本整条删除；
 *   <li>{@code ActorData.actors}：**只删除"清账后在任何位置都不再持有任何账户"的主体**。判据： {@code 清账前持有过账本 ∧
 *       清账后一本都不剩}；仍有别处账户的主体保留； <b>本来就无账户的主体保留</b>——无账户主体不是区域绑定数据（它是跨区的身份记录，可能今天还没开户）；
 *   <li>{@code ActorData.meta} 与其它 Region 的账本/主体<b>一字不动</b>。
 * </ul>
 *
 * <p>★ <b>Region 必须先在 {@code map.regions()} 里存在</b>：缺 map 切片/切片类型不对是装配故障（{@link
 * IllegalStateException} 当场炸，不走拒绝路径）；payload 里的 regionId 查不到才是 {@link HandlerOutcome.Rejected}（零
 * revision）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册到 Core、仍进 {@code commandTargets}，GM 的 {@code
 * simos.command.submit} 可提交；组合根构造令白名单/RegisterEffect/决策人目录时排除它。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：签名拿不到 state，逐格 actor 资源路径要读目标 Region 的 hex 集才能展开；本命令
 * GM-only、不进入决策令/裁决目标检查 ⇒ 返回空列表（fail-closed）。
 */
public final class ActorClearRegionHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  private static final Logger LOG = ActorLog.account();

  /** 命令类型（唯一拼写点：catalog 提示、Shell 注册与组合工具都从这里取/对齐）。 */
  public static final String TYPE = "actor.ClearRegion";

  @Override
  public String type() {
    return TYPE;
  }

  /** ★ 本命令没有可签名的目标（见类注）；仍解析载荷形状，坏载荷照旧以 {@link IllegalArgumentException} 出面。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    requireRegionId(ActorPayloads.parse(payloadJson));
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      String regionId = requireRegionId(ActorPayloads.parse(payloadJson));
      Region region = requireRegion(state, regionId);
      Set<HexCoord> hexes = region.hexes();
      // ★★ P2-A 具名缺口（如实记）：账户键不再带 HexCoord（位置从 Household.location 派生），
      //   而 actor 切片看不见 social ⇒ 本命令**无法**再把"目标 Region 的账本"映射出来。
      //   区域清账必须由组合根（同时看得见 social 与 actor）按"该区域的家户集"协调，属 P2-F。
      //   本命令因此只校验 region 存在性，不改任何账本/主体（不猜、不静默删错）。
      LOG.info(
          "event=ACTOR_REGION_CLEAR_SKIPPED region={} hexes={} accounts={} reason=accounts-are-household-owned",
          regionId,
          hexes.size(),
          base.accounts().size());
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, base));
    } catch (IllegalArgumentException e) {
      // ★ 域构造期守卫（若清空边界写错）也在这里折成具名拒绝，不穿成整条推进失败。
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** payload 的 regionId：必填、非空文本。 */
  private static String requireRegionId(JsonNode payload) {
    JsonNode node = payload.get("regionId");
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 regionId 必填且为非空文本");
    }
    return node.asText();
  }

  /**
   * 从只读 state 的 map 切片里取真档 Region：缺切片/切片类型不对 = 装配故障（{@link IllegalStateException} 当场炸）； regionId
   * 查不到 = 命令载荷错误（{@link IllegalArgumentException}，由 {@link #handle} 折成 Rejected）。
   */
  private static Region requireRegion(SimulationState state, String regionId) {
    Snapshot mapModule =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(mapModule instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + mapModule.getClass().getName());
    }
    Region region = mapSnapshot.map().regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    return region;
  }
}
