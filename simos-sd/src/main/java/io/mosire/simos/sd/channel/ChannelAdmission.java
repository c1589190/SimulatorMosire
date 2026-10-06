package io.mosire.simos.sd.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * 决策渠道的**模块侧强制**（spec §十三.2，N16/N17）：身份校验、落点校验、按 actor 构造脱敏简报。
 *
 * <p>★ **自定义渠道是攻击面**：渠道自己声明 {@code representableActors()} 不算数——actor 是否属于该集合**由这里判**（N16）；视图**由这里按
 * actor 的 {@code accessLimit} 构造**，渠道拿不到全量（N17）。这三条是**安全边界**，不是风格。
 */
public final class ChannelAdmission {

  /** 决策唯一允许的两条落点（R9）。 */
  public static final Set<String> LANDING_POINTS = Set.of("sd.IssueDirective", "sd.SubmitVerdict");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final Logger LOG = SdLog.decision();

  private ChannelAdmission() {}

  /** N16：actor 必须落在渠道声明的集合里；否则拒（模块侧判定，渠道自己说"可以"不算）。 */
  public static void requireRepresentable(Set<ActorId> declared, ActorId actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor 不得为 null");
    }
    if (declared == null || !declared.contains(actor)) {
      // ★ 2026-10-23 用户裁定 A：具名拒绝在 sd 侧补一条 INFO（只加日志、不改判定）。
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "SD_CHANNEL_ADMISSION_REJECTED",
                  SdLogSource.SD_DECISION,
                  "actor",
                  actor.value(),
                  "commandType",
                  "-",
                  "reason",
                  "actor-not-representable"));
      throw new IllegalArgumentException("actor 不在该渠道声明可代表的集合里（N16）: " + actor.value());
    }
  }

  /** R9：决策只能落在两条窄工具上，不得经渠道提交任意命令。 */
  public static void requireLandingPoint(String commandType) {
    if (!LANDING_POINTS.contains(commandType)) {
      // ★ 2026-10-23 用户裁定 A：具名拒绝在 sd 侧补一条 INFO（只加日志、不改判定）。
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "SD_CHANNEL_ADMISSION_REJECTED",
                  SdLogSource.SD_DECISION,
                  "actor",
                  "-",
                  "commandType",
                  commandType == null ? "-" : commandType,
                  "reason",
                  "landing-point-not-allowed"));
      throw new IllegalArgumentException(
          "决策只能落在 sd.IssueDirective / sd.SubmitVerdict（R9）: " + commandType);
    }
  }

  /** actor 的**额外限制**（actor = {@code DecisionMakerId} 裸值）；不存在 ⇒ 空。 */
  public static Optional<AccessLimit> accessLimitOf(SdState state, ActorId actor) {
    DecisionMaker maker = state.decisionMakers().get(new DecisionMakerId(actor.value()));
    return maker == null ? Optional.empty() : Optional.of(maker.accessLimit());
  }

  /**
   * 按 actor 的 {@code accessLimit} 构造**脱敏简报**（N17）：只列可观察项，**不生成**"未探测到 X"的否定式条目。
   *
   * <p>★ 渠道**拿不到全量状态**——它只能拿到这段简报；两个 actor 的简报**不同**（限制不同则简报不同）。
   *
   * <p>★★ **简报的内容范围随 T9 收窄了，这是语义变更不是删字段**：旧简报列的是 actor 的**绝对可见集合** （{@code visibleRegions}/{@code
   * visibleUnits}），因为那时可见性就是 GM 存的那些集合。新语义下可见性由 **app 层的范围函数现算**（spec §3.1）——sd **看不见**范围函数（它在 app
   * 层，要读地图与单位状态），故 sd 能如实报出的只有 **GM 配的那一层限制**本身：前缀图 + 字段级剔除 +
   * 判决披露档。把它们冒充成"你能看见这些"就会**撒谎**（限制是交集的一半， 另一半在 app 层）。⇒ 简报键名相应改为描述"限制"而不是"可见集合"。
   *
   * <p>★ 本方法**无生产调用者**（spec §1.3 第 3 条自陈的 N17 缺口在 T9 时仍未接），改动只影响它自己的用例。
   */
  public static String redactedBrief(SdState state, ActorId actor) {
    AccessLimit limit = accessLimitOf(state, actor).orElse(AccessLimit.empty());
    Map<String, Object> brief = new LinkedHashMap<>();
    Map<String, Object> prefixes = new LinkedHashMap<>();
    for (var entry : limit.prefixesByNamespace().entrySet()) {
      prefixes.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    List<String> redacted = new ArrayList<>(limit.redactedFields());
    brief.put("accessLimit", prefixes);
    brief.put("redactedFields", redacted);
    brief.put("adjudicationDisclosure", limit.adjudicationDisclosure().name());
    try {
      return MAPPER.writeValueAsString(brief);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("脱敏简报序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
