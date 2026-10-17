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

  /**
   * ★★★ <b>I12 收口后的具名拒绝理由（B2 批）</b>：GM 想用本命令改铸币产出率时的稳定短 id。
   *
   * <p>铸币产出率的唯一权威自 B2 起在 production 的产业定义（{@code IndustryDefinitionView.moneyOutputPerUnit}）， 写
   * economy 的政策键不会改变任何行为 ⇒ 本命令对该键<b>具名拒</b>并指路真入口 （{@code production.UpsertIndustry} / {@code
   * simos.economy.upsertIndustry} 的 {@code moneyOutputPerUnit}），而不是留一个"写进去、无人读"的静默失效旋钮。
   */
  public static final String MINT_RATE_MOVED_REASON = "mint-rate-moved-to-production-definition";

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
      if (key == EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE) {
        // ★★★ I12 / §13.10.2 更正一 / §13.10.4 第 6 条（B2 批）：本键的**运行期读取点已随 B2 退役**
        //   （`MintSettlement` 不再读它 —— 铸币产出率的唯一权威改为 production 的有效参数
        //   `IndustryDefinitionView.moneyOutputPerUnit`）。⇒ 继续让它"写进去、无人读"就是**静默失效**，
        //   本仓最禁止的形态之一（表里有值、行为不变）。这里**具名拒**并把 GM 指到真入口，
        //   而不是删掉提示再宣称收口。
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_POLICY_MISROUTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "key",
                    key.name(),
                    "reason",
                    MINT_RATE_MOVED_REASON,
                    "redirectCommand",
                    "production.UpsertIndustry",
                    "redirectTool",
                    "simos.economy.upsertIndustry",
                    "redirectField",
                    "moneyOutputPerUnit",
                    "note",
                    "铸币产出率的权威在 production 的产业定义；政策键不再是读取点"));
        return reject(
            keyText,
            valueText,
            MINT_RATE_MOVED_REASON,
            "铸币产出率已收口到 production 的产业定义（I12）：本政策键在 B2 批起**不再是读取点**，"
                + "写它不会改变任何行为。改走 production 的定义写面 —— 命令 production.UpsertIndustry / "
                + "工具 simos.economy.upsertIndustry 的 moneyOutputPerUnit 字段（GM-only）。"
                + "★ 该定义写面对已被引用的产业只允许发新版本 id（kind_v2@q_r）；"
                + "运行中产业【原地改产出率】的便捷入口按计划留 U10 后续批次（能力已具名，未实现）。");
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
