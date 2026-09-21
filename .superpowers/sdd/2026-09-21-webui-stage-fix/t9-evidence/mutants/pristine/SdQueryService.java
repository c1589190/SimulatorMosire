package io.mosire.simos.app.query;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 决策人只读查询面（WebUI 阶段修复 T5，spec §六.1）：给 GUI 提供"列出全部 / 按归属过滤 / 取单个详情"。
 *
 * <p>★ **为什么单列一类**：此前**没有任何"列出决策人"的查询面**——{@code Shell.currentActorIds} 只全量遍历、**不过滤
 * affiliation**，{@code RedactingQueryService.scopeOf} 只按 ID 单查。T7（决策人交互）/ T9（待决信号）/ T10（开始决策入口）
 * 都要读这个面。
 *
 * <p>★ **铁律 3：读取走 sd 模块自己的口**。数据来源是 {@code Snapshot}"sd" 切片（{@link SdSnapshot}）的 {@link
 * SdState}，只用它的**公共 record 访问器**（{@code decisionMakers()} / {@code armies()} / {@code nations()}）——
 * 不反射内部字段、不复制它的结构。本类**不新增 sd 字段**（D13：左栏要显示的现有数据都够）。
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

  /** 归属过滤（spec §六.1 的 {@code ?affiliation=nation:<id>|army:<id>}）。 */
  public sealed interface AffiliationFilter {

    /** 不筛（列出全部）。 */
    record All() implements AffiliationFilter {}

    record OfNation(NationId nationId) implements AffiliationFilter {}

    record OfArmy(ArmyId armyId) implements AffiliationFilter {}
  }

  /** 过滤缺省：全部。 */
  public static final AffiliationFilter ALL = new AffiliationFilter.All();

  /**
   * 解析 {@code ?affiliation=…}：{@code null}/空白 ⇒ {@link #ALL}；{@code nation:<id>} / {@code
   * army:<id>} 各成过滤。
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
      throw new IllegalArgumentException("affiliation 过滤形如 nation:<id> 或 army:<id>: " + raw);
    }
    String kind = raw.substring(0, colon);
    String id = raw.substring(colon + 1);
    return switch (kind) {
      case "nation" -> new AffiliationFilter.OfNation(NationId.parse(id));
      case "army" -> new AffiliationFilter.OfArmy(ArmyId.parse(id));
      default ->
          throw new IllegalArgumentException("未知 affiliation kind（只认 nation / army）: " + kind);
    };
  }

  /**
   * 全部决策人（先按 filter 筛、再按 id **字典序**排），每条带由 affiliation 解析出的显示信息。
   *
   * <p>★ 排序是为了**响应字节可复现**（{@code SdState.decisionMakers()} 是插入序表；C15 要"组内按 id 字典序"）。
   */
  public List<DecisionMakerInfo> listDecisionMakers(AffiliationFilter filter, QueryTarget target) {
    Objects.requireNonNull(filter, "filter");
    SimulationState state = query.stateAt(target);
    SdState sd = sdState(state);
    List<DecisionMaker> makers = new ArrayList<>();
    for (DecisionMaker maker : sd.decisionMakers().values()) {
      if (matches(maker.affiliation(), filter)) {
        makers.add(maker);
      }
    }
    makers.sort(Comparator.comparing(maker -> maker.id().value()));
    return infos(makers, sd, tickOf(state));
  }

  /** 单个决策人；不存在 ⇒ {@link Optional#empty()}（调用方折成 404）。 */
  public Optional<DecisionMakerInfo> decisionMaker(DecisionMakerId id, QueryTarget target) {
    Objects.requireNonNull(id, "id");
    SimulationState state = query.stateAt(target);
    SdState sd = sdState(state);
    DecisionMaker maker = sd.decisionMakers().get(id);
    return maker == null ? Optional.empty() : Optional.of(info(maker, sd, tickOf(state)));
  }

  private static boolean matches(Affiliation affiliation, AffiliationFilter filter) {
    return switch (filter) {
      case AffiliationFilter.All ignored -> true;
      case AffiliationFilter.OfNation nation ->
          affiliation instanceof Affiliation.Nation n && n.nationId().equals(nation.nationId());
      case AffiliationFilter.OfArmy army ->
          affiliation instanceof Affiliation.Army a && a.armyId().equals(army.armyId());
    };
  }

  private static List<DecisionMakerInfo> infos(List<DecisionMaker> makers, SdState sd, long tick) {
    List<DecisionMakerInfo> out = new ArrayList<>(makers.size());
    for (DecisionMaker maker : makers) {
      out.add(info(maker, sd, tick));
    }
    return List.copyOf(out);
  }

  private static DecisionMakerInfo info(DecisionMaker maker, SdState sd, long tick) {
    return switch (maker.affiliation()) {
      case Affiliation.Nation nation -> {
        Nation value = sd.nations().get(nation.nationId());
        yield new DecisionMakerInfo(
            maker,
            "nation",
            nation.nationId().value(),
            value == null ? null : value.name(),
            nation.nationId().value(),
            null,
            pending(maker, sd, tick));
      }
      case Affiliation.Army army -> {
        Army value = sd.armies().get(army.armyId());
        yield new DecisionMakerInfo(
            maker,
            "army",
            army.armyId().value(),
            value == null ? null : value.name(),
            value == null ? null : value.nationId().value(),
            value == null ? null : value.rootUnit().value(),
            pending(maker, sd, tick));
      }
    };
  }

  /**
   * D7 已裁公式：{@code due = (当前 tick − 该 dm 最近一次落 Directive 的 tick) ≥ decisionCadenceTicks}； 首次（该 dm
   * 无任何 Directive）**恒 {@code due}**。
   *
   * <p>★ **"最近一次"取最大 tick，不取最小**：{@code SdState.directives()} 是插入序表（R4 只保证 {@code (dm,tick)}
   * 唯一，不保证按 tick 有序）⇒ 必须显式求 max；取首个/取最小都会把周期算反。
   *
   * <p>★ **首次用 {@code null} 表示"没有上一次"**（{@code ticksSinceLast} 是无穷大）：填 {@code 0} 或 {@code -1}
   * 会把"没有基准"伪装成一个具体间隔，下游就没法把"从未决策"与"刚决策过"区分开。
   *
   * <p>★ **只读派生**：只查 {@code sd.directives()}，不改任何状态、不落 revision。
   */
  private static PendingSignal pending(DecisionMaker maker, SdState sd, long tick) {
    Long last = null;
    for (Directive directive : sd.directives().values()) {
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

  /** sd 切片只能从 sd 模块拿（铁律 3/4）：缺席或类型不对是装配故障，不是"没有候选"。 */
  private SdState sdState(SimulationState state) {
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("状态里没有 sd 切片（装配故障）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException("状态里 sd 切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }

  /**
   * 决策人视图的领域侧投影：{@link DecisionMaker} 本体 + 由 affiliation 解析出的显示信息。
   *
   * <p>★ 解析不出的字段一律 {@code null}（显式未知）。当前命令路径（{@code sd.CreateDecisionMaker} 拒绝不存在的 affiliation
   * 目标）下这些字段必非空；{@code null} 只在状态被外部构造坏时出现——**不用空串顶替**，那会与"名称为空"混淆。
   */
  public record DecisionMakerInfo(
      DecisionMaker maker,
      String affiliationKind,
      String affiliationId,
      String displayName,
      String nationId,
      String rootUnit,
      PendingSignal pending) {}

  /**
   * 待决信号（T9，D7 已裁公式）的三件派生值。
   *
   * <p>{@code lastDirectiveTick} / {@code ticksSinceLast} 在该决策人**从未落过 Directive** 时为 {@code null}
   * （{@code due} 恒 {@code true}）——这是"没有基准"的显式表示，不是缺字段。
   */
  public record PendingSignal(boolean due, Long lastDirectiveTick, Long ticksSinceLast) {}
}
