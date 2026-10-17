package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.spi.ActorClearRegionHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.spi.EconomyClearRegionHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.spi.ClearRegionHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code simos.region.clearData} 的只读 pre-scan（P1b1，2026-10-01）：复用 {@link
 * RegionSeedPlan#inspectCleanGate} 的**同一次**命中扫描，把命中按域折成 social / actor / economy 三份 {@link
 * RegionSeedPlan.CleanGate} 形状，并给出实际要落的固定命令序。
 *
 * <p>★★ <b>为什么复用 clean gate 而不是另写一份"清空命中"</b>：{@code simos.region.seed} 的 {@code NEEDS_CLEAR}
 * 就是拿这份扫描说"哪里脏、让你去清"的；若 clearData 自己再写一份判据，两侧会漂开——出现"seed 说脏、clearData 说干净" 或反之的假象（AGENTS.md §9.1
 * 的同族失败）。
 *
 * <p>★ <b>命中即下单</b>：每个域只有 clean gate 非干净时才落对应的 {@code ClearRegion}；三域都干净 ⇒ 命令序为空（零 revision）。{@code
 * ga.PutInfo} 只在至少一个域有命中时追加在最后。
 *
 * <p>★ <b>计数是"阻塞播种的记录条数"</b>（与 clean gate 逐条同源）：经济域只认 {@code industries}/{@code markets}
 * ——清空命令在实际执行时还会连带清掉 unit/classes/memberships 等依赖记录，那些不由 pre-scan 的命中数表达，但它们的清空发生在 同一个域命令内、同一批内。
 */
final class RegionClearPlan {

  /** 固定批序：social → actor → economy → ga.PutInfo（见类注与工具描述）。三个域名从各 handler 的 TYPE 取，不另抄字面量。 */
  static final String SOCIAL_CLEAR_TYPE = ClearRegionHandler.TYPE;

  static final String ACTOR_CLEAR_TYPE = ActorClearRegionHandler.TYPE;

  static final String ECONOMY_CLEAR_TYPE = EconomyClearRegionHandler.TYPE;

  /**
   * ★★ D-031 P1：production 清区（<b>B2c 裁定九加宽</b>：同一命令内<b>先退役该区生产实例、再清该区无人引用的 产业定义</b>；退役数与删除数都进该命令的具名
   * INFO 读数；全局 mode 不随区删）。
   *
   * <p>★★ <b>批序为什么由本类保证</b>：economy 的 {@code ClearRegion} 先摘掉指向本区的引用行（relations /
   * operatorConditions / allocations / assetShares …），随后本命令才既退役实例又删定义 —— 三段同批，
   * 所以"先摘引用、再退役实例、最后删定义"这个次序是**批序的函数**，不是各命令内部再排序。
   */
  static final String PRODUCTION_CLEAR_TYPE =
      io.mosire.simos.production.spi.ClearRegionDefinitionsHandler.TYPE;

  /** {@code ga.PutInfo} 的类型字面与 {@link RegionSeedPlan#PUT_INFO_TYPE} 同源（同住包内，不另抄）。 */
  static final String PUT_INFO_TYPE = RegionSeedPlan.PUT_INFO_TYPE;

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 目标 Region canonical，一次清空一条）。 */
  static final String INFO_KEY = "regionClearData";

  private RegionClearPlan() {}

  /** 一个域的命中（保序干净门形状 + 域名）。 */
  record Domain(String name, RegionSeedPlan.CleanGate gate) {

    Domain {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(gate, "gate");
    }

    boolean clean() {
      return gate.clean();
    }

    /** 该域全部命中的条数之和（示例样本不算）。 */
    long hitCount() {
      long total = 0L;
      for (RegionSeedPlan.Hit hit : gate.hits()) {
        total += hit.count();
      }
      return total;
    }

    /** preview 视图：{@code {domain, clean, hits[], hitCounts{}}}（与 clean gate 逐字段同形）。 */
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("domain", name);
      view.putAll(gate.view());
      return view;
    }
  }

  /** 一次可执行清空计划（纯函数产物；{@code commands} 是实际会落的类型序，含最后的 ga.PutInfo）。 */
  record Plan(
      Region region,
      long tick,
      Domain social,
      Domain actor,
      Domain economy,
      List<String> commands) {

    Plan {
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(social, "social");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(economy, "economy");
      commands = List.copyOf(commands);
    }

    boolean hasWork() {
      return !commands.isEmpty();
    }

    Map<String, Object> domainsView() {
      Map<String, Object> domains = new LinkedHashMap<>();
      domains.put("social", social.view());
      domains.put("actor", actor.view());
      domains.put("economy", economy.view());
      return domains;
    }

    Map<String, Long> hitCounts() {
      Map<String, Long> counts = new LinkedHashMap<>();
      counts.put("social", social.hitCount());
      counts.put("actor", actor.hitCount());
      counts.put("economy", economy.hitCount());
      return counts;
    }
  }

  /**
   * 只读扫描：region 必须在当前 {@code map.regions()} 里；命中按域归类为三份 CleanGate，并据"非空即下单"组命令序。
   *
   * @throws IllegalArgumentException regionId 不在当前 map（由工具折 BAD_REQUEST）；map 切片缺失/类型不对由 {@link
   *     ToolSupport#gameMap} 抛
   */
  static Plan scan(SimulationState state, String regionId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(regionId, "regionId");
    GameMap map = ToolSupport.gameMap(state);
    Region region = map.regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    RegionSeedPlan.CleanGate gate = RegionSeedPlan.inspectCleanGate(state, region);
    List<RegionSeedPlan.Hit> socialHits = new ArrayList<>();
    List<RegionSeedPlan.Hit> actorHits = new ArrayList<>();
    List<RegionSeedPlan.Hit> economyHits = new ArrayList<>();
    for (RegionSeedPlan.Hit hit : gate.hits()) {
      if (hit.kind().startsWith("social.")) {
        socialHits.add(hit);
      } else if (hit.kind().startsWith("actor.")) {
        actorHits.add(hit);
      } else if (hit.kind().startsWith("economy.")) {
        economyHits.add(hit);
      } else {
        // ★ 未分类命中 = 实现漂移（RegionSeedPlan 加了新 kind 而这里没归域）⇒ 当场炸，不静默塞进别的域。
        throw new IllegalStateException("clean gate 出现未分类命中类型（实现漂移）: " + hit.kind());
      }
    }
    Domain social = new Domain("social", new RegionSeedPlan.CleanGate(socialHits));
    Domain actor = new Domain("actor", new RegionSeedPlan.CleanGate(actorHits));
    Domain economy = new Domain("economy", new RegionSeedPlan.CleanGate(economyHits));
    List<String> commands = new ArrayList<>(4);
    if (!social.clean()) {
      commands.add(SOCIAL_CLEAR_TYPE);
    }
    if (!actor.clean()) {
      commands.add(ACTOR_CLEAR_TYPE);
    }
    if (!economy.clean()) {
      commands.add(ECONOMY_CLEAR_TYPE);
    }
    // ★★ D-031 P1 + B2c 裁定九：只要本区域有任何域命中，就在 economy 引用清理之后追加 production 清区
    //   （同批内先退役该区实例、再删无人引用的定义；两者都进具名读数；无实例/无定义 ⇒ no-op）。
    if (!commands.isEmpty()) {
      commands.add(PRODUCTION_CLEAR_TYPE);
    }
    if (!commands.isEmpty()) {
      commands.add(PUT_INFO_TYPE);
    }
    return new Plan(
        region, state.meta().timestamp().tick(), social, actor, economy, List.copyOf(commands));
  }
}
