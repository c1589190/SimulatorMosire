package io.mosire.simos.economy.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>7 hex 家户“按生产方式预期利润加权迁移”探针</b>（用户 2026-10-06 裁定）。
 *
 * <p>它与既有 {@link ProbeEconomy} 不同：验证的不是“同一家户原地改 mode 字段”，而是：
 *
 * <pre>
 * 家户 = 人口 + 货币 + 债务 + 当前 mode
 * 每 tick：算各 mode/hex 的预期利润 π
 *   对每个家户，把可迁人口按 max(0, π_target − π_current) 加权拆分
 *   迁走人口按比例带走债务与货币
 *   目标：邻 hex 已有同 mode 且有空位的家户（合并）
 *        或符合条件的邻 hex（新建一个该 mode 家户）
 *   源户可缩编，也可人口迁空后消亡
 * 流民 DISPLACED π=0，可迁往任何正利润 mode；迁移时债务随人走
 * </pre>
 *
 * <p>★★ <b>本轮刻意的简化</b>：这是迁移规则探针，不跑完整生产/市场分配。
 *
 * <ul>
 *   <li>π 是具名参数信号，用于产生迁移权重；
 *   <li>货币只随人口迁移而移动，不新造不销毁；
 *   <li>债务只由流民每 tick 按人头增长（模拟“没收入仍要吃饭”），迁移时按人口比例随行；
 *   <li>不做死亡/出生/资产买卖；这些留给正式运行时。
 * </ul>
 *
 * <p>7 hex = 中心城 {@code C} + 环绕的 {@code R0..R5}。城市只允许手工业/商人；农村允许佃农/雇农/流民。 城市利润随该 mode
 * 城市人口上升而下降，因此会形成均衡，而不是全部挤进城市。
 */
class HomeModeMigrationProbeTest {

  /** 每 tick 每户最多迁出的人口份额（‰）：10‰ = 1%/tick。 */
  private static final long MIGRATION_PER_MILLE = 10L;

  /** 流民每个每人每 tick 新增的债务。 */
  private static final long DISPLACED_DEBT_PER_PERSON_PER_TICK = 1L;

  /** 新建家户时单户最多装多少人（与 {@link Mode#capacityPerHousehold()} 保持一致）。 */
  private static final long MAX_NEW_PER_HOUSEHOLD = 200L;

  /** 跑满 3650 tick。 */
  private static final int TICKS = 3650;

  @Test
  void householdsMigrateByWeightedProfitAndKeepPopulationMoneyDebtConserved() {
    World first = simulate();
    World second = simulate();

    assertThat(second.fingerprint()).as("同一输入两次运行必须逐字段相同（确定性）").isEqualTo(first.fingerprint());
    assertThat(first.totalPopulation()).as("人口总量守恒").isEqualTo(first.initialPopulation);
    assertThat(first.totalMoney()).as("货币只随迁移移动，不新造不销毁").isEqualTo(first.initialMoney);
    assertThat(first.totalDebt())
        .as("债务 = 初始 + 流民新增；迁移不丢债")
        .isEqualTo(first.initialDebt + first.displacedDebtGrowth);
    assertThat(first.households.values())
        .allSatisfy(
            household -> {
              assertThat(household.population).as("人口不得为负: %s", household.id).isNotNegative();
              assertThat(household.money).as("货币不得为负: %s", household.id).isNotNegative();
              assertThat(household.debt).as("债务不得为负: %s", household.id).isNotNegative();
            });

    long initialCity =
        first.initialPopulationOf(Mode.HANDICRAFT) + first.initialPopulationOf(Mode.MERCHANT);
    long finalCity =
        first.totalPopulationOf(Mode.HANDICRAFT) + first.totalPopulationOf(Mode.MERCHANT);
    assertThat(finalCity)
        .as("手工业 + 商人人口显著上升：初始 %s ⇒ 终局 %s", initialCity, finalCity)
        .isGreaterThan(initialCity);
    assertThat(finalCity).as("城市模式吸收多数人口").isGreaterThanOrEqualTo(first.initialPopulation / 2L);
    assertThat(first.totalPopulationOf(Mode.DISPLACED))
        .as("流民被正利润模式吸收")
        .isLessThan(first.initialPopulationOf(Mode.DISPLACED));
    assertThat(first.merges).as("至少一次合并进已有家户").isPositive();
    assertThat(first.creations).as("至少一次新建家户").isPositive();
    assertThat(first.extinctions).as("至少一次源户迁空后消亡").isPositive();
    assertThat(first.migrations).as("发生过人口迁移").isPositive();
  }

