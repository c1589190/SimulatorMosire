package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyEntrySettlement.AssetGrant;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.EntryContext;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.EntryIntent;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.Execution;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.Tables;
import static io.mosire.simos.economy.time.EconomyEntrySettlement.WriteMode;
import static io.mosire.simos.economy.time.EntryEvaluationBook.Attempt;
import static io.mosire.simos.economy.time.EntryEvaluationBook.accepted;
import static io.mosire.simos.economy.time.EntryEvaluationBook.assess;
import static io.mosire.simos.economy.time.EntryEvaluationBook.reject;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Entry 拆分 helper（由 EconomyEntrySettlement 抽取；行为不变）。 */
final class EntryExecutionBook {

  private EntryExecutionBook() {}

  /**
   * ★ <b>执行段（真写）</b>：在日结算的"现扣投入/劳动再分配"之前调用；对每条意向以**当前工作副本**重新评估，通过后才按 {@link WriteMode#REAL}
   * 落笔：production 的 {@code registerPlan}（本格物化定义，已存在则幂等 no-op）→ {@code createInstance}（实例；同 id 已存在 =
   * 具名 {@code unit-conflict}，不覆盖）→ 实例引用/构造守卫，然后写工作副本的 relation → condition(TRIALING) → 份额拆分 →
   * 劳动配额。任一步失败 ⇒ <b>当条</b>拒绝，不半建（已成功的条不回滚）。
   *
   * <p>★ 传入的 map 都是工作副本（由调用方持有并最终交出）；本方法**就地更新**，不另建第二份状态。★ 定义与实例<b>不</b>写进 工作副本的 {@code
   * industries}/{@code units} —— 它们的唯一权威是 production（本方法经 {@code runtime} 真写）， economy
   * 本地表写它们只会造出"第二份可写权威"。
   */
  static Execution execute(
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      List<EntryIntent> intents,
      Map<DemandId, HouseholdDemand> householdDemands,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<IndustryId, ActorRef> planAssetSources,
      Map<IndustryId, LaborSource> planLaborSources,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      MarketIndex markets,
      long day,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    Objects.requireNonNull(runtime, "runtime（D-032：试算走 production 契约，不接受 null）");
    Objects.requireNonNull(intents, "intents");
    Objects.requireNonNull(planAssetSources, "planAssetSources（缺省给空表 = 只认自有份额）");
    Objects.requireNonNull(planLaborSources, "planLaborSources（缺省给空表 = 全部 SELF）");
    if (intents.isEmpty()) {
      return new Execution(false, Set.of(), List.of());
    }
    Objects.requireNonNull(productionRosters, "productionRosters（C2：名册工作副本）");
    Tables tables =
        new Tables(
            industries,
            units,
            assetShares,
            laborCommitments,
            operatorConditions,
            relations,
            productionRosters);
    EntryContext context =
        new EntryContext(
            runtime,
            tables,
            householdDemands,
            householdEconomies,
            householdGoods,
            composition,
            markets,
            planAssetSources,
            planLaborSources,
            day);
    boolean changed = false;
    Set<ProductionUnitId> entered = new LinkedHashSet<>();
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (EntryIntent planned : intents) {
      Attempt attempt = assess(planned.household(), planned.plan(), context);
      if (attempt.intent() == null) {
        outcomes.add(
            attempt.rejection() != null
                ? attempt.rejection()
                : reject(context, planned.household(), planned.plan(), "REVALIDATION_NO_SIGNAL"));
        continue;
      }
      EntryIntent fresh = attempt.intent();
      // ★★ F5（R6）：执行段真写 —— registerPlan/createInstance/守卫 + 工作副本就地更新。
      Optional<String> failure = applyIntent(fresh, context, WriteMode.REAL);
      if (failure.isPresent()) {
        outcomes.add(reject(context, fresh.household(), fresh.plan(), failure.get()));
        continue;
      }
      changed = true;
      entered.add(ProductionUnitId.idOf(fresh.industryId(), fresh.householdActor()));
      outcomes.add(accepted(context, fresh));
    }
    return new Execution(changed, entered, outcomes);
  }

