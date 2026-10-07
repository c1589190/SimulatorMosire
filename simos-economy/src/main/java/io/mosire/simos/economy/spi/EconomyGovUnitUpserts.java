package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.UpsertGovUnit} 的唯一语义落点</b>（Z1c，spec §4.2）：为某 GOV 单位创建/补齐一个 <b>行政服务生产
 * unit</b>（{@code operator = HOUSEHOLD:hh-gov-<govUnitId>}，{@code industry = 指定 office 产业模板}，
 * {@code unitId = ProductionUnitId.idOf(industryId, operator)}），并一次写入 {@code units} + {@code
 * assetShares} + {@code relations} 三张既有表（命令 handler 与 app 的 GM 窄工具共用本类的纯函数，照 {@code
 * EconomyIndustryUpserts} 先例）。
 *
 * <p>★★ <b>载荷（Z1c 冻结；命令 handler、GM 工具、catalog 提示同源）</b>：
 *
 * <pre>{@code
 * {"govUnitId":"u-central",          // 必填非空白；家户 = hh-gov-<govUnitId>（GovernmentHouseholds.of）
 *  "industryId":"office@0_0",        // 必填；必须已存在且 base kind = office（office / office_v2…）
 *  "assets":{"TOOL":1},              // 必填对象；逐值 ≥ 0；至少覆盖 capacityPerUnit 的 1 单位规模；键必须是该 recipe 的锚
 *  "modeKey":"gov_service",          // 可选；缺省 = gov_service（稳定键，ProductionProcess.modeKey）
 *  "reason":"gm:..."}                // 必填非空白（审计；不进状态）
 * }</pre>
 *
 * <p>★★ <b>写语义（四条，冻结）</b>：
 *
 * <ol>
 *   <li><b>unit</b>：{@code idOf(industryId, operator)} 不存在 ⇒ 新增 {@link ProductionProcess}（{@code
 *       progressDays=0}、 {@code cycleLaborMilli=0}、空投入）；已存在且 industry/operator/modeKey 逐值一致 ⇒
 *       不动（运行时进度不属于载荷字段，不重置也不冲突）； 任一字段不一致 ⇒ 具名 {@link Rejection}，绝不静默覆盖。
 *   <li><b>assetShares</b>：按 {@code ProductionProcessBook} 的“可用资产”口径（{@code industry} + {@code
 *       operator} 双等值，与 owner/kind 无关）汇总各资产；逐 recipe {@code capacityPerUnit} 的 kind 要求载荷量 ≥ 既有量： 相等
 *       ⇒ 不动；不足 ⇒ 新增一条 {@code OWNED} 份额（owner=operator）补差；既有量超过载荷量 ⇒ 具名拒（本命令只补不缩，不静默覆盖他人份额）。
 *       载荷中不属于该 recipe {@code capacityPerUnit} 的资产 kind ⇒ 具名拒（避免“发了没生效”）。
 *   <li><b>relations</b>：缺键 ⇒ 新增 V1 空规则 {@link ProductionRules}（{@code rules=[]}、{@code
 *       residualOwner=operator}、 {@code inputSupplier=ToActor(operator)}、{@code
 *       laborSource=SELF}）——即“产出全留 operator”的既有等价路径（E9 先例）； 已有关系与这条 期望值逐字不同 ⇒ 具名拒（工资等规则归 Z3 的
 *       {@code ADMIN_SALARY}，本区不发明也不覆盖）。
 *   <li><b>operatorConditions 不写</b>：既有语义把缺键读作 ACTIVE/中性 1000‰（{@code ProductionProcessBook} /
 *       {@code OperatorSettlement}），本区不造第二份状态；{@code ProductionEnterprise} 也不写（没有任何跨表不变量要求它； Z3/Z4
 *       接劳动来源与 岗位时才需要 mode/position/laborSources，本区不提前伪造）。
 * </ol>
 *
 * <p>★★ <b>幂等</b>：同一载荷重放时，unit/relations 逐值一致、逐 kind 可用资产 = 载荷量 ⇒ 返回空变更集（不落 revision）。reason 不进状态，
 * 只换 reason 的重放同样 no-op。
 *
 * <p>★★ <b>守卫与失败语义</b>：业务/世界规则（经济未激活、产业不存在/非 office、格未激活、GOV 未登记、缺 hh-gov 经济行/Social 家户、assets
 * 不足/超出、字段冲突）一律 {@link Rejection}（{@code handler} 折 INFO + {@code Rejected}，零 revision）； 载荷形状/坏
 * id/负值/未知资产由 {@link EconomyCommandPayloads} 与各身份工厂抛 {@link IllegalArgumentException}（同折 INFO +
 * {@code Rejected}）； 写出后的 {@link EconomyData} 跨表契约故障折 {@link
 * IllegalStateException}（ERROR、fail-closed、原样抛，不降级）。
 *
 * <p>★★ <b>GOV 单位守卫的模块边界（如实记，Z1c 台账“偏差”节同源）</b>：本类位于 {@code simos-economy}， enforcer 禁依赖 {@code
 * simos-unit} ⇒ <b>编译期看不见</b> {@code Unit}/{@code GovernmentFormation}。这里能查的最强信封 = 经济侧登记证据： {@code
 * governments[gov-unit-<id>].treasury == HOUSEHOLD:hh-gov-<id>}（{@code economy.RegisterGovernment}
 * 是 GOV 单位登记的唯一经济侧见证者，且 {@code EconomyData} 把“单位政府国库 = 该单位政府家户”判死）+ {@code classes} 里的 {@code
 * HouseholdEconomy} 行 + Social 家户表的 hh-gov 行。 app 的 GM 工具 {@code simos.economy.upsertGovUnit} 另有
 * unit 切片预检（unit 存在 + {@code module()} 是 {@code GovernmentFormation}）；GM 直接 {@code
 * simos.command.submit} 的裸路径只过本类的经济登记信封。
 *
 * <p>★ <b>铁律 2/3/5</b>：唯一写口是既有的 {@code
 * EconomyData.withProcesses/withRelations/withOwnershipStakes}；变更集由 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)} 从两整份状态派生；只写 economy 切片；表保序/不可变沿用 {@code
 * LinkedHashMap} + {@code Collections.unmodifiableMap}（不 {@code Map.copyOf}）。
 */
