package io.mosire.simos.social.gen;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 纯函数式的中世纪聚落/人口生成器：一个国家/区域的格集 + 地形视图 ⇒ 逐格农村人口 + 城市表 + 审计量。
 *
 * <p>★ **确定性铁律**：所有随机量都是 {@code splitmix64(seed ⊕ 用途盐, q, r)} 的坐标哈希 —— **绝不**依赖任何 {@code
 * Map}/{@code Set} 的迭代序，也**不用** {@code new Random(seed)} 顺着遍历顺序抽。进函数第一件事就是把 {@code hexes} 排序成
 * {@code List<HexCoord>}，之后一切下标都从这份有序表出发；这样"同一 request 换一种插入顺序"必然逐字段相等（有用例）。
 *
 * <p>★ 十步算法（逐条落地，括号内是配置键）：
 *
 * <ol>
 *   <li>逐格权重 {@code w = terrainCapacity × (1 + noise)}（{@code surplusRatePerTerrain}、{@code
 *       noiseAmplitude}）； 承载率为 0 的格（{@code ocean}）不分配农村人口，且**不出现在** {@code ruralPopulation} 的键集里。
 *   <li>{@code urbanTotal = round(total × 城市化率)}、{@code ruralTotal = total − urbanTotal}；按 {@code
 *       w} **最大余数法** 取整，保证 {@code Σ rural == ruralTotal} **严格**成立。
 *   <li>逐格 {@code ruralCapacity}（自身维持口径 = 落在该格的农村人口）与 {@code surplusPotential = ruralCapacity ×
 *       地形剩余率 × agrarianSurplusRate × 河流乘数 × 沿海乘数}。
 *   <li>{@code candidateScore = 0.45·normalized(surplus) + 0.35·normalized(transport) +
 *       0.20·historical} （{@code candidateWeights}）——★ **三项各自先归一到
 *       [0,1]**：三项量纲不同（剩余是"人"、交通是指数、历史是均匀随机）， 不归一的话"人"那一项会把另两项淹得看不见，权重也就名存实亡。
 *   <li>三级抽样：按 {@code tierUpgradeRate} 从高分往下升级 MarketTown → Town → City → MajorCity，升级率乘 {@code
 *       commercialIntegration × politicalCentralization} 的调制；**再按 Zipf 累计档位封顶**（城市总数上界 = 按国家人口等比缩放的
 *       163， 等级上限 = 累计档位 1/3/8 × 同一缩放）。
 *   <li>最小距离抑制：两个市场镇之间 {@code hexDistance >= minMarketTownDistanceHex}，低分让位给高分（贪心、按分数降序）。
 *   <li>腹地竞争：{@code Influence = W / cost^k}，★ {@code cost = hexDistance × moveCost(目标格)}（**不是纯 hex
 *       距离**：沿河 3 格可能比翻山 1 格容易），{@code k = influenceExponent}；★ {@code W = candidateScore ×
 *       politicalMultiplier} —— 首都的政治权重让它的**影响半径**天然更大（否则高等级城只是"分得多"，而抢不到腹地，首都便当不成最大城）。 ★ 归属只在该城
 *       tier 对应的**市场半径上界**（{@code tierRadiiHex[tier][1]}）内竞争，**超出所有城半径的格不归属任何城** —— 不计入任何 {@code
 *       catchmentHexes}、不贡献 {@code localSurplus}（半径缺失即抛，fail-closed）。
 *   <li>{@code urbanPopulation = localSurplus × tradeMultiplier ×
 *       politicalMultiplier}；政治乘数由中央度派生，**首都吃大头** （这解释首都为何能远超本地农业承载）。
 *   <li>稀有惩罚（{@code rarityPenalty}，**只跑一轮，不迭代**；★ **首都豁免**——首都人口由政治决定，不由市场稀缺性决定）后 **归一化到
 *       urbanTotal**（最大余数法）；若 {@code Σ 城市承载 < urbanTotal}，在 {@link SettlementPlan#shortfall()}
 *       显式报出缺口，**绝不静默调低 total**。
 *   <li>出口**断言三条不变量**：{@code Σ rural == ruralTotal}、{@code Σ city.population == urbanTotal}、{@code
 *       ruralTotal + urbanTotal == total}。
 * </ol>
 *
 * <p>★ **首都硬目标**（{@link CapitalAnchor#targetPopulation()}）：有值时首都拿这个数、其余城市分剩下的 {@code urbanTotal}；
 * **目标 &gt; urbanTotal 即抛**（输入自相矛盾，fail-closed，不静默夹紧）。唯一例外：全区域只有首都一座城时"硬目标"与"{@code Σ ==
 * urbanTotal}"不能同时成立，此时**不变量优先**、首都吸收全部城市人口，并在该城 {@code justification} 里写明。
 *
 * <p>★ **河流启发式的诚实说明**：{@code riverMultiplier} 把"同格 river 边 ≥ {@code
 * navigableRiverEdgesAtHex}（2）"当作**可通航**。真档实测 **248 个河边格里 230 个达到该阈值** ——
 * 这个判据会把绝大多数河边格判成"可通航"，**它没有水文学依据**，只是配置给的一个便宜启发式。见交付报告。
 *
 * <p>★ 本类无可实例化构造器（全静态），不持有状态 ⇒ 天然纯函数、可并发调用。
 */
