package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.LandOwnershipSeeder.ownershipStakeNode;
import static io.mosire.simos.app.world.MarketSeeder.seedAnchor;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P3 测试条件（外部注入/初始债/份额拆分）的唯一应用点。 */
class EconomySeederConditions extends WorldBalanceSeeder {

  /**
   * ★★ <b>P3：把测试条件应用到已建好的家户账 / 资产份额 / 经营者表上</b> —— <b>每一条都有真实对价</b>：
   *
   * <ol>
   *   <li>{@code extraGoods}/{@code extraMoney}：直接加到家户开缸库存/钱包，但这是**具名的外部注入** （{@code
   *       test-condition:external-endowment}）⇒ 货币注入同时发一条 {@code FISCAL_ISSUE} {@code
   *       MoneyIssuanceRecord}，<b>不并进</b> INITIAL_ENDOWMENT；
   *   <li>{@code InitialDebt}：真实从债权人库存/货币扣、加给债务人；不足 ⇒ 具名拒绝（不改 {@code moveInventory=false}）； {@code
   *       moveInventory=false} 只能消耗本批外部注入的**未承诺余量**（同一份注入不许被两条债重复当对价）；
   *   <li>{@code AssetSplit}：源 {@code OWNED} 份额减、同 industry/asset 的新 {@code OWNED}
   *       份额（owner=operator=目标家户）加， 逐 {@code (industry, asset)} 总量守恒（出口复核，不等即抛）；
   *   <li>（P2d 起不再接受该初始条件） modeId} 已被本 profile 种下。
   * </ol>
   *
   * <p>★ 条件里的每一个家户都必须在<b>本次 seed 的格集</b>里（跨 seed 引用没有对侧，具名拒绝）。
   */
  static AppliedConditions applyTestConditions(
      String mapId,
      List<Map<String, Object>> entries,
      Map<HouseholdId, Map<CommodityId, Long>> householdStocks,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      TestConditions conditions) {
    if (conditions == null || conditions.isEmpty()) {
      return AppliedConditions.empty();
    }
    // ── ① 外部注入（具名；货币另发 FISCAL_ISSUE 审计）──────────────────────────────────────
    Map<CommodityId, Long> injectedGoodsTotal = new LinkedHashMap<>();
    Map<CurrencyId, Long> injectedMoneyTotal = new LinkedHashMap<>();
    // ★ "未承诺的注入余量"：moveInventory=false 的债只能消耗这份余量（逐条扣减 ⇒ 不重复计价）。
    Map<HouseholdId, Map<String, Long>> uncommittedInjection = new LinkedHashMap<>();
    List<Map<String, Object>> extraIssuances = new ArrayList<>();
    int issuanceIndex = 0;
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> byHousehold :
        conditions.extraGoodsByHousehold().entrySet()) {
      HouseholdId household = byHousehold.getKey();
      requireSeededHousehold(household, householdStocks, householdMoney, "extraGoodsByHousehold");
      Map<CommodityId, Long> stock = householdStocks.get(household);
      for (Map.Entry<CommodityId, Long> amount : byHousehold.getValue().entrySet()) {
        stock.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectedGoodsTotal.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectionCoverage(uncommittedInjection, household)
            .merge("commodity:" + amount.getKey().value(), amount.getValue(), Math::addExact);
      }
    }
    for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> byHousehold :
        conditions.extraMoneyByHousehold().entrySet()) {
      HouseholdId household = byHousehold.getKey();
      requireSeededHousehold(household, householdStocks, householdMoney, "extraMoneyByHousehold");
      Map<CurrencyId, Long> wallet = householdMoney.get(household);
      for (Map.Entry<CurrencyId, Long> amount : byHousehold.getValue().entrySet()) {
        wallet.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectedMoneyTotal.merge(amount.getKey(), amount.getValue(), Math::addExact);
        injectionCoverage(uncommittedInjection, household)
            .merge(DebtUnit.money(amount.getKey()).key(), amount.getValue(), Math::addExact);
        extraIssuances.add(
            externalIssuanceNode(
                mapId, entries, household, amount.getKey(), amount.getValue(), issuanceIndex++));
      }
    }
    // ── ② 初始债务（真实转账 / 真实外部对价）──────────────────────────────────────────────
    List<Map<String, Object>> debtNodes = new ArrayList<>();
    Set<DebtContractId> seenContracts = new LinkedHashSet<>();
    for (int i = 0; i < conditions.initialDebts().size(); i++) {
      TestConditions.InitialDebt debt = conditions.initialDebts().get(i);
      String where = "initialDebts[" + i + "]";
      requireSeededHousehold(debt.debtor(), householdStocks, householdMoney, where + ".debtor");
      requireSeededHousehold(debt.creditor(), householdStocks, householdMoney, where + ".creditor");
      if (debt.debtor().equals(debt.creditor())) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的债务人 == 债权人（自己欠自己不是债）: " + debt.debtor());
      }
      DebtContractId id =
          DebtContractId.idOf(debt.debtor(), debt.creditor(), debt.unit(), debt.terms());
      if (!seenContracts.add(id)) {
        throw new IllegalArgumentException(
            "test-condition: 同 (debtor, creditor, unit, terms) 在条件里出现多次 ⇒ 合同 id 重复，"
                + "不许静默合并或覆盖: "
                + id);
      }
      if (debt.moveInventory()) {
        transferForInitialDebt(debt, householdMoney);
      } else {
        consumeInjectedConsideration(debt, uncommittedInjection, where);
      }
      debtNodes.add(debtContractNode(id, debt));
    }
    // ── ③ 资产份额：先按 EconomyPayloads 的确定性序列口径给每条既有份额算出 id，再应用拆分 ──────
    Map<AssetShareId, ShareHandle> shares = new LinkedHashMap<>();
    Map<String, Long> sequences = new LinkedHashMap<>();
    Map<String, Long> conservedBefore = new LinkedHashMap<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      for (Map<String, Object> node : ownershipStakesList(entries.get(entryIndex), entryIndex)) {
        ShareHandle handle = parseShareHandle(node, entryIndex);
        String sequenceKey = sequenceKeyOf(handle);
        long sequence = sequences.getOrDefault(sequenceKey, 0L);
        sequences.put(sequenceKey, sequence + 1L);
        AssetShareId id = shareIdOf(handle, sequence);
        if (shares.putIfAbsent(id, handle) != null) {
          throw new IllegalStateException("资产份额确定性 id 冲突（plan 内部构造错误）: " + id);
        }
        conservedBefore.merge(assetTotalsKey(handle), handle.quantity(), Math::addExact);
      }
    }
    int splitShareCount = 0;
    for (int i = 0; i < conditions.assetSplits().size(); i++) {
      TestConditions.AssetSplit split = conditions.assetSplits().get(i);
      String where = "assetSplits[" + i + "]";
      requireSeededHousehold(
          split.targetHousehold(), householdStocks, householdMoney, where + ".targetHousehold");
      ShareHandle source = resolveSourceShare(split, shares, where);
      if (source.kind() != AssetRightKind.OWNED) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的源份额必须是 OWNED（拆分只拆自有份额）: id 对应 " + source.kind());
      }
      long available = source.quantity();
      if (available < split.quantity()) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 的源份额数量不足：现有=" + available + "，需要=" + split.quantity());
      }
      source.node().put("quantity", available - split.quantity());
      ActorRef target = HouseholdActors.of(split.targetHousehold());
      Map<String, Object> newNode =
          ownershipStakeNode(
              source.industry().value(),
              source.asset().name(),
              target,
              target,
              AssetRightKind.OWNED,
              split.quantity(),
              source.location().value());
      ShareHandle targetShare =
          new ShareHandle(
              source.entryIndex(),
              newNode,
              source.industry(),
              source.asset(),
              target,
              target,
              AssetRightKind.OWNED,
              source.location());
      String sequenceKey = sequenceKeyOf(targetShare);
      long sequence = sequences.getOrDefault(sequenceKey, 0L);
      sequences.put(sequenceKey, sequence + 1L);
      AssetShareId newId = shareIdOf(targetShare, sequence);
      ownershipStakesList(entries.get(source.entryIndex()), source.entryIndex()).add(newNode);
      if (shares.putIfAbsent(newId, targetShare) != null) {
        throw new IllegalStateException("拆分新建份额 id 冲突（plan 内部构造错误）: " + newId);
      }
      splitShareCount++;
    }
    Map<String, Long> conservedAfter = new LinkedHashMap<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      for (Map<String, Object> node : ownershipStakesList(entries.get(entryIndex), entryIndex)) {
        ShareHandle handle = parseShareHandle(node, entryIndex);
        conservedAfter.merge(assetTotalsKey(handle), handle.quantity(), Math::addExact);
      }
    }
    if (!conservedBefore.equals(conservedAfter)) {
      throw new IllegalStateException(
          "test-condition: 资产份额拆分违反逐 (industry, asset) 总量守恒：before="
              + conservedBefore
              + " after="
              + conservedAfter);
    }
    TestConditions.Report report =
        new TestConditions.Report(
            debtNodes.size(), splitShareCount, injectedGoodsTotal, injectedMoneyTotal);
    return new AppliedConditions(
        Collections.unmodifiableList(debtNodes),
        Collections.unmodifiableList(extraIssuances),
        report);
  }

  /** 条件里指名的家户必须在本 seed 的账表里（stocks 与 money 两表同键，由 {@code cohortGroup} 保证）。 */
  private static void requireSeededHousehold(
      HouseholdId household,
      Map<HouseholdId, ?> householdStocks,
      Map<HouseholdId, ?> householdMoney,
      String where) {
    if (household == null
        || !householdStocks.containsKey(household)
        || !householdMoney.containsKey(household)) {
      throw new IllegalArgumentException(
          "test-condition: " + where + " 指名的家户不在本次 seed 的格集里（跨 seed 引用没有对侧，拒绝）: " + household);
    }
  }

  /** 未承诺的注入余量表（按家户惰性建表）。 */
  private static Map<String, Long> injectionCoverage(
      Map<HouseholdId, Map<String, Long>> coverage, HouseholdId household) {
    return coverage.computeIfAbsent(household, ignored -> new LinkedHashMap<>());
  }

  /** {@code moveInventory=false} 的对价校验：只能消耗本批外部注入的未承诺余量（逐条扣减 ⇒ 同一份注入不被两条债重复计价）。 */
  private static void consumeInjectedConsideration(
      TestConditions.InitialDebt debt,
      Map<HouseholdId, Map<String, Long>> uncommittedInjection,
      String where) {
    Map<String, Long> byUnit = injectionCoverage(uncommittedInjection, debt.debtor());
    String key = debt.unit().key();
    long available = byUnit.getOrDefault(key, 0L);
    if (available < debt.principal()) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " moveInventory=false，但债务人的外部注入对价不足（不许无对价建条）：家户="
              + debt.debtor()
              + "，标的="
              + key
              + "，可用注入="
              + available
              + "，需要="
              + debt.principal());
    }
    long remaining = available - debt.principal();
    if (remaining == 0L) {
      byUnit.remove(key);
    } else {
      byUnit.put(key, remaining);
    }
  }

  /** 初始货币债务的真实转账：债权人对应币种真扣、债务人真加；不足 ⇒ 具名拒绝（不改 {@code moveInventory=false}）。 */
  private static void transferForInitialDebt(
      TestConditions.InitialDebt debt, Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    DebtUnit.Money money = debt.unit();
    CurrencyId currency = money.currency();
    Map<CurrencyId, Long> creditorWallet = householdMoney.get(debt.creditor());
    long have = creditorWallet.getOrDefault(currency, 0L);
    if (have < debt.principal()) {
      throw new IllegalArgumentException(
          "test-condition: 债权人货币不足，拒绝生成无对价的初始债务：creditor="
              + debt.creditor()
              + "，币种="
              + currency.value()
              + "，现有="
              + have
              + "，需要="
              + debt.principal());
    }
    putOrRemove(creditorWallet, currency, have - debt.principal());
    householdMoney.get(debt.debtor()).merge(currency, debt.principal(), Math::addExact);
  }

  /** 余额 0 ⇒ 删键（与"只落正的量"的纯形态一致），否则写回。 */
  private static <K> void putOrRemove(Map<K, Long> map, K key, long amount) {
    if (amount == 0L) {
      map.remove(key);
    } else {
      map.put(key, amount);
    }
  }

  /** 一条初始债务的 {@code debtContracts[]} 载荷节点（id 由四元组派生、openedDay=0、从未计息、NORMAL）。 */
  private static Map<String, Object> debtContractNode(
      DebtContractId id, TestConditions.InitialDebt debt) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id.value());
    node.put("debtor", debt.debtor().value());
    node.put("creditor", debt.creditor().value());
    Map<String, Object> unit = new LinkedHashMap<>();
    DebtUnit.Money money = debt.unit();
    unit.put("kind", "money");
    unit.put("currency", money.currency().value());
    node.put("unit", unit);
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
    // ★★★ D-032 W4：创世/测试条件里的初始债全部是普通借贷（没有工资/地租旧债来源可判定）；
    //   显式写 DebtSource.ORDINARY_LOAN，绝不留空让创世被 debt-source-missing 拒。
    node.put("source", DebtSource.ORDINARY_LOAN.name());
    node.put("principal", debt.principal());
    node.put("openedDay", 0L);
    // 尚未计息/无约定到期周期：显式 null（载荷解析口径：缺席/null ⇒ 空）。
    node.put("lastInterestDay", null);
    node.put("dueCycle", debt.dueCycle().isPresent() ? debt.dueCycle().getAsLong() : null);
    node.put("status", DebtStatus.NORMAL.name());
    return node;
  }

  /**
   * 外部注入货币的审计节点：{@link MoneyIssuanceKind#FISCAL_ISSUE}，reason 含 {@link
   * TestConditions#EXTERNAL_ENDOWMENT_REASON}，<b>绝不并进</b> INITIAL_ENDOWMENT。
   */
  private static Map<String, Object> externalIssuanceNode(
      String mapId,
      List<Map<String, Object>> entries,
      HouseholdId household,
      CurrencyId currency,
      long amount,
      int index) {
    String id =
        "testcondition-" + mapId + "-" + seedAnchor(entries) + "-" + index + "-" + currency.value();
    if (id.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "test-condition: 外部注入的 MoneyIssuanceId 含 '|'（请改 mapId/币种）: " + id);
    }
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id);
    node.put("governmentId", GENESIS_GOVERNMENT_ID.value());
    node.put("day", 0L);
    node.put("period", FIRST_PERIOD);
    node.put("currency", currency.value());
    node.put("amount", amount);
    node.put("kind", MoneyIssuanceKind.FISCAL_ISSUE.name());
    node.put(
        "reason",
        TestConditions.EXTERNAL_ENDOWMENT_REASON
            + "（测试外部注入，不计入 INITIAL_ENDOWMENT）：家户="
            + household.value()
            + "，金额="
            + amount
            + " 毫最小币值");
    return node;
  }

  /** 一条既有资产份额节点的内存视图（node 可变：拆分只改它的 quantity）。 */
  private record ShareHandle(
      int entryIndex,
      Map<String, Object> node,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      ProductionLocation location) {

    long quantity() {
      return ((Number) node.get("quantity")).longValue();
    }
  }

  /**
   * ★★ <b>与经济载荷解析器逐字同式的确定性 id 口径</b>：{@code LAND} 的序列键只含 {@code
   * industry|asset|location}（同一格多块稳定递增，owner/operator/kind 不进入身份）；非土地资产保持原键 {@code
   * industry|asset|owner|operator|kind|location}。序号 = 该键在<b>整份载荷</b>里出现的第几条（从 0 起）。本类先在拆分前对全部既有份额
   * 算一遍，拆分新建的份额再按同一张计数器往后发 —— 于是本类算出的 id 与 economy 侧解析出的 id <b>逐值相同</b>（拆分创建的目标份额因此可被 调用方按同一 id
   * 口径读取目标份额）。
   */
  private static String sequenceKeyOf(ShareHandle handle) {
    if (handle.asset() == AssetKind.LAND) {
      return handle.industry() + "|" + handle.asset() + "|" + handle.location().value();
    }
    return handle.industry()
        + "|"
        + handle.asset()
        + "|"
        + handle.owner()
        + "|"
        + handle.operator()
        + "|"
        + handle.kind()
        + "|"
        + handle.location().value();
  }

  /** 土地块走稳定 id（owner/operator/kind 不入 id）；非土地资产走既有 {@link OwnershipStake#idOf}。 */
  private static AssetShareId shareIdOf(ShareHandle handle, long sequence) {
    if (handle.asset() == AssetKind.LAND) {
      return OwnershipStake.landBlockIdOf(handle.industry(), handle.location(), sequence);
    }
    return OwnershipStake.idOf(
        handle.industry(),
        handle.asset(),
        handle.owner(),
        handle.operator(),
        handle.kind(),
        handle.location(),
        sequence);
  }

  /** 守恒复核对账键：逐 {@code (industry, asset)}。 */
  private static String assetTotalsKey(ShareHandle handle) {
    return handle.industry().value() + "|" + handle.asset().name();
  }

  /** entry 的资产份额列表（plan 内部构造保证存在）。 */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> ownershipStakesList(
      Map<String, Object> entry, int entryIndex) {
    Object value = entry.get("assetShares");
    if (!(value instanceof List<?>)) {
      throw new IllegalStateException("entry[" + entryIndex + "] 缺 assetShares 列表（plan 内部构造错误）");
    }
    return (List<Map<String, Object>>) value;
  }

  private static ShareHandle parseShareHandle(Map<String, Object> node, int entryIndex) {
    Object industryNode = node.get("industry");
    Object assetNode = node.get("asset");
    Object kindNode = node.get("kind");
    Object ownerNode = node.get("owner");
    Object operatorNode = node.get("operator");
    Object quantityNode = node.get("quantity");
    Object locationNode = node.get("location");
    if (!(industryNode instanceof String industry)
        || !(assetNode instanceof String asset)
        || !(kindNode instanceof String kind)
        || !(ownerNode instanceof Map<?, ?> owner)
        || !(operatorNode instanceof Map<?, ?> operator)
        || !(quantityNode instanceof Number)
        || !(locationNode instanceof String location)) {
      throw new IllegalStateException("资产份额节点形状非法（plan 内部构造错误）: " + node);
    }
    return new ShareHandle(
        entryIndex,
        node,
        IndustryId.parse(industry),
        AssetKind.parse(asset),
        parseActorNode(owner),
        parseActorNode(operator),
        AssetRightKind.valueOf(kind),
        OwnershipStake.requireSpecifiedLocation(ProductionLocation.parse(location)));
  }

  private static ActorRef parseActorNode(Map<?, ?> node) {
    Object kind = node.get("kind");
    Object id = node.get("id");
    if (!(kind instanceof String kindText) || !(id instanceof String idText)) {
      throw new IllegalStateException("actor 节点形状非法（plan 内部构造错误）: " + node);
    }
    return ActorRef.parse(kindText, idText);
  }

  /** 拆分源解析：具体 id ⇒ 直查；查询 ⇒ 必须唯一命中一条 OWNED 份额（有歧义则具名拒绝）。 */
  private static ShareHandle resolveSourceShare(
      TestConditions.AssetSplit split, Map<AssetShareId, ShareHandle> shares, String where) {
    if (split.sourceAssetShareId().isPresent()) {
      AssetShareId id = split.sourceAssetShareId().get();
      ShareHandle handle = shares.get(id);
      if (handle == null) {
        throw new IllegalArgumentException(
            "test-condition: " + where + " 找不到 sourceAssetShareId（不在本次 seed 的份额表里）: " + id);
      }
      return handle;
    }
    IndustryId industry = split.industry().orElseThrow();
    AssetKind asset = split.asset().orElseThrow();
    ActorRef sourceOwner = split.sourceOwner().orElseThrow();
    List<ShareHandle> matches = new ArrayList<>();
    for (ShareHandle handle : shares.values()) {
      if (handle.kind() == AssetRightKind.OWNED
          && handle.industry().equals(industry)
          && handle.asset() == asset
          && handle.owner().equals(sourceOwner)) {
        matches.add(handle);
      }
    }
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " 查询不到匹配的 OWNED 源份额：industry="
              + industry
              + "，asset="
              + asset
              + "，sourceOwner="
              + sourceOwner);
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "test-condition: "
              + where
              + " 的查询命中 "
              + matches.size()
              + " 条 OWNED 源份额（有歧义）⇒ 请改用 sourceAssetShareId");
    }
    return matches.get(0);
  }
}