  private static World simulate() {
    World world = new World();
    System.out.println(world.line(0));
    for (int tick = 1; tick <= TICKS; tick++) {
      world.tick();
      if (tick == 120 || tick == 1200 || tick == TICKS) {
        System.out.println(world.line(tick));
      }
    }
    return world;
  }

  /** 生产方式：本轮探针只定义预期利润、单户承载与每格上限。 */
  enum Mode {
    TENANCY_FIXED(200L, 3000L, false),
    WAGE_FARM(200L, 2500L, false),
    HANDICRAFT(200L, 1200L, true),
    MERCHANT(200L, 700L, true),
    DISPLACED(300L, 2000L, false);

    private final long capacityPerHousehold;
    private final long maxPerHex;
    private final boolean cityOnly;

    Mode(long capacityPerHousehold, long maxPerHex, boolean cityOnly) {
      this.capacityPerHousehold = capacityPerHousehold;
      this.maxPerHex = maxPerHex;
      this.cityOnly = cityOnly;
    }

    long capacityPerHousehold() {
      return capacityPerHousehold;
    }

    long maxPerHex() {
      return maxPerHex;
    }

    boolean canLiveIn(Hex hex) {
      return !cityOnly || hex.city;
    }
  }

  /** 一个 hex：城市只允许手工业/商人；fertility 是佃农/雇农利润的固定加成。 */
  record Hex(String id, int q, int r, boolean city, int fertility) {

    int distance(Hex other) {
      int dq = q - other.q;
      int dr = r - other.r;
      return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    boolean sameOrAdjacent(Hex other) {
      return distance(other) <= 1;
    }

    @Override
    public String toString() {
      return id;
    }
  }

  /** 可变家户。 */
  static final class Household {
    final String id;
    final Hex hex;
    Mode mode;
    long population;
    long money;
    long debt;

    Household(String id, Hex hex, Mode mode, long population, long money, long debt) {
      this.id = id;
      this.hex = hex;
      this.mode = mode;
      this.population = population;
      this.money = money;
      this.debt = debt;
    }

    long capacityFree() {
      return Math.max(0L, mode.capacityPerHousehold() - population);
    }

    String key() {
      return hex.id + "|" + mode;
    }

    @Override
    public String toString() {
      return "Household["
          + id
          + ","
          + hex
          + ","
          + mode
          + ",pop="
          + population
          + ",money="
          + money
          + ",debt="
          + debt
          + "]";
    }
  }

  /** 一条实际迁移。 */
  private record Move(Household origin, Household target, long population, long money, long debt) {}

  /** 一个候选 (hex, mode) 及其权重。 */
  private record TargetWeight(Hex hex, Mode mode, long weight) {}

  /** 7 hex 最小世界。 */
  static final class World {
    final List<Hex> hexes;
    final TreeMap<String, Household> households = new TreeMap<>();
    private long nextSequence = 9L;

    long migrations;
    long merges;
    long creations;
    long extinctions;
    long displacedDebtGrowth;

    final long initialPopulation;
    final long initialMoney;
    final long initialDebt;
    private final Map<Mode, Long> initialPopulationByMode = new LinkedHashMap<>();

    World() {
      hexes =
          List.of(
              new Hex("C", 0, 0, true, 0),
              new Hex("R0", 1, 0, false, 1),
              new Hex("R1", 1, -1, false, 2),
              new Hex("R2", 0, -1, false, 0),
              new Hex("R3", -1, 0, false, 1),
              new Hex("R4", -1, 1, false, 2),
              new Hex("R5", 0, 1, false, 0));
      Hex c = hex("C");
      add(new Household("H1", hex("R0"), Mode.TENANCY_FIXED, 250, 1000, 500));
      add(new Household("H2", hex("R1"), Mode.TENANCY_FIXED, 250, 1000, 500));
      add(new Household("H3", hex("R2"), Mode.WAGE_FARM, 220, 900, 400));
      add(new Household("H4", hex("R3"), Mode.WAGE_FARM, 180, 800, 300));
      add(new Household("H5", hex("R4"), Mode.TENANCY_FIXED, 200, 900, 400));
      add(new Household("H6", hex("R5"), Mode.DISPLACED, 100, 100, 200));
      add(new Household("H7", c, Mode.HANDICRAFT, 100, 2000, 100));
      add(new Household("H8", c, Mode.MERCHANT, 50, 1500, 50));
      initialPopulation = totalPopulation();
      initialMoney = totalMoney();
      initialDebt = totalDebt();
      for (Mode mode : Mode.values()) {
        initialPopulationByMode.put(mode, totalPopulationOf(mode));
      }
    }

