package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>P0（2026-10-10）：经济腿的一笔迁移人口事实（瞬态 outbox 条目）</b>——{@link ModeMigrationSettlement} 在每笔 {@code
 * MigrationMove} 成功落账后追加一条，由 {@code simos-app} 的 {@code PopulationEconomyTimeParticipant} 在 {@code
 * stepper.step(day)} 之后取走并翻译成 Social 工单。
 *
 * <pre>
 * EconomyPopulationTransfer(
 *     source,       // 源经济行（迁出）；非 null
 *     target,       // 目标经济行（已有或本批新建）；非 null、不得 == source
 *     population,   // 本次搬的人数；必须 > 0
 *     targetHex,    // 目标经济视图所在格；非 null（Social 侧 CREATE_HOUSEHOLD 的位置）
 *     targetMode,   // 目标 mode；非 null
 *     newTarget,    // true = 本批新建的经济行（Social 侧需要 CREATE_HOUSEHOLD）
 *     reason)       // 迁移原因；非 null（进 Social 工单审计）
 * </pre>
 *
 * <p>★★ <b>为什么它是瞬态而不是持久组件</b>：人口权威在 Social；经济行 {@code population} 只是 App 按 Social
 * 真值落下的当日物化视图。outbox 只承载"这一天经济腿执行了哪些人口移动"这一条事实，不新增 {@code EconomyData} 组件、不进 Codec/ChangeSet；重启后同一份
 * {@code MigrationPlan} 重放会产生同一批 outbox（工单 orderId 含 day/序号/source/target，由 Social 侧幂等键挡重复）。
 *
 * <p>★ 本记录只做形状校验（非 null / population &gt; 0 / source != target）；"人够不够、Social 家户在不在" 由 Social
 * 工单受理方（{@code HouseholdWorkOrderBook}）具名拒绝，不在两条路上各判一份。
 *
 * @param source 源家户稳定 id；非 null
 * @param target 目标家户稳定 id；非 null 且不得等于 {@code source}
 * @param population 本次迁移人数；必须 &gt; 0
 * @param targetHex 目标经济视图所在格；非 null
 * @param targetMode 目标 mode；非 null
 * @param newTarget 本批是否新建了目标经济行（Social 侧据此决定是否 CREATE_HOUSEHOLD）
 * @param reason 迁移原因；非 null
 */
public record EconomyPopulationTransfer(
    HouseholdId source,
    HouseholdId target,
    long population,
    HexCoord targetHex,
    ProductionModeId targetMode,
    boolean newTarget,
    String reason) {

  public EconomyPopulationTransfer {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(targetHex, "targetHex");
    Objects.requireNonNull(targetMode, "targetMode");
    Objects.requireNonNull(reason, "reason");
    if (source.equals(target)) {
      throw new IllegalArgumentException("迁移 outbox 的 source 不得 == target: " + source);
    }
    if (population <= 0L) {
      throw new IllegalArgumentException("迁移 outbox 的 population 必须 > 0: " + population);
    }
  }
}
