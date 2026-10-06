package io.mosire.simos.economy.market;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用小型经济探针模型（M0+）。
 *
 * <p>它把本轮讨论里的最小机制放进一个纯 Java 测试模型：
 *
 * <ul>
 *   <li>家户有库存 A、人口、货币、单边债务；
 *   <li>生产方式 = 一条 {@link Recipe}（产出/投入/劳动/目标规模）；
 *   <li>每轮：生产决策 → C1 → A1/C2/C3 → 分格市场 → 规则 A/B/C 更新 ref → 计息 → 吃饭/死亡 → 生产；
 *   <li>市场跨 hex，运输成本按距离加成；买家按总财富降序、无出价、按最低到货价成交；
 *   <li>成交直接记买家债务；信贷池为卖家支付货币；运输费先挂托管（不指定收款方）。
 * </ul>
 *
 * <p>它不是正式生产代码，只服务 M0 探针；正式实现按 M1–M6 分阶段进行。
 */
final class ProbeEconomy {

  static final long PER_MILLE = 1000L;

  /** 农村商人每做一轮贸易，额外运输成本累积多少（‰），封顶 {@link #RURAL_MERCHANT_COST_CAP_PER_MILLE}。 */
  static final long RURAL_MERCHANT_COST_GROWTH_PER_MILLE = 2L;

  /** 农村贸易成本累积上限（‰）。 */
  static final long RURAL_MERCHANT_COST_CAP_PER_MILLE = 100L;

  /** 辐射成本：每远离最近城市 1 hex，运输费率增加多少（‰）。 */
  static final long RADIAL_COST_PER_HEX_PER_MILLE = 20L;

  /** 道路：每级降低多少运输费率（‰）。 */
  static final long ROAD_DISCOUNT_PER_LEVEL_PER_MILLE = 50L;

  /** 城市扩建：占用超过承载多少后开始推进（‰）。 */
  static final long CITY_EXPANSION_PRESSURE_PER_MILLE = 800L;

  /** 城市扩建每轮推进多少（进度点）。 */
  static final long CITY_EXPANSION_SPEED = 1L;

  /** 城市扩建一次需要的进度点（越慢越大）。 */
  static final long CITY_EXPANSION_THRESHOLD = 200L;

  /** 城市每次扩建增加的承载。 */
  static final long CITY_EXPANSION_STEP = 20L;

  /** 城市每次扩建增加的城区比例（‰）。 */
  static final long CITY_BUILT_AREA_STEP_PER_MILLE = 20L;

  enum Good {
    GRAIN,
    CLOTH,
    FIBER
  }

  record Params(
      long alphaUpPerMille,
      long alphaDownPerMille,
      long undercutPerMille,
      long pMax,
      long interestPerMille,
      long mortalityPerMille,
      long laborPerCapita,
      long foodPerCapita,
      long baseTransportPerMille,
      long perHexTransportPerMille,
      EnumMap<Good, Long> initialPrice,
      long naturalMortalityPerMillion,
      long birthsPerMillion) {

    /** 旧探针构造：自然死亡/出生默认 0（与之前行为逐值一致）。 */
    Params(
        long alphaUpPerMille,
        long alphaDownPerMille,
        long undercutPerMille,
        long pMax,
        long interestPerMille,
        long mortalityPerMille,
        long laborPerCapita,
        long foodPerCapita,
        long baseTransportPerMille,
        long perHexTransportPerMille,
        EnumMap<Good, Long> initialPrice) {
      this(
          alphaUpPerMille,
          alphaDownPerMille,
          undercutPerMille,
          pMax,
          interestPerMille,
          mortalityPerMille,
          laborPerCapita,
          foodPerCapita,
          baseTransportPerMille,
          perHexTransportPerMille,
          initialPrice,
          0L,
          0L);
    }
  }

  record Recipe(
      Good output,
      long outputPerUnit,
      EnumMap<Good, Long> inputPerUnit,
      long laborPerUnit,
      long desiredScale) {}

  static final class Hex {
    final String id;
    final int q;
    final int r;
    long arableMu = 3_100L;
    final EnumMap<Good, Long> ref = new EnumMap<>(Good.class);

    Hex(String id, int q, int r) {
      this.id = id;
      this.q = q;
      this.r = r;
    }
  }

  static final class Household {
    final String id;
    final String hexId;
    long population;
    long money;
    long debt;
    final EnumMap<Good, Long> stock = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> needPerCapita = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> cost = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> unsoldLastRound = new EnumMap<>(Good.class);
    final EnumMap<Good, Long> lastProducedByGood = new EnumMap<>(Good.class);
    Recipe recipe;
    final List<Recipe> extraRecipes = new ArrayList<>();
    boolean productionStopped;
    long lastFoodNeed;
    long lastFoodEaten;
    long lastEfficiency = PER_MILLE;
    long lastDeaths;
    long lastBirths;
    long naturalDeathRemainder;
    long birthRemainder;
    long lastProduced;

    Household(String id, String hexId) {
      this.id = id;
      this.hexId = hexId;
    }

    Household stock(Good good, long value) {
      stock.put(good, value);
      return this;
    }

    Household need(Good good, long perCapita) {
      needPerCapita.put(good, perCapita);
      return this;
    }

    Household cost(Good good, long value) {
      cost.put(good, value);
      return this;
    }

    Household addRecipe(Recipe recipe) {
      if (this.recipe == null) {
        this.recipe = recipe;
      } else {
        extraRecipes.add(recipe);
      }
      return this;
    }

    /** 按优先级：主 recipe 在前，之后按加入顺序；先到先分劳动。 */
    List<Recipe> productionRecipes() {
      if (recipe == null) {
        return List.copyOf(extraRecipes);
      }
      if (extraRecipes.isEmpty()) {
        return List.of(recipe);
      }
      List<Recipe> all = new ArrayList<>(extraRecipes.size() + 1);
      all.add(recipe);
      all.addAll(extraRecipes);
      return all;
    }

    long stockOf(Good good) {
      return stock.getOrDefault(good, 0L);
    }
  }

  static final class Ask {
    final Household seller;
    final String sellerHex;
    final Good good;
    final long basePrice;
    long qty;

    Ask(Household seller, String sellerHex, Good good, long basePrice, long qty) {
      this.seller = seller;
      this.sellerHex = sellerHex;
      this.good = good;
      this.basePrice = basePrice;
      this.qty = qty;
    }
  }

  record Trade(
      String buyerHex, String sellerHex, Good good, long basePrice, long landedPrice, long qty) {}

