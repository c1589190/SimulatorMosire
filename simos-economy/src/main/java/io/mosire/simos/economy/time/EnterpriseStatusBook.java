package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.AssetGrant;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.GeneratedRelation;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.OrganizeResult;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionEnterprise.Status;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRole.LaborRole;
import io.mosire.simos.economy.model.ProductionRole.RelationToMeans;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** E2 拆分 helper（由 EconomyEnterpriseSettlement 抽取；行为不变）。 */
final class EnterpriseStatusBook {

  private EnterpriseStatusBook() {}

  /**
   * 同产业、同资产种类、自有自营（operator == owner）、不属于组织者、TENANCY 之外的闲置份额（按 id 升序）。
   *
   * <p>★ {@code reservedHouseholdOwners} = 本 mode 下自己会生产的家户 actor 集合：这些家户的 OWNED 份额要留给
   * 他们自己的组织，不能被别家的租佃拆走（见 {@code organize} 里的注释）。非生产位置（地主/官署）的家户份额不在此列。
   *
   * <p>★★ <b>P2-E 新增：{@code operatorsWithUnit}</b> = 本产业里**已经有生产 unit 的经营者**集合。这些 (industry,
   * operator) 作用域下的份额是那条 unit 的产能来源，不是闲置份额 —— 否则自动组织会为另一个家户 "租"走别人正在用的产能，把原 unit 拆成没有份额的空壳（P2-E 的
   * weave 红点即此路径）。
   */
  static List<OwnershipStake> idleSources(
      IndustryId industryId,
      AssetKind assetKind,
      ActorRef organizer,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<ActorRef> reservedHouseholdOwners,
      Set<ActorRef> operatorsWithUnit) {
    List<OwnershipStake> sources = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (!share.industry().equals(industryId) || share.asset() != assetKind) {
        continue;
      }
      if (share.kind() == AssetRightKind.TENANCY) {
        continue; // 已租出的份额不再转租（E2 不做转租；TransferRule.allowSublease 由后续阶段读）
      }
      if (!share.operator().equals(share.owner())) {
        continue; // 已有人在用（operator != owner）⇒ 不是闲置
      }
      if (operatorsWithUnit.contains(share.operator())) {
        continue; // 该 (industry, operator) 已有生产 unit 在用它 ⇒ 不是闲置（P2-E：防拆空正在生产的 unit）
      }
      if (share.owner().equals(organizer)) {
        continue; // 本户自己的份额已经在 usable 里
      }
      if (reservedHouseholdOwners.contains(share.owner())) {
        continue; // 该份额的主人自己会生产：留给它，不当别家的闲置
      }
      if (share.quantity() <= 0L) {
        continue;
      }
      sources.add(share);
    }
    sources.sort(Comparator.comparing(share -> share.id().value()));
    return sources;
  }

  /**
   * 把 grants 落到份额工作副本：<b>委托 {@link OwnershipStakeBook#apply}</b> —— 源份额减量/删行，新建 owner 不变、
   * operator=组织者、kind=TENANCY 的份额（Σ 逐 {@code (industry, asset)} 守恒）。★ E5a 起不再在本类直接 {@code
   * put}/{@code remove} 份额：新 id 的确定性序号、源数量上界、industry 守卫全部收在唯一写口，且失败时工作副本一字不动。
   */
  static void applyGrants(
      List<AssetGrant> grants,
      ActorRef organizer,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares) {
    if (grants.isEmpty()) {
      return;
    }
    List<OwnershipStakeBook.Move> moves = new ArrayList<>(grants.size());
    for (AssetGrant grant : grants) {
      moves.add(
          new OwnershipStakeBook.Move(
              grant.source(), grant.quantity(), grant.owner(), organizer, AssetRightKind.TENANCY));
    }
    OwnershipStakeBook.apply(assetShares, industries, moves);
  }

  /** 租佃拆分后的产能规模（与 {@code ProductionProcessBook} 同式的只读预估；grants 尚未落盘）。 */
  static long expectedCapacityAfterGrants(
      IndustryDefinitionView industry, Map<AssetKind, Long> usable, List<AssetGrant> grants) {
    Map<AssetKind, Long> granted = new LinkedHashMap<>();
    for (AssetGrant grant : grants) {
      granted.merge(grant.asset(), grant.quantity(), Long::sum);
    }
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      long available =
          usable.getOrDefault(entry.getKey(), 0L) + granted.getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, available / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /** 租佃腿的分组：同一 (assetKind, owner) 只生成一条租金规则（多条份额不重复计租）；保序确定。 */
  static List<AssetGrant> rentGroups(List<AssetGrant> grants) {
    Map<String, AssetGrant> groups = new LinkedHashMap<>();
    List<AssetGrant> ordered = new ArrayList<>(grants);
    ordered.sort(
        Comparator.comparing((AssetGrant grant) -> grant.asset().name())
            .thenComparing(grant -> grant.owner().kind().name())
            .thenComparing(grant -> grant.owner().id())
            .thenComparing(grant -> grant.source().value()));
    for (AssetGrant grant : ordered) {
      groups.putIfAbsent(grant.asset().name() + "|" + grant.owner(), grant);
    }
    return new ArrayList<>(groups.values());
  }

  /**
   * {@link RentRule} → 结算侧规则（逐腿一条；货币腿走既有 FIXED_MONEY_RENT 路径）。
   *
   * <p>★★ <b>B3（2026-10-10）：实物腿的商品由 {@link #rentCommodity} 定（= 本产业的产出表）</b>，不再照抄资产规则里的 常量商品 ——
   * 理由与三条分支见该方法。
   *
   * @param industry 这条 unit 的产业模板（产出表 = 商品的唯一权威）；不得为 null
   * @param mode 组织这条 unit 的生产方式（只进日志/拒因，不参与判据）
   * @throws IllegalArgumentException 实物腿的商品判不出（见 {@link #rentCommodity}）⇒ 调用方 fail-closed：不建 unit、
   *     落具名 SHORTAGE（**不落一条 E14 非法的关系**）
   */
  static List<CompensationRule> rentRules(
      RentRule rentRule,
      Payee recipient,
      IndustryDefinitionView industry,
      ProductionModeDefinitionView mode) {
    List<CompensationRule> rules = new ArrayList<>();
    for (RentRule.RentLeg leg : rentRule.legs()) {
      // ★ 货币腿（commodity 空）没有商品可判（E14 只判实物档）；实物腿的商品走 rentCommodity 解析。
      Optional<CommodityId> commodity =
          leg.commodity().isEmpty()
              ? Optional.empty()
              : rentCommodity(leg, industry, mode, recipient);
      CompensationRule rule =
          switch (leg.kind()) {
            case FIXED_IN_KIND ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    recipient,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    leg.fixedAmount(),
                    commodity,
                    Optional.empty(),
                    rentRule.priority());
            case FIXED_MONEY ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_RENT,
                    recipient,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    leg.fixedAmount(),
                    Optional.empty(),
                    leg.currency(),
                    rentRule.priority());
            case SHARE ->
                new CompensationRule(
                    RuleType.OUTPUT_SHARE,
                    recipient,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    leg.ratePerMille(),
                    0L,
                    commodity,
                    Optional.empty(),
                    rentRule.priority());
            case MIXED -> throw new IllegalArgumentException("RentLeg.kind 不得为 MIXED（构造期已判死）");
          };
      rules.add(rule);
    }
    return rules;
  }

  /**
   * ★★ <b>B3（2026-10-10）：一条实物租金腿该用哪个商品 —— 权威 = <b>本产业的产出表</b>。</b>
   *
   * <p>★★ <b>为什么权威是产业产出表、而不是资产规则里的商品常量</b>：租金腿的语义（{@link RentRule} / {@link RentRule.RentLeg}
   * 的类注）是"该 unit <b>毛产的</b> {@code ratePerMille}‰"，落到结算侧就是 {@code OUTPUT_SHARE ×
   * GROSS_OUTPUT}（{@link #rentRules} 的 SHARE 分支）；而"毛产"是<b>这一个产业</b>的产出 —— {@code
   * ProductionSettlement.requireProducibleCommodities} 的 E14 判据（"规则指名的商品必须在该产业的产出表里"）说的正是同一件事。
   * 资产规则那边是 {@code (mode × 资产种类)} 的<b>常量表</b>（{@code
   * EconomySeeder.productionRuntimeRentRuleNode}：LAND 分成粮、其余资产分成布），它<b>看不见产业</b>；"非 LAND ⇒
   * 布"那条出厂标定成立的前提是"非 LAND 的租佃只发生在产布的手工业" —— A1（{@code a15fb3ec}）把 {@code trade@hex} 的产出从空表改成
   * <b>haul</b>（运输服务）之后，这条常量对它就是一条 E14 非法数据（{@code CATTLE} 是 trade 的产能资产 ⇒ 租佃腿恒取"布"）。 ⇒
   * 判据收敛到**产业产出表**这一个权威上（而不是给资产规则再加一份"按产业"的表）。
   *
   * <p>★★ <b>三条分支（都判在关系落盘之前，绝不静默）</b>：
   *
   * <ol>
   *   <li>腿上的商品<b>本产业能产</b> ⇒ 原样用（既有世界逐值不变：农田→粮 / 织机→布 / 作坊→布）；
   *   <li>腿上的商品<b>不能产</b>、且这是<b>分成腿</b>、且该产业<b>恰有一个产出商品</b> ⇒ 把分成记在<b>它自己的产出</b>上（这正是 "产出分成"的定义；记一条
   *       DEBUG，含被替换的商品与产业）；
   *   <li>其余情形（固定实物租的商品不能产 / 多产出产业判不出该取哪一个）⇒ <b>抛</b>，由调用方落具名 SHORTAGE、不建 unit —— 比落一条 E14
   *       非法的关系（下次关账整段 advance 被拒、`Bad_request`）或静默付 0 都好。
   * </ol>
   *
   * @param leg 实物腿（{@code commodity} 非空；货币腿不进本方法）；不得为 null
   * @param industry 这条 unit 的产业模板（产出表的唯一来源）；不得为 null
   * @param mode 组织这条 unit 的生产方式（只进日志/拒因）
   * @param recipient 受方（只进日志/拒因）
   * @return 该腿要结算的商品（恒非空）
   * @throws IllegalArgumentException 见上面第 3 条分支（调用方 fail-closed）
   */
  static Optional<CommodityId> rentCommodity(
      RentRule.RentLeg leg,
      IndustryDefinitionView industry,
      ProductionModeDefinitionView mode,
      Payee recipient) {
    CommodityId declared = leg.commodity().orElseThrow();
    Map<CommodityId, Long> outputs = industry.outputPerUnit();
    if (outputs.containsKey(declared)) {
      return Optional.of(declared); // ★ 能产 ⇒ 逐值不变（绝不动既有世界的关系）
    }
    if (leg.kind() == RentRule.RentType.SHARE && outputs.size() == 1) {
      CommodityId sole = outputs.keySet().iterator().next();
      EventLog.channel(EconomyLog.enterprise())
          .debug(
              LogEvent.of(
                  "RENT_COMMODITY_FROM_INDUSTRY_OUTPUT",
                  EconomyLogSource.ECONOMY_ORGANIZATION_RENT,
                  "mode",
                  mode.id().value(),
                  "industry",
                  industry.id().value(),
                  "assetCommodity",
                  declared.value(),
                  "industryCommodity",
                  sole.value(),
                  "recipient",
                  recipient.toString(),
                  "reason",
                  "share-rent-is-paid-in-this-industry-output"));
      return Optional.of(sole);
    }
    EventLog.channel(EconomyLog.enterprise())
        .info(
            LogEvent.of(
                "RENT_COMMODITY_NOT_PRODUCED_REFUSED",
                EconomyLogSource.ECONOMY_ORGANIZATION_RENT,
                "mode",
                mode.id().value(),
                "industry",
                industry.id().value(),
                "commodity",
                declared.value(),
                "industryOutputs",
                outputs.keySet().toString(),
                "rentKind",
                leg.kind().name(),
                "recipient",
                recipient.toString(),
                "reason",
                "rent-commodity-not-in-industry-output"));
    throw new IllegalArgumentException(
        "租金腿的商品不在该产业的产出表里（与 E14 同一判据，判在落关系之前）：industry="
            + industry.id().value()
            + " commodity="
            + declared.value()
            + " 该产业的产出="
            + outputs.keySet()
            + " kind="
            + leg.kind()
            + " mode="
            + mode.id().value()
            + " —— 判不出正确的商品 ⇒ 拒绝这条租佃（不落非法关系、不静默付 0）");
  }

  // ── 关系生成 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 新 unit 的关系：优先 regime 默认模板（唯一拼写点 {@link RegimeRelations}），模板里的 cohort 受方在本格解析不出 唯一行/或 regime 未登记
   * ⇒ 退回最小自留关系（空 rules = 产出全归 operator，既有等价路径）。
   */
  static GeneratedRelation generateRelation(
      ProductionModeDefinitionView mode,
      ProductionRole position,
      IndustryDefinitionView industry,
      IndustryId industryId,
      ProductionUnitId unitId,
      ActorRef organizer,
      HouseholdEconomy householdEconomy,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    try {
      ProductionRules byRegime =
          RegimeRelations.defaultRelation(
              industry.regime(),
              unitId,
              industryId,
              organizer,
              Set.of(householdEconomy.view().residence()));
      ProductionRules normalized =
          normalizePayees(byRegime, householdEconomies, householdEconomy.view().hex());
      if (normalized != null) {
        return new GeneratedRelation(
            normalized, "mode:" + mode.id().value() + ":regime:" + industry.regime().value());
      }
    } catch (IllegalArgumentException ignored) {
      // regime 未登记（没有默认关系模板）⇒ 退回最小自留关系；不猜制度。
    }
    ProductionRules minimal =
        new ProductionRules(
            unitId,
            organizer,
            new Payee.ToActor(organizer),
            List.of(),
            organizer,
            laborSourceOf(position));
    return new GeneratedRelation(
        minimal, "mode:" + mode.id().value() + ":position:" + position.id().value());
  }

  /**
   * ★★ <b>把 regime 默认模板里的 cohort 受方一对一归一成 {@link Payee.ToHousehold}，并剔除 受方 == organizer 的自付规则</b>。
   *
   * <p>★★ <b>为什么必须在 E2 就地归一</b>：新建 unit 的关系当天就会被 harvest/settle 使用，而 {@code EconomyData} 的构造期归一到
   * revision 边界才发生；若把 {@code ToCohort} 留到那时， 按劳动加权的分账会在 {@code SubsistenceObligation.laborOf}
   * 处因"没有家户身份"fail-closed（实测）。 返回 {@code null} = 视图不唯一 / 受方不在本格 / 家户不存在 ⇒ 调用方退回最小自留关系，不猜。
   *
   * <p>★★ <b>自付规则为什么直接剔除而不是留到转移端</b>：{@code Transfer} 的两端不得相等（自转移是坏数据）。 一条"付给 organizer
   * 自己"的规则对余额是恒等变换：产出已经归 residualOwner = organizer，工资/租金也只是 从自己的一个口袋到另一个口袋。这四档默认模板的 cohort 受方在 E2
   * 里就是 operator 本人时（家户自营）， 对应的份额 <b>本来就该留在 operator 手里</b>，故剔除；其余受方照原率保留。
   *
   * <p>★★ <b>GAP-3 共用</b>：{@code ModeMigrationSettlement.buildMigrationRelation} 走同一条模板也必须就地归一 ——
   * 否则迁移新建的关系会在**当天** harvest 时被 {@code requireCohortRows} 的"视图不再是唯一身份"守卫拒绝。归一只有这一份拼写点。
   */
  static ProductionRules normalizePayees(
      ProductionRules relation,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HexCoord hex) {
    Map<CohortKey, HouseholdId> householdByView = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (householdByView.putIfAbsent(householdEconomy.view(), householdEconomy.id()) != null) {
        ambiguous.add(householdEconomy.view());
      }
    }
    for (CohortKey view : ambiguous) {
      householdByView.remove(view);
    }
    List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
    boolean changed = false;
    for (CompensationRule rule : relation.rules()) {
      Payee recipient = rule.recipient();
      if (recipient instanceof Payee.ToHousehold toHousehold) {
        if (HouseholdActors.of(toHousehold.household()).equals(relation.operator())) {
          changed = true; // 自付：净额恒等，不落转移（见类注）
          continue;
        }
        HouseholdEconomy targetHouseholdEconomy = householdEconomies.get(toHousehold.household());
        if (targetHouseholdEconomy == null || !targetHouseholdEconomy.view().hex().equals(hex)) {
          return null;
        }
        rules.add(rule);
      } else if (recipient instanceof Payee.ToCohort toCohort) {
        HouseholdId household = householdByView.get(toCohort.cohort());
        if (household == null) {
          return null;
        }
        if (HouseholdActors.of(household).equals(relation.operator())) {
          changed = true; // 自付：与上面同一条口径
          continue;
        }
        HouseholdEconomy targetHouseholdEconomy = householdEconomies.get(household);
        if (targetHouseholdEconomy == null || !targetHouseholdEconomy.view().hex().equals(hex)) {
          return null;
        }
        rules.add(
            new CompensationRule(
                rule.type(),
                new Payee.ToHousehold(household),
                rule.pool(),
                rule.weight(),
                rule.ratePerMille(),
                rule.fixedAmount(),
                rule.commodity(),
                rule.currency(),
                rule.priority()));
        changed = true;
      } else if (recipient instanceof Payee.ToActor toActor
          && toActor.actor().equals(relation.operator())) {
        changed = true; // 自付：同一条口径（默认模板不走这档，留给将来）
        continue;
      } else {
        rules.add(rule);
      }
    }
    return changed
        ? new ProductionRules(
            relation.activity(),
            relation.operator(),
            relation.inputSupplier(),
            rules,
            relation.residualOwner(),
            relation.laborSource())
        : relation;
  }

  /** 位置 → 劳动来源档（最小自留关系用；E2 只做形状，逐档结算差异属后续阶段）。 */
  static LaborSource laborSourceOf(ProductionRole position) {
    return switch (position.surplusRole()) {
      case WAGE_EARNER ->
          position.relationToMeans() == RelationToMeans.DIRECT_LABORER
              ? LaborSource.TENANT
              : LaborSource.WAGE;
      case SELF_SUBSISTENCE -> LaborSource.SELF;
      default -> position.laborRole() == LaborRole.PROVIDER ? LaborSource.WAGE : LaborSource.SELF;
    };
  }

  // ── 小工具 ───────────────────────────────────────────────────────────────────────────────

  /** 一个组织指名的资产份额列表（同产业、同 operator；按 id 升序 ⇒ 字节可复现）。 */
  /** 同一 {@code (industry, operator)} 是否已有生产实例；只做 scope 守卫，不读资产数量。 */
  static boolean hasUnitInScope(
      Map<ProductionUnitId, ProductionProcess> units, IndustryId industry, ActorRef operator) {
    for (ProductionProcess unit : units.values()) {
      if (unit.industry().equals(industry) && unit.operator().equals(operator)) {
        return true;
      }
    }
    return false;
  }

  /** 候选进入前的自有份额计划表；不冒充运行时 D4 allocation。 */
  static Map<AssetKind, Long> candidateOwnAssets(
      IndustryId industry, ActorRef operator, Map<AssetShareId, OwnershipStake> assetShares) {
    Map<AssetKind, Long> assets = new LinkedHashMap<>();
    for (OwnershipStake share : assetShares.values()) {
      if (share.industry().equals(industry) && share.operator().equals(operator)) {
        assets.merge(share.asset(), share.quantity(), Math::addExact);
      }
    }
    return assets;
  }

  static List<AssetShareId> shareIdsOf(
      IndustryId industryId, ActorRef operator, Map<AssetShareId, OwnershipStake> assetShares) {
    List<AssetShareId> ids = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (share.industry().equals(industryId) && share.operator().equals(operator)) {
        ids.add(share.id());
      }
    }
    ids.sort(Comparator.comparing(AssetShareId::value));
    return ids;
  }

  /** 具名缺口结果（没有 unit；资产来源照实带过，便于读口看"当时有什么/缺什么"）。 */
  static OrganizeResult shortageResult(
      ProductionOrganizationId orgId,
      ProductionModeDefinitionView mode,
      ClassPositionId positionId,
      HouseholdId household,
      List<AssetShareId> assetSources,
      String reason) {
    return new OrganizeResult(
        shortage(orgId, mode, positionId, household, assetSources, reason), false);
  }

  /** 具名缺口组织本体（没有 unit）。 */
  static ProductionEnterprise shortage(
      ProductionOrganizationId orgId,
      ProductionModeDefinitionView mode,
      ClassPositionId positionId,
      HouseholdId household,
      List<AssetShareId> assetSources,
      String reason) {
    return new ProductionEnterprise(
        orgId,
        mode.id(),
        positionId,
        Optional.empty(),
        HouseholdActors.of(household),
        List.of(household),
        assetSources,
        List.of(),
        new Payee.ToActor(HouseholdActors.of(household)),
        Optional.empty(),
        Status.SHORTAGE,
        reason);
  }

  /** 组织 id 的兜底构造（id 工厂本身抛异常时用；把会引发地址截断的 {@code "."} 换成 {@code "_"}，保持确定性）。 */
  static ProductionOrganizationId orgIdFallback(
      ProductionModeDefinitionView mode,
      ClassPositionId positionId,
      HouseholdId household,
      String hexKey) {
    String sanitized =
        ("org-"
                + mode.id().value()
                + "-"
                + positionId.value()
                + "-"
                + household.value()
                + "-"
                + hexKey)
            .replace('.', '_');
    return new ProductionOrganizationId(sanitized);
  }

  /** {@code (mode, assetKind)} 的稳定索引键（只在内存里用，不进状态/线格式）。 */
  static String assetKindKey(AssetRule rule) {
    return assetKindKey(rule.modeId().value(), rule.assetKind());
  }

  /** {@code (mode, assetKind)} 的稳定索引键（字符串形态；上面是同义重载）。 */
  static String assetKindKey(String modeId, AssetKind assetKind) {
    return modeId + "|" + assetKind.name();
  }
}