public final class EconomyGovUnitUpserts {

  /** 命令类型（与 {@link EconomyUpsertGovUnitHandler#TYPE} 同一拼写点）。 */
  public static final String COMMAND = EconomyUpsertGovUnitHandler.TYPE;

  /** 行政服务生产方式的稳定键（{@code modeKey} 缺省值；Z3 的 {@code GOV_SERVICE} 承诺按它认领 unit）。 */
  public static final String GOV_SERVICE_MODE_KEY = "gov_service";

  /** GOV 行政服务产业族的 base kind（spec §4.2 的 {@code office} / {@code office_v2} 约定）。 */
  public static final String OFFICE_BASE_KIND = "office";

  private EconomyGovUnitUpserts() {}

  /**
   * 解析后的冻结载荷（本类内部用；{@code assets} 已保序冻结、逐值 ≥ 0）。
   *
   * <p>★ {@code reason} 只作审计，不进任何状态字段。
   */
  private record Intent(
      String govUnitId,
      HouseholdId household,
      ActorRef operator,
      IndustryId industryId,
      Map<AssetKind, Long> assets,
      String modeKey,
      String reason) {

    private Intent {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(industryId, "industryId");
      Objects.requireNonNull(assets, "assets");
      Objects.requireNonNull(modeKey, "modeKey");
      Objects.requireNonNull(reason, "reason");
      assets = Collections.unmodifiableMap(new LinkedHashMap<>(assets));
    }
  }

