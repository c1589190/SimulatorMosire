package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.PopulationSeeder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.gen.CapitalAnchor;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementGenerator;
import io.mosire.simos.social.gen.SettlementParams;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.gen.SettlementRequest;
import io.mosire.simos.social.gen.TerrainView;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * ★★ {@code simos.region.seed} 的<b>纯推导</b>（P1a1，2026-10-01 后端 + MCP 稳定化计划）：从一份 {@link
 * SimulationState} 与 GM 参数算出 {@link Plan}——<b>不碰 {@code ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>复用现有生成器 / 播种器，不写第二份推导</b>：{@link SettlementGenerator#generate(SettlementRequest,
 * TerrainView, SettlementParams)} + {@link PopulationSeeder#groups(SettlementPlan, long)} + {@link
 * EconomySeeder#plan(String, List, GameMap)}。逐格人口 / 城市 / 批次的载荷由 {@code WorldgenInitializeTool}
 * 的同一份包内助手拼（见 {@code RegionSeedTool#buildBatch}），经济与 actor 的载荷直接取同一次 {@link
 * EconomySeeder.Seed}——"一次算出、同一份喂两条命令"的接缝不走样。
 *
 * <p>★★ <b>clean gate（只读 base state）</b>：目标 Region 的格集内出现下列任一记录 ⇒ 不进入生成、由工具返回 {@code NEEDS_CLEAR}（零
 * revision）：
 *
 * <ul>
 *   <li>{@code social.populations} 键命中；
 *   <li>{@code SocialData.groups} 的 {@code residence} 命中；
 *   <li>{@code SocialCity.at} 命中，或 {@code SocialCity.region} == 目标 Region；
 *   <li>{@code ActorData.accounts} 的 {@code GoodsAccountKey.location} 命中；
 *   <li>{@code economy.industries} 的 {@code <kind>@<q>_<r>} 命中，或 {@code economy.markets} 的格键命中。
 * </ul>
 *
 * <p>★ <b>世界级 classFirst 池不是 Region 命中</b>（它是共享世界状态，多 Region 追加播种是受支持形态）：只在结果 {@code warnings} 里警告。
 */
final class RegionSeedPlan {

  /** {@code social.SetPopulation} 的命令类型（与 {@code WorldgenInitializeTool} 同源，不另写字面量）。 */
  static final String SET_POPULATION_TYPE = WorldgenInitializeTool.SET_POPULATION_TYPE;

  /** {@code social.CreateCity} 的命令类型（每座城一条）。 */
  static final String CREATE_CITY_TYPE = WorldgenInitializeTool.CREATE_CITY_TYPE;

  /** {@code social.SeedGroups} 的命令类型。 */
  static final String SEED_GROUPS_TYPE = WorldgenInitializeTool.SEED_GROUPS_TYPE;

  /** {@code economy.Seed} 的命令类型（{@code includeEconomy=false} 时缺席）。 */
  static final String SEED_ECONOMY_TYPE = WorldgenInitializeTool.SEED_ECONOMY_TYPE;

  /** {@code actor.Seed} 的命令类型（{@code includeActors=false} 时缺席）。 */
  static final String SEED_ACTOR_TYPE = WorldgenInitializeTool.SEED_ACTOR_TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有：行动记录；与 {@code PutInfoHandler.type()} 同字面）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 城市化率缺省（冻结输入 {@code v17levant-nations.json} 的德意志取值；GM 可覆盖）。 */
  static final double DEFAULT_URBANIZATION_RATE = 0.12;

  /** 种子缺省：**确定**——同 region + 同参数 ⇒ 同输出；不传 seed 时用它（不依赖墙钟 / 随机源）。 */
  static final long DEFAULT_SEED = 0L;

  /** 三个系数的缺省（农业剩余 / 商业整合 / 政治中央度）。 */
  static final double DEFAULT_COEFFICIENT = 1.0;

  /** 每类命中最多列几条示例 id（计数仍是全量）。 */
  private static final int MAX_HIT_SAMPLES = 5;

  /** 等级直方图的固定序（{@link PlannedCity#tierRank}）。 */
  private static final List<String> TIER_ORDER =
      List.of(
          PlannedCity.TIER_MARKET_TOWN,
          PlannedCity.TIER_TOWN,
          PlannedCity.TIER_CITY,
          PlannedCity.TIER_MAJOR_CITY);

  private RegionSeedPlan() {}

  /**
   * 一次调用的全部 GM 参数（已在工具层完成类型 / 范围解析；本类型只做结构冻结与语义推导）。
   *
   * @param regionId 目标 Region（必须在当前 map.regions() 里）
   * @param totalPopulation 总人口（≥ 0）
   * @param urbanizationRate 城市化率（[0,1]）
   * @param seed 随机种子
   * @param capital 首都锚点（无首都 = {@link Optional#empty()}；{@link CapitalAnchor} 没有 hex，本工具不发明）
   * @param documentedNames 文档地名（按优先级；无 = 空列表）
   * @param agrarianSurplusRate 全国农业剩余率系数（≥ 0）
   * @param commercialIntegration 商业整合度（≥ 0）
   * @param politicalCentralization 政治中央度（≥ 0）
   * @param includeEconomy 是否落 {@code economy.Seed}（缺省 true）
   * @param includeActors 是否落 {@code actor.Seed}（缺省 true）
   */
  record Params(
      String regionId,
      long totalPopulation,
      double urbanizationRate,
      long seed,
      Optional<CapitalAnchor> capital,
      List<String> documentedNames,
      double agrarianSurplusRate,
      double commercialIntegration,
      double politicalCentralization,
      boolean includeEconomy,
      boolean includeActors) {

    Params {
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(capital, "capital");
      Objects.requireNonNull(documentedNames, "documentedNames");
      documentedNames = List.copyOf(documentedNames);
    }
  }

  /** 一类命中：命中类型 / 数量 / 最多 {@value #MAX_HIT_SAMPLES} 条示例 id。 */
  record Hit(String kind, long count, List<String> samples) {

    Hit {
      Objects.requireNonNull(kind, "kind");
      samples = List.copyOf(samples);
    }
  }

  /** clean gate 结果（{@code hits} 为空 = 干净；世界级 classFirst 不在此列，见类注）。 */
  record CleanGate(List<Hit> hits) {

    CleanGate {
      hits = List.copyOf(hits);
    }

    boolean clean() {
      return hits.isEmpty();
    }

    /** 线格式视图（preview 的 {@code cleanGate} 与 {@code NEEDS_CLEAR} 载荷共用一份形状）。 */
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("clean", clean());
      List<Map<String, Object>> hitViews = new ArrayList<>(hits.size());
      Map<String, Object> hitCounts = new LinkedHashMap<>();
      for (Hit hit : hits) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", hit.kind());
        row.put("count", hit.count());
        row.put("samples", hit.samples());
        hitViews.add(row);
        hitCounts.put(hit.kind(), hit.count());
      }
      view.put("hits", hitViews);
      view.put("hitCounts", hitCounts);
      return view;
    }
  }

  /**
   * 推导结果：干净 ⇒ {@code plan} 在场；命中 ⇒ {@code plan} 缺席（工具返回 {@code NEEDS_CLEAR}，零 revision）。 {@code
   * warnings} 在两种形态下都给（世界级 classFirst / actor 与 economy 开关不一致）。
   */
  record Derivation(Optional<Plan> plan, CleanGate cleanGate, List<String> warnings) {

    Derivation {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(cleanGate, "cleanGate");
      warnings = List.copyOf(warnings);
    }

    boolean clean() {
      return plan.isPresent();
    }
  }

  /**
   * 一份 Region 播种计划（全部字段是 base state 与参数的纯函数）。
   *
   * @param mapId 世界 map 称谓（进 {@code economy.Seed} 与 {@code sd.PutInfo} 地址）
   * @param region 目标 Region（真档对象）
   * @param params 本次调用的全部 GM 参数
   * @param request 交给 {@link SettlementGenerator} 的生成请求（全部字段都在）
   * @param settlement 生成的聚落计划
   * @param groups 与 {@code social.SeedGroups} / {@code economy.Seed} **同源**的人口批次
   * @param economySeed 同一次 {@link EconomySeeder#plan} 的内存产物（{@code includeEconomy/includeActors}
   *     任一为真时在场）
   * @param tick 批次锚点 = base state 的世界当前日
   * @param regionHexCount 目标 Region 的格数（clean gate 的扫描范围；★ 不是生成器落了农村序列的格数，两者在含海洋/零产格的 Region 上不等）
   */
  record Plan(
      String mapId,
      Region region,
      Params params,
      SettlementRequest request,
      SettlementPlan settlement,
      List<PopulationGroup> groups,
      Optional<EconomySeeder.Seed> economySeed,
      long tick,
      int regionHexCount) {

    Plan {
      Objects.requireNonNull(mapId, "mapId");
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(params, "params");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(settlement, "settlement");
      groups = List.copyOf(groups);
      Objects.requireNonNull(economySeed, "economySeed");
    }

    /** Σ 逐格农村人口（= 请求的 ruralPopulation）。 */
    long ruralTotal() {
      long total = 0L;
      for (long value : settlement.ruralPopulation().values()) {
        total += value;
      }
      return total;
    }

    /** Σ 城市人口（= 请求的 urbanPopulation）。 */
    long urbanTotal() {
      long total = 0L;
      for (PlannedCity city : settlement.cities()) {
        total += city.population();
      }
      return total;
    }

    /** 批内命令类型的固定序（与 {@code WorldgenInitializeTool} 同源；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4 + settlement.cities().size());
      types.add(SET_POPULATION_TYPE);
      for (int i = 0; i < settlement.cities().size(); i++) {
        types.add(CREATE_CITY_TYPE);
      }
      types.add(SEED_GROUPS_TYPE);
      if (params.includeEconomy()) {
        types.add(SEED_ECONOMY_TYPE);
      }
      if (params.includeActors()) {
        types.add(SEED_ACTOR_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** 等级直方图（固定序，只列出现过的档）。 */
    Map<String, Integer> tierHistogram() {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (String tier : TIER_ORDER) {
        counts.put(tier, 0);
      }
      for (PlannedCity city : settlement.cities()) {
        counts.merge(city.tier(), 1, Integer::sum);
      }
      Map<String, Integer> histogram = new LinkedHashMap<>();
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        if (entry.getValue() > 0) {
          histogram.put(entry.getKey(), entry.getValue());
        }
      }
      return histogram;
    }

    /** 首都 = 城市表里与 {@code capital.name} 同名的那一座；无首都 / 名字对不上 ⇒ 空。 */
    Optional<PlannedCity> capitalCity() {
      String capitalName = params.capital().map(CapitalAnchor::name).orElse(null);
      if (capitalName == null) {
        return Optional.empty();
      }
      for (PlannedCity city : settlement.cities()) {
        if (capitalName.equals(city.name())) {
          return Optional.of(city);
        }
      }
      return Optional.empty();
    }

    /** 最大城（空表 ⇒ 空）。 */
    Optional<PlannedCity> largestCity() {
      return settlement.cities().stream().max(Comparator.comparingLong(PlannedCity::population));
    }
  }

  /**
   * ★★ preview / apply 共用的唯一推导入口：读 base state → clean gate → 生成聚落/批次/经济 → {@link Plan}。任何前置不满足都抛
   * {@link IllegalArgumentException}（工具折 {@code BAD_REQUEST}，零 revision）。
   *
   * @param state 只读 base state（preview = head 或给定 revision；apply = expectedRevision）
   * @param mapId 世界 map 称谓
   * @param params 已解析的 GM 参数
   */
  static Derivation derive(SimulationState state, String mapId, Params params) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(params, "params");
    GameMap map = ToolSupport.gameMap(state);
    Region region = requireRegion(map, params.regionId());
    CleanGate cleanGate = inspectCleanGate(state, region);
    List<String> warnings = warnings(state, params);
    if (!cleanGate.clean()) {
      return new Derivation(Optional.empty(), cleanGate, warnings);
    }
    // ★ 低人口 / 零人口：economy/actor 播种（class-first 阶层池 + 家户账）需要人口。
    //   先判最直白的一档，保证拒因里带"可关开关"的指路；更细的"有地无家户"由 EconomySeeder 自己的
    //   fail-closed 前置兜住（见下），这里不写第二份阈值推导。
    if ((params.includeEconomy() || params.includeActors()) && params.totalPopulation() == 0L) {
      throw lowPopulation("总人口为 0");
    }
    long tick = state.meta().timestamp().tick();
    SettlementRequest request =
        new SettlementRequest(
            region.id(),
            region.name(),
            params.totalPopulation(),
            params.urbanizationRate(),
            params.agrarianSurplusRate(),
            params.commercialIntegration(),
            params.politicalCentralization(),
            params.seed(),
            params.capital(),
            region.hexes(),
            params.documentedNames());
    SettlementPlan settlement =
        SettlementGenerator.generate(request, TerrainView.of(map), SettlementParams.defaults());
    long ruralTotal = sumRural(settlement);
    List<PopulationGroup> groups = PopulationSeeder.groups(settlement, tick);
    Optional<EconomySeeder.Seed> economySeed = Optional.empty();
    if (params.includeEconomy() || params.includeActors()) {
      if (ruralTotal == 0L) {
        throw lowPopulation("农村人口为 0（全部人口在城镇），class-first 没有农村家户承载土地/农具");
      }
      try {
        // ★ 一次算出：economy.Seed 的 entries/markets 与 actor.Seed 的库存/货币/经营者读的是同一份。
        economySeed = Optional.of(EconomySeeder.plan(mapId, groups, map));
      } catch (IllegalStateException e) {
        // ★ planClassFirst 的"有地无家户"前置：低人口（各阶层切分后某土地/农具持有位置无人）会在这里响亮抛。
        //   折成 BAD_REQUEST 并附可用的降级开关；不静默、也不把用户输入问题伪装成内部故障。
        throw lowPopulation("class-first 无法建池：" + e.getMessage());
      }
    }
    Plan plan =
        new Plan(
            mapId,
            region,
            params,
            request,
            settlement,
            groups,
            economySeed,
            tick,
            region.hexes().size());
    return new Derivation(Optional.of(plan), cleanGate, warnings);
  }

  /** 低人口 / 零人口的具名拒因（恒带"可关开关"的可用降级路径）。 */
  private static IllegalArgumentException lowPopulation(String cause) {
    return new IllegalArgumentException(
        "低人口/零人口无法播种 economy/actor："
            + cause
            + "；可显式 includeEconomy=false、includeActors=false 只播种人口与城市后重试");
  }

  /** 真档 region（**不是**配置里的 nation）：它的 {@code hexes()} 是生成器与 clean gate 的格集来源。 */
  private static Region requireRegion(GameMap map, String regionId) {
    Region region = map.regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    return region;
  }

  /**
   * ★★ clean gate：目标 Region 的格集内是否存在 social / actor / economy 的既有记录。只读 {@code state}，不写任何东西。
   *
   * <p>★ 逐格经济只认 {@code industries}（按 {@link IndustryHexKeys} 的 id 语法）与 {@code markets}（格键）——世界级的
   * {@code classFirst} 不在此列，见类注。
   */
  private static CleanGate inspectCleanGate(SimulationState state, Region region) {
    Set<HexCoord> hexes = region.hexes();
    List<Hit> hits = new ArrayList<>();
    SocialData social = ToolSupport.socialData(state);
    collect(
        hits,
        "social.population",
        social.populations().keySet(),
        hexes::contains,
        HexCoord::toString);
    collect(
        hits,
        "social.group",
        social.groups().values(),
        group -> hexes.contains(group.residence()),
        group -> group.id().value());
    collect(
        hits,
        "social.city.at",
        social.cities().values(),
        city -> hexes.contains(city.at()),
        city -> city.id().value());
    collect(
        hits,
        "social.city.region",
        social.cities().values(),
        city -> city.region().filter(region.id()::equals).isPresent(),
        city -> city.id().value());
    ActorData actors = ApiViews.actorData(state);
    collect(
        hits,
        "actor.account",
        actors.accounts().keySet(),
        account -> hexes.contains(account.location()),
        Object::toString);
    EconomyData economy = ToolSupport.economyData(state);
    Set<String> regionHexKeys = new HashSet<>();
    for (HexCoord hex : hexes) {
      regionHexKeys.add(IndustryHexKeys.hexKey(hex.q(), hex.r()));
    }
    collect(
        hits,
        "economy.industry",
        economy.industries().keySet(),
        industryId ->
            IndustryHexKeys.hexKeyOf(industryId).filter(regionHexKeys::contains).isPresent(),
        industryId -> industryId.value());
    collect(
        hits, "economy.market", economy.markets().keySet(), hexes::contains, HexCoord::toString);
    return new CleanGate(hits);
  }

  /** 逐类收集命中（全量计数 + 前 {@value #MAX_HIT_SAMPLES} 条示例；样本排序保证可复现）。 */
  private static <T> void collect(
      List<Hit> hits,
      String kind,
      Iterable<T> items,
      Predicate<T> inRegion,
      Function<T, String> sample) {
    long count = 0L;
    List<String> samples = new ArrayList<>(MAX_HIT_SAMPLES);
    for (T item : items) {
      if (!inRegion.test(item)) {
        continue;
      }
      count++;
      if (samples.size() < MAX_HIT_SAMPLES) {
        samples.add(sample.apply(item));
      }
    }
    if (count == 0L) {
      return;
    }
    samples.sort(Comparator.naturalOrder());
    hits.add(new Hit(kind, count, samples));
  }

  /** preview / apply 都要给的世界级与口径警告（不阻断落盘；阻断的只有 clean gate 的 Region 命中）。 */
  private static List<String> warnings(SimulationState state, Params params) {
    EconomyData economy = ToolSupport.economyData(state);
    List<String> warnings = new ArrayList<>();
    if (!economy.classFirst().isEmpty()) {
      warnings.add(
          "economy.classFirst 已是世界级非空状态（共享世界状态，不作为 Region 命中；本工具不清空它）"
              + "——若目标 Region 是空白可继续，若与目标区域有关请另行评估");
    }
    if (params.includeActors() && !params.includeEconomy()) {
      warnings.add(
          "includeActors=true 而 includeEconomy=false：家户/经营者开缸账仍由同一次 class-first 推导算出，"
              + "但 economy.Seed 不会落盘 ⇒ actor 账与 economy 切片可能不同步（建议两者同开）");
    }
    return List.copyOf(warnings);
  }

  /** Σ 逐格农村人口。 */
  private static long sumRural(SettlementPlan settlement) {
    long total = 0L;
    for (long value : settlement.ruralPopulation().values()) {
      total += value;
    }
    return total;
  }
}
