package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LaborQueueWritebackBook {
  private LaborQueueWritebackBook() {}

  /** (unit, 角色) 复合键：写回时用于只替换被排队的角色行，不误删同户同 unit 的其他角色。 */
  record UnitRole(ProductionUnitId unit, ProductionRoleId role) {}

  // ── 写回 ────────────────────────────────────────────────────────────────────────────────

  /**
   * 把最终排队结果写回配额工作副本：只删/改本户**排队的 PRODUCTION 旧行**，再按决定写回（复用旧行的 id/group/actor/period/kind；没有旧行 ⇒ 用
   * unit.operator() / 选定批次新发一条 PRODUCTION）。保留活动的旧行一律不动。
   *
   * <p>★★ C7：GOV_SERVICE 行**绝不**被队列删除/改写；若某个决定指向带 GOV_SERVICE 承诺的 activity ⇒ 具名契约 ERROR +
   * fail-closed（防"队列覆盖政府承诺"这条纪律被绕开）。
   */
  static void applyPlan(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      LaborQueueBook.Plan plan,
      PeopleLotId lot,
      Map<ProductionUnitId, ProductionProcess> candidateUnitsById,
      long day) {
    Set<ProductionUnitId> queuedUnits = new LinkedHashSet<>();
    Set<UnitRole> queuedSlots = new LinkedHashSet<>();
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      queuedUnits.add(decision.offer().unitId());
      queuedSlots.add(new UnitRole(decision.offer().unitId(), decision.offer().role()));
    }
    // ★ C7 防御：正常路径上带 GOV_SERVICE 的 activity 已被挡在候选外；这里再判一次，任何"排队到政府承诺上"的
    //   路径都 fail-closed（不静默覆盖/删除）。
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE
          && queuedUnits.contains(new ProductionUnitId(laborCommitment.activity()))) {
        throw laborCommitmentContractFault(
            plan.household(),
            day,
            "队列试图排队/覆盖 GOV_SERVICE 承诺: activity=" + laborCommitment.activity());
      }
    }
    Map<UnitRole, HouseholdLaborCommitment> templates = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue; // ★ C7：GOV_SERVICE 行原样留在活表里，不参与"删旧行/复用模板"
      }
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      ProductionRoleId role =
          laborCommitment
              .productionRole()
              .orElseThrow(
                  () ->
                      laborCommitmentContractFault(
                          plan.household(),
                          day,
                          "PRODUCTION 承诺缺 productionRole（D2 §5.6 不变量被绕开）: id="
                              + laborCommitment.id().value()));
      UnitRole slot = new UnitRole(unitId, role);
      if (queuedSlots.contains(slot)) {
        templates.putIfAbsent(slot, laborCommitment);
        laborCommitments.remove(laborCommitment.id());
      }
    }
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      if (decision.grantedLaborMilli() <= 0L) {
        continue;
      }
      ProductionUnitId unitId = decision.offer().unitId();
      ProductionRoleId role = decision.offer().role();
      UnitRole slot = new UnitRole(unitId, role);
      HouseholdLaborCommitment template = templates.get(slot);
      if (template != null) {
        laborCommitments.put(template.id(), withLaborMilli(template, decision.grantedLaborMilli()));
        continue;
      }
      ProductionProcess unit = candidateUnitsById.get(unitId);
      if (unit == null) {
        // ★★ 套利活动没有 ProductionProcess：收劳动的主体是**家户自己**（{@code HouseholdActors} 是
        //   家户身份 ↔ actor 身份的唯一拼写点）；配额 id 仍走契约层的唯一拼写点 {@code idOfProduction}。
        if (!TradeArbitrageActivity.isArbitrageActivity(unitId.value())) {
          throw new IllegalStateException("排队结果指向一个不在候选表里的 unit（内部不一致）：" + unitId.value());
        }
        LaborAllocationId arbitrageId =
            HouseholdLaborCommitment.idOfProduction(unitId, role, lot, plan.household());
        HouseholdLaborCommitment freshLaborCommitment =
            new HouseholdLaborCommitment(
                arbitrageId,
                lot,
                plan.household(),
                HouseholdActors.of(plan.household()),
                unitId.value(),
                decision.grantedLaborMilli(),
                1L,
                LaborCommitmentKind.PRODUCTION,
                Optional.of(role)); // ★ 套利属普通生产承诺：进排队、可缩（I13 的 Σ 里与生产同侧）
        HouseholdLaborCommitment previousLaborCommitment =
            laborCommitments.putIfAbsent(arbitrageId, freshLaborCommitment);
        if (previousLaborCommitment != null) {
          if (previousLaborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            throw laborCommitmentContractFault(
                plan.household(),
                day,
                "队列新发 id 撞上既有 GOV_SERVICE 承诺（拒绝覆盖政府承诺）: id=" + arbitrageId.value());
          }
          laborCommitments.put(
              arbitrageId, withLaborMilli(previousLaborCommitment, decision.grantedLaborMilli()));
        }
        continue;
      }
      LaborAllocationId id =
          HouseholdLaborCommitment.idOfProduction(unitId, role, lot, plan.household());
      HouseholdLaborCommitment freshLaborCommitment =
          new HouseholdLaborCommitment(
              id,
              lot,
              plan.household(),
              unit.operator(),
              unitId.value(),
              decision.grantedLaborMilli(),
              1L,
              LaborCommitmentKind.PRODUCTION,
              Optional.of(role)); // ★ C7：队列只发 PRODUCTION，绝不创建 GOV_SERVICE
      HouseholdLaborCommitment previousLaborCommitment =
          laborCommitments.putIfAbsent(id, freshLaborCommitment);
      if (previousLaborCommitment != null) {
        if (previousLaborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          throw laborCommitmentContractFault(
              plan.household(), day, "队列新发 id 撞上既有 GOV_SERVICE 承诺（拒绝覆盖政府承诺）: id=" + id.value());
        }
        laborCommitments.put(
            id, withLaborMilli(previousLaborCommitment, decision.grantedLaborMilli()));
      }
    }
  }

  /**
   * 把**超出预算的 PRODUCTION 既有保留量**按既有量比例缩到剩余预算以内（只在坏状态兜底）：0 ⇒ 删行、其余写回缩小值。
   *
   * <p>★★ C7：GOV_SERVICE 一律整额保留、不参与权重、不写回——调用方已先判"Σ GOV_SERVICE ≤ budget"；本方法的 可用量 = {@code budget
   * - govServiceLaborMilli}。GOV_SERVICE 行在此**绝不**被删/缩。
   */
  static void preserveIntoBudget(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      Set<UnitRole> queuedSlots,
      long budget,
      long govServiceLaborMilli) {
    preserveIntoBudget(
        laborCommitments,
        householdLaborCommitments,
        queuedSlots,
        budget,
        govServiceLaborMilli,
        Set.of());
  }

  /**
   * ★★★ <b>D-032 P3c3：锁定雇佣不参与比例缩</b>。{@code protectedSlots} 里的行整额保留；缩放的可用预算 = {@code budget −
   * govService − Σ protected}。
   */
  static void preserveIntoBudget(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      Set<UnitRole> queuedSlots,
      long budget,
      long govServiceLaborMilli,
      Set<UnitRole> protectedSlots) {
    long protectedLabor = 0L;
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
          || laborCommitment.productionRole().isEmpty()) {
        continue;
      }
      UnitRole slot =
          new UnitRole(
              new ProductionUnitId(laborCommitment.activity()),
              laborCommitment.productionRole().get());
      if (protectedSlots.contains(slot)) {
        protectedLabor = Math.addExact(protectedLabor, Math.max(0L, laborCommitment.laborMilli()));
      }
    }
    long availableForProduction = Math.max(0L, budget - govServiceLaborMilli - protectedLabor);
    List<LaborAllocationId> ids = new ArrayList<>();
    List<Long> weights = new ArrayList<>();
    long productionPreserved = 0L;
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue; // ★ C7：GOV_SERVICE 整额保留，绝不进入比例缩
      }
      UnitRole slot =
          new UnitRole(
              new ProductionUnitId(laborCommitment.activity()),
              laborCommitment.productionRole().orElseThrow());
      if (queuedSlots.contains(slot) || protectedSlots.contains(slot)) {
        continue;
      }
      ids.add(laborCommitment.id());
      long weight = Math.max(0L, laborCommitment.laborMilli());
      weights.add(weight);
      productionPreserved = Math.addExact(productionPreserved, weight);
    }
    if (ids.isEmpty()) {
      return; // 没有可缩的 PRODUCTION 保留量（防御性 no-op；超预算时理论上不可达）
    }
    long[] weightArray = new long[weights.size()];
    for (int i = 0; i < weights.size(); i++) {
      weightArray[i] = weights.get(i);
    }
    long[] parts =
        ProportionalSplit.byDenominator(availableForProduction, weightArray, productionPreserved);
    for (int i = 0; i < ids.size(); i++) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
      if (laborCommitment == null) {
        continue;
      }
      if (parts[i] <= 0L) {
        laborCommitments.remove(ids.get(i));
      } else {
        laborCommitments.put(ids.get(i), withLaborMilli(laborCommitment, parts[i]));
      }
    }
  }

  /** 换劳动量（其余字段——含 {@code kind}——原样带过）—— 与 {@code EconomySettlement.withLaborMilli} 同一形制。 */
  static HouseholdLaborCommitment withLaborMilli(
      HouseholdLaborCommitment laborCommitment, long laborMilli) {
    return new HouseholdLaborCommitment(
        laborCommitment.id(),
        laborCommitment.group(),
        laborCommitment.household(),
        laborCommitment.actor(),
        laborCommitment.activity(),
        laborMilli,
        laborCommitment.period(),
        laborCommitment.kind(),
        laborCommitment.productionRole());
  }

  /**
   * ★★ <b>C7 具名契约故障的唯一发射点</b>：先记 {@code LABOR_COMMITMENT_CONTRACT} ERROR（契约/一致性故障不降级）， 再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。{@code reason} 只含稳定 id / 数量，不含载荷明文。
   */
  static IllegalStateException laborCommitmentContractFault(
      HouseholdId household, long day, String reason) {
    EventLog.channel(EconomyLog.population())
        .error(
            LogEvent.of(
                "LABOR_COMMITMENT_CONTRACT",
                EconomyLogSource.ECONOMY_POPULATION,
                "day",
                day,
                "household",
                household.value(),
                "reason",
                reason));
    return new IllegalStateException("家庭劳动承诺契约违约：" + reason);
  }
}
