package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;

/**
 * ★★ <b>生产定义命令所需的"跨切片使用情况"只读端口</b>（D-031 P1 spec §3.2/§3.3）。
 *
 * <p>★★ <b>为什么需要它</b>：定义权威在 production，但"某定义是否仍被运行实例引用""某格是否已有经济状态""某阶层结构是否存在" 只有拥有实例 /
 * 经济状态的切片知道。production 禁依赖 economy 实现 ⇒ 那些事实经本契约问出来。
 *
 * <p>★★ <b>实现归组合根（{@code simos-app}）；方法<b>带 state 入参</b></b>（D-032 B4 / §13.14.5 第 1 条）：
 *
 * <ul>
 *   <li>实现者要同时看见 <b>economy 切片</b>（家户行 / 资产份额 / 关系 / 生产方式引用）与 <b>production 切片</b>
 *       （实例、定义）——"某产业被谁引用"的 {@code units} 维在 B2b 之后<b>只有 production 的实例表</b>能回答 （economy 的 {@code
 *       units} 组件已删）。单看一个切片答不全，故实现只能住在同时认识两边的组合根；
 *   <li>本接口<b>无状态</b>：一份实现可跨 revision 复用，每次调用现取 {@code state} 的当刻切片 ⇒ <b>结构上不可能读到陈旧值</b> （I13）。这与
 *       {@code SocialUnitPort} / {@code ArmyManpowerPort} 的形制相同（app 实现 + state 入参 + 装配期注入）；
 *   <li>production 侧 handler / 工具由组合根在<b>装配期</b>注入实现（构造器 {@code requireNonNull}，缺了当场炸）—— 不再是"从
 *       {@code state.module("economy")} 里向下转型"（那条路要求 economy 快照自己实现本契约，而它看不见 production）。
 * </ul>
 *
 * <p>★ <b>只读、无写口</b>：本端口只回答使用情况，不改任何状态；写动作分别归 production/economy 各自的 ChangeSet。
 *
 * <p>★ <b>装配故障不许降级成业务拒绝</b>：state 缺切片 / 切片类型不符 ⇒ 实现必须抛 {@link IllegalStateException} （<b>不是</b>
 * {@code IllegalArgumentException}——那会被命令层折成"业务拒绝 INFO"，把装配错说成用户错）。
 *
 * @see ProductionConsumerPort.IndustryReferenceCounts
 * @see ProductionConsumerPort.ModeReferenceReport
 */
public interface ProductionConsumerPort {

  /**
   * 该格（规范 {@code <q>_<r>} 键）是否已有已激活的经济状态。
   *
   * <p>★ 判据 = <b>economy 侧的家户行</b> ∪ <b>production 侧落在此格的生产实例</b>（后者是 B2b 前 economy {@code units}
   * 那一维的承接者）。未激活世界（{@code EconomyData.meta} 空）⇒ 恒 false。
   */
  boolean isHexEconomicallyActive(SimulationState state, String hexKey);

  /**
   * 某产业模板的引用计数（三张表：{@code units} / {@code assetShares} / {@code relations}；{@code relations} 经其实例的
   * industry 间接命中）。
   *
   * <p>★★ <b>B4 口径更正</b>：{@code units} 维读 <b>production 的实例只读投影</b>（{@code
   * ProductionStateLookup#instances()}），不再读已删的 economy {@code units} 组件；{@code assetShares} /
   * {@code relations} 仍读 economy 侧（它们是经济侧的表）。
   */
  IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id);

  /** 某生产方式的全部真实引用者（旧 {@code EconomyGmAdjustments:396} 的六类；无引用 ⇒ 全空报告）。 */
  ModeReferenceReport modeReferences(SimulationState state, ProductionModeId id);

  // ★★★ B5（§13.15.2 裁定十八 / §13.15.4）：原 `classStructureExists(state, id)`（旧
  //   `economy.GmAdjust.upsertProductionMode` 的 classStructureId 存在性守卫）**已删** ——
  //   那个命令 kind 已随 `EconomyData.modes` 组件删除而退役 ⇒ 本方法在 main + test 全仓 0 调用点，
  //   契约面不再保留一个没有职责的方法。

  /** 一次产业 upsert 的引用计数（units / assetShares / relations 三张表）。 */
  record IndustryReferenceCounts(int units, int assetShares, int relations) {

    public IndustryReferenceCounts {
      if (units < 0 || assetShares < 0 || relations < 0) {
        throw new IllegalArgumentException(
            "IndustryReferenceCounts 不得为负: units="
                + units
                + ", assetShares="
                + assetShares
                + ", relations="
                + relations);
      }
    }

    /** 三张表的引用总数（"是否允许原地改"的判据用它）。 */
    public int total() {
      return units + assetShares + relations;
    }

    public boolean isEmpty() {
      return total() == 0;
    }
  }

  /**
   * 一次 mode 停用检查的引用者清单（六类；展示串与旧 {@code deactivateProductionMode} 逐字对齐）。
   *
   * <p>★ 空报告 = 无引用 ⇒ 允许停用。
   */
  record ModeReferenceReport(
      List<String> classStructures,
      List<String> classPositions,
      List<String> productionOrganizations,
      List<String> assetRules,
      List<String> modeTransitions,
      List<String> pledges) {

    public ModeReferenceReport {
      classStructures = List.copyOf(classStructures);
      classPositions = List.copyOf(classPositions);
      productionOrganizations = List.copyOf(productionOrganizations);
      assetRules = List.copyOf(assetRules);
      modeTransitions = List.copyOf(modeTransitions);
      pledges = List.copyOf(pledges);
    }

    public boolean isEmpty() {
      return classStructures.isEmpty()
          && classPositions.isEmpty()
          && productionOrganizations.isEmpty()
          && assetRules.isEmpty()
          && modeTransitions.isEmpty()
          && pledges.isEmpty();
    }

    /** 旧 {@code deactivateProductionMode} 的拒绝理由片段（逐类具名列出引用者）。 */
    public String describe() {
      StringBuilder text = new StringBuilder();
      append(text, "classStructures", classStructures);
      append(text, "classPositions", classPositions);
      append(text, "productionOrganizations", productionOrganizations);
      append(text, "assetRules", assetRules);
      append(text, "modeTransitions", modeTransitions);
      append(text, "pledges", pledges);
      return text.toString();
    }

    private static void append(StringBuilder text, String name, List<String> values) {
      if (values.isEmpty()) {
        return;
      }
      if (text.length() > 0) {
        text.append('，');
      }
      text.append(name).append('=').append(values);
    }
  }
}