  /**
   * 一次 upsert 的纯投影：身份、创建标志、写入前后的逐 kind 可用资产、以及由两整份状态派生的变更集。
   *
   * <p>★ {@code operator} 恒为 {@code HOUSEHOLD:hh-gov-<govUnitId>}；{@code unitId} 恒为 {@code
   * ProductionUnitId.idOf(industryId, operator)}（同一载荷重放得到同一 id）。
   */
  public record Projection(
      String govUnitId,
      HouseholdId household,
      ActorRef operator,
      IndustryId industryId,
      ProductionUnitId unitId,
      String modeKey,
      boolean unitCreated,
      boolean relationCreated,
      List<AssetShareId> sharesCreated,
      Map<AssetKind, Long> assetTotalsBefore,
      Map<AssetKind, Long> assetTotalsAfter,
      EconomyData projected,
      EconomyChangeSet changeSet) {

    public Projection {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(industryId, "industryId");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(modeKey, "modeKey");
      Objects.requireNonNull(sharesCreated, "sharesCreated");
      Objects.requireNonNull(assetTotalsBefore, "assetTotalsBefore");
      Objects.requireNonNull(assetTotalsAfter, "assetTotalsAfter");
      Objects.requireNonNull(projected, "projected");
      Objects.requireNonNull(changeSet, "changeSet");
      sharesCreated = List.copyOf(sharesCreated);
      assetTotalsBefore = Collections.unmodifiableMap(new LinkedHashMap<>(assetTotalsBefore));
      assetTotalsAfter = Collections.unmodifiableMap(new LinkedHashMap<>(assetTotalsAfter));
    }

    /** 逐值同载荷重放 ⇒ 空变更集（app 工具据此不落空 revision）。 */
    public boolean noop() {
      return changeSet.isEmpty();
    }
  }

