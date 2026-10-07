package io.mosire.simos.app.household;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>§18（Z0 控制方裁定）跨切片一致性：gov service unit 的 operator GOV 必须在 unit 切片存在且带 {@code
 * GovernmentFormation}</b>。
 *
 * <pre>
 * economy.units 的 operator == HOUSEHOLD:hh-gov-&lt;govUnitId&gt;
 *   ⇒ unit 切片必须有 Unit(&lt;govUnitId&gt;)，且 module() instanceof GovernmentFormation
 * </pre>
 *
 * <p>★★ <b>为什么必须有这一处</b>：Z1c 的 {@code economy.UpsertGovUnit} handler 在 economy 侧只能看见经济信封（已登记 +
 * {@code hh-gov} 在 classes/Social），<b>编译期看不见 unit</b>（模块边界，见 spec §18 裁定 A）；app 的 GM 窄工具 {@code
 * simos.economy.upsertGovUnit} 在 preview/apply 两条路径都做了 unit 切片预检，但 <b>GM 裸 {@code
 * simos.command.submit} 可绕过它</b>。本类就是 §18.3 指定的组合根兜底：任何"有 office/GOV 生产 unit、却没有对应 GOV 编制"
 * 的世界在这里**具名 fail-closed**，不静默继续。
 *
 * <p>★ <b>只读 + 纯函数</b>：不改入参；不一致以 {@link Mismatch} 具名列出，{@link #requireConsistent} 折成一条异常。unit
 * 切片完全缺席 （{@code units == null}）但存在 gov service unit ⇒ 也是具名不一致（不是跳过）。
 *
 * <p>★ <b>判据</b>：只认 operator 是 {@code HOUSEHOLD} 且家户 id 带 {@code hh-gov-} 前缀的经济 unit——这正是 Z1c 与 GM
 * 裸 submit 创建 gov service unit 时的 operator 身份；GOV 侧其余一致性（家户/政府记录/国库）由 {@link
 * GovernmentHouseholdWiring} 另行判死。
 */
public final class GovernmentServiceUnitConsistency {

  /**
   * 契约故障日志通道：与推进入口同一 logger（{@code io.mosire.simos.app.time}），来源 {@link
   * AppLogSource#HOUSEHOLD_SYNC}。
   */
  private static final LogChannel CONSISTENCY = EventLog.channel(AppLog.time());

  private GovernmentServiceUnitConsistency() {}

  /** 一处具名不一致。 */
  public record Mismatch(String kind, String detail) {

    public Mismatch {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
      return kind + ": " + detail;
    }
  }

  /**
   * 全量只读校核（按 {@code ProductionUnitId.value()} 稳定序遍历；结果只依赖状态内容）。
   *
   * @param economy 经济切片（非 null）
   * @param units unit 切片；{@code null} 表示该 state 没有 unit 切片（装配缺席 ⇒ 有 gov service unit 即不一致）
   */
  public static List<Mismatch> mismatches(EconomyData economy, UnitState units) {
    Objects.requireNonNull(economy, "economy");
    List<ProductionProcess> ordered = new ArrayList<>(economy.units().values());
    ordered.sort(Comparator.comparing(process -> process.id().value()));
    List<Mismatch> out = new ArrayList<>();
    for (ProductionProcess process : ordered) {
      ActorRef operator = process.operator();
      if (operator.kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      HouseholdId operatorHousehold;
      try {
        operatorHousehold = HouseholdActors.householdOf(operator);
      } catch (IllegalArgumentException e) {
        out.add(
            new Mismatch(
                "GOV_SERVICE_OPERATOR_HOUSEHOLD_UNREADABLE",
                "经济 unit "
                    + process.id().value()
                    + " 的 operator 看似家户但读不回身份 "
                    + operator
                    + "："
                    + e.getMessage()));
        continue;
      }
      if (!GovernmentHouseholds.isGovernment(operatorHousehold)) {
        continue;
      }
      Optional<String> govUnitRef = GovernmentHouseholds.unitRefOf(operatorHousehold);
      if (govUnitRef.isEmpty()) {
        out.add(
            new Mismatch(
                "GOV_SERVICE_OPERATOR_BAD_HOUSEHOLD",
                "经济 unit "
                    + process.id().value()
                    + " 的 operator 是政府家户前缀但解析不出 GOV 单位 id："
                    + operatorHousehold.value()));
        continue;
      }
      String govUnitId = govUnitRef.get();
      if (units == null) {
        out.add(
            new Mismatch(
                "GOV_SERVICE_UNIT_SLICE_MISSING",
                "经济 unit "
                    + process.id().value()
                    + " 的 operator GOV="
                    + govUnitId
                    + "，但本状态没有 unit 切片：先在 unit 切片建 GOV 单位并 unit.SetGovFormation"));
        continue;
      }
      Unit govUnit = units.units().get(UnitId.parse(govUnitId));
      if (govUnit == null) {
        out.add(
            new Mismatch(
                "GOV_SERVICE_GOV_UNIT_MISSING",
                "经济 unit "
                    + process.id().value()
                    + " 的 operator GOV="
                    + govUnitId
                    + " 在 unit 切片不存在：先 unit.CreateUnit + unit.SetGovFormation（GM 裸 simos.command.submit "
                    + "创建的 gov service unit 必须补 unit 侧 GOV 编制）"));
      } else if (!(govUnit.module().orElse(null) instanceof GovernmentFormation)) {
        out.add(
            new Mismatch(
                "GOV_SERVICE_GOV_FORMATION_MISSING",
                "经济 unit "
                    + process.id().value()
                    + " 的 operator GOV="
                    + govUnitId
                    + " 存在但没有 GovernmentFormation（实际 module="
                    + govUnit
                        .module()
                        .map(module -> module.getClass().getSimpleName())
                        .orElse("<空>")
                    + "）：先 unit.SetGovFormation"));
      }
    }
    return List.copyOf(out);
  }

  /** 校核 + 具名 fail-closed（不一致 ⇒ 先 ERROR 一行、再 {@link IllegalStateException}，消息最多列 5 条）。 */
  public static void requireConsistent(EconomyData economy, UnitState units) {
    List<Mismatch> mismatches = mismatches(economy, units);
    if (!mismatches.isEmpty()) {
      // ★ AGENTS §一.9（2026-10-23）：契约/跨切片一致性故障 = ERROR 不降级；先落证据再 fail-closed。
      CONSISTENCY.error(
          LogEvent.of(
              "GOV_SERVICE_UNIT_CONSISTENCY_FAULT",
              AppLogSource.HOUSEHOLD_SYNC,
              "count",
              mismatches.size(),
              "first",
              mismatches.get(0)));
      throw new IllegalStateException(
          "gov service unit 的 operator GOV 与 unit 切片不一致（spec §18 跨切片一致性）："
              + mismatches.size()
              + " 处："
              + mismatches.subList(0, Math.min(5, mismatches.size())));
    }
  }
}