public final class SettlementGenerator {

  // ★ 用途盐：同一坐标在不同步骤必须抽到互不相关的数（否则"噪声大的格恰好也高分"这类伪相关会系统性偏置结果）。
  private static final long SALT_NOISE = 0x51L;
  private static final long SALT_HISTORY = 0x52L;
  private static final long SALT_TIER_TOWN = 0x53L;
  private static final long SALT_TIER_CITY = 0x54L;
  private static final long SALT_TIER_MAJOR = 0x55L;
  private static final long SALT_NAME = 0x56L;

  /** 首都的政治乘数份额（中央度 × 本值）。 */
  private static final double CAPITAL_POLITICAL_SHARE = 1.0;

  /** 非首都的政治乘数份额。 */
  private static final double URBAN_POLITICAL_SHARE = 0.1;

  private SettlementGenerator() {}

  /** 用冻结输入的默认参数生成（{@link SettlementParams#defaults()}）。 */
  public static SettlementPlan generate(SettlementRequest request, TerrainView terrain) {
    return generate(request, terrain, SettlementParams.defaults());
  }

  /**
   * 主入口。**纯函数**：同 request + 同 params + 同 terrain 恒得逐字段相同的结果，与 {@code request.hexes()} 的迭代序无关。
   *
   * @throws IllegalArgumentException 区域里没有一格有承载力、或首都硬目标超过城市总人口
   * @throws IllegalStateException 出口不变量被破坏（理论上不可达，宁抛不静默）
   */
  public static SettlementPlan generate(
      SettlementRequest request, TerrainView terrain, SettlementParams params) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(terrain, "terrain");
    Objects.requireNonNull(params, "params");

    List<HexCoord> hexes = new ArrayList<>(request.hexes());
    Collections.sort(hexes); // ★ 迭代序无关的第一道闸：之后一切下标都从这份有序表出发
    int n = hexes.size();

    // ── 步骤 1：逐格权重（坐标哈希噪声）──
    String[] keys = new String[n];
    double[] weights = new double[n];
    boolean[] arable = new boolean[n];
    int arableCount = 0;
    for (int i = 0; i < n; i++) {
      HexCoord hex = hexes.get(i);
      keys[i] = terrain.terrainKey(hex);
      double capacity = capacityOf(keys[i], params);
      if (capacity <= 0.0) {
        continue; // 海洋/零产：权重 0、不分配农村人口、不进 populations
      }
      arable[i] = true;
      arableCount++;
      double noise =
          params.noiseAmplitude() * (2.0 * unit(hash(request.seed(), hex, SALT_NOISE)) - 1.0);
      weights[i] = Math.max(0.0, capacity * (1.0 + noise));
    }
    if (arableCount == 0) {
      throw new IllegalArgumentException(
          "区域 " + request.displayName() + " 里没有一格有承载力（全为 ocean 或零产地形），无法分配人口");
    }

