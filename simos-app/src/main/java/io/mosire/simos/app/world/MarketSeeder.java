package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.world.EconomySeeder.OperatorSeed;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 创世市场、货币禀赋、政府/发行载荷节点。 */
class MarketSeeder extends WorldBalanceSeeder {

  /**
   * ★★ <b>P5b2：创世区级地价的工程默认（毫计价货币 / 整亩）</b> = {@value #GENESIS_LAND_PRICE_MILLI_PER_MU}。
   *
   * <p>★ <b>单位与三态</b>：{@link MarketTable#landPrice()} 是 {@link java.util.OptionalLong}；本常量给出 {@code
   * OptionalLong.of(10)}（<b>明确有价</b>，不是 empty）。empty 仍只用于"未定价"，{@code 0} 仍只用于"明确免费"。
   * 所有派生市场表（{@code single-region} / {@code currency:<币>}）都取本值 ⇒ 土地市场在创世就有区级参考价、能生成土地订单。
   *
   * <p>★ <b>标定口径（为什么是 10，不是 0/1000）</b>：农户手持粮食财富约 3,000–4,500 毫、创世钱包约 100–1,000 毫；本世界一个
   * 1,085 亩的农场按出厂价一周期净产约 3.3 万毫。10 毫/亩让"整块 1,085 亩 ≈ 1.085 万毫"落在可交易量级（约为该农场一周期净产的
   * 1/3），既不把土地订单永远挡在预算外，也不把土地估值抬到与商品账完全脱节。★ 它是工程默认，不是用户裁定；P2 的市场轮地价自适应
   * alpha/结算语义一字不动。
   */
  static final long GENESIS_LAND_PRICE_MILLI_PER_MU = 10L;

  /**
   * ★★ <b>P5b2：创世工资锚的工程默认（毫计价货币 / 毫劳动）</b> = {@value #GENESIS_WAGE_PER_LABOR_MILLI}。
   *
   * <p>★ <b>非 0、可观测</b>：工资锚 quote 走 {@code due = quote × laborMilli / 1000}；本值让本世界一支"真实初始雇工"
   * 的日工资 = {@code 20 × plannedLaborMilliPerDay / 1000}，在已播种的农场净产（出厂价）内保持正收益。★ 具体标定见 P5b2 台账：
   * 被雇流民户的参与率折算劳动约 4–6 千毫/日；最大农场（1,085 亩）一周期净产约 3.3 万毫 ⇒ 工资账单约占净产 20–30%。★ 它是工程默认，
   * 不是用户裁定；U20 的调价公式与 policy 不改。
   */
  static final long GENESIS_WAGE_PER_LABOR_MILLI = 20L;

  /**
   * ★★ <b>B1：给定计价币的一格市场</b>（价格表恒为 {@link #MARKET_PRICES_FACTORY}，只有计价币换）。
   *
   * <p>★ 它<b>不折算任何价格</b>：价格是"每商品单位的毫计价货币"，换了计价币就按新币读同一个数 —— 世界上没有汇率（I17），播种器也不许替 GM 换汇。 ★★
   * W1：本方法给出的是**逐格**形态的价表（成员格 = 一个格），创世期把它按币归组成"每币一张派生价表" （{@link #derivedMarketTables}）；{@code
   * MARKET_FACTORY} 常量随"逐格价表"一并退役。
   */
  public static MarketTable marketFor(CurrencyId numeraire) {
    Objects.requireNonNull(numeraire, "numeraire");
    return new MarketTable(numeraire, Set.of(), MARKET_PRICES_FACTORY);
  }

