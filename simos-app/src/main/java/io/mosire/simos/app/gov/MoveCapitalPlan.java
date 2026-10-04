package io.mosire.simos.app.gov;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.moveCapital} 的后端计划/组合（P1.2，2026-10-09）</b>：把一次迁都拆成固定批序的命令序列， 经 {@link
 * CoreSimos#submitBatch} 落 <b>一条 revision</b>。本类<b>不是 MCP 工具</b>——它是下一层工具要复用的组合根逻辑 （用户 2026-10-09
 * 口径：先做后端，再做 Agent/MCP 工具）。
 *
 * <p>★★ <b>迁都覆盖的五件事与命令序</b>（顺序即语义；整批原子）：
 *
 * <ol>
 *   <li><b>首都区</b>：把目标 hex 从它当前所属的其他 Region 划给 {@code Nation.homeRegion} （普通源 ⇒ {@code
 *       map.ReassignHexes}；会被划空的单格源 ⇒ {@code map.MergeRegions}）；若目标格本无归属， 用 {@code
 *       map.UpdateRegion} 把该格加入首都区。
 *   <li><b>城市归属</b>：{@code social.MoveCity} 把首都城市挪到目标格并把 region 设为首都区；城市身份不变 ⇒ {@code
 *       urban:&lt;cityId&gt;:} 的城镇人口派生不丢。
 *   <li><b>中央 GOV</b>：{@code unit.PlaceAt} 把中央 GOV 单位移到目标格。
 *   <li><b>国库</b>：{@code actor.MoveAccount} 按 owner（{@code UNIT:&lt;govUnitId&gt;}）把旧格整本账搬到新格，
 *       余额与冻结额随行、目标已有逐键精确相加。
 *   <li><b>编制/管辖</b>：中央 GOV 的 {@code jurisdiction} 若尚未含首都区，用 {@code unit.SetJurisdiction}
 *       补上（已有的长期税率与上限原样保留）。
 * </ol>
 *
 * <p>★ <b>纯计划 + 显式提交</b>：{@link #plan} 只读状态、不写任何字节；{@link #submit} 才调 {@code
 * CoreSimos.submitBatch}。{@link Plan#commands()} 是实际要落的命令序（已过滤 no-op），无命令时 {@link Plan#empty()} 为真
 * —— 调用方应把空计划视为"迁都无需改动"，不要提交空批（{@code submitBatch} 会拒空批）。
 *
 * <p>★ <b>已知边界（如实）</b>：本计划不搬人口批次（{@code social.MovePopulationLots} 需要调用方给批次 id 与目标家户；
 * 城市身份不动时城镇人口归属不变，物理人口是否随迁是独立决定）；也不自动改省属 GOV 的 superior 链——那些是 行政重划的一部分，超出"迁都"最小组合。
 */
public final class MoveCapitalPlan {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private MoveCapitalPlan() {}

  /**
   * 一次迁都请求（全部为稳定身份，不含任何派生读数）。
   *
   * @param nationId 目标国家（取其 {@code homeRegion} 作为首都区）
   * @param capitalCityId 要迁的首都城市（social 侧城市 id）
   * @param centralGovUnitId 中央 GOV 单位 id（GOV 单位的 {@code unit.id()} 裸值）
   * @param toHex 新首都落点
   * @param reason 迁移原因（进命令载荷；至少非空白）
   * @param branch 提交分支
   * @param expectedRevision 提交所依据的 head
   * @param initiator 命令发起者（审计）
   * @param batchId 本批的 correlationId（一次迁都一个；不得空白）
   */
  public record Request(
      String nationId,
      String capitalCityId,
      String centralGovUnitId,
      HexCoord toHex,
      String reason,
      io.mosire.simos.util.state.BranchId branch,
      RevisionId expectedRevision,
      String initiator,
      String batchId) {

    public Request {
      requireText(nationId, "nationId");
      requireText(capitalCityId, "capitalCityId");
      requireText(centralGovUnitId, "centralGovUnitId");
      Objects.requireNonNull(toHex, "toHex");
      requireText(reason, "reason");
      Objects.requireNonNull(branch, "branch");
      Objects.requireNonNull(expectedRevision, "expectedRevision");
      requireText(initiator, "initiator");
      requireText(batchId, "batchId");
    }

    private static void requireText(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " 不得为空白");
      }
    }
  }

  /** 计划产物：固定批序的命令 + 只读预览读数。 */
  public record Plan(
      Request request,
      RegionId capitalRegionId,
      CityId cityId,
      UnitId centralGovUnitId,
      HexCoord fromHex,
      List<CommandEnvelope> commands,
      Map<String, Object> preview) {

    public Plan {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(capitalRegionId, "capitalRegionId");
      Objects.requireNonNull(cityId, "cityId");
      Objects.requireNonNull(centralGovUnitId, "centralGovUnitId");
      Objects.requireNonNull(fromHex, "fromHex");
      commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
      preview =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(preview, "preview")));
    }

    /** 无需任何改动（调用方不要提交空批）。 */
    public boolean empty() {
      return commands.isEmpty();
    }
  }

  /**
   * 纯计划：读 {@code state}，算出固定批序的命令序列；任何身份缺失 / 图外 hex / 国库账缺失 / 会把某个区域划空且 该源不是单格（无法用 merge 表达）都抛
   * {@link IllegalArgumentException}，由调用方折成具名拒。
   */
  public static Plan plan(SimulationState state, Request request) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(request, "request");
    GameMap map = mapOf(state);
    SocialData social = socialOf(state);
    UnitState units = unitsOf(state);
    SdState sd = sdOf(state);
    ActorSnapshot actorSnapshot = actorSnapshotOf(state);
    SimosTimestamp at = state.meta().timestamp();

    NationId nationId = NationId.parse(request.nationId());
    Nation nation = sd.nations().get(nationId);
    if (nation == null) {
      throw new IllegalArgumentException("国家不存在: " + nationId);
    }
    RegionId capitalRegionId = nation.homeRegion();
    Region capitalRegion = map.regions().get(capitalRegionId);
    if (capitalRegion == null) {
      throw new IllegalArgumentException("国家的 homeRegion " + capitalRegionId + " 不在当前地图里（首都区不存在）");
    }
    CityId cityId = CityId.parse(request.capitalCityId());
    SocialCity city = social.cities().get(cityId);
    if (city == null) {
      throw new IllegalArgumentException("城市不存在: " + cityId);
    }
    UnitId govId = UnitId.parse(request.centralGovUnitId());
    Unit gov = units.units().get(govId);
    if (gov == null) {
      throw new IllegalArgumentException("中央 GOV 单位不存在: " + govId);
    }
    HexCoord toHex = request.toHex();
    if (!map.hexes().containsKey(toHex)) {
      throw new IllegalArgumentException("目标 hex 不在图上: " + toHex);
    }
    HexCoord fromHex =
        units
            .effectivePosition(govId, at)
            .orElseThrow(
                () -> new IllegalArgumentException("中央 GOV 单位没有有效位置（无法定位旧国库账，拒绝迁都）: " + govId));
    ActorRef owner = new ActorRef(ActorKind.UNIT, govId.value());
    GoodsAccount treasury =
        actorSnapshot.data().accounts().get(new GoodsAccountKey(owner, fromHex));
    if (treasury == null) {
      throw new IllegalArgumentException(
          "中央 GOV 在旧位置 " + fromHex + " 没有国库账（" + owner + "）；请先落账再迁都，拒绝把空账当国库");
    }

    List<CommandEnvelope> commands = new ArrayList<>();
    // ① 首都区：把目标 hex 归入 homeRegion，并尽量从旧归属里移除（重叠虽是合法状态，但首都区应独占其格）。
    Set<RegionId> sourceRegions = new LinkedHashSet<>();
    for (Region region : map.regions().values()) {
      if (!region.id().equals(capitalRegionId) && region.hexes().contains(toHex)) {
        sourceRegions.add(region.id());
      }
    }
    List<RegionId> mergeSources = new ArrayList<>();
    List<RegionId> reassignSources = new ArrayList<>();
    for (RegionId sourceId : sourceRegions) {
      Region source = map.regions().get(sourceId);
      LinkedHashSet<HexCoord> remaining = new LinkedHashSet<>(source.hexes());
      remaining.remove(toHex);
      if (remaining.isEmpty()) {
        mergeSources.add(sourceId);
      } else {
        reassignSources.add(sourceId);
      }
    }
    if (!mergeSources.isEmpty()) {
      commands.add(
          envelope(
              request,
              "map.MergeRegions",
              Map.of(
                  "targetRegionId", capitalRegionId.value(),
                  "sourceRegionIds", mergeSources.stream().map(RegionId::value).toList())));
    }
    if (!reassignSources.isEmpty()) {
      commands.add(
          envelope(
              request,
              "map.ReassignHexes",
              Map.of(
                  "toRegionId", capitalRegionId.value(),
                  "fromRegionIds", reassignSources.stream().map(RegionId::value).toList(),
                  "hexes", List.of(hexJson(toHex)))));
    }
    if (sourceRegions.isEmpty() && !capitalRegion.hexes().contains(toHex)) {
      List<Map<String, Object>> hexes = new ArrayList<>(capitalRegion.hexes().size() + 1);
      for (HexCoord hex : capitalRegion.hexes()) {
        hexes.add(hexJson(hex));
      }
      hexes.add(hexJson(toHex));
      commands.add(
          envelope(
              request,
              "map.UpdateRegion",
              Map.of("regionId", capitalRegionId.value(), "hexes", hexes)));
    }

    // ② 城市归属：身份不变 ⇒ 城镇人口派生不丢；物理人口随迁是独立决定。
    Map<String, Object> cityPayload = new LinkedHashMap<>();
    cityPayload.put("id", cityId.value());
    cityPayload.put("at", hexJson(toHex));
    cityPayload.put("region", capitalRegionId.value());
    if (!city.at().equals(toHex)
        || city.region().map(r -> !r.equals(capitalRegionId)).orElse(true)) {
      commands.add(envelope(request, "social.MoveCity", cityPayload));
    }

    // ③ 中央 GOV：移到新落点。
    if (!fromHex.equals(toHex)) {
      commands.add(
          envelope(request, "unit.PlaceAt", Map.of("id", govId.value(), "hex", hexJson(toHex))));
    }

    // ④ 国库：整本账随 owner 从旧格搬到新格（余额 + 冻结随行；目标已有逐键精确相加）。
    if (!fromHex.equals(toHex)) {
      commands.add(
          envelope(
              request,
              "actor.MoveAccount",
              Map.of(
                  "owner", Map.of("kind", ActorKind.UNIT.name(), "id", govId.value()),
                  "from", hexJson(fromHex),
                  "to", hexJson(toHex),
                  "reason", request.reason())));
    }

    // ⑤ 编制/管辖：首都区必须在中央 GOV 的管辖集里（已有税率与上限留给 SetJurisdiction 保原值）。
    boolean jurisdictionHasCapital =
        gov.jurisdiction()
            .map(j -> j.taxRatePerMilleByRegion().containsKey(capitalRegionId))
            .orElse(false);
    if (!jurisdictionHasCapital) {
      List<String> regions = new ArrayList<>();
      gov.jurisdiction()
          .ifPresent(
              j -> {
                for (RegionId regionId : j.taxRatePerMilleByRegion().keySet()) {
                  regions.add(regionId.value());
                }
              });
      if (!regions.contains(capitalRegionId.value())) {
        regions.add(capitalRegionId.value());
      }
      commands.add(
          envelope(
              request,
              "unit.SetJurisdiction",
              Map.of("unitId", govId.value(), "regions", regions)));
    }

    Map<String, Object> preview = new LinkedHashMap<>();
    preview.put("nationId", nationId.value());
    preview.put("capitalRegionId", capitalRegionId.value());
    preview.put("capitalCityId", cityId.value());
    preview.put("centralGovUnitId", govId.value());
    preview.put("from", hexJson(fromHex));
    preview.put("to", hexJson(toHex));
    preview.put("populationWithCity", social.urbanPopulationAt(cityId));
    preview.put("commands", commands.stream().map(CommandEnvelope::type).toList());
    preview.put("commandCount", commands.size());
    return new Plan(request, capitalRegionId, cityId, govId, fromHex, commands, preview);
  }

  /**
   * 计划 + 提交（一批 = 一条 revision）。只读重放用 {@code request.expectedRevision} 对应的坐标—— 乐观并发由 {@code
   * submitBatch} 复核；冲突/拒绝原样返回，本类不重试、不吞结局。
   */
  public static BatchResult submit(CoreSimos core, QueryService query, Request request) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(request, "request");
    SimulationState state =
        query.stateAt(QueryService.QueryTarget.at(request.branch(), request.expectedRevision()));
    Plan planned = plan(state, request);
    if (planned.empty()) {
      throw new IllegalArgumentException("simos.gov.moveCapital 计划为空：目标状态已经满足，无需提交空批");
    }
    return core.submitBatch(planned.commands());
  }

  /** 一条命令：身份取自 request 的 batchId/initiator，载荷由 {@link #json} 序列化。 */
  private static CommandEnvelope envelope(
      Request request, String type, Map<String, Object> payload) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        request.batchId(),
        request.initiator(),
        request.branch(),
        request.expectedRevision(),
        type,
        json(payload));
  }

  /** 坐标 JSON：{@code {q,r}}（与各域载荷的既有形状一致）。 */
  private static Map<String, Object> hexJson(HexCoord hex) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("q", hex.q());
    out.put("r", hex.r());
    return out;
  }

  /** 载荷序列化失败 = 契约故障（不是可恢复的业务分支），当场炸。 */
  private static String json(Map<String, Object> payload) {
    try {
      return MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("moveCapital 命令载荷序列化失败: " + payload, e);
    }
  }

  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot = requireModule(state, "map");
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  private static SocialData socialOf(SimulationState state) {
    Snapshot snapshot = requireModule(state, "social");
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  private static UnitState unitsOf(SimulationState state) {
    Snapshot snapshot = requireModule(state, "unit");
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  private static SdState sdOf(SimulationState state) {
    Snapshot snapshot = requireModule(state, "sd");
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "state 的 sd 切片不是 SdSnapshot: " + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }

  private static ActorSnapshot actorSnapshotOf(SimulationState state) {
    Snapshot snapshot = requireModule(state, "actor");
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot;
  }

  private static Snapshot requireModule(SimulationState state, String namespace) {
    return state
        .module(namespace)
        .orElseThrow(() -> new IllegalStateException("state 里没有 " + namespace + " 切片（装配故障）"));
  }
}
