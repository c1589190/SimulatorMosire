package io.mosire.simos.economy.codec;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;

/**
 * economy 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} 同制，理由不重复——只记 economy 自己的那点差异。
 *
 * <p>★ <b>树里的自定义键（读侧注册；写侧靠各自的 {@code toString()}）</b>：{@code IndustryId}（{@code industries} /
 * {@code relations} / {@code operatorConditions}）、{@code HouseholdId}（{@code classes} / {@code
 * flows}；旧档的 {@code CohortKey} 规范串由 {@code HouseholdIdDeserializer} 识别并映射成 {@code ofLegacy}）、{@code
 * DebtContractId} （{@code debtContracts}；旧键 {@code debts} 由 {@link EconomyDebtCodecs} 手工迁移）、 {@code
 * CommodityId}（产业产出/投入、行需求、流水与规则里的商品键）、{@code PeopleLotId} （{@code laborSupply}）、{@code
 * LaborAllocationId}（{@code allocations}）、{@code MembershipId} （{@code memberships}）、{@code
 * AssetShareId}（{@code assetShares}；旧档的 {@code use-…} 键字符串由 {@link AssetShareId#parse(String)}
 * opaque 原样读入）、{@code HexCoord}（{@code markets}， 住在 {@code simos-map}）、{@code ShipmentId}（{@code
 * shipments}）。★ 它们都重写了 {@code toString()} 并与 各自的 {@code parse} 互为逆，故只需读侧；键反序列化器照裁定 16
 * 在**本模块**注册，不进共享基座。★ 漏注册的症状是"读档时键 解析不出来"（Jackson 会去调构造器或报 {@code no String-argument
 * constructor}）。★★ E1 追加 {@code ProductionModeId}（{@code modes}）、{@code ClassStructureId}（{@code
 * classStructures}）与 {@code ClassPositionId}（{@code classPositions}，以及 {@code
 * ClassStructure.positions} / {@code ClassStructure.defaultSharesPerMille} / {@code
 * HouseholdClassMembership.retainedShares} 三个嵌套键表）；{@code classStandings} 的键仍是上面已注册的 {@code
 * HouseholdId}。 旧档缺这四个键 ⇒ 快照侧收成空表、变更集侧收成 {@code Unchanged}，见各自的构造器兜底。
 *
 * <p>★ <b>P10.1</b>：{@code merchantFirms}（第 30 个组件）已于 2026-10-10 M-A1 **退役**（运力改派生量）⇒ 本 codec 不再有
 * 它的读写面（用户裁定"旧设计和数据类型直接重建不用留"⇒ 不写旧键忽略/归一代码，旧档按重建处理）。它的键类型 {@code ProductionOrganizationId} 仍由
 * {@code productionOrganizations}（E2）使用，故那个键反序列化器保留。
 *
 * <p>★ <b>P4c3</b>：工资报价唯一权威是 U20 锚 {@code productionWageAnchors}；旧两张工资表及其键注册已删除。
 *
 * <p>★ <b>P4a</b>：{@code periodicAdjustments}（第 31 个组件）的键复用 {@code PeriodicHouseholdAdjustmentId} 的
 * {@code toString()/parse} 配对；规则值 {@link
 * io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment} 按 record 组件绑定（派生读法 {@code sink()}
 * 不用 {@code is} 前缀，避免被 Jackson 当成属性写进线格式）。旧档缺该键 ⇒ 快照侧收成空表、变更集侧收成 {@code Unchanged}，见 {@code
 * EconomyData}/{@code EconomyChangeSet} 构造器兜底。
 *
 * <p>★★ <b>Z1</b>：{@code outputQuantityOverrides}（第 32 个组件）的键 = {@code IndustryId}（已注册），内层键 =
 * {@code CommodityId}（已注册）—— 嵌套 Map 的泛型绑定即可；值 ∈ [0, 1_000_000] 由 {@code EconomyData} 构造期
 * fail-closed。载入时<b>额外做跨表守卫</b>（§3.1）：每一层产业必须存在于 {@code industries}、商品必须是该产业 {@code
 * recipe().outputPerUnit()} 的键，违反 ⇒ {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR + {@link
 * IllegalStateException}（{@link #apply} 的重放路径同款）。{@code productionEfficiency}（第 33 个组件）的键 = {@code
 * ProductionUnitId}（已注册），值 {@link io.mosire.simos.economy.model.ProductionEfficiencyState} 走 record
 * 绑定（五个 long 的 {@code ≥ 0} 由记录构造期判）。 旧档缺这两个节点 ⇒ 空表/中性，见 {@code EconomyData}/{@code
 * EconomyChangeSet} 构造器兜底。
 *
 * <p>★★ <b>B2（2026-10-08）追加 {@code marketZones}</b>（第 36 个组件；约束设计书 §4.2）：键 = {@code MarketZoneId}
 * （已注册），值 = {@code MarketZone}（区 id + 锚格 + 半径 + 成员格 + 法定币 + 发行政府 + 区级官方汇率覆盖）走 record 绑定；旧档缺该节点 ⇒
 * {@code EconomyData} 构造期归一成空表（市场区退回"城市 + tier 半径"派生 ⇒ 既有世界逐值不变）。
 *
 * <p>★ {@code AssetKind} 作键（{@code dailyInputPerUnit}/{@code capacity}）走 Jackson **默认的枚举键** 绑定（按
 * {@code name()}），无需自定义；其余 ID/键类型都重写了 {@code toString()}（= 裸值）并与各自的 {@code parse} 互为逆，故只需读侧。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)}。
 *
 * <p>★★ <b>M2.7 的类结构增量与旧档兼容</b>：{@code HouseholdEconomy.cycleNaturalNeedMilli} 是追加在记录末尾的原始 {@code
 * long} —— Jackson 的记录绑定对<b>缺失的原始组件</b>取默认值 {@code 0}（本批实测过：把该键从 JSON 里删掉仍能读出 0，不抛），这就是"旧档缺该键 ⇒ 0 =
 * 还没开始累计"的兜底；载荷边缘的 {@code EconomyPayloads.householdEconomy} 另有一条 {@code optionalLong(..., 0L)}
 * 的同款兜底。两条都<b>不新增迁移代码</b>：缺键的方向本来就是 fail-closed 的 0。
 */