    // ── 步骤 2：城乡切分 + 最大余数法 ──
    long total = request.totalPopulation();
    long urbanTotal = Math.round(total * request.urbanizationRate());
    long ruralTotal = total - urbanTotal;
    long[] rural = largestRemainder(weights, ruralTotal);

    // ── 步骤 3：逐格 ruralCapacity（= 落在该格的农村人口）与 surplusPotential ──
    double[] surplus = new double[n];
    double[] transport = new double[n];
    for (int i = 0; i < n; i++) {
      if (!arable[i]) {
        continue;
      }
      HexCoord hex = hexes.get(i);
      double rate = capacityOf(keys[i], params) * request.agrarianSurplusRate();
      double riverMul = riverMultiplier(terrain.riverEdgesAt(hex), params.river());
      double coastMul = terrain.coastal(hex) ? params.coastalMultiplier() : 1.0;
      surplus[i] = rural[i] * rate * riverMul * coastMul;
      transport[i] = transportOf(terrain, params, hex, keys[i]);
    }

    // ── 步骤 4：三项归一后的建城候选分 ──
    double maxSurplus = 0.0;
    double maxTransport = 0.0;
    for (int i = 0; i < n; i++) {
      if (!arable[i]) {
        continue;
      }
      maxSurplus = Math.max(maxSurplus, surplus[i]);
      maxTransport = Math.max(maxTransport, transport[i]);
    }
    double[] score = new double[n];
    for (int i = 0; i < n; i++) {
      if (!arable[i]) {
        continue;
      }
      double s = maxSurplus > 0.0 ? surplus[i] / maxSurplus : 0.0;
      double t = maxTransport > 0.0 ? transport[i] / maxTransport : 0.0;
      double historical = unit(hash(request.seed(), hexes.get(i), SALT_HISTORY));
      score[i] =
          params.candidateWeights().surplus() * s
              + params.candidateWeights().transport() * t
              + params.candidateWeights().historical() * historical;
    }

    // ── 步骤 5/6：候选市场镇（高分优先 + 最小距离抑制）──
    List<Integer> order = new ArrayList<>(arableCount);
    for (int i = 0; i < n; i++) {
      if (arable[i]) {
        order.add(i);
      }
    }
    order.sort(
        (a, b) -> {
          int c = Double.compare(score[b], score[a]); // 分数降序
          return c != 0 ? c : Integer.compare(a, b); // 同分按格序（hexes 已排序 ⇒ 下标序 == 格序）
        });
    int cap = cityCountCap(request, params, arableCount);
    int minDistance = params.minMarketTownDistanceHex();
    List<Integer> chosen = new ArrayList<>();
    for (int index : order) {
      if (chosen.size() >= cap) {
        break;
      }
      boolean farEnough = true;
      for (int picked : chosen) {
        if (hexes.get(picked).distanceTo(hexes.get(index)) < minDistance) {
          farEnough = false;
          break;
        }
      }
      if (farEnough) {
        chosen.add(index);
      }
    }
    int m = chosen.size();

