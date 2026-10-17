package io.mosire.simos.app.household;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.spi.EconomyGovUnitUpserts;
import io.mosire.simos.production.contract.ProductionProcess;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>§18（Z0 控制方裁定）跨切片一致性：gov service unit 的 operator GOV 必须在 unit 切片存在且带 {@code
 * GovernmentFormation}</b>。
 *
 * <pre>
 * economy.units 的 operator == HOUSEHOLD:hh-gov-&lt;govUnitId&gt; 且 modeKey == gov_service
 *   ⇒ unit 切片必须有 Unit(&lt;govUnitId&gt;)，且 module() instanceof GovernmentFormation
 * </pre>
 *
 * <p>★★ <b>W5-fix2（2026-10-11）：判据补上 {@code modeKey == gov_service} 这一维</b> —— 本类管的是 <b>gov service
 * unit</b>（行政服务），而它的旧判据只看"operator 是政府家户"，那是一个<b>过近似</b>：任何<b>由</b>政府家户<b>经营</b>的 生产性 unit（W5 的铸币
 * {@code mint@<q>_<r>}，regime {@code government_mint}）也被卷进来，要求它必须在 unit 切片有一支 GOV 编制 —— 而那不是服务
 * unit，没有任何编制可补。 实测（{@code SmallWorld.state} 创世后）：operator 是政府家户的经济 unit 共 3 个 —— {@code
 * unit-mint@0_0-…hh-gov-world-silver}（{@code modeKey=mint@0_0}）、{@code
 * …office@0_0…hh-gov-gov-central} 与 {@code …office@0_1…hh-gov-gov-province}（两者 {@code
 * modeKey=gov_service}）⇒ <b>只有 2 个</b>是本类的判据面。
 *
 * <p>★★ <b>为什么 {@code modeKey} 是"是不是服务 unit"的判据</b>：{@code gov_service} 是行政服务生产方式的稳定键， 它的唯一拼写点是
 * {@link EconomyGovUnitUpserts#GOV_SERVICE_MODE_KEY}（{@code economy.UpsertGovUnit} 的 {@code
 * modeKey} 缺省值），而<b>唯一</b>能建出 gov service unit 的命令路径就是那一条 —— 它同时由 {@code requireOfficeIndustry}
 * 判死"产业 base kind 必须是 {@code office}"。⇒ 判据不靠猜、也不放宽：走那条路径的 unit 恒带这个键。
 *
 * <p>★ <b>§一.9：被排除的 unit 具名可查</b> —— 每次跳过发一行 DEBUG（{@code
 * event=GOV_SERVICE_UNIT_SKIPPED_NOT_SERVICE} + {@code
 * unit/modeKey/industry/operatorHousehold/reason}），不是静默缩小判据面。
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
  public static List<Mismatch> mismatches(
      EconomyData economy,
      Map<
              io.mosire.simos.economy.api.id.ProductionUnitId,
              io.mosire.simos.production.contract.ProductionProcess>
          productionUnits,
      UnitState units) {
    Objects.requireNonNull(economy, "economy");
    List<ProductionProcess> ordered = new ArrayList<>(productionUnits.values());
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
      // ★★ W5-fix2：本类只管 **gov service unit**（见类注）—— 政府家户经营的**生产性** unit（W5 的铸币）不属于本判据面。
      if (!EconomyGovUnitUpserts.GOV_SERVICE_MODE_KEY.equals(process.modeKey())) {
        CONSISTENCY.debug(
            LogEvent.of(
                "GOV_SERVICE_UNIT_SKIPPED_NOT_SERVICE",
                AppLogSource.HOUSEHOLD_SYNC,
                "unit",
                process.id().value(),
                "modeKey",
                process.modeKey(),
                "industry",
                process.industry().value(),
                "operatorHousehold",
                operatorHousehold.value(),
                "reason",
                "not-a-gov-service-unit"));
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
  public static void requireConsistent(
      EconomyData economy,
      Map<
              io.mosire.simos.economy.api.id.ProductionUnitId,
              io.mosire.simos.production.contract.ProductionProcess>
          productionUnits,
      UnitState units) {
    List<Mismatch> mismatches = mismatches(economy, productionUnits, units);
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