  /**
   * ★★★ <b>把一条意向落笔 —— 两种显式写模式（见 {@link WriteMode}），前置校验段两模式共用</b>。
   *
   * <p>先做<b>全量前置校验</b>（GRANT_SOURCE_*、UNIT_ID_EXISTS、ALLOCATION_ID_EXISTS、
   * LABOR_HOUSEHOLD_ROOM_GONE），任一失败 ⇒ 返回具名原因且<b>一个字不改</b>（production 与副本表都不动）。 校验全过后按模式分叉：
   *
   * <ul>
   *   <li>{@link WriteMode#SIMULATED_ONLY}（{@link #planEntries} 用）：<b>不碰 production 的任何写口</b> —— 不调
   *       {@code registerPlan}/{@code createInstance}/{@code validateProductionInstances}；新实例
   *       <b>只</b>写进 {@code context.tables.units()}（进度 0、无周期贡献/投入），使同批次后续 {@code assess} 的 {@code
   *       UNIT_ID_EXISTS} 与 {@code hasActiveDuplicate} 能在副本表看见它；relation/condition/份额/劳动配额 同样只写副本表；
   *   <li>{@link WriteMode#REAL}（{@link #execute} 用）：{@code registerPlan}（同 id 同值 = 幂等 no-op）→
   *       {@code createInstance}（同 id 实例已存在 = 具名 {@code unit-conflict}）→ 实例引用/构造守卫，然后写工作副本；
   *       <b>不</b>把定义/实例写进工作副本的 {@code industries}/{@code units}（唯一权威在 production）。
   * </ul>
   *
   * <p>★ 两模式在"写工作副本"这一段<b>逐值相同</b>：relation / condition(TRIALING) / 份额拆分 / 劳动配额 / 派生账本 （{@code
   * unitIdsByOperator}、{@code allocationMilliByHousehold}、{@code allocationMilliByGroup}）。 返回
   * {@link Optional#empty()} = 成功（真实写入或模拟登记完成）。
   *
   * <p>★★ <b>E5a 如实边界</b>：本方法里的份额写是<b>跨产业重新登记</b>（把来源主体名下的空闲份额改挂到方案的新 industryId，
   * 再交给本户经营），不是纯所有权/经营权转移 ⇒ 不委托 {@link OwnershipStakeBook}（Book 的守恒式是逐 {@code (industry, asset)}
   * 的，改产业不在它语义内）。这是记为遗留的显式例外，不是新增写路径；E5b 清算的转移/拆分只走 Book。
   */
  static Optional<String> applyIntent(EntryIntent intent, EntryContext context, WriteMode mode) {
    Tables tables = context.tables;
    for (AssetGrant grant : intent.grants()) {
      OwnershipStake source = tables.assetShares().get(grant.sourceShare());
      if (source == null) {
        return Optional.of("GRANT_SOURCE_GONE:" + grant.sourceShare().value());
      }
      if (source.quantity() < grant.quantity()) {
        return Optional.of(
            "GRANT_SOURCE_SHORT:share="
                + grant.sourceShare().value()
                + ",have="
                + source.quantity()
                + ",need="
                + grant.quantity());
      }
    }
    ProductionUnitId unitId = ProductionUnitId.idOf(intent.industryId(), intent.householdActor());
    if (tables.units().containsKey(unitId)) {
      return Optional.of("UNIT_ID_EXISTS:" + unitId.value());
    }
    // ★★★ D-032 D2 §5.4 / I16：真实执行时的名册前置判定（模拟期不落名册，只证明可以建）。
    Optional<ProductionRoster> defaultRoster = Optional.empty();
    if (mode == WriteMode.REAL) {
      List<io.mosire.simos.production.contract.ProductionRoleDefinition> roles =
          intent.plan().roles();
      if (roles.size() > 1 && !tables.productionRosters().containsKey(unitId)) {
        return Optional.of("roster-required:" + unitId.value());
      }
      if (roles.size() == 1) {
        HouseholdEconomy row = context.householdEconomies.get(intent.household());
        long remaining =
            row == null
                ? 0L
                : Math.max(
                    0L,
                    row.laborMilli()
                        - ProductionRosters.plannedLaborOf(
                            tables.productionRosters(), intent.household()));
        defaultRoster =
            ProductionRosters.defaultSingleHouseholdRoster(
                unitId, intent.plan(), intent.household(), remaining);
        if (defaultRoster.isEmpty()) {
          return Optional.of("roster-required:" + unitId.value());
        }
      }
    }
    LaborAllocationId allocationId = null;
    io.mosire.simos.economy.api.production.ProductionRoleId allocationRole =
        intent.plan().roles().size() == 1 ? intent.plan().roles().get(0).id() : null;
    if (intent.laborMilli() > 0L && allocationRole != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      allocationId =
          HouseholdLaborCommitment.idOfProduction(unitId, allocationRole, lot, intent.household());
      if (tables.laborCommitments().containsKey(allocationId)) {
        return Optional.of("ALLOCATION_ID_EXISTS:" + allocationId.value());
      }
      HouseholdEconomy roomHouseholdEconomy = context.householdEconomies.get(intent.household());
      long room =
          roomHouseholdEconomy == null
              ? 0L
              : roomHouseholdEconomy.laborMilli()
                  - context.allocationMilliByHousehold.getOrDefault(intent.household(), 0L);
      if (room < intent.laborMilli()) {
        return Optional.of(
            "LABOR_HOUSEHOLD_ROOM_GONE:household="
                + intent.household().value()
                + ",room="
                + room
                + ",need="
                + intent.laborMilli());
      }
    }

    // ── 校验全过：开始落笔（同一批内不再有可失败的依赖）。
    if (mode == WriteMode.SIMULATED_ONLY) {
      // ★★ F5（R6）：意向段的**只读模拟** —— 实例只写私有副本表（同批次 assess 的 UNIT_ID_EXISTS /
      //   hasActiveDuplicate 因此看得见它）；production 的 registerPlan/createInstance/守卫一个都不调
      //   ⇒ 真实定义/实例/changeSet 零变化。副本表在 planEntries 返回后即丢。
      tables
          .units()
          .put(
              unitId,
              new ProductionProcess(
                  unitId,
                  intent.industryId(),
                  intent.location(),
                  intent.householdActor(),
                  intent.plan().id().value(),
                  0L,
                  Map.of(),
                  Map.of()));
    } else {
      // ★★★ B2（§13.10.3 裁定五）：execute 的**真写** —— 方案定义与实例都经 production 的契约立起来：
      //   ① `registerPlan(定义)`：方案数值的唯一权威在 production（合并/冲突规则也在它那边）；
      //   ② `createInstance(请求)`：实例的唯一权威同样在 production。
      //   ★ 两件事都**幂等**（同 id 同值 = no-op；同 id 的实例已存在 = 具名拒 unit-conflict），
      //     故 revalidation 重复走到这里不会造出第二份权威。
      //   ★ economy 侧在**真写模式**下也**不**把定义/实例写进工作副本 —— `EconomyStateBuilder.industries()` /
      //     `units()` 直接读 production 的当刻候选态，写副本反而会造出"第二份可写权威"（本批明禁）。
      var planOutcome = context.runtime.registerPlan(intent.planDefinition());
      if (planOutcome
          instanceof io.mosire.simos.production.contract.ProductionPlanOutcome.Rejected rejected) {
        return Optional.of("PLAN_REGISTER_REJECTED:" + rejected.reason());
      }
      var instanceOutcome =
          context.runtime.createInstance(
              new io.mosire.simos.production.contract.ProductionCreationRequest(
                  intent.industryId(),
                  unitId,
                  intent.location(),
                  intent.householdActor(),
                  // ★★ B2c（裁定八）：modeKey = 方案 id 字面（版本键已删）—— 经验条/解锁因此围绕**方案稳定身份**
                  //   记账（换 hex 不重学同一门技术）。
                  intent.plan().id().value()));
      if (instanceOutcome
          instanceof
          io.mosire.simos.production.contract.ProductionInstanceOutcome.Rejected rejected) {
        return Optional.of("INSTANCE_CREATE_REJECTED:" + rejected.reason());
      }
      // ★★ B2（§13.10.4 第 4 条 / U7）：建档后**当场**用同一份只读投影复核"实例引用/构造守卫"
      //   （`EconomyReferenceValidation.validateProductionInstances`）—— 判据与旧 `EconomyData` 的 units
      //   构造期守卫同义（键==行内 id / 方案存在 / 进度闭区间 / 经营者非空 / 地点不是 UNSPECIFIED 占位），
      //   读的却是 production.contract 的**当刻候选态**（I13），不是我方本地表。★ 守卫一条没删，只换了读哪一份。
      io.mosire.simos.economy.EconomyReferenceValidation.validateProductionInstances(
          context.runtime, "EconomyEntrySettlement.applyIntent");
    }
    tables
        .relations()
        .put(
            unitId,
            new ProductionRules(
                unitId,
                intent.householdActor(),
                new Payee.ToHousehold(intent.household()),
                List.of(),
                intent.householdActor(),
                // ★★ B3（§13.13.3 裁定十二）：**逐值不变** —— 劳动来源取 economy 的**方案声明**
                //   （`EconomyMeta.planLaborSources`，键 = 不绑地点的方案 id；缺键 ⇒ SELF）。
                //   ★ 不得改回 `RegimeRelations.laborSourceFor(regime)`：那是"按制度派生"的**行为变更**
                //     （非 SELF 制度下与旧候选缺省 SELF 不同），裁定要把那条另立项 + 数值验收，不在本批。
                EconomyMeta.laborSourceOf(context.planLaborSources, intent.plan().id())));
    tables
        .operatorConditions()
        .put(
            unitId,
            new OperatorCondition(
                intent.industryId(),
                IndustryStatus.TRIALING,
                0L,
                0L,
                0L,
                0L,
                0L,
                Map.of(), // ★★ P-T4：上一周期收入按币分列（新入场 ⇒ 空表）
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                "entry:" + intent.plan().id().value(),
                0L,
                0L,
                0L,
                Map.of(), // ★★ P-T4：本周期累计货款按币分列（新入场 ⇒ 空表）
                0L,
                0L,
                0L,
                0L));
    for (AssetGrant grant : intent.grants()) {
      OwnershipStake source = tables.assetShares().get(grant.sourceShare());
      long left = source.quantity() - grant.quantity();
      if (left == 0L) {
        tables.assetShares().remove(grant.sourceShare());
      } else {
        tables
            .assetShares()
            .put(
                source.id(),
                new OwnershipStake(
                    source.id(),
                    source.industry(),
                    source.asset(),
                    source.owner(),
                    source.operator(),
                    left,
                    source.kind(),
                    source.location()));
      }
      AssetShareId newId =
          nextShareId(
              tables,
              intent.industryId(),
              grant.asset(),
              source.owner(),
              intent.householdActor(),
              grant.kind(),
              intent.location());
      tables
          .assetShares()
          .put(
              newId,
              new OwnershipStake(
                  newId,
                  intent.industryId(),
                  grant.asset(),
                  source.owner(),
                  intent.householdActor(),
                  grant.quantity(),
                  grant.kind(),
                  intent.location()));
    }
    if (allocationId != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      tables
          .laborCommitments()
          .put(
              allocationId,
              new HouseholdLaborCommitment(
                  allocationId,
                  lot,
                  intent.household(),
                  intent.householdActor(),
                  unitId.value(),
                  intent.laborMilli(),
                  intent.laborPeriod(),
                  io.mosire.simos.economy.api.labor.LaborCommitmentKind.PRODUCTION,
                  Optional.of(allocationRole)));
    }
    defaultRoster.ifPresent(roster -> tables.productionRosters().put(unitId, roster));
    // 派生账本同步（供同批次后续评估看见本条的占用）。
    context
        .unitIdsByOperator
        .computeIfAbsent(intent.householdActor(), ignored -> new ArrayList<>())
        .add(unitId);
    if (allocationId != null) {
      PeopleLotId lot = intent.laborLot().orElseThrow();
      context.allocationMilliByHousehold.merge(intent.household(), intent.laborMilli(), Long::sum);
      context.allocationMilliByGroup.merge(lot, intent.laborMilli(), Long::sum);
    }
    return Optional.empty();
  }

  /**
   * ★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link OwnershipStakeBook}</b>。理由：进入动作要把来源主体
   * 名下的空闲份额<b>改登记到本格物化定义</b>（{@code intent.industryId()}）后交给本户经营，这样新 unit 才 通过 {@code D4 资产占用预算} 的
   * {@code industry/operator/location} 作用域判据看见它；而 Book 的守恒式是 逐 {@code (industry, asset)}
   * 的，跨产业的重新登记不在它的语义内。这是记为遗留的显式例外，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link OwnershipStakeBook}。
   */
  static AssetShareId nextShareId(
      Tables tables,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      ProductionLocation location) {
    for (long sequence = 0L; ; sequence++) {
      AssetShareId candidate =
          OwnershipStake.idOf(industry, asset, owner, operator, kind, location, sequence);
      if (!tables.assetShares().containsKey(candidate)) {
        return candidate;
      }
    }
  }
}
