package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.spi.AdjustAccountsHandler;
import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.spi.EconomyDefineCurrencyHandler;
import io.mosire.simos.economy.spi.EconomyRecordMoneyIssuanceHandler;
import io.mosire.simos.economy.spi.EconomyRegisterGovernmentHandler;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.economy.spi.EconomySetGovServiceCommitmentHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdLaborHandler;
import io.mosire.simos.economy.spi.EconomyUpsertGovUnitHandler;
import io.mosire.simos.economy.spi.EconomyUpsertIndustryHandler;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.gov.spi.SetAdministrationPlanHandler;
import io.mosire.simos.gov.spi.SetBudgetPolicyHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.CreateHouseholdHandler;
import io.mosire.simos.social.spi.TransferHouseholdMembersHandler;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetGovernmentFormationHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetTaxRateHandler;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>Z5：19 hex 小世界的政府行政链 bootstrap（落点 A —— 扩展 in-code 世界创世）</b>。
 *
 * <p>本类把"中央/省两级 GOV + 官署 office 产业模板 + GOV 行政服务 unit（operator = {@code hh-gov-<govUnitId>}） + 官吏户
 * {@code hh-unit:<govUnitId>} + 内部岗位 + 全程承诺 + 编制计划 + 预算政策 + 国库初始注资"编译成一条<b>固定顺序的真命令链</b>， 由 {@link
 * io.mosire.simos.app.world.SmallWorld#state(String)} 在 {@code actor.Seed} 之后逐条应用。
 *
 * <p>★★ <b>为什么是创世路径（而不是 GM 组合工具）</b>：
 *
 * <ul>
 *   <li><b>空库才生效</b>：调用方是 {@code ShellMain#seedGenesisIfEmpty} ⇒ {@code
 *       CoreSimos#bootstrapGenesis}，后者在库非空时具名拒； 本类只在创世组装期运行，不存在"对既有世界重复 bootstrap"的写面；
 *   <li><b>确定性</b>：全部载荷是常量与 {@code SocialData} 权威值的纯函数；不读墙钟、不用随机量、不取 UUID；
 *   <li><b>不破坏既有守卫</b>：不调 {@code economy.Seed}/不碰 {@code occupiedHexKeys}；{@code
 *       economy.UpsertIndustry} 复用 Z1a "已激活格可追加模板"的口径，{@code
 *       economy.RegisterGovernment/UpsertGovUnit} 等守卫原样生效；
 *   <li><b>Command → ChangeSet → Revision</b>：每条命令都由 {@link SmallWorld} 的同一段 {@code handler →
 *       codec.apply} 语义执行 （与既有创世逐条一致），最终整份状态由 {@code bootstrapGenesis} 落成一条 {@code (main, 1)} 创世
 *       revision。
 * </ul>
 *
 * <p>★★ <b>每个 GOV 的固定命令序</b>（同一 GOV 内顺序不可换；两级按中央 → 省）：
 *
 * <pre>
 * economy.UpsertIndustry（office@<seated hex>，空输出 + TOOL 产能锚）
 * → unit.CreateUnit → social.CreateHousehold(hh-gov-<id>, UNIT) → actor.EnsureHouseholdAccount
 * → unit.SetGovFormation（staff={POST:2}、OfficePolicy 默认）
 * → economy.RegisterGovernment
 * → unit.SetJurisdiction（本级省级辖区：中央=capital-province / 省=small-world）
 * → [省] unit.SetTaxRate(100‰)（中央不发：新 region 从 0 起 ⇒ 初始 0‰，要不要征由中央自定/后续工具改）
 * → economy.UpsertGovUnit（office unit + TOOL 份额 + 空规则关系）
 * → social.CreateHousehold(hh-unit:<id>, UNIT) → social.TransferHouseholdMembers（2 名成年男性）
 * → unit.SetUnitHouseholds（hh-gov + hh-unit）→ economy.RegisterHousehold → actor.EnsureHouseholdAccount
 * → unit.AssignGovPost（POST / tier-3：两维各 50%）
 * → economy.SetHouseholdLabor（= Social 投影劳动）→ economy.SetGovServiceCommitment（GOV_SERVICE 全职）
 * → gov.SetAdministrationPlan（两维计划、默认 3 档、静态修正 1000‰、k=1）
 * → gov.SetBudgetPolicy（默认五类顺序 + 官吏工资规则 粮 10/银 1 毫·承诺小时）
 * …两级之后（A1 追加，顺序不可换）：
 * economy.DefineCurrency（中央 GOV 定义第二币种 copper，scale 3、SPECIE、发行权归中央）
 * → actor.AdjustAccounts（一次给两国库注入初始 粮/布/银 + 给中央国库注入铜）
 * → economy.RecordMoneyIssuance（那笔铜的 INITIAL_ENDOWMENT 审计）
 * </pre>
 *
 * <p>★★ <b>官吏规模口径（部分配员，目标 vs 实际）</b>：每 GOV 恰一个官吏户 {@code hh-unit:<govId>}，2 名成年男性（劳动 = 2 ×
 * 标准劳动定额，取自当前 Social 权威），挂 1 个 {@code POST}/tier-3 岗位 ⇒ 两维供给各 1 × 定额。计划量 = {@code 1×定额（中央）}/{@code
 * 2×定额（省，每维）}：中央满覆盖（1000‰），省半覆盖（500‰、按设计只告警不自动招募）。 满覆盖（如 19 hex 建议需求的 100/139 人当量）留给决策人后续招募 ——
 * 本类不自动扩员。
 *
 * <p>★★ <b>国库注资</b>：{@code actor.AdjustAccounts}（既有裸账原语，GM-only）对两国库做纯正增量；金额见 {@link
 * #TREASURY_GRAIN_MILLI_PER_GOV} 等常量（≈ 2000 天粮 + 1800 天布 + 3000 天银的现行政策需求）。<b>不做 M1 铸币</b>、不接
 * world-silver 铸币收益（按设计书 §13）。
 *
 * <p>★ <b>不做什么</b>：不建决策人（GM/后续显式建）、不建军队/军俸、不自动招募/注资/调计划、不做队列/应募。本类只落"可运行的行政链"最小集。
 */
public final class GovWorldBootstrap {

  /** 中央 GOV 的稳定 id（与 run setup 的 {@code gov-central} 同字面）。 */
  public static final String CENTRAL_GOV_ID = "gov-central";

  /** 省 GOV 的稳定 id（与 run setup 的 {@code gov-province} 同字面）。 */
  public static final String PROVINCE_GOV_ID = "gov-province";

  /** 中央 GOV 座位格（首都；Z7a 后它所在格只属 capital-province）。 */
  public static final HexCoord CENTRAL_AT = new HexCoord(0, 0);

  /** 省 GOV 座位格（run setup 口径：{@code (0,1)}；属 small-world）。 */
  public static final HexCoord PROVINCE_AT = new HexCoord(0, 1);

  /** 每个官吏户配的成年男性人数（2 名 = 全职基层班子的最小可运行规模）。 */
  public static final long OFFICIAL_MEMBERS_PER_GOV = 2L;

  /** 国库初始粮（毫粮 / GOV）：约 2000 余天的现行俸禄+工资需求。 */
  public static final long TREASURY_GRAIN_MILLI_PER_GOV = 1_000_000L;

  /** 国库初始布（毫布 / GOV）：约 1800 天的行政定额需求。 */
  public static final long TREASURY_CLOTH_MILLI_PER_GOV = 100_000L;

  /** 国库初始银（毫银 / GOV）：约 3000 天的官吏工资需求。 */
  public static final long TREASURY_SILVER_MILLI_PER_GOV = 100_000L;

  /**
   * ★★ <b>A1：中央国库的初始铜（毫铜）</b>——第二币种的创世禀赋（{@code INITIAL_ENDOWMENT} 审计，见 {@link
   * #copperEndowmentPayload()}）。
   *
   * <p>★ 量取与银同阶（100,000 毫 = 100 铜）：A 阶段政府要能"持两种货币"（设计书 §3），数额本身不是判据 ——
   * 判据是"国库真的持有一笔铜，且那一笔在发行审计里对得上"。★ <b>只给中央</b>（省 GOV 不发行铜 ⇒ 也不持铜）。
   */
  public static final long TREASURY_COPPER_MILLI_PER_GOV = 100_000L;

  /** 官吏工资规则：每承诺小时粮（毫粮）。与 Z3c-1 探针口径同值。 */
  public static final long SALARY_GRAIN_MILLI_PER_COMMITTED_HOUR = 10L;

  /** 官吏工资规则：每承诺小时银（毫银）。 */
  public static final long SALARY_SILVER_MILLI_PER_COMMITTED_HOUR = 1L;

  /** office 产业模板的周期（天）：30 天一轮的行政服务周期哨兵（服务产出为空，不参与库存/市场）。 */
  public static final long OFFICE_CYCLE_DAYS = 30L;

  /** office 产业模板的 laborPerUnit（千分劳动/单位规模）：只作岗位槽载体，不接生产结算。 */
  public static final long OFFICE_LABOR_PER_UNIT = 1_000L;

  /** office 产业模板的制度键（与 {@code economy.UpsertGovUnit} 的默认 modeKey 同字面，语义自洽）。 */
  public static final String OFFICE_REGIME = "gov_service";

  /** office 产业模板的产能锚（1 件 TOOL / 单位规模）；{@code economy.UpsertGovUnit} 的 assets 必须逐 kind 覆盖它。 */
  public static final long OFFICE_ASSET_PER_UNIT = 1L;

  /** 省对 {@code small-world}（18 格省级辖区）的长期税率（‰；run setup 口径）。中央初始 0‰，不用本常量。 */
  public static final long PROVINCE_TAX_RATE_PER_MILLE = 100L;

  /** office@hex 的 kind 前缀（id 形状 = {@code <kind>@<q>_<r>}；Z1a 版本约定）。 */
  private static final String OFFICE_BASE_KIND = "office";

  /** 官吏户主岗位档位（设计书 §14.2 默认档 3：治安 500‰ + 公文 500‰）。 */
  private static final String OFFICIAL_TIER_ID = GovAdministrationPlan.DEFAULT_TIER_3.tierId();

  private GovWorldBootstrap() {}

  /**
   * 一条命令的执行口：由调用方（{@code SmallWorld}）提供"handler → codec.apply → 新状态"的同一条创世语义。
   *
   * <p>本类不自己碰 {@code CoreSimos}/{@code Codec.apply} 的细节，只按固定顺序把真命令交给它 —— 于是"命令序"与"命令如何落状态"
   * 各自只有一处实现。
   */
  @FunctionalInterface
  public interface CommandApplier {

    /** 应用一条命令，返回新状态；被拒 = 当场抛（创世不继续）。 */
    SimulationState apply(
        SimulationState state,
        String type,
        CommandHandler handler,
        ModuleCodec codec,
        String payloadJson);
  }

  /** 一名官吏的来源批次 + 它当前所属家户（{@code social.TransferHouseholdMembers} 的 {@code from} 需要家户，不是批次）。 */
  public record ManpowerSource(String lotId, String fromHouseholdId) {

    public ManpowerSource {
      requireNonBlank(lotId, "lotId");
      requireNonBlank(fromHouseholdId, "fromHouseholdId");
    }
  }

  /**
   * 把行政链追加到已经完成人口/经济/actor 播种的状态上（见类注的命令序）。
   *
   * @param state 创世候选状态（必须已有 map/social/unit/economy/actor/gov 切片；由 {@code SmallWorld} 保证）
   * @param applier 命令执行口（见 {@link CommandApplier}）
   * @param map 真地图（校验两个座位格与两个省级 Region 存在；不读别的）
   * @param provinceRegionId 省 GOV（gov-province）管辖的省级 Region id（Z7a = small-world，18 格）
   * @param capitalRegionId 中央 GOV（gov-central）直辖的省级 Region id（Z7a = capital-province，含首都座位）
   * @param manpowerSources 两名官吏的来源批次，顺序 = {@code [中央, 省]}
   * @return 追加行政链后的状态；任一步被拒 ⇒ 当场抛（整次创世失败，不落半截世界）
   */
  public static SimulationState apply(
      SimulationState state,
      CommandApplier applier,
      GameMap map,
      String provinceRegionId,
      String capitalRegionId,
      List<ManpowerSource> manpowerSources) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(applier, "applier");
    Objects.requireNonNull(map, "map");
    requireNonBlank(provinceRegionId, "provinceRegionId");
    requireNonBlank(capitalRegionId, "capitalRegionId");
    Objects.requireNonNull(manpowerSources, "manpowerSources");
    List<ManpowerSource> sources = List.copyOf(manpowerSources);
    if (sources.size() != 2) {
      throw new IllegalArgumentException(
          "manpowerSources 必须恰 2 条（顺序 = [中央, 省]），收到 " + sources.size());
    }
    requireSeat(map, CENTRAL_AT, "CENTRAL_AT");
    requireSeat(map, PROVINCE_AT, "PROVINCE_AT");
    Region provinceRegion = map.regions().get(new RegionId(provinceRegionId));
    Region capitalRegion = map.regions().get(new RegionId(capitalRegionId));
    if (provinceRegion == null || capitalRegion == null) {
      throw new IllegalArgumentException(
          "省级 Region 不在当前地图里: provinceRegionId="
              + provinceRegionId
              + ", capitalRegionId="
              + capitalRegionId
              + "（无法给两级 GOV 落管辖/税率）");
    }
    // ★ Z7a 互斥/归属 fail-closed：两个省级辖区必须不重叠、各自含本级 GOV 座位。世界若装配成重叠/座位在外，
    //   省份税会再次抽中央国库或抽空税基——这类世界宁可不 bootstrap，也不静默落盘。
    if (!Collections.disjoint(provinceRegion.hexes(), capitalRegion.hexes())) {
      throw new IllegalStateException(
          "Z7a 要求两级省级辖区互斥，但 " + provinceRegionId + " 与 " + capitalRegionId + " 存在重叠 hex");
    }
    if (!provinceRegion.contains(PROVINCE_AT)) {
      throw new IllegalStateException(
          "省 GOV 座位 " + PROVINCE_AT + " 不在其辖区 " + provinceRegionId + " 内（装配故障）");
    }
    if (!capitalRegion.contains(CENTRAL_AT)) {
      throw new IllegalStateException(
          "中央 GOV 座位 " + CENTRAL_AT + " 不在其辖区 " + capitalRegionId + " 内（装配故障）");
    }

    SocialData social = requireSocial(state);
    long standardLaborMilli = social.provisioning().standardLaborMilliHoursPerTick();
    if (standardLaborMilli <= 0L) {
      throw new IllegalStateException("Social 标准劳动定额必须为正（C8 权威），收到 " + standardLaborMilli);
    }
    long officialLaborMilli = Math.multiplyExact(OFFICIAL_MEMBERS_PER_GOV, standardLaborMilli);
    long personEquivalents = Math.floorDiv(officialLaborMilli, standardLaborMilli);

    SimulationState next = state;
    next =
        bootstrapGov(
            next,
            applier,
            new GovSpec(
                CENTRAL_GOV_ID,
                "小世界中央政府",
                CENTRAL_AT,
                GovernmentLevel.CENTRAL,
                Optional.empty(),
                capitalRegionId,
                standardLaborMilli,
                standardLaborMilli,
                officialLaborMilli,
                personEquivalents,
                sources.get(0)));
    next =
        bootstrapGov(
            next,
            applier,
            new GovSpec(
                PROVINCE_GOV_ID,
                "小世界省政府",
                PROVINCE_AT,
                GovernmentLevel.PROVINCE,
                Optional.of(CENTRAL_GOV_ID),
                provinceRegionId,
                Math.multiplyExact(standardLaborMilli, 2L),
                Math.multiplyExact(standardLaborMilli, 2L),
                officialLaborMilli,
                personEquivalents,
                sources.get(1)));
    // ★★ A1（2026-10-08 约束设计书 §3.1-2/§3.1-3）：**第二币种 copper 的创世注入**——顺序不可换：
    //   ① economy.DefineCurrency（币种进词表 + 工具进工具表 + 中央 GOV 成为它的发行人）；
    //   ② actor.AdjustAccounts（国库余额 +copper，与粮/布/银同一笔注资）；
    //   ③ economy.RecordMoneyIssuance（这一笔记资的 INITIAL_ENDOWMENT 审计，量 = ②的铜量）。
    //   ★ 顺序不可换的理由：①要求政府已登记（两个 bootstrapGov 已完成）②③的可审计性都建立在"币种已定义、发行人已登记"上。
    //   ★ 这不是铸币生产方式（用户 §1.1「铸币暂缓」仍有效）：它是一次性的创世禀赋 + 审计，不走产业产出。
    next =
        applier.apply(
            next,
            EconomyDefineCurrencyHandler.TYPE,
            new EconomyDefineCurrencyHandler(),
            new EconomyCodec(),
            defineCopperPayload());
    next =
        applier.apply(
            next,
            ActorAdjustAccountsTool.NAME,
            new AdjustAccountsHandler(),
            new ActorCodec(),
            treasuryInjectionPayload());
    return applier.apply(
        next,
        EconomyRecordMoneyIssuanceHandler.TYPE,
        new EconomyRecordMoneyIssuanceHandler(),
        new EconomyCodec(),
        copperEndowmentPayload());
  }

  /**
   * {@code economy.DefineCurrency} 的载荷：小世界第二币种 = 铜（scale/显示名/币种 id 都取 {@code MoneyVocabulary}
   * 的拼写点）。
   */
  private static String defineCopperPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", CENTRAL_GOV_ID);
    payload.put("currencyId", MoneyVocabulary.COPPER_CURRENCY_ID);
    payload.put("scale", MoneyVocabulary.COPPER_SCALE);
    payload.put("displayName", MoneyVocabulary.COPPER_DISPLAY_NAME);
    payload.put("reason", "small-world 创世：中央 GOV 定义第二币种（A1；非铸币生产方式）");
    return ToolSupport.json(payload);
  }

  /** {@code economy.RecordMoneyIssuance} 的载荷：中央国库那笔铜的 {@code INITIAL_ENDOWMENT} 审计。 */
  private static String copperEndowmentPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", CENTRAL_GOV_ID);
    payload.put("currency", MoneyVocabulary.COPPER_CURRENCY_ID);
    payload.put("amountMilli", TREASURY_COPPER_MILLI_PER_GOV);
    payload.put("kind", "INITIAL_ENDOWMENT");
    payload.put("reason", "small-world 创世：中央国库第二币种初始禀赋（A1 INITIAL_ENDOWMENT）");
    return ToolSupport.json(payload);
  }

  /** 一个 GOV 的 bootstrap 事实（全部由调用方一次算清，链内不重算、不发明）。 */
  private record GovSpec(
      String govId,
      String name,
      HexCoord at,
      GovernmentLevel level,
      Optional<String> superiorGov,
      String jurisdictionRegionId,
      long securityPlannedLaborMilli,
      long paperworkPlannedLaborMilli,
      long officialLaborMilli,
      long personEquivalents,
      ManpowerSource manpowerSource) {}

  /** 单个 GOV 的固定命令序（见类注；顺序不可换）。 */
  private static SimulationState bootstrapGov(
      SimulationState state, CommandApplier applier, GovSpec spec) {
    String reason = "small-world 创世：建 " + spec.govId() + " 行政链";
    String officeIndustryId = officeIndustryId(spec.at());
    String governmentHousehold = GovernmentHouseholds.of(spec.govId()).value();
    String officialHousehold = RaiseUnitPlan.householdIdFor(spec.govId());

    SimulationState next = state;
    next =
        applier.apply(
            next,
            EconomyUpsertIndustryHandler.TYPE,
            new EconomyUpsertIndustryHandler(),
            new EconomyCodec(),
            officeIndustryPayload(officeIndustryId, spec));
    next =
        applier.apply(
            next,
            CreateUnitHandler.TYPE,
            new CreateUnitHandler(),
            new UnitCodec(),
            createUnitPayload(spec));
    next =
        applier.apply(
            next,
            CreateHouseholdHandler.TYPE,
            new CreateHouseholdHandler(),
            new SocialCodec(),
            createHouseholdPayload(
                governmentHousehold, spec.govId(), spec.name() + "政府家户", reason));
    next =
        applier.apply(
            next,
            EnsureHouseholdAccountHandler.TYPE,
            new EnsureHouseholdAccountHandler(),
            new ActorCodec(),
            ensureAccountPayload(governmentHousehold, reason));
    next =
        applier.apply(
            next,
            GovCreateOfficePlan.SET_GOV_FORMATION_TYPE,
            new SetGovernmentFormationHandler(),
            new UnitCodec(),
            setGovFormationPayload(spec));
    next =
        applier.apply(
            next,
            EconomyRegisterGovernmentHandler.TYPE,
            new EconomyRegisterGovernmentHandler(),
            new EconomyCodec(),
            registerGovernmentPayload(spec, reason));
    // ★ Z7a：两级 GOV 都落本级省级辖区（中央 capital-province / 省 small-world），二者互斥。
    //   中央不发 SetTaxRate：SetJurisdiction 对本 GOV 的新 region 从 0 起 ⇒ 初始税率 0‰（设计书 §2/§9）；
    //   要不要征税由中央自定，后续走既有 simos.unit.setTaxRate（命令形状不变）。
    next =
        applier.apply(
            next,
            GovCreateOfficePlan.SET_JURISDICTION_TYPE,
            new SetJurisdictionHandler(),
            new UnitCodec(),
            setJurisdictionPayload(spec));
    if (spec.level() == GovernmentLevel.PROVINCE) {
      next =
          applier.apply(
              next,
              UnitSetTaxRateTool.NAME,
              new SetTaxRateHandler(),
              new UnitCodec(),
              setTaxRatePayload(spec));
    }
    next =
        applier.apply(
            next,
            EconomyUpsertGovUnitHandler.TYPE,
            new EconomyUpsertGovUnitHandler(),
            new EconomyCodec(),
            upsertGovUnitPayload(spec, officeIndustryId, reason));
    next =
        applier.apply(
            next,
            CreateHouseholdHandler.TYPE,
            new CreateHouseholdHandler(),
            new SocialCodec(),
            createHouseholdPayload(officialHousehold, spec.govId(), "官吏户:" + spec.govId(), reason));
    next =
        applier.apply(
            next,
            TransferHouseholdMembersHandler.TYPE,
            new TransferHouseholdMembersHandler(),
            new SocialCodec(),
            transferMembersPayload(spec, officialHousehold, reason));

    // ★ 承诺劳动的唯一权威 = 转移后的 Social 投影（与 GovExpandHouseholdTool 同口径）；与
    //   "2 × 标准定额"的预期不一致 ⇒ 创世自检失败（不把漂移写进世界）。
    SocialData afterTransfer = requireSocial(next);
    long projectedLaborMilli =
        afterTransfer.householdLaborMilli(
            HouseholdId.parse(officialHousehold),
            next.meta().timestamp().tick(),
            CalendarClock.julianDefault());
    if (projectedLaborMilli != spec.officialLaborMilli()) {
      throw new IllegalStateException(
          "官吏户 "
              + officialHousehold
              + " 的 Social 投影劳动 "
              + projectedLaborMilli
              + " 与预期 "
              + spec.officialLaborMilli()
              + " 不一致（来源批次必须恰为 2 名成年男性；拒绝静默）");
    }

    next =
        applier.apply(
            next,
            SetUnitHouseholdsHandler.TYPE,
            new SetUnitHouseholdsHandler(),
            new UnitCodec(),
            setUnitHouseholdsPayload(spec, governmentHousehold, officialHousehold, reason));
    next =
        applier.apply(
            next,
            EconomyRegisterHouseholdHandler.TYPE,
            new EconomyRegisterHouseholdHandler(),
            new EconomyCodec(),
            registerHouseholdPayload(spec, officialHousehold, afterTransfer, reason));
    next =
        applier.apply(
            next,
            EnsureHouseholdAccountHandler.TYPE,
            new EnsureHouseholdAccountHandler(),
            new ActorCodec(),
            ensureAccountPayload(officialHousehold, reason));
    next =
        applier.apply(
            next,
            AssignGovPostHandler.TYPE,
            new AssignGovPostHandler(),
            new UnitCodec(),
            assignGovPostPayload(spec, officialHousehold));
    next =
        applier.apply(
            next,
            EconomySetHouseholdLaborHandler.TYPE,
            new EconomySetHouseholdLaborHandler(),
            new EconomyCodec(),
            setHouseholdLaborPayload(officialHousehold, projectedLaborMilli, reason));
    next =
        applier.apply(
            next,
            EconomySetGovServiceCommitmentHandler.TYPE,
            new EconomySetGovServiceCommitmentHandler(),
            new EconomyCodec(),
            setGovServiceCommitmentPayload(spec, officialHousehold, projectedLaborMilli, reason));
    next =
        applier.apply(
            next,
            SetAdministrationPlanHandler.TYPE,
            new SetAdministrationPlanHandler(),
            new GovCodec(),
            administrationPlanPayload(spec));
    next =
        applier.apply(
            next,
            SetBudgetPolicyHandler.TYPE,
            new SetBudgetPolicyHandler(),
            new GovCodec(),
            budgetPolicyPayload(spec.govId()));
    return next;
  }

  // ── 载荷（唯一构造点；形状以各 handler 的注为准，字段名与解析器逐字对应） ─────────────────────

  private static String officeIndustryPayload(String industryId, GovSpec spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", industryId);
    payload.put("name", spec.name() + "官署");
    payload.put("regime", OFFICE_REGIME);
    payload.put("cycleDays", OFFICE_CYCLE_DAYS);
    payload.put("capacityPerUnit", Map.of(AssetKind.TOOL.name(), OFFICE_ASSET_PER_UNIT));
    payload.put("dailyInputPerUnit", new LinkedHashMap<>());
    payload.put("dailyLaborPerUnit", 0L);
    payload.put("laborPerUnit", OFFICE_LABOR_PER_UNIT);
    payload.put("outputPerUnit", new LinkedHashMap<>());
    payload.put("cycleInputPerUnit", new LinkedHashMap<>());
    List<Map<String, Object>> slots = new ArrayList<>(1);
    Map<String, Object> slot = new LinkedHashMap<>();
    slot.put("id", SocialClassId.OFFICIAL.value());
    slot.put("name", "官吏");
    slot.put("laborParticipationPerMille", 1000);
    slots.add(slot);
    payload.put("slots", slots);
    Map<String, Object> allocation = new LinkedHashMap<>();
    allocation.put("@class", "split");
    allocation.put("meansWeightPerMille", 500);
    allocation.put("laborWeightPerMille", 500);
    payload.put("allocation", allocation);
    return ToolSupport.json(payload);
  }

  private static String createUnitPayload(GovSpec spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", spec.govId());
    payload.put("name", spec.name());
    payload.put("position", ToolSupport.hexCoord(spec.at()));
    payload.put("manpower", List.of());
    payload.put("equipment", List.of());
    payload.put("speed", 1);
    payload.put("mobilityPerMille", 500);
    return ToolSupport.json(payload);
  }

  private static String createHouseholdPayload(
      String householdId, String unitId, String name, String reason) {
    Map<String, Object> location = new LinkedHashMap<>();
    location.put("type", "UNIT");
    location.put("unitId", unitId);
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("name", name);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("householdId", householdId);
    payload.put("location", location);
    payload.put("profile", profile);
    payload.put("vitalRates", List.of());
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String ensureAccountPayload(String householdId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", householdId);
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String setGovFormationPayload(GovSpec spec) {
    OfficePolicy policy = OfficePolicy.defaults();
    Map<String, Object> staff = new LinkedHashMap<>();
    staff.put(StaffRole.POST.name(), spec.personEquivalents());
    Map<String, Object> policyView = new LinkedHashMap<>();
    policyView.put("grainPerStaffPerTick", policy.grainPerStaffPerTick());
    policyView.put("clothPerStaffPerCycle", policy.clothPerStaffPerCycle());
    policyView.put("moneyPerStaffPerTick", policy.moneyPerStaffPerTick());
    policyView.put("retirementPerStaff", policy.retirementPerStaff());
    policyView.put("staffCap", new LinkedHashMap<>());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("level", spec.level().name());
    spec.superiorGov().ifPresent(superior -> payload.put("superiorGov", superior));
    payload.put("staff", staff);
    payload.put("policy", policyView);
    return ToolSupport.json(payload);
  }

  private static String registerGovernmentPayload(GovSpec spec, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", spec.govId());
    payload.put("nationRef", spec.superiorGov().orElse(spec.govId()));
    payload.put("q", spec.at().q());
    payload.put("r", spec.at().r());
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String setJurisdictionPayload(GovSpec spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("regions", List.of(spec.jurisdictionRegionId()));
    return ToolSupport.json(payload);
  }

  private static String setTaxRatePayload(GovSpec spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("regionId", spec.jurisdictionRegionId());
    payload.put("ratePerMille", PROVINCE_TAX_RATE_PER_MILLE);
    return ToolSupport.json(payload);
  }

  private static String upsertGovUnitPayload(GovSpec spec, String officeIndustryId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", spec.govId());
    payload.put("industryId", officeIndustryId);
    payload.put("assets", Map.of(AssetKind.TOOL.name(), OFFICE_ASSET_PER_UNIT));
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String transferMembersPayload(
      GovSpec spec, String officialHousehold, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("from", spec.manpowerSource().fromHouseholdId());
    payload.put("to", officialHousehold);
    payload.put("lotId", spec.manpowerSource().lotId());
    payload.put("count", OFFICIAL_MEMBERS_PER_GOV);
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String setUnitHouseholdsPayload(
      GovSpec spec, String governmentHousehold, String officialHousehold, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("households", List.of(governmentHousehold, officialHousehold));
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String registerHouseholdPayload(
      GovSpec spec, String officialHousehold, SocialData social, String reason) {
    ResidenceKind residence =
        social.cities().values().stream().anyMatch(city -> city.at().equals(spec.at()))
            ? ResidenceKind.URBAN
            : ResidenceKind.RURAL;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", officialHousehold);
    payload.put("q", spec.at().q());
    payload.put("r", spec.at().r());
    payload.put("residence", residence.value());
    payload.put("stratum", SocialClassId.OFFICIAL.value());
    payload.put("participationPerMille", 1000);
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String assignGovPostPayload(GovSpec spec, String officialHousehold) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("household", officialHousehold);
    payload.put("role", StaffRole.POST.name());
    payload.put("tierId", OFFICIAL_TIER_ID);
    payload.put("level", spec.level().name());
    payload.put("head", false);
    return ToolSupport.json(payload);
  }

  private static String setHouseholdLaborPayload(
      String officialHousehold, long laborMilli, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", officialHousehold);
    payload.put("laborMilli", laborMilli);
    payload.put("participationPerMille", 1000);
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String setGovServiceCommitmentPayload(
      GovSpec spec, String officialHousehold, long laborMilli, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", spec.govId());
    payload.put("householdId", officialHousehold);
    payload.put("laborMilli", laborMilli);
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String administrationPlanPayload(GovSpec spec) {
    List<Map<String, Object>> tiers =
        new ArrayList<>(GovAdministrationPlan.DEFAULT_POST_TIERS.size());
    for (GovPostTier tier : GovAdministrationPlan.DEFAULT_POST_TIERS) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tierId", tier.tierId());
      row.put("securityWeightPerMille", tier.securityWeightPerMille());
      row.put("paperworkWeightPerMille", tier.paperworkWeightPerMille());
      tiers.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("securityPlannedLaborMilli", spec.securityPlannedLaborMilli());
    payload.put("paperworkPlannedLaborMilli", spec.paperworkPlannedLaborMilli());
    payload.put("postTiers", tiers);
    payload.put(
        "securitySupplyStaticModifierPerMille", GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    payload.put(
        "paperworkSupplyStaticModifierPerMille", GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    payload.put(
        "securityDemandStaticModifierPerMille", GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    payload.put(
        "paperworkDemandStaticModifierPerMille", GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE);
    payload.put(
        "supernumerarySqrtCoefficient",
        GovAdministrationPlan.DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
    return ToolSupport.json(payload);
  }

  private static String budgetPolicyPayload(String govId) {
    List<Map<String, Object>> categories = new ArrayList<>(GovBudgetCategory.values().length);
    for (GovBudgetCategory category : GovBudgetCategory.values()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("category", category.name());
      line.put("minPerCycle", 0L);
      line.put("capPerCycle", Long.MAX_VALUE);
      categories.add(line);
    }
    Map<String, Object> salaryRule = new LinkedHashMap<>();
    salaryRule.put("grainMilliPerCommittedHour", SALARY_GRAIN_MILLI_PER_COMMITTED_HOUR);
    salaryRule.put("silverMilliPerCommittedHour", SALARY_SILVER_MILLI_PER_COMMITTED_HOUR);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", govId);
    payload.put("orderedCategories", categories);
    payload.put("officialSalaryRule", salaryRule);
    // ★ Z7c：创世显式冻结 remittance 默认 0‰（省可改可抗税；中央不能自动强制）。
    payload.put("remittancePerMilleToSuperior", 0L);
    return ToolSupport.json(payload);
  }

  /**
   * 一次给两国库注入初始 粮/布/银（`actor.AdjustAccounts` 的纯正增量；缺账由该命令建账）。
   *
   * <p>★★ <b>A1 追加</b>：<b>中央</b>国库再多一笔<b>铜</b>（第二币种）—— 它与同批的 {@code
   * economy.RecordMoneyIssuance}（{@code INITIAL_ENDOWMENT}）成对：余额腿在这里，审计腿在那条命令。 省 GOV 不发行铜（它不是
   * copper 的发行人）⇒ 不给它注铜。
   */
  private static String treasuryInjectionPayload() {
    List<Map<String, Object>> entries = new ArrayList<>(2);
    entries.add(treasuryEntry(CENTRAL_GOV_ID, TREASURY_COPPER_MILLI_PER_GOV));
    entries.add(treasuryEntry(PROVINCE_GOV_ID, 0L));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }

  private static Map<String, Object> treasuryEntry(String govId, long copperMilli) {
    Map<String, Object> goods = new LinkedHashMap<>();
    goods.put(EconomyCommodities.GRAIN.value(), TREASURY_GRAIN_MILLI_PER_GOV);
    goods.put(EconomyCommodities.CLOTH.value(), TREASURY_CLOTH_MILLI_PER_GOV);
    Map<String, Object> money = new LinkedHashMap<>();
    money.put(MoneyVocabulary.SILVER_CURRENCY.value(), TREASURY_SILVER_MILLI_PER_GOV);
    if (copperMilli > 0L) {
      money.put(MoneyVocabulary.COPPER_CURRENCY.value(), copperMilli);
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("household", GovernmentHouseholds.of(govId).value());
    entry.put("goods", goods);
    entry.put("money", money);
    return entry;
  }

  // ── 小件 ─────────────────────────────────────────────────────────────────────────────────

  private static String officeIndustryId(HexCoord at) {
    return OFFICE_BASE_KIND + "@" + at.q() + "_" + at.r();
  }

  private static SocialData requireSocial(SimulationState state) {
    return state
        .module("social")
        .filter(SocialSnapshot.class::isInstance)
        .map(SocialSnapshot.class::cast)
        .map(SocialSnapshot::data)
        .orElseThrow(() -> new IllegalStateException("GovWorldBootstrap 需要 social 切片（创世装配故障）"));
  }

  private static void requireSeat(GameMap map, HexCoord at, String field) {
    if (!map.hexes().containsKey(at)) {
      throw new IllegalArgumentException(field + " 指向的格不在当前地图里: " + at);
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不得为空白");
    }
  }
}
