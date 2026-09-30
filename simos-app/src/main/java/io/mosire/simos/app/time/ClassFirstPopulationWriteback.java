package io.mosire.simos.app.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.HouseholdProductionAccountId;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>R2c：把 social 的月度出生/死亡（{@link LotChange}）接到 classfirst 的阶层池/家户生产账户</b>。
 *
 * <p>它是人口再生产的唯一跨切片落账点：social 是人口源（{@code PopulationDynamics.monthly} 已改完批次 count），classfirst
 * 是生产/账户侧。两侧的维度不同 —— social 是 {@code (格, 居住类型, 年龄, 性别)} 批次， classfirst 是 {@code (池, 家户)} 子账户 ——
 * 故本类按<b>共同的分辨率</b>落账：
 *
 * <ol>
 *   <li>把逐 lot 的出生/死亡先按 <b>{@code (格, 居住类型)} 组</b>求和（{@link LotChange#at()} + {@link
 *       ResidenceKind#ofLot}）；
 *   <li>每组的 classfirst 家户 = {@code householdAccounts} 里经 {@code economy.classes} 的 view 落进该 {@code
 *       (格, 居住类型)} 的账户（与 {@link ClassFirstSocialWriteback} 同一套 seed 映射）；
 *   <li><b>死亡</b>：先按人口权重最大余数法分，再逐户夹到"不能杀出负人口"，夹掉的余量按剩余人口权重在未满户之间继续分 （等价于容量感知的最大余数法，确定性、逐值分完）；
 *   <li><b>出生</b>：落到同组既有家户，按人口权重最大余数法（组内人口为 0 却要出生 ⇒ 两侧已不同步，fail-closed，不猜）；
 *   <li>每个账户：{@code population = 旧人口 − 死亡 + 出生}；{@code laborUnits} 只在死亡上按同比例缩 （{@code labor × (旧人口
 *       − 死亡) ÷ 旧人口}），出生不加劳动 —— 与旧协调器同口径；
 *   <li>经 {@link ClassFirstState#withHouseholdAccounts} 同步重算所属 {@code ClassPool.population/labor}。
 * </ol>
 *
 * <p>★★ <b>守恒</b>：逐组 {@code Δclassfirst人口 == 出生 − 死亡 == Δsocial人口}（本类不写 social，social 已由 monthly
 * 改完；两侧总量相等由调用方断言）；不碰任何库存/货币/土地/债务字段 ⇒ 人口学不进商品守恒式。
 *
 * <p>★ <b>为什么按组而不是按 lot</b>：classfirst 家户没有年龄/性别维，把逐 lot 生死按组求和是两侧分辨率的 上限；"组内怎么分"用人口权重的显式近似，不冒充逐
 * lot 账（与 {@link ClassFirstActorWriteback} 的分摊同款纪律）。
 */
final class ClassFirstPopulationWriteback {

  private ClassFirstPopulationWriteback() {}

  /** 落账结果：新的 classfirst 状态 + 本月实际落下的出生/死亡合计（调用方据此记日志/断言守恒）。 */
  record Applied(ClassFirstState state, long births, long deaths) {

    Applied {
      Objects.requireNonNull(state, "state");
      if (births < 0L || deaths < 0L) {
        throw new IllegalArgumentException("出生/死亡不得为负: " + births + " / " + deaths);
      }
    }
  }

  /**
   * 把 {@code changes} 里的出生/死亡落到 classfirst。
   *
   * @param before 月度结算前的 classfirst 状态（逐日结算之后的状态；只读）
   * @param economy 与 {@code before} 同代的经济切片（提供家户 → {@code (格, 居住类型)} 的 seed 映射）
   * @param changes {@code PopulationDynamics.Outcome.changeList()}（逐 lot 的出生/死亡）
   */
  static Applied apply(ClassFirstState before, EconomyData economy, List<LotChange> changes) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(economy, "economy");
    if (changes == null || changes.isEmpty()) {
      return new Applied(before, 0L, 0L);
    }

    LinkedHashMap<Location, long[]> byLocation = new LinkedHashMap<>();
    long birthsTotal = 0L;
    long deathsTotal = 0L;
    for (LotChange change : changes) {
      if (change == null || change.isEmpty()) {
        continue;
      }
      Location at = new Location(change.at(), ResidenceKind.ofLot(change.group()));
      long[] totals = byLocation.computeIfAbsent(at, ignored -> new long[2]);
      totals[0] = Math.addExact(totals[0], change.births());
      totals[1] = Math.addExact(totals[1], change.deaths());
      birthsTotal = Math.addExact(birthsTotal, change.births());
      deathsTotal = Math.addExact(deathsTotal, change.deaths());
    }
    if (byLocation.isEmpty()) {
      return new Applied(before, 0L, 0L);
    }

    LinkedHashMap<Location, List<HouseholdProductionAccount>> householdsByLocation =
        new LinkedHashMap<>();
    for (HouseholdProductionAccount account : before.householdAccounts().values()) {
      ClassRow row = economy.classes().get(HouseholdId.parse(account.householdId()));
      if (row == null) {
        if (account.population() > 0L) {
          throw new IllegalStateException(
              "人口回写无法定位 classfirst 家户的 (格, 居住类型)：家户在 classes 视图里缺席: "
                  + account.householdId()
                  + " population="
                  + account.population());
        }
        continue; // 空池占位账户（population=0）没有 ClassRow，也不可能承接出生/死亡
      }
      Location at = new Location(row.view().hex(), row.view().residence());
      householdsByLocation.computeIfAbsent(at, ignored -> new ArrayList<>()).add(account);
    }

    LinkedHashMap<HouseholdProductionAccountId, HouseholdProductionAccount> replacements =
        new LinkedHashMap<>();
    for (Map.Entry<Location, long[]> entry : byLocation.entrySet()) {
      Location at = entry.getKey();
      long births = entry.getValue()[0];
      long deaths = entry.getValue()[1];
      if (births == 0L && deaths == 0L) {
        continue;
      }
      List<HouseholdProductionAccount> members = householdsByLocation.get(at);
      if (members == null || members.isEmpty()) {
        throw new IllegalStateException(
            "出生/死亡落在 classfirst 没有任何家户的 (格, 居住类型) 组："
                + at
                + " births="
                + births
                + " deaths="
                + deaths
                + "（两侧人口账已不同步，拒绝静默丢）");
      }
      members.sort(Comparator.comparing(HouseholdProductionAccount::householdId));
      long[] populations = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        populations[i] = members.get(i).population();
      }
      long[] birthShares = distributeBirths(births, populations, at);
      long[] deathShares = distributeDeaths(deaths, populations, at);
      for (int i = 0; i < members.size(); i++) {
        HouseholdProductionAccount account = members.get(i);
        long population = account.population();
        long remaining = population - deathShares[i];
        long labor = population == 0L ? 0L : account.laborUnits() * remaining / population;
        long nextPopulation = remaining + birthShares[i];
        if (nextPopulation < 0L || labor < 0L) {
          throw new IllegalStateException(
              "人口回写产生了负人口/负劳动：" + account.id() + " -> " + nextPopulation + " / " + labor);
        }
        replacements.put(
            account.id(),
            new HouseholdProductionAccount(
                account.id(),
                account.poolId(),
                account.householdId(),
                account.name(),
                nextPopulation,
                account.laborPerCapita(),
                account.participationSharePerMille(),
                labor));
      }
    }
    return new Applied(before.withHouseholdAccounts(replacements), birthsTotal, deathsTotal);
  }

  /** 出生按既有家户人口权重最大余数法；组内人口为 0 却要出生 ⇒ 两侧不同步，fail-closed。 */
  private static long[] distributeBirths(long births, long[] populations, Location at) {
    long[] shares = new long[populations.length];
    if (births == 0L) {
      return shares;
    }
    long total = 0L;
    for (long population : populations) {
      total += population;
    }
    if (total <= 0L) {
      throw new IllegalStateException(
          "出生落在 classfirst 人口为 0 的 (格, 居住类型) 组：" + at + " births=" + births);
    }
    return ClassFirstDistribution.largestRemainder(births, populations);
  }

  /**
   * 死亡按容量感知的人口权重最大余数法：每轮以"剩余可减少人口"为权重分剩余死亡数，逐户夹到 0 后把余量再分给还有人口的户。
   *
   * <p>确定性：轮次内用 {@link ClassFirstDistribution#largestRemainder}（余数降序、同余数下标升序），户序已按 householdId
   * 排序；总死亡 ≤ 总人口（{@code PopulationDynamics} 的每批次封顶保证）⇒ 必然分完。
   */
  private static long[] distributeDeaths(long deaths, long[] populations, Location at) {
    long[] shares = new long[populations.length];
    if (deaths == 0L) {
      return shares;
    }
    long[] capacity = populations.clone();
    long remaining = deaths;
    while (remaining > 0L) {
      long totalCapacity = 0L;
      for (long value : capacity) {
        totalCapacity += value;
      }
      if (totalCapacity <= 0L) {
        throw new IllegalStateException(
            "classfirst 家户人口不足以承接死亡：" + at + " deaths=" + deaths + " remaining=" + remaining);
      }
      long[] parts = ClassFirstDistribution.largestRemainder(remaining, capacity);
      long moved = 0L;
      for (int i = 0; i < capacity.length; i++) {
        long take = Math.min(parts[i], capacity[i]);
        shares[i] += take;
        capacity[i] -= take;
        remaining -= take;
        moved += take;
      }
      if (moved == 0L) {
        throw new IllegalStateException("死亡分摊没有前进（坏数据）：" + at + " remaining=" + remaining);
      }
    }
    return shares;
  }

  /** 人口学落账的共同分辨率：一格 + 一种居住类型。 */
  private record Location(HexCoord hex, ResidenceKind residence) {

    Location {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(residence, "residence");
    }
  }
}
