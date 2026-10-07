package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.SetGovServiceCommitment} 的唯一语义落点</b>（Z3a，spec §3/§4.2/§9/§10 C7/§19）： 为某 GOV
 * 单位的<b>行政服务 unit</b>写/改/清一条 {@link LaborCommitmentKind#GOV_SERVICE} 的 {@link
 * HouseholdLaborCommitment}。
 *
 * <p>★★ <b>载荷（Z3a 冻结；命令 handler 与后续 Z3c 的工具共用本纯函数）</b>：
 *
 * <pre>{@code
 * {"govUnitId":"u-central",            // 必填非空白；家户 = hh-gov-<govUnitId>
 *  "householdId":"hh-unit-u-central",   // 必填；承诺出劳动的家户（官吏户/外来户；须在 economy classes + Social 家户表）
 *  "laborMilli":16000,                  // 必填；≥ 0；0 = release（删除该 (household, activity) 的 GOV_SERVICE 承诺）
 *  "activity":"unit-office@0_0-HOUSEHOLD-hh-gov-u-central", // 可选；缺省按 operator+office 唯一解析
 *  "reason":"gov:..."}                  // 必填非空白（审计；不进状态）
 * }</pre>
 *
 * <p>★★ <b>一次写一张既有表</b>（铁律 2/5）：唯一写口 = {@link EconomyData#withLaborCommitments(Map)}； 变更集由 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)} 从两整份状态派生；本类只写 economy 切片（铁律 3）。
 *
 * <p>★★ <b>写语义（冻结）</b>：
 *
 * <ol>
 *   <li><b>laborMilli &gt; 0 = upsert</b>：写/改确定 id {@code gov-service:<activity>:<household>} 的那一条；
 *       已有 GOV_SERVICE 行 ⇒ 只改量、group/period/id 逐值保留（同量重放 = 空变更集 no-op）；
 *   <li><b>laborMilli = 0 = release</b>：删除该 (household, activity) 的 GOV_SERVICE 行；本来没有 ⇒ 空变更集 no-op
 *       （幂等释放）；<b>不碰</b>同 activity 的 PRODUCTION 行（release 不构成 kind 改写）；
 *   <li><b>同 (household, activity) 已有 PRODUCTION 承诺 ⇒ 具名冲突拒</b>（upsert 路径）：本命令绝不静默把生产承诺改写成
 *       GOV_SERVICE；请先释放生产承诺；
 *   <li><b>Σ 全部承诺 ≤ 家户 HouseholdEconomy.laborMilli 预检</b>：写入后越界 ⇒ 具名 {@link Rejection} （不让 {@link
 *       EconomyData} 构造期 IAE 承担业务拒绝语义）。
 * </ol>
 *
 * <p>★★ <b>id 约定（Z3a 冻结，确定性）</b>：{@code gov-service:<activity>:<household>}。含 {@code ":"} 但不含
 * {@code "."}（地址切段符）；{@code activity}/{@code household} 含点 ⇒ 具名拒（不静默造一个解析不到的地址）。
 *
 * <p>★★ <b>activity 解析</b>：缺省时在 {@code units} 里找 {@code operator == HOUSEHOLD:hh-gov-<govUnitId>} 且
 * industry base kind = {@code office}（Z1a 版本约定，{@code office}/{@code office_v2}…）的 unit；0 个 / 多个 ⇒
 * 具名拒并要求显式 {@code activity}（不猜）。显式 activity 仍要求是该 GOV 的 office unit。
 *
 * <p>★★ <b>group（批次位）口径</b>：GOV_SERVICE 是<b>家户级</b>全职承诺（spec §0 劳动力权威：供给取 {@code
 * HouseholdEconomy.laborMilli}，不造每角色劳动值），但 record 的 {@code group} 必填且要被人口缩放路径读。 新建时取该 Social
 * 家户<b>正成员批次里 id 规范序第一个</b>（确定性、真实批次 ⇒ {@code ResidenceKind.ofLot} 可解析）；家户 0 人口 ⇒ 具名拒（laborMilli=0
 * 的 release 不受此限）。已有行则逐值保留其 group/period。
 *
 * <p>★★ <b>C7</b>：写出的行是 {@code GOV_SERVICE} ⇒ 不进劳动队列、不可缩、最高优先级；死亡/预算缩放路径必须整额保留 （Z3a 落在 {@code
 * EconomySettlement} 与 {@code reallocateLabor}；越预算 ⇒ 具名 ERROR fail-closed）。
 *
 * <p>★ <b>模块边界</b>：economy enforcer 禁 unit ⇒ 这里只用经济侧 GOV 登记信封（{@code
 * governments[gov-unit-<id>].treasury == hh-gov actor}）+ {@code classes} 行 + Social 家户表（economy 直连
 * social 是既有允许面）；unit 切片检查（GOV 单位存在 + {@code GovernmentFormation}）由 app 组合根/工具承担（§18 裁定 A，本命令为
 * GM-only 裸命令路径）。
 */
public final class EconomyGovServiceCommitments {

  /** 命令类型（与 {@link EconomySetGovServiceCommitmentHandler#TYPE} 同一拼写点）。 */
  public static final String COMMAND = EconomySetGovServiceCommitmentHandler.TYPE;

  /** deterministic commitment id 前缀（唯一拼写点）。 */
  public static final String COMMITMENT_ID_PREFIX = "gov-service:";

  /** 承诺周期缺省（与队列新发 {@code PRODUCTION} 行同值：1 = 首个世界周期；常设承诺由行内 period 原样承载）。 */
  public static final long DEFAULT_PERIOD = 1L;

  private EconomyGovServiceCommitments() {}

  /**
   * 一次 upsert/release 的纯投影：命令身份、动作、写入量、以及由两整份状态派生的变更集。
   *
   * <p>★ {@code released=true} 只表示这是 release 语义（laborMilli=0）；真正删掉一条既有行时 {@code changeSet} 非空，
   * 本来就没有行时 {@code released=true && noop()=true}。
   */
  public record Projection(
      String govUnitId,
      HouseholdId household,
      ActorRef operator,
      ProductionUnitId unitId,
      LaborAllocationId commitmentId,
      boolean released,
      boolean created,
      long laborMilli,
      EconomyData projected,
      EconomyChangeSet changeSet) {

    public Projection {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(commitmentId, "commitmentId");
      Objects.requireNonNull(projected, "projected");
      Objects.requireNonNull(changeSet, "changeSet");
    }

    /** 逐值同载荷重放 / 释放不存在的行 ⇒ 空变更集（不落 revision）。 */
    public boolean noop() {
      return changeSet.isEmpty();
    }

    /** 日志动作标签（created / updated / release / noop）。 */
    public String action() {
      if (noop()) {
        return "noop";
      }
      if (released) {
        return "release";
      }
      return created ? "created" : "updated";
    }
  }

  /**
   * <b>业务/世界规则拒绝</b>（GOV 未登记、unit 不是行政服务 unit、家户不存在、PRODUCTION 冲突、Σ 越预算、0 人口写非 0）： handler 折具名
   * {@code Rejected} + INFO；载荷形状/坏 id 仍是普通 {@link IllegalArgumentException}（同折 INFO）。
   */
  public static final class Rejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public Rejection(String message) {
      super(Objects.requireNonNull(message, "message"));
    }
  }

  /**
   * 纯函数：读经济状态 + Social 家户表 + 载荷 JSON，算出目标承诺行与派生变更集；不写任何状态。
   *
   * @param base 当前 economy 状态
   * @param social 当前 Social 状态（家户表 + 正成员批次；economy 直连 social 是既有的允许面）
   * @param payloadJson {@code economy.SetGovServiceCommitment} 载荷（形状见类注）
   * @throws Rejection 业务/世界规则拒绝（零 revision；理由具名）
   * @throws IllegalArgumentException 载荷形状/坏 id/负值等（handler 同折 INFO + {@code Rejected}）
   * @throws IllegalStateException 写出后的状态违反 {@link EconomyData} 跨表契约、或既有 GOV_SERVICE 行破坏确定性 id
   *     约定（一致性故障，不是载荷错）
   */
  public static Projection project(EconomyData base, SocialData social, String payloadJson) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
    String govUnitId = EconomyCommandPayloads.requireText(COMMAND, payload, "govUnitId");
    HouseholdId govHousehold = parseGovHousehold(govUnitId);
    ActorRef operator = HouseholdActors.of(govHousehold);
    HouseholdId household = parseHousehold(payload);
    long laborMilli = EconomyCommandPayloads.requireLong(COMMAND, payload, "laborMilli");
    if (laborMilli < 0L) {
      throw new IllegalArgumentException(COMMAND + " 的 laborMilli 不得为负: " + laborMilli);
    }
    String activityText = EconomyCommandPayloads.optionalText(COMMAND, payload, "activity", null);
    // reason 只作审计（不进状态）；仍要求非空白，保住"每条写命令都可审计"的既有边界。
    EconomyCommandPayloads.requireText(COMMAND, payload, "reason");
    requireGovernmentEnvelope(base, social, govUnitId, govHousehold, operator);
    ProductionUnitId unitId = resolveGovServiceUnit(base, operator, activityText);
    requireCommitmentHousehold(base, social, household);
    return reconcile(base, social, govUnitId, operator, unitId, household, laborMilli);
  }

  /** GOV 单位 id → {@code hh-gov-<id>}（校验口径与 Z1c 同源：{@link GovernmentHouseholds#of(String)}）。 */
  private static HouseholdId parseGovHousehold(String govUnitId) {
    try {
      return GovernmentHouseholds.of(govUnitId);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(COMMAND + " 的 govUnitId 不合法: " + e.getMessage(), e);
    }
  }

  /** {@code householdId} 解析（opaque id，只判非空白；与其余经济命令同款）。 */
  private static HouseholdId parseHousehold(JsonNode payload) {
    String text = EconomyCommandPayloads.requireText(COMMAND, payload, "householdId");
    try {
      return HouseholdId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(COMMAND + " 的 householdId 不合法: " + e.getMessage(), e);
    }
  }

  /**
   * GOV 信封守卫（模块边界内可查的最强证据，与 {@code EconomyGovUnitUpserts} 同款）： {@code governments[gov-unit-<id>]}
   * 存在且 treasury == {@code HOUSEHOLD:hh-gov-<id>}；economy {@code classes} 有 hh-gov 行；Social 家户表有
   * hh-gov。
   */
  private static void requireGovernmentEnvelope(
      EconomyData base,
      SocialData social,
      String govUnitId,
      HouseholdId govHousehold,
      ActorRef operator) {
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(govUnitId);
    } catch (IllegalArgumentException e) {
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
    if (!government.treasury().equals(operator)) {
      throw new IllegalStateException(
          COMMAND
              + " 发现政府国库与 hh-gov 派生 actor 不一致（EconomyData 跨表守卫应已判死）: government="
              + governmentId.value()
              + " treasury="
              + government.treasury()
              + " expected="
              + operator);
    }
    if (!base.classes().containsKey(govHousehold)) {
      throw new Rejection(COMMAND + " 要求经济侧有政府家户的 HouseholdEconomy 行: " + govHousehold.value());
    }
    if (!social.households().containsKey(govHousehold)) {
      throw new Rejection(COMMAND + " 要求 Social 家户表里有政府家户: " + govHousehold.value());
    }
  }

  /**
   * 行政服务 unit 解析：缺省 = 该 GOV 的 office unit（operator + base kind 判）；0/多个 ⇒ 具名拒并要显式 activity。 显式
   * activity 也必须是该 GOV 的 office unit（存在 + operator 命中 + industry base kind=office）。
   */
  private static ProductionUnitId resolveGovServiceUnit(
      EconomyData base, ActorRef operator, String activityText) {
    if (activityText == null) {
      List<ProductionUnitId> candidates = govServiceUnitsOf(base, operator);
      if (candidates.isEmpty()) {
        throw new Rejection(
            COMMAND
                + " 找不到 GOV operator="
                + operator.kind()
                + "|"
                + operator.id()
                + " 的行政服务 unit（operator=hh-gov 且 industry base kind=office）；"
                + "先用 economy.UpsertGovUnit 创建，或显式给 activity");
      }
      if (candidates.size() > 1) {
        throw new Rejection(
            COMMAND
                + " 发现多个候选行政服务 unit "
                + candidates
                + "（operator="
                + operator.id()
                + "）；本命令不猜，请显式给 activity");
      }
      return candidates.get(0);
    }
    ProductionUnitId explicit;
    try {
      explicit = ProductionUnitId.parse(activityText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(COMMAND + " 的 activity 不合法: " + e.getMessage(), e);
    }
    ProductionProcess unit = base.units().get(explicit);
    if (unit == null) {
      throw new Rejection(COMMAND + " 的 activity 指名的 unit 不存在: " + explicit.value());
    }
    if (!unit.operator().equals(operator)) {
      throw new Rejection(
          COMMAND
              + " 的 activity 不属于 GOV "
              + operator.id()
              + "：unit.operator="
              + unit.operator().kind()
              + "|"
              + unit.operator().id());
    }
    String baseKind = baseKindOrContractFault(base, unit);
    if (!EconomyGovUnitUpserts.OFFICE_BASE_KIND.equals(baseKind)) {
      throw new Rejection(
          COMMAND
              + " 的 activity 不是行政服务 unit（industry base kind 必须="
              + EconomyGovUnitUpserts.OFFICE_BASE_KIND
              + "，收到 "
              + unit.industry().value()
              + "，base kind="
              + baseKind
              + "）");
    }
    return explicit;
  }

  /** 该 GOV 名下的 office unit（保序，units 表首次出现序）；industry/base kind 由状态守卫保证存在。 */
  private static List<ProductionUnitId> govServiceUnitsOf(EconomyData base, ActorRef operator) {
    List<ProductionUnitId> candidates = new ArrayList<>();
    for (ProductionProcess unit : base.units().values()) {
      if (!unit.operator().equals(operator)) {
        continue;
      }
      if (EconomyGovUnitUpserts.OFFICE_BASE_KIND.equals(baseKindOrContractFault(base, unit))) {
        candidates.add(unit.id());
      }
    }
    return candidates;
  }

  /** unit 指名的产业必须存在（EconomyData 跨表守卫应已判死；读到缺产业 ⇒ 一致性故障，不是载荷错）。 */
  private static String baseKindOrContractFault(EconomyData base, ProductionProcess unit) {
    if (!base.industries().containsKey(unit.industry())) {
      throw new IllegalStateException(
          COMMAND
              + " 读到 unit 指名的产业模板不存在（EconomyData 跨表守卫应已判死，一致性故障）: unit="
              + unit.id().value()
              + " industry="
              + unit.industry().value());
    }
    return EconomyIndustryUpserts.baseKindOf(unit.industry());
  }

  /** 出劳动的家户必须同时存在于 economy classes 与 Social 家户表（spec §3 家户权威 + §4.2 同表承诺）。 */
  private static void requireCommitmentHousehold(
      EconomyData base, SocialData social, HouseholdId household) {
    if (!base.classes().containsKey(household)) {
      throw new Rejection(COMMAND + " 的家户不存在于 economy classes: " + household.value());
    }
    if (!social.households().containsKey(household)) {
      throw new Rejection(COMMAND + " 的家户不存在于 Social 家户表: " + household.value());
    }
  }

  /** 确定性承诺 id（Z3a 冻结）：{@code gov-service:<activity>:<household>}。含 {@code "."} ⇒ 具名拒（地址会被点截断）。 */
  public static LaborAllocationId commitmentIdOf(ProductionUnitId unitId, HouseholdId household) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(household, "household");
    String value = COMMITMENT_ID_PREFIX + unitId.value() + ":" + household.value();
    if (value.indexOf('.') >= 0) {
      throw new Rejection(COMMAND + " 的确定性承诺 id 含 '.'（经济资源地址按点切段，拒绝解析不到的地址）: " + value);
    }
    return new LaborAllocationId(value);
  }

  /** 新建行的批次位：Social 家户的正成员批次按规范 id 升序取第一个；0 人口 ⇒ 具名拒。 */
  private static PeopleLotId requireHouseholdLot(SocialData social, HouseholdId household) {
    Household socialHousehold = social.households().get(household);
    if (socialHousehold == null) {
      // 前面 requireCommitmentHousehold 已判；保留为兜底（状态在纯函数内不变）。
      throw new Rejection(COMMAND + " 的家户不存在于 Social 家户表: " + household.value());
    }
    List<PeopleLotId> lots = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> member : socialHousehold.members().entrySet()) {
      if (member.getValue() != null && member.getValue() > 0L) {
        lots.add(member.getKey());
      }
    }
    if (lots.isEmpty()) {
      throw new Rejection(
          COMMAND + " 的家户没有正成员批次（0 人口不能承担全职行政岗位承诺；laborMilli=0 可用于释放既有承诺）: " + household.value());
    }
    lots.sort(Comparator.comparing(PeopleLotId::value));
    return lots.get(0);
  }

  /** 逐行重建 allocations 表的最小写口；预检已在前面完成，这里只落工作副本并把越界 IAE 折成契约 ISE。 */
  private static Projection reconcile(
      EconomyData base,
      SocialData social,
      String govUnitId,
      ActorRef operator,
      ProductionUnitId unitId,
      HouseholdId household,
      long laborMilli) {
    LaborAllocationId rowId = commitmentIdOf(unitId, household);
    HouseholdLaborCommitment existing = null;
    boolean productionConflict = false;
    for (HouseholdLaborCommitment commitment : base.allocations().values()) {
      if (!commitment.household().equals(household)
          || !commitment.activity().equals(unitId.value())) {
        continue;
      }
      if (commitment.kind() != LaborCommitmentKind.GOV_SERVICE) {
        productionConflict = true;
        continue;
      }
      if (existing != null) {
        throw new IllegalStateException(
            COMMAND
                + " 发现同一 (household, activity) 有多条 GOV_SERVICE 承诺（确定性 id 约定被破坏，一致性故障）: household="
                + household.value()
                + " activity="
                + unitId.value()
                + " ids="
                + existing.id().value()
                + ", "
                + commitment.id().value());
      }
      existing = commitment;
    }
    if (existing != null && !existing.id().equals(rowId)) {
      throw new IllegalStateException(
          COMMAND
              + " 既有 GOV_SERVICE 承诺的 id 与确定性约定不一致（一致性故障）: 既有="
              + existing.id().value()
              + " 期望="
              + rowId.value()
              + "（只允许本命令写该 (household, activity) 的 GOV_SERVICE）");
    }
    // ★ id 约定不得与既有 allocation 行冲突：确定性 id 若已被别的行占用（哪怕不是本 pair 的 GOV 行，
    //   例如同名的 PRODUCTION/自由劳动行）⇒ fail-closed，绝不 put/remove 覆盖它。
    HouseholdLaborCommitment rowAtDeterministicId = base.allocations().get(rowId);
    if (rowAtDeterministicId != null && !rowAtDeterministicId.equals(existing)) {
      throw new IllegalStateException(
          COMMAND
              + " 的确定性承诺 id 已被其它 allocation 行占用（一致性/身份冲突，拒绝覆盖）: id="
              + rowId.value()
              + " 既有 kind="
              + rowAtDeterministicId.kind()
              + " household="
              + rowAtDeterministicId.household().value()
              + " activity="
              + rowAtDeterministicId.activity());
    }
    // ★ kind 冲突：upsert 绝不像征性改写成 GOV_SERVICE；release（0）不构成改写，照常释放 GOV_SERVICE 行。
    if (laborMilli > 0L && productionConflict) {
      throw new Rejection(
          COMMAND
              + " 的 (household, activity) 已有 PRODUCTION 承诺，禁止静默改写 kind；"
              + "请先释放该生产承诺再写 GOV_SERVICE: household="
              + household.value()
              + " activity="
              + unitId.value());
    }
    if (laborMilli == 0L) {
      if (existing == null) {
        return new Projection(
            govUnitId,
            household,
            operator,
            unitId,
            rowId,
            true,
            false,
            0L,
            base,
            EconomyChangeSet.between(base, base));
      }
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> allocations =
          new LinkedHashMap<>(base.allocations());
      allocations.remove(rowId);
      EconomyData projected = apply(base, allocations);
      return new Projection(
          govUnitId,
          household,
          operator,
          unitId,
          rowId,
          true,
          false,
          0L,
          projected,
          EconomyChangeSet.between(base, projected));
    }
    if (existing != null && existing.laborMilli() == laborMilli) {
      // 同量重放：id/group/period/kind 全部保留 ⇒ 逐值相同 = 空变更集 no-op。
      return new Projection(
          govUnitId,
          household,
          operator,
          unitId,
          rowId,
          false,
          false,
          laborMilli,
          base,
          EconomyChangeSet.between(base, base));
    }
    HouseholdEconomy householdEconomy = base.classes().get(household);
    if (householdEconomy == null) {
      // requireCommitmentHousehold 已判；纯函数内状态不变 ⇒ 兜底（不静默写坏状态）。
      throw new Rejection(COMMAND + " 的家户不存在于 economy classes: " + household.value());
    }
    long oldGovServiceLabor = existing == null ? 0L : existing.laborMilli();
    long projectedTotal =
        householdCommittedLabor(base, household) - oldGovServiceLabor + laborMilli;
    if (projectedTotal > householdEconomy.laborMilli()) {
      throw new Rejection(
          COMMAND
              + " 预检失败：写入后家户 "
              + household.value()
              + " 全部承诺之和 "
              + projectedTotal
              + " 超过每 tick 时间预算 "
              + householdEconomy.laborMilli()
              + "（本次 laborMilli="
              + laborMilli
              + "，既有 GOV_SERVICE="
              + oldGovServiceLabor
              + "；GOV_SERVICE 不可缩，请先释放生产承诺或提高预算/人口）");
    }
    PeopleLotId group =
        existing != null ? existing.group() : requireHouseholdLot(social, household);
    long period = existing != null ? existing.period() : DEFAULT_PERIOD;
    HouseholdLaborCommitment row =
        new HouseholdLaborCommitment(
            rowId,
            group,
            household,
            operator,
            unitId.value(),
            laborMilli,
            period,
            LaborCommitmentKind.GOV_SERVICE);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> allocations =
        new LinkedHashMap<>(base.allocations());
    allocations.put(rowId, row); // 既有键 ⇒ put 保留原插入位（保序）
    EconomyData projected = apply(base, allocations);
    return new Projection(
        govUnitId,
        household,
        operator,
        unitId,
        rowId,
        false,
        existing == null,
        laborMilli,
        projected,
        EconomyChangeSet.between(base, projected));
  }

  /** 该家户全部承诺之和（毫小时）；溢出 ⇒ 契约故障（加法用 addExact）。 */
  private static long householdCommittedLabor(EconomyData base, HouseholdId household) {
    long total = 0L;
    for (HouseholdLaborCommitment commitment : base.allocations().values()) {
      if (commitment.household().equals(household)) {
        total = Math.addExact(total, commitment.laborMilli());
      }
    }
    return total;
  }

  /** 唯一写口包装：{@code withLaborCommitments} 的 IAE = 跨表契约被违反（不是载荷形状错）⇒ 折 ISE。 */
  private static EconomyData apply(
      EconomyData base, Map<LaborAllocationId, HouseholdLaborCommitment> allocations) {
    try {
      return base.withLaborCommitments(Collections.unmodifiableMap(allocations));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          COMMAND + " 写出后的 EconomyData 违反跨表契约（一致性故障，非载荷问题）: " + e.getMessage(), e);
    }
  }
}
