package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.UpsertIndustry}（Z1a，GM-only）：创建/修改产业模板</b>。
 *
 * <p>★★ <b>载荷与 {@code economy.Seed} 的 {@code industries[]} 节点逐字同形</b>（不另造字段口径）：
 *
 * <pre>{@code
 * {"id":"office@0_0",              // <kind>@<q>_<r>；新版本写进 kind：office_v2@0_0
 *  "name":"衙署",
 *  "regime":"government_office",
 *  "cycleDays":30,
 *  "capacityPerUnit":{"TOOL":1},  // 非空且逐值 > 0（"单位规模"的锚）
 *  "dailyInputPerUnit":{},        // 可选；缺省 = 空
 *  "dailyLaborPerUnit":0,         // 可选；缺省 0
 *  "laborPerUnit":16000,          // 可选；缺省 0
 *  "outputPerUnit":{},            // 可选；空 map = 无商品产出（服务产业）
 *  "cycleInputPerUnit":{},        // 可选
 *  "slots":[{"id":"...","name":"...","laborParticipationPerMille":1000}],
 *  "allocation":{"@class":"split","meansWeightPerMille":500,"laborWeightPerMille":500}}
 * }</pre>
 *
 * <p>★★ <b>语义（spec §4.2 / §12，冻结）</b>：
 *
 * <ul>
 *   <li><b>id 不存在 ⇒ 创建</b>：格必须已有已激活经济状态（与 Seed 追加播种同一判据）；同格同 base kind 的版本不得倒退、 不得撞既有版本。
 *   <li><b>id 已存在 ⇒ 原地全量替换</b>：仅当没有被任何 {@code units}/{@code assetShares}/{@code relations}
 *       引用时允许；被引用 ⇒ 具名 {@code Rejected}（理由带逐类引用计数并指路新版本 id）。逐值相同的重放是幂等 no-op。
 *   <li><b>版本 = 新的 kind 后缀 id</b>（{@code office@0_0 → office_v2@0_0}）：不改 {@code Industry} record
 *       形状、 不改 Codec/ChangeSet 形状；老 unit 引用老 id、逐值零影响。
 * </ul>
 *
 * <p>★★ <b>守卫分层</b>：字段形状与构造期语义（capacity/cycleDays/slots/非负/allocation）由 {@link
 * EconomyPayloads#parseIndustryNode(com.fasterxml.jackson.databind.JsonNode)}（= Seed 的同一份解析器）与
 * {@link io.mosire.simos.economy.model.Industry} 的构造期守卫判；版本/引用/空白格等业务守卫在 {@link
 * EconomyIndustryUpserts#project(EconomyData, String)}（命令 handler 与 GM 工具共用的唯一语义落点）。
 *
 * <p>★★ <b>写口与铁律</b>：只走既有 {@link EconomyData#withIndustries(java.util.Map)}；变更集由两整份状态经 {@code
 * EconomyChangeSet.between} 派生（铁律 5）；本命令只写 economy 切片（铁律 2/3）。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人目录；GM 的
 * {@code simos.command.submit} 与配套窄工具 {@code simos.economy.upsertIndustry} 可用。★ <b>不实现 {@code
 * CommandTargets}</b>（spec §5 的 Z1a 范围：不进决策令桶，没有决策人版；see Z1a ledger）。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code INDUSTRY_UPSERTED}）、业务/载荷拒绝 INFO（具名 reason，经 {@code
 * logReason} 脱敏）；{@code EconomyData} 跨表一致性故障 ERROR 且不降级、原样抛出。事件来源 = {@link
 * EconomyLogSource#ECONOMY_COMMAND}。本类不新增/不降级 WARN。
 */
public final class EconomyUpsertIndustryHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、GM 工具、catalog 提示都从这里取/对齐）。 */
  public static final String TYPE = "economy.UpsertIndustry";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base;
    try {
      base = EconomySnapshots.of(state).data();
    } catch (RuntimeException e) {
      // ★ 装配故障不是载荷错：ERROR 不降级，原样抛（不折 Rejected）。
      logContract("state-assembly", e);
      throw e;
    }
    try {
      EconomyIndustryUpserts.Projection projection =
          EconomyIndustryUpserts.project(base, payloadJson);
      logApplied(projection);
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalStateException e) {
      // ★ 跨表一致性契约故障（EconomyData 写出后校验等）：ERROR 不降级、原样抛。
      logContract("project", e);
      throw e;
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状/构造期守卫/业务规则（被引用/版本倒退/空白格）都折成具名 Rejected，一律 INFO。
      String reason = EconomyCommandPayloads.logReason(e.getMessage());
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "INDUSTRY_UPSERT_REJECTED", EconomyLogSource.ECONOMY_COMMAND, "reason", reason));
      return new HandlerOutcome.Rejected(e.getMessage());
    } catch (RuntimeException e) {
      logContract("project", e);
      throw e;
    }
  }

  /** 成功 INFO：谁（id）、创建还是原地改/幂等、周期、配方规模、逐类引用计数。 */
  private static void logApplied(EconomyIndustryUpserts.Projection projection) {
    boolean changed = !projection.changeSet().isEmpty();
    String mode = projection.created() ? "created" : (changed ? "in_place" : "noop");
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "INDUSTRY_UPSERTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "id",
                projection.industry().id().value(),
                "mode",
                mode,
                "cycleDays",
                projection.industry().cycleDays(),
                "capacityAssets",
                projection.industry().capacityPerUnit().size(),
                "outputCommodities",
                projection.industry().outputPerUnit().size(),
                "units",
                projection.references().units(),
                "assetShares",
                projection.references().assetShares(),
                "relations",
                projection.references().relations(),
                "changed",
                changed));
  }

  /** 契约/一致性故障 ERROR（事件名与来源固定；reason 过脱敏，不进载荷明文）。 */
  private static void logContract(String stage, RuntimeException failure) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "INDUSTRY_UPSERT_CONTRACT_VIOLATION",
                EconomyLogSource.ECONOMY_COMMAND,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                EconomyCommandPayloads.logReason(failure.getMessage())));
  }
}
