package io.mosire.simos.app.query;

import io.mosire.simos.app.access.AppDecisionMakerAffiliations;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 决策人只读查询面（WebUI 阶段修复 T5，spec §六.1）：给 GUI 提供"列出全部 / 按归属过滤 / 取单个详情"。
 *
 * <p>★ **为什么单列一类**：此前**没有任何"列出决策人"的查询面**——{@code Shell.currentActorIds} 只全量遍历、**不过滤
 * affiliation**，{@code RedactingQueryService.scopeOf} 只按 ID 单查。T7（决策人交互）/ T9（待决信号）/ T10（开始决策入口）
 * 都要读这个面。
 *
 * <p>★ **铁律 3：读取走各模块自己的口**。★★ <b>三张表的家逐批搬过（本类跟着改的只有读取入口，视图字段一字未变）</b>：{@code decisionMakers} 与
 * {@code info} 自 2026-10-12 批次 3b-2 起住 {@code simos-ga}（用户原话「ga 应当维护标准的一般决策人」）⇒ 读 {@code
 * ToolSupport.gaState}；{@code nations} 自 3c 起住 {@code simos-gov} ⇒ 读 {@code
 * ToolSupport.govState}（{@link #govOf}）；<b>{@code armies} 自本批 3d-1 起住 {@code simos-army}</b> ⇒ 读
 * {@code ToolSupport.armyData}。各处都只用**公共 record
 * 访问器**——不反射内部字段、不复制它的结构。本类**不新增字段**（D13：左栏要显示的现有数据都够）。
 *
 * <p>★ **fail-closed**：过滤串**未知 kind / 空 id / 少冒号**一律抛（GUI 折成 400），**不静默返回空集合冒充"没有决策人"**； 单个详情查不到 ⇒
 * {@link Optional#empty()}（GUI 折成 404），与 unit/region 详情同口径。归属目标解析不出显示名时字段为 {@code
 * null}（**显式未知**，不编造）。
 *
 * <p>★ **待决信号（T9，D7 已裁）**：{@link #pending} 按公式 {@code due = 当前 tick − 该 dm 最近一次落 Directive 的 tick ≥
 * decisionCadenceTicks} 计算，**首次（无任何 Directive）恒 {@code due}**。这是**只读派生**—— 不写 {@code SdState}、不落
 * revision（铁律 2：本类没有写入口）。当前 tick 取**被查询快照**的 {@code meta().timestamp().tick()}（推进只改 tick，不产生决策标记）。
 */
public final class SdQueryService {

  private final QueryService query;

  public SdQueryService(QueryService query) {
    this.query = Objects.requireNonNull(query, "query");
  }

  /**
   * 归属过滤（spec §六.1 的 {@code ?affiliation=nation:<id>|army:<id>|gov:<unitId>}）。
   *
   * <p>★ {@code gov} 是阶段 10a 新增：过滤的是"决策人 → GOV 单位"那条归属轴（实际行政部门），与"决策人 → 国家"（政治实体） 是两条并存的归属轴（用户裁定
   * 5/7）。★ 乙收尾后归属表住各模块自己的状态（{@code GovState.nationAffiliations} / {@code GovState.govAffiliations}
   * / {@code ArmyData.armyAffiliations}），过滤词表仍是本类自己的 GUI 词表。
   */
  public sealed interface AffiliationFilter {

    /** 不筛（列出全部）。 */
    record All() implements AffiliationFilter {}

    record OfNation(NationId nationId) implements AffiliationFilter {}

    record OfArmy(ArmyId armyId) implements AffiliationFilter {}

    record OfGov(UnitId govUnit) implements AffiliationFilter {}
  }

  /** 过滤缺省：全部。 */
  public static final AffiliationFilter ALL = new AffiliationFilter.All();

  /**
   * 解析 {@code ?affiliation=…}：{@code null}/空白 ⇒ {@link #ALL}；{@code nation:<id>} / {@code
   * army:<id>} / {@code gov:<unitId>} 各成过滤。
   *
   * <p>★ **fail-closed**：未知 kind、缺冒号、空 id 一律 {@link IllegalArgumentException}（GUI 回 400）。把这些折成"空列表"
   * 会让"输入坏掉"与"确实没有决策人"不可区分——那是把"没查到"伪装成"不存在"的同族陷阱。
   */
  public static AffiliationFilter parseFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return ALL;
    }
    int colon = raw.indexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException(
          "affiliation 过滤形如 nation:<id> / army:<id> / gov:<unitId>: " + raw);
    }
    String kind = raw.substring(0, colon);
    String id = raw.substring(colon + 1);
    return switch (kind) {
      case "nation" -> new AffiliationFilter.OfNation(NationId.parse(id));
      case "army" -> new AffiliationFilter.OfArmy(ArmyId.parse(id));
      case "gov" -> new AffiliationFilter.OfGov(UnitId.parse(id));
      default ->
          throw new IllegalArgumentException(
              "未知 affiliation kind（只认 nation / army / gov）: " + kind);
    };
  }

  /**
   * 全部决策人（先按 filter 筛、再按 id **字典序**排），每条带由 affiliation 解析出的显示信息。
   *
   * <p>★ 排序是为了**响应字节可复现**（{@code GaState.decisionMakers()} 是插入序表；C15 要"组内按 id 字典序"）。
   */
  public List<DecisionMakerInfo> listDecisionMakers(AffiliationFilter filter, QueryTarget target) {
    Objects.requireNonNull(filter, "filter");
    SimulationState state = query.stateAt(target);
    GaState ga = gaState(state);
    // ★★ 乙收尾：归属不再挂在决策人身上 ⇒ 先拼一次"决策人 → 归属"的索引（唯一拼装点 =
    //   AppDecisionMakerAffiliations，各模块自报），再按它筛。
    Map<DecisionMakerId, List<AppDecisionMakerAffiliations.Claim>> affiliations =
        AppDecisionMakerAffiliations.indexOf(state);
    List<DecisionMaker> makers = new ArrayList<>();
    for (DecisionMaker maker : ga.decisionMakers().values()) {
      if (matches(affiliations.getOrDefault(maker.id(), List.of()), filter)) {
        makers.add(maker);
      }
    }
    makers.sort(Comparator.comparing(maker -> maker.id().value()));
    return infos(makers, affiliations, state, tickOf(state));
  }

  /**
   * **全部决策记录**（{@code GET /api/sd/directives} 的数据源）：按 {@link #order} 排（**新的在前**）。
   *
   * <p>★ **它回答的是"这人到底下了什么令"**：此前 {@link DecisionMakerInfo} 只报 {@code lastDirectiveTick} / {@code
   * ticksSinceLast}（"最近一次在第几 tick"），**内容一个字都看不到**——决策的产出其实一直在世界状态里（{@code
   * sd.directives()}），缺的只是读它的口。
   */
  public List<DirectiveInfo> listDirectives(QueryTarget target) {
    SimulationState state = query.stateAt(target);
    return directivesOf(directivesTable(state), gaState(state), null);
  }

  /**
   * **某个决策人的**决策记录；该决策人在这个版本的世界里查无 ⇒ {@link Optional#empty()}（调用方折成 404）。
   *
   * <p>★ **为什么查无是 404 而不是空列表**：空列表只表示"这个人还没出过令"，把它与"没有这个人"折成同一个响应，等于把 "没查到"伪装成"不存在"（与 {@link
   * #decisionMaker} 同口径）。过滤 {@code ?affiliation=} 那种**集合筛**才用空列表。
   */
  public Optional<List<DirectiveInfo>> listDirectives(DecisionMakerId id, QueryTarget target) {
    Objects.requireNonNull(id, "id");
    SimulationState state = query.stateAt(target);
    if (!gaState(state).decisionMakers().containsKey(id)) {
      return Optional.empty();
    }
    return Optional.of(directivesOf(directivesTable(state), gaState(state), id));
  }

  /**
   * 决策记录投影 + **执行原文**：{@code intentInfo} 从 ga INFO 覆盖层取（spec §三.6：写在 {@code sd:directive.<id>}
   * 地址下、key = 该记录自己的 {@code intentInfoKey}），取不到 ⇒ {@code null}（**显式未知**，不拿空串顶替）。
   *
   * <p>★★ <b>两半现在同住一个切片</b>（2026-10-12 批次 3b-2 起）：{@code directives} 表随 3b-1、{@code info} 覆盖层随本批
   * 都搬进 {@code simos-ga} ⇒ 令与执行原文都从 ga 切片读（{@link #directivesTable(SimulationState)} / {@link
   * #gaState(SimulationState)}）。
   */
  private static List<DirectiveInfo> directivesOf(
      Map<DirectiveId, Directive> directives, GaState ga, DecisionMakerId only) {
    Map<DirectiveId, Map<String, String>> infoByDirective = infoByDirective(ga);
    List<Directive> picked = new ArrayList<>();
    for (Directive directive : directives.values()) {
      if (only == null || directive.decisionMakerId().equals(only)) {
        picked.add(directive);
      }
    }
    picked.sort(order());
    List<DirectiveInfo> out = new ArrayList<>(picked.size());
    for (Directive directive : picked) {
      String intent =
          infoByDirective.getOrDefault(directive.id(), Map.of()).get(directive.intentInfoKey());
      out.add(new DirectiveInfo(directive, intent));
    }
    return List.copyOf(out);
  }

  /**
   * 排序（**响应字节可复现**的前提）：tick **降序**（新的在前，界面要的第一条就是"最近一次"），同 tick 再按 id 字典序。
   *
   * <p>★ {@code GaState.directives()} 是插入序表（R4 自 2026-09-23 起是**末位生效**：同一 {@code (dm,tick)} 可有多条令，
   * 旧版为 {@code SUPERSEDED}）⇒ 插入序**不保证**任何可读次序，必须显式排。
   */
  private static Comparator<Directive> order() {
    return Comparator.comparingLong(Directive::tick)
        .reversed()
        .thenComparing(directive -> directive.id().value());
  }

  /**
   * 执行原文索引：{@code 决策 id ⇒ (INFO key ⇒ 值)}。**一次遍历**建好（每次查一条就重扫一遍 ga.info 是 O(n·m)）。
   *
   * <p>★ 认 {@code sourceDirective} 而**不是**自己去拼地址串：地址形态是写入方（{@code IssueDirectiveHandler}）的事，
   * 读的一侧照它留的引用找——拼串的两处会各自"看起来对"，改名时静默失配。
   */
  private static Map<DirectiveId, Map<String, String>> infoByDirective(GaState ga) {
    Map<DirectiveId, Map<String, String>> out = new LinkedHashMap<>();
    for (List<SdInfoEntry> entries : ga.info().values()) {
      for (SdInfoEntry entry : entries) {
        if (entry.sourceDirective().isEmpty()) {
          continue;
        }
        out.computeIfAbsent(entry.sourceDirective().get(), ignored -> new LinkedHashMap<>())
            .put(entry.key(), String.valueOf(entry.value()));
      }
    }
    return out;
  }

  /** 单个决策人；不存在 ⇒ {@link Optional#empty()}（调用方折成 404）。 */
  public Optional<DecisionMakerInfo> decisionMaker(DecisionMakerId id, QueryTarget target) {
    Objects.requireNonNull(id, "id");
    SimulationState state = query.stateAt(target);
    DecisionMaker maker = gaState(state).decisionMakers().get(id);
    return maker == null
        ? Optional.empty()
        : Optional.of(
            info(maker, AppDecisionMakerAffiliations.claimsOf(id, state), state, tickOf(state)));
  }

  /**
   * 该决策人的归属里有没有 filter 要的那一条。
   *
   * <p>★★ 2026-10-12 乙收尾：判据从"看 {@code Affiliation} 的密封分支"改成"比**受众标签**"（{@code kind:ref}）——标签是
   * 各模块自报的串（唯一拼写点 {@code AppDecisionMakerAffiliations.label}），过滤词表（{@code
   * ?affiliation=nation:…}）与它同源。★ 过滤词的 kind 仍是 app 的 GUI 词表（{@code SdQueryService.parseFilter}），
   * 与"归属有几种"无关。
   */
  private static boolean matches(
      List<AppDecisionMakerAffiliations.Claim> claims, AffiliationFilter filter) {
    return switch (filter) {
      case AffiliationFilter.All ignored -> true;
      case AffiliationFilter.OfNation nation ->
          hasLabel(claims, NationAffiliations.KIND, nation.nationId().value());
      case AffiliationFilter.OfArmy army ->
          hasLabel(claims, ArmyAffiliations.KIND, army.armyId().value());
      case AffiliationFilter.OfGov gov ->
          hasLabel(claims, GovAffiliations.KIND, gov.govUnit().value());
    };
  }

  private static boolean hasLabel(
      List<AppDecisionMakerAffiliations.Claim> claims, String kind, String ref) {
    String label = AppDecisionMakerAffiliations.label(kind, ref);
    for (AppDecisionMakerAffiliations.Claim claim : claims) {
      if (label.equals(claim.label())) {
        return true;
      }
    }
    return false;
  }

  private static List<DecisionMakerInfo> infos(
      List<DecisionMaker> makers,
      Map<DecisionMakerId, List<AppDecisionMakerAffiliations.Claim>> affiliations,
      SimulationState state,
      long tick) {
    List<DecisionMakerInfo> out = new ArrayList<>(makers.size());
    for (DecisionMaker maker : makers) {
      out.add(info(maker, affiliations.getOrDefault(maker.id(), List.of()), state, tick));
    }
    return List.copyOf(out);
  }

  /**
   * 决策人视图的领域侧投影（乙收尾改造：归属来自 {@code AppDecisionMakerAffiliations} 的索引，不再读 {@code
   * maker.affiliation()}）。
   *
   * <p>★ <b>多轴时取第一条</b>（按 {@code AppDecisionMakerAffiliations.SOURCES} 的登记序 = nation / gov / army）：
   * 视图只报一条归属（字段形状一字未改），而权限面（{@code DecisionScopeFunctions}）对多轴取并集——两者不冲突，但**视图不假装完整**： 归属条数可从
   * {@code AppDecisionMakerAffiliations.claimsOf} 现取。
   *
   * <p>★ 解析不出的字段一律 {@code null}（显式未知，不编造）。
   */
  private static DecisionMakerInfo info(
      DecisionMaker maker,
      List<AppDecisionMakerAffiliations.Claim> claims,
      SimulationState state,
      long tick) {
    if (claims.isEmpty()) {
      // 裸决策人（乙收尾起合法：创建时没有归属）⇒ 归属三槽全 null（显式未知，不编造）。
      return new DecisionMakerInfo(
          maker, null, null, null, null, null, null, pending(maker, directivesTable(state), tick));
    }
    AppDecisionMakerAffiliations.Claim claim = claims.get(0);
    GovState govState = govOf(state);
    String displayName = null;
    String nationId = null;
    String masterGovUnitId = null;
    String rootUnit = null;
    if (NationAffiliations.KIND.equals(claim.kind())) {
      Nation value = nationOrNull(govState, claim.ref());
      displayName = value == null ? null : value.name();
      nationId = value == null ? null : value.id().value();
    } else if (ArmyAffiliations.KIND.equals(claim.kind())) {
      Army value = ToolSupport.armyData(state).armies().get(ArmyId.parse(claim.ref()));
      displayName = value == null ? null : value.name();
      masterGovUnitId =
          value == null ? null : value.masterGovUnitId().map(UnitId::value).orElse(null);
      rootUnit = value == null ? null : value.rootUnit().value();
    } else if (GovAffiliations.KIND.equals(claim.kind())) {
      Unit value = unitOf(state, UnitId.parse(claim.ref()));
      displayName = value == null ? null : value.name();
    }
    return new DecisionMakerInfo(
        maker,
        claim.kind(),
        claim.ref(),
        displayName,
        nationId,
        masterGovUnitId,
        rootUnit,
        pending(maker, directivesTable(state), tick));
  }

  /** unit 切片里的单位（stage 10a 的 GOV 显示名用）：缺切片/类型不对 = 装配故障，当场炸。 */
  private static Unit unitOf(SimulationState state, UnitId id) {
    Snapshot snapshot =
        state.module("unit").orElseThrow(() -> new IllegalStateException("状态里没有 unit 切片（装配故障）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "状态里 unit 切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state().units().get(id);
  }

  /**
   * D7 已裁公式：{@code due = (当前 tick − 该 dm 最近一次落 Directive 的 tick) ≥ decisionCadenceTicks}； 首次（该 dm
   * 无任何 Directive）**恒 {@code due}**。
   *
   * <p>★ **"最近一次"取最大 tick，不取最小**：{@code GaState.directives()} 是插入序表（R4 自 2026-09-23 起是**末位生效** ——同一
   * {@code (dm,tick)} 可有多条令）⇒ 插入序与 tick 序无关，必须显式求 max；取首个/取最小都会把周期算反。
   *
   * <p>★ **首次用 {@code null} 表示"没有上一次"**（{@code ticksSinceLast} 是无穷大）：填 {@code 0} 或 {@code -1}
   * 会把"没有基准"伪装成一个具体间隔，下游就没法把"从未决策"与"刚决策过"区分开。
   *
   * <p>★ **只读派生**：只查 {@code GaState.directives()}（2026-10-12 批次 3b′ 起住 ga），不改任何状态、不落 revision。
   */
  private static PendingSignal pending(
      DecisionMaker maker, Map<DirectiveId, Directive> directives, long tick) {
    Long last = null;
    for (Directive directive : directives.values()) {
      if (!directive.decisionMakerId().equals(maker.id())) {
        continue;
      }
      if (last == null || directive.tick() > last) {
        last = directive.tick();
      }
    }
    if (last == null) {
      return new PendingSignal(true, null, null);
    }
    long since = tick - last;
    return new PendingSignal(since >= maker.decisionCadenceTicks(), last, since);
  }

  /** 被查询快照的当前 tick（推进只改它；决策标记只由命令落，不由推进产生）。 */
  private static long tickOf(SimulationState state) {
    return state.meta().timestamp().tick();
  }

  /**
   * {@code directives} 表的取数口（2026-10-12 批次 3b′：表住 {@code simos-ga}）——与 {@link #directivesOf} 区分：
   * 本方法只取表，后者做投影。
   *
   * <p>★ 缺切片 / 类型不对 = 装配故障，当场炸（与 {@link #govOf} 同口径，不静默折成空表）。
   */
  private static Map<DirectiveId, Directive> directivesTable(SimulationState state) {
    return gaState(state).directives();
  }

  /**
   * ga 切片的取数口（2026-10-12 批次 3b-2：{@code decisionMakers} 与 {@code info} 两张表搬进 {@code simos-ga}）； 缺切片
   * / 类型不对 = 装配故障，当场炸（与 {@link #govOf} 同口径）。
   */
  private static GaState gaState(SimulationState state) {
    return ToolSupport.gaState(state);
  }

  /**
   * 把不透明国家引用解释成 gov 的国家记录：解析不了 / 国家不在表里 ⇒ {@code null}（**显式未知**，不编造、也不抛）。
   *
   * <p>★ 2026-10-12 批次 3c：{@code nations} 表搬进 {@link GovState}，而 sd 侧只剩 {@code Affiliation.Nation}
   * 的不透明串 ⇒ 解释与查表都在这里（读侧的唯一口径见 {@code AppNationAffiliations}）。
   */
  private static Nation nationOrNull(GovState govState, String opaqueNationId) {
    NationId nationId;
    try {
      nationId = NationId.parse(opaqueNationId);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return govState.nations().get(nationId);
  }

  /**
   * gov 切片的取数口（2026-10-12 批次 3c：{@code nations} 与外交两表搬进 {@code simos-gov}）；缺切片 / 类型不对 = 装配故障，当场炸。
   */
  private static GovState govOf(SimulationState state) {
    return ToolSupport.govState(state);
  }

  /**
   * 决策人视图的领域侧投影：{@link DecisionMaker} 本体 + 由 affiliation 解析出的显示信息。
   *
   * <p>★ 解析不出的字段一律 {@code null}（显式未知）。归属三槽（{@code affiliationKind}/{@code affiliationId} 之外的
   * nation/army 派生槽）在"该轴不存在"或"目标已被删"时为 {@code null}——**不用空串顶替**，那会与"名称为空"混淆。 ★★ 乙收尾起 {@code
   * affiliationKind}/{@code affiliationId} 也可能是 {@code null}：**裸决策人**（创建后还没被任何模块赋予归属）
   * 是合法状态，且此时它什么都看不见（deny-all）。
   *
   * <p>★ 阶段 12：Army 去 {@code NationId} 后，{@code nationId} 槽位只服务 {@link
   * Affiliation.Nation}；军队的认领主子改看 {@code masterGovUnitId}（未认主子 ⇒ {@code null}）。
   */
  public record DecisionMakerInfo(
      DecisionMaker maker,
      String affiliationKind,
      String affiliationId,
      String displayName,
      String nationId,
      String masterGovUnitId,
      String rootUnit,
      PendingSignal pending) {}

  /**
   * 一条决策记录的投影：{@link Directive} 本体 + 从 ga INFO 覆盖层取回的**执行原文**。
   *
   * <p>★ {@code intentInfo} 取不到时为 {@code null}（**显式未知**）——它可能在（a）该记录由别处构造、没写 INFO，
   * （b）地址被外部改坏。两种情况都不该被读成"这条决策没有理由"。
   */
  public record DirectiveInfo(Directive directive, String intentInfo) {}

  /**
   * 待决信号（T9，D7 已裁公式）的三件派生值。
   *
   * <p>{@code lastDirectiveTick} / {@code ticksSinceLast} 在该决策人**从未落过 Directive** 时为 {@code null}
   * （{@code due} 恒 {@code true}）——这是"没有基准"的显式表示，不是缺字段。
   */
  public record PendingSignal(boolean due, Long lastDirectiveTick, Long ticksSinceLast) {}
}
