package io.mosire.simos.app.query;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
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
    SdState sd = sdState(target);
    List<DecisionMaker> makers = new ArrayList<>();
    for (DecisionMaker maker : sd.decisionMakers().values()) {
      if (matches(maker.affiliation(), filter)) {
        makers.add(maker);
      }
    }
    makers.sort(Comparator.comparing(maker -> maker.id().value()));
    return infos(makers, sd);
  }

  /** 单个决策人；不存在 ⇒ {@link Optional#empty()}（调用方折成 404）。 */
  public Optional<DecisionMakerInfo> decisionMaker(DecisionMakerId id, QueryTarget target) {
    Objects.requireNonNull(id, "id");
    SdState sd = sdState(target);
    DecisionMaker maker = sd.decisionMakers().get(id);
    return maker == null ? Optional.empty() : Optional.of(info(maker, sd));
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

  private static List<DecisionMakerInfo> infos(List<DecisionMaker> makers, SdState sd) {
    List<DecisionMakerInfo> out = new ArrayList<>(makers.size());
    for (DecisionMaker maker : makers) {
      out.add(info(maker, sd));
    }
    return List.copyOf(out);
  }

  private static DecisionMakerInfo info(DecisionMaker maker, SdState sd) {
    return switch (maker.affiliation()) {
      case Affiliation.Nation nation -> {
        Nation value = sd.nations().get(nation.nationId());
        yield new DecisionMakerInfo(
            maker,
            "nation",
            nation.nationId().value(),
            value == null ? null : value.name(),
            nation.nationId().value(),
            null);
      }
      case Affiliation.Army army -> {
        Army value = sd.armies().get(army.armyId());
        yield new DecisionMakerInfo(
            maker,
            "army",
            army.armyId().value(),
            value == null ? null : value.name(),
            value == null ? null : value.nationId().value(),
            value == null ? null : value.rootUnit().value());
      }
    };
  }

  /** sd 切片只能从 sd 模块拿（铁律 3/4）：缺席或类型不对是装配故障，不是"没有候选"。 */
  private SdState sdState(QueryTarget target) {
    SimulationState state = query.stateAt(target);
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
      String rootUnit) {}
}
