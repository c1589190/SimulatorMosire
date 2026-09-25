package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code economy.Seed} 命令的处理器（聚合式经济重设计 §十 的 R2a）：**一次把某国全部格的初始经济状态种进 economy 切片** ——一条命令、一条
 * revision（与 {@code social.SetPopulation} 同制）。
 *
 * <p>★★ **它只做 materialize，不做任何公式**：每格人口、阶层比例、土地、有效劳动、库存全在**载荷**里（由 {@code EconomySeeder} 按 §十
 * 的口径算好），本类把它翻成 §3 的领域类型。理由：§八 R1 行"模块化、无公式"是 economy 切片的既定边界，而且
 * "一切数字来自场景参数"这条纪律落在生成器一处即可（两条路各算一遍必然漂移）。
 *
 * <p>★★ **{@code meta} 非空 = 已激活 ⇒ 拒**（§3.3 + §6.6）：本命令是"创世播种"，不是"增量修改"。重放同一份载荷（或对已播种的世界再播一次） 必须以
 * {@code Rejected} 面世、理由**点名"已激活"**——静默覆盖会抹掉既有经济状态且没有任何症状。
 *
 * <p>★ **目标资源**（{@link CommandTargets}）：{@code entries[]} 里**每一个**格的 {@link
 * ResourcePaths#economy(int, int)} （{@code <q>_<r>}）——GM 代执行决策人令时据此逐条判越权（与 {@code
 * SetPopulationHandler} 同款）。
 *
 * <p>★ **校验分工**：形状/类型在本包 {@link EconomyPayloads} 判；数值语义（非负、槽位 ∈ 该产业 slots、{@code progressDays ≤
 * cycleDays}）由 §3 的领域类型与 {@link EconomyData} 构造期守卫判——**不重复实现**。两者的失败都以 {@code Rejected} 出面。
 */
public final class EconomySeedHandler implements CommandHandler, CommandTargets {

  /** 已激活时拒因里必须出现的字样（用例据此判"理由点名已激活"）。 */
  public static final String ALREADY_ACTIVATED_MARKER = "已激活";

  @Override
  public String type() {
    return "economy.Seed";
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return EconomyPayloads.entryHexKeys(EconomyPayloads.parse(payloadJson));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    if (base.meta().isPresent()) {
      return new HandlerOutcome.Rejected(
          "经济切片"
              + ALREADY_ACTIVATED_MARKER
              + "（meta 非空），拒绝重复播种: mapId="
              + base.meta().orElseThrow().mapId());
    }
    try {
      EconomyData next =
          EconomyPayloads.toData(EconomyPayloads.parse(payloadJson), state.meta().timestamp());
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