    Hex hex(String id) {
      return hexes.stream().filter(h -> h.id.equals(id)).findFirst().orElseThrow();
    }

    void add(Household household) {
      households.put(household.id, household);
    }

    long initialPopulationOf(Mode mode) {
      return initialPopulationByMode.getOrDefault(mode, 0L);
    }

    long totalPopulation() {
      return households.values().stream().mapToLong(h -> h.population).sum();
    }

    long totalPopulationOf(Mode mode) {
      return households.values().stream()
          .filter(h -> h.mode == mode)
          .mapToLong(h -> h.population)
          .sum();
    }

    long totalMoney() {
      return households.values().stream().mapToLong(h -> h.money).sum();
    }

    long totalDebt() {
      return households.values().stream().mapToLong(h -> h.debt).sum();
    }

    long totalDebtOf(Mode mode) {
      return households.values().stream().filter(h -> h.mode == mode).mapToLong(h -> h.debt).sum();
    }

    long householdsOf(Mode mode) {
      return households.values().stream().filter(h -> h.mode == mode).count();
    }

    /** 一个 tick：流民计债 → 利润快照 → 逐户迁移 → 清理空壳户。 */
    void tick() {
      displaceTick();
      Map<Mode, Long> cityPopulationByMode = cityPopulationByMode();
      Map<Hex, Map<Mode, Long>> profits = profitSnapshot(cityPopulationByMode);
      Map<String, Long> capacityLedger = capacityLedger();

      List<Household> origins =
          households.values().stream().sorted(Comparator.comparing(h -> h.id)).toList();
      for (Household origin : origins) {
        if (households.containsKey(origin.id) && origin.population > 0L) {
          migrateOne(origin, profits, capacityLedger);
        }
      }
      cleanupEmptyHouseholds();
    }

    private void displaceTick() {
      for (Household household : households.values()) {
        if (household.mode == Mode.DISPLACED && household.population > 0L) {
          long added = Math.multiplyExact(household.population, DISPLACED_DEBT_PER_PERSON_PER_TICK);
          household.debt = Math.addExact(household.debt, added);
          displacedDebtGrowth = Math.addExact(displacedDebtGrowth, added);
        }
      }
    }

