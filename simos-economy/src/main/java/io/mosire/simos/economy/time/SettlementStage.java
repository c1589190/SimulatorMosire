package io.mosire.simos.economy.time;

/**
 * ★★ <b>日结算阶段的具名序</b>（R1 并行内核）：提交序 {@code (stage, partitionIndex, canonicalKey, intraIndex)} 的第一段。
 *
 * <p>★★ <b>为什么用 enum 而不是字符串</b>：字符串阶段名写错<b>不会编译报错</b>，只会让排序悄悄退化成"按字典序混排"； enum 的 {@link #ordinal()}
 * 是编译期钉死的日序，正是 {@code 依赖图} 里那条先后关系（见计划 §4.3 的日序）。 阶段顺序必须与 {@code
 * EconomySettlement.settleOneDayInto} 的实际执行序一致，否则"提交序"就会把依赖关系读反。
 *
 * <p>★ <b>1/4/8 线程只用这一个序</b>：并行只影响"谁先算完"，不影响任何算式的输入、累积顺序与 tie-break。
 *
 * <p>★★ <b>R2：每个阶段声明自己的分区依据</b>（{@link #partitionBasis()}）—— 这是"并行骨架落在生产代码里"的静态可审计形态： 按
 * hex/区/账户分区的阶段走 {@link PartitionPlan} + {@link AccountIntentBuffer} + {@link
 * SettlementExecutor#commit}； 仍为协调器单线程的阶段（{@link PartitionBasis#COORDINATOR}）也写明原因，禁止退回"共享可变四张会话地图"
 * （R1 起账户只有 {@link AccountSession} 一个活表，见其类注）。
 */
public enum SettlementStage {
  /**
   * ★★ <b>E2 自动生产组织</b>：按 mode + 阶层结构 + 可支配劳动 + 可用 {@code AssetShare} 建立/激活生产单元。
   *
   * <p>★ 它是<b>协调器单线程的前置阶段</b>（ordinal 排第一，实际执行在到货/现扣之前，见 {@code
   * EconomySettlement.settleOneDayInto}）： "哪个阶层的哪一批劳动/哪一份资产应当组成哪条生产活动"要看见全天的全局状态（跨 hex 的既有 unit
   * id、全表资产份额、各批次余量）， 分区会把它拆成互不相见的碎片。本阶段不铸转移、不进 {@code PartitionPlan}/{@code CommitOrder}（它的确定性由稳定
   * id 排序承担）。
   */
  ORGANIZE_PRODUCTION(PartitionBasis.COORDINATOR, "生产组织"),
  /** 到货：在途 → 买方账户（按目的地账户并行；同一买方多票并到同一分区）。 */
  DELIVER_SHIPMENTS(PartitionBasis.ACCOUNT, "到货"),
  /** 周期投入：供方账户 → 产业（按 hex 并行；同格内串行）。 */
  INPUT_DRAW(PartitionBasis.HEX, "周期投入"),
  /** 劳动再分配：只写配额表（按产业 hex 分区；同一批次跨 hex 的全局协调留 R3）。 */
  LABOR_REALLOCATION(PartitionBasis.HEX, "劳动再分配"),
  /** 消费：家户账户 → 日耗（按 hex 并行；同格内串行）。 */
  CONSUMPTION(PartitionBasis.HEX, "消费"),
  /** 收获/关系分账（按 hex 并行；同格内 farm/weave/craft 共享家户账户，必须串行）。 */
  HARVEST(PartitionBasis.HEX, "收获/分账"),
  /** 区内市场（按市场区并行计算订单与撮合意向；协调器按拓扑区序回放，冻结写仍在协调阶段——见 R2/R3 边界）。 */
  LOCAL_MARKET(PartitionBasis.MARKET_REGION, "区内市场"),
  /** 跨区市场（所有邻接区买卖单与在途；P1.2 索引 + 协调器稳定序一次算完，按 buyerRegion 的分区留 R3 的 P3）。 */
  CROSS_REGION_MARKET(PartitionBasis.COORDINATOR, "跨区市场"),
  /** 借粮（按 hex 并行；同格内家户之间串行；跨格借贷不在此阶段）。 */
  LENDING(PartitionBasis.HEX, "借粮"),
  /** 偿还债务（按 debtor account 分组；债权人可能在别格 ⇒ R3 的跨区协调阶段）。 */
  REPAYMENT(PartitionBasis.COORDINATOR, "偿还"),
  /** 饿死判据与劳动缩放（按 PeopleLotId / 产业 hex 分区；死亡缩放需全局同序）。 */
  FAMINE_MORTALITY(PartitionBasis.COORDINATOR, "饿死/劳动缩放"),
  /** 计息（债务表；协调阶段，只读当日起始本金）。 */
  INTEREST(PartitionBasis.COORDINATOR, "计息"),
  /** 人口回写（social 出生/死亡 → 经济行/配额；按 PeopleLotId 分区，协调阶段提交）。 */
  POPULATION_WRITEBACK(PartitionBasis.COORDINATOR, "人口回写"),
  /** 流水组装（读当日全部阶段累加器；必须最后）。 */
  FLOW_ASSEMBLY(PartitionBasis.COORDINATOR, "流水组装"),
  /** 家户迁移/分家（命令面 batch；跨 economy+actor，由组合根协调器提交）。 */
  HOUSEHOLD_MIGRATION(PartitionBasis.COORDINATOR, "家户迁移"),

  /**
   * ★★ <b>P2-D：辖区日税 / GOV 行政俸禄的账户提交阶段</b>。
   *
   * <p>它<b>不在</b> {@code EconomySettlement.settleOneDayInto} 内部，而是由组合根 （{@code
   * PopulationEconomyTimeParticipant}）在 {@code EconomyDayStepper.step(day)} <b>之后</b>提交： 税是家户账户 →
   * 政府家户账户，俸禄是政府家户账户 → 消失（旧 {@code GovDaily} 合约：付款无可信对端）。 ordinal 放在最后 =
   * 与"日结算之后"的实际次序一致；本阶段只含协调器产出的 {@link AccountDelta}， 不参与并行分区计算。
   */
  TAX_AND_UPKEEP(PartitionBasis.COORDINATOR, "辖区日税/行政俸禄");

  /** 阶段实体的分区依据（R2 的静态声明；见类注）。 */
  public enum PartitionBasis {
    /** 按账户 {@code (actor, location)} 的 canonical 串哈希（同一本账必落同一分区）。 */
    ACCOUNT,
    /** 按 {@code HexCoord.toString()}（{@code q_r}）哈希（同一格的产业/家户/账户必落同一分区）。 */
    HEX,
    /** 按市场区 {@code MarketNode.nodeId()} 哈希（一个格恰属一个区）。 */
    MARKET_REGION,
    /** 由协调器单线程处理（跨区/跨主体，或需要全天全局序）。 */
    COORDINATOR
  }

  private final PartitionBasis partitionBasis;
  private final String label;

  SettlementStage(PartitionBasis partitionBasis, String label) {
    this.partitionBasis = partitionBasis;
    this.label = label;
  }

  /** 分区依据（R2；见 {@link PartitionBasis}）。 */
  public PartitionBasis partitionBasis() {
    return partitionBasis;
  }

  /** 中文标签（只服务日志/异常消息，不参与任何判定）。 */
  public String label() {
    return label;
  }
}