    // ── 步骤 5：三级抽样 + Zipf 档位封顶 ──
    boolean hasCapital = request.capital().isPresent();
    String[] tier = new String[m];
    double modulation = request.commercialIntegration() * request.politicalCentralization();
    SettlementParams.TierUpgradeRates rates = params.tierUpgradeRates();
    for (int k = 0; k < m; k++) {
      HexCoord hex = hexes.get(chosen.get(k));
      String value = PlannedCity.TIER_MARKET_TOWN;
      if (roll(request.seed(), hex, SALT_TIER_TOWN)
          < clamp01(rates.marketTownToTown() * modulation)) {
        value = PlannedCity.TIER_TOWN;
        if (roll(request.seed(), hex, SALT_TIER_CITY) < clamp01(rates.townToCity() * modulation)) {
          value = PlannedCity.TIER_CITY;
          if (roll(request.seed(), hex, SALT_TIER_MAJOR)
              < clamp01(rates.cityToMajorCity() * modulation)) {
            value = PlannedCity.TIER_MAJOR_CITY;
          }
        }
      }
      tier[k] = value;
    }
    if (hasCapital) {
      tier[0] = PlannedCity.TIER_MAJOR_CITY; // chosen[0] = 最高分 = 首都落点
    }
    int[] tierCaps = tierCaps(request.totalPopulation(), params.zipfShape(), m);
    demote(tier, PlannedCity.TIER_MAJOR_CITY, tierCaps[0], PlannedCity.TIER_CITY);
    demote(tier, PlannedCity.TIER_CITY, tierCaps[1], PlannedCity.TIER_TOWN);
    demote(tier, PlannedCity.TIER_TOWN, tierCaps[2], PlannedCity.TIER_MARKET_TOWN);

    // ── 政治乘数（步骤 8 用；但腹地竞争（步骤 7）的 W 也吃它 —— 首都的政治权重先于人口决定影响半径）──
    double[] politicalMultiplier = new double[m];
    for (int k = 0; k < m; k++) {
      boolean isCapital = hasCapital && k == 0;
      politicalMultiplier[k] =
          1.0
              + request.politicalCentralization()
                  * (isCapital ? CAPITAL_POLITICAL_SHARE : URBAN_POLITICAL_SHARE);
    }

    // ── 步骤 7：腹地竞争（cost = hexDistance × moveCost(目标格)；W = 候选分 × 政治乘数）──
    // ★ 腹地**有界**：归属只在该城 tier 对应的市场半径**上界**内竞争；**超出所有城半径的格不归属任何城**
    //   （不计入任何 catchmentHexes、不贡献 localSurplus）。半径缺失即抛（fail-closed，不静默当无界）。
    int[] radius = new int[m];
    for (int k = 0; k < m; k++) {
      radius[k] = params.tierRadiiHex().maxHex(tier[k]);
    }
    int[] catchment = new int[m];
    double[] localSurplus = new double[m];
    double exponent = params.influenceExponent();
    for (int i = 0; i < n; i++) {
      if (!arable[i]) {
        continue;
      }
      HexCoord hex = hexes.get(i);
      double cost = terrain.moveCost(hex);
      int best = -1;
      double bestInfluence = -1.0;
      for (int k = 0; k < m; k++) {
        int distance = hex.distanceTo(hexes.get(chosen.get(k)));
        if (distance > radius[k]) {
          continue; // 超出该城的市场半径上界 ⇒ 不参选
        }
        double influence;
        if (distance == 0) {
          influence = Double.POSITIVE_INFINITY; // 本格只可能属于本格的城（最小距离 2 保证唯一）
        } else {
          double weight = Math.max(score[chosen.get(k)], 1e-9) * politicalMultiplier[k];
          influence = weight / Math.pow(distance * cost, exponent);
        }
        if (influence > bestInfluence) {
          bestInfluence = influence;
          best = k;
        }
      }
      if (best < 0) {
        continue; // 半径外：不归属任何城
      }
      catchment[best]++;
      localSurplus[best] += surplus[i];
    }

    // ── 步骤 8：城市人口初算 ──
    double[] raw = new double[m];
    double[] tradeMultiplier = new double[m];
    for (int k = 0; k < m; k++) {
      tradeMultiplier[k] = transport[chosen.get(k)] * request.commercialIntegration();
      raw[k] = localSurplus[k] * tradeMultiplier[k] * politicalMultiplier[k];
    }

