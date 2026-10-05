package io.mosire.simos.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.DefaultRemedy;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>P3：世界生成的测试条件</b>（纯数据、确定性、可序列化；空表合法）—— 允许测试显式给出初始债务、初始质押、资产份额拆分与 额外库存/货币注入，而<b>不是</b>在断言侧凭空把
 * {@code debts} 写成非空。
 *
 * <p>★★ <b>它为什么是纯数据</b>：条件要能随 {@code economy.Seed} 载荷 / {@code simos.worldgen.initialize} 的 {@code
 * economyTestConditions} 参数传递，也要能在测试里被确定性构造（同一份输入 → 逐字段同一份输出）。故这里只有 record 与 校验，<b>不含</b>任何对
 * worldgen 状态的读写；真正的应用（真实对价、守恒、审计）在 {@link EconomySeeder#plan}。
 *
 * <p>★★ <b>没有对价就不许建条</b>（P3 的铁律，见 {@link InitialDebt} 的 {@code moveInventory}）：
 *
 * <ul>
 *   <li>{@code moveInventory=true}（缺省）⇒ 播种器必须从债权人真实的对应库存/货币里扣出 {@code principal}、再加到债务人账上； 债权人不足 ⇒
 *       具名异常（<b>不</b>静默改成 {@code false}）；
 *   <li>{@code moveInventory=false} ⇒ 调用方声明"对价来自本批的 {@code extraGoods}/{@code extraMoney} 外部注入"；
 *       播种器校验该户该标的的注入额确有 {@code ≥ principal} 的余量并逐条扣减，否则具名拒绝 —— 不存在"无条件债"。
 * </ul>
 *
 * <p>★★ <b>JSON 形状</b>（{@code economyTestConditions} 参数；未知键 fail-closed，不静默忽略）：
 *
 * <pre>{@code
 * {
 *   "initialDebts": [
 *     {"debtor":"hh-0_0-rural-poor_peasant","creditor":"hh-0_0-rural-landlord",
 *      "unit":{"kind":"commodity","commodity":"grain"},
 *      "principal":500000, "dueCycle":1, "moveInventory":true,
 *      "terms":{"interestRatePerMillePerCycle":20,"interestTiming":"AFTER_REPAYMENT_ON_CLOSE",
 *               "repaymentRule":"AVAILABLE_SURPLUS_SHARE","monetaryConversion":"NOT_ALLOWED",
 *               "defaultRemedy":"MARK_DEFAULTED"}}
 *   ],
 *   "initialPledges": [
 *     {"debtContractId":"debtc-…","assetShareId":"share-…","quantity":50000,
 *      "modeId":"legacy","priority":10}
 *   ],
 *   "assetSplits": [
 *     {"industry":"farm@0_0","asset":"LAND","sourceOwner":{"kind":"ESTATE","id":"farm@0_0"},
 *      "targetHousehold":"hh-0_0-rural-poor_peasant","quantity":100000},
 *     {"sourceAssetShareId":"share-…","targetHousehold":"hh-…","quantity":1000}
 *   ],
 *   "extraGoodsByHousehold": {"hh-0_0-rural-poor_peasant":{"grain":1000000}},
 *   "extraMoneyByHousehold": {"hh-0_0-rural-poor_peasant":{"silver":100}}
 * }
 * }</pre>
 *
 * <p>★ {@code sourceOwner} 既接受 {@code {"kind","id"}} 主体节点，也接受家户字符串（按 {@link HouseholdActors} 转成
 * {@code HOUSEHOLD:<id>}）；{@code assetSplits} 每项恰给"具体 id"或"(industry, asset, sourceOwner)"三件之一套。
 */
public record TestConditions(
    List<InitialDebt> initialDebts,
    List<InitialPledge> initialPledges,
    List<AssetSplit> assetSplits,
    Map<HouseholdId, Map<CommodityId, Long>> extraGoodsByHousehold,
    Map<HouseholdId, Map<CurrencyId, Long>> extraMoneyByHousehold) {

  /** 空条件（= P1 行为；worldgen 不传参数、或传空对象都归一到这里）。 */
  public static final TestConditions EMPTY =
      new TestConditions(List.of(), List.of(), List.of(), Map.of(), Map.of());

  /** 外部注入在 reason / 报告里的具名前缀（不得与 INITIAL_ENDOWMENT 混淆）。 */
  public static final String EXTERNAL_ENDOWMENT_REASON = "test-condition:external-endowment";

  /** 条件 JSON 的顶层键集合（未知键 fail-closed）。 */
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "initialDebts",
          "initialPledges",
          "assetSplits",
          "extraGoodsByHousehold",
          "extraMoneyByHousehold");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  public TestConditions {
    // ★ 防御性拷贝必须写在 SpotBugs 看得见的地方（EI_EXPOSE_REP 不做跨过程分析；抽成助手会触发告警）——
    //   与 Seed 的四张表同款：先校验、再包 unmodifiable。
    List<InitialDebt> debtsCopy = new ArrayList<>(initialDebts == null ? List.of() : initialDebts);
    for (InitialDebt debt : debtsCopy) {
      Objects.requireNonNull(debt, "test-condition: initialDebts 的元素不得为 null");
    }
    initialDebts = Collections.unmodifiableList(debtsCopy);
    List<InitialPledge> pledgesCopy =
        new ArrayList<>(initialPledges == null ? List.of() : initialPledges);
    for (InitialPledge pledge : pledgesCopy) {
      Objects.requireNonNull(pledge, "test-condition: initialPledges 的元素不得为 null");
    }
    initialPledges = Collections.unmodifiableList(pledgesCopy);
    List<AssetSplit> splitsCopy = new ArrayList<>(assetSplits == null ? List.of() : assetSplits);
    for (AssetSplit split : splitsCopy) {
      Objects.requireNonNull(split, "test-condition: assetSplits 的元素不得为 null");
    }
    assetSplits = Collections.unmodifiableList(splitsCopy);
    Map<HouseholdId, Map<CommodityId, Long>> goodsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry :
        (extraGoodsByHousehold == null
                ? Map.<HouseholdId, Map<CommodityId, Long>>of()
                : extraGoodsByHousehold)
            .entrySet()) {
      Objects.requireNonNull(entry.getKey(), "test-condition: extraGoodsByHousehold 的家户键不得为 null");
      Map<CommodityId, Long> inner = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> amount :
          (entry.getValue() == null ? Map.<CommodityId, Long>of() : entry.getValue()).entrySet()) {
        if (amount.getKey() == null || amount.getValue() == null || amount.getValue() <= 0L) {
          throw new IllegalArgumentException(
              "test-condition: extraGoodsByHousehold 的键值非 null 且金额必须 > 0: " + amount);
        }
        inner.put(amount.getKey(), amount.getValue());
      }
      goodsCopy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    extraGoodsByHousehold = Collections.unmodifiableMap(goodsCopy);
    Map<HouseholdId, Map<CurrencyId, Long>> moneyCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> entry :
        (extraMoneyByHousehold == null
                ? Map.<HouseholdId, Map<CurrencyId, Long>>of()
                : extraMoneyByHousehold)
            .entrySet()) {
      Objects.requireNonNull(entry.getKey(), "test-condition: extraMoneyByHousehold 的家户键不得为 null");
      Map<CurrencyId, Long> inner = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> amount :
          (entry.getValue() == null ? Map.<CurrencyId, Long>of() : entry.getValue()).entrySet()) {
        if (amount.getKey() == null || amount.getValue() == null || amount.getValue() <= 0L) {
          throw new IllegalArgumentException(
              "test-condition: extraMoneyByHousehold 的键值非 null 且金额必须 > 0: " + amount);
        }
        inner.put(amount.getKey(), amount.getValue());
      }
      moneyCopy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    extraMoneyByHousehold = Collections.unmodifiableMap(moneyCopy);
  }

  /** 无任何条件（等价于 P1 的播种路径）。 */
  public boolean isEmpty() {
    return initialDebts.isEmpty()
        && initialPledges.isEmpty()
        && assetSplits.isEmpty()
        && extraGoodsByHousehold.isEmpty()
        && extraMoneyByHousehold.isEmpty();
  }

  /**
   * 由本条件派生出的<b>声明报告</b>（条数 + 注入总量；确定性、无状态）—— 用于 {@code readings} 的诊断与工具摘要。 真正的"实际应用"结果由 {@link
   * EconomySeeder} 在逐条校验/转账成功后交回（条数与注入总量逐值等于本报告，失败则整批抛）。
   */
  public Report report() {
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    for (Map<CommodityId, Long> byCommodity : extraGoodsByHousehold.values()) {
      for (Map.Entry<CommodityId, Long> entry : byCommodity.entrySet()) {
        goods.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    for (Map<CurrencyId, Long> byCurrency : extraMoneyByHousehold.values()) {
      for (Map.Entry<CurrencyId, Long> entry : byCurrency.entrySet()) {
        money.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return new Report(initialDebts.size(), initialPledges.size(), assetSplits.size(), goods, money);
  }

  /** 工具摘要里的 {@code testConditions} 节点（键序固定；只用 String 键，避免 JSON 键序列化口径问题）。 */
  public Map<String, Object> wireSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("initialDebts", initialDebts.size());
    out.put("initialPledges", initialPledges.size());
    out.put("assetSplits", assetSplits.size());
    out.put("extraGoodsByHousehold", wireGoods(extraGoodsByHousehold));
    out.put("extraMoneyByHousehold", wireMoney(extraMoneyByHousehold));
    out.put("reason", EXTERNAL_ENDOWMENT_REASON);
    return Collections.unmodifiableMap(out);
  }

  /**
   * 把本条件序列化成 {@link #parseJson(String)} 的**逆**（测试夹具/工具参数用）—— 同一份条件 JSON 经 {@code
   * parseJson(toJson())} 往返逐字段不变。空条件序列化成"五个键都在的空表"（合法输入）。
   */
  public String toJson() {
    Map<String, Object> root = new LinkedHashMap<>();
    List<Map<String, Object>> debtNodes = new ArrayList<>(initialDebts.size());
    for (InitialDebt debt : initialDebts) {
      debtNodes.add(initialDebtNode(debt));
    }
    root.put("initialDebts", debtNodes);
    List<Map<String, Object>> pledgeNodes = new ArrayList<>(initialPledges.size());
    for (InitialPledge pledge : initialPledges) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("debtContractId", pledge.debtContractId().value());
      node.put("assetShareId", pledge.assetShareId().value());
      node.put("quantity", pledge.quantity());
      node.put("modeId", pledge.modeId().value());
      node.put("priority", pledge.priority());
      pledgeNodes.add(node);
    }
    root.put("initialPledges", pledgeNodes);
    List<Map<String, Object>> splitNodes = new ArrayList<>(assetSplits.size());
    for (AssetSplit split : assetSplits) {
      splitNodes.add(assetSplitNode(split));
    }
    root.put("assetSplits", splitNodes);
    root.put("extraGoodsByHousehold", wireGoods(extraGoodsByHousehold));
    root.put("extraMoneyByHousehold", wireMoney(extraMoneyByHousehold));
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("test-condition: 条件无法序列化", e);
    }
  }

  private static Map<String, Object> initialDebtNode(InitialDebt debt) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("debtor", debt.debtor().value());
    node.put("creditor", debt.creditor().value());
    Map<String, Object> unit = new LinkedHashMap<>();
    if (debt.unit() instanceof DebtUnit.Commodity commodity) {
      unit.put("kind", "commodity");
      unit.put("commodity", commodity.commodity().value());
    } else if (debt.unit() instanceof DebtUnit.Money money) {
      unit.put("kind", "money");
      unit.put("currency", money.currency().value());
    } else {
      throw new IllegalStateException("未知 DebtUnit 变体: " + debt.unit());
    }
    node.put("unit", unit);
    node.put("principal", debt.principal());
    DebtTerms terms = debt.terms();
    Map<String, Object> termsNode = new LinkedHashMap<>();
    termsNode.put("interestRatePerMillePerCycle", terms.interestRatePerMillePerCycle());
    termsNode.put("interestTiming", terms.interestTiming().name());
    termsNode.put("repaymentRule", terms.repaymentRule().name());
    termsNode.put("monetaryConversion", terms.monetaryConversion().name());
    termsNode.put("defaultRemedy", terms.defaultRemedy().name());
    if (terms.dueCycle().isPresent()) {
      termsNode.put("dueCycle", terms.dueCycle().getAsLong());
    }
    if (terms.dueDay().isPresent()) {
      termsNode.put("dueDay", terms.dueDay().getAsLong());
    }
    node.put("terms", termsNode);
    if (debt.dueCycle().isPresent()) {
      node.put("dueCycle", debt.dueCycle().getAsLong());
    }
    node.put("moveInventory", debt.moveInventory());
    return node;
  }

  private static Map<String, Object> assetSplitNode(AssetSplit split) {
    Map<String, Object> node = new LinkedHashMap<>();
    if (split.sourceAssetShareId().isPresent()) {
      node.put("sourceAssetShareId", split.sourceAssetShareId().get().value());
    } else {
      node.put("industry", split.industry().orElseThrow().value());
      node.put("asset", split.asset().orElseThrow().name());
      ActorRef owner = split.sourceOwner().orElseThrow();
      Map<String, Object> ownerNode = new LinkedHashMap<>();
      ownerNode.put("kind", owner.kind().name());
      ownerNode.put("id", owner.id());
      node.put("sourceOwner", ownerNode);
    }
    node.put("targetHousehold", split.targetHousehold().value());
    node.put("quantity", split.quantity());
    return node;
  }

  /**
   * ★★ <b>一条初始债务</b>：债务人 / 债权人 / 计量标的 / 本金（&gt; 0）/ 条款（缺省 {@link DebtTerms#legacyDefault()}）/
   * 到期周期（可空）/ 是否移动库存（缺省 true）。
   *
   * <p>★ <b>{@code moveInventory=false} 不是"免对价"的开关</b>：它要求本批 {@code extraGoodsByHousehold} / {@code
   * extraMoneyByHousehold} 在债务人的同一标的上确有 ≥ 本金的注入余量（播种器逐条扣减），否则具名拒绝。
   */
  public record InitialDebt(
      HouseholdId debtor,
      HouseholdId creditor,
      DebtUnit unit,
      long principal,
      DebtTerms terms,
      OptionalLong dueCycle,
      boolean moveInventory) {

    public InitialDebt {
      Objects.requireNonNull(debtor, "test-condition: initialDebts[].debtor 不得为 null");
      Objects.requireNonNull(creditor, "test-condition: initialDebts[].creditor 不得为 null");
      Objects.requireNonNull(unit, "test-condition: initialDebts[].unit 不得为 null");
      if (principal <= 0L) {
        throw new IllegalArgumentException(
            "test-condition: initialDebts[].principal 必须 > 0（本金是正量）: " + principal);
      }
      // ★ 缺省 legacyDefault：与旧债路径同一份条款口径（利率 20‰/周期、关账日偿还后计息…）。
      terms = terms == null ? DebtTerms.legacyDefault() : terms;
      dueCycle = dueCycle == null ? OptionalLong.empty() : dueCycle;
      if (dueCycle.isPresent() && dueCycle.getAsLong() <= 0L) {
        throw new IllegalArgumentException(
            "test-condition: initialDebts[].dueCycle 若给出必须 ≥ 1（周期号从 1 起）: " + dueCycle.getAsLong());
      }
    }

    /** 真实移动库存、legacy 条款、无约定到期周期。 */
    public static InitialDebt moving(
        HouseholdId debtor, HouseholdId creditor, DebtUnit unit, long principal) {
      return new InitialDebt(
          debtor, creditor, unit, principal, DebtTerms.legacyDefault(), OptionalLong.empty(), true);
    }

    /** 真实移动库存 + 显式条款 / 到期周期。 */
    public static InitialDebt moving(
        HouseholdId debtor,
        HouseholdId creditor,
        DebtUnit unit,
        long principal,
        DebtTerms terms,
        OptionalLong dueCycle) {
      return new InitialDebt(debtor, creditor, unit, principal, terms, dueCycle, true);
    }

    /** 不移动库存（对价必须来自本批外部注入；播种器逐条校验注入余量）。 */
    public static InitialDebt externallyFunded(
        HouseholdId debtor, HouseholdId creditor, DebtUnit unit, long principal) {
      return new InitialDebt(
          debtor,
          creditor,
          unit,
          principal,
          DebtTerms.legacyDefault(),
          OptionalLong.empty(),
          false);
    }
  }

  /**
   * ★★ <b>一条初始质押</b>：债务合同 + 资产份额 + 数量 + 生产方式 + 优先级（状态恒 {@code ACTIVE}，由播种器写）。
   *
   * <p>播种器逐条校验：合同∈本批、份额∈本批、{@code share.owner == debt.debtor}、份额是真实 {@code OWNED}、 Σ活跃质押 ≤
   * 份额数量、{@code modeId} 已被本 profile 种下。
   */
  public record InitialPledge(
      DebtContractId debtContractId,
      AssetShareId assetShareId,
      long quantity,
      ProductionModeId modeId,
      int priority) {

    public InitialPledge {
      Objects.requireNonNull(
          debtContractId, "test-condition: initialPledges[].debtContractId 不得为 null");
      Objects.requireNonNull(
          assetShareId, "test-condition: initialPledges[].assetShareId 不得为 null");
      Objects.requireNonNull(modeId, "test-condition: initialPledges[].modeId 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "test-condition: initialPledges[].quantity 必须 > 0: " + quantity);
      }
      if (priority < 0) {
        throw new IllegalArgumentException(
            "test-condition: initialPledges[].priority 不得为负: " + priority);
      }
    }
  }

  /**
   * ★★ <b>一条资产份额拆分</b>：把一条既有 {@code OWNED} 份额的一部分拆给指定家户（新份额 {@code owner=operator=目标家户}、 同
   * industry/asset、kind 仍 {@code OWNED}）。源既可用确定的 {@code sourceAssetShareId}，也可用 {@code (industry,
   * asset, sourceOwner)} 查询（命中必须唯一）。
   */
  public record AssetSplit(
      Optional<AssetShareId> sourceAssetShareId,
      Optional<IndustryId> industry,
      Optional<AssetKind> asset,
      Optional<ActorRef> sourceOwner,
      HouseholdId targetHousehold,
      long quantity) {

    public AssetSplit {
      sourceAssetShareId = sourceAssetShareId == null ? Optional.empty() : sourceAssetShareId;
      industry = industry == null ? Optional.empty() : industry;
      asset = asset == null ? Optional.empty() : asset;
      sourceOwner = sourceOwner == null ? Optional.empty() : sourceOwner;
      Objects.requireNonNull(
          targetHousehold, "test-condition: assetSplits[].targetHousehold 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "test-condition: assetSplits[].quantity 必须 > 0: " + quantity);
      }
      boolean byId = sourceAssetShareId.isPresent();
      boolean byQuery = industry.isPresent() || asset.isPresent() || sourceOwner.isPresent();
      if (byId == byQuery) {
        throw new IllegalArgumentException(
            "test-condition: assetSplits 每项恰给一种源定位：sourceAssetShareId，或 (industry, asset, sourceOwner) 三件齐全");
      }
      if (byQuery && !(industry.isPresent() && asset.isPresent() && sourceOwner.isPresent())) {
        throw new IllegalArgumentException(
            "test-condition: assetSplits 的查询定位必须同时给 industry / asset / sourceOwner（不许只给一部分）");
      }
    }

    /** 用具体份额 id 定位源。 */
    public static AssetSplit byId(AssetShareId source, HouseholdId target, long quantity) {
      return new AssetSplit(
          Optional.of(source),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          target,
          quantity);
    }

    /** 用 {@code (industry, asset, sourceOwner)} 查询源（命中必须唯一）。 */
    public static AssetSplit byQuery(
        IndustryId industry,
        AssetKind asset,
        ActorRef sourceOwner,
        HouseholdId target,
        long quantity) {
      return new AssetSplit(
          Optional.empty(),
          Optional.of(industry),
          Optional.of(asset),
          Optional.of(sourceOwner),
          target,
          quantity);
    }

    /** 查询源的便捷形态：sourceOwner 是家户（按 {@link HouseholdActors} 转 actor 身份）。 */
    public static AssetSplit byHouseholdQuery(
        IndustryId industry,
        AssetKind asset,
        HouseholdId sourceOwnerHousehold,
        HouseholdId target,
        long quantity) {
      return byQuery(industry, asset, HouseholdActors.of(sourceOwnerHousehold), target, quantity);
    }
  }

  /**
   * ★★ <b>条件应用后的可读报告</b>（条数 + 外部注入总量）—— 由 {@link EconomySeeder} 在校验/转账成功后构造； 空条件 ⇒ {@link #EMPTY}。
   */
  public record Report(
      int debtContractCount,
      int pledgeCount,
      int splitShareCount,
      Map<CommodityId, Long> injectedGoods,
      Map<CurrencyId, Long> injectedMoney) {

    public static final Report EMPTY = new Report(0, 0, 0, Map.of(), Map.of());

    public Report {
      if (debtContractCount < 0 || pledgeCount < 0 || splitShareCount < 0) {
        throw new IllegalArgumentException("test-condition: Report 的条数不得为负");
      }
      Map<CommodityId, Long> goodsCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry :
          (injectedGoods == null ? Map.<CommodityId, Long>of() : injectedGoods).entrySet()) {
        requirePositiveReportAmount(entry.getKey(), entry.getValue(), "Report.injectedGoods");
        goodsCopy.put(entry.getKey(), entry.getValue());
      }
      injectedGoods = Collections.unmodifiableMap(goodsCopy);
      Map<CurrencyId, Long> moneyCopy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry :
          (injectedMoney == null ? Map.<CurrencyId, Long>of() : injectedMoney).entrySet()) {
        requirePositiveReportAmount(entry.getKey(), entry.getValue(), "Report.injectedMoney");
        moneyCopy.put(entry.getKey(), entry.getValue());
      }
      injectedMoney = Collections.unmodifiableMap(moneyCopy);
    }

    public boolean isEmpty() {
      return debtContractCount == 0
          && pledgeCount == 0
          && splitShareCount == 0
          && injectedGoods.isEmpty()
          && injectedMoney.isEmpty();
    }

    /** 载荷 / 摘要里的报告节点（String 键；空表也写出零值，读口不用猜键是否存在）。 */
    public Map<String, Object> toWireMap() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("debtContracts", debtContractCount);
      out.put("pledges", pledgeCount);
      out.put("splitShares", splitShareCount);
      Map<String, Object> goods = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : injectedGoods.entrySet()) {
        goods.put(entry.getKey().value(), entry.getValue());
      }
      out.put("injectedGoods", goods);
      Map<String, Object> money = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry : injectedMoney.entrySet()) {
        money.put(entry.getKey().value(), entry.getValue());
      }
      out.put("injectedMoney", money);
      out.put("reason", EXTERNAL_ENDOWMENT_REASON);
      return out;
    }

    private static void requirePositiveReportAmount(Object key, Long value, String where) {
      if (key == null || value == null || value <= 0L) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的键值非 null 且金额必须 > 0: " + key + "=" + value);
      }
    }
  }

  // ── 资产份额确定性 id 助手（fixture 预测"拆分新建份额"的 id 用；与 EconomyPayloads 的序列口径同式）────

  /**
   * ★★ <b>家户自有 OWNED 份额的确定性 id</b> = {@code OwnershipStake.idOf(industry, asset, household, household,
   * OWNED, sequence)}。{@code sequence} 是该 {@code entry} 内同 {@code (industry, asset, owner,
   * operator, kind)} 键的第几条（从 0 起）—— 拆分出来的新份额通常 {@code sequence=0}（目标家户此前没有同键份额）。
   */
  public static AssetShareId ownedShareIdForHousehold(
      IndustryId industry, AssetKind asset, HouseholdId household, long sequence) {
    ActorRef actor = HouseholdActors.of(household);
    return OwnershipStake.idOf(industry, asset, actor, actor, OwnershipStake.RightKind.OWNED, sequence);
  }

  // ── JSON 解析（fail-closed、具名）──────────────────────────────────────────────────────

  /**
   * 解析 {@code economyTestConditions} 的 JSON 文本。
   *
   * @throws IllegalArgumentException 文本为空白 / 不是 JSON 对象 / 有未知键 / 任一字段非法（错误消息以 {@code
   *     test-condition: } 开头，带字段路径）
   */
  public static TestConditions parseJson(String jsonText) {
    if (jsonText == null || jsonText.isBlank()) {
      throw new IllegalArgumentException(
          "test-condition: economyTestConditions 不得为空白（不传该参数 = 无条件）");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(jsonText);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "test-condition: economyTestConditions 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("test-condition: economyTestConditions 必须是 JSON 对象");
    }
    requireOnlyKeys(root, TOP_LEVEL_KEYS, "economyTestConditions");
    return new TestConditions(
        parseInitialDebts(root.get("initialDebts")),
        parseInitialPledges(root.get("initialPledges")),
        parseAssetSplits(root.get("assetSplits")),
        parseExtraGoods(root.get("extraGoodsByHousehold")),
        parseExtraMoney(root.get("extraMoneyByHousehold")));
  }

  private static List<InitialDebt> parseInitialDebts(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("test-condition: initialDebts 必须是数组（缺省 = 空表）: " + node);
    }
    List<InitialDebt> debts = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String where = "initialDebts[" + i + "]";
      requireObject(item, where);
      requireOnlyKeys(
          item,
          Set.of("debtor", "creditor", "unit", "principal", "terms", "dueCycle", "moveInventory"),
          where);
      HouseholdId debtor = HouseholdId.parse(requireText(item, "debtor", where));
      HouseholdId creditor = HouseholdId.parse(requireText(item, "creditor", where));
      DebtUnit unit = parseDebtUnit(item.get("unit"), where + ".unit");
      long principal = requirePositiveLong(item, "principal", where);
      DebtTerms terms =
          item.hasNonNull("terms") ? parseDebtTerms(item.get("terms"), where + ".terms") : null;
      OptionalLong dueCycle = optionalOptionalLong(item, "dueCycle", where);
      boolean moveInventory =
          !item.hasNonNull("moveInventory") || requireBoolean(item, "moveInventory", where);
      debts.add(new InitialDebt(debtor, creditor, unit, principal, terms, dueCycle, moveInventory));
    }
    return debts;
  }

  private static List<InitialPledge> parseInitialPledges(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("test-condition: initialPledges 必须是数组（缺省 = 空表）: " + node);
    }
    List<InitialPledge> pledges = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String where = "initialPledges[" + i + "]";
      requireObject(item, where);
      requireOnlyKeys(
          item, Set.of("debtContractId", "assetShareId", "quantity", "modeId", "priority"), where);
      pledges.add(
          new InitialPledge(
              DebtContractId.parse(requireText(item, "debtContractId", where)),
              AssetShareId.parse(requireText(item, "assetShareId", where)),
              requirePositiveLong(item, "quantity", where),
              ProductionModeId.parse(requireText(item, "modeId", where)),
              requireNonNegativeInt(item, "priority", where)));
    }
    return pledges;
  }

  private static List<AssetSplit> parseAssetSplits(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException("test-condition: assetSplits 必须是数组（缺省 = 空表）: " + node);
    }
    List<AssetSplit> splits = new ArrayList<>(node.size());
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String where = "assetSplits[" + i + "]";
      requireObject(item, where);
      requireOnlyKeys(
          item,
          Set.of(
              "sourceAssetShareId",
              "industry",
              "asset",
              "sourceOwner",
              "targetHousehold",
              "quantity"),
          where);
      HouseholdId target = HouseholdId.parse(requireText(item, "targetHousehold", where));
      long quantity = requirePositiveLong(item, "quantity", where);
      if (item.hasNonNull("sourceAssetShareId")) {
        if (item.hasNonNull("industry")
            || item.hasNonNull("asset")
            || item.hasNonNull("sourceOwner")) {
          throw new IllegalArgumentException(
              "test-condition: " + where + " 不得同时给 sourceAssetShareId 与查询三件");
        }
        splits.add(
            AssetSplit.byId(
                AssetShareId.parse(requireText(item, "sourceAssetShareId", where)),
                target,
                quantity));
      } else {
        if (!(item.hasNonNull("industry")
            && item.hasNonNull("asset")
            && item.hasNonNull("sourceOwner"))) {
          throw new IllegalArgumentException(
              "test-condition: "
                  + where
                  + " 必须给 sourceAssetShareId，或 (industry, asset, sourceOwner) 三件齐全");
        }
        splits.add(
            AssetSplit.byQuery(
                IndustryId.parse(requireText(item, "industry", where)),
                parseAssetKind(requireText(item, "asset", where), where + ".asset"),
                parseActorRef(item.get("sourceOwner"), where + ".sourceOwner"),
                target,
                quantity));
      }
    }
    return splits;
  }

  private static Map<HouseholdId, Map<CommodityId, Long>> parseExtraGoods(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    requireObject(node, "extraGoodsByHousehold");
    Map<HouseholdId, Map<CommodityId, Long>> out = new LinkedHashMap<>();
    var fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      HouseholdId household = HouseholdId.parse(field.getKey());
      JsonNode goods = field.getValue();
      String where = "extraGoodsByHousehold." + field.getKey();
      requireObject(goods, where);
      Map<CommodityId, Long> byCommodity = new LinkedHashMap<>();
      var amounts = goods.fields();
      while (amounts.hasNext()) {
        Map.Entry<String, JsonNode> amount = amounts.next();
        byCommodity.put(
            CommodityId.parse(amount.getKey()),
            requirePositiveIntegral(amount.getValue(), where + "." + amount.getKey()));
      }
      out.put(household, byCommodity);
    }
    return out;
  }

  private static Map<HouseholdId, Map<CurrencyId, Long>> parseExtraMoney(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    requireObject(node, "extraMoneyByHousehold");
    Map<HouseholdId, Map<CurrencyId, Long>> out = new LinkedHashMap<>();
    var fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      HouseholdId household = HouseholdId.parse(field.getKey());
      JsonNode money = field.getValue();
      String where = "extraMoneyByHousehold." + field.getKey();
      requireObject(money, where);
      Map<CurrencyId, Long> byCurrency = new LinkedHashMap<>();
      var amounts = money.fields();
      while (amounts.hasNext()) {
        Map.Entry<String, JsonNode> amount = amounts.next();
        byCurrency.put(
            CurrencyId.parse(amount.getKey()),
            requirePositiveIntegral(amount.getValue(), where + "." + amount.getKey()));
      }
      out.put(household, byCurrency);
    }
    return out;
  }

  private static DebtUnit parseDebtUnit(JsonNode node, String where) {
    requireObject(node, where);
    requireOnlyKeys(node, Set.of("kind", "commodity", "currency"), where);
    String kind = requireText(node, "kind", where);
    return switch (kind) {
      case "commodity" -> {
        if (node.hasNonNull("currency")) {
          throw new IllegalArgumentException(
              "test-condition: " + where + ".kind=commodity 不得同时给 currency");
        }
        yield DebtUnit.commodity(CommodityId.parse(requireText(node, "commodity", where)));
      }
      case "money" -> {
        if (node.hasNonNull("commodity")) {
          throw new IllegalArgumentException(
              "test-condition: " + where + ".kind=money 不得同时给 commodity");
        }
        yield DebtUnit.money(CurrencyId.parse(requireText(node, "currency", where)));
      }
      default ->
          throw new IllegalArgumentException(
              "test-condition: " + where + ".kind 必须是 commodity 或 money: " + kind);
    };
  }

  private static DebtTerms parseDebtTerms(JsonNode node, String where) {
    requireObject(node, where);
    requireOnlyKeys(
        node,
        Set.of(
            "interestRatePerMillePerCycle",
            "interestTiming",
            "repaymentRule",
            "monetaryConversion",
            "defaultRemedy",
            "dueCycle",
            "dueDay"),
        where);
    return new DebtTerms(
        requireNonNegativeInt(node, "interestRatePerMillePerCycle", where),
        parseEnum(
            InterestTiming.class,
            requireText(node, "interestTiming", where),
            where + ".interestTiming"),
        parseEnum(
            RepaymentRule.class,
            requireText(node, "repaymentRule", where),
            where + ".repaymentRule"),
        parseEnum(
            MonetaryConversion.class,
            requireText(node, "monetaryConversion", where),
            where + ".monetaryConversion"),
        parseEnum(
            DefaultRemedy.class,
            requireText(node, "defaultRemedy", where),
            where + ".defaultRemedy"),
        optionalOptionalLong(node, "dueCycle", where),
        optionalOptionalLong(node, "dueDay", where));
  }

  private static ActorRef parseActorRef(JsonNode node, String where) {
    if (node != null && node.isTextual() && !node.asText().isBlank()) {
      // 便捷形态：家户字符串 ⇒ HOUSEHOLD:<id>（唯一拼写点是 HouseholdActors）。
      return HouseholdActors.of(HouseholdId.parse(node.asText()));
    }
    if (node == null) {
      throw new IllegalArgumentException("test-condition: " + where + " 必填（主体节点或家户字符串）");
    }
    requireObject(node, where);
    requireOnlyKeys(node, Set.of("kind", "id"), where);
    return ActorRef.parse(requireText(node, "kind", where), requireText(node, "id", where));
  }

  private static AssetKind parseAssetKind(String text, String where) {
    try {
      return AssetKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "test-condition: " + where + " 不是合法的 AssetKind: " + text, e);
    }
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String text, String where) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "test-condition: " + where + " 不在词表 " + type.getSimpleName() + " 里: " + text, e);
    }
  }

  private static long requirePositiveLong(JsonNode node, String field, String where) {
    long value = requireIntegral(node, field, where);
    if (value <= 0L) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必须 > 0: " + value);
    }
    return value;
  }

  private static long requirePositiveIntegral(JsonNode value, String where) {
    if (value == null || !value.isIntegralNumber()) {
      throw new IllegalArgumentException("test-condition: " + where + " 必须是整数: " + value);
    }
    long amount = value.asLong();
    if (amount <= 0L) {
      throw new IllegalArgumentException("test-condition: " + where + " 必须 > 0: " + amount);
    }
    return amount;
  }

  private static long requireIntegral(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必填且为整数: " + value);
    }
    return value.asLong();
  }

  private static int requireNonNegativeInt(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必填且为 int: " + value);
    }
    int intValue = value.asInt();
    if (intValue < 0) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 不得为负: " + intValue);
    }
    return intValue;
  }

  private static boolean requireBoolean(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必须是布尔值: " + value);
    }
    return value.asBoolean();
  }

  private static OptionalLong optionalOptionalLong(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return OptionalLong.empty();
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必须是整数或 null: " + value);
    }
    return OptionalLong.of(value.asLong());
  }

  private static String requireText(JsonNode node, String field, String where) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + "." + field + " 必填且为非空文本: " + value);
    }
    return value.asText();
  }

  private static void requireObject(JsonNode node, String where) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("test-condition: " + where + " 必须是对象: " + node);
    }
  }

  private static void requireOnlyKeys(JsonNode node, Set<String> allowed, String where) {
    Set<String> unknown = new LinkedHashSet<>();
    var names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowed.contains(name)) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "test-condition: " + where + " 有未知键 " + unknown + "（合法键: " + allowed + "）");
    }
  }

  // ── 载荷/摘要节点的 String 键形态 ──────────────────────────────────────────────────────

  private static Map<String, Object> wireGoods(Map<HouseholdId, Map<CommodityId, Long>> values) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : values.entrySet()) {
      Map<String, Object> inner = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> amount : entry.getValue().entrySet()) {
        inner.put(amount.getKey().value(), amount.getValue());
      }
      out.put(entry.getKey().value(), inner);
    }
    return out;
  }

  private static Map<String, Object> wireMoney(Map<HouseholdId, Map<CurrencyId, Long>> values) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> entry : values.entrySet()) {
      Map<String, Object> inner = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> amount : entry.getValue().entrySet()) {
        inner.put(amount.getKey().value(), amount.getValue());
      }
      out.put(entry.getKey().value(), inner);
    }
    return out;
  }
}
