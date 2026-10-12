package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.SetGovernmentMintScale}（W5-fix6，2026-10-11）</b>：GM 把<b>某一个已登记的政府</b>的铸币政策旋钮
 * （{@link Government#mintScalePerCycle()}）改成给定值。
 *
 * <pre>{@code
 * {"governmentId":"world-silver","mintScalePerCycle":300}   // 精确政府 id（世界级政府走这一路）
 * {"govUnitId":"gov-central","mintScalePerCycle":300}       // GOV 单位 id ⇒ 派生 gov-unit-gov-central
 * }</pre>
 *
 * <p>★★ <b>它为什么存在（W5 验收报告 §3-D1 的直接后果）</b>：旋钮是"这个政府本周期最多铸多少毫"的<b>唯一闸门</b> （{@code MintSettlement}
 * 逐轮现读），而改前唯一能写它的命令 {@code economy.RegisterGovernment} 只写 <b>{@code gov-unit-<govUnitId>}</b>
 * 形态的政府行（{@code GovernmentIds.ofUnit}）—— 小世界里<b>唯一会铸币的政府</b>是世界级 {@code world-silver}（{@code
 * EconomySeeder.GENESIS_GOVERNMENT_ID}），用它去调那条命令会撞"同一家户不能同时是两届政府的国库"（HTTP 422 原文见报告）⇒
 * <b>政策旋钮在唯一会铸币的政府上够不着</b>。本命令就是那条缺的写口。
 *
 * <p>★★ <b>它写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code governments} 里<b>一个政府的一个字段</b>：国库、{@code issuable}、{@code
 *       mintedLastCyclePerCycle}（上一轮印量读数）、官方汇率、其余政府<b>一个数都不动</b>；
 *   <li><b>不设上下限</b>：除 {@code ≥ 0} 外没有任何夹取 —— 可以远大于出厂值（决策人预期即权威，用户 2026-10-11
 *       原话「按照决策人预期被发行就行」），也可以为 0（= 政策不许铸币，是<b>合法状态</b>而不是"缺省"：它表达"决策人关掉了铸币"）； ★ 负值具名拒 {@code
 *       bad-payload}（{@code Government} 构造期也拒负，这里先给具名理由）；
 *   <li><b>只作用于已登记的政府</b>：{@code governments} 里没有那把键 ⇒ 具名拒 {@code government-missing}（本命令<b>不</b>凭空
 *       建政府 —— 建政府要同时给国库家户/单位/发钞权，那是 {@code economy.RegisterGovernment} 的判据）；
 *   <li>与现值相同 ⇒ 具名拒 {@code mint-scale-unchanged}（照 {@code economy.SetMarketZonePriceAlpha} / {@code
 *       economy.SetMarketNumeraire} 的幂等口径：没变化就不落 revision）；
 *   <li>★ <b>旧形态不受影响</b>：本命令是<b>新增</b>的一条写口，{@code economy.RegisterGovernment} 的 {@code govUnitId}
 *       语义与守卫一字不动（同一世界仍能建/改 {@code gov-unit-*} 政府）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令不打 {@code DmUsableCommand}（照 {@code economy.SetMarketZonePriceAlpha}）——
 * 它改的是<b>发钞规模</b>这条 政策面，不进决策人令（{@code sd.IssueDirective} / {@code AdjudicateTick} 的 directive
 * 载荷），只走 GM 的 {@code simos.command.submit} 与窄工具 {@code simos.economy.setGovernmentMintScale}。
 *
 * <p>★★ <b>铁律 5（零形状改动 + 往返）</b>：本写口<b>不新增/不删除任何持久组件</b> —— 它只改既有的 {@code
 * government.mintScalePerCycle} 这<b>一个字段的值</b>，形状（{@code Government} 的组件表、{@code EconomyData} 的
 * {@code governments} 组件、{@code EconomyChangeSet.governments} 的增量形态）逐字不变 ⇒ 不存在"派生不完整"的面。
 * 往返由同一条既有通道保证：{@code base.withGovernments(...)} → {@code EconomyChangeSet.between}（{@code
 * FieldDelta.diff} 按 record 相等判变化）→ Codec（Jackson record 绑定）三件套本来就是这一字段的既有三件套 （{@code
 * MintSettlement.writeLastCycleReadout} 与 {@code economy.RegisterGovernment} 已在写它）。
 */
