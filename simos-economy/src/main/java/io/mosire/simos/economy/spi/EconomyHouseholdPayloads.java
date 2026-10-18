package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyPayloadSupport.actorRef;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.commodityMap;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalText;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireBoolean;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 劳动配额/名册/家户行的机械解析 helper（P0.5-C2）。 */
final class EconomyHouseholdPayloads {

  // ── 劳动供给 / 劳动分配（R2，设计稿 §四）──────────────────────────────────────────────

  /**
   * 一份劳动供给：{@code {group, period, grossLaborMilli, servedLaborMilli?, committedLaborMilli?}}。
   *
   * <p>★ **毛额由载荷给**（不由本层从人口算）：人口与年龄性别住在 social 的 {@code PopulationGroup}，economy 编译期不认识它 （设计稿
   * §二/§八.1）—— 算出毛额的是 {@code EconomySeeder}（它以 R1 已落地的年龄×性别系数表折算）。
   */

  /**
   * ★★ <b>R3B.2：配额 activity → unit</b>。新载荷必须直接给 unit id；旧载荷给活动标签（{@code farm}）或旧产业串 ⇒ 按 {@code
   * actor.id()} 当产业串找**唯一** unit 改写；多个候选抛（新载荷必须写 unit id，不猜）；解析不到 ⇒ 原样 （自由家户劳动，不喂任何生产，旧档同义）。
   */
  static HouseholdLaborCommitment canonicalAllocationActivity(
      HouseholdLaborCommitment laborCommitment,
      Map<String, List<ProductionUnitId>> unitsByIndustry,
      Map<ProductionUnitId, ProductionProcess> units,
      JsonNode entry) {
    ProductionUnitId byActivity = new ProductionUnitId(laborCommitment.activity());
    ProductionProcess direct = units.get(byActivity);
    if (direct != null) {
      // ★ activity 已是 unit id：actor 必须是该 unit 的 operator（守卫要求两者一致）——旧载荷里 actor 可能是
      //   产业 id（默认经营者同 id 时本就相等），这里按 unit.operator 对齐。
      return direct.operator().equals(laborCommitment.actor())
          ? laborCommitment
          : new HouseholdLaborCommitment(
              laborCommitment.id(),
              laborCommitment.group(),
              laborCommitment.household(),
              direct.operator(),
              laborCommitment.activity(),
              laborCommitment.laborMilli(),
              laborCommitment.period(),
              laborCommitment.kind(),
              laborCommitment.productionRole());
    }
    if (laborCommitment.activity().startsWith("unit-")) {
      throw new IllegalArgumentException(
          "劳动配额的 activity 看起来是 unit id 但该 unit 不在载荷里（悬空引用；自由家户劳动请用非 unit 的活动词）："
              + laborCommitment
              + "，entry="
              + entry);
    }
    List<ProductionUnitId> candidates =
        unitsByIndustry.getOrDefault(laborCommitment.actor().id(), List.of());
    if (candidates.isEmpty()) {
      candidates = unitsByIndustry.getOrDefault(laborCommitment.activity(), List.of());
    }
    if (candidates.size() == 1) {
      ProductionProcess resolved = units.get(candidates.get(0));
      return new HouseholdLaborCommitment(
          laborCommitment.id(),
          laborCommitment.group(),
          laborCommitment.household(),
          resolved == null ? laborCommitment.actor() : resolved.operator(),
          candidates.get(0).value(),
          laborCommitment.laborMilli(),
          laborCommitment.period(),
          laborCommitment.kind(),
          laborCommitment.productionRole());
    }
    if (candidates.size() > 1) {
      throw new IllegalArgumentException(
          "劳动配额的 activity 不是 unit id，而 actor 指名的产业有多条 unit ⇒ 无法确定是哪一条（请在载荷里写 unit id）："
              + laborCommitment
              + "，候选="
              + candidates
              + "，entry="
              + entry);
    }
    return laborCommitment;
  }