  /**
   * ★★ <b>W1：派生价表（键 = 派生市场 id）</b>—— 创世期"只有计价币、还没有市场区"的世界形态。
   *
   * <pre>
   * 全格同币 ⇒ 一张 {@code single-region}（成员格 = 全部有市场的格；与改动前 MarketTopology.singleRegion 的区 id 同源）
   * 多币混用 ⇒ 逐币一张 {@code currency:<币>}（成员格 = 用该币的格）
   * </pre>
   *
   * <p>★ <b>为什么按币分组</b>：区级价表自带计价币（{@link MarketTable#numeraire()}），而"这一格用什么钱"在改动前 是逐格 {@code
   * Market.numeraire}。分币分组是"不丢信息"的最小映射：同一币的格共用一张表，跨币的格不许共用 （那会把两种钱的价混成一张）。★ 键是<b>内容派生</b>的（币种 id
   * 升序）：同一份输入两次调用逐字节相同。
   */
  static Map<MarketZoneId, MarketTable> derivedMarketTables(
      Map<CurrencyId, Set<HexCoord>> hexesByNumeraire) {
    Map<MarketZoneId, MarketTable> tables = new LinkedHashMap<>();
    if (hexesByNumeraire.size() == 1) {
      Map.Entry<CurrencyId, Set<HexCoord>> only = hexesByNumeraire.entrySet().iterator().next();
      tables.put(
          new MarketZoneId(SINGLE_REGION_MARKET_ID),
          new MarketTable(only.getKey(), only.getValue(), MARKET_PRICES_FACTORY)
              // ★★ P5b2：创世派生市场表 = 明确有价（不是 empty、也不是 0）。
              .withLandPrice(GENESIS_LAND_PRICE_MILLI_PER_MU));
      return Collections.unmodifiableMap(tables);
    }
    List<CurrencyId> currencies = new ArrayList<>(hexesByNumeraire.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      tables.put(
          new MarketZoneId("currency:" + currency.value()),
          new MarketTable(currency, hexesByNumeraire.get(currency), MARKET_PRICES_FACTORY)
              .withLandPrice(GENESIS_LAND_PRICE_MILLI_PER_MU));
    }
    return Collections.unmodifiableMap(tables);
  }

  /**
   * ★★ P10.1/P11.7：PRODUCTION_RUNTIME 新档写入的运行时版本（{@link
   * EconomyMeta#RUNTIME_VERSION_SEVEN_HEX_V2}）—— 唯一拼写点在 {@link EconomyMeta}；写入前先过版本门自检（当前版本 ≠ 自检值 ⇒
   * 当场抛"旧档/版本不符，需要 GM 重置"，而不是把错版本写进新档）。
   */
  static String productionRuntimeRulesVersion() {
    EconomyMeta.requireCurrentRuntimeVersionTag(EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2);
    return EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2;
  }

  static List<Map<String, Object>> governmentNodes(Map<GovernmentId, Government> governments) {
    List<Map<String, Object>> nodes = new ArrayList<>(governments.size());
    for (Government government : governments.values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", government.id().value());
      node.put("nationRef", government.nationRef());
      Map<String, Object> treasury = new LinkedHashMap<>();
      treasury.put("kind", government.treasury().kind().name());
      treasury.put("id", government.treasury().id());
      node.put("treasury", treasury);
      List<String> issuable = new ArrayList<>(government.issuable().size());
      for (CurrencyId currency : government.issuable()) {
        issuable.add(currency.value());
      }
      node.put("issuable", issuable);
      // ★ 旧行为逐字节不变：旋钮为 0 时不写键（读口按缺省 0 回读）。
      //   ★★ W5：只有一个旋钮了（双轨合并）；"上一轮印量"**不进这个节点** —— 它是结算写的事实
      //     （MintSettlement 每轮覆盖写），把它当 seed 参数发出去等于让"播种"覆盖运行期事实。
      if (government.mintScalePerCycle() > 0L) {
        node.put("mintScalePerCycle", government.mintScalePerCycle());
      }
      nodes.add(node);
    }
    return nodes;
  }

  /**
   * E3：{@code INITIAL_ENDOWMENT} 的逐币种聚合记录（id 含本 seed 的格集锚点 ⇒ 多国 seed 各自一条、不互相覆盖）。 记录总量 = 家户钱包 +
   * 经营者钱包（同一次 {@link #plan} 的同一份表）。
   */
  static List<Map<String, Object>> issuanceNodes(
      String mapId,
      List<Map<String, Object>> entries,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita) {
    return issuanceNodes(
        mapId,
        entries,
        genesisEndowment,
        "GM 代 GOV 创世初始禀赋："
            + genesisMoneyMilliPerCapita
            + " 毫/人（含经营者工资周转金；"
            + "总量按 actor.Seed 的家户+经营者钱包逐值汇总）");
  }