    private void migrateOne(
        Household origin, Map<Hex, Map<Mode, Long>> profits, Map<String, Long> capacityLedger) {
      long currentProfit = profitAt(origin.mode, origin.hex, profits);
      long moveable =
          Math.min(
              origin.population, Math.max(1L, origin.population * MIGRATION_PER_MILLE / 1000L));
      if (moveable <= 0L) {
        return;
      }

      List<TargetWeight> targets = new ArrayList<>();
      for (Hex hex : hexes) {
        if (!hex.sameOrAdjacent(origin.hex)) {
          continue;
        }
        for (Mode mode : Mode.values()) {
          if (hex.equals(origin.hex) && mode == origin.mode) {
            continue;
          }
          if (!mode.canLiveIn(hex) || availableCapacity(hex, mode, capacityLedger) <= 0L) {
            continue;
          }
          long weight = Math.max(0L, profitAt(mode, hex, profits) - currentProfit);
          if (weight > 0L) {
            targets.add(new TargetWeight(hex, mode, weight));
          }
        }
      }
      if (targets.isEmpty()) {
        return;
      }

      long[] shares =
          splitByWeights(moveable, targets.stream().mapToLong(TargetWeight::weight).toArray());
      List<Move> moves = new ArrayList<>();
      for (int i = 0; i < targets.size(); i++) {
        long count = shares[i];
        if (count <= 0L) {
          continue;
        }
        TargetWeight target = targets.get(i);
        long remaining = count;
        while (remaining > 0L) {
          long room = availableCapacity(target.hex(), target.mode(), capacityLedger);
          if (room <= 0L) {
            break;
          }
          Household existing = firstWithCapacity(target.hex(), target.mode());
          if (existing != null) {
            long take = Math.min(remaining, Math.min(existing.capacityFree(), room));
            if (take <= 0L) {
              break;
            }
            moves.add(new Move(origin, existing, take, 0L, 0L));
            capacityLedger.merge(existing.key(), take, Math::addExact);
            remaining -= take;
            merges++;
            continue;
          }
          long create = Math.min(remaining, Math.min(room, MAX_NEW_PER_HOUSEHOLD));
          if (create <= 0L) {
            break;
          }
          Household created =
              new Household("H" + (nextSequence++), target.hex(), target.mode(), 0L, 0L, 0L);
          add(created);
          moves.add(new Move(origin, created, create, 0L, 0L));
          capacityLedger.merge(created.key(), create, Math::addExact);
          remaining -= create;
          creations++;
        }
      }

      long movedPopulation = moves.stream().mapToLong(Move::population).sum();
      if (movedPopulation <= 0L) {
        return;
      }

      long moneyMoved = 0L;
      long debtMoved = 0L;
      long populationBefore = origin.population;
      boolean emptiesOrigin = movedPopulation == populationBefore;
      for (int i = 0; i < moves.size(); i++) {
        Move move = moves.get(i);
        long moneyShare = Math.multiplyExact(origin.money, move.population()) / populationBefore;
        long debtShare = Math.multiplyExact(origin.debt, move.population()) / populationBefore;
        if (i == moves.size() - 1 && emptiesOrigin) {
          moneyShare = origin.money - moneyMoved;
          debtShare = origin.debt - debtMoved;
        }
        moves.set(
            i, new Move(move.origin(), move.target(), move.population(), moneyShare, debtShare));
        moneyMoved = Math.addExact(moneyMoved, moneyShare);
        debtMoved = Math.addExact(debtMoved, debtShare);
      }
      for (Move move : moves) {
        move.target().population = Math.addExact(move.target().population, move.population());
        move.target().money = Math.addExact(move.target().money, move.money());
        move.target().debt = Math.addExact(move.target().debt, move.debt());
      }
      origin.population = Math.subtractExact(origin.population, movedPopulation);
      origin.money = Math.subtractExact(origin.money, moneyMoved);
      origin.debt = Math.subtractExact(origin.debt, debtMoved);
      migrations = Math.addExact(migrations, movedPopulation);
    }

    private Map<Mode, Long> cityPopulationByMode() {
      Map<Mode, Long> totals = new LinkedHashMap<>();
      for (Mode mode : Mode.values()) {
        totals.put(mode, 0L);
      }
      for (Household household : households.values()) {
        if (household.hex.city) {
          totals.merge(household.mode, household.population, Math::addExact);
        }
      }
      return totals;
    }

    private Map<Hex, Map<Mode, Long>> profitSnapshot(Map<Mode, Long> cityPopulationByMode) {
      Map<Hex, Map<Mode, Long>> snapshot = new LinkedHashMap<>();
      for (Hex hex : hexes) {
        Map<Mode, Long> row = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
          row.put(mode, profit(mode, hex, cityPopulationByMode));
        }
        snapshot.put(hex, row);
      }
      return snapshot;
    }

    private long profit(Mode mode, Hex hex, Map<Mode, Long> cityPopulationByMode) {
      if (mode == Mode.DISPLACED) {
        return 0L;
      }
      if (mode == Mode.TENANCY_FIXED) {
        return 8L + hex.fertility;
      }
      if (mode == Mode.WAGE_FARM) {
        return 7L + hex.fertility;
      }
      if (!hex.city) {
        return 3L + hex.fertility;
      }
      long cityPopulation = cityPopulationByMode.getOrDefault(mode, 0L);
      if (mode == Mode.HANDICRAFT) {
        return Math.max(0L, 30L - cityPopulation / 20L);
      }
      return Math.max(0L, 26L - cityPopulation / 20L);
    }

    private long profitAt(Mode mode, Hex hex, Map<Hex, Map<Mode, Long>> snapshot) {
      return snapshot.getOrDefault(hex, Map.of()).getOrDefault(mode, 0L);
    }

