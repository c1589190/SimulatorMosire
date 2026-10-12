package io.mosire.simos.ga.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.ga.spi.GaPayloads;
import io.mosire.simos.util.json.SimosObjectMapper;

/**
 * 效果（ECA 规则）命令载荷里 <b>{@code kind} / {@code trigger} / {@code action} 三字段的唯一拼写点</b>（2026-10-12 批次
 * 3b-4 自 {@code io.mosire.simos.ga.spi.GaPayloads} 的三条同名方法<b>逐字搬来</b>）。
 *
 * <p>★★ <b>为什么必须搬</b>：这三个方法返回的类型（{@link EffectKind} / {@link Trigger} / {@link Action}）随 {@code
 * effects} 整族迁进本包 ⇒ 留在 {@code GaPayloads}（sd）里会立刻造出 {@code sd → ga} 的禁边（enforcer 建图期直接拒）。
 * 搬的是<b>同一条判据、同一段文案</b>，不是"第二份解析"。
 *
 * <p>★ <b>通用部分仍委托 {@link GaPayloads}</b>（{@code requireText} 这些"读一个字符串字段"的判据全仓只有一份；ga 侧的其余 handler
 * 也走同一处，见 {@code IssueDirectiveHandler}）——本类只管效果那三个字段的形状与类型。★ 失败一律以带可读中文原因的 {@link
 * IllegalArgumentException} 面世，handler 在命令边界把它折成 {@code HandlerOutcome.Rejected}（理由进 {@code
 * simos.command.rejected} 事件，拒绝不留 revision）——与搬迁前逐字同口径。
 */
public final class EffectPayloads {

  /** 与 {@code GaPayloads} 同一台基座（{@code Trigger}/{@code Action} 的多态注解钉在类型上，故无需模块 mixin）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private EffectPayloads() {}

  /** {@code kind} 必须是五个枚举名之一（大小写敏感；非法值 ⇒ 具名拒）。 */
  public static EffectKind requireEffectKind(JsonNode payload, String field) {
    String text = GaPayloads.requireText(payload, field);
    try {
      return EffectKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("effect kind 非法: " + text);
    }
  }

  /** {@code trigger} 必须是一个对象，按 {@link Trigger} 的 {@code @class} 多态解析。 */
  public static Trigger requireTrigger(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是条件对象: " + payload);
    }
    return MAPPER.convertValue(value, Trigger.class);
  }

  /** {@code action} 必须是一个对象，按 {@link Action} 的 {@code @class} 多态解析。 */
  public static Action requireAction(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是动作对象: " + payload);
    }
    return MAPPER.convertValue(value, Action.class);
  }
}
