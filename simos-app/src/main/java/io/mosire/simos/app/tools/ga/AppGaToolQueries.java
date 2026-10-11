package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.access.DecisionScopeView;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.ga.tools.GaToolQueries;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>{@link GaToolQueries} 的 app 实现</b>（2026-10-12 B3 第七域）：把"决策人 / 令 / 判决 / 决策结果 / 文档 / 上报"
 * 的读面**逐条转调既有的唯一实现**（{@link SdQueryService} 与 {@link RedactingQueryService}）。
 *
 * <p>★ <b>为什么这些查询不跟着工具进 ga</b>：{@code RedactingQueryService} 的裁剪切面要吃 {@code
 * DecisionScopeFunctions}（范围函数 = 权限面）、{@code ApiViews}（视图）、{@code GameMap}/格/区（地图）⇒ 按 I-G2 只能住 app。
 * 本类**不重写任何判据**：可见性、排序、截断、字段集全部来自那两个服务（它们同时服务 GUI 端点 ⇒ 形状只有一处）。
 *
 * <p>★ <b>窗口镜像的单点转换</b>：ga 的 {@link Window} 与 {@code RedactingQueryService.DecisionResultWindow}
 * 逐字段同形（含三条构造期断言），这里只做一次 {@code new}。
 */
public final class AppGaToolQueries implements GaToolQueries {

  private final QueryService query;
  private final String mapId;

  public AppGaToolQueries(QueryService query, String mapId) {
    this.query = java.util.Objects.requireNonNull(query, "query");
    this.mapId = java.util.Objects.requireNonNull(mapId, "mapId");
  }

  /** 目标坐标：{@code revision == null} = 该分支 head（与端口类注同口径）。 */
  private static QueryTarget target(BranchId branch, RevisionId revision) {
    return revision == null ? QueryTarget.head(branch) : QueryTarget.at(branch, revision);
  }

  /** 裁剪读面（与 GUI 的 {@code as=} 路径同一段装配：无状态范围函数 + 同一 mapId）。 */
  private RedactingQueryService redacting() {
    return new RedactingQueryService(query, DecisionScopeFunctions.defaults(), mapId);
  }

  @Override
  public List<Map<String, Object>> decisionMakers(
      String affiliationFilterRaw, BranchId branch, RevisionId revision) {
    SdQueryService.AffiliationFilter filter = SdQueryService.parseFilter(affiliationFilterRaw);
    return ApiViews.decisionMakers(
        new SdQueryService(query).listDecisionMakers(filter, target(branch, revision)));
  }

  @Override
  public Optional<Map<String, Object>> decisionMaker(
      String decisionMakerIdRaw, BranchId branch, RevisionId revision) {
    QueryTarget at = target(branch, revision);
    DecisionMakerId makerId = new DecisionMakerId(decisionMakerIdRaw);
    Optional<SdQueryService.DecisionMakerInfo> info =
        new SdQueryService(query).decisionMaker(makerId, at);
    Optional<io.mosire.agentlib.permission.ResourceScopeMap> scopes =
        redacting().computedScopeOf(makerId, at);
    if (info.isEmpty() || scopes.isEmpty()) {
      // ★ 与 GUI `/api/sd/decision-makers/{id}/scope` 同口径：解不出决策人或算不出范围 ⇒ 同一个 404（不折成空对象）。
      return Optional.empty();
    }
    SimulationState state = query.stateAt(at);
    DecisionScopeView scope = DecisionScopeView.of(scopes.get(), ApiViews.gameMap(state), mapId);
    Map<String, Object> view = new LinkedHashMap<>(ApiViews.decisionMaker(info.get()));
    view.put("scope", ApiViews.decisionScope(info.get(), state.meta().ref(), scope));
    return Optional.of(view);
  }

  @Override
  public Optional<List<Map<String, Object>>> directives(
      String decisionMakerIdRaw, BranchId branch, RevisionId revision) {
    QueryTarget at = target(branch, revision);
    SdQueryService sd = new SdQueryService(query);
    if (decisionMakerIdRaw == null) {
      return Optional.of(ApiViews.directives(sd.listDirectives(at)));
    }
    Optional<List<SdQueryService.DirectiveInfo>> only =
        sd.listDirectives(DecisionMakerId.parse(decisionMakerIdRaw), at);
    // ★ 未知 id ⇒ 空（调用方据此给 NOT_FOUND），不折成"这个人还没出过令"的空列表。
    return only.map(ApiViews::directives);
  }

  @Override
  public List<Map<String, Object>> verdicts(
      String actorIdRaw, BranchId branch, RevisionId revision) {
    QueryTarget at = target(branch, revision);
    RedactingQueryService redacting = redacting();
    return actorIdRaw == null || actorIdRaw.isBlank()
        ? redacting.verdicts(at)
        : redacting.verdicts(new DecisionMakerId(actorIdRaw), at);
  }

  @Override
  public List<Map<String, Object>> decisionResults(
      DecisionMakerId actor, Window window, BranchId branch, RevisionId revision) {
    return redacting().decisionResults(actor, target(branch, revision), windowOf(window));
  }

  @Override
  public List<Map<String, Object>> docs(
      DecisionMakerId actor, Window window, BranchId branch, RevisionId revision) {
    return redacting().docs(actor, target(branch, revision), windowOf(window));
  }

  @Override
  public boolean reportVisibleToDecisionMaker(
      SdInfoEntry entry, DecisionMakerId actor, Affiliation affiliation, String sender) {
    return RedactingQueryService.reportVisibleToDecisionMaker(entry, actor, affiliation, sender);
  }

  @Override
  public List<MakerDue> makerDueSignals(BranchId branch, RevisionId revision) {
    List<SdQueryService.DecisionMakerInfo> infos =
        new SdQueryService(query).listDecisionMakers(SdQueryService.ALL, target(branch, revision));
    List<MakerDue> out = new ArrayList<>(infos.size());
    for (SdQueryService.DecisionMakerInfo info : infos) {
      DecisionMaker maker = info.maker();
      SdQueryService.PendingSignal pending = info.pending();
      out.add(
          new MakerDue(
              maker.id().value(),
              maker.providerId().orElse(null),
              maker.decisionCadenceTicks(),
              pending.due(),
              pending.lastDirectiveTick(),
              pending.ticksSinceLast(),
              new ArrayList<>(maker.allowedTools())));
    }
    return out;
  }

  /** ga 的窗口 → app 的窗口（逐字段同形；三条边界断言在两边都成立，转换不改变任何判定）。 */
  private static RedactingQueryService.DecisionResultWindow windowOf(Window window) {
    return new RedactingQueryService.DecisionResultWindow(
        window.tick(), window.fromTick(), window.toTick(), window.limit());
  }
}
