package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★★ <b>{@code economy.SetEconomyPolicy}（W10，2026-10-11）</b>：GM 把一个<b>经济侧行为值</b>写成可运行期生效的政策预设。
 *
 * <p>★★ <b>它为什么存在</b>（用户 2026-09-25 裁定第 18 条：「凡"行为"一律做成 GM 可调的预设」）：这些值改前是编译期常量 （{@code public
 * static final long}）—— 要改就得重编译、重播种，而它们恰恰是 W6 标定批交给用户拍板的旋钮。本命令是它们的 <b>唯一写口</b>（形制照 {@code
 * economy.SetGovernmentMintScale}：窄工具 {@code simos.economy.setEconomyPolicy}）。
 *
 * <pre>
 * 载荷：{"key": "SLIDE_SPEED_PER_MILLE_PER_RATIO", "valueMilli": 200}
 *       key        = {@link EconomyPolicyKey} 的稳定枚举名（大小写敏感；未知 ⇒ 具名拒 unknown-key）
 *       valueMilli = 该键的新值（整数；越域 ⇒ 具名拒 bad-range；与现值相同 ⇒ 具名拒 unchanged）
 * </pre>
 *
 * <p>★★ <b>铁律 5（零形状改动 + 往返）</b>：本写口<b>不新增/不删除任何持久组件</b> —— 它只改既有的 {@code
 * economy.meta.economyPolicies} 这张表里的<b>一个键</b>。形状（{@code EconomyMeta} 的组件表、{@code EconomyData} 的
 * {@code meta} 组件、{@code EconomyChangeSet.meta} 的单键表投影）逐字不变 ⇒ 不存在"派生不完整"的面。往返由同一条既有通道保证： {@code
 * base.withMeta(...)} → {@code EconomyChangeSet.between}（{@code FieldDelta.diff} 按 record 相等判变化）→
 * Codec（Jackson record 绑定）。
 *
 * <p>★ <b>写下与缺省相同的值 = 删掉这一行</b>（{@link EconomyMeta#withPolicy}）：政策表里不留"等于现值的冗余行"。
 *
 * <p>★ <b>未激活的世界</b>（{@code meta} 为空）没有政策宿主 ⇒ 具名拒 {@code not-activated}（不静默建一个 meta：那是另一件事）。
 */
public final class EconomySetEconomyPolicyHandler implements CommandHandler {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.SetEconomyPolicy";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    String keyText = "";
    String valueText = "";
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      keyText = payload.path("key").asText("");
      valueText = payload.path("valueMilli").asText("");
      String keyName = EconomyCommandPayloads.requireText(TYPE, payload, "key");
      long value = EconomyCommandPayloads.requireLong(TYPE, payload, "valueMilli");
      EconomyPolicyKey key;
      try {
        key = EconomyPolicyKey.valueOf(keyName);
      } catch (IllegalArgumentException unknown) {
        return reject(keyText, valueText, "unknown-key", "未知的政策键（用枚举名）: " + keyName);
      }
      if (!key.accepts(value)) {
        return reject(
            keyText,
            valueText,
            "bad-range",
            "政策值越域："
                + key.name()
                + " = "
                + value
                + "（有效域 ["
                + key.minValue()
                + ", "
                + key.maxValue()
                + "]；"
                + key.description()
                + "）");
      }
      Optional<EconomyMeta> meta = base.meta();
      if (meta.isEmpty()) {
        return reject(keyText, valueText, "not-activated", "经济切片未激活（没有 meta ⇒ 没有政策宿主）");
      }
      long current = meta.get().policyOf(key);
      if (current == value) {
        return reject(keyText, valueText, "unchanged", "该政策键已经是这个值，没变化: " + key.name());
      }
      EconomyMeta next = meta.get().withPolicy(key, value);
      EconomyData projected = base.withMeta(Optional.of(next));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_POLICY_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "key",
                  key.name(),
                  "from",
                  current,
                  "to",
                  value,
                  "default",
                  key.defaultValue(),
                  "min",
                  key.minValue(),
                  "max",
                  key.maxValue(),
                  "configuredKeys",
                  projected.meta().map(m -> m.economyPolicies().size()).orElse(0),
                  "effectiveFrom",
                  "same-revision",
                  "note",
                  "行为值政策预设：写入即生效（读取点每 tick 从政策表读，不再读编译期常量）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ECONOMY_POLICY_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "key",
                    key.name(),
                    "description",
                    key.description(),
                    "previousValue",
                    current,
                    "writtenValue",
                    value,
                    "isDefaultAfterWrite",
                    value == key.defaultValue(),
                    "note",
                    "写回缺省值 ⇒ 该行从政策表里删掉（缺键 = 缺省）"));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException badPayload) {
      return reject(keyText, valueText, "bad-payload", String.valueOf(badPayload.getMessage()));
    }
  }

  private static HandlerOutcome reject(String key, String value, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_POLICY_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "key",
                key,
                "valueMilli",
                value,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(TYPE + " 被拒（" + reason + "）：" + detail);
  }
}