  /** ★★ R2a：同上，但 reason 由调用方给出（调用方自行给出可审计原因）。 */
  static List<Map<String, Object>> issuanceNodes(
      String mapId,
      List<Map<String, Object>> entries,
      Map<CurrencyId, Long> genesisEndowment,
      String reason) {
    if (genesisEndowment.isEmpty()) {
      return List.of();
    }
    String anchor = seedAnchor(entries);
    List<Map<String, Object>> nodes = new ArrayList<>(genesisEndowment.size());
    for (Map.Entry<CurrencyId, Long> amount : genesisEndowment.entrySet()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put(
          "id",
          new MoneyIssuanceId("endowment-" + mapId + "-" + anchor + "-" + amount.getKey().value())
              .value());
      node.put("governmentId", GENESIS_GOVERNMENT_ID.value());
      node.put("currency", amount.getKey().value());
      node.put("amount", amount.getValue());
      node.put("kind", "INITIAL_ENDOWMENT");
      node.put("reason", reason);
      nodes.add(node);
    }
    return nodes;
  }

  /** 本 seed 格集的最小 {@code (q,r)} 规范串（ENDOWMENT id 的确定性锚点；空格集取 {@code none}）。 */
  static String seedAnchor(List<Map<String, Object>> entries) {
    String min = null;
    for (Map<String, Object> entry : entries) {
      Object q = entry.get("q");
      Object r = entry.get("r");
      if (!(q instanceof Number qNumber) || !(r instanceof Number rNumber)) {
        continue;
      }
      String key = qNumber.intValue() + "_" + rNumber.intValue();
      if (min == null) {
        min = key;
        continue;
      }
      String[] minParts = min.split("_", -1);
      int minQ = Integer.parseInt(minParts[0]);
      int minR = Integer.parseInt(minParts[1]);
      int qInt = qNumber.intValue();
      int rInt = rNumber.intValue();
      if (qInt < minQ || (qInt == minQ && rInt < minR)) {
        min = key;
      }
    }
    return min == null ? "none" : min;
  }

