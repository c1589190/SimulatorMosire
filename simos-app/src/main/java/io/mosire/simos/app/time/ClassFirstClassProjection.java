package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.model.ClassRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>R2c：从 classfirst 家户账户聚合出只读 {@link ClassRow} 投影</b>。
 *
 * <p>CLASS_FIRST profile 的 {@code economy.classes} 不再是权威（权威是 {@link ClassFirstState} 的池 + {@code
 * householdAccounts}），但旧读口 / social↔economy 的映射仍按 {@code ClassRow.view} 找格与居住类型 ⇒ R2c
 * 保留一行"视图"，并在每次推进后按家户账户重算：
 *
 * <ul>
 *   <li>{@code population} = 该 household 账户人口；
 *   <li>{@code laborMilli} = 该账户的 {@code laborUnits}（千分劳动/日）；
 *   <li>{@code view.stratum} = 账户当前所属池的位置经 {@link EconomySeeder#classFirstStratumOf} 翻回社会阶层槽位
 *       （迁移只搬人/改池归属，不改 view 的格与居住类型）；
 *   <li>{@code naturalNeeds}/{@code effectiveDemand}/{@code
 *       cycleNaturalNeedMilli}：按人口比例缩放（只读视图的确定近似）；
 *   <li>{@code money}：直接读 actor 家户账的余额合计（与 actor 写回同源；账户缺席时保留旧值）。
 * </ul>
 *
 * <p>★★ <b>它绝不反向写 classfirst</b>：只做"状态 → 行"的投影；行里的 population/labor/money 都不再被任何生产路径读作权威。 键集
 * （{@link HouseholdId}）一字不动 ⇒ 地址、actor 账键、social 映射不因投影漂移。
 */
final class ClassFirstClassProjection {

  private ClassFirstClassProjection() {}

  static EconomyData project(EconomyData economy, ClassFirstState state, ActorData actor) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(actor, "actor");
    if (economy.classes().isEmpty() || state.householdAccounts().isEmpty()) {
      return economy;
    }

    LinkedHashMap<String, HouseholdProductionAccount> byHousehold = new LinkedHashMap<>();
    for (HouseholdProductionAccount account : state.householdAccounts().values()) {
      byHousehold.put(account.householdId(), account);
    }

    LinkedHashMap<HouseholdId, ClassRow> rows = new LinkedHashMap<>();
    boolean changed = false;
    for (Map.Entry<HouseholdId, ClassRow> entry : economy.classes().entrySet()) {
      ClassRow row = entry.getValue();
      HouseholdProductionAccount account = byHousehold.get(row.id().value());
      if (account == null) {
        rows.put(entry.getKey(), row); // 0 人口 seed 行 / 无家户账户的旧行：原样保留
        continue;
      }
      ClassPool pool = state.classPools().get(account.poolId());
      if (pool == null) {
        throw new IllegalStateException(
            "classfirst 家户账户指名的池不存在（状态损坏）：" + account.id() + " -> " + account.poolId());
      }
      long population = account.population();
      long laborMilli = account.laborUnits();
      long money = actorMoney(actor, row);
      ClassRow projected =
          new ClassRow(
              row.id(),
              new CohortKey(
                  row.view().hex(),
                  row.view().residence(),
                  EconomySeeder.classFirstStratumOf(pool.classPositionId())),
              population,
              laborMilli,
              row.participationPerMille(),
              money,
              row.debts(),
              scale(row.naturalNeeds(), row.population(), population),
              scale(row.effectiveDemand(), row.population(), population),
              scale(row.cycleNaturalNeedMilli(), row.population(), population));
      rows.put(entry.getKey(), projected);
      if (!projected.equals(row)) {
        changed = true;
      }
    }
    return changed ? economy.withClasses(rows) : economy;
  }

  /** actor 家户账的余额合计；账户缺席 ⇒ 保留旧值（不凭空造钱、也不把旧值当权威断言）。 */
  private static long actorMoney(ActorData actor, ClassRow row) {
    GoodsAccount account =
        actor.accounts().get(new GoodsAccountKey(HouseholdActors.of(row.id()), row.view().hex()));
    if (account == null) {
      return row.money();
    }
    long money = 0L;
    for (long value : account.money().values()) {
      money = Math.addExact(money, value);
    }
    return money;
  }

  /** 逐商品按人口比例缩放（只读视图的确定近似；旧人口为 0 或人数不变 ⇒ 原样）。 */
  private static Map<CommodityId, Long> scale(
      Map<CommodityId, Long> values, long oldPopulation, long newPopulation) {
    if (values.isEmpty() || oldPopulation == 0L || oldPopulation == newPopulation) {
      return values;
    }
    LinkedHashMap<CommodityId, Long> scaled = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : values.entrySet()) {
      scaled.put(entry.getKey(), entry.getValue() * newPopulation / oldPopulation);
    }
    return scaled;
  }

  private static long scale(long value, long oldPopulation, long newPopulation) {
    if (oldPopulation == 0L || oldPopulation == newPopulation) {
      return value;
    }
    return value * newPopulation / oldPopulation;
  }
}
