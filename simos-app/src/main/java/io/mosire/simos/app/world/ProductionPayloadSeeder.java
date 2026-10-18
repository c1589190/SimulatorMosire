package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.MarketSeeder.governmentNodes;
import static io.mosire.simos.app.world.MarketSeeder.issuanceNodes;
import static io.mosire.simos.app.world.MarketSeeder.marketNode;
import static io.mosire.simos.app.world.MarketSeeder.productionRuntimeRulesVersion;
import static io.mosire.simos.app.world.ProductionRoleSeeder.productionRuntimeClassMemberships;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** economy.Seed / production.Seed 的最终 JSON 载荷与目录节点装配。 */
class ProductionPayloadSeeder extends WorldBalanceSeeder {

  /**
   * 把 {@code entries} 与 {@code markets} 包成 {@code economy.Seed} 的载荷文本（{@code mapId} / {@code
   * rulesVersion} 在顶层）。
   *
   * <p>★★ <b>H4 的市场载荷（app 写、economy 读的形状）</b>：
   *
   * <pre>{@code
   * {"mapId":"Map1","rulesVersion":"aggregate-v1","entries":[…],
   *  "markets":{"single-region":{"numeraire":"silver","hexes":["0_0","0_1"],
   *                             "prices":{"grain":1,"cloth":5,"fiber":1,"tool":20,"iron":10}}}}
   * }</pre>
   *
   * ★★ <b>W1：键 = 市场 id、值 = {@code {numeraire, hexes, prices}}</b>（逐格价表已退役）—— 全格同币 ⇒ 一张 {@code
   * single-region}；多币 ⇒ 逐币一张 {@code currency:<币>}（见 {@link #derivedMarketTables}）。★ 格串一律由 {@link
   * HexCoord#toString()} 给出（本类**不自己拼**那个格式），成员格按 {@code (q,r)} 字典序 ⇒ 同一份输入两次调用 **逐字节**相同。
   *
   * <p>★★ <b>缺格的格 = 没有市场</b>（合法状态）：市场只在"本格有经济 entry"时发出（格集 = 批次落点集合）——
   * "这一格没有市场"与"这一格不存在"是两件事，读口照此回答（{@code ApiViews.economyHex}）。
   */
  /**
   * ★★ <b>P3：带初始条件产物的载荷构造</b>。{@code debtContracts} 由 {@link #applyTestConditions}
   * 在真实转账/拆分成功后给出；{@code extraMoneyIssuances} 是外部注入货币的 {@code FISCAL_ISSUE} 审计节点（**不并入**
   * INITIAL_ENDOWMENT）；{@code conditionReport} 非空时额外落一个顶层 {@code testConditions} 报告键（可审计"这次 seed
   * 注入了什么"）。
   *
   * <p>★★ <b>无 conditions 的路径逐字节不变</b>：条件表为空 + 报告为空 ⇒ 不出现 {@code testConditions} 键， {@code
   * debtContracts} 仍是空数组，与本方法 P1 版本的输出逐字节相同。
   *
   * <p>★★ production-runtime 在<b>本方法末尾追加</b>六个顶层键：{@code modes} / {@code classStructures} / {@code
   * classPositions} / {@code classStandings} / {@code assetRules} / {@code
   * liquidationPolicies}（默认目录来自 {@code DefaultProductionModes}）。
   */
  static String jsonOf(
      String mapId,
      List<Map<String, Object>> entries,
      Map<MarketZoneId, MarketTable> markets,
      Map<GovernmentId, Government> governments,
      Map<CurrencyId, Long> genesisEndowment,
      long genesisMoneyMilliPerCapita,
      List<Map<String, Object>> debtContracts,
      List<Map<String, Object>> extraMoneyIssuances,
      List<Map<String, Object>> productionRosters,
      TestConditions.Report conditionReport) {
    // ★★★ D-032 C3（D2 §5.5）：多角色 plan 的 unit 缺显式名册 ⇒ 创世失败（seed-roster-missing:<unit>）。
    requireRostersForMultiRoleUnits(entries, productionRosters);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mapId", mapId);
    // ★★ P10.1：新档写当前 7 hex 运行时版本（唯一拼写点在 {@code EconomyMeta}）。
    payload.put("rulesVersion", productionRuntimeRulesVersion());
    payload.put("entries", entries);
    Map<String, Object> marketNodes = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, MarketTable> atMarket : markets.entrySet()) {
      marketNodes.put(atMarket.getKey().value(), marketNode(atMarket.getValue()));
    }
    payload.put("markets", marketNodes);
    // ★★★ D-032 C3（D2 §5.5）：顶层 productionRosters[]（每项与 §5.4 同形；plan 不由载荷给）。
    //   空表 = 单角色 unit 走 C2 自动名册；多角色 unit 必须显式（上面的守卫已判）。
    payload.put("productionRosters", productionRosters == null ? List.of() : productionRosters);
    // ★★ E3：政府与 INITIAL_ENDOWMENT 发行记录（缺省长（ENDOWMENT=零）时只有 governments；旧载荷缺这两个键 ⇒ 空表）。
    payload.put("governments", governmentNodes(governments));
    List<Map<String, Object>> issuances =
        new ArrayList<>(
            issuanceNodes(mapId, entries, genesisEndowment, genesisMoneyMilliPerCapita));
    // ★★ P3：外部注入的货币走独立的 FISCAL_ISSUE 记录 —— 绝不并进 INITIAL_ENDOWMENT 的总量（见 TestConditions）。
    if (extraMoneyIssuances != null) {
      issuances.addAll(extraMoneyIssuances);
    }
    payload.put("moneyIssuances", issuances);
    // ★★ E4c/P3：新键的空语义 —— 无条件时发空表（世界从零债开始，合法）；有条件时**只发真实对价已备好的**债务
    //   （见 applyTestConditions：债权人库存/货币真扣）。
    payload.put("debtContracts", debtContracts == null ? List.of() : debtContracts);
    if (conditionReport != null && !conditionReport.isEmpty()) {
      // ★ 条件应用的可读报告（条数 + 外部注入总量 + 具名 reason）：随载荷走，读口/审计可核。
      payload.put("testConditions", conditionReport.toWireMap());
    }
    // ★★ P2：在既有键集之后**追加**六个顶层键 —— 顺序固定为 modes / classStructures /
    //   classPositions / classStandings / assetRules。
    // ★★ P2：基础六表（mode/结构/位置/standing）后追加资产规则；清算政策与规则 id 同源派生 ⇒ 引用必然对得上。
    payload.put("modes", productionModeNodes());
    payload.put("classStructures", classStructureNodes());
    payload.put("classPositions", productionRoleNodes());
    payload.put("classStandings", productionRuntimeClassMemberships(entries));
    payload.put("assetRules", productionRuntimeAssetRuleNodes());
    // ★★ M-A1：商号行（merchantFirms）已退役 —— 运力是派生量（MerchantCapacityPool），创世不再发这个顶层键。
    return ToolSupport.json(payload);
  }

  /**
   * ★★ <b>默认生产方式目录 → {@code modes[]} 节点</b>：{@code {id,name,version,classStructureId}}。
   *
   * <p>键序 = {@code DefaultProductionModes.modes()} 的稳定声明序；每个键名与 {@code EconomyPayloads.parseModes}
   * 的读取名逐字一致（不在这里发明第二套字段名）。
   */
  static List<Map<String, Object>> productionModeNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionModeDefinitionView mode : DefaultProductionModes.modes().values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", mode.id().value());
      node.put("name", mode.name());
      node.put("version", mode.version());
      node.put("classStructureId", mode.classStructureId().value());
      nodes.add(node);
    }
    return nodes;
  }

  /**
   * ★★ <b>默认阶层结构 → {@code classStructures[]} 节点</b>：{@code
   * {id,modeId,positions:[...],defaultSharesPerMille:{...}}}。
   *
   * <p>{@code positions} 与下面的扁平 {@code classPositions[]} 由<b>同一个</b> {@link #productionRoleNode} 生成
   * ⇒ 结构内位置与全局表逐字段同形，{@code EconomyData} 的“结构内位置 == 全局位置表”守卫自然成立。
   */
  static List<Map<String, Object>> classStructureNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ClassStructure structure : DefaultProductionModes.classStructures().values()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", structure.id().value());
      node.put("modeId", structure.modeId().value());
      List<Map<String, Object>> positions = new ArrayList<>();
      for (ProductionRole position : structure.positions().values()) {
        positions.add(productionRoleNode(position));
      }
      node.put("positions", positions);
      Map<String, Object> shares = new LinkedHashMap<>();
      for (Map.Entry<ClassPositionId, Long> share : structure.defaultSharesPerMille().entrySet()) {
        shares.put(share.getKey().value(), share.getValue());
      }
      node.put("defaultSharesPerMille", shares);
      nodes.add(node);
    }
    return nodes;
  }

  /** ★ 默认全局位置表 → 扁平的 {@code classPositions[]} 节点（与结构内 {@code positions} 逐字段相同）。 */
  static List<Map<String, Object>> productionRoleNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionRole position : DefaultProductionModes.classPositions().values()) {
      nodes.add(productionRoleNode(position));
    }
    return nodes;
  }

  /**
   * ★ 一个阶层位置的线格式节点：{@code {id,modeId,name,relationToMeans,laborRole,surplusRole,ruleExtensions?}}。
   *
   * <p>{@code ruleExtensions} 只在非空时发出（P1 全空）；缺键 ⇒ 解析器给空表，不在这里多写一个空对象。
   */
  static Map<String, Object> productionRoleNode(ProductionRole position) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", position.id().value());
    node.put("modeId", position.modeId().value());
    node.put("name", position.name());
    node.put("relationToMeans", position.relationToMeans().name());
    node.put("laborRole", position.laborRole().name());
    node.put("surplusRole", position.surplusRole().name());
    if (!position.ruleExtensions().isEmpty()) {
      node.put("ruleExtensions", new LinkedHashMap<>(position.ruleExtensions()));
    }
    return node;
  }

  // ── P2：PRODUCTION_RUNTIME 的资产规则 / 清算政策（DefaultProductionModes 是唯一 mode 来源）──────────

  /** PRODUCTION_RUNTIME 基线覆盖的生产资料种类（P11.7/D-024 起加入商号运力 SHIP/CATTLE）。 */
  private static final List<AssetKind> PRODUCTION_RUNTIME_ASSET_KINDS =
      List.of(
          AssetKind.LAND,
          AssetKind.SMALL_TOOL,
          AssetKind.WORKSHOP,
          AssetKind.SHIP,
          AssetKind.CATTLE);

  /** P2 基线资产规则的默认租金分成率（‰；沿用旧 COMPLETE 地基的 300‰ 制度量级）。 */
  private static final int PRODUCTION_RUNTIME_RENT_SHARE_PER_MILLE = 300;

  /**
   * ★★ <b>PRODUCTION_RUNTIME：{@code assetRules[]} 节点</b>：{@code DefaultProductionModes} 的<b>每个
   * mode</b> × {@link #PRODUCTION_RUNTIME_ASSET_KINDS}（LAND/SMALL_TOOL/WORKSHOP/SHIP/CATTLE）。
   *
   * <p>★★ <b>id 是派生值、不是手写第二份身份</b>：逐条经 {@link AssetRuleId#idOf(ProductionModeId, AssetKind)} 生成（与
   * {@code EconomyPayloads.parseAssetRules} 的判据同一处），并有 {@code idMatchesIdentity} 的同一条 {@code (mode,
   * assetKind)} 唯一性。{@code rentRule}/{@code transferRule} 的形状与旧 COMPLETE 地基逐字段同源： LAND
   * 分成粮、SMALL_TOOL/WORKSHOP 分成布；转移需所有权人同意、不许转租。
   *
   * <p>★ 键序 = mode 目录序 × {@link #PRODUCTION_RUNTIME_ASSET_KINDS} 声明序；纯函数、可复现。
   */
  static List<Map<String, Object>> productionRuntimeAssetRuleNodes() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (ProductionModeDefinitionView mode : DefaultProductionModes.modes().values()) {
      for (AssetKind assetKind : PRODUCTION_RUNTIME_ASSET_KINDS) {
        AssetRuleId ruleId = AssetRuleId.idOf(mode.id(), assetKind);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", ruleId.value());
        node.put("modeId", mode.id().value());
        node.put("assetKind", assetKind.name());
        node.put("isCoreMeans", true);
        node.put("rentRule", productionRuntimeRentRuleNode(assetKind));
        node.put("transferRule", productionRuntimeTransferRuleNode());
        nodes.add(node);
      }
    }
    return nodes;
  }

  /** 一条资产规则的租金模板：产出分成 300‰；LAND 分成粮，其余（SMALL_TOOL/WORKSHOP/SHIP/CATTLE）分成布。 */
  private static Map<String, Object> productionRuntimeRentRuleNode(AssetKind assetKind) {
    String commodity = assetKind == AssetKind.LAND ? COMMODITY_GRAIN : COMMODITY_CLOTH;
    Map<String, Object> leg = new LinkedHashMap<>();
    leg.put("kind", RentRule.RentType.SHARE.name());
    leg.put("ratePerMille", PRODUCTION_RUNTIME_RENT_SHARE_PER_MILLE);
    leg.put("fixedAmount", 0L);
    leg.put("commodity", commodity);
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("type", RentRule.RentType.SHARE.name());
    rule.put("priority", 10);
    rule.put("legs", List.of(leg));
    return rule;
  }

  /** 一条资产规则的转移模板：可转移、需所有权人同意、不许转租（旧 COMPLETE 地基的三个制度布尔）。 */
  private static Map<String, Object> productionRuntimeTransferRuleNode() {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("transferable", true);
    node.put("requiresOwnerConsent", true);
    node.put("allowSublease", false);
    return node;
  }

  /**
   * ★★★ <b>D-032 C3：{@code production.Seed.industries[]} 的节点裁剪（唯一拼写点）</b>。
   *
   * <p>economy 的 {@code plans[]} 节点是"production 定义 + economy 实例/关系键"的合集（后者由 {@code
   * plan.payload().put("operator"/"relation", …)} 追加）。production 的解析器当前对顶层多余键是<b>不读</b> （D1 的宽松
   * Seed 习惯），但跨域键本就不该出现在 production 载荷里 ⇒ C3 在这里只挑 production 认识的键。
   *
   * <p>★ <b>不改 production 的解析语义</b>（`requireKnownFields` 等一个字不动）；本方法是纯拷贝，不生成任何 新值，也不替 production
   * 做缺省推导。
   */
  static Map<String, Object> productionIndustryNode(Map<String, Object> planNode) {
    Objects.requireNonNull(planNode, "planNode");
    Map<String, Object> node = new LinkedHashMap<>();
    for (String key : PRODUCTION_INDUSTRY_KEYS) {
      if (planNode.containsKey(key)) {
        node.put(key, planNode.get(key));
      }
    }
    return Collections.unmodifiableMap(node);
  }

  /**
   * {@code ProductionDefinitionPayloads.industry(JsonNode)} 读取的全部字段名（唯一清单）。
   *
   * <p>★ 它只服务上面的裁剪；新增 production 定义字段时必须同步本表 + {@code productionPayload()}，否则该字段
   * 会在创世时被静默丢掉（本仓"声明式清单不随别处自动延伸"的那类静默面）。
   */
  private static final List<String> PRODUCTION_INDUSTRY_KEYS =
      List.of(
          "id",
          "name",
          "regime",
          "skill",
          "cycleDays",
          "buildDays",
          "acceptedRightKinds",
          "capacityPerUnit",
          "laborPerUnit",
          "dailyInputPerUnit",
          "dailyLaborPerUnit",
          "outputPerUnit",
          "moneyOutputPerUnit",
          "cycleInputPerUnit",
          "slots",
          "allocation",
          "experience",
          "availability",
          "roles",
          "roleShareRemainderRule",
          "upgradesTo",
          "mode");

  /**
   * ★★★ <b>D-032 C3（D2 §5.5）：创世名册的唯一守卫</b>。
   *
   * <p>规则：<b>多角色 plan 的 unit 必须在 {@code productionRosters} 里有显式名册</b>，缺失 ⇒ 具名 {@code
   * seed-roster-missing:<unit>}（整体失败，不静默生成单户名册）。单角色 plan 由 C2 的自动路径 （{@code
   * EconomyEnterpriseSettlement} / {@code EconomyEntrySettlement}）补默认名册，本方法不管。
   *
   * <p>★ 只读：只看本次载荷的 plan / unit 节点与准备发出的名册节点，不写任何状态；跨切片完整校验（角色存在、份额 owner、全局劳动预算、旧规则禁用）仍在 economy 的
   * {@code EconomyReferenceValidation}。
   */
  static void requireRostersForMultiRoleUnits(
      List<Map<String, Object>> entries, List<Map<String, Object>> productionRosters) {
    Objects.requireNonNull(entries, "entries");
    List<Map<String, Object>> rosters = productionRosters == null ? List.of() : productionRosters;
    Set<String> rosterUnits = new LinkedHashSet<>();
    for (Map<String, Object> roster : rosters) {
      Object unit = roster == null ? null : roster.get("unit");
      if (unit instanceof String text && !text.isBlank()) {
        rosterUnits.add(text);
      }
    }
    Map<String, Integer> roleCountByPlan = new LinkedHashMap<>();
    Map<String, String> planByUnit = new LinkedHashMap<>();
    for (Map<String, Object> entry : entries) {
      Object rawPlans = entry.get("plans");
      if (rawPlans instanceof List<?> planList) {
        for (Object element : planList) {
          if (!(element instanceof Map<?, ?> planNode)) {
            continue;
          }
          Object id = planNode.get("id");
          if (!(id instanceof String planId) || planId.isBlank()) {
            continue;
          }
          int roles = 1; // 缺 roles 键 = production 的单 operator 缺省（D1 §2.2）
          Object rawRoles = planNode.get("roles");
          if (rawRoles instanceof List<?> roleList) {
            roles = roleList.size();
          }
          roleCountByPlan.put(planId, roles);
        }
      }
      Object rawUnits = entry.get("units");
      if (rawUnits instanceof List<?> unitList) {
        for (Object element : unitList) {
          if (!(element instanceof Map<?, ?> unitNode)) {
            continue;
          }
          Object id = unitNode.get("id");
          Object industry = unitNode.get("industry");
          if (id instanceof String unitId && industry instanceof String planId) {
            planByUnit.put(unitId, planId);
          }
        }
      }
    }
    for (Map.Entry<String, String> unit : planByUnit.entrySet()) {
      Integer roles = roleCountByPlan.get(unit.getValue());
      if (roles != null && roles > 1 && !rosterUnits.contains(unit.getKey())) {
        throw new IllegalStateException(
            "seed-roster-missing:"
                + unit.getKey()
                + "（多角色 plan "
                + unit.getValue()
                + " 的 unit 必须在 economy.Seed.productionRosters 里给显式名册，不静默生成单户名册）");
      }
    }
  }
}
