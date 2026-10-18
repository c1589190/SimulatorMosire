package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.IndustrySeeder.singleOperatorRoleNodes;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 创世阶层归属与 (residence, slot) → production 默认位置的唯一裁决。 */
class ProductionRoleSeeder extends WorldBalanceSeeder {

  /**
   * ★★ <b>PRODUCTION_RUNTIME：{@code entries[].classes[]} → {@code classStandings[]}</b>。
   *
   * <p>每一条 {@code classes} 行写一条归属（人口 0 的行也在内，与 {@code EconomyData.classes} 的表一一对应）； {@code
   * originalPositionId == currentPositionId}、{@code retainedShares} 空表、压力/变迁数值 0、{@code reason} 取
   * {@link #PRODUCTION_RUNTIME_SEED_REASON}。slot → 默认位置由 {@link #productionRuntimePositionId} 唯一裁决；
   * 词表外槽位或目录缺位置都 fail-closed，<b>不静默少一条</b>。
   */
  static List<Map<String, Object>> productionRuntimeClassMemberships(
      List<Map<String, Object>> entries) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    Set<HouseholdId> seenHouseholds = new LinkedHashSet<>();
    for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
      Object classesValue = entries.get(entryIndex).get("classes");
      if (!(classesValue instanceof List<?> rows)) {
        throw new IllegalStateException(
            "PRODUCTION_RUNTIME 的 entry[" + entryIndex + "].classes 必须是数组: " + classesValue);
      }
      for (Object rowValue : rows) {
        if (!(rowValue instanceof Map<?, ?> row)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的 entry[" + entryIndex + "].classes[] 每项必须是对象: " + rowValue);
        }
        Object householdValue = row.get("householdId");
        Object slotValue = row.get("slot");
        Object residenceValue = row.get("residence");
        if (!(householdValue instanceof String householdText)
            || !(slotValue instanceof String slot)
            || !(residenceValue instanceof String residenceText)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的家户行必须带字符串 householdId/slot/residence：entry="
                  + entryIndex
                  + "，行="
                  + rowValue);
        }
        HouseholdId householdId = HouseholdId.parse(householdText);
        if (!seenHouseholds.add(householdId)) {
          throw new IllegalStateException(
              "PRODUCTION_RUNTIME 的家户 id 在 entries 里重复（classStandings 必须一户一条）: " + householdId);
        }
        // ★★ 2026-10-07 GOV 非生产家户试点：official 槽位的 GOV 家户**没有**阶层位置/HouseholdClassMembership ——
        //   它不生产、不持资产、不出劳动；关账日 HouseholdClassRule 在"无可观察证据"时保留当前 view（official）。
        //   这里只做 id 去重（上面已做），不发 standing。
        if (SocialClassId.OFFICIAL.value().equals(slotValue)) {
          continue;
        }
        // ★★ P11.7/D-024：位置由 (residence, slot) 共同裁决 —— 同一个 slot 在城乡映射到不同 mode。
        ClassPositionId positionId =
            productionRuntimePositionId(ResidenceKind.parse(residenceText), slot);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("householdId", householdId.value());
        node.put("originalPositionId", positionId.value());
        node.put("currentPositionId", positionId.value());
        node.put("retainedShares", new LinkedHashMap<String, Long>());
        node.put("consecutiveDebtStressCycles", 0L);
        node.put("lastTransitionDay", 0L);
        node.put("reason", PRODUCTION_RUNTIME_SEED_REASON);
        nodes.add(node);
      }
    }
    return nodes;
  }

  /**
   * ★★ <b>PRODUCTION_RUNTIME：(residence, slot) → 默认位置 id（本类唯一裁决点）</b>。
   *
   * <pre>
   * 农村：poor_peasant → wage_farm.wage_laborer；middle/rich_peasant → family_farm.family_farmer；
   *       landlord → tenancy_fixed_kind.landlord
   * 城镇：poor_peasant → handicraft_workshop.artisan；middle_peasant → handicraft_workshop.workshop_owner；
   *       rich_peasant → merchant.self_employed；landlord → merchant.principal
   * 任意居住类型：{@code landless_laborer}（流民槽位，{@link #DISPLACED_SLOT}）→ displaced.laborer
   * </pre>
   *
   * <p>位置 id 只能经 {@link DefaultProductionModes#positionId} 查得 ⇒ 本类不拼第二份位置串；目录与映射漂开时具名抛，
   * 不静默少一条。词表外槽位（本类没有裁决过的值）同样 fail-closed。
   */
  static ClassPositionId productionRuntimePositionId(ResidenceKind residence, String slot) {
    if (residence == null) {
      throw new IllegalStateException("PRODUCTION_RUNTIME 的 residence 不得为 null");
    }
    if (slot == null || slot.isBlank()) {
      throw new IllegalStateException("PRODUCTION_RUNTIME 的社会阶层槽位不得为空白");
    }
    if (DISPLACED_SLOT.equals(slot)) {
      // ★ 流民无工作：位置是 displaced.laborer（D-023），与居住类型无关（城乡各有一条流民行）。
      return requiredDefaultPosition(
          DefaultProductionModes.DISPLACED, DefaultProductionModes.ROLE_DISPLACED_LABORER);
    }
    boolean rural = residence == ResidenceKind.RURAL;
    if (CLASS_IDS[0].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.WAGE_FARM, DefaultProductionModes.ROLE_WAGE_LABORER)
          : requiredDefaultPosition(
              DefaultProductionModes.HANDICRAFT_WORKSHOP, DefaultProductionModes.ROLE_ARTISAN);
    }
    if (CLASS_IDS[1].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
          : requiredDefaultPosition(
              DefaultProductionModes.HANDICRAFT_WORKSHOP,
              DefaultProductionModes.ROLE_WORKSHOP_OWNER);
    }
    if (CLASS_IDS[2].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.FAMILY_FARM, DefaultProductionModes.ROLE_FAMILY_FARMER)
          : requiredDefaultPosition(
              DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_SELF_EMPLOYED);
    }
    if (CLASS_IDS[3].equals(slot)) {
      return rural
          ? requiredDefaultPosition(
              DefaultProductionModes.TENANCY_FIXED_KIND, DefaultProductionModes.ROLE_LANDLORD)
          : requiredDefaultPosition(
              DefaultProductionModes.MERCHANT, DefaultProductionModes.ROLE_MERCHANT_PRINCIPAL);
    }
    throw new IllegalStateException("PRODUCTION_RUNTIME 未裁决的社会阶层槽位（拒绝臆造映射）: " + slot);
  }

  /**
   * ★★ <b>M-C：这个（居住类型, 阶层槽位）的家户是不是"选了跑商"的</b>（= 它的出厂位置属于 {@code merchant} 生产方式）—— 创世启动工具的判据。
   *
   * <p>★ 走**位置目录**而不是写死槽位号：阶层 → 位置的映射在 {@link #productionRuntimePositionId} 一处， 这里只问它的 {@code
   * modeId} ⇒ 改映射不会漏改本判据（也不会多出第二处真相）。
   */
  static boolean isMerchantClassSlot(ResidenceKind residence, String slot) {
    ProductionRole position =
        DefaultProductionModes.position(productionRuntimePositionId(residence, slot))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "默认生产方式目录缺少位置（catalog 与 seeder 漂开）：residence="
                            + residence
                            + " slot="
                            + slot));
    return DefaultProductionModes.MERCHANT.equals(position.modeId());
  }

  /** ★ 默认目录必须给得出这个位置；给不出 = 目录与 seeder 漂开，具名抛（不猜另一个位置）。 */
  private static ClassPositionId requiredDefaultPosition(ProductionModeId modeId, String role) {
    return DefaultProductionModes.positionId(modeId, role)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "默认生产方式目录缺少位置：mode="
                        + modeId.value()
                        + " role="
                        + role
                        + "（catalog 与 seeder 漂开）"));
  }

  /**
   * ★★★ <b>D-032 C3 / D2 §4.3：把旧的 {@code CompensationRule} 规则表映射成 plan-level 角色节点</b>。
   *
   * <p>映射表（逐条对应 D2 §4.3 的处置表）：
   *
   * <ul>
   *   <li>{@code OUTPUT_SHARE} → {@code PRODUCT_SHARE} 角色；{@code ratePerMille} → {@code
   *       sharePerMille}；{@code LABOR_AMOUNT → BY_RECOGNIZED_LABOR}、{@code NONE/EQUAL → EQUAL}；
   *   <li>{@code FIXED_MONEY_WAGE} → {@code MONEY_WAGE} 角色（share=0；quote/target 归 W2a）；
   *   <li>{@code SELF_RETENTION} → 不建角色（residual 归 organizer）；
   *   <li>其余（含 {@code FIXED_IN_KIND_PER_LABOR} / {@code FIXED_IN_KIND_RENT} / {@code
   *       FIXED_MONEY_RENT}、 未知权重 / share 合计 &gt; 1000 / organizer 无 PRODUCT_SHARE 角色）→
   *       <b>本方法不抛异常</b>，返回 {@link LegacyRoleMapping#blocked(String)} 的具名原因，由调用方走旧路径。
   * </ul>
   *
   * <p>★★ <b>角色 id/名称只从 seed 事实派生</b>：cohort 受方取 {@code stratum.value()}、家户受方取 {@code household}、
   * actor 受方取 {@code actor.id}；重名在同一 plan 内追加稳定序号。没有"猜"出来的角色名。
   *
   * <p>★ 纯函数：不读状态、不写账户；**唯一输出** = 可映射的 roles 或 blocked 原因。不可表示不是创世故障， 而是"这个 plan 继续走旧 rules
   * 路径"的具名状态（控制方 2026-10-17 裁定选项 (a)）。
   */
  static LegacyRoleMapping legacyRuleRoleNodes(
      IndustryPlan plan, List<CompensationRule> rules, ActorRef operator) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(operator, "operator");
    if (rules.isEmpty()) {
      return LegacyRoleMapping.mapped(singleOperatorRoleNodes());
    }
    List<Map<String, Object>> roles = new ArrayList<>();
    Set<String> usedRoleIds = new LinkedHashSet<>();
    long productShareTotal = 0L;
    boolean organizerCovered = false;
    for (CompensationRule rule : rules) {
      switch (rule.type()) {
        case SELF_RETENTION -> {
          // 不建角色：residual 归 organizer（D2 §4.3 的 SELF_RETENTION 行）。
        }
        case OUTPUT_SHARE -> {
          if (rule.ratePerMille() <= 0L) {
            return LegacyRoleMapping.blocked("OUTPUT_SHARE:share-zero");
          }
          if (productShareTotal > 1_000L - rule.ratePerMille()) {
            // ★ 不在累加处抛 ArithmeticException：超额本身就是"走旧路径"的具名原因。
            return LegacyRoleMapping.blocked("OUTPUT_SHARE:share-total:>1000");
          }
          productShareTotal += rule.ratePerMille();
          roles.add(
              productShareRoleNode(
                  uniqueRoleId(rule, usedRoleIds),
                  roleNameOf(rule),
                  rule.ratePerMille(),
                  intraRoleSplitOf(rule.weight())));
          if (payeeIsOperator(rule.recipient(), operator)) {
            organizerCovered = true;
          }
        }
        case FIXED_MONEY_WAGE -> {
          roles.add(moneyWageRoleNode(uniqueRoleId(rule, usedRoleIds), roleNameOf(rule)));
          if (payeeIsOperator(rule.recipient(), operator)) {
            organizerCovered = true;
          }
        }
        // ★ 其余一切 kind（FIXED_IN_KIND_PER_LABOR / FIXED_IN_KIND_RENT / FIXED_MONEY_RENT，以及未来
        //   新增而本方法尚不识别的档）都走同一条 legacy 回退：旧规则不可表示 ⇒ 不建角色，具名 block
        //   后由 applyLegacyRoleMapping 原样保留旧 rules（不抛异常、不静默成功）。
        default -> {
          return LegacyRoleMapping.blocked(rule.type().name());
        }
      }
    }
    if (productShareTotal > 1_000L) {
      return LegacyRoleMapping.blocked("OUTPUT_SHARE:share-total:" + productShareTotal);
    }
    if (productShareTotal < 1_000L) {
      // 余数归 organizer（D2 §8.3）：显式给一个 organizer 角色，保证 D2-I3/I4 的"组织者是 PRODUCT_SHARE 角色"。
      roles.add(
          productShareRoleNode(
              ProductionRoleDefinition.defaultOperator().id().value(),
              ProductionRoleDefinition.defaultOperator().name(),
              1_000L - productShareTotal,
              IntraRoleSplitRule.EQUAL));
    } else if (!organizerCovered) {
      return LegacyRoleMapping.blocked("OUTPUT_SHARE:organizer-role-missing");
    }
    return LegacyRoleMapping.mapped(Collections.unmodifiableList(roles));
  }

  /**
   * ★★★ <b>D-032 C3：plan 级旧规则映射的唯一应用点</b>（控制方 2026-10-17 裁定选项 (a)）。
   *
   * <p>逐 plan 收集**主 unit + 全部副 unit** 的旧规则，然后：
   *
   * <ul>
   *   <li><b>可安全整体映射</b>（无不可表示项、product share 合计 ≤ 1000、organizer 有 PRODUCT_SHARE 角色）⇒ 写 plan-level
   *       roles、清空该 plan 所有 unit 的旧 rules（不双分）；
   *   <li><b>否则</b> ⇒ <b>原样保留旧 rules</b>、不添加新 roles/roster（roles 保持 {@code industry(...)} 的 C0 单
   *       operator 默认），并记 INFO 具名事件 {@code LEGACY_COMPENSATION_RULE_BLOCKED} （reason = {@code
   *       legacy-compensation-rule-blocked:<plan>:<detail>}）。
   * </ul>
   *
   * <p>★ 不抛异常、不阻断创世：不可表示的 plan 继续走既有旧 {@code ProductionRules.rules} 结算路径；C2 的自动名册只在 {@code rules}
   * 空时触发，因此不会出现"旧规则 + 名册双分"。
   */
  static void applyLegacyRoleMapping(
      List<IndustryPlan> plans,
      List<Map<String, Object>> units,
      Map<String, List<CompensationRule>> legacyRulesByPlan) {
    Objects.requireNonNull(plans, "plans");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(legacyRulesByPlan, "legacyRulesByPlan");
    for (IndustryPlan plan : plans) {
      List<CompensationRule> rules = legacyRulesByPlan.getOrDefault(plan.id(), List.of());
      LegacyRoleMapping mapping = legacyRuleRoleNodes(plan, rules, plan.operator());
      if (mapping.mapped() && mapping.roles().size() > 1) {
        // ★ 多角色 plan 必须同时有显式 productionRosters（D2 §5.4/§5.5）；C3 不在这里造 roster
        //   （planned labor/participants 需要逐 unit 劳动事实）⇒ 安全回退旧路径，避免
        //   `seed-roster-missing` 在后续创世校验里把世界挡住。
        mapping = LegacyRoleMapping.blocked("roles-require-explicit-roster");
      }
      if (mapping.mapped()) {
        plan.payload().put("roles", mapping.roles());
        if (!rules.isEmpty()) {
          clearLegacyRules(units, plan.id());
        }
        continue;
      }
      // ★ 选项 (a)：原样保留旧 rules；roles 保持 C0 单 operator 默认；不写 roster。
      plan.payload().put("roles", singleOperatorRoleNodes());
      String reason =
          "legacy-compensation-rule-blocked:" + plan.id() + ":" + mapping.blockedReason();
      io.mosire.simos.util.log.EventLog.channel(LOG)
          .info(
              io.mosire.simos.util.log.LogEvent.of(
                  "LEGACY_COMPENSATION_RULE_BLOCKED",
                  io.mosire.simos.app.AppLogSource.SHELL_LIFECYCLE,
                  "plan",
                  plan.id(),
                  "reason",
                  reason));
    }
  }

  /** 把某 plan 全部 unit 的 relation 节点里的旧 {@code rules} 清空（只在 plan 级映射成功时调用）。 */
  private static void clearLegacyRules(List<Map<String, Object>> units, String planId) {
    for (Map<String, Object> unit : units) {
      if (!planId.equals(unit.get("industry"))) {
        continue;
      }
      Object relation = unit.get("relation");
      if (relation instanceof Map<?, ?> relationNode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) relationNode;
        node.put("rules", List.of());
      }
    }
  }

  private static IntraRoleSplitRule intraRoleSplitOf(Weight weight) {
    return switch (weight) {
      case LABOR_AMOUNT -> IntraRoleSplitRule.BY_RECOGNIZED_LABOR;
      case NONE, EQUAL -> IntraRoleSplitRule.EQUAL;
        // ★ D2 §4.3 表里的 ASSET_QUANTITY→BY_RIGHTS 在当前 economy-api 已不可达：
        //   Weight.ASSET_QUANTITY 随产权表在 H0.5 整块退役（Weight 类注明写"不许加回来"）⇒ 没有可映射的旧规则形态。
    };
  }

  private static Map<String, Object> productShareRoleNode(
      String id, String name, long sharePerMille, IntraRoleSplitRule split) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id);
    node.put("name", name);
    node.put("sharePerMille", sharePerMille);
    node.put("intraRoleSplit", split.key());
    node.put("standardLaborMilliPerCycle", 0L);
    node.put("payment", "product_share");
    return Collections.unmodifiableMap(node);
  }

  private static Map<String, Object> moneyWageRoleNode(String id, String name) {
    Map<String, Object> payment = new LinkedHashMap<>();
    payment.put("kind", "money_wage");
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id);
    node.put("name", name);
    node.put("sharePerMille", 0L);
    node.put("intraRoleSplit", IntraRoleSplitRule.EQUAL.key());
    node.put("standardLaborMilliPerCycle", 0L);
    node.put("payment", Collections.unmodifiableMap(payment));
    return Collections.unmodifiableMap(node);
  }

  /** 角色 id 只从受方身份机械派生（cohort stratum / household / actor）；同 plan 内重名追加稳定序号。 */
  private static String uniqueRoleId(CompensationRule rule, Set<String> used) {
    String base = sanitizeRoleId(roleBaseId(rule.recipient()));
    String candidate = base;
    int suffix = 2;
    while (!used.add(candidate)) {
      candidate = base + "_" + suffix;
      suffix++;
    }
    return candidate;
  }

  private static String roleBaseId(Payee recipient) {
    return switch (recipient) {
      case Payee.ToCohort cohort -> cohort.cohort().stratum().value();
      case Payee.ToHousehold household -> "household_" + household.household().value();
      case Payee.ToActor actor -> "actor_" + actor.actor().id();
    };
  }

  private static String roleNameOf(CompensationRule rule) {
    return roleBaseId(rule.recipient());
  }

  private static String sanitizeRoleId(String text) {
    String sanitized = text == null ? "" : text.replaceAll("[^A-Za-z0-9_-]", "_");
    return sanitized.isBlank() ? "role" : sanitized;
  }

  private static boolean payeeIsOperator(Payee recipient, ActorRef operator) {
    return switch (recipient) {
      case Payee.ToCohort cohort ->
          HouseholdActors.of(
                  HouseholdIds.ofSeed(
                      cohort.cohort().hex(),
                      cohort.cohort().residence(),
                      cohort.cohort().stratum()))
              .equals(operator);
      case Payee.ToHousehold household ->
          HouseholdActors.of(household.household()).equals(operator);
      case Payee.ToActor actor -> actor.actor().equals(operator);
    };
  }
}
