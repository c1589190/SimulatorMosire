package io.mosire.simos.sd.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 决策渠道的**模块侧强制**（spec §十三.2，N16/N17）：身份校验、落点校验、按 actor 构造脱敏简报。
 *
 * <p>★ **自定义渠道是攻击面**：渠道自己声明 {@code representableActors()} 不算数——actor 是否属于该集合**由这里判**（N16）；视图**由这里按
 * actor 的 {@code viewScope} 构造**，渠道拿不到全量（N17）。这三条是**安全边界**，不是风格。
 */
public final class ChannelAdmission {

  /** 决策唯一允许的两条落点（R9）。 */
  public static final Set<String> LANDING_POINTS = Set.of("sd.IssueDirective", "sd.SubmitVerdict");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private ChannelAdmission() {}

  /** N16：actor 必须落在渠道声明的集合里；否则拒（模块侧判定，渠道自己说"可以"不算）。 */
  public static void requireRepresentable(Set<ActorId> declared, ActorId actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor 不得为 null");
    }
    if (declared == null || !declared.contains(actor)) {
      throw new IllegalArgumentException("actor 不在该渠道声明可代表的集合里（N16）: " + actor.value());
    }
  }

  /** R9：决策只能落在两条窄工具上，不得经渠道提交任意命令。 */
  public static void requireLandingPoint(String commandType) {
    if (!LANDING_POINTS.contains(commandType)) {
      throw new IllegalArgumentException(
          "决策只能落在 sd.IssueDirective / sd.SubmitVerdict（R9）: " + commandType);
    }
  }

  /** actor 的可见范围（actor = {@code DecisionMakerId} 裸值）；不存在 ⇒ 空。 */
  public static Optional<ViewScope> viewScopeOf(SdState state, ActorId actor) {
    DecisionMaker maker = state.decisionMakers().get(new DecisionMakerId(actor.value()));
    return maker == null ? Optional.empty() : Optional.of(maker.viewScope());
  }

  /**
   * 按 actor 的 {@code viewScope} 构造**脱敏简报**（N17）：只列可观察项，**不生成**"未探测到 X"的否定式条目。
   *
   * <p>★ 渠道**拿不到全量状态**——它只能拿到这段简报；两 scope 的 actor 得到的简报**不同**。
   */
  public static String redactedBrief(SdState state, ActorId actor) {
    ViewScope scope = viewScopeOf(state, actor).orElse(ViewScope.empty());
    Map<String, Object> brief = new LinkedHashMap<>();
    List<String> regions = new ArrayList<>();
    for (var region : scope.visibleRegions()) {
      regions.add(region.value());
    }
    List<String> units = new ArrayList<>();
    for (var unit : scope.visibleUnits()) {
      units.add(unit.value());
    }
    brief.put("visibleRegions", regions);
    brief.put("visibleUnits", units);
    brief.put("seeOwnUnits", scope.seeOwnUnits());
    brief.put("adjudicationDisclosure", scope.adjudicationDisclosure().name());
    try {
      return MAPPER.writeValueAsString(brief);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("脱敏简报序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