    // ── 步骤 9：稀有惩罚（一轮）→ 归一化 ──
    // ★ 首都**豁免**稀有惩罚：首都的人口由政治决定（吃 politicalCentralization 的大头、靠税赋/贸易/国家网络输送），
    //   不由市场稀缺性决定。不豁免时默认阈值 10 万会把非硬目标国的首都压到比普通城还小（修前实测：马尔克堡 7068 < Hochheim 20228）。
    double threshold = params.rarityPenalty().threshold();
    double factor = params.rarityPenalty().factor();
    boolean[] penalized = new boolean[m];
    for (int k = 0; k < m; k++) {
      boolean isCapital = hasCapital && k == 0;
      if (!isCapital && raw[k] > threshold) {
        raw[k] *= factor;
        penalized[k] = true;
      }
    }
    double capacitySum = 0.0;
    for (double value : raw) {
      capacitySum += value;
    }
    long urbanCapacity = Math.round(capacitySum);
    long shortfall = Math.max(0L, urbanTotal - urbanCapacity);

    long[] cityPopulation = new long[m];
    boolean capitalAbsorbedAll = false;
    if (hasCapital && request.capital().get().targetPopulation().isPresent()) {
      long wanted = request.capital().get().targetPopulation().getAsLong();
      if (wanted > urbanTotal) {
        throw new IllegalArgumentException(
            "首都硬目标 "
                + wanted
                + " 超过城市总人口 "
                + urbanTotal
                + "（"
                + request.displayName()
                + "）：输入自相矛盾，不静默夹紧");
      }
      cityPopulation[0] = wanted;
      if (m > 1) {
        long[] tail = largestRemainder(Arrays.copyOfRange(raw, 1, m), urbanTotal - wanted);
        System.arraycopy(tail, 0, cityPopulation, 1, tail.length);
      } else if (wanted < urbanTotal) {
        // 只有首都一座城：硬目标与"Σ == urbanTotal"不能同时成立 ⇒ 不变量优先，首都吸收全部（justification 写明）
        capitalAbsorbedAll = true;
        cityPopulation[0] = urbanTotal;
      }
    } else {
      cityPopulation = largestRemainder(raw, urbanTotal);
    }

    // ── 命名：首都名/文档名优先，其余坐标哈希取词 ──
    String[] names = new String[m];
    Set<String> usedNames = new HashSet<>();
    if (hasCapital) {
      names[0] = request.capital().get().name();
      usedNames.add(names[0]);
    }
    List<Integer> nameOrder = new ArrayList<>(m);
    for (int k = 0; k < m; k++) {
      if (!(hasCapital && k == 0)) {
        nameOrder.add(k);
      }
    }
    nameOrder.sort(
        (a, b) -> {
          int c = Integer.compare(PlannedCity.tierRank(tier[b]), PlannedCity.tierRank(tier[a]));
          return c != 0 ? c : Integer.compare(a, b); // 同级按分数序（chosen 已是分数降序）
        });
    List<String> documented = request.documentedNames();
    int documentedIndex = 0;
    for (int k : nameOrder) {
      while (documentedIndex < documented.size()
          && usedNames.contains(documented.get(documentedIndex))) {
        documentedIndex++;
      }
      if (documentedIndex >= documented.size()) {
        break;
      }
      names[k] = documented.get(documentedIndex);
      usedNames.add(names[k]);
      documentedIndex++;
    }
    for (int k = 0; k < m; k++) {
      if (names[k] == null) {
        names[k] =
            hashName(request.seed(), hexes.get(chosen.get(k)), params.cityNameStyle(), usedNames);
      }
    }

