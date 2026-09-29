package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Objects;

/**
 * ★★ <b>质押/抵押的基础形状</b>（E4a；理想架构 §5.4）：一条“某债务合同以某资产份额的多少、在哪套生产方式 下、以什么优先级作质押”的记录。
 *
 * <pre>
 * Pledge(id, debtContractId, assetShareId, quantity, modeId, priority,
 *        status: ACTIVE / RELEASED / EXECUTED)
 * </pre>
 *
 * <p>★★ <b>E4a 只落形状、Codec 与守卫；清算行为明确留 E5</b>：
 *
 * <ul>
 *   <li>本类不解释 {@code priority}（跨质押的稳定处置序要看见整张表，属 E5 清算阶段）；
 *   <li>不产生 {@code EXECUTED}/{@code RELEASED} 的写路径（E5 的处置/释放才写）；
 *   <li>{@code Σ活跃质押 ≤ share.quantity} 的跨表守卫在 {@code EconomyData} 构造期按“对侧已提供”分段生效 （资产份额表为空 = 该侧尚未提供
 *       ⇒ 只判结构；非空 ⇒ 逐 {@code ACTIVE} 质押求和判上界）。
 * </ul>
 *
 * <p>★ <b>不变量</b>：{@code quantity ≥ 0}、{@code priority ≥ 0}；三处引用与状态不得为 null。
 *
 * @param id 质押稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param debtContractId 被担保的债务合同；不得为 null
 * @param assetShareId 用作质押的资产份额；不得为 null
 * @param quantity 质押数量（与份额同计量单位）；不得为负
 * @param modeId 清算/规则所属的生产方式；不得为 null
 * @param priority 处置优先级（≥ 0；越小越先，解释权在 E5）
 * @param status 质押状态；不得为 null
 */
public record Pledge(
    PledgeId id,
    DebtContractId debtContractId,
    AssetShareId assetShareId,
    long quantity,
    ProductionModeId modeId,
    int priority,
    Status status) {

  /** 质押状态：活跃 / 已释放 / 已执行。E4a 只允许构造，不产生转移路径（E5 接线）。 */
  public enum Status {
    /** 质押有效，计入“Σ活跃质押 ≤ share.quantity”守卫。 */
    ACTIVE,
    /** 已释放（E5 的偿还/解除路径写入）。 */
    RELEASED,
    /** 已执行（E5 的清算处置写入）。 */
    EXECUTED
  }

  public Pledge {
    Objects.requireNonNull(id, "Pledge.id 不得为 null");
    Objects.requireNonNull(debtContractId, "Pledge.debtContractId 不得为 null");
    Objects.requireNonNull(assetShareId, "Pledge.assetShareId 不得为 null");
    Objects.requireNonNull(modeId, "Pledge.modeId 不得为 null");
    Objects.requireNonNull(status, "Pledge.status 不得为 null");
    if (quantity < 0L) {
      throw new IllegalArgumentException("Pledge.quantity 不得为负: " + quantity);
    }
    if (priority < 0) {
      throw new IllegalArgumentException("Pledge.priority 不得为负: " + priority);
    }
  }
}
