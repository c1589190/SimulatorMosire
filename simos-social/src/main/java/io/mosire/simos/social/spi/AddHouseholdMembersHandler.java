package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.AddHouseholdMembers} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：给家户新增一个成员批次。
 *
 * <pre>{@code
 * {"householdId":"hh-1","lotId"?: "hh-1-m", "sex":"MALE","count":120,
 *  "ageAtAnchorDays"?: 7200, "anchorTick"?: 30, "reason":"入籍"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code lotId} 可缺省——缺省时用 {@link #deriveLotId} 生成确定性的新批次 id（同一 base 状态 + 同一家户
 * ⇒ 同一 id；app 工具预览与命令落盘共用这一份规则）；{@code count} &gt; 0；{@code ageAtAnchorDays} 缺省 0；{@code
 * anchorTick} 缺省 = 当前世界日（{@code state.meta().timestamp().tick()}）。批次 id 已存在 ⇒ 具名拒（不做静默合并）。
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#addMembers}；落一条 {@code GM_ADJUST} 事件（可回放），守恒检查在域层收口。
 */
public final class AddHouseholdMembersHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.AddHouseholdMembers";

  /** 缺省批次 id 的前缀（确定性的唯一拼写点：{@code gm-add:<householdId>}；冲突时追加 {@code -2}/-3…）。 */
  private static final String DEFAULT_LOT_PREFIX = "gm-add:";

  /** 2026-10-20：目标 = 家户 SocialData 当前位置（HEX ⇒ social / UNIT ⇒ unit）；家户查无 ⇒ 具名拒。 */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HouseholdId id = SocialPayloads.requireHouseholdId(payload, "householdId");
    return List.of(HouseholdCommandTargets.currentTarget(state, id));
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      HouseholdId id = SocialPayloads.requireHouseholdId(payload, "householdId");
      String lotIdText = SocialPayloads.optionalText(payload, "lotId");
      PeopleLotId lotId = lotIdText == null ? deriveLotId(base, id) : PeopleLotId.parse(lotIdText);
      Sex sex = SocialPayloads.requireSex(payload, "sex");
      long count = SocialPayloads.requireLong(payload, "count");
      Long ageAtAnchorDays = SocialPayloads.optionalLong(payload, "ageAtAnchorDays");
      Long anchorTick = SocialPayloads.optionalLong(payload, "anchorTick");
      long anchor = anchorTick == null ? state.meta().timestamp().tick() : anchorTick;
      String reason = SocialPayloads.requireReason(payload);
      SocialData next =
          HouseholdBook.addMembers(
              base,
              id,
              lotId,
              sex,
              count,
              ageAtAnchorDays == null ? 0L : ageAtAnchorDays,
              anchor,
              reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 可缺省 {@code lotId} 的确定性生成规则：前缀 {@code gm-add:<householdId>}；已存在则依次尝试 {@code -2}/{@code -3}…。
   *
   * <p>★ <b>为什么确定性</b>：同一条命令的载荷（工具预览/重放）在同一 base 状态上必须得到同一个 id；随机 UUID 会让"预览看到的批次"
   * 与"落盘的批次"不是同一个身份。★ app 工具在预览时调用本方法，落盘时把结果原样写进 {@code lotId} 字段（命令侧不再重新生成）。
   */
  public static PeopleLotId deriveLotId(SocialData base, HouseholdId householdId) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(householdId, "householdId");
    String prefix = DEFAULT_LOT_PREFIX + householdId.value();
    PeopleLotId candidate = PeopleLotId.parse(prefix);
    if (!base.groups().containsKey(candidate)) {
      return candidate;
    }
    for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
      candidate = PeopleLotId.parse(prefix + "-" + suffix);
      if (!base.groups().containsKey(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("确定性批次 id 后缀已用尽: " + prefix);
  }
}