    // ── 可读依据（含稀有惩罚/首都硬目标/承载缺口）──
    String[] justification = new String[m];
    for (int k = 0; k < m; k++) {
      HexCoord hex = hexes.get(chosen.get(k));
      StringBuilder sb = new StringBuilder();
      sb.append("tier=").append(tier[k]);
      sb.append("; catchment=").append(catchment[k]).append('格');
      sb.append("; localSurplus=").append(fmt(localSurplus[k]));
      sb.append("; transport=").append(fmt(transport[chosen.get(k)]));
      sb.append("; riverEdges=").append(terrain.riverEdgesAt(hex));
      sb.append("; coastal=").append(terrain.coastal(hex));
      sb.append("; rarityPenalty=").append(penalized[k] ? "applied" : "none");
      if (hasCapital && k == 0) {
        sb.append("; ★首都豁免稀有惩罚（人口由政治决定，不由市场稀缺性决定）");
      }
      if (hasCapital && k == 0) {
        CapitalAnchor anchor = request.capital().get();
        if (anchor.targetPopulation().isPresent()) {
          sb.append("; 首都硬目标=").append(anchor.targetPopulation().getAsLong());
          sb.append(" (占 urbanTotal ")
              .append(pct(anchor.targetPopulation().getAsLong(), urbanTotal))
              .append(')');
        } else {
          sb.append("; 首都（无文档人口，规模由公式给出）");
        }
        if (capitalAbsorbedAll) {
          sb.append("; ★全区域仅此一城，硬目标不能与守恒同立 ⇒ 首都吸收全部城市人口");
        }
      }
      if (shortfall > 0) {
        sb.append("; ★城市承载缺口=")
            .append(shortfall)
            .append("（承载 ")
            .append(urbanCapacity)
            .append(" < 需求 ")
            .append(urbanTotal)
            .append("，已归一化放大）");
      }
      justification[k] = sb.toString();
    }

    List<PlannedCity> cities = new ArrayList<>(m);
    for (int k = 0; k < m; k++) {
      HexCoord hex = hexes.get(chosen.get(k));
      cities.add(
          new PlannedCity(
              "c-" + hex,
              names[k],
              hex,
              tier[k],
              cityPopulation[k],
              catchment[k],
              localSurplus[k],
              tradeMultiplier[k],
              politicalMultiplier[k],
              justification[k]));
    }
    cities.sort(
        Comparator.<PlannedCity>comparingLong(PlannedCity::population)
            .reversed()
            .thenComparing(PlannedCity::id));

