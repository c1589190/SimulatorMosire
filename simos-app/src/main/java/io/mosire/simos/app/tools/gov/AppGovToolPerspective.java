package io.mosire.simos.app.tools.gov;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.access.AppDecisionMakerAffiliations;
import io.mosire.simos.app.access.AppGovAffiliations;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.time.GovernmentServiceDesertionBridge;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.Directive;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.gov.tools.GovToolPerspective;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link GovToolPerspective} 的宿主实现（组合根）：**逐条转调既有唯一实现** —— 身份派生（{@code DecisionCallerFactory}）、
 * 国库家户解析（{@code GovernmentHouseholdResolver}）、区域围栏（{@code ToolSupport.regionVisible}）、逃亡档位 （{@code
 * GovernmentServiceDesertionBridge.rateTier}）与 sd 决策人只读事实。
 *
 * <p>★ <b>为什么 sd 事实要在这里读</b>：{@code simos-sd} 是 gov 的下游（gov 的 enforcer 禁列 + gov→sd 会让 sd→gov 成环）， 而
 * app 是唯一认识两边的地方。★ {@code affiliationText} 取 {@code affiliation().toString()} 的**原样文本**：既有拒因/告警文案
 * 里嵌了它，换一份渲染就是改对外文本（I-G1）。
 */
public final class AppGovToolPerspective implements GovToolPerspective {

  @Override
  public Optional<String> decisionMakerIdOf(AgentIdentity identity) {
    return DecisionCallerFactory.decisionMakerIdOf(identity);
  }

  @Override
  public GovDecisionMaker decisionMaker(SimulationState state, String decisionMakerId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    // ★ 非法 id 的拒因与文案来自 util.spi.DecisionMakerId 的构造（与 mig 入前逐字相同）。
    DecisionMaker maker = ToolSupport.gaState(state).decisionMakers().get(sdId(decisionMakerId));
    if (maker == null) {
      return new GovDecisionMaker(decisionMakerId, false, false, null, "");
    }
    return factOf(maker, state);
  }

  @Override
  public List<GovDecisionMaker> decisionMakers(SimulationState state) {
    Objects.requireNonNull(state, "state");
    List<GovDecisionMaker> facts = new ArrayList<>();
    for (DecisionMaker maker : ToolSupport.gaState(state).decisionMakers().values()) {
      facts.add(factOf(maker, state));
    }
    // ★ 迭代序 = sd 状态表自身的序（调用方各自按 id 稳定序排序；此处不重排，免得两处口径）。
    return Collections.unmodifiableList(facts);
  }

  @Override
  public HouseholdId governmentHousehold(Unit unit, String govUnitId) {
    return GovernmentHouseholdResolver.requireGovernmentHousehold(unit, govUnitId);
  }

  @Override
  public int rateTier(long fleeRatePerMille) {
    return GovernmentServiceDesertionBridge.rateTier(fleeRatePerMille);
  }

  @Override
  public boolean regionVisible(ToolContext context, String mapId, RegionId regionId) {
    return ToolSupport.regionVisible(context, mapId, regionId);
  }

  @Override
  public boolean nationTag(String tag) {
    return NationTag.isNationTag(tag);
  }

  @Override
  public List<String> directiveIdsReferencing(SimulationState state, String decisionMakerId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    DecisionMakerId id = sdId(decisionMakerId);
    List<String> ids = new ArrayList<>();
    for (Directive directive : ToolSupport.gaState(state).directives().values()) {
      if (directive.decisionMakerId().equals(id)) {
        ids.add(directive.id().value());
      }
    }
    Collections.sort(ids);
    return Collections.unmodifiableList(ids);
  }

  /**
   * ★★ 2026-10-12 乙收尾：归属从 {@code AppDecisionMakerAffiliations} 现取（各模块自报）。{@code affiliationText}
   * 逐字保留 收窄前 {@code Affiliation.toString()} 的渲染（**可能拼多条**：多轴归属时按登记序连接；裸决策人 ⇒ 空串），
   * 既有拒因/告警文案因此不会因为"归属换家"而改字。
   */
  private static GovDecisionMaker factOf(DecisionMaker maker, SimulationState state) {
    String govUnitId =
        AppGovAffiliations.govUnitOf(maker.id(), state).map(UnitId::value).orElse(null);
    return new GovDecisionMaker(
        maker.id().value(),
        true,
        govUnitId != null,
        govUnitId,
        AppDecisionMakerAffiliations.affiliationTextOf(maker.id(), state));
  }

  /** sd 的决策人 id 解析（非法 ⇒ {@code IllegalArgumentException}，与既有构造同一条拼写）。 */
  private static DecisionMakerId sdId(String decisionMakerId) {
    return new DecisionMakerId(decisionMakerId);
  }
}