  /**
   * <b>业务拒绝</b>（世界语义：GOV 未登记/未激活、产业不存在/非 office、assets 不足或超出、字段冲突等）：handler 折成具名 {@code Rejected} +
   * INFO；工具折成 {@code REJECTED}。载荷形状/类型错是普通 {@link IllegalArgumentException}，与它区分开。
   */
  public static final class Rejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public Rejection(String message) {
      super(Objects.requireNonNull(message, "message"));
    }
  }

  /**
   * 纯函数：读经济状态 + Social 家户表 + 载荷 JSON，算出目标 unit/份额/关系与派生变更集；不写任何状态。
   *
   * @param base 当前 economy 状态
   * @param social 当前 Social 状态（判 {@code hh-gov-<govUnitId>} 是否在 Social 家户表；economy 直连 social
   *     是既有的允许面）
   * @param payloadJson {@code economy.UpsertGovUnit} 载荷（形状见类注）
   * @throws Rejection 业务/世界规则拒绝（零 revision；理由具名）
   * @throws IllegalArgumentException 载荷形状/坏 id/负值/未知资产等（handler 同折 INFO + {@code Rejected}）
   * @throws IllegalStateException 写出后的状态违反 {@link EconomyData} 跨表契约（一致性故障，不是载荷错）
   */
  public static Projection project(EconomyData base, SocialData social, String payloadJson) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
    Intent intent = parseIntent(payload);
    Industry industry = requireOfficeIndustry(base, intent.industryId());
    requireActivatedHex(base, intent.industryId());
    requireGovernmentEnvelope(base, social, intent);
    return reconcile(base, intent, industry);
  }

  /** 载荷形状（字段缺失/类型/坏 id/负值/未知资产）→ 普通 IAE；不判任何世界状态。 */
  private static Intent parseIntent(JsonNode payload) {
    String govUnitId = EconomyCommandPayloads.requireText(COMMAND, payload, "govUnitId");
    HouseholdId household;
    try {
      household = GovernmentHouseholds.of(govUnitId);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(COMMAND + " 的 govUnitId 不合法: " + e.getMessage(), e);
    }
    ActorRef operator = HouseholdActors.of(household);
    IndustryId industryId;
    try {
      industryId =
          IndustryId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "industryId"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(COMMAND + " 的 industryId 不合法: " + e.getMessage(), e);
    }
    String modeKey =
        EconomyCommandPayloads.optionalText(COMMAND, payload, "modeKey", GOV_SERVICE_MODE_KEY);
    String reason = EconomyCommandPayloads.requireText(COMMAND, payload, "reason");
    JsonNode assetsNode = payload.get("assets");
    if (assetsNode == null || assetsNode.isNull()) {
      throw new IllegalArgumentException(
          COMMAND + " 缺少 assets 字段（{AssetKind: 数量}，逐值 ≥ 0，且至少覆盖 recipe capacityPerUnit 的 1 单位规模）");
    }
    // 复用既有资产表解析（逐值 ≥ 0、未知 AssetKind 具名拒、非对象具名拒）。
    Map<AssetKind, Long> assets =
        new LinkedHashMap<>(EconomyCommandPayloads.optionalAssetMap(COMMAND, payload, "assets"));
    return new Intent(govUnitId, household, operator, industryId, assets, modeKey, reason);
  }

  /**
   * 产业守卫：必须已存在、base kind = {@link #OFFICE_BASE_KIND}（{@code office}/{@code office_v2}…；Z1a 版本约定）。
   */
  private static Industry requireOfficeIndustry(EconomyData base, IndustryId industryId) {
    Industry industry = base.industries().get(industryId);
    if (industry == null) {
      throw new Rejection(
          COMMAND + " 的产业不存在: " + industryId.value() + "（先用 economy.UpsertIndustry 创建）");
    }
    String baseKind = EconomyIndustryUpserts.baseKindOf(industryId);
    if (!OFFICE_BASE_KIND.equals(baseKind)) {
      throw new Rejection(
          COMMAND
              + " 只接受 base kind = "
              + OFFICE_BASE_KIND
              + " 的产业（Z1a 约定：office / office_v2…），收到: "
              + industryId.value()
              + "（base kind="
              + baseKind
              + "）");
    }
    return industry;
  }

  /** 格守卫：产业 id 必须能解出格键，且该格已有已激活经济状态（与 Z1a 的空白格守卫同一判据）。 */
  private static void requireActivatedHex(EconomyData base, IndustryId industryId) {
    if (base.meta().isEmpty()) {
      throw new Rejection(COMMAND + " 需要 economy 已激活（先 economy.Seed 播种）才能建 GOV 生产 unit");
    }
    String hexKey =
        IndustryHexKeys.hexKeyOf(industryId)
            .orElseThrow(
                () ->
                    new Rejection(
                        COMMAND + " 的产业 id 没有可解析的格键（不是 <kind>@<q>_<r> 形状）: " + industryId.value()));
    if (!EconomySeedHandler.occupiedHexKeys(base).contains(hexKey)) {
      throw new Rejection(COMMAND + " 的产业所在格 " + hexKey + " 没有已激活的经济状态（该格不在任何产业/家户行的登记里）");
    }
  }

  /**
   * GOV 信封守卫（模块边界内可查的最强证据，见类注）：
   *
   * <ol>
   *   <li>经济侧 {@code governments} 有该单位的派生存 {@code gov-unit-<id>}，且国库 = {@code
   *       HOUSEHOLD:hh-gov-<id>}；
   *   <li>经济侧 {@code classes} 有 {@code hh-gov-<id>} 的 {@link HouseholdEconomy} 行；
   *   <li>Social 家户表有 {@code hh-gov-<id>}。
   * </ol>
   */
  private static void requireGovernmentEnvelope(
      EconomyData base, SocialData social, Intent intent) {
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(intent.govUnitId());
    } catch (IllegalArgumentException e) {
      // 与 GovernmentHouseholds.of 同校验规则；正常不可达（parseIntent 已判），保留为兜底载荷错。
      throw new IllegalArgumentException(COMMAND + " 的 govUnitId 不合法: " + e.getMessage(), e);
    }
    Government government = base.governments().get(governmentId);
    if (government == null) {
      throw new Rejection(
          COMMAND
              + " 要求 GOV 单位已在经济侧登记政府（先 unit.CreateUnit + unit.SetGovernmentFormation + "
              + "economy.RegisterGovernment）: "
              + governmentId.value());
    }
    if (!government.treasury().equals(intent.operator())) {
      // EconomyData 对单位政府已判死“国库 = 该单位政府家户”，走到这里说明状态与守卫不一致。
      throw new IllegalStateException(
          COMMAND
              + " 发现政府国库与 hh-gov 派生 actor 不一致（EconomyData 跨表守卫应已判死）: government="
              + governmentId.value()
              + " treasury="
              + government.treasury()
              + " expected="
              + intent.operator());
    }
    HouseholdEconomy householdEconomy = base.classes().get(intent.household());
    if (householdEconomy == null) {
      throw new Rejection(
          COMMAND + " 要求经济侧有政府家户的 HouseholdEconomy 行: " + intent.household().value());
    }
    if (!social.households().containsKey(intent.household())) {
      throw new Rejection(COMMAND + " 要求 Social 家户表里有政府家户: " + intent.household().value());
    }
  }

  /** unit/relations/assetShares 三表的最小重建；全部可失败判据已在前面完成，这里只落工作副本。 */
  private static Projection reconcile(EconomyData base, Intent intent, Industry industry) {
    ProductionUnitId unitId = ProductionUnitId.idOf(intent.industryId(), intent.operator());
    ProductionProcess existingUnit = base.units().get(unitId);
    ProductionProcess nextUnit = null;
    boolean unitCreated = false;
    if (existingUnit == null) {
      nextUnit =
          new ProductionProcess(
              unitId, intent.industryId(), intent.operator(), intent.modeKey(), 0L, 0L, Map.of());
      unitCreated = true;
    } else {
      if (!existingUnit.industry().equals(intent.industryId())) {
        throw fieldConflict(
            unitId, "industry", existingUnit.industry().value(), intent.industryId().value());
      }
      if (!existingUnit.operator().equals(intent.operator())) {
        throw fieldConflict(
            unitId, "operator", existingUnit.operator().toString(), intent.operator().toString());
      }
      if (!existingUnit.modeKey().equals(intent.modeKey())) {
        throw fieldConflict(unitId, "modeKey", existingUnit.modeKey(), intent.modeKey());
      }
    }

    ProductionRules expectedRelation = expectedRelation(unitId, intent.operator());
    ProductionRules existingRelation = base.relations().get(unitId);
    boolean relationCreated = false;
    if (existingRelation == null) {
      relationCreated = true;
    } else if (!existingRelation.equals(expectedRelation)) {
      throw new Rejection(
          COMMAND
              + " 的 unit "
              + unitId.value()
              + " 已有关系（ProductionRules）且与 V1 空规则/residualOwner=operator 不一致；本命令不静默覆盖"
              + "（工资等规则归 Z3）：已有 rules="
              + existingRelation.rules().size()
              + " residualOwner="
              + existingRelation.residualOwner()
              + " inputSupplier="
              + existingRelation.inputSupplier()
              + " laborSource="
              + existingRelation.laborSource());
    }

    Map<AssetKind, Long> totalsBefore =
        usableAssetTotals(base, intent.industryId(), intent.operator());
    for (AssetKind kind : intent.assets().keySet()) {
      if (!industry.capacityPerUnit().containsKey(kind)) {
        throw new Rejection(
            COMMAND
                + " 的 assets 含 recipe 未覆盖的资产种类 "
                + kind
                + "（该产业 capacityPerUnit 的锚 = "
                + industry.capacityPerUnit().keySet()
                + "）；本命令只写 recipe 锚定的份额，拒绝静默忽略");
      }
    }
    Map<AssetShareId, OwnershipStake> assetShares = new LinkedHashMap<>(base.assetShares());
    Map<AssetKind, Long> totalsAfter = new LinkedHashMap<>(totalsBefore);
    List<AssetShareId> sharesCreated = new ArrayList<>();
    for (Map.Entry<AssetKind, Long> anchor : industry.capacityPerUnit().entrySet()) {
      AssetKind kind = anchor.getKey();
      long required = anchor.getValue();
      Long provided = intent.assets().get(kind);
      if (provided == null || provided < required) {
        throw new Rejection(
            COMMAND
                + " 的 assets 未覆盖 1 单位规模：kind="
                + kind
                + " 需要 ≥ "
                + required
                + "（recipe capacityPerUnit），收到 "
                + (provided == null ? "缺键" : provided));
      }
      long existing = totalsAfter.getOrDefault(kind, 0L);
      if (existing > provided) {
        throw new Rejection(
            COMMAND
                + " 发现既有可用资产 "
                + kind
                + "="
                + existing
                + " 超过载荷 assets="
                + provided
                + "；本命令只补足、不静默缩/覆盖既有份额（要缩请先走 economy.TransferAssetShare）");
      }
      if (existing < provided) {
        long delta = provided - existing;
        AssetShareId shareId =
            nextShareId(
                assetShares,
                intent.industryId(),
                kind,
                intent.operator(),
                intent.operator(),
                OwnershipStake.RightKind.OWNED);
        OwnershipStake share =
            new OwnershipStake(
                shareId,
                intent.industryId(),
                kind,
                intent.operator(),
                intent.operator(),
                delta,
                OwnershipStake.RightKind.OWNED);
        assetShares.put(shareId, share);
        sharesCreated.add(shareId);
        totalsAfter.put(kind, Math.addExact(existing, delta));
      }
    }

    EconomyData projected =
        apply(base, unitId, nextUnit, expectedRelation, relationCreated, assetShares);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        intent.govUnitId(),
        intent.household(),
        intent.operator(),
        intent.industryId(),
        unitId,
        intent.modeKey(),
        unitCreated,
        relationCreated,
        sharesCreated,
        totalsBefore,
        totalsAfter,
        projected,
        changeSet);
  }

  /**
   * V1 关系的唯一拼写点：空规则 ⇒ 产出全留 {@code residualOwner=operator}（{@link ProductionRules} 类注的缺省等价路径，E9
   * 先例）；投入供应者/劳动来源取构造期缺省值（{@code ToActor(operator)} / {@code SELF}）的显式写法。
   */
  private static ProductionRules expectedRelation(ProductionUnitId unitId, ActorRef operator) {
    return new ProductionRules(
        unitId, operator, new Payee.ToActor(operator), List.of(), operator, LaborSource.SELF);
  }

  /** 单位/关系/份额三张表的顺序写回（`with*` 逐组件替换；每步都在 EconomyData 构造期过跨表守卫）。 */
  private static EconomyData apply(
      EconomyData base,
      ProductionUnitId unitId,
      ProductionProcess nextUnit,
      ProductionRules expectedRelation,
      boolean relationCreated,
      Map<AssetShareId, OwnershipStake> assetShares) {
    EconomyData projected = base;
    try {
      if (nextUnit != null) {
        Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>(base.units());
        units.put(unitId, nextUnit);
        projected = projected.withProcesses(units);
      }
      if (relationCreated) {
        Map<ProductionUnitId, ProductionRules> relations =
            new LinkedHashMap<>(projected.relations());
        relations.put(unitId, expectedRelation);
        projected = projected.withRelations(relations);
      }
      if (!assetShares.equals(base.assetShares())) {
        projected = projected.withOwnershipStakes(assetShares);
      }
    } catch (IllegalArgumentException e) {
      // ★ with* 的 IAE = 跨表契约被违反（不是载荷形状错）：折 ISE ⇒ handler 记 ERROR 并向上抛，不折 Rejected。
      throw new IllegalStateException(
          COMMAND + " 写出后的 EconomyData 违反跨表契约（一致性故障，非载荷问题）: " + e.getMessage(), e);
    }
    return projected;
  }

  /**
   * 可用资产汇总（与 {@code ProductionProcessBook.usableAssets} 同口径）：{@code industry} + {@code operator}
   * 双等值， 与 owner/kind 无关；逐 kind 按份额首次出现序保序。
   */
  private static Map<AssetKind, Long> usableAssetTotals(
      EconomyData base, IndustryId industryId, ActorRef operator) {
    Map<AssetKind, Long> totals = new LinkedHashMap<>();
    for (OwnershipStake share : base.assetShares().values()) {
      if (share.industry().equals(industryId) && share.operator().equals(operator)) {
        totals.merge(share.asset(), share.quantity(), Math::addExact);
      }
    }
    return totals;
  }

  /** 确定性新份额 id（照 {@code EconomyEntrySettlement.nextShareId} 先例）：从序号 0 起取第一个未占用者。 */
  private static AssetShareId nextShareId(
      Map<AssetShareId, OwnershipStake> assetShares,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      OwnershipStake.RightKind kind) {
    for (long sequence = 0L; ; sequence++) {
      AssetShareId candidate =
          OwnershipStake.idOf(industry, asset, owner, operator, kind, sequence);
      if (!assetShares.containsKey(candidate)) {
        return candidate;
      }
    }
  }

  /** 具名字段冲突（不静默覆盖）：构造标准理由后交给 {@link Rejection}。 */
  private static Rejection fieldConflict(
      ProductionUnitId unitId, String field, String actual, String expected) {
    return new Rejection(
        COMMAND
            + " 发现已存在的 unit "
            + unitId.value()
            + " 与载荷字段冲突（不静默覆盖）: "
            + field
            + " 现状="
            + actual
            + "，载荷="
            + expected);
  }
}