public final class EconomyCodec implements ModuleCodec, ModuleDiffer {

  @Override
  public String namespace() {
    return "economy";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return EconomyCoreCodecs.readJson(json, EconomyChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return EconomyCoreCodecs.writeJson((EconomyChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    EconomySnapshot snapshot = EconomyCoreCodecs.readJson(json, EconomySnapshot.class);
    // ★★ A1：读回快照 = 状态物化的第二条路径 ⇒ 同一处装词表（否则新进程读旧盘会读回出厂词表）。
    installVocabulary(snapshot.data());
    return snapshot;
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return EconomyCoreCodecs.writeJson(asEconomySnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    EconomySnapshot economyBase = asEconomySnapshot(base);
    EconomyData next = EconomyChangeSet.apply((EconomyChangeSet) changeSet, economyBase.data());
    // ★★ Z1（§3.1）：重放/落盘同样过一遍载入守卫 —— 变更集可能把覆盖表写成悬空引用；违反 ⇒ 契约 ERROR +
    //   fail-closed（构造期已判值域/结构，这里判跨表引用）。
    EconomyCoreCodecs.requireOutputQuantityOverridesValid(next);
    installVocabulary(next);
    return new EconomySnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /**
   * ★★ <b>A1：把世界词表装进 {@code MoneyVocabulary} 的门面</b>（唯一同步点的两处调用之一，另一处是 {@link #decodeSnapshot}）。
   *
   * <p>★★ <b>为什么在 codec 边界而不是别处</b>：状态树<b>只有两条</b>物化路径 —— ① {@code Command → ChangeSet →
   * Revision}（本 {@link #apply}）；② 从盘上读回快照（{@link #decodeSnapshot}，含 checkpoint 与重放）。 两处都过 ⇒
   * "状态每变一次，门面就跟着变一次"；漏掉任何一处都会出现"世界里有 copper、读口却只有 silver"的静默死分支 （阶段 1 的教训：探针必须打在边界**之后**那一侧）。
   *
   * <p>★ 代价如实记：门面是进程级的（最后物化的那个状态说了算），见 {@link MoneyVocabulary} 的类注。
   */
  private static void installVocabulary(EconomyData data) {
    MoneyVocabulary.install(data.currencies().values(), data.moneyInstruments().values());
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link EconomyChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return EconomyChangeSet.between(
        asEconomySnapshot(base).data(), asEconomySnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸（照 {@code LedgerCodec.asLedgerSnapshot}：裸 cast 同样会
   * 抛，但那是**未确认的下转型**，错误信息读不出"这是装配给错了切片"）。
   */
  private static EconomySnapshot asEconomySnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy codec 的切片不是 EconomySnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return economySnapshot;
  }
}