  /** 佃农制的租形。 */
  enum RentForm {
    FIXED_KIND,
    SHARE,
    FIXED_CASH
  }

  /** 佃农制：地主只出土地；佃农家户是 operator，出劳动/投入，产出先归佃农，再按租约给地主。 */
  static final class Tenancy {
    final String id;
    final Household landlord;
    final Household tenant;
    final Recipe recipe;
    long desiredScale;
    long harvestPerMille = PER_MILLE;
    long landPerUnit;
    final RentForm rentForm;
    final long fixedKind;
    final long landlordSharePerMille;
    final long fixedCash;
    long lastScale;
    long lastOutput;
    long lastLaborUsed;
    long lastRentPaid;
    long lastRentArrears;

    Tenancy(
        String id,
        Household landlord,
        Household tenant,
        Recipe recipe,
        long desiredScale,
        RentForm rentForm,
        long fixedKind,
        long landlordSharePerMille,
        long fixedCash) {
      this.id = id;
      this.landlord = landlord;
      this.tenant = tenant;
      this.recipe = recipe;
      this.desiredScale = desiredScale;
      this.rentForm = rentForm;
      this.fixedKind = fixedKind;
      this.landlordSharePerMille = landlordSharePerMille;
      this.fixedCash = fixedCash;
    }

    Tenancy landPerUnit(long value) {
      this.landPerUnit = value;
      return this;
    }
  }

  /** 雇农工资合同：按实际投入劳动计酬；钱与粮可叠加。 */
  static final class WageContract {
    final Household worker;
    final long cashPerLabor;
    final long grainPerLabor;
    long lastLaborUsed;
    long lastCashPaid;
    long lastGrainPaid;
    long lastCashArrears;
    long lastGrainArrears;

    WageContract(Household worker, long cashPerLabor, long grainPerLabor) {
      this.worker = worker;
      this.cashPerLabor = cashPerLabor;
      this.grainPerLabor = grainPerLabor;
    }
  }

  /** 雇农制：地主/经营地主是 operator，出土地和资本；产出先归经营者，再给雇农发工资。 */
  static final class WageFarm {
    final String id;
    final Household operator;
    final Recipe recipe;
    long desiredScale;
    long harvestPerMille = PER_MILLE;
    long landPerUnit;
    long fixedCashPerRound;
    final List<WageContract> contracts = new ArrayList<>();
    long lastScale;
    long lastOutput;
    long lastLaborUsed;
    long lastCashPaid;
    long lastGrainPaid;
    long lastCashArrears;
    long lastGrainArrears;

    WageFarm(String id, Household operator, Recipe recipe, long desiredScale) {
      this.id = id;
      this.operator = operator;
      this.recipe = recipe;
      this.desiredScale = desiredScale;
    }

    WageFarm hire(Household worker, long cashPerLabor, long grainPerLabor) {
      contracts.add(new WageContract(worker, cashPerLabor, grainPerLabor));
      return this;
    }

    WageFarm fixedCash(long amount) {
      this.fixedCashPerRound = amount;
      return this;
    }

    WageFarm landPerUnit(long value) {
      this.landPerUnit = value;
      return this;
    }
  }

  /** 运输队：一条 lane 的运力与运费收入；本金归 operator 家户。 */
  /** 商人内部阶层：脚夫 → 个体户 → 老板；越靠上运力越大、城区占用越高。 */
  enum MerchantTier {
    PORTER,
    SELF_EMPLOYED,
    BOSS;

    /** 占用城市区的当量（脚夫 1、个体户 2、老板 4）。 */
    int districtUse() {
      return switch (this) {
        case PORTER -> 1;
        case SELF_EMPLOYED -> 2;
        case BOSS -> 4;
      };
    }

    /** 城市商人能压低的运输费率（‰）；农村商人只会让成本累积变贵。 */
    long transportDiscountPerMille() {
      return switch (this) {
        case PORTER -> 10L;
        case SELF_EMPLOYED -> 25L;
        case BOSS -> 50L;
      };
    }
  }

  static final class TransportTeam {
    final String id;
    final Household operator;
    final String fromHex;
    final String toHex;
    long capacityPerRound;
    final MerchantTier tier;
    final boolean homeIsCity;
    final String homeHex;
    final long serviceRadiusHex;
    long ruralTradeCostPenaltyPerMille;
    long remainingCapacity;
    long lastUnitsMoved;
    long lastFeeEarned;
    long lastTradeProfit;

    /** 旧构造：默认个体户、家在非城市、无农村成本累积（旧探针行为逐值不变）。 */
    TransportTeam(
        String id, Household operator, String fromHex, String toHex, long capacityPerRound) {
      this(id, operator, fromHex, toHex, capacityPerRound, MerchantTier.SELF_EMPLOYED, false);
    }

    TransportTeam(
        String id,
        Household operator,
        String fromHex,
        String toHex,
        long capacityPerRound,
        MerchantTier tier,
        boolean homeIsCity) {
      this(id, operator, fromHex, toHex, capacityPerRound, tier, homeIsCity, fromHex, 0L);
    }

    /**
     * 城市商人扩散构造：{@code fromHex/toHex} 用 {@code "*"} 表示通配； 只要 lane 两端都在 {@code homeHex} 的 {@code
     * serviceRadiusHex} 半径内，本队就能承运。
     */
    TransportTeam(
        String id,
        Household operator,
        String fromHex,
        String toHex,
        long capacityPerRound,
        MerchantTier tier,
        boolean homeIsCity,
        String homeHex,
        long serviceRadiusHex) {
      this.id = id;
      this.operator = operator;
      this.fromHex = fromHex;
      this.toHex = toHex;
      this.capacityPerRound = capacityPerRound;
      this.tier = tier;
      this.homeIsCity = homeIsCity;
      this.homeHex = homeHex;
      this.serviceRadiusHex = serviceRadiusHex;
      this.remainingCapacity = capacityPerRound;
    }
  }

  /** 城市承载与慢速扩建：城区占用 = 商人当量 + 作坊当量 + 人口/1000 当量。 */
  static final class CityState {
    final String id;
    final String hexId;
    final long radiusHex;
    long capacity;
    long usedCapacity;
    long expansionProgress;
    long expansionCount;
    long builtAreaPerMille;
    long lastTradeVolume;
    long cumulativeTradeVolume;

    CityState(String id, String hexId, long capacity) {
      this(id, hexId, capacity, 0L);
    }

    CityState(String id, String hexId, long capacity, long radiusHex) {
      this.id = id;
      this.hexId = hexId;
      this.capacity = capacity;
      this.radiusHex = radiusHex;
    }
  }

