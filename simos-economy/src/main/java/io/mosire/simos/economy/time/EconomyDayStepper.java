package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.FlowRow;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ **逐日结算的会话**（R4；给 {@code simos-app} 的人口—经济协调器用）：把"一次推 N 天"的**内部日循环**开放给 **唯一同时看得见两个切片的调用方**。
 *
 * <p>★★ **为什么必须有它**（而不是把 {@code settleOneDay} 直接公开）：日循环里有一个**跨日存活的可变累加器** （本期的流水；见 {@code
 * EconomySettlement.settle} 的实现）。把它作为公开方法的入参交出去，等于把"哪一份累加器" 这件事变成调用方的责任 ——
 * 而它错了不会报错，只会让流水少记几天。本类把这个可变状态**收进一个对象**， 对外只出"推进一天 / 交回状态"两件事（与 {@code EconomySettlement.settle}
 * 的纯函数形态**共用同一份实现**： 本类的方法体就是转调它）。
 *
 * <p>★★ **谁用它、为什么它必须存在**：{@code PopulationEconomyTimeParticipant}（住在 {@code simos-app}）要在
 * **每一天**的经济结算之后读当天发生额（算生理压力）、并在月度边界把出生/死亡**回写**经济侧。若它改用 {@code EconomySettlement.settle(base,
 * from, to)} 一次算完，就再也插不进"每一天之后"这一步； 而若把它拆成"N 次独立推进"，{@code range.to} 的语义（一条 revision）与 §十一 等价性都会走样
 * —— **日循环的语义必须留在同一个调用栈里**。
 *
 * <p>★ **它是可变对象**（唯一的一个：内部持有累加器），故**不共享、不并发**：一次推进一个实例（用完即弃）。
 *
 * <p>★ **{@link #finish()} 之前拿到的 {@link #data()} 里流水还是旧的**（累加器在会话里）：日循环结束后由 {@code finish()} 一次性挂上
 * —— 与 {@code EconomySettlement.settle} 的收尾完全同款。
 */
public final class EconomyDayStepper {

  private final boolean plantingDrawsFirst;
  private final int famineMortalityPerMille;
  private EconomyData data;
  private final LinkedHashMap<CohortKey, FlowRow> flows;

  /** 从 {@code base} 起步（流水累加器以 base 已累计的本期流水为起点，与 {@code settle} 同款）。 */
  public EconomyDayStepper(EconomyData base) {
    this(base, EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION);
  }

  /**
   * 同 {@link #EconomyDayStepper(EconomyData)}，但**播种次序可注入**（见 {@code
   * EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION}）。
   */
  public EconomyDayStepper(EconomyData base, boolean plantingDrawsFirst) {
    this(base, plantingDrawsFirst, EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★ 先播种还是先吃饭（{@code plantingDrawsFirst}）**与致死率都可注入**（**包内可见**）—— 两个旋钮的理由逐字见 {@link
   * EconomySettlement#PLANTING_DRAWS_BEFORE_CONSUMPTION} 与 {@link
   * EconomySettlement#FAMINE_MORTALITY_PER_MILLE}：它们**不是死分支**，故必须有路真的走得到，而 {@code simos-app}
   * 的协调器只该看到公开入口那份默认值。
   */
  EconomyDayStepper(EconomyData base, boolean plantingDrawsFirst, int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    this.data = base;
    this.plantingDrawsFirst = plantingDrawsFirst;
    this.famineMortalityPerMille = famineMortalityPerMille;
    this.flows = new LinkedHashMap<>(base.flows());
  }

  /** 当前状态（**流水尚未挂上**：见类注）。 */
  public EconomyData data() {
    return data;
  }

  /** 本期的流水累加器（**只读视图**；键序 = 行的插入序）。 */
  public Map<CohortKey, FlowRow> flows() {
    return Collections.unmodifiableMap(flows);
  }

  /**
   * ★★ **结算一天**（{@code day} 是绝对世界日）：与 {@code EconomySettlement.settleOneDay} 是**同一条实现**， 并**交回当天**的
   * {@link ProductionLedger}（S1 阶段 4+5 Task 4；裁定 E7 的核心）。
   *
   * <p>★★ **为什么必须交回它**（而不是"结算完就完事"）：产出自本阶段起<b>不再写进阶层行</b> —— 它变成产权条目 （{@code +净产 → operator}
   * 与关系规则的转出/收入），而**产权住在 {@code simos-actor}**：economy 切片刻意不认识它 （铁律 3）。⇒ "把这一天离开 {@code ClassRow}
   * 的东西交给看得见 actor 那一侧的人"就是本方法的返回值。 ★ <b>扔掉它 = 静默丢产出</b>，所以它<b>不是</b>一个可选的回调、也不是一个字段：它是返回值。
   *
   * <p>★ 单模块用例（只装 economy 的世界）可以照旧忽略返回值 —— 那里<b>没有 actor 账户可落</b>，行侧账由 {@code harvest} 自己落完（R5 ③）。
   *
   * <p>★ 与 {@code settle(base, from, to)} 的等价性因此是构造性的：那边的日循环调的就是这里调的东西。★ 反过来， {@code settle} 已
   * **fail-closed**（关账要产出就抛）—— 单模块的多日推进请走本类。
   *
   * @return 当天的发生额（毛产 / 损耗 / 投入 / 产权条目 / cohort 入账 / 货币待办；什么都没发生 ⇒ {@link
   *     ProductionLedger#empty()}）
   * @throws IllegalArgumentException {@code day < 1}（创世是第 0 天，没有"第 0 天"这一天）
   */
  public ProductionLedger step(long day) {
    if (day < 1L) {
      throw new IllegalArgumentException("结算的日号必须 ≥ 1（创世是第 0 天）: " + day);
    }
    // ★ 每天一个**新的**累加器：它记的是"这一天"（跨日累计会让调用方重复落账，见 ProductionLedger 的类注）。
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator();
    data =
        EconomySettlement.settleOneDay(
            data, day, flows, plantingDrawsFirst, famineMortalityPerMille, ledger);
    return ledger.toLedger();
  }

  /**
   * ★★ **把一份"逐批次的出生/死亡"回写到经济侧**（行人口、劳动配额与流水）—— 转调 {@link
   * EconomySettlement#applyPopulationChange}，并把流水累加器重新对齐（那份实现会带出自己的流水副本）。
   */
  public void applyPopulationChange(List<LotChange> changes) {
    Objects.requireNonNull(changes, "changes");
    if (changes.isEmpty()) {
      return;
    }
    EconomyData attached = data.withFlows(new LinkedHashMap<>(flows));
    data = EconomySettlement.applyPopulationChange(attached, changes);
    flows.clear();
    flows.putAll(data.flows());
  }

  /** 收尾：把累加器挂上，交出可以进变更集的**最终状态**。 */
  public EconomyData finish() {
    data = data.withFlows(flows);
    return data;
  }
}
