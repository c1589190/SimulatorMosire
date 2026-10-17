package io.mosire.simos.production.spi;

import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code production.UpsertIndustry}（GM-only）：创建/修改产业定义</b>（D-031 P1；原 {@code
 * economy.UpsertIndustry} 的完整载荷与幂等/版本/被引用拒绝语义迁入）。
 *
 * <pre>{@code
 * {"id":"office@0_0", "name":"衙署", "regime":"government_office", "skill":"government",
 *  "cycleDays":30, "capacityPerUnit":{"SMALL_TOOL":1}, "dailyInputPerUnit":{}, "dailyLaborPerUnit":0,
 *  "laborPerUnit":16000, "outputPerUnit":{}, "moneyOutputPerUnit":{}, "cycleInputPerUnit":{},
 *  "slots":[{"id":"...","name":"...","laborParticipationPerMille":1000}],
 *  "allocation":{"@class":"split","meansWeightPerMille":500,"laborWeightPerMille":500},
 *  "roles":[{"id":"operator","name":"经营者","sharePerMille":1000,"intraRoleSplit":"equal",
 *            "standardLaborMilliPerCycle":0,"payment":"product_share"}],
 *  "roleShareRemainderRule":"unallocated", "upgradesTo":[], "mode":"..."}
 * }</pre>
 *
 * <p>★ 业务规则见 {@link IndustryUpserts#project}；跨域引用/激活检查经 {@link ProductionConsumerPort}（★★ D-032 B4
 * / §13.14.5 第 1 条：该端口的实现在组合根 {@code simos-app}，由装配期<b>构造器注入</b>——不再是"从 state 的 economy 切片里 向下转型"）。
 */
public final class UpsertIndustryHandler implements CommandHandler {

  /** 命令类型（唯一拼写点：Shell 注册、GM 工具、catalog 提示都从这里取/对齐）。 */
  public static final String TYPE = "production.UpsertIndustry";

  private static final Logger LOG = ProductionLog.command();

  /** ★★ B4：跨切片使用情况端口（实现在组合根；构造期 requireNonNull ⇒ 装配错在启动期当场炸，不降级成业务拒绝）。 */
  private final ProductionConsumerPort consumers;

  public UpsertIndustryHandler(ProductionConsumerPort consumers) {
    this.consumers =
        Objects.requireNonNull(consumers, "consumers（组合根必须注入 ProductionConsumerPort 实现）");
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ProductionData base = ProductionSnapshots.of(state);
    try {
      IndustryUpserts.Projection projection =
          IndustryUpserts.project(base, state, consumers, payloadJson);
      logApplied(projection);
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状/构造期守卫/业务规则（被引用/版本倒退/空白格）都折成具名 Rejected，一律 INFO。
      ProductionPayloads.logRejected(
          LOG,
          ProductionLogSource.PRODUCTION_COMMAND,
          "PRODUCTION_UPSERT_INDUSTRY_REJECTED",
          e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 成功 INFO：谁（id）、创建还是原地改/幂等、周期、配方规模、逐类引用计数。 */
  private static void logApplied(IndustryUpserts.Projection projection) {
    boolean changed = !projection.changeSet().isEmpty();
    String mode = projection.created() ? "created" : (changed ? "in_place" : "noop");
    long moneyWageRoles =
        projection.industry().roles().stream()
            .filter(role -> role.payment() instanceof RolePayment.MoneyWage)
            .count();
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_INDUSTRY_UPSERTED",
                ProductionLogSource.PRODUCTION_COMMAND,
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
                "roles",
                projection.industry().roles().size(),
                "moneyWageRoles",
                moneyWageRoles,
                "roleShareRemainderRule",
                projection.industry().roleShareRemainderRule().key(),
                "upgradesTo",
                projection.industry().upgradesTo().size(),
                "mode",
                projection.industry().mode().map(value -> value.value()).orElse("-"),
                "units",
                projection.references().units(),
                "assetShares",
                projection.references().assetShares(),
                "relations",
                projection.references().relations(),
                "changed",
                changed));
  }
}
