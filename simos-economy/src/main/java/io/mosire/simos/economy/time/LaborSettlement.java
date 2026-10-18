package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class LaborSettlement {

  /** 劳动再分配阶段一个分区的产出（本地配额表 + 该分区 seed 过的键，用于识别"整条回池"的删除）。 */
  record LaborPartition(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Set<LaborAllocationId> seededKeys) {}

  /**
   * ★★ <b>R4-E2b：这个 unit 是否由候选预设进入</b>——旧档/主副 unit 的 {@code modeKey == industry.id()}（世界播种与
   * 旧档迁移的不变式），候选 unit 的 {@code modeKey == candidateId@version}（见 {@code ProductionProcess} 类注）。 候选
   * unit 的 {@code cycleDays} 可以与同格旧产业不同，故它自己的周期边界不应当触发整格重排。
   */
  static boolean isPresetOrigin(ProductionProcess unit) {
    return !unit.modeKey().equals(unit.industry().value());
  }

  /**
   * ★★ <b>劳动再分配按 hex 并行</b>（{@link #reallocateLabor} 的并行外壳；R3B.2 起键 = unit id）。
   *
   * <p>★ <b>R4-E2b：{@code cycleStartExempt}</b> = 今天刚由 E2b 进入执行新建的 unit。它们 {@code progressDays==0}
   * 是事实，但本步<b>整条把它们排除在本次重排之外</b>：既不让它们单独触发"这一格本周期第一天"的判定，也不让它们进 本格的再分配集合。理由两条：
   *
   * <ol>
   *   <li>一个进入动作不得把同格<b>既有 unit</b> 从半周期/周期初重新排一次劳动 —— 既有 unit 看到的重排结果必须与"没有进入动作"
   *       的世界逐值相同（进入的代价只应落在新 unit 自己与显式 grant 的 assetSource 份额上）；
   *   <li>新 unit 自己的配额就是试产计划的 {@code trialScale × laborPerUnit}（由 E2b 显式发放），也不需要参与 pool 再分配 —— 它若被
   *       reallocation 按"投入缩过后的 need"削一刀，反而让试产口径多一个隐藏写者。
   * </ol>
   *
   * <p>★ <b>触发条件</b>：本格任一<b>旧档/主副 unit</b>（{@code modeKey == industry.id()}，见 {@link
   * #isPresetOrigin}） 在周期第一天（{@code progressDays == 0}）就重排 —— 这是既有口径。★ <b>E2b 候选 unit 不单独触发重排</b>：
   * 它们可以有与既有产业不同的 {@code cycleDays}，若让"试产 unit 自己的周期边界"触发，就会把同格既有 unit 从半周期里
   * 重排一次（违反"旧经营者延续"）。它们仍会在**本格因旧 unit 触发而进入重排**时作为普通 unit 参与（need 照算）。
   *
   * <p>★★ <b>与"空集逐值等价旧路径"的关系</b>：豁免集为空、且世界里没有候选 unit（{@code isPresetOrigin} 全为 false）时，
   * 本方法逐字等于旧路径（触发条件、单位集合、分配顺序都不变）。候选 unit 只由 R4-E2b 新建，故旧档/空表基线不受影响。
   */
  static void reallocateLaborPartitioned(
      EconomySession session,
      EconomyParallelism parallelism,
      SettlementIndex index,
      Set<ProductionUnitId> cycleStartExempt) {
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    Map<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, List<ProductionUnitId>> unitsByHex = new TreeMap<>();
    for (ProductionProcess unit : units.values()) {
      if (cycleStartExempt.contains(unit.id())) {
        continue; // ★ 今天刚进入的试产 unit：不触发重排、不进本格再分配集合（见方法注释）
      }
      IndustryHexKeys.hexKeyOf(unit.industry())
          .ifPresent(
              hex -> unitsByHex.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(unit.id()));
    }
    TreeMap<String, List<ProductionUnitId>> active = new TreeMap<>();
    for (Map.Entry<String, List<ProductionUnitId>> entry : unitsByHex.entrySet()) {
      // ★ 触发者只认旧档/主副 unit（既有口径）；候选 unit 自己翻篇不触发本格重排（见方法注释）。
      for (ProductionUnitId id : entry.getValue()) {
        ProductionProcess unit = units.get(id);
        if (unit != null && unit.progressDays() == 0L && !isPresetOrigin(unit)) {
          active.put(entry.getKey(), entry.getValue());
          break;
        }
      }
    }
    if (active.isEmpty()) {
      return;
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.LABOR_REALLOCATION, active.keySet(), parallelism.partitionCount());
    List<LaborPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              LinkedHashMap<ProductionUnitId, ProductionProcess> localUnits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (ProductionUnitId id : active.get(hex)) {
                  // ★★★ B8（缺陷 DEF-T2b-2 的同源一处）：本步的"最大可吸收劳动"
                  //   （{@link LaborQueueBook#maxAbsorbableLaborMilli}）要读**本生产日实扣的周期投入**，
                  //   而本日现扣阶段（上面 `drawCycleInputsPartitioned`）的落点是**会话**
                  //   （`EconomySession#drawnProductionInputs`）——实例自己的 `cycleInputUsedMilli`
                  //   要等本 tick 的 `executeDay` 才被写，本步跑在它**之前**。
                  //   ⇒ 把"本日实扣量"镜像进**分区副本**再交给本步（只读副本，不回写实例、不改签名）。
                  //   ★ 为什么必须在**分区副本**上镜像而不是在权威表上：实例状态的唯一写者是 production
                  //     （I1），economy 就地写实例表正是 B1 删掉的那条老路。
                  //   ★ 为什么这样不"造第二份权威"：这份副本只活在本方法内、只喂"本 tick 最多能吸收多少劳动"
                  //     这一个只读判定；周期投入的权威仍由 `executeDay` 的 `finalInputsMilli` 落到 production。
                  ProductionProcess plannerUnit = units.get(id);
                  Map<CommodityId, Long> drawnToday = session.drawnProductionInputsOf(id);
                  localUnits.put(
                      id,
                      drawnToday.isEmpty()
                          ? plannerUnit
                          : plannerUnit.withCycleState(
                              plannerUnit.progressDays(), new LinkedHashMap<>(drawnToday)));
                }
              }
              LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> localLaborCommitments =
                  new LinkedHashMap<>();
              Set<LaborAllocationId> seeded = new LinkedHashSet<>();
              for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
                if (localUnits.containsKey(new ProductionUnitId(laborCommitment.activity()))) {
                  localLaborCommitments.put(laborCommitment.id(), laborCommitment);
                  seeded.add(laborCommitment.id());
                }
              }
              reallocateLabor(
                  localUnits,
                  industries,
                  householdEconomies,
                  localLaborCommitments,
                  operatorConditions,
                  index);
              return new LaborPartition(localLaborCommitments, seeded);
            },
            parallelism.poolOrNull());
    for (LaborPartition partition : partitions) {
      for (LaborAllocationId id : partition.seededKeys()) {
        if (!partition.laborCommitments().containsKey(id)) {
          laborCommitments.remove(id); // 整条回池（原算法 remove；本地副本里没有它）
        }
      }
      laborCommitments.putAll(partition.laborCommitments());
    }
  }

  /**
   * ★★ **把一个批次在两个产业上的配额按"该批次的存活比例"缩**（R4 的出生/死亡回写路径用；与 {@link #scaleLaborOfIndustry}
   * 同一条口径，只是键换成了批次）。
   *
   * <p>★ 人口真值源在 social ⇒ 本步的**唯一输入**是一份 {@code group → (出生, 死亡)} 的账（见 {@link
   * #applyPopulationChange}）： economy 不需要认识 {@code PopulationGroup}，只需要它的稳定身份。
   *
   * <p>★★ <b>Z3a/C7：{@code GOV_SERVICE} 行整额跳过</b>；批次缩放完成后由调用方统一 {@link
   * #requireGovServiceCommitmentsWithinBudgets} fail-closed 校验。
   */
  static void scaleLaborOfGroup(
      PeopleLotId group,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      SettlementIndex index) {
    if (before <= 0L || after >= before) {
      return;
    }
    // ★ R4-B.3a-perf：按批次查配额 id（旧实现每条 LotChange 扫全量配额）；id 回活表取当前 record，
    //   故同一次回写里前面的缩放不会被缓存的旧值覆盖。
    for (LaborAllocationId allocationId : index.allocationIdsOfGroup(group)) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
      if (laborCommitment == null) {
        continue; // 索引与活表同源；这里只防御中途被移除
      }
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        continue; // ★ Z3a/C7：政府行政岗位承诺不参与人口/死亡比例缩
      }
      long scaled = laborCommitment.laborMilli() * after / before;
      laborCommitments.put(
          allocationId,
          new HouseholdLaborCommitment(
              laborCommitment.id(),
              laborCommitment.group(),
              laborCommitment.household(),
              laborCommitment.actor(),
              laborCommitment.activity(),
              scaled,
              laborCommitment.period(),
              laborCommitment.kind(),
              laborCommitment.productionRole()));
    }
  }

  /**
   * ★★ <b>C7 统一校验：政府承诺整额保留后，PRODUCTION 不得超过"政府预留后的可用时间"</b>（Z7d-1 放宽）。
   *
   * <p>凡有 {@code GOV_SERVICE} 承诺的家户，逐户算 {@code Σ GOV_SERVICE}（职位/诉求）与 {@code Σ PRODUCTION}； Z7d-1 起
   * {@code Σ GOV_SERVICE > HouseholdEconomy.laborMilli} 是**合法的 underfed 状态**（饥饿把预算饿少；承诺行
   * 不缩/不删，有效供给由 app 供给桥 cap 到实际劳动）。真契约故障只剩一条：{@code Σ PRODUCTION > budget − min(Σ GOV_SERVICE,
   * budget)} ⇒ 发 {@code LABOR_COMMITMENT_CONTRACT} ERROR 并抛 {@link
   * IllegalStateException}（fail-closed，绝不静默缩/删政府承诺）。没有 {@code GOV_SERVICE} 的家户不检查 ⇒ 既有路径逐值不变。
   *
   * <p>★ <b>为什么放在整批缩放之后而不是缩放循环里</b>：同一家户可能同时供给多个 unit/批次，前面一次缩放看到的中途值可能被 后面的缩放继续缩小；只有在"这一轮所有 {@code
   * scaleLaborOf*} 都已跑完"的点上，越界判定才不是假阳性。
   *
   * @param source 无 day 上下文的调用方按日志纪律用 {@link EconomyLogSource#ECONOMY_POPULATION_WRITE}（system 档）；
   *     有 day 的调用方用 {@link EconomyLogSource#ECONOMY_POPULATION}
   * @param day 调用方的日锚点；方法签名无 day 的路径传 {@code -1}（事件不带 {@code day} 字段）
   */
  static void requireGovServiceCommitmentsWithinBudgets(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      EconomyLogSource source,
      long day) {
    Map<HouseholdId, Long> govServiceByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> productionByHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        govServiceByHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Math::addExact);
      } else {
        productionByHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Math::addExact);
      }
    }
    if (govServiceByHousehold.isEmpty()) {
      return;
    }
    for (Map.Entry<HouseholdId, Long> entry : govServiceByHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomies.get(entry.getKey());
      if (householdEconomy == null) {
        continue; // 旧档迁移期占位行（运行期 GOV_SERVICE 写入者要求先有 classes 行）
      }
      long govServiceSum = entry.getValue();
      long productionSum = productionByHousehold.getOrDefault(entry.getKey(), 0L);
      long budget = Math.max(0L, householdEconomy.laborMilli());
      // ★★ Z7d-1：GOV_SERVICE 允许饿少后 over-budget（职位保留，C7 不缩/删）；只要求 PRODUCTION 不超过
      //   "扣除政府承诺最高优先预留后的可用时间"。有效供给不足由 app 供给桥 cap 并记录 underfed。
      long reservedGovService = Math.min(govServiceSum, budget);
      long productionBudget = budget - reservedGovService;
      if (productionSum > productionBudget) {
        throw laborCommitmentBudgetFault(
            entry.getKey(),
            govServiceSum,
            Math.addExact(govServiceSum, productionSum),
            budget,
            "GOV_SERVICE 最高优先预留后 PRODUCTION 超过可用时间预算（只缩 PRODUCTION 仍不足）",
            source,
            day);
      }
    }
  }

  /**
   * ★★ <b>C7 具名契约故障出口</b>（与 Z1b {@code LaborQueueSettlement.laborCommitmentContractFault} 同事件名）： 先发
   * {@code LABOR_COMMITMENT_CONTRACT} ERROR，再返回 {@link IllegalStateException} 供调用方 fail-closed。
   * {@code day < 0} ⇒ 事件不带 {@code day}（调用方方法签名无日锚点，按日志纪律归 system 来源）。
   */
  static IllegalStateException laborCommitmentBudgetFault(
      HouseholdId household,
      long govServiceLaborMilli,
      long totalLaborMilli,
      long budgetLaborMilli,
      String reason,
      EconomyLogSource source,
      long day) {
    if (day >= 0L) {
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "LABOR_COMMITMENT_CONTRACT",
                  source,
                  "day",
                  day,
                  "household",
                  household.value(),
                  "govServiceLaborMilli",
                  govServiceLaborMilli,
                  "totalLaborMilli",
                  totalLaborMilli,
                  "budgetLaborMilli",
                  budgetLaborMilli,
                  "reason",
                  reason));
    } else {
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "LABOR_COMMITMENT_CONTRACT",
                  source,
                  "household",
                  household.value(),
                  "govServiceLaborMilli",
                  govServiceLaborMilli,
                  "totalLaborMilli",
                  totalLaborMilli,
                  "budgetLaborMilli",
                  budgetLaborMilli,
                  "reason",
                  reason));
    }
    return new IllegalStateException(
        "家庭劳动承诺契约违约："
            + reason
            + "（household="
            + household.value()
            + "，GOV_SERVICE="
            + govServiceLaborMilli
            + "，总承诺="
            + totalLaborMilli
            + "，预算="
            + budgetLaborMilli
            + "）");
  }

  /**
   * ★★ <b>劳动再分配（P2-A §13.4 的小时口径版本；★ P2-B 起只服务 {@code modes} 为空的旧档世界）</b>。
   *
   * <p>★★ <b>P2-B §13.5 的接替者</b>：有 {@code modes} 的世界不再走本方法，改走 {@link LaborQueueSettlement}（每
   * tick、按家户利润率排队 + 逐 unit 最大可吸收量全局封顶）。本方法保留为 <b>旧路径</b>：空 modes 世界的逐值行为一字不变（同一批配额、同一修剪、同一封顶）。
   *
   * <p>★★ <b>本批的语义（判据就是它）</b>：
   *
   * <pre>
   * ① 逐 unit 算"这一周期真的用得上的劳动"（本 tick 最大可吸收量）：
   *      usableScale_i = min( 产能那一路, ⌊本周期已扣到的投入_j ÷ inputPerUnit_j⌋ … )
   *      need_i        = usableScale_i × 配方 laborPerUnit_i       // 与配额同量纲（本批仍为毫小时刻度）
   * ② 逐 (批次, unit) 配额：保留 = min(配额, 该 unit**剩余**可吸收量)；超出的缩掉/整条删掉
   * ③ 按**家户每 tick 时间预算**（{@code HouseholdEconomy.laborMilli}）封顶：Σallocations(household) ≤ budget，
   *      超出时按现有配额权重的最大余数法缩 —— Σ 从不凭空增加
   * </pre>
   *
   * <p>★★ <b>已删除的旧语义</b>：改前第 ③/④ 步是"回池的劳动按**缺口大者先得**、兜底给产粮 unit（最后雇主）"。 那正是计划 §13.5
   * 要求替换掉的"按缺口优先"口径；本批**不**用它冒充利润率排队 —— "预期单位劳动净收益降序 × 各生产方式最大可吸收劳动"的排序接线属 P2-B。⇒ 释放出来的时间**留在空缺**
   * （不自动塞给别的 unit），这与"没有可吸收的生产方式 ⇒ 劳动空缺"同侧。
   *
   * <p>★ <b>只在本格的产业处于"周期第一天"时重排</b>（{@code progressDays == 0}，与现扣投入同一天）： 那时"这一周期开得起来多大"已由现扣步写进
   * {@code cycleInputUsedMilli} ⇒ 判定有据。
   *
   * <p>★ <b>保序与幂等</b>：全部遍历走保序表 + id 序 ⇒ 同一份状态两次调用逐值相同。
   */
  static void reallocateLabor(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index) {
    Map<String, List<ProductionUnitId>> hexToUnits = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> unitEntry : units.entrySet()) {
      IndustryHexKeys.hexKeyOf(unitEntry.getValue().industry())
          .ifPresent(
              hex ->
                  hexToUnits
                      .computeIfAbsent(hex, ignored -> new ArrayList<>())
                      .add(unitEntry.getKey()));
    }
    for (Map.Entry<String, List<ProductionUnitId>> hex : hexToUnits.entrySet()) {
      List<ProductionUnitId> ids = hex.getValue();
      boolean cycleStart = false;
      for (ProductionUnitId id : ids) {
        if (units.get(id).progressDays() == 0L) {
          cycleStart = true;
          break;
        }
      }
      if (!cycleStart) {
        continue; // 周期中途：这一周期的劳动口径已经开跑，不重排（见方法注释）
      }
      // ① 每 unit "这一周期真的用得上的劳动"（缺口的来源）
      //   ★ R4-B.3a-perf：先把本 hex 的 unit id 放进 Set，再逐配额做 O(1) 判定（旧实现对每条配额做
      //     {@code ids.contains} 线性查找；插入序/求和/取整口径不变）。
      Set<ProductionUnitId> idSet = new LinkedHashSet<>(ids);
      Map<ProductionUnitId, Long> allocated = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (idSet.contains(unitId)) {
          allocated.merge(unitId, laborCommitment.laborMilli(), Long::sum);
        }
      }
      Map<ProductionUnitId, Long> need = new LinkedHashMap<>();
      for (ProductionUnitId id : ids) {
        ProductionProcess unit = units.get(id);
        IndustryDefinitionView industry = industries.get(unit.industry());
        if (industry == null) {
          throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
        }
        need.put(
            id,
            laborNeedOf(
                unit, industry, index, allocated.getOrDefault(id, 0L), operatorConditions.get(id)));
      }
      // ② 按 unit 的**最大可吸收劳动**修剪（这是 §13.5 的"本 tick 最大可吸收量"，不是按缺口抢）
      //   ★★ Z3a/C7：GOV_SERVICE 先整额占住该 unit 的 room（最高优先级）且不参与修剪/删除；
      //      PRODUCTION 只吃剩余 room（"先扣承诺再排生产"）。
      Map<ProductionUnitId, Long> kept = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (!need.containsKey(unitId)
            || laborCommitment.kind() != LaborCommitmentKind.GOV_SERVICE) {
          continue;
        }
        kept.merge(unitId, laborCommitment.laborMilli(), Long::sum);
      }
      for (LaborAllocationId allocId : new ArrayList<>(laborCommitments.keySet())) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocId);
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (!need.containsKey(unitId)) {
          continue; // 不是本格的配额（另一格的 unit）
        }
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          continue; // ★ Z3a/C7：政府行政岗位承诺不参与按最大可吸收量修剪
        }
        long room = Math.max(0L, need.get(unitId) - kept.getOrDefault(unitId, 0L));
        long keep = Math.min(laborCommitment.laborMilli(), room);
        kept.merge(unitId, keep, Long::sum);
        if (keep <= 0L) {
          laborCommitments.remove(allocId); // 这个 unit 这一周期一点也用不上
        } else if (keep < laborCommitment.laborMilli()) {
          laborCommitments.put(allocId, withLaborMilli(laborCommitment, keep));
        }
      }
      // ③ ★★ P2-A §13.4：按**家户每 tick 时间预算**封顶（毫小时）—— Σallocations(household) ≤ row.laborMilli。
      //   ★ 本步**不**按缺口把释放出来的时间再分给别的 unit：预期单位劳动净收益降序 + 各生产方式最大可吸收
      //     劳动的排队属 P2-B（计划 §13.5）；本批只保证小时口径与预算不变量，旧的"缺口大者先得 + 最后兜底产粮
      //     unit"语义已删除（不得以任何形式冒充利润率排队）。
      Map<HouseholdId, List<LaborAllocationId>> byHousehold = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        if (!idSet.contains(new ProductionUnitId(laborCommitment.activity()))) {
          continue; // 另一格的配额
        }
        byHousehold
            .computeIfAbsent(laborCommitment.household(), ignored -> new ArrayList<>())
            .add(laborCommitment.id());
      }
      for (Map.Entry<HouseholdId, List<LaborAllocationId>> household : byHousehold.entrySet()) {
        HouseholdEconomy householdEconomy = householdEconomies.get(household.getKey());
        if (householdEconomy == null) {
          continue;
        }
        List<LaborAllocationId> householdAllocationIds = new ArrayList<>(household.getValue());
        householdAllocationIds.sort(Comparator.comparing(LaborAllocationId::value));
        // ★★ Z3a/C7：GOV_SERVICE 整额保留、不进比例权重；只对 PRODUCTION 按剩余预算缩。
        long govServiceSum = 0L;
        long productionSum = 0L;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          HouseholdLaborCommitment laborCommitment =
              laborCommitments.get(householdAllocationIds.get(i));
          long amount = laborCommitment == null ? 0L : Math.max(0L, laborCommitment.laborMilli());
          if (laborCommitment != null
              && laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            govServiceSum = Math.addExact(govServiceSum, amount);
          } else {
            productionSum = Math.addExact(productionSum, amount);
          }
        }
        long sum = Math.addExact(govServiceSum, productionSum);
        long budget = Math.max(0L, householdEconomy.laborMilli());
        if (sum <= budget || sum <= 0L) {
          continue;
        }
        // ★★ Z7d-1：同 applyLaborBudgetsInto —— GOV_SERVICE 允许 over-budget（职位保留），PRODUCTION 可用量归 0。
        long availableForProduction = Math.max(0L, budget - govServiceSum);
        if (productionSum <= availableForProduction || productionSum <= 0L) {
          continue; // GOV_SERVICE 已整额装下（或 over-budget 但没有可缩 PRODUCTION）：防御性 no-op
        }
        int productionCount = 0;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          HouseholdLaborCommitment laborCommitment =
              laborCommitments.get(householdAllocationIds.get(i));
          if (laborCommitment != null
              && laborCommitment.kind() != LaborCommitmentKind.GOV_SERVICE) {
            productionCount++;
          }
        }
        long[] productionWeights = new long[productionCount];
        List<LaborAllocationId> productionIds = new ArrayList<>(productionCount);
        int at = 0;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          LaborAllocationId allocationId = householdAllocationIds.get(i);
          HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
          if (laborCommitment == null
              || laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            continue;
          }
          productionIds.add(allocationId);
          productionWeights[at] = Math.max(0L, laborCommitment.laborMilli());
          at++;
        }
        long[] parts =
            ProportionalSplit.byDenominator(
                availableForProduction, productionWeights, productionSum);
        for (int i = 0; i < productionIds.size(); i++) {
          LaborAllocationId allocationId = productionIds.get(i);
          HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
          if (laborCommitment == null) {
            continue;
          }
          if (parts[i] <= 0L) {
            laborCommitments.remove(allocationId);
          } else {
            laborCommitments.put(allocationId, withLaborMilli(laborCommitment, parts[i]));
          }
        }
      }
    }
    // ★★ Z3a/C7：本分区的修剪/预算缩放已跑完 —— 政府承诺整额保留后若越预算，统一具名 ERROR + fail-closed。
    //   本方法签名无 day ⇒ system 来源、事件不带 day。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION_WRITE, -1L);
  }

  /**
   * ★ <b>一个 unit "这一周期真的用得上的劳动"</b>（千分劳动·日）= {@code min(产能那一路, 投入那几路) × laborPerUnit}。
   *
   * <pre>
   * usableScale = min( ⌊assetAllocation[k] ÷ capacityPerUnit[k]⌋ …, ⌊cycleInputUsedMilli[j] ÷ inputPerUnit[j]⌋ … )
   * need        = usableScale × 配方.laborPerUnit
   * </pre>
   *
   * <p>★ 与收获日公式（{@link ProductionEfficiencyBook}）是**同一个算式的两个前缀**：这里**刻意不含劳动那一路**（劳动正是本步要求解的量）。
   */
  static long laborNeedOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      long allocated,
      OperatorCondition condition) {
    if (industry.recipe().laborPerUnit() <= 0L) {
      return allocated; // ★ 劳动那一路**不施加约束**（与收获日公式的同款口径）
    }
    // ★★ P2-B：产能×投入那一路的算式**只有一处拼写点** —— {@link LaborQueueBook#maxAbsorbableLaborMilli}
    //   （本方法保留"laborPerUnit ≤ 0 ⇒ 返回已分配量"的旧包装语义，旧档逐值不变）。
    return LaborQueueBook.maxAbsorbableLaborMilli(unit, industry, index, condition);
  }

  /** 换劳动量（其余字段原样带过）—— 配额缩小与累加共用。 */
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
   * ★★ <b>受方家户的 fail-closed 守卫</b>（H1.3；{@code deliverCohortIntake} 的替代品）：逐条 {@code Payee.ToCohort}
   * 规则判两件事 —— <b>行在</b>、<b>它住在本格</b>。
   *
   * <p>★★ <b>为什么这条守卫必须存在</b>：改前"解析不到行"会补一条 {@code +unresolved} 留在 operator（E17 的等价路径）——
   * 那在"受方是一池人、要按人口分派"的时代是合理的兜底；H1 之后受方就是**那一个家户**（身份一一对应）， "这个家户不存在"只可能是**配置错**（规则指了一个没人住的
   * cohort），把它静默变成"operator 自留"会让账面上完全看不出问题。 本仓纪律：配置错一律当场抛。
   *
   * <p>★ <b>人口为 0 的家户照旧合法</b>（"分配仍可能把钱/物给付给人口 0 的家户" —— 例如地租那条规则）：本守卫只判"行在不在"，
   * 不判人口（判人口会让合法状态被误杀；消费与投入那两条路才按 {@code population > 0} 走，见 {@code settleHexes} / {@code
   * drawCycleInputs}）。
   *
   * <p>★ <b>"住在本格"这一条</b>：家户账的键是 {@code (HouseholdActors.of(cohort), cohort.hex())}，而条目落在 {@code
   * facts.location()} —— 两者不同时，钱会落到这个家户在**别处**的账户上（而它的消费读的是本格的账）⇒ 静默丢失。
   */
  static void requireCohortRows(
      ProductionRules relation,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HexCoord location) {
    for (CompensationRule rule : relation.rules()) {
      HouseholdId household;
      String source;
      if (rule.recipient() instanceof Payee.ToHousehold toHousehold) {
        household = toHousehold.household();
        source = "家户";
      } else if (rule.recipient() instanceof Payee.ToCohort toCohort) {
        // ★ 旧档视图（S1 迁移前）：按视图反查**恰一个**家户；多于一户 ⇒ fail-closed（视图不再是身份）。
        household = null;
        for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
          if (householdEconomy.view().equals(toCohort.cohort())) {
            if (household != null) {
              throw new IllegalStateException(
                  "规则指名的旧 cohort 视图对上了多个家户（S1 起视图不再是唯一身份）：" + toCohort.cohort() + "；规则=" + rule);
            }
            household = householdEconomy.id();
          }
        }
        if (household == null) {
          throw new IllegalStateException(
              "规则指名的 cohort 没有家户行（H1 fail-closed：受方就是那一个家户，交付不出去就当场抛）："
                  + toCohort.cohort()
                  + "；规则="
                  + rule);
        }
        source = "旧 cohort 视图";
      } else {
        continue;
      }
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        throw new IllegalStateException(
            "规则指名的家户没有行（S1 fail-closed：受方就是那一个家户，交付不出去就当场抛）："
                + source
                + "="
                + household
                + "；规则="
                + rule);
      }
      if (!householdEconomy.view().hex().equals(location)) {
        throw new IllegalStateException(
            "规则指名的家户不住在本格（H1：家户账的 location 取自它的视图，条目却落在产业所在格）：activity="
                + relation.activity()
                + " "
                + source
                + "="
                + household
                + " 视图格="
                + householdEconomy.view().hex()
                + " 产业的格="
                + location
                + "；规则="
                + rule);
      }
    }
  }

  /**
   * ★★ <b>本格各家户本期劳动量</b>（{@code LABOR_AMOUNT} 那一族的分子/分母，也是 M1.7 给养义务的"按什么量"）：键 = **行键本身**（H0：行就是
   * cohort），值 = 该行的 {@code rowLabor}（= <b>按参与率折算后的每日可用劳动</b> × cycleDays）。
   *
   * <p>★★ <b>M1.8：折算只有一处拼写点</b> —— {@link HouseholdEconomy#participationAdjustedLaborMilli()}（=
   * {@code laborMilli × participationPerMille ÷
   * 1000}）。本方法<b>不再自己乘一次</b>参与率：否则读口/配额与这里会各折算一遍，真档数字会崩。
   *
   * <p>★★ <b>H0 起它不再"按 (格, 阶层) 并池"（E28 的收口）</b>：改前两池人的劳动被并进同一个 {@code (格, 阶层)}
   * 键（农村行与城镇行），而**受方行**那一侧已由 E24 分开 ⇒ 城市格上"这一格的产出在四个阶层之间怎么分"被另一池人的劳动**参与计权** （自述量级 ≤ 0.04‰）。现在键 =
   * 行键（含居住维）⇒ <b>规则指名的 cohort（含居住类型）只被它自己那批人的劳动计权</b>，并池消失。
   *
   * <p>★ <b>仍然取"本格"的行</b>（判据不变）：「谁在干活」在真档里只写在有人口的那些行上，而规则不区分产业的劳动 —— {@code LABOR_AMOUNT}
   * 的分母是**这一格全部家户**的劳动之和（口径与改前逐字相同，只是键不合并）。★ 于是同一格的农业家户与作坊家户各按自己的劳动 拿各自规则的份额，而不会互相顶替。
   *
   * <p>★ 只放**非零**的行：{@code ProductionSettlement} 的 {@code laborOf} 查不到即 0，而 {@code Σ劳动} 是分母 —— 塞 0
   * 进去不改变任何一个数，只会把表弄脏。
   *
   * <p>★ <b>可见性 public</b>（M1.7）：读口（{@code ApiViews.industryView} 的给养义务一栏）要用**同一个函数**算"按什么劳动量"，
   * 不许在视图层另写一套（两处各写一套 = 读到的义务与实付的义务会漂开）。
   *
   * @param location 产业所在的那一格（{@code IndustryHexKeys.hexKeyOf} 是唯一拼写点）
   * @param cycleDays 该产业的周期天数（把"每日劳动"折成"本周期劳动"；见下面的量纲注释）
   */
  static Map<HouseholdId, Long> laborOfCohort(
      Map<HouseholdId, HouseholdEconomy> householdEconomies, HexCoord location, long cycleDays) {
    Map<HouseholdId, Long> byCohort = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (!householdEconomyEntry.getValue().view().hex().equals(location)) {
        continue; // 只取本格的家户（见方法注释：分母的口径与改前逐字相同）
      }
      HouseholdEconomy householdEconomy = householdEconomyEntry.getValue();
      // ★★ **每日口径 × 周期天数 = 本周期口径**（量纲的收口）：行的 `laborMilli` 与发放的配额同口径
      //   （**每日**千分劳动；结算逐日把它累加进 `cycleLaborMilli`），而 {@code LABOR_AMOUNT} 那一族量的是
      //   **本周期**的劳动量（{@code FIXED_IN_KIND_PER_LABOR} 的"每周期一笔"就写在名字里）。
      //   ★ 少了这个乘数，给养只有应有值的 1/cycleDays —— 实测（真档 14,806 人的格）：一周期只拿到 0.8% 的口粮，
      //     第 2 周期起人吃不饱、也播不下种 ⇒ 生产逐周期崩掉（`EconomyRealScaleClothTest` / `WorldgenInitializeToolTest`
      //     的跨周期用例当场红）。★ 分成类（`OUTPUT_SHARE`）不受影响：那一路是比值，量纲自消。
      //   ★ M1.8：参与率的折算收进 HouseholdEconomy 的唯一算法（上面乘一次，这里不许再乘）。
      long rowLabor = householdEconomy.participationAdjustedLaborMilli() * cycleDays;
      if (rowLabor <= 0L) {
        continue;
      }
      byCohort.put(householdEconomyEntry.getKey(), rowLabor);
    }
    return byCohort;
  }
}
