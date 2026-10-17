package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.SetPlanSources}（D-032 B3，裁定十一 + 裁定十二）</b>：GM 声明/清除<b>某个生产方案</b>在 economy
 * 侧的<b>两条经济关系</b> —— 谁提供闲置份额、劳动由谁出。
 *
 * <p>★★ <b>它为什么存在</b>（裁定十一原文取 (a)）：候选面（{@code ProductionCandidate}）退役时，B2c 把 {@code assetSource}
 * 的宿主搬进了 {@code EconomyMeta.planAssetSources}、读点也接好了，但**写面随 {@code upsertCandidate} kind 一起被删掉了** ⇒
 * 那张表恒空、"借用外部闲置份额"这条腿不可达。裁定的理由是：它是<b>经济关系</b> （谁提供闲置份额）⇒ 归 economy；而"并入 {@code
 * production.UpsertIndustry} 双写"= production 的命令写 economy 状态（违反铁律 3 / I1）；"认可退役"= 让一项能力因删了旧写面而静默消失。
 *
 * <p>★★ <b>裁定十二（劳动来源）：逐值不变</b>。旧口径的声明面是候选的 {@code laborSource} 字段（缺省 {@link LaborSource#SELF}）；B2c
 * 把它改成 {@code RegimeRelations.laborSourceFor(regime)} ⇒ <b>非 SELF 制度下与旧口径
 * 不同</b>（那是提议的行为变更，已另立项，<b>不在本批</b>）。本命令把声明搬进 economy 的声明面 （{@code
 * EconomyMeta.planLaborSources}），读点缺键 ⇒ {@code SELF} ⇒ <b>逐值等于改动前</b>（I14）。
 *
 * <pre>
 * 载荷：{"plan": "&lt;IndustryId&gt;",
 *        "assetSource": {"kind":"HOUSEHOLD","id":"h-1"} | null,   // 缺键 = 不动这一维；null = 清除（回缺省）
 *        "laborSource": "TENANT" | null}                          // 同上
 * </pre>
 *
 * <ul>
 *   <li>{@code plan}：方案稳定 id。<b>必须是不绑地点的方案 id</b>（{@code hexKeyOf(id)} 为空）—— 带格段的是"某格已物化
 *       的定义"，声明挂在它上面永远不会被进入算法读到 ⇒ 具名拒 {@code plan-not-a-plan}（不留"写进去、无人读"的静默失效）。
 *   <li>两条声明各<b>至少要给一条</b>（都不给 ⇒ 具名拒 {@code no-declaration}）；<b>缺键</b> = 不动这一维，<b>显式 {@code
 *       null}</b> = 清除该维（回缺省）。语义与 {@code EconomyGmAdjustments} 关系覆写的"显式 null ⇒ 回缺省"同制。
 *   <li>{@code assetSource} = {@code {kind,id}} 主体；{@code laborSource} = {@link LaborSource}
 *       词表名（大小写敏感）。
 *   <li>写成缺省值（无来源 / {@code SELF}）⇒ <b>该行从表里删掉</b>（缺键 = 缺省，不留冗余行，与政策表同制）。
 *   <li>整条命令<b>没产生任何变化</b> ⇒ 具名拒 {@code unchanged}（不落空 revision）。
 *   <li>★ <b>不做版本、不做窗口</b>（裁定十一原话）：声明就是"当刻起生效"，读取点每次进入评估时现读。
 * </ul>
 *
 * <p>★★ <b>铁律 5</b>：本写口只改既有的 {@code economy.meta} 的<b>两个键</b>（{@code planAssetSources} / {@code
 * planLaborSources}）—— 不新增/不删除任何持久组件；形状、往返、Codec 全部走同一条既有通道 （{@code base.withMeta(...)} → {@code
 * EconomyChangeSet.between} 的 meta 单键表投影 → Jackson record 绑定）。
 *
 * <p>★ <b>未激活的世界</b>（{@code meta} 为空）没有声明宿主 ⇒ 具名拒 {@code not-activated}（不静默建一个 meta）。 ★
 * <b>GM-only</b>：本 handler 不打 {@code DmUsableCommand}（反默认 = 仅 GM）。
 */
public final class EconomySetPlanSourcesHandler implements CommandHandler {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册、catalog 与窄工具都取它）。 */
  public static final String TYPE = "economy.SetPlanSources";

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
    String planText = "";
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      planText = payload.path("plan").asText("");
      boolean setAsset = payload.has("assetSource");
      boolean setLabor = payload.has("laborSource");
      if (!setAsset && !setLabor) {
        return reject(planText, "no-declaration", "两条声明至少要给一条：assetSource / laborSource");
      }
      String planName = EconomyCommandPayloads.requireText(TYPE, payload, "plan");
      IndustryId plan;
      try {
        plan = IndustryId.parse(planName);
      } catch (IllegalArgumentException badPlan) {
        return reject(planText, "bad-payload", "方案 id 非法: " + badPlan.getMessage());
      }
      if (IndustryHexKeys.hexKeyOf(plan).isPresent()) {
        // ★ 声明按**不绑地点的方案 id** 取（进入算法的方案池 = hexKeyOf(id) 为空者）⇒ 带格段的 id 写进来永远不会被读到，
        //   那是"写进去、无人读"的静默失效形态（本仓最禁止的一类）⇒ 具名拒并指路。
        return reject(
            planText,
            "plan-not-a-plan",
            "本命令只声明**不绑地点的方案**（进入算法的方案池 = hexKeyOf(id) 为空的定义）；"
                + "带格段的 id 是某格已物化的定义，声明挂在它上面永远读不到: "
                + plan.value());
      }
      Optional<EconomyMeta> meta = base.meta();
      if (meta.isEmpty()) {
        return reject(planText, "not-activated", "经济切片未激活（没有 meta ⇒ 没有声明宿主）");
      }
      EconomyMeta current = meta.get();
      Optional<ActorRef> assetSource = Optional.empty();
      Optional<LaborSource> laborSource = Optional.empty();
      boolean assetChanged = false;
      boolean laborChanged = false;
      if (setAsset) {
        assetSource = EconomyCommandPayloads.optionalActor(TYPE, payload, "assetSource");
        assetChanged =
            !Objects.equals(current.planAssetSources().get(plan), assetSource.orElse(null));
      }
      if (setLabor) {
        JsonNode node = payload.get("laborSource");
        if (node != null && !node.isNull()) {
          if (!node.isTextual() || node.asText().isBlank()) {
            return reject(planText, "bad-payload", "laborSource 必须是文本（或 null = 清除）: " + node);
          }
          try {
            laborSource = Optional.of(LaborSource.parse(node.asText()));
          } catch (IllegalArgumentException badSource) {
            return reject(planText, "bad-payload", String.valueOf(badSource.getMessage()));
          }
        }
        laborChanged =
            current.laborSourceOf(plan) != laborSource.orElse(LaborSource.SELF)
                || current.planLaborSources().containsKey(plan) != laborSource.isPresent();
      }
      if (!assetChanged && !laborChanged) {
        return reject(planText, "unchanged", "该方案的声明已经是这个样子，没变化: " + plan.value());
      }
      EconomyMeta next = current;
      if (setAsset) {
        next = next.withPlanAssetSource(plan, assetSource);
      }
      if (setLabor) {
        next = next.withPlanLaborSource(plan, laborSource);
      }
      EconomyData projected = base.withMeta(Optional.of(next));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_PLAN_SOURCES_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "plan",
                  plan.value(),
                  "assetSourceSet",
                  setAsset,
                  "assetSource",
                  assetSource.map(Object::toString).orElse("<cleared>"),
                  "laborSourceSet",
                  setLabor,
                  "laborSource",
                  setLabor ? laborSource.map(Enum::name).orElse("<cleared>") : "<untouched>",
                  "effectiveLaborSource",
                  next.laborSourceOf(plan).name(),
                  "assetSourceRows",
                  next.planAssetSources().size(),
                  "laborSourceRows",
                  next.planLaborSources().size(),
                  "effectiveFrom",
                  "same-revision",
                  "note",
                  "经济关系声明：缺键 = 只认自有份额 / SELF（空表逐值等于改动前）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ECONOMY_PLAN_SOURCES_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "plan",
                    plan.value(),
                    "assetSourceClearedToDefault",
                    setAsset && assetSource.isEmpty(),
                    "laborSourceClearedToDefault",
                    setLabor && (laborSource.isEmpty() || laborSource.get() == LaborSource.SELF),
                    "note",
                    "写成缺省值 ⇒ 该行从声明表里删掉（缺键 = 缺省）"));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException badPayload) {
      return reject(planText, "bad-payload", String.valueOf(badPayload.getMessage()));
    }
  }

  private static HandlerOutcome reject(String plan, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_PLAN_SOURCES_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "plan",
                plan,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(TYPE + " 被拒（" + reason + "）：" + detail);
  }
}
