package io.mosire.simos.app.production;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@link ProductionConsumerPort} 的宿主实现（组合根）</b> —— D-032 B4 / §13.14.5 第 1 条、裁定十三。
 *
 * <p>★★ <b>为什么实现必须在 app</b>：本端口要同时回答两类事实，而它们各在一半世界里：
 *
 * <ul>
 *   <li><b>economy 半边</b>：家户行（"某格是否已激活"）、{@code assetShares} / {@code relations} / 生产方式六类引用者 /
 *       阶层结构；
 *   <li><b>production 半边</b>：<b>生产实例</b>（{@code IndustryReferenceCounts#units} 的唯一权威 —— B2b 已把
 *       economy 的 {@code units} 组件删除；"某格是否已激活"的实例那一维同理）。
 * </ul>
 *
 * <p>⇒ production 看不见 economy 实现（enforcer），economy 的切片快照也看不见 production；只有组合根同时认识两边 （这正是 §3.2 "由
 * app 装配，或 economy 快照实现只读 contract" 里被裁定十三选中的那一支）。
 *
 * <p>★★ <b>无状态、每次调用现取 state</b>：本类不持有任何切片引用 ⇒ 同一份实例可跨 revision 复用，读到的永远是调用那一刻的 当刻值（I13：不得跨 revision
 * 陈旧）。装配只有一处：组合根把它 {@code new} 出来，经构造器注入给用它的 production handler / 工具（{@code Shell} / {@code
 * GovWorldBootstrap} / {@code ThreePowersGovBootstrap} / {@code SimosToolSource}）。
 *
 * <p>★★ <b>判据逐条承接 P0（不新增、不放宽、也不丢）</b>：
 *
 * <ul>
 *   <li>{@code industryReferences.units}：旧 P0 数的是 {@code EconomyData.units} 里 {@code industry() ==
 *       id} 的实例 ⇒ <b>现值 = production {@code instances()} 里 {@code plan == id} 的实例</b>（同一语义，换了权威）；
 *   <li>{@code industryReferences.assetShares} / {@code relations}：仍读 economy（{@code relations}
 *       经其实例的 industry 间接命中 —— 实例不存在的那条关系不计，与 P0 的 {@code unit == null} 跳过逐字一致）；
 *   <li>{@code modeReferences}：六类与旧 {@code EconomyGmAdjustments:396} 的遍历逐类对齐（含 {@code
 *       modeTransitions} 的 {@code (from)} / {@code (to)} 后缀）；
 *   <li>{@code isHexEconomicallyActive}：{@code EconomyData.meta} 非空（未激活世界恒 false）∧（家户行所在的格 ∪
 *       <b>落在此格的生产实例</b>）。★ 旧 P0 判据还含"economy 侧的产业定义键"一维：那份副本已按裁定十五删除，本实现 <b>不把它换成</b>"production
 *       定义键" —— 那会把更多格判成已激活（= 放宽创建守卫）；实例那一维才是 B2b 后"这格上真有个生产单位"的 同一件事。
 * </ul>
 *
 * <p>★ <b>装配故障当场炸</b>：缺切片 / 切片类型不符 ⇒ {@link IllegalStateException}（ERROR 事件 + 抛），<b>不是</b> {@code
 * IllegalArgumentException} —— 后者会被 production 命令层折成"业务拒绝 INFO"，把装配错说成用户错（§一.9 日志纪律）。
 */
public final class AppProductionConsumerPort implements ProductionConsumerPort {

  /** 契约/装配故障的日志门面（组合根级事件 ⇒ 与 Shell 同源）。 */
  private static final Logger LOG = AppLog.shell();

  @Override
  public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(hexKey, "hexKey");
    EconomyData economy = economyOf(state);
    if (economy.meta().isEmpty()) {
      return false; // 未激活世界：没有家户行、也没有经济状态（与 P0 的 requireActivatedHex 同判据）
    }
    if (economy.occupiedHexKeys().contains(hexKey)) {
      return true;
    }
    for (ProductionProcess instance : productionStateOf(state).instances().values()) {
      if (hexKey.equals(requireInstanceHexKey(instance))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    EconomyData economy = economyOf(state);
    Map<ProductionUnitId, ProductionProcess> instances = productionStateOf(state).instances();
    // ★★★ B4（§13.14.5 第 1 条②）：units 维按 B2b 后的新权威重算 —— 数 production 的实例，不数已删的 economy units。
    int units = 0;
    for (ProductionProcess instance : instances.values()) {
      if (instance.industry().equals(id)) {
        units++;
      }
    }
    int assetShares = 0;
    for (OwnershipStake share : economy.assetShares().values()) {
      if (share.industry().equals(id)) {
        assetShares++;
      }
    }
    // ★ relations 经它的实例的 industry 间接命中（实例不存在 ⇒ 不计；与 P0 的 `unit == null` 跳过逐字一致）。
    int relations = 0;
    for (ProductionUnitId activity : economy.relations().keySet()) {
      ProductionProcess instance = instances.get(activity);
      if (instance != null && instance.industry().equals(id)) {
        relations++;
      }
    }
    return new IndustryReferenceCounts(units, assetShares, relations);
  }

  @Override
  public ModeReferenceReport modeReferences(SimulationState state, ProductionModeId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    EconomyData economy = economyOf(state);
    List<String> classStructures = new ArrayList<>();
    for (ClassStructure structure : economy.classStructures().values()) {
      if (structure.modeId().equals(id)) {
        classStructures.add(structure.id().value());
      }
    }
    List<String> classPositions = new ArrayList<>();
    for (ProductionRole position : economy.classPositions().values()) {
      if (position.modeId().equals(id)) {
        classPositions.add(position.id().value());
      }
    }
    List<String> productionOrganizations = new ArrayList<>();
    for (ProductionEnterprise enterprise : economy.productionOrganizations().values()) {
      if (enterprise.modeId().equals(id)) {
        productionOrganizations.add(enterprise.id().value());
      }
    }
    List<String> assetRules = new ArrayList<>();
    for (AssetRule rule : economy.assetRules().values()) {
      if (rule.modeId().equals(id)) {
        assetRules.add(rule.id().value());
      }
    }
    List<String> modeTransitions = new ArrayList<>();
    for (ModeTransition transition : economy.modeTransitions().values()) {
      if (transition.fromModeId().equals(id)) {
        modeTransitions.add(transition.id().value() + "(from)");
      }
      if (transition.toModeId().equals(id)) {
        modeTransitions.add(transition.id().value() + "(to)");
      }
    }
    List<String> pledges = new ArrayList<>();
    for (Pledge pledge : economy.pledges().values()) {
      if (pledge.modeId().equals(id)) {
        pledges.add(pledge.id().value());
      }
    }
    return new ModeReferenceReport(
        classStructures,
        classPositions,
        productionOrganizations,
        assetRules,
        modeTransitions,
        pledges);
  }

  /** economy 切片（装配故障 ⇒ ERROR + 抛，不降级）。 */
  private static EconomyData economyOf(SimulationState state) {
    Snapshot slice = state.module("economy").orElse(null);
    if (!(slice instanceof EconomySnapshot snapshot)) {
      throw assemblyFault(
          "state 里没有 economy 切片（或类型不符）: " + (slice == null ? "null" : slice.getClass().getName()));
    }
    return snapshot.data();
  }

  /** production 切片的实例只读投影（装配故障 ⇒ ERROR + 抛，不降级）。 */
  private static ProductionStateLookup productionStateOf(SimulationState state) {
    Snapshot slice = state.module("production").orElse(null);
    if (!(slice instanceof ProductionStateLookup instances)) {
      throw assemblyFault(
          "state 里没有 production 切片（或它未实现 ProductionStateLookup）: "
              + (slice == null ? "null" : slice.getClass().getName()));
    }
    return instances;
  }

  /**
   * 实例属于哪一格（{@link ProductionProcess#hexKey()} 是唯一拼写点；与 {@code ClearRegionDefinitionsHandler}
   * 同一条判据）。
   *
   * <p>★ 判不出来 = 状态自相矛盾（B3 起四处构造点全部 fail-closed ⇒ 不可构造）⇒ 契约故障，不静默当作"不在这一格"。
   */
  private static String requireInstanceHexKey(ProductionProcess instance) {
    return instance
        .hexKey()
        .orElseThrow(
            () ->
                assemblyFault(
                    "生产实例地点不可判（状态自相矛盾）: unit="
                        + instance.id().value()
                        + ", plan="
                        + instance.plan().value()));
  }

  /** 契约/装配故障：ERROR 具名事件 + 抛 {@link IllegalStateException}（不许被折成业务拒绝）。 */
  private static IllegalStateException assemblyFault(String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "PRODUCTION_CONSUMER_PORT_ASSEMBLY_FAULT",
                AppLogSource.SHELL_LIFECYCLE,
                "port",
                AppProductionConsumerPort.class.getSimpleName(),
                "reason",
                reason));
    return new IllegalStateException("ProductionConsumerPort: " + reason);
  }
}