  record RoundResult(
      int round,
      long totalPopulation,
      long deathsThisRound,
      long birthsThisRound,
      long totalDebt,
      long creditIssued,
      long creditRepaid,
      long debtDeleted,
      long newIssuance,
      long newRepayment,
      long debtDeletedThisRound,
      long totalMoney,
      long transportEscrow,
      Map<String, Map<Good, Long>> refs,
      List<Trade> trades) {}

  final Params params;
  final Map<String, Hex> hexes = new LinkedHashMap<>();
  final List<Household> households = new ArrayList<>();
  final List<Tenancy> tenancies = new ArrayList<>();
  final List<WageFarm> wageFarms = new ArrayList<>();
  final List<TransportTeam> transportTeams = new ArrayList<>();
  final List<CityState> cities = new ArrayList<>();
  final Map<String, Long> roadLevels = new LinkedHashMap<>();
  long creditIssued;
  long creditRepaid;
  long debtDeleted;
  long transportEscrow;

  ProbeEconomy(Params params) {
    this.params = params;
  }

  Tenancy addTenancy(Tenancy tenancy) {
    tenancies.add(tenancy);
    return tenancy;
  }

  WageFarm addWageFarm(WageFarm farm) {
    wageFarms.add(farm);
    return farm;
  }

  TransportTeam addTransportTeam(TransportTeam team) {
    transportTeams.add(team);
    return team;
  }

  CityState addCity(CityState city) {
    cities.add(city);
    return city;
  }

  Hex addHex(String id, int q, int r) {
    Hex hex = new Hex(id, q, r);
    hexes.put(id, hex);
    return hex;
  }

  Household addHousehold(Household household) {
    households.add(household);
    return household;
  }

  /** 探针版人口迁移：人口与债务按比例随人走；库存不搬（正式版由家户资产账处理）。 */
  void migratePopulation(Household from, Household to, long count) {
    if (count <= 0L || from.population <= 0L) {
      return;
    }
    long moved = Math.min(count, from.population);
    long before = from.population;
    long movedDebt = Math.multiplyExact(from.debt, moved) / before;
    from.population = before - moved;
    from.debt -= movedDebt;
    to.population = Math.addExact(to.population, moved);
    to.debt = Math.addExact(to.debt, movedDebt);
  }

  long currentPrice(String hexId, Good good) {
    long ref = hexes.get(hexId).ref.getOrDefault(good, 0L);
    return ref > 0L ? ref : params.initialPrice().getOrDefault(good, 0L);
  }

  long wealthOf(Household household) {
    long wealth = household.money;
    for (Good good : Good.values()) {
      wealth += household.stockOf(good) * currentPrice(household.hexId, good);
    }
    return wealth;
  }

  /** 运输费率：基础距离费 + 农村商人累积成本 − 城市商人折价。 */
  /** 运输费率：基础距离费 + 离城辐射成本 − 道路折扣 + 农村商人累积 − 城市商人折价（按离城衰减）。 */
  long transportPerMille(String fromHex, String toHex) {
    Hex from = hexes.get(fromHex);
    Hex to = hexes.get(toHex);
    int distance = Math.abs(from.q - to.q) + Math.abs(from.r - to.r);
    long radialDistance = Long.MAX_VALUE;
    for (CityState city : cities) {
      long near = Math.min(hexDistance(city.hexId, fromHex), hexDistance(city.hexId, toHex));
      radialDistance = Math.min(radialDistance, near);
    }
    if (radialDistance == Long.MAX_VALUE) {
      radialDistance = 0L;
    }
    long base =
        params.baseTransportPerMille()
            + params.perHexTransportPerMille() * distance
            + RADIAL_COST_PER_HEX_PER_MILLE * radialDistance;
    base = Math.max(0L, base - roadDiscountPerMille(fromHex, toHex));
    long cityDiscount = 0L;
    long ruralPenalty = 0L;
    for (TransportTeam team : transportTeams) {
      if (!servesLane(team, fromHex, toHex)) {
        continue;
      }
      if (team.homeIsCity) {
        long d = Math.max(hexDistance(team.homeHex, fromHex), hexDistance(team.homeHex, toHex));
        if (team.serviceRadiusHex <= 0L || d > team.serviceRadiusHex) {
          continue;
        }
        long decay = (team.serviceRadiusHex - d + 1L) * PER_MILLE / (team.serviceRadiusHex + 1L);
        cityDiscount += team.tier.transportDiscountPerMille() * decay / PER_MILLE;
      } else {
        ruralPenalty += team.ruralTradeCostPenaltyPerMille;
      }
    }
    return Math.max(0L, base - cityDiscount + ruralPenalty);
  }

  /** 道路等级：0 = 无路；每级降低固定运输费率（后续接道路图，现在先留接口）。 */
  void setRoad(String hexA, String hexB, long level) {
    roadLevels.put(roadKey(hexA, hexB), Math.max(0L, level));
  }

  private long roadDiscountPerMille(String fromHex, String toHex) {
    long direct = roadLevels.getOrDefault(roadKey(fromHex, toHex), 0L);
    long pathMin = roadPathMinLevel(fromHex, toHex);
    long level = Math.max(direct, Math.max(0L, pathMin));
    return level * ROAD_DISCOUNT_PER_LEVEL_PER_MILLE;
  }