  /**
   * 一格市场的载荷节点：{@code {numeraire, hexes, prices, landPrice?}}（键序固定 ⇒ 载荷字节可复现）。
   *
   * <p>★ <b>P5b2 三态</b>：{@code landPrice} 只在 {@link MarketTable#landPrice()} 为 present 时发出；缺键 ⇒ 解析侧读成
   * {@code OptionalLong.empty()}（未定价），值 {@code 0} ⇒ 明确免费。绝不把 empty 写成 0。
   */
  static Map<String, Object> marketNode(MarketTable market) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("numeraire", market.numeraire().value());
    List<String> hexes = new ArrayList<>(market.hexes().size());
    for (HexCoord hex : market.hexes()) {
      hexes.add(hex.toString()); // ★ 格串的唯一拼写点在 HexCoord（本类不自己拼 "<q>_<r>"）
    }
    node.put("hexes", hexes);
    Map<String, Object> prices = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> price : market.prices().entrySet()) {
      prices.put(price.getKey().value(), price.getValue());
    }
    node.put("prices", prices);
    market.landPrice().ifPresent(price -> node.put("landPrice", price));
    return node;
  }

  /**
   * ★★★ <b>P5b2：创世工资锚节点（{@code economy.Seed.wageAnchors[]}）</b>——逐实际带 {@code hired_laborer} 角色的
   * plan，按该 plan 所在派生市场表生成一条。
   *
   * <p>键 = {@code (marketZone, industry, role)}：{@code marketZone} 取该 hex 所属派生价表的 id，{@code industry} =
   * plan id，{@code role} = {@link DefaultProductionModes#ROLE_HIRED_LABORER}；币种 = 该市场表的 {@code numeraire}。
   * 历史字段按 P4b1 默认：streak=0、{@code lastAdjustedTick=-1}、观测=0、delta=0。★ 不生成 0 兜底：工资值取具名工程常量
   * {@link #GENESIS_WAGE_PER_LABOR_MILLI}。
   *
   * <p>★ <b>为什么从 plan 节点识别角色</b>：本方法只读 {@code entries[].plans[].roles} 的线格式，不复制 production 定义；
   * 只有显式带 {@code hired_laborer} 角色的 plan（当前 = 每个 hex 的 {@code grain_farm}）会得到锚。
   */
  static List<Map<String, Object>> wageAnchorNodes(
      List<Map<String, Object>> entries, Map<MarketZoneId, MarketTable> markets) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      Object q = entry.get("q");
      Object r = entry.get("r");
      if (!(q instanceof Number qNumber) || !(r instanceof Number rNumber)) {
        continue;
      }
      HexCoord hex = new HexCoord(qNumber.intValue(), rNumber.intValue());
      MarketZoneId zone = marketZoneAt(markets, hex);
      MarketTable table = markets.get(zone);
      Object rawPlans = entry.get("plans");
      if (!(rawPlans instanceof List<?> plans)) {
        continue;
      }
      for (Object rawPlan : plans) {
        if (!(rawPlan instanceof Map<?, ?> plan)) {
          continue;
        }
        Object rawId = plan.get("id");
        if (!(rawId instanceof String planId) || planId.isBlank()) {
          continue;
        }
        if (!hasHiredLaborerRole(plan)) {
          continue;
        }
        nodes.add(wageAnchorNode(zone, planId, table.numeraire()));
      }
    }
    return nodes;
  }

  /** plan 节点的 {@code roles[]} 里是否显式有 {@code hired_laborer}（只读线格式，不猜缺省角色）。 */
  private static boolean hasHiredLaborerRole(Map<?, ?> plan) {
    Object rawRoles = plan.get("roles");
    if (!(rawRoles instanceof List<?> roles)) {
      return false;
    }
    for (Object rawRole : roles) {
      if (rawRole instanceof Map<?, ?> role
          && DefaultProductionModes.ROLE_HIRED_LABORER.equals(role.get("id"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ 一格所属的派生市场表 id（成员格唯一权威）；不属于任何创世市场表 ⇒ 装配故障 fail-closed（不猜 single-region）。
   */
  static MarketZoneId marketZoneAt(Map<MarketZoneId, MarketTable> markets, HexCoord hex) {
    for (Map.Entry<MarketZoneId, MarketTable> table : markets.entrySet()) {
      if (table.getValue().hexes().contains(hex)) {
        return table.getKey();
      }
    }
    throw new IllegalStateException("格 " + hex + " 不属于任何创世派生市场表（seed 内部不一致）");
  }

  /** 一条工资锚载荷节点（键序固定、历史字段取 P4b1 默认）。 */
  static Map<String, Object> wageAnchorNode(
      MarketZoneId zone, String industry, CurrencyId currency) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("marketZone", zone.value());
    node.put("industry", industry);
    node.put("role", DefaultProductionModes.ROLE_HIRED_LABORER);
    node.put("currency", currency.value());
    node.put("wagePerLaborMilli", GENESIS_WAGE_PER_LABOR_MILLI);
    node.put("shortageStreak", 0L);
    node.put("satisfiedStreak", 0L);
    node.put("lastAdjustedTick", -1L);
    node.put("lastObservedMatchedLaborMilli", 0L);
    node.put("lastObservedTargetLaborMilli", 0L);
    node.put("lastAdjustmentDelta", 0L);
    return node;
  }

  /**
   * ★★ <b>创世货币禀赋的每人出厂值（毫银）</b>（裁定 K14）—— <b>按人口</b>的一次性初始条件：
   *
   * <pre>
   * 每人一个周期的口粮 = RATION_MILLI_PER_PERSON = 10,000 毫粮（= 10 粮，120 天）
   * 买下它的钱       = 10,000 毫粮 × 粮价 1 ÷ 1000 = 10 毫银          ← 价量表的口径：毫银 / 商品单位
   * 禀赋             = 10 毫银 × 缓冲 1200‰ = 12 毫银（毫银的最小币值）
   * </pre>
   *
   * ★ <b>它为什么"够买一个周期的口粮"</b>：家户一个周期（{@link #CYCLE_DAYS} 120 天）要买的口粮 = {@code
   * RATION_MILLI_PER_PERSON} = 10,000 毫粮/人 ⇒ 按挂牌粮价折 **10 毫银**；本值 **12 毫银 = 1.2 倍** （多出的两成是周转余量，见
   * {@link #GENESIS_MONEY_BUFFER_PER_MILLE}）。★ 口径与结算**同源**：口粮毫数取自 {@link
   * EconomyVocabulary#RATION_MILLI_PER_PERSON}（与 {@code 旧结算引擎（R3a 已删除）} 每日需求同一个常量）， 价格换算的 {@code ÷
   * 1000} 与 {@code MarketTable} 的货款公式**逐字同式**，不是本类另拍的数。
   *
   * <p>★★ <b>E3：初始禀赋就是一次显式 INITIAL_ENDOWMENT 发行</b>（GM 代 GOV，发行主体 = 世界级最小政府）。本方法仍只算 <b>每人金额</b>；总量由
   * {@link #genesisEndowmentOf(Map, List)} 对同一份钱包表求和，绝不另算一遍人口。默认值不变， 参数化入口见 {@link
   * #genesisMoney(long, long)} 与 {@code plan(..., long)}。
   */
  public static long genesisMoneyMilliPerCapita() {
    return EconomyVocabulary.RATION_MILLI_PER_PERSON
        * MARKET_PRICE_GRAIN
        * GENESIS_MONEY_BUFFER_PER_MILLE
        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
        / 1000L;
  }

  /**
   * 一个家户的创世钱包（毫银）：{@code 人口 × }{@link #genesisMoneyMilliPerCapita()}。
   *
   * <p>★ <b>口径与开缸库存逐字同形</b>（见 {@link #openingStock}）：<b>只落正的量</b> —— 人口 0 的家户拿到一本
   * **空的钱包**（键在、表空），与"一本空账"同义；"没有钱"与"没有这个家户"是两件事。
   *
   * <p>★ <b>可见性 = public</b>（H4 收口）：它是**跨包**的测试夹具（{@code app.gui.GuiApiTest} 与 {@code
   * app.world.EconomyTestWorld} 都要造出"与真播种器逐值相同的钱包"）—— 口径只有一个拼写点，夹具不许自己再拍一个价。
   */
  public static Map<CurrencyId, Long> genesisMoney(long population) {
    return genesisMoney(population, genesisMoneyMilliPerCapita());
  }

  /** ★★ E3：一个家户的创世钱包（毫计价货币），每人金额可注入。金额 &lt; 0 或乘法溢出 ⇒ 当场抛； 金额 0 ⇒ 空钱包（与旧"只落正的量"逐值相同）。 */
  public static Map<CurrencyId, Long> genesisMoney(
      long population, long genesisMoneyMilliPerCapita) {
    return genesisMoney(population, genesisMoneyMilliPerCapita, MARKET_NUMERAIRE);
  }

  /**
   * ★★ <b>B1：一个家户的创世钱包，币种可注入</b>（多币种世界：本区法定币）。
   *
   * <p>★ <b>为什么币种必须与同格的 {@code MarketTable.numeraire} 同源</b>：家户要在**本地市场**买口粮 —— 若它的钱包是银、
   * 而本格市场按铜报价，则每一次买盘都会撞上 I19 的异币具名拒（{@code currency_mismatch}），
   * 这个家户在本地市场上等于没有购买力。故两者由**同一个**逐格函数给出（见 {@code plan(..., numeraireOf)}）。
   *
   * <p>★ 金额口径与老入口逐值相同（{@code 人口 × 每人毫数}）；2 参入口等价于本方法传 {@link #MARKET_NUMERAIRE}。
   */
  public static Map<CurrencyId, Long> genesisMoney(
      long population, long genesisMoneyMilliPerCapita, CurrencyId numeraire) {
    Objects.requireNonNull(numeraire, "numeraire");
    if (population < 0L) {
      throw new IllegalArgumentException("population 不得为负: " + population);
    }
    if (genesisMoneyMilliPerCapita < 0L) {
      throw new IllegalArgumentException(
          "genesisMoneyMilliPerCapita 不得为负: " + genesisMoneyMilliPerCapita);
    }
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    long amount = Math.multiplyExact(population, genesisMoneyMilliPerCapita);
    if (amount > 0L) {
      wallet.put(numeraire, amount);
    }
    return wallet;
  }

  /**
   * E3：创世初始发行总量 = 家户钱包 + 经营者钱包的逐币种纯和（与 {@code actor.Seed} 的同一份表）。 只保留正数键；0 不造记录（"没有发行"与"发行 0"是两件事）。
   */
  static Map<CurrencyId, Long> genesisEndowmentOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney, List<OperatorSeed> operators) {
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (Map<CurrencyId, Long> wallet : householdMoney.values()) {
      mergeWallet(totals, wallet);
    }
    for (OperatorSeed operator : operators) {
      mergeWallet(totals, operator.money());
    }
    return Collections.unmodifiableMap(totals);
  }

  private static void mergeWallet(Map<CurrencyId, Long> totals, Map<CurrencyId, Long> wallet) {
    for (Map.Entry<CurrencyId, Long> entry : wallet.entrySet()) {
      if (entry.getValue() == 0L) {
        continue;
      }
      totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
  }

  /**
   * ★★ <b>经营者的创世<strong>工资周转金</strong></b>（毫计价货币；H5 ⑤）：<b>它自己那条制度里全部货币档规则的 每周期应付之和 × {@link
   * #GENESIS_MONEY_BUFFER_PER_MILLE}÷1000</b>。
   *
   * <pre>
   * handicraft：4 条 FIXED_MONEY_WAGE × 1,000 毫/周期 = 4,000 毫银/周期
   *             × 1200‰（与家户禀赋同一个缓冲）= **4,800 毫银**（= 1.2 个周期的工资）
   * 其余三档（feudal / household / tenant）：没有货币档规则 ⇒ 0 ⇒ 空钱包
   * </pre>
   *
   * <p>★★ <b>为什么口径取自"制度里的货币档"而不是另拍一个数</b>：{@code RegimeRelations} 是"每周期该付多少"的**唯一拼写点** ——
   * 播种器另写一份必然与它对不上（改一个数要改两处，而漏改不会报错）。
   *
   * <p>★★ <b>如实记的边界（本批没做的事）</b>：本批**没有任何钱回流到经营者的通道**（同格市场只在家户之间；经营者作为市场 参与者是后续批次）⇒
   * 这笔周转金是**一次性的**：它付完 N=1.2 个周期的工资就见底（此后货币工资回到"实付 0"， 读数里 {@code due > 0 && paid == 0} 看得见）。★
   * 这不是"静默付 0"：付不出是**账面上读得出来**的状态； 要让它长期成立，得让经营者**卖得掉它的布与工具**（市场参与者扩容）。
   */
  static long operatorWageReserveMilli(String regime, IndustryId id, ActorRef owner) {
    ProductionRules relation =
        RegimeRelations.defaultRelation(
            new RegimeId(regime),
            ProductionUnitId.idOf(id, owner),
            id,
            owner,
            Set.of(ResidenceKind.URBAN));
    long perCycle = 0L;
    for (CompensationRule rule : relation.rules()) {
      if (rule.type().money()) {
        perCycle += rule.fixedAmount();
      }
    }
    return perCycle * GENESIS_MONEY_BUFFER_PER_MILLE / 1000L;
  }

  /** 一个经营者的开缸钱包（毫计价货币；0 ⇒ 空钱包）—— 逐字照 {@link #genesisMoney(long)} 的"只落正的量"。 */
  static Map<CurrencyId, Long> operatorWallet(long milli) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>();
    if (milli > 0L) {
      wallet.put(MARKET_NUMERAIRE, milli);
    }
    return wallet;
  }
}