public final class EconomySetGovernmentMintScaleHandler implements CommandHandler {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.SetGovernmentMintScale";

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
    String addressText = "";
    String scaleText = "";
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      addressText = addressText(payload);
      long mintScalePerCycle =
          EconomyCommandPayloads.requireLong(TYPE, payload, "mintScalePerCycle");
      scaleText = Long.toString(mintScalePerCycle);
      GovernmentId governmentId = EconomyCommandPayloads.requireGovernmentAddress(TYPE, payload);
      if (mintScalePerCycle < 0L) {
        return reject(
            addressText,
            scaleText,
            "bad-payload",
            "mintScalePerCycle 不得为负（0 = 政策不许铸币，是合法状态；没有\"负的铸币规模\"）: " + mintScalePerCycle);
      }
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return reject(
            addressText,
            scaleText,
            "government-missing",
            "该政府未登记（本命令只改已登记的政府；要建政府请走 economy.RegisterGovernment）: " + governmentId.value());
      }
      if (government.mintScalePerCycle() == mintScalePerCycle) {
        return reject(addressText, scaleText, "mint-scale-unchanged", "该政府的铸币规模已经是它，没变化");
      }
      Map<GovernmentId, Government> governments = new LinkedHashMap<>(base.governments());
      governments.put(governmentId, government.withMintScalePerCycle(mintScalePerCycle));
      // ★ 只碰政府表这一个组件：账户/债务/发行审计/产业/市场表一个数都不动（canonical 构造器与全部守卫照跑）。
      EconomyData projected = base.withGovernments(governments);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "GOVERNMENT_MINT_SCALE_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "government",
                  governmentId.value(),
                  "from",
                  government.mintScalePerCycle(),
                  "to",
                  mintScalePerCycle,
                  "issuable",
                  government.issuable().toString(),
                  "treasury",
                  government.treasury().kind() + "/" + government.treasury().id(),
                  "governments",
                  projected.governments().size(),
                  "effectiveFrom",
                  "next-mint-cycle-close",
                  "note",
                  "铸币政策旋钮 = 本政府每个产业大周期最多铸多少毫（实铸 = min(产出请求, 它)；0 = 不许铸）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "GOVERNMENT_MINT_SCALE_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "government",
                    governmentId.value(),
                    "previousMintScalePerCycle",
                    government.mintScalePerCycle(),
                    "newMintScalePerCycle",
                    mintScalePerCycle,
                    "mintedLastCyclePerCycle",
                    government.mintedLastCyclePerCycle().toString(),
                    "debtContracts",
                    projected.debtContracts().size(),
                    "why",
                    "mint-scale-is-the-only-gate-user-2026-10-11-issue-as-the-decider-expects"));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject(
          addressText, scaleText, "bad-payload", EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /**
   * 地址的日志文本（只读、不判形状）：{@code governmentId} 优先，否则回显 {@code govUnitId}；都没有 ⇒ 空串 （具名拒由 {@link
   * EconomyCommandPayloads#requireGovernmentAddress} 给出）。
   */
  private static String addressText(JsonNode payload) {
    if (payload.hasNonNull("governmentId")) {
      return payload.get("governmentId").asText();
    }
    if (payload.hasNonNull("govUnitId")) {
      return "gov-unit-" + payload.get("govUnitId").asText();
    }
    return "";
  }

  /** 具名拒（业务拒绝 = INFO；字段级细节 = DEBUG）。 */
  private static HandlerOutcome reject(String address, String scale, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOVERNMENT_MINT_SCALE_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "government",
                address,
                "mintScalePerCycle",
                scale,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