  /** 一条配额供给的 unit 集合（已对齐 activity；解析不到 ⇒ 空表）。 */
  static List<ProductionUnitId> resolveAllocationUnits(
      HouseholdLaborCommitment laborCommitment,
      Map<String, List<ProductionUnitId>> unitsByIndustry,
      Set<ProductionUnitId> unitIds) {
    ProductionUnitId byActivity = new ProductionUnitId(laborCommitment.activity());
    if (unitIds.contains(byActivity)) {
      return List.of(byActivity);
    }
    if (laborCommitment.activity().startsWith("unit-")) {
      return List.of(); // 悬空 unit 引用：不解析；由构造期守卫 fail-closed
    }
    List<ProductionUnitId> candidates =
        unitsByIndustry.getOrDefault(laborCommitment.actor().id(), List.of());
    if (candidates.isEmpty()) {
      candidates = unitsByIndustry.getOrDefault(laborCommitment.activity(), List.of());
    }
    return candidates.size() == 1 ? List.of(candidates.get(0)) : List.of();
  }

  /**
   * 一条劳动配额：{@code {id, group, actor:{kind,id}, activity, laborMilli, period}}。
   *
   * <p>★ {@code actor.kind} 走 {@link ActorKind#parse} 的**词表**（词表外的种类即抛并列出合法值）； {@code actor.id}
   * 与产业的对应关系由 {@code EconomyData} 的构造期守卫判死（不在这里重复实现）。
   */
  /**
   * ★★★ <b>D-032 C2（D2 §5.5）：顶层 {@code productionRosters} 的形状解析</b>。
   *
   * <p>★ 每一项与 {@code economy.SetProductionRoster} 同形（§5.4），但 {@code plan} 不由载荷给：它从 production
   * 当刻实例读出（给 plan 只会制造第二拼写点，故不接受）。{@code participants: []} = 清除该 unit 名册。
   *
   * <p>★ 本层只判 <b>形状</b>（unit 必须已在 production 实例投影里、participant 字段类型、id 词表）；角色存在性 / 份额 owner / 资格 /
   * 权重规则由 {@code EconomyReferenceValidation#validateProductionRosters} 在候选态判。
   */
  static Map<ProductionUnitId, ProductionRoster> productionRosters(
      JsonNode payload,
      ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionProcess> productionInstances) {
    Map<ProductionUnitId, ProductionRoster> rosters = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "productionRosters")) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("productionRosters 的元素必须是对象: " + node);
      }
      ProductionUnitId unit = ProductionUnitId.parse(requireText(node, "unit"));
      ProductionProcess instance = productionInstances.get(unit);
      if (instance == null) {
        throw new IllegalArgumentException(
            "roster-instance-unknown: 名册指名的生产实例不存在（实例的唯一权威在 production）: " + unit.value());
      }
      IndustryDefinitionView plan =
          definitions
              .industry(instance.plan())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "roster-plan-unknown: 名册实例的方案定义不存在: unit="
                              + unit.value()
                              + " plan="
                              + instance.plan().value()));
      Optional<String> declaredPlan = optionalText(node, "plan");
      if (declaredPlan.isPresent() && !plan.id().value().equals(declaredPlan.get())) {
        throw new IllegalArgumentException(
            "roster-plan-mismatch: 名册载荷的 plan 与 production 实例不一致: unit="
                + unit.value()
                + " 载荷="
                + declaredPlan.get()
                + " 实例="
                + plan.id().value());
      }
      List<ProductionParticipant> participants = new ArrayList<>();
      for (JsonNode participantNode : optionalArray(node, "participants")) {
        if (participantNode == null || !participantNode.isObject()) {
          throw new IllegalArgumentException(
              "productionRosters[].participants 的元素必须是对象: " + participantNode);
        }
        HouseholdId household = HouseholdId.parse(requireText(participantNode, "household"));
        ProductionRoleId role = ProductionRoleId.parse(requireText(participantNode, "role"));
        boolean organizer = requireBoolean(participantNode, "organizer");
        long plannedLaborMilliPerDay = requireLong(participantNode, "plannedLaborMilliPerDay");
        List<AssetShareId> rightsStakes = new ArrayList<>();
        for (JsonNode stakeNode : optionalArray(participantNode, "rightsStakes")) {
          if (stakeNode == null || !stakeNode.isTextual() || stakeNode.asText().isBlank()) {
            throw new IllegalArgumentException(
                "productionRosters[].participants[].rightsStakes 的元素必须是非空字符串: " + stakeNode);
          }
          rightsStakes.add(AssetShareId.parse(stakeNode.asText()));
        }
        long configuredWeight = optionalLong(participantNode, "configuredWeight", 0L);
        participants.add(
            new ProductionParticipant(
                household,
                role,
                organizer,
                plannedLaborMilliPerDay,
                rightsStakes,
                configuredWeight));
      }
      if (participants.isEmpty()) {
        rosters.remove(unit); // participants: [] = 清除该 unit 名册
        continue;
      }
      ProductionRoster roster = new ProductionRoster(unit, plan.id(), participants);
      if (rosters.put(unit, roster) != null) {
        throw new IllegalArgumentException("同一份载荷里 productionRosters 的 unit 重复: " + unit.value());
      }
    }
    return rosters;
  }

  /**
   * ★★★ <b>D-032 D2 §5.6：把一条载荷配额的 PRODUCTION 角色解析出来</b>。
   *
   * <p>解析次序（fail-closed，绝不猜）：
   *
   * <ol>
   *   <li>载荷显式给 {@code productionRole} ⇒ 用它（必须是该 plan 的角色）；
   *   <li>否则读该 unit 的 {@link ProductionRoster}：该户在名册里恰有一个角色 ⇒ 用它；多个 ⇒ {@code
   *       production-role-ambiguous}；零个 ⇒ {@code roster-participant-missing}；
   *   <li>没有名册但 plan 只有一个角色 ⇒ 用该角色（与单户默认名册同口径）；多角色 ⇒ {@code roster-required}；
   *   <li>activity 不是 production 里的实例（自由家户劳动）⇒ 必须有显式角色，否则 {@code production-role-required}。
   * </ol>
   */
  static ProductionRoleId resolveProductionRole(
      RawAllocation raw,
      Map<ProductionUnitId, ProductionProcess> productionInstances,
      Map<ProductionUnitId, ProductionRoster> productionRosters,
      ProductionDefinitionLookup definitions) {
    ProductionUnitId unit = new ProductionUnitId(raw.activity());
    ProductionProcess instance = productionInstances.get(unit);
    if (instance == null) {
      if (raw.role().isPresent()) {
        return raw.role().get();
      }
      throw new IllegalArgumentException(
          "production-role-required: 非生产实例的自由家户劳动也必须显式给 productionRole: activity="
              + raw.activity()
              + " household="
              + raw.household().value());
    }
    IndustryDefinitionView plan =
        definitions
            .industry(instance.plan())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "production-role-plan-unknown: 劳动配额指名的实例方案定义不存在: unit="
                            + unit.value()
                            + " plan="
                            + instance.plan().value()));
    if (raw.role().isPresent()) {
      ProductionRoleId role = raw.role().get();
      if (plan.roles().stream().noneMatch(definition -> definition.id().equals(role))) {
        throw new IllegalArgumentException(
            "role-unknown:"
                + role.value()
                + ": 劳动配额的 productionRole 不在方案角色里: unit="
                + unit.value());
      }
      return role;
    }
    ProductionRoster roster = productionRosters.get(unit);
    if (roster != null) {
      List<ProductionParticipant> matches = roster.participantsOf(raw.household());
      if (matches.size() == 1) {
        return matches.get(0).role();
      }
      if (matches.size() > 1) {
        throw new IllegalArgumentException(
            "production-role-ambiguous: 名册里该户有多个角色而配额未显式给 productionRole: unit="
                + unit.value()
                + " household="
                + raw.household().value());
      }
      throw new IllegalArgumentException(
          "roster-participant-missing: 名册里没有该户而配额未显式给 productionRole: unit="
              + unit.value()
              + " household="
              + raw.household().value());
    }
    if (plan.roles().size() == 1) {
      return plan.roles().get(0).id();
    }
    throw new IllegalArgumentException(
        "roster-required: 多角色方案的名册缺失（先给 productionRosters 或 economy.SetProductionRoster）: unit="
            + unit.value()
            + " plan="
            + plan.id().value());
  }

  static RawAllocation allocation(JsonNode node) {
    // （household 缺键的具名拒见下方 requireTextOrRejectAllocationHousehold）
    JsonNode actor = optionalObject(node, "actor");
    if (actor == null) {
      throw new IllegalArgumentException("劳动分配的字段 actor 必须是对象: " + node);
    }
    LaborAllocationId id = LaborAllocationId.parse(requireText(node, "id"));
    Optional<String> roleText = optionalText(node, "productionRole");
    return new RawAllocation(
        id,
        PeopleLotId.parse(requireText(node, "group")),
        // ★ S1：载荷必须显式给 household。★★ B2c（§13.12.5）：迁移期 pending 占位已整套删除 ⇒ 缺键**当场
        //   具名拒**（不再有自动补齐 —— 旧档迁移器 B2b 已删，没人会把它换成真实家户）。
        HouseholdId.parse(requireTextOrRejectAllocationHousehold(node, id)),
        actorRef(actor),
        requireText(node, "activity"),
        requireLong(node, "laborMilli"),
        requireLong(node, "period"),
        roleText.map(ProductionRoleId::parse));
  }

  /**
   * ★★★ <b>D-032 D2 §5.6：一条尚未挂角色的劳动配额（载荷中间态）</b>。
   *
   * <p>★ <b>为什么需要中间态</b>：名册/实例在载荷里的位置不保证在配额之前 ⇒ 角色必须等 `units[]` 与顶层 `productionRosters[]` 都解析完再解析；而
   * {@link HouseholdLaborCommitment} 的构造期守卫禁止 `PRODUCTION + empty`（不制造第二条无角色的生产承诺）。本记录只在 {@code
   * EconomyPayloads} 内部存活。
   */
  static record RawAllocation(
      LaborAllocationId id,
      PeopleLotId group,
      HouseholdId household,
      io.mosire.simos.actor.api.actor.ActorRef actor,
      String activity,
      long laborMilli,
      long period,
      Optional<ProductionRoleId> role) {

    HouseholdLaborCommitment withRole(ProductionRoleId resolved, boolean productionUnit) {
      // ★ D-032 D2 §5.6：生产承诺的 id 必须由 idOfProduction 派生（角色入 id）；非 unit 的自由劳动保留载荷 id。
      LaborAllocationId canonicalId =
          productionUnit
              ? HouseholdLaborCommitment.idOfProduction(
                  new ProductionUnitId(activity), resolved, group, household)
              : id;
      return new HouseholdLaborCommitment(
          canonicalId,
          group,
          household,
          actor,
          activity,
          laborMilli,
          period,
          LaborCommitmentKind.PRODUCTION,
          Optional.of(resolved));
    }
  }

  /**
   * ★ <b>B2c（§13.12.5）：劳动配额的 {@code household} 键必填</b> —— 缺键 ⇒ 具名拒（不再造 {@code legacy-pending-}
   * 占位：旧档迁移器 B2b 已整段删除，占位没有消费者）。
   */
  static String requireTextOrRejectAllocationHousehold(JsonNode node, LaborAllocationId id) {
    if (!node.hasNonNull("household")) {
      throw new IllegalArgumentException(
          "劳动配额缺少必填字段 household（B2c 起不再有旧档 pending 占位补齐）: " + id.value());
    }
    return requireText(node, "household");
  }

  /**
   * ★★ <b>一条家户行</b>（H0：键 = 该 entry 的格 + 行上显式声明的 {@code residence} + {@code slot}）： {@code
   * {residence, slot, population, laborMilli, participationPerMille, money, naturalNeeds,
   * effectiveDemand, cycleNaturalNeedMilli?}}。
   *
   * <p>★★ <b>三条 fail-closed（{@code goods} 那条是 H1 新增的）</b>：
   *
   * <ul>
   *   <li>{@code residence} <b>必填</b>（{@link ResidenceKind#parse}，词表外即抛）：居住维是家户身份的一维，而"行属于哪个产业"
   *       那层隐含（{@code farm}/{@code weave} = 农村）H0 之后没有了 ⇒ 按产业种类猜出来的第二份约定会与配额表漂开（见类注 ①）；
   *   <li>{@code meansOfProduction} <b>给了即抛</b>（K3）：产能搬到 {@code IndustryDefinitionView.capacity} ——
   *       静默忽略它 = "看起来在记、其实被丢掉"（真档表现为全格绝收而账面看不出是谁弄丢的）；
   *   <li>★★ {@code goods} <b>给了即抛</b>（H1；裁定 D3-C/K1）：家户的商品库存住在 actor 切片的 {@code
   *       HouseholdInventory}（键 {@code (HouseholdActors.of(cohort), cohort.hex())}），economy
   *       侧只在**会话工作副本**里读它 （{@code 旧日推进器（R3a 已删除）} 的入参）。★ 播种那一份要**搬**过去（app 的 {@code
   *       HouseholdSeeder}）， 静默忽略它 = 创世库存凭空消失（真档表现为第 1 天全员断粮，而载荷看起来完全正常）。
   * </ul>
   *
   * <p>★★ <b>M2.7 的 {@code cycleNaturalNeedMilli} 是可选键</b>（旧档缺键 ⇒ 0，照本类 {@code money} 的同款先例）：
   * 它是**结算逐日累加的读数**（本周期累计自然口粮需要），创世载荷通常不写它；旧载荷读成 0 = "还没开始累计"，不是"没有需要"。
   */
  static HouseholdEconomy householdEconomy(HexCoord hex, JsonNode node) {
    ResidenceKind residence = ResidenceKind.parse(requireText(node, "residence"));
    SocialClassId slot = SocialClassId.parse(requireText(node, "slot"));
    long population = requireLong(node, "population");
    long laborMilli = requireLong(node, "laborMilli");
    int participation = requireInt(node, "participationPerMille");
    if (node.hasNonNull("meansOfProduction")) {
      throw new IllegalArgumentException(
          "家户行不再有 meansOfProduction 键（K3：产能已搬到产业的 capacity 键，见 IndustryDefinitionView.capacity）："
              + node.get("meansOfProduction"));
    }
    if (node.hasNonNull("goods")) {
      throw new IllegalArgumentException(
          "家户行不再有 goods 键（H1/K1：商品库存住在 actor 切片的 HouseholdInventory 上，"
              + "economy 侧只在 旧日推进器（R3a 已删除） 的会话工作副本里读它 —— 播种时请把它搬进该家户 actor 的账户）："
              + node.get("goods"));
    }
    long money = optionalLong(node, "money", 0L);
    long cycleNaturalNeedMilli = optionalLong(node, "cycleNaturalNeedMilli", 0L);
    for (JsonNode debt : optionalArray(node, "debts")) {
      if (!debt.isTextual() || debt.asText().isBlank()) {
        throw new IllegalArgumentException("classes[].debts 的每项必须是非空 DebtContractId 字符串: " + debt);
      }
      // ★★ 2026-10-09 选项 A：载荷里的 {@code debts} 键**仍然校验形状、但不再进状态** —— 引用已拆成独立表
      //   {@code householdDebtRefs}，唯一权威是 {@code debtContracts} 表（构造期由 DebtReferenceReconciler 重建）。
      //   改前那份同名字段也只是派生索引（同一段对账会把它整份覆盖）⇒ 载荷里写什么从来不影响终态；
      //   保留校验只为"坏载荷照旧具名拒"，不是为了留下第二个引用真源。
      DebtContractId.parse(debt.asText());
    }
    Map<CommodityId, Long> needs =
        commodityMap(optionalObject(node, "naturalNeeds"), "naturalNeeds");
    Map<CommodityId, Long> demand =
        commodityMap(optionalObject(node, "effectiveDemand"), "effectiveDemand");
    HouseholdId householdId =
        node.hasNonNull("householdId")
            ? HouseholdId.parse(requireText(node, "householdId"))
            : HouseholdIds.ofSeed(hex, residence, slot);
    return new HouseholdEconomy(
        householdId,
        // ★ 这是创世载荷声明的 view（slot 可含 S3 新阶层）；运行期只由 HouseholdClassRule 改写 HouseholdEconomy.view，
        //   不回写载荷、也不反过来从 view 推身份（householdId 缺失时才由 ofSeed 生成稳定身份）。
        new CohortKey(hex, residence, slot),
        population,
        laborMilli,
        participation,
        money,
        needs,
        demand,
        cycleNaturalNeedMilli);
  }

  /** 键是 {@link AssetKind} 名的定点整数表（值非整/键不认识 ⇒ 抛）。 */
}