    Map<HexCoord, Long> ruralPopulation = new LinkedHashMap<>();
    Map<HexCoord, SurplusEstimate> audit = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      if (!arable[i]) {
        continue; // ★ 海洋/零产格缺席（不是 0）：写 0 会让"该格有人口序列"变成假话
      }
      ruralPopulation.put(hexes.get(i), rural[i]);
      audit.put(hexes.get(i), new SurplusEstimate(rural[i], surplus[i], transport[i], score[i]));
    }

    // ── 步骤 10：出口不变量，断言一次 ──
    long sumRural = 0L;
    for (long value : ruralPopulation.values()) {
      sumRural += value;
    }
    long sumCity = 0L;
    for (PlannedCity city : cities) {
      sumCity += city.population();
    }
    if (sumRural != ruralTotal) {
      throw new IllegalStateException(
          "不变量破坏：Σ rural = " + sumRural + " != ruralTotal = " + ruralTotal);
    }
    if (sumCity != urbanTotal) {
      throw new IllegalStateException(
          "不变量破坏：Σ city.population = " + sumCity + " != urbanTotal = " + urbanTotal);
    }
    if (ruralTotal + urbanTotal != total) {
      throw new IllegalStateException(
          "不变量破坏：ruralTotal + urbanTotal = " + (ruralTotal + urbanTotal) + " != total = " + total);
    }

    return new SettlementPlan(ruralPopulation, cities, audit, urbanCapacity, shortfall);
  }

  /** 地形承载率，取 {@code surplusRatePerTerrain}（{@code ocean} 为 0 ⇒ 不分配农村人口）。 */
  private static double capacityOf(String terrainKey, SettlementParams params) {
    Double value = params.surplusRatePerTerrain().get(terrainKey);
    if (value == null) {
      throw new IllegalArgumentException("surplusRatePerTerrain 缺少地形 key: " + terrainKey);
    }
    return value;
  }

  /** 0 条河边 ⇒ 1.0；1..navigable−1 条 ⇒ ordinary；≥ navigable 条 ⇒ navigable（诚实说明见类注释）。 */
  private static double riverMultiplier(int riverEdges, SettlementParams.RiverMultipliers river) {
    if (riverEdges <= 0) {
      return 1.0;
    }
    if (riverEdges >= river.navigableEdgesAtHex()) {
      return river.navigable();
    }
    return river.ordinary();
  }

  /** 交通优势：河流档 × 沿海 × 地形档，**三类叠乘**。 */
  private static double transportOf(
      TerrainView terrain, SettlementParams params, HexCoord hex, String terrainKey) {
    SettlementParams.TransportBonuses bonuses = params.transportBonuses();
    int riverEdges = terrain.riverEdgesAt(hex);
    double riverFactor;
    if (riverEdges >= bonuses.riverJunctionEdgesAtHex()) {
      riverFactor = bonuses.riverJunction();
    } else if (riverEdges >= params.river().navigableEdgesAtHex()) {
      riverFactor = bonuses.navigableRiver();
    } else if (riverEdges >= 1) {
      riverFactor = bonuses.river();
    } else {
      riverFactor = 1.0;
    }
    double coastFactor = terrain.coastal(hex) ? bonuses.coastal() : 1.0;
    return riverFactor * coastFactor * terrainFactor(terrain, params, hex, terrainKey);
  }

  /** 地形档：山口优先（"本格难走但邻格好走"）⇒ 深山 ⇒ 贫瘠 ⇒ 平原 ⇒ 其余（低矮丘陵/平缓高原）中性 1.0。 */
  private static double terrainFactor(
      TerrainView terrain, SettlementParams params, HexCoord hex, String terrainKey) {
    SettlementParams.TransportBonuses bonuses = params.transportBonuses();
    if (terrain.moveCost(hex) >= bonuses.mountainPassMoveCost()
        && hasCheapNeighbor(terrain, params, hex)) {
      return bonuses.mountainPass();
    }
    return switch (terrainKey) {
      case "mountains", "plateau_mountains" -> bonuses.deepMountain();
      case "desert", "ocean" -> bonuses.barren();
      case "plains" -> bonuses.plain();
      default -> 1.0;
    };
  }

  /**
   * 邻格里有没有低成本的"门"。★ 邻居**不在图上**时按不可通行哨兵处理（{@code moveCost} 对越界格抛，这里把越界读作"不是门"） ——
   * 图外的地形信息不存在，把它当成一条路才是瞎猜。
   */
  private static boolean hasCheapNeighbor(
      TerrainView terrain, SettlementParams params, HexCoord hex) {
    int limit = params.transportBonuses().mountainPassNeighborMoveCost();
    for (HexCoord neighbor : hex.neighbors()) {
      int cost;
      try {
        cost = terrain.moveCost(neighbor);
      } catch (IllegalArgumentException offMapOrUnknownTerrain) {
        cost = TerrainType.IMPASSABLE_MOVE_COST;
      }
      if (cost <= limit) {
        return true;
      }
    }
    return false;
  }

  /** 城市总数上界 = 按国家人口等比缩放的 Zipf 城市总数（再夹到可用格数）。 */
  private static int cityCountCap(
      SettlementRequest request, SettlementParams params, int arableCount) {
    SettlementParams.ZipfShape zipf = params.zipfShape();
    long scaled =
        Math.round(
            (double) zipf.totalCityCount() * request.totalPopulation() / zipf.samplePopulation());
    long cap = Math.max(1L, scaled);
    return (int) Math.min(cap, arableCount);
  }

  /** 各等级数量上限（Zipf 累计档位 × 人口缩放），保证 1 ≤ MajorCity ≤ City ≤ Town。 */
  private static int[] tierCaps(
      long totalPopulation, SettlementParams.ZipfShape zipf, int cityCount) {
    double scale = (double) totalPopulation / zipf.samplePopulation();
    int major = (int) Math.max(1L, Math.round(zipf.majorCityCap() * scale));
    int city = (int) Math.max(major, Math.round(zipf.cityCap() * scale));
    int town = (int) Math.max(city, Math.round(zipf.townCap() * scale));
    return new int[] {
      Math.min(major, cityCount), Math.min(city, cityCount), Math.min(town, cityCount)
    };
  }

  /** 把超出上限的低分城降一级（按分数降序扫，前面的留任、后面的降级）。cap ≥ 1 保证首都（最高分）不被降。 */
  private static void demote(String[] tier, String target, int cap, String lower) {
    int seen = 0;
    for (int k = 0; k < tier.length; k++) {
      if (!target.equals(tier[k])) {
        continue;
      }
      seen++;
      if (seen > cap) {
        tier[k] = lower;
      }
    }
  }

  /** 最大余数法：返回与 {@code raw} 同长的整数数组，{@code Σ == total} **严格**成立（余数按小数部分降序、下标升序分配）。 */
  private static long[] largestRemainder(double[] raw, long total) {
    int n = raw.length;
    long[] out = new long[n];
    if (total <= 0L || n == 0) {
      return out;
    }
    double sum = 0.0;
    int positives = 0;
    for (double value : raw) {
      if (value > 0.0) {
        sum += value;
        positives++;
      }
    }
    if (positives == 0) {
      // 退化兜底（全 0 权重）：按序均分，仍严格守恒。
      long base = total / n;
      long remainder = total - base * n;
      for (int i = 0; i < n; i++) {
        out[i] = base;
      }
      for (int i = 0; i < remainder; i++) {
        out[i]++;
      }
      return out;
    }
    double scale = total / sum;
    double[] fraction = new double[n];
    List<Integer> indices = new ArrayList<>(positives);
    long assigned = 0L;
    for (int i = 0; i < n; i++) {
      if (raw[i] <= 0.0) {
        continue;
      }
      double exact = raw[i] * scale;
      long floor = (long) Math.floor(exact);
      out[i] = floor;
      assigned += floor;
      fraction[i] = exact - floor;
      indices.add(i);
    }
    long remainder = total - assigned;
    indices.sort(
        (a, b) -> {
          int c = Double.compare(fraction[b], fraction[a]);
          return c != 0 ? c : Integer.compare(a, b);
        });
    for (int k = 0; k < remainder; k++) {
      out[indices.get(k % indices.size())]++;
    }
    return out;
  }

  /** 坐标哈希命名：词干 + 后缀；重名用同一坐标的不同盐重抽，仍不行则退化成带序号的可复现名。 */
  private static String hashName(
      long seed, HexCoord hex, SettlementParams.NameStyle style, Set<String> used) {
    for (int attempt = 0; attempt < 1_000; attempt++) {
      long first = hash(seed, hex, SALT_NAME + attempt);
      String stem = style.stems().get((int) Math.floorMod(first, (long) style.stems().size()));
      long second = splitmix64(first ^ 0x5EEDL);
      String suffix =
          style.suffixes().get((int) Math.floorMod(second, (long) style.suffixes().size()));
      String candidate = stem + suffix;
      if (used.add(candidate)) {
        return candidate;
      }
    }
    for (int i = 0; ; i++) {
      String candidate = "Siedlung" + i;
      if (used.add(candidate)) {
        return candidate;
      }
    }
  }

  /** SplitMix64：坐标哈希的搅动函数（无状态、跨机跨 JVM 恒定，不用 {@code java.util.Random}）。 */
  private static long splitmix64(long z) {
    z += 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** {@code (seed, 用途盐, q, r)} ⇒ 64 位哈希。★ 只与坐标和种子有关，**与集合迭代序无关**。 */
  private static long hash(long seed, HexCoord hex, long salt) {
    long mixed = splitmix64(seed ^ salt);
    return splitmix64(mixed ^ (((long) hex.q()) << 32) ^ (hex.r() & 0xFFFFFFFFL));
  }

  /** 64 位哈希 ⇒ [0,1) 的均匀量（取高 53 位）。 */
  private static double unit(long hashed) {
    return (hashed >>> 11) * 0x1.0p-53;
  }

  private static double roll(long seed, HexCoord hex, long salt) {
    return unit(hash(seed, hex, salt));
  }

  private static double clamp01(double value) {
    if (value < 0.0) {
      return 0.0;
    }
    return value > 1.0 ? 1.0 : value;
  }

  private static String fmt(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String pct(long part, long whole) {
    if (whole <= 0L) {
      return "0.0%";
    }
    return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
  }
}
