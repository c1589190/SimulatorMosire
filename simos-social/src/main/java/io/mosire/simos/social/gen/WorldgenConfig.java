package io.mosire.simos.social.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 冻结输入 {@code config/worldgen/*.json} 的**生产版加载器**：只吃一个 {@link Path}（不写死仓库布局），解析出三国 {@link
 * NationSetup} 与共用的 {@link SettlementParams}。
 *
 * <p>★ **单一解析口**：本类与测试夹具 {@code RealNations} 读的是同一份 JSON，且**逐字段照抄** {@code defaults}（而非复用 {@link
 * SettlementParams#defaults()} 的字面量）—— 这样"改 JSON 不用重编译"这条承诺才成立。三条**配置里没有、由实现定**的启发式 （{@code
 * _derived}）仍以常量落在本类，见下方常量。
 *
 * <p>★ **randomization 块**（默认关闭）：顶层声明抖动/夹紧；三国可用**同名键整体覆盖**（缺省继承）。{@code enabled=false} ⇒ 各标量取
 * {@link ValueRange#exact(double)}（文档值），与开启之前逐字节相同。见 {@link ValueRange}。
 *
 * <p>★ **fail-closed**：文件不存在 / JSON 非法 / 缺 {@code defaults}/{@code nations}/{@code armKits} / 未知
 * regionId / 某个键形状不对 ⇒ 抛 {@link IllegalArgumentException}（带可读中文原因），**不兜底、不猜默认**。
 *
 * <p>★ **不引入新依赖**：走 {@link SimosObjectMapper#create()}（simos-util 的唯一点）解析成 {@code JsonNode} 树 ——
 * 树式读取天然忽略 {@code _note}/{@code _source} 之类的注释键，不触碰领域类型。
 *
 * <p>★ 本类不加任何 {@code isXxx()} 实例方法（见 {@link PlannedCity} 类注释）。
 */
public final class WorldgenConfig {

  // ★ 配置里没有、由本实现定的启发式（与 SettlementParams 的 _derived 说明同源）：
  private static final int RIVER_JUNCTION_EDGES_AT_HEX = 3;
  private static final int MOUNTAIN_PASS_MOVE_COST = 3;
  private static final int MOUNTAIN_PASS_NEIGHBOR_MOVE_COST = 2;

  // ★ ZipfShape 需要的累计档位分别取前 1 / 2 / 3 档的 count 之和（对应 1 / 3 / 8）。
  private static final int ZIPF_RANK_CAP_COUNT = 3;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final List<NationSetup> nations;
  private final Map<String, NationSetup> byRegionId;

  private WorldgenConfig(List<NationSetup> nations) {
    this.nations = Collections.unmodifiableList(nations);
    Map<String, NationSetup> index = new LinkedHashMap<>();
    for (NationSetup nation : nations) {
      if (index.put(nation.region().value(), nation) != null) {
        throw new IllegalArgumentException("nations 里 regionId 重复: " + nation.region().value());
      }
    }
    this.byRegionId = Collections.unmodifiableMap(index);
  }

  /**
   * 读文件并解析。
   *
   * @param file 参数文件路径（任意布局；调用方给路径，本类不拼仓库相对路径）
   * @throws IllegalArgumentException 见类注释的 fail-closed 清单
   */
  public static WorldgenConfig load(Path file) {
    if (file == null) {
      throw new IllegalArgumentException("参数文件路径不得为 null");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("世界生成参数文件不存在或不是普通文件: " + file.toAbsolutePath());
    }
    String json;
    try {
      json = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("世界生成参数文件读失败: " + file.toAbsolutePath(), e);
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (IOException e) {
      throw new IllegalArgumentException("世界生成参数文件 JSON 非法: " + file.toAbsolutePath(), e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("世界生成参数文件顶层必须是 JSON 对象: " + file.toAbsolutePath());
    }
    return parse(root, file);
  }

  private static WorldgenConfig parse(JsonNode root, Path file) {
    String where = file.toAbsolutePath().toString();
    JsonNode defaults = requireObject(root, "defaults", where);
    SettlementParams params = parseParams(defaults, root, where);
    JsonNode armKitsNode = requireObject(root, "armKits", where);
    Map<String, Map<String, Integer>> armKits = parseArmKits(armKitsNode, where);
    Randomization topLevel = parseRandomization(root.path("randomization"), where, "randomization");

    JsonNode nationsNode = root.path("nations");
    if (!nationsNode.isArray() || nationsNode.isEmpty()) {
      throw new IllegalArgumentException(where + "：缺少 nations（必须是非空数组）");
    }
    List<NationSetup> nations = new ArrayList<>(nationsNode.size());
    for (JsonNode nationNode : nationsNode) {
      if (!nationNode.isObject()) {
        throw new IllegalArgumentException(where + "：nations 的每一项必须是对象");
      }
      nations.add(parseNation(nationNode, params, armKits, topLevel, where));
    }
    return new WorldgenConfig(nations);
  }

  // ── 国家 ──

  private static NationSetup parseNation(
      JsonNode nation,
      SettlementParams params,
      Map<String, Map<String, Integer>> armKits,
      Randomization topLevel,
      String where) {
    String regionText = requireText(nation, "regionId", where);
    String displayName = requireText(nation, "displayName", where);
    Randomization randomization =
        nation.has("randomization")
            ? parseRandomization(
                nation.path("randomization"), where, "nations[" + regionText + "].randomization")
            : topLevel;

    ValueRange population =
        range(requireDouble(nation, "population", where), "population", randomization);
    ValueRange urbanization =
        range(requireDouble(nation, "urbanizationRate", where), "urbanizationRate", randomization);
    ValueRange agrarian =
        range(
            requireDouble(nation, "agrarianSurplusRate", where),
            "agrarianSurplusRate",
            randomization);
    ValueRange commercial =
        range(
            requireDouble(nation, "commercialIntegration", where),
            "commercialIntegration",
            randomization);
    ValueRange centralization =
        range(
            requireDouble(nation, "politicalCentralization", where),
            "politicalCentralization",
            randomization);

    Optional<NationSetup.CapitalSeed> capital =
        parseCapital(nation.path("capital"), randomization, where);

    List<String> documented = new ArrayList<>();
    JsonNode places = nation.path("documentedPlaceNames");
    if (!places.isMissingNode() && !places.isNull()) {
      if (!places.isArray()) {
        throw new IllegalArgumentException(
            where + "：" + regionText + " 的 documentedPlaceNames 必须是数组");
      }
      for (JsonNode place : places) {
        documented.add(requireText(place, "name", where + "/" + regionText));
      }
    }

    ArmyPlan army = parseArmy(nation, armKits, where, regionText);

    return new NationSetup(
        RegionId.parse(regionText),
        displayName,
        population,
        urbanization,
        agrarian,
        commercial,
        centralization,
        nation.path("seed").asLong(),
        capital,
        documented,
        army,
        params,
        Set.of());
  }

  private static Optional<NationSetup.CapitalSeed> parseCapital(
      JsonNode capital, Randomization randomization, String where) {
    if (capital.isMissingNode() || capital.isNull()) {
      return Optional.empty();
    }
    String name = requireText(capital, "name", where);
    JsonNode target = capital.path("targetPopulation");
    if (target.isMissingNode() || target.isNull()) {
      return Optional.of(new NationSetup.CapitalSeed(name, Optional.empty()));
    }
    if (!target.isNumber()) {
      throw new IllegalArgumentException(where + "：首都 " + name + " 的 targetPopulation 必须是数字或 null");
    }
    return Optional.of(
        new NationSetup.CapitalSeed(
            name, Optional.of(range(target.asDouble(), "capitalTargetPopulation", randomization))));
  }

  private static ArmyPlan parseArmy(
      JsonNode nation, Map<String, Map<String, Integer>> armKits, String where, String regionText) {
    JsonNode army = nation.path("army");
    if (!army.isObject()) {
      throw new IllegalArgumentException(where + "：" + regionText + " 缺少 army 对象");
    }
    int peacetime = requireInt(army, "peacetime", where);
    int mobilization = requireInt(army, "mobilization", where);
    JsonNode establishmentNode = requireObject(army, "establishment", where);
    Map<String, Integer> establishment = new LinkedHashMap<>();
    Iterator<String> fields = establishmentNode.fieldNames();
    while (fields.hasNext()) {
      String unit = fields.next();
      if (unit.startsWith("_")) {
        continue;
      }
      JsonNode value = establishmentNode.get(unit);
      if (!value.canConvertToInt()) {
        throw new IllegalArgumentException(
            where + "：" + regionText + " 的 establishment." + unit + " 必须是整数");
      }
      establishment.put(unit, value.asInt());
    }
    return new ArmyPlan(peacetime, mobilization, establishment, armKits);
  }

  private static Map<String, Map<String, Integer>> parseArmKits(
      JsonNode armKitsNode, String where) {
    Map<String, Map<String, Integer>> kits = new LinkedHashMap<>();
    Iterator<String> units = armKitsNode.fieldNames();
    while (units.hasNext()) {
      String unit = units.next();
      if (unit.startsWith("_")) {
        continue;
      }
      JsonNode kit = armKitsNode.get(unit);
      if (!kit.isObject()) {
        throw new IllegalArgumentException(where + "：armKits." + unit + " 必须是对象");
      }
      Map<String, Integer> equipment = new LinkedHashMap<>();
      Iterator<String> items = kit.fieldNames();
      while (items.hasNext()) {
        String item = items.next();
        if (item.startsWith("_")) {
          continue;
        }
        JsonNode amount = kit.get(item);
        if (!amount.canConvertToInt()) {
          throw new IllegalArgumentException(where + "：armKits." + unit + "." + item + " 必须是整数");
        }
        equipment.put(item, amount.asInt());
      }
      kits.put(unit, equipment);
    }
    return kits;
  }

  // ── randomization ──

  /** 解析后的 randomization 块：开关 + 各标量抖动比例 + 各标量硬夹紧区间。 */
  private record Randomization(
      boolean enabled, Map<String, Double> jitterPct, Map<String, double[]> clamp) {}

  private static Randomization parseRandomization(JsonNode node, String where, String field) {
    if (node.isMissingNode() || node.isNull()) {
      return new Randomization(false, Map.of(), Map.of());
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(where + "：" + field + " 必须是对象");
    }
    boolean enabled = node.path("enabled").asBoolean(false);
    Map<String, Double> jitter = new LinkedHashMap<>();
    JsonNode jitterNode = node.path("jitterPct");
    if (!jitterNode.isMissingNode() && !jitterNode.isNull()) {
      if (!jitterNode.isObject()) {
        throw new IllegalArgumentException(where + "：" + field + ".jitterPct 必须是对象");
      }
      Iterator<String> names = jitterNode.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (name.startsWith("_")) {
          continue;
        }
        JsonNode value = jitterNode.get(name);
        if (!value.isNumber() || value.asDouble() < 0.0) {
          throw new IllegalArgumentException(
              where + "：" + field + ".jitterPct." + name + " 必须是有限非负数");
        }
        jitter.put(name, value.asDouble());
      }
    }
    Map<String, double[]> clamp = new LinkedHashMap<>();
    JsonNode clampNode = node.path("clamp");
    if (!clampNode.isMissingNode() && !clampNode.isNull()) {
      if (!clampNode.isObject()) {
        throw new IllegalArgumentException(where + "：" + field + ".clamp 必须是对象");
      }
      Iterator<String> names = clampNode.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (name.startsWith("_")) {
          continue;
        }
        JsonNode pair = clampNode.get(name);
        if (!pair.isArray()
            || pair.size() != 2
            || !pair.get(0).isNumber()
            || !pair.get(1).isNumber()) {
          throw new IllegalArgumentException(
              where + "：" + field + ".clamp." + name + " 必须是 [下界, 上界] 两个数字");
        }
        double low = pair.get(0).asDouble();
        double high = pair.get(1).asDouble();
        if (low > high) {
          throw new IllegalArgumentException(
              where + "：" + field + ".clamp." + name + " 的下界大于上界: " + low + " > " + high);
        }
        clamp.put(name, new double[] {low, high});
      }
    }
    return new Randomization(enabled, jitter, clamp);
  }

  private static ValueRange range(double documented, String key, Randomization randomization) {
    if (!randomization.enabled()) {
      return ValueRange.exact(documented);
    }
    double jitter = randomization.jitterPct().getOrDefault(key, 0.0);
    double[] clamp = randomization.clamp().get(key);
    double low = clamp == null ? Double.NEGATIVE_INFINITY : clamp[0];
    double high = clamp == null ? Double.POSITIVE_INFINITY : clamp[1];
    return new ValueRange(documented, jitter, low, high);
  }

  // ── SettlementParams ──

  private static SettlementParams parseParams(JsonNode defaults, JsonNode root, String where) {
    Map<String, Double> rates = new LinkedHashMap<>();
    JsonNode ratesNode = requireObject(defaults, "surplusRatePerTerrain", where);
    Iterator<String> rateKeys = ratesNode.fieldNames();
    while (rateKeys.hasNext()) {
      String key = rateKeys.next();
      if (key.startsWith("_")) {
        continue;
      }
      JsonNode value = ratesNode.get(key);
      if (!value.isNumber()) {
        throw new IllegalArgumentException(where + "：surplusRatePerTerrain." + key + " 必须是数字");
      }
      rates.put(key, value.asDouble());
    }

    JsonNode riverNode = requireObject(defaults, "riverMultiplier", where);
    SettlementParams.RiverMultipliers river =
        new SettlementParams.RiverMultipliers(
            requireDouble(riverNode, "ordinary", where),
            requireDouble(riverNode, "navigable", where),
            requireInt(riverNode, "navigableRiverEdgesAtHex", where));

    JsonNode weightsNode = requireObject(defaults, "candidateWeights", where);
    SettlementParams.CandidateWeights weights =
        new SettlementParams.CandidateWeights(
            requireDouble(weightsNode, "surplus", where),
            requireDouble(weightsNode, "transport", where),
            requireDouble(weightsNode, "historical", where));

    JsonNode bonusNode = requireObject(defaults, "transportBonus", where);
    SettlementParams.TransportBonuses bonuses =
        new SettlementParams.TransportBonuses(
            requireDouble(bonusNode, "riverJunction", where),
            requireDouble(bonusNode, "navigableRiver", where),
            requireDouble(bonusNode, "river", where),
            requireDouble(bonusNode, "coastal", where),
            requireDouble(bonusNode, "mountainPass", where),
            requireDouble(bonusNode, "plain", where),
            requireDouble(bonusNode, "deepMountain", where),
            requireDouble(bonusNode, "barren", where),
            RIVER_JUNCTION_EDGES_AT_HEX,
            MOUNTAIN_PASS_MOVE_COST,
            MOUNTAIN_PASS_NEIGHBOR_MOVE_COST);

    JsonNode radiiNode = requireObject(defaults, "tierRadiiHex", where);
    Map<String, SettlementParams.TierRadii.Radius> radii = new LinkedHashMap<>();
    for (String tier :
        List.of(
            PlannedCity.TIER_MARKET_TOWN,
            PlannedCity.TIER_TOWN,
            PlannedCity.TIER_CITY,
            PlannedCity.TIER_MAJOR_CITY)) {
      JsonNode pair = radiiNode.get(tier);
      if (pair == null || !pair.isArray() || pair.size() != 2) {
        throw new IllegalArgumentException(where + "：tierRadiiHex 缺少等级 " + tier + "（必须是 [下界, 上界]）");
      }
      radii.put(
          tier, new SettlementParams.TierRadii.Radius(pair.get(0).asInt(), pair.get(1).asInt()));
    }

    JsonNode rarityNode = requireObject(defaults, "rarityPenalty", where);
    SettlementParams.RarityPenalty rarity =
        new SettlementParams.RarityPenalty(
            requireLong(rarityNode, "threshold", where),
            requireDouble(rarityNode, "factor", where));

    SettlementParams.ZipfShape zipf = parseZipf(requireObject(defaults, "zipfShape", where), where);

    JsonNode upgradeNode = requireObject(defaults, "tierUpgradeRate", where);
    SettlementParams.TierUpgradeRates upgrades =
        new SettlementParams.TierUpgradeRates(
            requireDouble(upgradeNode, "MarketTownToTown", where),
            requireDouble(upgradeNode, "TownToCity", where),
            requireDouble(upgradeNode, "CityToMajorCity", where));

    return new SettlementParams(
        rates,
        river,
        requireDouble(defaults, "coastalMultiplier", where),
        requireDouble(defaults, "noiseAmplitude", where),
        weights,
        bonuses,
        requireInt(defaults, "minMarketTownDistanceHex", where),
        requireDouble(defaults, "influenceExponent", where),
        rarity,
        zipf,
        upgrades,
        new SettlementParams.TierRadii(radii),
        parseNameStyle(requireObject(root, "cityNameStyle", where), where));
  }

  /** Zipf：{@code totalCityCount} = 各档 count 之和；三个等级上限 = 前 1/2/3 档 count 的累计（1/3/8）。 */
  private static SettlementParams.ZipfShape parseZipf(JsonNode zipfNode, String where) {
    long samplePopulation = requireLong(zipfNode, "samplePopulation", where);
    JsonNode ranks = zipfNode.path("ranks");
    if (!ranks.isArray() || ranks.size() < ZIPF_RANK_CAP_COUNT) {
      throw new IllegalArgumentException(
          where + "：zipfShape.ranks 至少要有 " + ZIPF_RANK_CAP_COUNT + " 档（用来推 1/3/8 的累计档位）");
    }
    long total = 0L;
    long cumulative = 0L;
    long majorCap = 0L;
    long cityCap = 0L;
    long townCap = 0L;
    for (int i = 0; i < ranks.size(); i++) {
      long count = requireLong(ranks.get(i), "count", where);
      total += count;
      if (i < ZIPF_RANK_CAP_COUNT) {
        cumulative += count;
        if (i == 0) {
          majorCap = cumulative;
        } else if (i == 1) {
          cityCap = cumulative;
        } else {
          townCap = cumulative;
        }
      }
    }
    return new SettlementParams.ZipfShape(samplePopulation, total, majorCap, cityCap, townCap);
  }

  /** 城市名词表：取 {@code cityNameStyle} 下**第一个**非注释子对象（配置当前只有 germanic 一份）。 */
  private static SettlementParams.NameStyle parseNameStyle(JsonNode styleNode, String where) {
    JsonNode style = null;
    Iterator<String> names = styleNode.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (name.startsWith("_")) {
        continue;
      }
      JsonNode candidate = styleNode.get(name);
      if (candidate.isObject()) {
        style = candidate;
        break;
      }
    }
    if (style == null) {
      throw new IllegalArgumentException(where + "：cityNameStyle 下没有任何词表对象（stems/suffixes）");
    }
    return new SettlementParams.NameStyle(
        parseStrings(requireArray(style, "stems", where), where),
        parseStrings(requireArray(style, "suffixes", where), where));
  }

  private static List<String> parseStrings(JsonNode array, String where) {
    List<String> values = new ArrayList<>(array.size());
    for (JsonNode element : array) {
      if (!element.isTextual()) {
        throw new IllegalArgumentException(where + "：词表项必须是字符串");
      }
      values.add(element.asText());
    }
    return values;
  }

  // ── 查询 ──

  /** 三国（顺序即 JSON 里 nations 的顺序）；每项**不含格集**，接线方用 {@link NationSetup#withHexes} 补。 */
  public List<NationSetup> nations() {
    return nations;
  }

  /** 按 regionId 取国家。 */
  public NationSetup byRegionId(String regionId) {
    if (regionId == null) {
      throw new IllegalArgumentException("regionId 不得为 null");
    }
    NationSetup nation = byRegionId.get(regionId);
    if (nation == null) {
      throw new IllegalArgumentException(
          "配置里没有这个 regionId: " + regionId + "（有的是 " + byRegionId.keySet() + "）");
    }
    return nation;
  }

  // ── 读键工具（fail-closed）──

  private static JsonNode requireObject(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.isObject()) {
      throw new IllegalArgumentException(where + "：缺少对象 " + field);
    }
    return node;
  }

  private static JsonNode requireArray(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.isArray()) {
      throw new IllegalArgumentException(where + "：缺少数组 " + field);
    }
    return node;
  }

  private static String requireText(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(where + "：缺少非空字符串 " + field);
    }
    return node.asText();
  }

  private static double requireDouble(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.isNumber()) {
      throw new IllegalArgumentException(where + "：缺少数字 " + field);
    }
    return node.asDouble();
  }

  private static long requireLong(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.isIntegralNumber()) {
      throw new IllegalArgumentException(where + "：缺少整数 " + field);
    }
    return node.asLong();
  }

  private static int requireInt(JsonNode parent, String field, String where) {
    JsonNode node = parent.path(field);
    if (!node.canConvertToInt()) {
      throw new IllegalArgumentException(where + "：缺少整数 " + field);
    }
    return node.asInt();
  }
}