  /** 沿已有道路走，能到 {@code toHex} 的路径上最小道路等级；没有路 ⇒ -1。 */
  private long roadPathMinLevel(String fromHex, String toHex) {
    if (fromHex.equals(toHex)) {
      return 0L;
    }
    Map<String, List<Map.Entry<String, Long>>> adjacency = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : roadLevels.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      String[] parts = entry.getKey().split("\\|", -1);
      if (parts.length != 2) {
        continue;
      }
      adjacency
          .computeIfAbsent(parts[0], k -> new ArrayList<>())
          .add(Map.entry(parts[1], entry.getValue()));
      adjacency
          .computeIfAbsent(parts[1], k -> new ArrayList<>())
          .add(Map.entry(parts[0], entry.getValue()));
    }
    Map<String, Long> bestBottleneck = new HashMap<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    bestBottleneck.put(fromHex, Long.MAX_VALUE);
    queue.add(fromHex);
    while (!queue.isEmpty()) {
      String current = queue.removeFirst();
      long currentBottleneck = bestBottleneck.getOrDefault(current, -1L);
      for (Map.Entry<String, Long> edge : adjacency.getOrDefault(current, List.of())) {
        long candidate = Math.min(currentBottleneck, edge.getValue());
        Long existing = bestBottleneck.get(edge.getKey());
        if (existing == null || candidate > existing) {
          bestBottleneck.put(edge.getKey(), candidate);
          queue.addLast(edge.getKey());
        }
      }
    }
    Long result = bestBottleneck.get(toHex);
    return result == null ? -1L : result;
  }

  private static String roadKey(String a, String b) {
    return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
  }

  /** 商人能不能服务这条 lane：显式 lane 精确匹配；通配则 lane 两端都在 homeHex 的服务半径内。 */
  boolean servesLane(TransportTeam team, String fromHex, String toHex) {
    if (team.fromHex.equals(fromHex) && team.toHex.equals(toHex)) {
      return true;
    }
    if (team.serviceRadiusHex <= 0L) {
      return false;
    }
    return hexDistance(team.homeHex, fromHex) <= team.serviceRadiusHex
        && hexDistance(team.homeHex, toHex) <= team.serviceRadiusHex;
  }

  private int hexDistance(String left, String right) {
    Hex a = hexes.get(left);
    Hex b = hexes.get(right);
    if (a == null || b == null) {
      return Integer.MAX_VALUE;
    }
    return Math.abs(a.q - b.q) + Math.abs(a.r - b.r);
  }

  /** 本格城区已占用的亩数 = 可耕地 × 城区比例。 */
  /** 本格承担的城区占地：城市把 total built 按“离城越近权重越大”辐射到半径内的格。 */
  long cityBuiltAreaMu(String hexId) {
    long total = 0L;
    for (CityState city : cities) {
      long distance = hexDistance(city.hexId, hexId);
      if (distance > city.radiusHex) {
        continue;
      }
      long cityArable = hexes.get(city.hexId).arableMu;
      long builtTotal = cityArable * city.builtAreaPerMille / PER_MILLE;
      long weightSum = 0L;
      for (Hex candidate : hexes.values()) {
        long d = hexDistance(city.hexId, candidate.id);
        if (d <= city.radiusHex) {
          weightSum += city.radiusHex - d + 1L;
        }
      }
      long weight = city.radiusHex - distance + 1L;
      total += builtTotal * weight / Math.max(1L, weightSum);
    }
    return total;
  }

  /** 本格还能用于农业的亩数 = 可耕地 − 城区占地（不为负）。 */
  long availableArableMu(String hexId) {
    Hex hex = hexes.get(hexId);
    return Math.max(0L, hex.arableMu - cityBuiltAreaMu(hexId));
  }

  /** 本户本轮可投入生产的有效劳动 = 人口 × 劳动系数 × 吃饱度（千分）。 */
  long effectiveLabor(Household household) {
    return household.population
        * params.laborPerCapita()
        / PER_MILLE
        * household.lastEfficiency
        / PER_MILLE;
  }

  private static void reserveInputs(EnumMap<Good, Long> reserve, Recipe recipe, long desiredScale) {
    for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
      long needed = desiredScale * entry.getValue();
      if (needed > 0L) {
        reserve.merge(entry.getKey(), needed, Math::addExact);
      }
    }
  }

  RoundResult runRound(int round) {
    long creditBefore = creditIssued;
    long repaymentBefore = creditRepaid;
    long deathsTotal = 0L;
    long birthsTotal = 0L;
    long debtDeletedThisRound = 0L;
    List<Trade> trades = new ArrayList<>();

    // 运力每轮重置（不可储存）。
    for (TransportTeam team : transportTeams) {
      team.remainingCapacity = team.capacityPerRound;
      team.lastUnitsMoved = 0L;
      team.lastFeeEarned = 0L;
    }
    for (CityState city : cities) {
      city.lastTradeVolume = 0L;
    }

    // ① 生产决策：ref < cost ⇒ 该 recipe 停产；全部 recipe 停产才算 household 停产。
    for (Household household : households) {
      List<Recipe> recipes = household.productionRecipes();
      boolean allStopped = !recipes.isEmpty();
      for (Recipe recipe : recipes) {
        if (recipeStopped(household, recipe)) {
          continue;
        }
        allStopped = false;
      }
      household.productionStopped = allStopped;
    }

    // ② C1 = 消费需求 + 生产投入需求（估计）。
    Map<Household, EnumMap<Good, Long>> c1 = new IdentityHashMap<>();
    for (Household household : households) {
      EnumMap<Good, Long> reserve = new EnumMap<>(Good.class);
      for (Good good : Good.values()) {
        long consumption = household.population * household.needPerCapita.getOrDefault(good, 0L);
        if (consumption > 0L) {
          reserve.merge(good, consumption, Math::addExact);
        }
      }
      for (Recipe recipe : household.productionRecipes()) {
        if (recipeStopped(household, recipe)) {
          continue;
        }
        for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
          long needed = recipe.desiredScale() * entry.getValue();
          if (needed > 0L) {
            reserve.merge(entry.getKey(), needed, Math::addExact);
          }
        }
      }
      c1.put(household, reserve);
    }
    // 生产组织的投入需求也进 C1：佃农制由佃农家户出，雇农制由经营者出。
    for (Tenancy tenancy : tenancies) {
      if (!recipeStopped(tenancy.tenant, tenancy.recipe)) {
        reserveInputs(c1.get(tenancy.tenant), tenancy.recipe, tenancy.desiredScale);
      }
    }
    for (WageFarm farm : wageFarms) {
      if (!recipeStopped(farm.operator, farm.recipe)) {
        reserveInputs(c1.get(farm.operator), farm.recipe, farm.desiredScale);
      }
    }

    // ②.5 还债 wave（简化：把手上全部货币按 min(钱, 债) 还给信贷池；
    // “意思意思”式部分偿还留到正式 M3，这里先让有收入的家户能滚动还债）。
    for (Household household : households) {
      long payment = Math.min(household.money, household.debt);
      if (payment > 0L) {
        household.money -= payment;
        household.debt -= payment;
        creditRepaid = Math.addExact(creditRepaid, payment);
      }
    }

    // ③ A1 / C2 / C3（C3 = C2；债务需求在本探针不进入商品需求）。
    Map<Household, EnumMap<Good, Long>> sellable = new IdentityHashMap<>();
    Map<Household, EnumMap<Good, Long>> demand = new IdentityHashMap<>();
    for (Household household : households) {
      EnumMap<Good, Long> surplus = new EnumMap<>(Good.class);
      EnumMap<Good, Long> shortfall = new EnumMap<>(Good.class);
      for (Good good : Good.values()) {
        long reserve = c1.get(household).getOrDefault(good, 0L);
        long stock = household.stockOf(good);
        long extra = stock - reserve;
        if (extra > 0L) {
          surplus.put(good, extra);
        } else if (extra < 0L) {
          shortfall.put(good, -extra);
        }
      }
      sellable.put(household, surplus);
      demand.put(household, shortfall);
    }

    // ④ 挂单：ref>0 用 ref；ref=0 用 GM 初始价；上轮未售立刻降价。
    List<Ask> asks = new ArrayList<>();
    Map<String, Map<Good, Long>> offeredByHex = new LinkedHashMap<>();
    for (Household household : households) {
      for (Good good : Good.values()) {
        long qty = sellable.get(household).getOrDefault(good, 0L);
        if (qty <= 0L) {
          continue;
        }
        long base = currentPrice(household.hexId, good);
        long unsold = household.unsoldLastRound.getOrDefault(good, 0L);
        long localRef = hexes.get(household.hexId).ref.getOrDefault(good, 0L);
        if (localRef > 0L && unsold > 0L) {
          base = base * (PER_MILLE - params.undercutPerMille()) / PER_MILLE;
        }
        asks.add(new Ask(household, household.hexId, good, Math.max(0L, base), qty));
        offeredByHex
            .computeIfAbsent(household.hexId, ignored -> new LinkedHashMap<>())
            .merge(good, qty, Math::addExact);
        household.unsoldLastRound.put(good, 0L);
      }
    }

    // ⑤ 买家按总财富降序；按最低到货价在全部 hex 中选卖方。
    List<Household> buyers = new ArrayList<>(households);
    buyers.sort(Comparator.comparingLong(this::wealthOf).reversed().thenComparing(h -> h.id));
    Map<String, Map<Good, Long>> soldByHex = new LinkedHashMap<>();
    Map<String, Map<Good, Long>> unmetByHex = new LinkedHashMap<>();
    Map<String, Map<Good, Long>> priceSumByHex = new LinkedHashMap<>();
    Map<String, Map<Good, Long>> priceCountByHex = new LinkedHashMap<>();

    for (Household buyer : buyers) {
      EnumMap<Good, Long> need = demand.get(buyer);
      for (Good good : Good.values()) {
        long remaining = need.getOrDefault(good, 0L);
        while (remaining > 0L) {
          Ask best = null;
          long bestLanded = Long.MAX_VALUE;
          for (Ask ask : asks) {
            if (ask.good != good || ask.qty <= 0L || ask.seller == buyer) {
              continue;
            }
            long landed =
                ask.basePrice
                    * (PER_MILLE + transportPerMille(ask.sellerHex, buyer.hexId))
                    / PER_MILLE;
            if (best == null
                || landed < bestLanded
                || (landed == bestLanded
                    && (ask.basePrice < best.basePrice
                        || (ask.basePrice == best.basePrice
                            && ask.seller.id.compareTo(best.seller.id) < 0)))) {
              best = ask;
              bestLanded = landed;
            }
          }
          if (best == null) {
            unmetByHex
                .computeIfAbsent(buyer.hexId, ignored -> new LinkedHashMap<>())
                .merge(good, remaining, Math::addExact);
            break;
          }
          long qty = Math.min(remaining, best.qty);
          long amountBase = Math.multiplyExact(qty, best.basePrice);
          long amountLanded = Math.multiplyExact(qty, bestLanded);

          buyer.stock.merge(good, qty, Math::addExact);
          buyer.debt = Math.addExact(buyer.debt, amountLanded);
          best.seller.stock.merge(good, -qty, Math::addExact);
          best.seller.money = Math.addExact(best.seller.money, amountBase);
          creditIssued = Math.addExact(creditIssued, amountLanded);
          routeTransportFee(best.sellerHex, buyer.hexId, qty, amountLanded - amountBase);

          best.qty -= qty;
          remaining -= qty;
          soldByHex
              .computeIfAbsent(best.sellerHex, ignored -> new LinkedHashMap<>())
              .merge(good, qty, Math::addExact);
          priceSumByHex
              .computeIfAbsent(buyer.hexId, ignored -> new LinkedHashMap<>())
              .merge(good, bestLanded, Math::addExact);
          priceCountByHex
              .computeIfAbsent(buyer.hexId, ignored -> new LinkedHashMap<>())
              .merge(good, 1L, Math::addExact);
          priceSumByHex
              .computeIfAbsent(best.sellerHex, ignored -> new LinkedHashMap<>())
              .merge(good, best.basePrice, Math::addExact);
          priceCountByHex
              .computeIfAbsent(best.sellerHex, ignored -> new LinkedHashMap<>())
              .merge(good, 1L, Math::addExact);
          trades.add(new Trade(buyer.hexId, best.sellerHex, good, best.basePrice, bestLanded, qty));
        }
      }
    }

    // ⑥ 未售登记 + 规则 A/B/C 更新每个 hex 的 ref。
    for (Ask ask : asks) {
      if (ask.qty > 0L) {
        ask.seller.unsoldLastRound.merge(ask.good, ask.qty, Math::addExact);
      }
    }
    for (Hex hex : hexes.values()) {
      for (Good good : Good.values()) {
        long count = valueAt(priceCountByHex, hex.id, good);
        long offered = valueAt(offeredByHex, hex.id, good);
        long sold = valueAt(soldByHex, hex.id, good);
        long missed = valueAt(unmetByHex, hex.id, good);
        if (count == 0L) {
          hex.ref.put(good, 0L); // 规则 C：无成交 → 归零
          continue;
        }
        long avg = valueAt(priceSumByHex, hex.id, good) / count;
        long next = avg;
        long unsold = Math.max(0L, offered - sold);
        if (unsold > 0L) {
          long ratio = unsold * PER_MILLE / Math.max(1L, offered);
          next = next * (PER_MILLE - params.alphaDownPerMille() * ratio / PER_MILLE) / PER_MILLE;
        }
        if (missed > 0L) {
          long demandQty = sold + missed;
          long gap = missed * PER_MILLE / Math.max(1L, demandQty);
          next = next * (PER_MILLE + params.alphaUpPerMille() * gap / PER_MILLE) / PER_MILLE;
        }
        hex.ref.put(good, Math.max(0L, Math.min(params.pMax(), next)));
      }
    }

    // ⑦ 计息。
    for (Household household : households) {
      household.debt = household.debt + household.debt * params.interestPerMille() / PER_MILLE;
    }

    // ⑧ 吃饭/消费/死亡：口粮不足 → 效率下降 + 人口死亡 → 债务按人口比例删除。
    for (Household household : households) {
      long foodNeed = household.population * household.needPerCapita.getOrDefault(Good.GRAIN, 0L);
      long foodEaten = Math.min(household.stockOf(Good.GRAIN), foodNeed);
      household.stock.put(Good.GRAIN, household.stockOf(Good.GRAIN) - foodEaten);
      long satisfaction =
          foodNeed <= 0L ? PER_MILLE : foodEaten * PER_MILLE / Math.max(1L, foodNeed);
      household.lastFoodNeed = foodNeed;
      household.lastFoodEaten = foodEaten;
      household.lastEfficiency = Math.max(300L, satisfaction);
      long deaths =
          household.population
              * params.mortalityPerMille()
              * (PER_MILLE - satisfaction)
              / 1_000_000L;
      deaths = Math.min(household.population, Math.max(0L, deaths));
      household.lastDeaths = deaths;
      long beforePopulation = household.population;
      // 自然死亡（月度/年度率的探针版：按百万分率逐轮累计余数，避免小人口被取整抹掉）。
      long naturalNumerator =
          Math.addExact(
              Math.multiplyExact(beforePopulation, params.naturalMortalityPerMillion()),
              household.naturalDeathRemainder);
      long naturalDeaths = Math.min(beforePopulation - deaths, naturalNumerator / 1_000_000L);
      household.naturalDeathRemainder = naturalNumerator % 1_000_000L;
      long totalDeaths = Math.min(beforePopulation, deaths + Math.max(0L, naturalDeaths));
      long afterPopulation = beforePopulation - totalDeaths;
      long debtBeforeDeletion = household.debt;
      if (totalDeaths > 0L) {
        household.debt =
            afterPopulation <= 0L
                ? 0L
                : household.debt * afterPopulation / Math.max(1L, beforePopulation);
      }
      long deleted = debtBeforeDeletion - household.debt;
      if (deleted > 0L) {
        debtDeleted = Math.addExact(debtDeleted, deleted);
        debtDeletedThisRound = Math.addExact(debtDeletedThisRound, deleted);
      }
      // 出生（探针版粗出生率；无年龄/性别结构）。
      long birthNumerator =
          Math.addExact(
              Math.multiplyExact(afterPopulation, params.birthsPerMillion()),
              household.birthRemainder);
      long births = birthNumerator / 1_000_000L;
      household.birthRemainder = birthNumerator % 1_000_000L;
      household.population = afterPopulation + births;
      household.lastDeaths = totalDeaths;
      household.lastBirths = births;
      deathsTotal += totalDeaths;
      birthsTotal += births;

      for (Good good : Good.values()) {
        if (good == Good.GRAIN) {
          continue; // 口粮上面已单独处理
        }
        long need = household.population * household.needPerCapita.getOrDefault(good, 0L);
        long eat = Math.min(household.stockOf(good), need);
        household.stock.put(good, household.stockOf(good) - eat);
      }
    }

    // ⑨ 生产：用 A、劳动和效率按优先级投产；Σ 分配劳动 ≤ 有效劳动。
    for (Household household : households) {
      List<Recipe> recipes = household.productionRecipes();
      household.lastProducedByGood.clear();
      if (recipes.isEmpty() || household.population <= 0L) {
        household.lastProduced = 0L;
        continue;
      }
      long effectiveLabor =
          household.population
              * params.laborPerCapita()
              / PER_MILLE
              * household.lastEfficiency
              / PER_MILLE;
      long totalOutput = 0L;
      for (Recipe recipe : recipes) {
        if (recipeStopped(household, recipe)) {
          continue;
        }
        long desired = recipe.desiredScale();
        long laborLimited =
            recipe.laborPerUnit() <= 0L
                ? desired
                : Math.max(0L, effectiveLabor / recipe.laborPerUnit());
        long inputLimited = desired;
        for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
          long perUnit = entry.getValue();
          if (perUnit > 0L) {
            inputLimited = Math.min(inputLimited, household.stockOf(entry.getKey()) / perUnit);
          }
        }
        long scale = Math.max(0L, Math.min(Math.min(desired, laborLimited), inputLimited));
        if (scale <= 0L) {
          continue;
        }
        for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
          long used = Math.multiplyExact(scale, entry.getValue());
          household.stock.put(entry.getKey(), household.stockOf(entry.getKey()) - used);
        }
        long output = Math.multiplyExact(scale, recipe.outputPerUnit());
        household.stock.merge(recipe.output(), output, Math::addExact);
        household.lastProducedByGood.merge(recipe.output(), output, Math::addExact);
        effectiveLabor -= scale * recipe.laborPerUnit();
        totalOutput = Math.addExact(totalOutput, output);
      }
      household.lastProduced = totalOutput;
    }

    // ⑨.1 佃农制：产出归佃农 → 交租给地主。
    for (Tenancy tenancy : tenancies) {
      processTenancy(tenancy);
    }

    // ⑨.2 雇农制：产出归经营者 → 给雇农发工资（钱/粮）。
    for (WageFarm farm : wageFarms) {
      processWageFarm(farm);
    }

    // ⑨.3 城市承载/扩建 + 农村商人成本累积。
    updateCitiesAndRuralMerchants();

    Map<String, Map<Good, Long>> refs = new LinkedHashMap<>();
    for (Hex hex : hexes.values()) {
      refs.put(hex.id, Map.copyOf(hex.ref));
    }
    long totalPopulation = 0L;
    long totalDebt = 0L;
    long totalMoney = 0L;
    for (Household household : households) {
      totalPopulation = Math.addExact(totalPopulation, household.population);
      totalDebt = Math.addExact(totalDebt, household.debt);
      totalMoney = Math.addExact(totalMoney, household.money);
    }
    return new RoundResult(
        round,
        totalPopulation,
        deathsTotal,
        birthsTotal,
        totalDebt,
        creditIssued,
        creditRepaid,
        debtDeleted,
        creditIssued - creditBefore,
        creditRepaid - repaymentBefore,
        debtDeletedThisRound,
        totalMoney,
        transportEscrow,
        refs,
        List.copyOf(trades));
  }

  /** 佃农制关账：佃农经营账户 → 产出 → 交租。 */
  /** 运费先给能承运的运输队；没有队伍/运力不足的部分仍落 escrow（旧抽象保留）。 */
  /** 城市承载/慢速扩建 + 农村商人每轮累积贸易成本。 */
  private void updateCitiesAndRuralMerchants() {
    for (TransportTeam team : transportTeams) {
      long upkeep = team.tier.districtUse() * 100L;
      team.lastTradeProfit = team.lastFeeEarned - upkeep;
      if (team.homeIsCity) {
        if (team.lastTradeProfit > 0L && team.capacityPerRound < 100_000L) {
          team.capacityPerRound += 5L;
        } else if (team.lastTradeProfit < 0L && team.capacityPerRound > 5L) {
          team.capacityPerRound -= 5L;
        }
      }
      if (!team.homeIsCity && team.lastUnitsMoved > 0L) {
        team.ruralTradeCostPenaltyPerMille =
            Math.min(
                RURAL_MERCHANT_COST_CAP_PER_MILLE,
                team.ruralTradeCostPenaltyPerMille + RURAL_MERCHANT_COST_GROWTH_PER_MILLE);
      }
    }
    for (CityState city : cities) {
      long used = 0L;
      long population = 0L;
      for (Household household : households) {
        if (household.hexId.equals(city.hexId)) {
          population = Math.addExact(population, household.population);
        }
      }
      used += population / 100L; // 每 100 人占 1 点承载
      for (WageFarm farm : wageFarms) {
        if (farm.operator.hexId.equals(city.hexId)) {
          used += 2L; // 一座作坊占 2 点
        }
      }
      for (TransportTeam team : transportTeams) {
        if (team.homeIsCity && team.operator.hexId.equals(city.hexId)) {
          used += team.tier.districtUse();
        }
      }
      city.cumulativeTradeVolume = Math.addExact(city.cumulativeTradeVolume, city.lastTradeVolume);
      used += city.cumulativeTradeVolume / 1000L; // 累计贸易量转化为承载压力
      city.usedCapacity = used;
      if (used * PER_MILLE >= city.capacity * CITY_EXPANSION_PRESSURE_PER_MILLE) {
        city.expansionProgress += CITY_EXPANSION_SPEED;
        if (city.expansionProgress >= CITY_EXPANSION_THRESHOLD) {
          city.capacity += CITY_EXPANSION_STEP;
          city.expansionProgress = 0L;
          city.expansionCount += 1L;
          city.builtAreaPerMille += CITY_BUILT_AREA_STEP_PER_MILLE;
        }
      }
    }
  }

  private void routeTransportFee(String fromHex, String toHex, long qty, long fee) {
    if (qty <= 0L) {
      return;
    }
    for (CityState city : cities) {
      if (city.hexId.equals(fromHex) || city.hexId.equals(toHex)) {
        city.lastTradeVolume = Math.addExact(city.lastTradeVolume, qty);
      }
    }
    long remainingQty = qty;
    long paidToTeams = 0L;
    // 同一 lane 上多个商人分摊：按列表顺序，每个商人先吃满自己的运力。
    for (TransportTeam team : transportTeams) {
      if (remainingQty <= 0L) {
        break;
      }
      if (!servesLane(team, fromHex, toHex)) {
        continue;
      }
      if (team.remainingCapacity <= 0L) {
        continue;
      }
      long moved = Math.min(remainingQty, team.remainingCapacity);
      team.remainingCapacity -= moved;
      team.lastUnitsMoved = Math.addExact(team.lastUnitsMoved, moved);
      if (fee > 0L) {
        long teamFee = Math.multiplyExact(fee, moved) / qty;
        paidToTeams = Math.addExact(paidToTeams, teamFee);
        team.lastFeeEarned = Math.addExact(team.lastFeeEarned, teamFee);
        team.operator.money = Math.addExact(team.operator.money, teamFee);
      }
      remainingQty -= moved;
    }
    if (fee > 0L && paidToTeams < fee) {
      transportEscrow = Math.addExact(transportEscrow, fee - paidToTeams);
    }
  }

  private void processTenancy(Tenancy tenancy) {
    tenancy.lastScale = 0L;
    tenancy.lastOutput = 0L;
    tenancy.lastLaborUsed = 0L;
    tenancy.lastRentPaid = 0L;
    tenancy.lastRentArrears = 0L;
    Household tenant = tenancy.tenant;
    Recipe recipe = tenancy.recipe;
    if (tenant.population <= 0L || recipeStopped(tenant, recipe)) {
      return;
    }
    long labor = effectiveLabor(tenant);
    long laborLimited =
        recipe.laborPerUnit() <= 0L ? tenancy.desiredScale : labor / recipe.laborPerUnit();
    long inputLimited = tenancy.desiredScale;
    for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
      long perUnit = entry.getValue();
      if (perUnit > 0L) {
        inputLimited = Math.min(inputLimited, tenant.stockOf(entry.getKey()) / perUnit);
      }
    }
    long landLimited =
        tenancy.landPerUnit <= 0L
            ? tenancy.desiredScale
            : availableArableMu(tenant.hexId) / tenancy.landPerUnit;
    long scale =
        Math.max(
            0L,
            Math.min(
                Math.min(tenancy.desiredScale, laborLimited), Math.min(inputLimited, landLimited)));
    if (scale <= 0L) {
      return;
    }
    for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
      long used = Math.multiplyExact(scale, entry.getValue());
      tenant.stock.put(entry.getKey(), tenant.stockOf(entry.getKey()) - used);
    }
    long output =
        Math.multiplyExact(scale, recipe.outputPerUnit()) * tenancy.harvestPerMille / PER_MILLE;
    tenant.stock.merge(recipe.output(), output, Math::addExact);
    tenancy.lastScale = scale;
    tenancy.lastOutput = output;
    tenancy.lastLaborUsed = scale * recipe.laborPerUnit();

    Good rentGood = recipe.output();
    switch (tenancy.rentForm) {
      case FIXED_KIND -> {
        long due = tenancy.fixedKind;
        long paid = Math.min(due, tenant.stockOf(rentGood));
        tenant.stock.put(rentGood, tenant.stockOf(rentGood) - paid);
        tenancy.landlord.stock.merge(rentGood, paid, Math::addExact);
        tenancy.lastRentPaid = paid;
        long shortfall = due - paid;
        tenancy.lastRentArrears = shortfall;
        if (shortfall > 0L) {
          long price = Math.max(1L, currentPrice(tenant.hexId, rentGood));
          tenant.debt = Math.addExact(tenant.debt, Math.multiplyExact(shortfall, price));
        }
      }
      case SHARE -> {
        long due = output * tenancy.landlordSharePerMille / PER_MILLE;
        long paid = Math.min(due, tenant.stockOf(rentGood));
        tenant.stock.put(rentGood, tenant.stockOf(rentGood) - paid);
        tenancy.landlord.stock.merge(rentGood, paid, Math::addExact);
        tenancy.lastRentPaid = paid;
        long shortfall = due - paid;
        tenancy.lastRentArrears = shortfall;
        if (shortfall > 0L) {
          long price = Math.max(1L, currentPrice(tenant.hexId, rentGood));
          tenant.debt = Math.addExact(tenant.debt, Math.multiplyExact(shortfall, price));
        }
      }
      case FIXED_CASH -> {
        long due = tenancy.fixedCash;
        long paid = Math.min(due, tenant.money);
        tenant.money -= paid;
        tenancy.landlord.money = Math.addExact(tenancy.landlord.money, paid);
        tenancy.lastRentPaid = paid;
        long shortfall = due - paid;
        tenancy.lastRentArrears = shortfall;
        if (shortfall > 0L) {
          tenant.debt = Math.addExact(tenant.debt, shortfall);
        }
      }
    }
  }

  /** 雇农制关账：经营者投入资本 → 产出归经营者 → 按合同给雇农发钱/粮。 */
  private void processWageFarm(WageFarm farm) {
    farm.lastScale = 0L;
    farm.lastOutput = 0L;
    farm.lastLaborUsed = 0L;
    farm.lastCashPaid = 0L;
    farm.lastGrainPaid = 0L;
    farm.lastCashArrears = 0L;
    farm.lastGrainArrears = 0L;
    Household operator = farm.operator;
    Recipe recipe = farm.recipe;
    if (operator.population <= 0L || recipeStopped(operator, recipe)) {
      return;
    }
    Map<WageContract, Long> capacity = new IdentityHashMap<>();
    long totalLabor = 0L;
    for (WageContract contract : farm.contracts) {
      long available = effectiveLabor(contract.worker);
      capacity.put(contract, available);
      totalLabor = Math.addExact(totalLabor, available);
    }
    long laborLimited =
        recipe.laborPerUnit() <= 0L ? farm.desiredScale : totalLabor / recipe.laborPerUnit();
    long inputLimited = farm.desiredScale;
    for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
      long perUnit = entry.getValue();
      if (perUnit > 0L) {
        inputLimited = Math.min(inputLimited, operator.stockOf(entry.getKey()) / perUnit);
      }
    }
    long landLimited =
        farm.landPerUnit <= 0L
            ? farm.desiredScale
            : availableArableMu(operator.hexId) / farm.landPerUnit;
    long scale =
        Math.max(
            0L,
            Math.min(
                Math.min(farm.desiredScale, laborLimited), Math.min(inputLimited, landLimited)));
    if (scale <= 0L) {
      return;
    }
    for (Map.Entry<Good, Long> entry : recipe.inputPerUnit().entrySet()) {
      long used = Math.multiplyExact(scale, entry.getValue());
      operator.stock.put(entry.getKey(), operator.stockOf(entry.getKey()) - used);
    }
    long output =
        Math.multiplyExact(scale, recipe.outputPerUnit()) * farm.harvestPerMille / PER_MILLE;
    operator.stock.merge(recipe.output(), output, Math::addExact);
    farm.lastScale = scale;
    farm.lastOutput = output;
    long laborUsed = scale * recipe.laborPerUnit();
    farm.lastLaborUsed = laborUsed;

    long remainingLabor = laborUsed;
    for (WageContract contract : farm.contracts) {
      long available = capacity.getOrDefault(contract, 0L);
      long allocated = Math.max(0L, Math.min(remainingLabor, available));
      remainingLabor -= allocated;
      contract.lastLaborUsed = allocated;
      contract.lastCashPaid = 0L;
      contract.lastGrainPaid = 0L;
      contract.lastCashArrears = 0L;
      contract.lastGrainArrears = 0L;
      if (allocated <= 0L) {
        continue;
      }
      long grainDue = Math.multiplyExact(allocated, contract.grainPerLabor);
      long grainPaid = Math.min(grainDue, operator.stockOf(Good.GRAIN));
      operator.stock.put(Good.GRAIN, operator.stockOf(Good.GRAIN) - grainPaid);
      contract.worker.stock.merge(Good.GRAIN, grainPaid, Math::addExact);
      long grainArrears = grainDue - grainPaid;
      if (grainArrears > 0L) {
        long price = Math.max(1L, currentPrice(operator.hexId, Good.GRAIN));
        operator.debt = Math.addExact(operator.debt, Math.multiplyExact(grainArrears, price));
      }
      long cashDue = Math.multiplyExact(allocated, contract.cashPerLabor);
      long cashPaid = Math.min(cashDue, operator.money);
      operator.money -= cashPaid;
      contract.worker.money = Math.addExact(contract.worker.money, cashPaid);
      long cashArrears = cashDue - cashPaid;
      if (cashArrears > 0L) {
        operator.debt = Math.addExact(operator.debt, cashArrears);
      }
      contract.lastGrainPaid = grainPaid;
      contract.lastGrainArrears = grainArrears;
      contract.lastCashPaid = cashPaid;
      contract.lastCashArrears = cashArrears;
      farm.lastGrainPaid = Math.addExact(farm.lastGrainPaid, grainPaid);
      farm.lastGrainArrears = Math.addExact(farm.lastGrainArrears, grainArrears);
      farm.lastCashPaid = Math.addExact(farm.lastCashPaid, cashPaid);
      farm.lastCashArrears = Math.addExact(farm.lastCashArrears, cashArrears);
    }

    // 周期固定现金工资（长工的年/季固定部分；探针里单独一条，避免只靠 per-labor 取整）。
    if (farm.fixedCashPerRound > 0L && !farm.contracts.isEmpty()) {
      WageContract first = farm.contracts.get(0);
      if (first.lastLaborUsed > 0L) {
        long due = farm.fixedCashPerRound;
        long paid = Math.min(due, operator.money);
        operator.money -= paid;
        first.worker.money = Math.addExact(first.worker.money, paid);
        long arrears = due - paid;
        if (arrears > 0L) {
          operator.debt = Math.addExact(operator.debt, arrears);
        }
        first.lastCashPaid = Math.addExact(first.lastCashPaid, paid);
        first.lastCashArrears = Math.addExact(first.lastCashArrears, arrears);
        farm.lastCashPaid = Math.addExact(farm.lastCashPaid, paid);
        farm.lastCashArrears = Math.addExact(farm.lastCashArrears, arrears);
      }
    }
  }

  private boolean recipeStopped(Household household, Recipe recipe) {
    Good output = recipe.output();
    long ref = currentPrice(household.hexId, output);
    long cost = household.cost.getOrDefault(output, Long.MAX_VALUE);
    return ref > 0L && ref < cost;
  }

  private static long valueAt(Map<String, Map<Good, Long>> table, String hexId, Good good) {
    Map<Good, Long> row = table.get(hexId);
    return row == null ? 0L : row.getOrDefault(good, 0L);
  }
}
