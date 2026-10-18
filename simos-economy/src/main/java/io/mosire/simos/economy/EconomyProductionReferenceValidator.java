package io.mosire.simos.economy;

import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CandidateStateValidator;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>economy 的候选态跨片校验器</b>（D-031 P1 spec §9.3）：在落库前核对 economy 实例对 production 定义权威的引用。
 *
 * <p>★ <b>只经 {@code production.contract}</b>：从候选态取 production 切片的 {@link
 * ProductionDefinitionLookup}，不 import production 实现包。
 *
 * <p>★ <b>覆盖原 {@code EconomyData} 构造期移走的跨域守卫</b>（{@link EconomyReferenceValidation}）：unit→industry、
 * assetShare→industry、{@code progressDays ≤ cycleDays}、classStructure/classPosition/组织/规则/变迁→mode、
 * mode→classStructure、输出覆盖→产业配方产出键等。本域形状约束仍由 {@code EconomyData} 构造器判。
 *
 * <p>★★ <b>两类失败的档位不同（日志纪律 §一.9）</b>：
 *
 * <ul>
 *   <li><b>装配 / 跨片契约故障</b>（有 economy 切片却缺 production 切片、切片类型不符）⇒ {@link EconomyLog} <b>ERROR</b> 且
 *       <b>fail-closed 抛异常</b>，不降级成普通业务拒绝；
 *   <li><b>领域非法新引用</b>（unit→不存在的 industry 等）⇒ 返回具名 {@code Optional} 理由，由 Core 折成 INFO 业务拒绝。
 * </ul>
 *
 * <p>★ 候选态没有 economy 切片 ⇒ 无事可校（空）。
 */
public final class EconomyProductionReferenceValidator implements CandidateStateValidator {

  /** 校验器名（注册序日志用）。 */
  public static final String NAME = "economy.production-references";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<String> rejection(SimulationState base, SimulationState candidate) {
    Objects.requireNonNull(candidate, "candidate");
    Snapshot economySlice = candidate.module("economy").orElse(null);
    if (economySlice == null) {
      return Optional.empty();
    }
    if (!(economySlice instanceof EconomySnapshot economySnapshot)) {
      throw assemblyFault("economy 切片类型不符（装配故障）: " + economySlice.getClass().getName());
    }
    Snapshot productionSlice = candidate.module("production").orElse(null);
    if (productionSlice == null) {
      throw assemblyFault("有 economy 切片却缺 production 切片（装配故障：定义权威必须在场）");
    }
    if (!(productionSlice instanceof ProductionDefinitionLookup definitions)) {
      throw assemblyFault(
          "production 切片未实现 ProductionDefinitionLookup（装配故障）: "
              + productionSlice.getClass().getName());
    }
    // ★★ B2b：实例与 GM 产出覆盖两维已从 economy 删除 ⇒ 交叉引用守卫改读 production 的当刻投影。
    if (!(productionSlice
        instanceof io.mosire.simos.production.contract.ProductionStateLookup productionState)) {
      throw assemblyFault(
          "production 切片未实现 ProductionStateLookup（装配故障：实例/覆盖权威必须在场）: "
              + productionSlice.getClass().getName());
    }
    try {
      // ★★ D-032 W5-E：本校验器每 tick / 每次候选提交都跑 ⇒ 走**运行时语义**（四参 validate 显式传 false）。
      //   名册 plannedLabor 是历史计划快照；人口/户结构变化后计划 &gt; 当刻预算不再硬拒整次 AdvanceTime。
      //   实际劳动分配由 LaborQueueSettlement / EconomySettlement.applyLaborBudgetsInto 按当刻预算封顶。
      //   写名册 / seed 的严格 D2-I13 语义在各自 handler 里以 enforcePlannedLaborBudget=true 单独执行。
      EconomyReferenceValidation.validate(
          definitions, productionState, economySnapshot.data(), NAME);
    } catch (IllegalArgumentException e) {
      // ★ 领域非法新引用 = 预期业务拒绝 ⇒ 具名 INFO（Core 折 Rejected），不升 ERROR。
      return Optional.of(e.getMessage());
    }
    return Optional.empty();
  }

  /** 装配 / 跨片契约故障：{@link EconomyLog} ERROR（不降级）后 fail-closed 抛出；Core 不会把它折成业务拒绝。 */
  private static IllegalStateException assemblyFault(String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ECONOMY_PRODUCTION_REFERENCE_ASSEMBLY_FAULT",
                EconomyLogSource.ECONOMY_COMMAND,
                "validator",
                NAME,
                "reason",
                reason));
    return new IllegalStateException(NAME + ": " + reason);
  }
}