    private Map<String, Long> capacityLedger() {
      Map<String, Long> ledger = new LinkedHashMap<>();
      for (Household household : households.values()) {
        ledger.merge(household.key(), household.population, Math::addExact);
      }
      return ledger;
    }

    private long availableCapacity(Hex hex, Mode mode, Map<String, Long> capacityLedger) {
      long used = capacityLedger.getOrDefault(hex.id + "|" + mode, 0L);
      return Math.max(0L, mode.maxPerHex() - used);
    }

    private Household firstWithCapacity(Hex hex, Mode mode) {
      for (Household household : households.values()) {
        if (household.hex.equals(hex) && household.mode == mode && household.capacityFree() > 0L) {
          return household;
        }
      }
      return null;
    }

    private void cleanupEmptyHouseholds() {
      List<String> toRemove = new ArrayList<>();
      for (Household household : households.values()) {
        if (household.population <= 0L) {
          if (household.money != 0L || household.debt != 0L) {
            throw new IllegalStateException("人口迁空但仍有货币/债务，迁移事务漏账: " + household);
          }
          toRemove.add(household.id);
        }
      }
      for (String id : toRemove) {
        households.remove(id);
        extinctions++;
      }
    }

    String line(int tick) {
      StringBuilder builder = new StringBuilder();
      builder.append("[MODEMIG] tick=").append(tick);
      for (Mode mode : Mode.values()) {
        long population = totalPopulationOf(mode);
        if (population > 0L) {
          builder
              .append(' ')
              .append(mode)
              .append("(pop=")
              .append(population)
              .append(",debt=")
              .append(totalDebtOf(mode))
              .append(",households=")
              .append(householdsOf(mode))
              .append(')');
        }
      }
      // 城市人口单列，方便看“加权迁移是否真的把人口挪到城市”。
      long cityPopulation = 0L;
      for (Household household : households.values()) {
        if (household.hex.city) {
          cityPopulation += household.population;
        }
      }
      builder
          .append(" cityPop=")
          .append(cityPopulation)
          .append(" totalPop=")
          .append(totalPopulation())
          .append(" totalMoney=")
          .append(totalMoney())
          .append(" totalDebt=")
          .append(totalDebt())
          .append(" migrations=")
          .append(migrations)
          .append(" merges=")
          .append(merges)
          .append(" creations=")
          .append(creations)
          .append(" extinctions=")
          .append(extinctions)
          .append(" displacedDebtGrowth=")
          .append(displacedDebtGrowth);
      return builder.toString();
    }

    String fingerprint() {
      StringBuilder builder = new StringBuilder();
      builder
          .append("pop=")
          .append(totalPopulation())
          .append(" money=")
          .append(totalMoney())
          .append(" debt=")
          .append(totalDebt())
          .append(" migrations=")
          .append(migrations)
          .append(" merges=")
          .append(merges)
          .append(" creations=")
          .append(creations)
          .append(" extinctions=")
          .append(extinctions)
          .append('\n');
      for (Household household : households.values()) {
        builder
            .append(household.id)
            .append('|')
            .append(household.hex)
            .append('|')
            .append(household.mode)
            .append('|')
            .append(household.population)
            .append('|')
            .append(household.money)
            .append('|')
            .append(household.debt)
            .append('\n');
      }
      return builder.toString();
    }
  }

  /** 最大余数法：把 {@code total} 按权重拆成整数份额，Σ = total；确定性（余数同分按下标升序处理）。 */
  static long[] splitByWeights(long total, long[] weights) {
    long sum = 0L;
    for (long weight : weights) {
      sum = Math.addExact(sum, weight);
    }
    long[] result = new long[weights.length];
    if (total <= 0L || sum <= 0L) {
      return result;
    }
    long assigned = 0L;
    long[] remainders = new long[weights.length];
    for (int i = 0; i < weights.length; i++) {
      long numerator = Math.multiplyExact(total, weights[i]);
      result[i] = numerator / sum;
      remainders[i] = numerator % sum;
      assigned = Math.addExact(assigned, result[i]);
    }
    long left = total - assigned;
    Integer[] order = new Integer[weights.length];
    for (int i = 0; i < weights.length; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        Comparator.comparingLong((Integer index) -> remainders[index])
            .reversed()
            .thenComparingInt(index -> index));
    for (int i = 0; i < left; i++) {
      result[order[i]]++;
    }
    return result;
  }
}
