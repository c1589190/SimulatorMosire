package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.spi.AdjustAccountsHandler;
import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.CurrencyDef;
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
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetGovernmentFormationHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>B1：N 个 GOV 的行政链 bootstrap（{@code three-powers} 世界的三个政府）</b>。
 *
 * <p>★★ <b>它与 {@code GovWorldBootstrap} 的关系（如实记，不是重复造轮子）</b>：{@code GovWorldBootstrap} 把"中央 + 省"两级
 * <b>写死</b>成两个 GOV（id / 座位格 / 辖区 / 名额 / 第二币种全是它的类常量），本批要的世界是 <b>3 个 GOV、各有自己的辖区与法定币</b>； 而它住在
 * {@code app.tools.write}（不在 B1 的文件所有权内），其内部 {@code GovSpec}/{@code bootstrapGov} 与 {@code
 * SET_GOV_FORMATION_TYPE} 等<b>都是包内可见</b> ⇒ 跨包既不能改也不能调。故本类把**同一条命令序**（逐条与它一致） 泛化成"逐 GOV 一份
 * spec"，并只使用<b>各 handler 自己公开的 {@code TYPE}</b>。★ 两处命令序的一致性由 {@code GovWorldBootstrap} 的类注与本文的
 * {@link #bootstrapGov} 双语对照保证；改动任一处时另一处必须同时改。
 *
 * <p>★★ <b>每个 GOV 的固定命令序（同一 GOV 内顺序不可换；与 {@code GovWorldBootstrap} 逐条一致）</b>：
 *
 * <pre>
 * economy.UpsertIndustry（office@<座位格>，空输出 + TOOL 产能锚）
 * → unit.CreateUnit → social.CreateHousehold(hh-gov-<id>, UNIT) → actor.EnsureHouseholdAccount
 * → unit.SetGovFormation（staff={POST:2}、OfficePolicy 默认）
 * → economy.RegisterGovernment（{@code issuable} 只在"既有币种"时显式声明，见 {@link GovSpec#declaredIssuable()}）
 * → [本 GOV 发行新币时] economy.DefineCurrency（币种进词表 + <币>-specie 进工具表 + 本 GOV 成为发行人）
 * → unit.SetJurisdiction（本级辖区：本 GOV 那一个行政区）
 * → economy.UpsertGovUnit（office unit + TOOL 份额 + 空规则关系）
 * → social.CreateHousehold(hh-unit:<id>, UNIT) → social.TransferHouseholdMembers（2 名成年男性）
 * → unit.SetUnitHouseholds（hh-gov + hh-unit）→ economy.RegisterHousehold → actor.EnsureHouseholdAccount
 * → unit.AssignGovPost（POST / tier-3：两维各 50%）→ economy.SetHouseholdLabor（= Social 投影劳动）
 * → economy.SetGovServiceCommitment（GOV_SERVICE 全职）
 * → gov.SetAdministrationPlan（两维计划、默认 3 档、静态修正 1000‰、k=1）
 * → gov.SetBudgetPolicy（默认五类顺序 + 官吏工资规则 粮 10/银 1 毫·承诺小时）
 * …全部 GOV 之后（顺序不可换）：
 * ★ economy.DefineMarketZone × N（B3：逐 GOV 一区 —— 见下"落点"）
 * → actor.AdjustAccounts（逐 GOV 国库注入 粮/布/银 + 本区法定币）
 * → economy.RecordMoneyIssuance（逐 GOV × 逐币种一条 INITIAL_ENDOWMENT 审计）
 * </pre>
 *
 * <p>★★ <b>B3（2026-10-08 阶段 2-B 第三批）：持久市场区的落点与理由</b>。三个行政区的市场区由 {@code
 * ThreePowersMarketZones#define} 在<b>全部 GOV 的 per-GOV 命令序之后、{@code actor.AdjustAccounts} 之前</b>落成
 * （见 {@link #apply}）。依赖逐条回代码核过（file:line 见 B3 账本 §1）：{@code economy.DefineMarketZone} 要求 <b>币种已在词表
 * + 政府已注册 + 该政府的 {@code issuable} 含该币 + 锚格有市场行 + 成员格计价币 = 法定币</b> ⇒ 硬依赖是"最后一个 GOV 的 {@code
 * economy.DefineCurrency} 之后"；{@code unit.SetJurisdiction} <b>不被该 handler 读</b> （区成员格取自 {@code
 * map.regions()}），但"区 = 辖区"在语义上成对，且任务书要求落在所有 SetJurisdiction 之后 ⇒ 取整个 per-GOV 循环之后。★ 放在 {@code
 * AdjustAccounts} 之前的好处：区表自检失败时<b>铸币/发行审计都还没发生</b>，创世整次失败 （不落半截世界）。
 *
 * <p>★★ <b>创世期的 I23（行政区互斥）自检</b>（{@link #apply} 入口）：三个辖区两两不相交、每级 GOV 的座位落在自己的辖区内 ——
 * 违反则<b>整次创世具名抛</b>（不落半截世界）。★ 这是必要的第二道：创世路径**绕过命令总线** （见 {@code CoreSimos#bootstrapGenesis}），组合根的
 * {@code GovJurisdictionGuard} 在创世期不会被调到。
 *
 * <p>★ <b>不做</b>：不建决策人、不建军队、不自动招募、不设税率（{@code unit.SetTaxRate} 本批不发：新地区从 0‰ 起， 要不要征是 GM
 * 的判断；且现行税收面是银本位的，见 B1 账本的边界一节）。
 */
public final class ThreePowersGovBootstrap {

  /** 官吏户 id 前缀（形状与 {@code RaiseUnitPlan.householdIdFor} 逐字一致；后者包内可见，跨包调不到）。 */
  public static final String OFFICIAL_HOUSEHOLD_PREFIX = "hh-unit:";

  /** 每个官吏户配的成年男性人数（2 名 = 全职基层班子的最小可运行规模；与 {@code GovWorldBootstrap} 同值）。 */
  public static final long OFFICIAL_MEMBERS_PER_GOV = 2L;

  /** office 产业模板的周期（天）：30 天一轮的行政服务周期哨兵（服务产出为空，不参与库存/市场）。 */
  public static final long OFFICE_CYCLE_DAYS = 30L;

  /** office 产业模板的 laborPerUnit（千分劳动/单位规模）：只作岗位槽载体，不接生产结算。 */
  public static final long OFFICE_LABOR_PER_UNIT = 1_000L;

  /** office 产业模板的制度键（与 {@code economy.UpsertGovUnit} 的默认 modeKey 同字面，语义自洽）。 */
  public static final String OFFICE_REGIME = "gov_service";

  /** office 产业模板的产能锚（1 件 TOOL / 单位规模）；{@code economy.UpsertGovUnit} 的 assets 必须逐 kind 覆盖它。 */
  public static final long OFFICE_ASSET_PER_UNIT = 1L;

  /** 国库初始粮（毫粮 / GOV）：与 {@code GovWorldBootstrap} 同值（≈ 2000 余天的现行俸禄+工资需求）。 */
  public static final long TREASURY_GRAIN_MILLI_PER_GOV = 1_000_000L;

  /** 国库初始布（毫布 / GOV）：与 {@code GovWorldBootstrap} 同值（≈ 1800 天的行政定额需求）。 */
  public static final long TREASURY_CLOTH_MILLI_PER_GOV = 100_000L;

  /**
   * 国库的**银**工作余额（毫银 / GOV）：现行行政结算（{@code GovBudgetExecutionBridge.BOOK_CURRENCY} = 银）与官吏工资规则
   * （{@code officialSalaryRule.silverMilliPerCommittedHour}）都是银本位的 ⇒ 每个 GOV 的国库都要有银，否则它在既有口径下
   * 什么都付不出（这不是本批要造的"多币种财政"，只是让现行结算面照常运转）。
   */
  public static final long TREASURY_SILVER_MILLI_PER_GOV = 100_000L;

  /** 官吏工资规则：每承诺小时粮（毫粮）；与 {@code GovWorldBootstrap} 同值。 */
  public static final long SALARY_GRAIN_MILLI_PER_COMMITTED_HOUR = 10L;

  /** 官吏工资规则：每承诺小时银（毫银）；与 {@code GovWorldBootstrap} 同值。 */
  public static final long SALARY_SILVER_MILLI_PER_COMMITTED_HOUR = 1L;

  /** 官吏户主岗位档位（设计书 §14.2 默认档 3：治安 500‰ + 公文 500‰）。 */
  private static final String OFFICIAL_TIER_ID = GovAdministrationPlan.DEFAULT_TIER_3.tierId();

  /** office@hex 的 kind 前缀（id 形状 = {@code <kind>@<q>_<r>}；Z1a 版本约定）。 */
  private static final String OFFICE_BASE_KIND = "office";

  /** 本类自己用的命令类型字面量：{@code unit.SetGovFormation} 的 {@code TYPE} 是包内可见的（见类注），故在此拼一次。 */
  private static final String SET_GOV_FORMATION_TYPE = "unit.SetGovFormation";

  /** 同上：{@code unit.SetJurisdiction} 的 {@code TYPE} 也是包内可见的。 */
  private static final String SET_JURISDICTION_TYPE = "unit.SetJurisdiction";

  private ThreePowersGovBootstrap() {}

  /**
   * 一条命令的执行口：由调用方（世界生成器）提供"handler → codec.apply → 新状态"的同一条**创世语义** （{@code Command → ChangeSet →
   * Revision} 的语义不走样；见 {@code SmallWorld} 的类注）。
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
   * 一个 GOV 在创世期要**新定义**的币种（本 GOV 成为它的发行人）。
   *
   * <p>★ <b>国库禀赋不在这里</b>：它在 {@link GovSpec#treasuryMoney()}（余额腿与审计腿都从那一张表派生， 一张表一个真相 ——
   * 两处各写一份必然漂开）。
   *
   * @param currencyId 币种 id（不得与既有词表重复）
   * @param scale 最小单位精度
   * @param displayName 显示名（非空）
   */
  public record NewCurrency(String currencyId, int scale, String displayName) {

    public NewCurrency {
      requireNonBlank(currencyId, "currencyId");
      requireNonBlank(displayName, "displayName");
      if (scale < 0) {
        throw new IllegalArgumentException("scale 不得为负: " + scale);
      }
    }

    /** 币种定义（与 {@code economy.DefineCurrency} 的载荷同源）。 */
    public CurrencyDef def() {
      return new CurrencyDef(currencyId, scale, displayName);
    }
  }

  /**
   * 一个 GOV 的创世事实（全部由调用方一次算清，链内不重算、不发明）。
   *
   * @param govId GOV 单位稳定 id
   * @param name 显示名
   * @param seat 座位格（必须在 {@code map} 里、且是本辖区的一格）
   * @param level 层级（CENTRAL / PROVINCE）
   * @param superiorGov 上级 GOV（中央为空；给了就必须已建好 ⇒ 调用方按层级序排 specs）
   * @param jurisdictionRegionId 本级辖区（map 里的行政区 id；3 个 GOV 的辖区必须两两不相交）
   * @param treasuryMoney 本国库的初始货币（逐币种毫；币种必须已定义 —— 银或本批某 spec 的 {@link NewCurrency}）
   * @param declaredIssuable 本 GOV 在 {@code economy.RegisterGovernment} 时就声明为**可发行**的币种（通常为空：新币在
   *     {@link NewCurrency} 的 {@code economy.DefineCurrency} 里自动成为本 GOV 的发行币）。★ 它存在的唯一理由是**既有币种**：
   *     {@code silver} 在词表里早已定义 ⇒ {@code economy.DefineCurrency} 会以 {@code
   *     currency-already-defined} 拒它， 于是"中央 GOV 是银的发行政府"这件事只能在这里声明；不声明，该 GOV 的银国库禀赋就**发不出发行审计**
   *     （{@code economy.RecordMoneyIssuance} 会以 {@code currency-not-issuable} 具名拒 —— 本批实测踩到的那条）。
   * @param newCurrency 本 GOV 在创世期新定义的币种（无 ⇒ 空）
   * @param manpowerSource 官吏来源（2 名成年男性）
   * @param marketZoneId ★ B3：本 GOV 辖区对应的<b>市场区 id</b>（= 本区城市 id，`ThreePowersWorld#ZONE_IDS` 的一个）；
   *     持久区的成员格 = 本辖区全部格、锚格 = {@code seat}、法定币 = 本 GOV 发行的币（见 {@code ThreePowersMarketZones}）。★
   *     放在参数表<b>末尾</b>：位置参数调用者不会被静默错位（少给一个 = 编译错，不是"两个 String 相互顶替"）
   */
  public record GovSpec(
      String govId,
      String name,
      HexCoord seat,
      GovernmentLevel level,
      Optional<String> superiorGov,
      String jurisdictionRegionId,
      Map<CurrencyId, Long> treasuryMoney,
      Set<CurrencyId> declaredIssuable,
      Optional<NewCurrency> newCurrency,
      ManpowerSource manpowerSource,
      String marketZoneId) {

    public GovSpec {
      requireNonBlank(govId, "govId");
      requireNonBlank(name, "name");
      Objects.requireNonNull(seat, "seat");
      Objects.requireNonNull(level, "level");
      superiorGov = Objects.requireNonNull(superiorGov, "superiorGov");
      requireNonBlank(jurisdictionRegionId, "jurisdictionRegionId");
      treasuryMoney =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(treasuryMoney, "treasuryMoney")));
      for (Map.Entry<CurrencyId, Long> entry : treasuryMoney.entrySet()) {
        Objects.requireNonNull(entry.getKey(), "treasuryMoney 的键不得为 null");
        Objects.requireNonNull(entry.getValue(), "treasuryMoney 的值不得为 null");
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException("treasuryMoney 不得为负: " + entry);
        }
      }
      declaredIssuable =
          Collections.unmodifiableSet(
              new LinkedHashSet<>(Objects.requireNonNull(declaredIssuable, "declaredIssuable")));
      for (CurrencyId currency : declaredIssuable) {
        Objects.requireNonNull(currency, "declaredIssuable 不得含 null");
      }
      newCurrency = Objects.requireNonNull(newCurrency, "newCurrency");
      Objects.requireNonNull(manpowerSource, "manpowerSource");
      requireNonBlank(marketZoneId, "marketZoneId");
      if (newCurrency.isPresent()
          && declaredIssuable.contains(new CurrencyId(newCurrency.get().currencyId()))) {
        throw new IllegalArgumentException(
            "币种 "
                + newCurrency.get().currencyId()
                + " 既在 declaredIssuable 里、又由本 GOV 新定义（两处声明同一件事）: "
                + govId);
      }
      if (level == GovernmentLevel.CENTRAL && superiorGov.isPresent()) {
        throw new IllegalArgumentException("中央 GOV 不得有上级: " + govId + " → " + superiorGov.get());
      }
      if (level == GovernmentLevel.PROVINCE && superiorGov.isEmpty()) {
        throw new IllegalArgumentException("省 GOV 必须指上级（层级链的唯一表达）: " + govId);
      }
    }

    /** 官吏户 id（{@code hh-unit:<govId>}）。 */
    public String officialHouseholdId() {
      return OFFICIAL_HOUSEHOLD_PREFIX + govId;
    }

    /** 政府家户 id（{@code hh-gov-<govId>}；唯一拼写点在 {@code GovernmentHouseholds}）。 */
    public HouseholdId governmentHouseholdId() {
      return GovernmentHouseholds.of(govId);
    }
  }

  /**
   * 把 N 个 GOV 的行政链追加到已完成人口/经济/actor 播种的状态上（命令序见类注）。
   *
   * @param state 创世候选状态（必须已有 map/social/unit/economy/actor/gov 切片；由世界生成器保证）
   * @param applier 命令执行口
   * @param map 真地图（校验座位与辖区；不读别的）
   * @param specs GOV 事实表（<b>顺序即创建序</b>：上级必须先于下级）
   * @return 追加行政链后的状态；任一步被拒 / 任一自检不过 ⇒ 当场抛（整次创世失败，不落半截世界）
   */
  public static SimulationState apply(
      SimulationState state, CommandApplier applier, GameMap map, List<GovSpec> specs) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(applier, "applier");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(specs, "specs");
    List<GovSpec> ordered = List.copyOf(specs);
    if (ordered.isEmpty()) {
      throw new IllegalArgumentException("specs 不得为空（没有 GOV 就没有行政链）");
    }
    requireSeatsAndRegions(map, ordered);
    requireDisjointJurisdictions(map, ordered);
    requireCurrenciesDefinable(ordered);

    SimulationState next = state;
    for (GovSpec spec : ordered) {
      next = bootstrapGov(next, applier, spec);
    }
    // ★★ B3：3 个持久市场区（I22 单一权威）。落点 = 全部 GOV 的 per-GOV 命令序之后（含全部 SetJurisdiction 与
    //   DefineCurrency）、国库注资之前；依赖与理由见类注。★ 自检不过 ⇒ 在铸币/审计之前整次创世失败。
    next =
        ThreePowersMarketZones.define(
            next, applier, map, ThreePowersMarketZones.zoneSpecsOf(ordered));
    // ★★ 国库注资：逐 GOV 一次纯正增量（缺账由该命令建账；政府家户已在链内 EnsureHouseholdAccount）。
    next =
        applier.apply(
            next,
            io.mosire.simos.app.tools.write.ActorAdjustAccountsTool.NAME,
            new AdjustAccountsHandler(),
            new ActorCodec(),
            treasuryInjectionPayload(ordered));
    // ★★ 逐 GOV × 逐币种一条 INITIAL_ENDOWMENT 审计（余额腿在上面那条命令里；顺序不可换：审计要求余额已存在）。
    for (GovSpec spec : ordered) {
      next = recordIssuances(next, applier, spec);
    }
    return next;
  }

  /** 座位在图上、辖区存在且**含座位**、座位格有市场（否则 GOV 服务与征税都没有可作用的格）。 */
  private static void requireSeatsAndRegions(GameMap map, List<GovSpec> specs) {
    Set<String> govIds = new LinkedHashSet<>();
    Set<String> regionIds = new LinkedHashSet<>();
    for (GovSpec spec : specs) {
      if (!govIds.add(spec.govId())) {
        throw new IllegalArgumentException("GOV id 重复: " + spec.govId());
      }
      if (!map.hexes().containsKey(spec.seat())) {
        throw new IllegalArgumentException("GOV " + spec.govId() + " 的座位不在当前地图里: " + spec.seat());
      }
      Region region = map.regions().get(new RegionId(spec.jurisdictionRegionId()));
      if (region == null) {
        throw new IllegalArgumentException(
            "GOV " + spec.govId() + " 的辖区不在当前地图里: " + spec.jurisdictionRegionId());
      }
      if (!regionIds.add(spec.jurisdictionRegionId())) {
        throw new IllegalArgumentException("两个 GOV 授同一个行政区（I23）: " + spec.jurisdictionRegionId());
      }
      if (!region.contains(spec.seat())) {
        throw new IllegalStateException(
            "GOV "
                + spec.govId()
                + " 的座位 "
                + spec.seat()
                + " 不在其辖区 "
                + spec.jurisdictionRegionId()
                + " 内（装配故障）");
      }
    }
    for (GovSpec spec : specs) {
      spec.superiorGov()
          .ifPresent(
              superior -> {
                if (!govIds.contains(superior)) {
                  throw new IllegalArgumentException(
                      "GOV " + spec.govId() + " 的上级 " + superior + " 不在本次创世表里（顺序必须上级先于下级）");
                }
                if (superior.equals(spec.govId())) {
                  throw new IllegalArgumentException("GOV 不得以自己为上级: " + spec.govId());
                }
              });
    }
  }

  /** ★★ I23 的**创世自检**：辖区两两不相交（逐 hex，不是"逐区 id"——两个不同的区完全可以覆盖同一格）。 */
  private static void requireDisjointJurisdictions(GameMap map, List<GovSpec> specs) {
    List<Map.Entry<String, Set<HexCoord>>> seen = new ArrayList<>(specs.size());
    for (GovSpec spec : specs) {
      Region region = map.regions().get(new RegionId(spec.jurisdictionRegionId()));
      Set<HexCoord> hexes = new LinkedHashSet<>(region.hexes());
      for (Map.Entry<String, Set<HexCoord>> previous : seen) {
        Set<HexCoord> both = new LinkedHashSet<>(hexes);
        both.retainAll(previous.getValue());
        if (!both.isEmpty()) {
          throw new IllegalStateException(
              "创世装配违反 I23（行政区互斥）：GOV "
                  + spec.govId()
                  + " 的辖区 "
                  + spec.jurisdictionRegionId()
                  + " 与 GOV "
                  + previous.getKey()
                  + " 的辖区 "
                  + previous.getValue().size()
                  + " 格存在重叠 hex "
                  + both.iterator().next()
                  + "（用户 2026-10-08 第 8 轮裁定：重叠辖区不被允许）");
        }
      }
      seen.add(Map.entry(spec.govId(), hexes));
    }
  }

  /** 国库货币必须已定义（银，或本批某个 spec 新定义的币）⇒ 否则创世会以"说不出这是什么钱"收场。 */
  private static void requireCurrenciesDefinable(List<GovSpec> specs) {
    Set<String> known = new LinkedHashSet<>();
    known.add(io.mosire.simos.economy.api.money.MoneyVocabulary.SILVER_CURRENCY_ID);
    for (GovSpec spec : specs) {
      spec.newCurrency().ifPresent(currency -> known.add(currency.currencyId()));
    }
    Set<String> definedBySpecs = new LinkedHashSet<>();
    for (GovSpec spec : specs) {
      spec.newCurrency().ifPresent(currency -> definedBySpecs.add(currency.currencyId()));
    }
    if (definedBySpecs.size()
        != specs.stream().filter(spec -> spec.newCurrency().isPresent()).count()) {
      throw new IllegalArgumentException("同一币种被多个 GOV 重复定义（一币一发行人）");
    }
    Set<String> declared = new LinkedHashSet<>();
    for (GovSpec spec : specs) {
      for (CurrencyId currency : spec.declaredIssuable()) {
        if (!known.contains(currency.value())) {
          throw new IllegalArgumentException(
              "GOV " + spec.govId() + " 声明发行未定义的币种 " + currency.value() + "（已知: " + known + "）");
        }
        if (!declared.add(currency.value())) {
          throw new IllegalArgumentException(
              "币种 " + currency.value() + " 被两个 GOV 声明为可发行（一币一发行人，I23 的同族：权限不得放大）");
        }
      }
      for (CurrencyId currency : spec.treasuryMoney().keySet()) {
        if (!known.contains(currency.value())) {
          throw new IllegalArgumentException(
              "GOV "
                  + spec.govId()
                  + " 的国库币种 "
                  + currency.value()
                  + " 既不是银、也不在本次创世定义的币种表里（"
                  + known
                  + "）");
        }
      }
    }
  }

  /** 单个 GOV 的固定命令序（见类注；顺序不可换）。 */
  private static SimulationState bootstrapGov(
      SimulationState state, CommandApplier applier, GovSpec spec) {
    String reason = "three-powers 创世：建 " + spec.govId() + " 行政链";
    String officeIndustryId = officeIndustryId(spec.seat());
    String governmentHousehold = spec.governmentHouseholdId().value();
    String officialHousehold = spec.officialHouseholdId();
    long standardLaborMilli = requireSocial(state).provisioning().standardLaborMilliHoursPerTick();
    if (standardLaborMilli <= 0L) {
      throw new IllegalStateException("Social 标准劳动定额必须为正（C8 权威），收到 " + standardLaborMilli);
    }
    long officialLaborMilli = Math.multiplyExact(OFFICIAL_MEMBERS_PER_GOV, standardLaborMilli);
    long personEquivalents = Math.floorDiv(officialLaborMilli, standardLaborMilli);

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
            SET_GOV_FORMATION_TYPE,
            new SetGovernmentFormationHandler(),
            new UnitCodec(),
            setGovFormationPayload(spec, personEquivalents));
    next =
        applier.apply(
            next,
            EconomyRegisterGovernmentHandler.TYPE,
            new EconomyRegisterGovernmentHandler(),
            new EconomyCodec(),
            registerGovernmentPayload(spec, reason));
    // ★ 本 GOV 发行新币 ⇒ 在"政府已登记"之后定义它（顺序不可换：DefineCurrency 要求发行人已登记）。
    if (spec.newCurrency().isPresent()) {
      next =
          applier.apply(
              next,
              EconomyDefineCurrencyHandler.TYPE,
              new EconomyDefineCurrencyHandler(),
              new EconomyCodec(),
              defineCurrencyPayload(spec, spec.newCurrency().get()));
    }
    next =
        applier.apply(
            next,
            SET_JURISDICTION_TYPE,
            new SetJurisdictionHandler(),
            new UnitCodec(),
            setJurisdictionPayload(spec));
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

    // ★ 承诺劳动的唯一权威 = 转移后的 Social 投影；与"2 × 标准定额"不一致 ⇒ 创世自检失败。
    SocialData afterTransfer = requireSocial(next);
    long projectedLaborMilli =
        afterTransfer.householdLaborMilli(
            HouseholdId.parse(officialHousehold),
            next.meta().timestamp().tick(),
            CalendarClock.julianDefault());
    if (projectedLaborMilli != officialLaborMilli) {
      throw new IllegalStateException(
          "官吏户 "
              + officialHousehold
              + " 的 Social 投影劳动 "
              + projectedLaborMilli
              + " 与预期 "
              + officialLaborMilli
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
            administrationPlanPayload(spec, standardLaborMilli));
    next =
        applier.apply(
            next,
            SetBudgetPolicyHandler.TYPE,
            new SetBudgetPolicyHandler(),
            new GovCodec(),
            budgetPolicyPayload(spec.govId()));
    return next;
  }

  /**
   * 逐 GOV × <b>该 GOV 真正发行的</b>币种，记一条 {@code INITIAL_ENDOWMENT} 审计（余额腿在 {@code
   * actor.AdjustAccounts}）。
   *
   * <p>★★ <b>为什么"国库里有"≠"能记发行"</b>（本批实测踩到，具名拒 {@code currency-not-issuable}）：{@code
   * economy.RecordMoneyIssuance} 只在 {@code Government.issuable} 上判"谁能发"（权限不得放大 / M6）。而国库的
   * **银工作余额**是每个 GOV 都有的（现行行政结算与官吏工资规则的记账币是银），但只有**中央**是银的发行政府 ⇒ 两个省的银余额**没有发行者**（它们不是银的发行人）。
   *
   * <p>★ 那么省国库的银从哪来？它与 {@code GovWorldBootstrap} 的既有口径逐字相同：{@code actor.AdjustAccounts}
   * 的**纯正增量**（"GM 代 GOV 的创世禀赋"），**不**产生一条会撒谎的发行记录（说"省发行了银"是错的）。这是一处 <b>如实的边界</b>：银的发行主体在这个世界里是中央
   * GOV（{@link GovSpec#declaredIssuable()}），省的银是**财政调拨** —— 而"调拨"的命令面不在 B1（见账本"不做"一节）。
   */
  private static SimulationState recordIssuances(
      SimulationState state, CommandApplier applier, GovSpec spec) {
    SimulationState next = state;
    Set<CurrencyId> issueable = new LinkedHashSet<>(spec.declaredIssuable());
    spec.newCurrency().ifPresent(currency -> issueable.add(new CurrencyId(currency.currencyId())));
    for (CurrencyId currency : issueable) {
      Long amount = spec.treasuryMoney().get(currency);
      if (amount == null || amount <= 0L) {
        continue; // "没有发行"与"发行 0"是两件事；没注资也不该有记录
      }
      next =
          applier.apply(
              next,
              EconomyRecordMoneyIssuanceHandler.TYPE,
              new EconomyRecordMoneyIssuanceHandler(),
              new EconomyCodec(),
              issuancePayload(spec.govId(), currency, amount));
    }
    return next;
  }

  // ── 载荷（唯一构造点；形状以各 handler 的注为准，字段名与解析器逐字对应）─────────────────────────

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
    payload.put("position", ToolSupport.hexCoord(spec.seat()));
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

  private static String setGovFormationPayload(GovSpec spec, long personEquivalents) {
    OfficePolicy policy = OfficePolicy.defaults();
    Map<String, Object> staff = new LinkedHashMap<>();
    staff.put(StaffRole.POST.name(), personEquivalents);
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
    payload.put("q", spec.seat().q());
    payload.put("r", spec.seat().r());
    if (!spec.declaredIssuable().isEmpty()) {
      List<String> issuable = new ArrayList<>(spec.declaredIssuable().size());
      for (CurrencyId currency : spec.declaredIssuable()) {
        issuable.add(currency.value());
      }
      payload.put("issuable", issuable);
    }
    payload.put("reason", reason);
    return ToolSupport.json(payload);
  }

  private static String defineCurrencyPayload(GovSpec spec, NewCurrency currency) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", spec.govId());
    payload.put("currencyId", currency.currencyId());
    payload.put("scale", currency.scale());
    payload.put("displayName", currency.displayName());
    payload.put(
        "reason",
        "three-powers 创世：" + spec.govId() + " 定义本区法定币 " + currency.currencyId() + "（非铸币生产方式）");
    return ToolSupport.json(payload);
  }

  private static String setJurisdictionPayload(GovSpec spec) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", spec.govId());
    payload.put("regions", List.of(spec.jurisdictionRegionId()));
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
        social.cities().values().stream().anyMatch(city -> city.at().equals(spec.seat()))
            ? ResidenceKind.URBAN
            : ResidenceKind.RURAL;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("household", officialHousehold);
    payload.put("q", spec.seat().q());
    payload.put("r", spec.seat().r());
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

  private static String administrationPlanPayload(GovSpec spec, long standardLaborMilli) {
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
    // ★ 三个 GOV 同形：两维计划各 = 1 个人当量（与 GovWorldBootstrap 的中央同制）；实际供给 = 2 名官吏 ⇒ 1000‰ 覆盖。
    payload.put("securityPlannedLaborMilli", standardLaborMilli);
    payload.put("paperworkPlannedLaborMilli", standardLaborMilli);
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
    payload.put("remittancePerMilleToSuperior", 0L);
    return ToolSupport.json(payload);
  }

  /** 逐 GOV 国库的初始 粮/布/币（{@code actor.AdjustAccounts} 的纯正增量；缺账由该命令建账）。 */
  private static String treasuryInjectionPayload(List<GovSpec> specs) {
    List<Map<String, Object>> entries = new ArrayList<>(specs.size());
    for (GovSpec spec : specs) {
      Map<String, Object> goods = new LinkedHashMap<>();
      goods.put(EconomyCommodities.GRAIN.value(), TREASURY_GRAIN_MILLI_PER_GOV);
      goods.put(EconomyCommodities.CLOTH.value(), TREASURY_CLOTH_MILLI_PER_GOV);
      Map<String, Object> money = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry : spec.treasuryMoney().entrySet()) {
        money.put(entry.getKey().value(), entry.getValue());
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("household", spec.governmentHouseholdId().value());
      entry.put("goods", goods);
      entry.put("money", money);
      entries.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }

  private static String issuancePayload(String govId, CurrencyId currency, long amountMilli) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govId);
    payload.put("currency", currency.value());
    payload.put("amountMilli", amountMilli);
    payload.put("kind", "INITIAL_ENDOWMENT");
    payload.put(
        "reason",
        "three-powers 创世：" + govId + " 国库的 " + currency.value() + " 初始禀赋（INITIAL_ENDOWMENT）");
    return ToolSupport.json(payload);
  }

  // ── 小件 ─────────────────────────────────────────────────────────────────────────────────

  private static String officeIndustryId(HexCoord at) {
    return OFFICE_BASE_KIND + "@" + at.q() + "_" + at.r();
  }

  /**
   * 只读诊断：当前状态里带 {@code GovernmentFormation} 的 GOV 单位数（保序无关；供创世日志与探针复用）。
   *
   * @throws IllegalStateException 状态里没有 unit 切片（装配故障）
   */
  public static int requireGovCount(SimulationState state) {
    Objects.requireNonNull(state, "state");
    UnitState units =
        state
            .module("unit")
            .filter(UnitSnapshot.class::isInstance)
            .map(UnitSnapshot.class::cast)
            .map(UnitSnapshot::state)
            .orElseThrow(
                () -> new IllegalStateException("ThreePowersGovBootstrap 需要 unit 切片（装配故障）"));
    int count = 0;
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        count++;
      }
    }
    return count;
  }

  private static SocialData requireSocial(SimulationState state) {
    return state
        .module("social")
        .filter(SocialSnapshot.class::isInstance)
        .map(SocialSnapshot.class::cast)
        .map(SocialSnapshot::data)
        .orElseThrow(
            () -> new IllegalStateException("ThreePowersGovBootstrap 需要 social 切片（创世装配故障）"));
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不得为空白");
    }
  }
}
