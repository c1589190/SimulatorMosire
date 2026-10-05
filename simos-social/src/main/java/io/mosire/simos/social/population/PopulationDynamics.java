package io.mosire.simos.social.population;

import io.mosire.simos.calendar.CalendarAge;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarSystem;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ **人口再生产**（R4 的 T1；spec §七 的落地）：**年龄推进是派生量、出生与死亡是显式事件**。
 *
 * <pre>
 * 逐日（由 app 的协调器每天调一次）：  {@link #stressAfter}    —— 生理压力的"加一些 / 消退一些"
 * 每 30 天（月度结算）：              {@link #monthly}        —— 出生 + 死亡（改 count，落 {@link LotChange}）
 * </pre>
 *
 * <p>★★ **缺粮不直接对应死亡人数**（spec §七 的原文，本轮最重要的一条）：每天的缺粮**只往上加压力**， 而压力**低于 {@link
 * #STRESS_MORTALITY_THRESHOLD} 时一个人都不多死**。于是：
 *
 * <ul>
 *   <li>**一次五天的供应中断**（满缺 5 天 ⇒ 压力 +50）**永远够不到那个门槛**，恢复供给后按 {@link #STRESS_DECAY_PER_DAY} 逐日消退 ⇒
 *       **不产生任何额外死亡**；
 *   <li>**连续半年严重不足**（满缺 180 天 ⇒ 压力 ≈ 1,800）**远超门槛** ⇒ 月度死亡率被显著抬高 ⇒ **死亡结果完全不同**。
 * </ul>
 *
 * ★ **这就是 spec §九 R4 行那条判据**（"缺粮五天与半年产生不同的死亡结果"）的机制，且它**不是**靠"两个阈值"凑出来的 —— 门槛只有一处（{@link
 * #STRESS_MORTALITY_THRESHOLD}），差别来自**压力随时间的累积**本身。
 *
 * <p>★★ **粮食不足与衣物不足的时间尺度分开表达**（spec §七）：压力有两个**各自独立的系数** （{@link #GRAIN_STRESS_PER_MILLE_PER_DAY} 与
 * {@link #CLOTH_STRESS_PER_MILLE_PER_DAY}），**不许合并成一个数**。 两者的量级刻意相差一个数量级：前现代的人先饿死、再冻死 ——
 * 缺布要慢得多才致命，这个"慢"就落在那个更小的系数上 （有一条逐值用例把"两个系数各自生效"钉住：把布那一项改成 0 ⇒ 布缺不再产生任何压力 ⇒ 红）。
 *
 * <p>★★ **年龄 × 性别 × 基础死亡率 × 生理压力**（spec §七 的死亡公式，四维齐备）：
 *
 * <pre>
 * mortality(批次) = 基础死亡率[年龄档] × 性别系数[sex] ÷ 1000        … 年龄与性别
 *                 + max(0, 压力 − 门槛) × {@link #MORTALITY_PER_STRESS} ÷ 1000   … 生理压力
 * deaths = count × min(1000, mortality) ÷ 1000                      … 封顶 1000‰（人不会死两遍）
 * </pre>
 *
 * <p>★★ **出生按"具体年龄、人数、历史生活资料满足情况"三者**（spec §七：**"至少不要用总人口乘一个增长率"**）：
 *
 * <pre>
 * births = Σ_{育龄女性批次} count × {@link #FERTILITY_PER_MILLE_PER_MONTH} ÷ 1000
 *                            × max(0, 1000 − 压力 × {@link #FERTILITY_SUPPRESSION_PER_STRESS}) ÷ 1000
 * 育龄 = 15 ≤ 该批次在结算日的**整历法年** &lt; 45（{@link CalendarAge}，与年龄档同一口径；2/29 出生平年按 3/1 长岁）
 * </pre>
 *
 * 三者各自在式子里：**具体年龄**（逐日精度，不是五岁桶）、**人数**（那批育龄女性有几个人）、**历史生活资料满足情况**（压力： 长期吃不饱 ⇒ 生得少甚至不生）。★
 * 它是**逐批次**算的，故总人口乘一个增长率的写法在这里根本表达不出来。
 *
 * <p>★★ **出生落成新的批次**（不是往现有批次里加人）：新生的孩子**年龄 0 天**，而创世批次的年龄是档中点的 7/37/75 岁 —— 把婴儿并进"7
 * 岁那批"是错的。故每月一层"当月出生"的批次（性别各半、年龄 0、锚点 = 结算日）， 由 {@link PopulationLots#born} 命名。⇒
 * **年龄结构会随推进真的演化**（0-14 档里出现"当月生"的批次，而旧批次逐日变老）。
 *
 * <p>★ **本类不含任何存储**：纯函数进、纯函数出（拿 {@link SocialData} 交一批 {@link LotChange} 与新批次）。**落盘与否由调用方定** —— 本轮由
 * {@code simos-app} 的协调器驱动（它同时看得见 social 与 economy 两个切片）。
 *
 * <p>★★ **参数默认值的来源**（全部是**判断结果**，spec §十一：出生率/死亡率/衰减率的默认值"按真档观察后再定"）：
 * 它们在此以**具名常量**出现，每个都注明量级依据。**V7 参数目录（spec §四）落地后迁入且成为 GM 可调** —— 本轮**不造半套目录**。
 */
public final class PopulationDynamics {

  /** 出生结算的周期（天）：**月度**（spec §七 原文："出生：月度结算"，每 30 天一次）。 */
  public static final long SETTLEMENT_DAYS = 30L;

  /**
   * ★★ **满缺粮一天往压力里加多少**（压力点/天）：**10**。
   *
   * <p>★ 依据：让"满缺 5 天"（+50）**明确够不到** {@link
   * #STRESS_MORTALITY_THRESHOLD}(300)，而"满缺半年"（≈1,800）**远超**它。
   * 这两个数是本轮判据的两端，本常量与那两个值是一起定的（判据驱动，不是拍脑袋）。
   */
  public static final long GRAIN_STRESS_PER_MILLE_PER_DAY = 10L;

  /**
   * ★★ **满缺布一天往压力里加多少**（压力点/天）：**1**。
   *
   * <p>★★ **它必须与粮那一项分开**（spec §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）：粮满缺 5 天就到 50，布满缺 50 天才到 50 ——
   * **相差一个数量级**。★ 判别力：把本常量改成 0 ⇒ 缺布不再产生任何压力 ⇒ "布缺单独一条"的用例当场红； **不许**把两个系数合并成一个（那样上面那句话就再也表达不出来了）。
   */
  public static final long CLOTH_STRESS_PER_MILLE_PER_DAY = 1L;

  /**
   * **供给满足时每天消退多少压力**（压力点/天）：**6**。
   *
   * <p>★ 依据：消退按**满足率**打折（{@code × min(粮满足, 布满足) ÷ 1000}），故"一次五天中断"（+50）在供给恢复后**约 9 天**内消净 ——
   * 赶得上在下一个结算月之前回到 0，这正是"短期中断与长期不足结果不同"的另一半。
   */
  public static final long STRESS_DECAY_PER_DAY = 6L;

  /**
   * ★★ **压力开始抬高死亡率的门槛**（压力点）：**300**。
   *
   * <p>★ 它换算成人话：满缺粮 **30 天**、或半缺粮 **60 天**、或满缺布 300 天。⇒ **短于它的中断不产生任何额外死亡** （门槛是本类**唯一**的一处阈值，"五天 ≠
   * 半年"不靠第二处阈值）。
   */
  public static final long STRESS_MORTALITY_THRESHOLD = 300L;

  /** **超过门槛的每 1 点压力抬高多少月度死亡率**（‰/压力点）：**20** ⇒ 满缺半年（≈1,800）的超额 ≈ 30‰/月。 */
  public static final long MORTALITY_PER_STRESS = 20L;

  /**
   * **各年龄档的月度基础死亡率**（‰/月；序与 {@link AgeBracket} 词表同：0-14 / 15-59 / 60+）：{@code 2 / 1 / 20}。
   *
   * <p>★ 依据：前现代量级（未成年与老年显著高于青壮年）。折年：0-14 ≈ 24‰/年、15-59 ≈ 12‰/年、60+ ≈ 240‰/年。★ 它同时是 {@code 年龄}
   * 那一维的落点（"年龄 × 性别 × 基础死亡率 × 生理压力"四维里的第一维）。
   */
  static final int[] BASE_MORTALITY_PER_MILLE_PER_MONTH = {2, 1, 20};

  /**
   * ★★ **基础死亡率的性别系数**（‰；键 = 性别）：默认**两性同值 1000**。
   *
   * <p>★ 与 R1 的劳动系数表同款：**表可按性别覆盖**是那条判据的载体（"性别**进入了**折算"），而**具体的差值没有任何文档依据** ⇒ 不臆造偏向。★
   * 可注入的重载（包内可见）让用例证明"性别真的参与了折算"（把女性系数改大 ⇒ 死亡数逐值变大）。
   */
  static final Map<Sex, Integer> MORTALITY_SEX_FACTOR_PER_MILLE =
      Map.of(Sex.MALE, 1000, Sex.FEMALE, 1000);

  /**
   * **育龄下界**（整历法年，含）：**15 岁**。
   *
   * <p>★ **已不是固定 365 天的边界**：本常量是**整历法年**，逐日年龄到整岁的换算由 {@link CalendarAge#ageInYears(CalendarSystem,
   * long, long)} 按历法年现算；2/29 出生平年按 3/1 长岁（与 {@link AgeBracket} 的 15/60 同一口径）。
   */
  public static final long FERTILE_MIN_YEARS = 15L;

  /**
   * **育龄上界**（整历法年，不含）：**45 岁**。
   *
   * <p>★ **同样已不是固定 365 天的边界**：由 {@link CalendarAge} 按历法年现算；见 {@link #FERTILE_MIN_YEARS} 的说明。
   */
  public static final long FERTILE_MAX_YEARS = 45L;

  /**
   * **每个育龄女性每月的生育率**（‰）：**20**（≈ 每人每年 0.24 胎）。
   *
   * <p>★ 依据：前现代一户人家一生五六个孩子、育龄期约 30 年 ⇒ 年化 0.18~0.2 的量级，取 0.24（比它略高一点，让"人口第一次动起来" 在**一年**的窗口里读得出来）。★
   * 改它 = 改规则口径 ⇒ 走 {@code rulesVersion}。
   */
  public static final long FERTILITY_PER_MILLE_PER_MONTH = 20L;

  /**
   * ★★ **压力对生育的抑制系数**（**每 1 点压力扣 2‰ 的生育率**；与压力同量纲，不再除 1000）：**2** ⇒ 压力 500 时生育率归零。
   *
   * <p>★ 这正是 spec §七 "按**历史生活资料满足情况**产生出生"的落点：长期吃不饱的人**生得少甚至不生** —— 而"历史"就是压力本身 （它是逐日累积出来的，不是当月快照）。
   */
  public static final long FERTILITY_SUPPRESSION_PER_STRESS = 2L;

  /** **满额**（‰）：满足率与系数的公共分母。 */
  private static final long FULL_PER_MILLE = 1000L;

  private PopulationDynamics() {}

  // ── 逐日：生理压力 ────────────────────────────────────────────────────────────────────

  /**
   * ★★ **今天的压力**（逐日一次，由协调器在每天的经济结算**之后**调用）：**缺什么加什么、满足则消退**。
   *
   * <pre>
   * 粮缺 = max(0, 1000 − 粮满足率‰)；布缺 = max(0, 1000 − 布满足率‰)
   * 加   = 粮缺 × {@link #GRAIN_STRESS_PER_MILLE_PER_DAY} ÷ 1000        ← 各自系数，**不合并**
   * 加  += 布缺 × {@link #CLOTH_STRESS_PER_MILLE_PER_DAY} ÷ 1000        ← 各自系数，**不合并**
   * 退   = {@link #STRESS_DECAY_PER_DAY} × min(粮满足率, 布满足率) ÷ 1000  ← 按满足率打折（两边都好才退得快）
   * 新压力 = max(0, 旧压力 + 加 − 退)                                    ← 不夹上界：长期不足要能累积到门槛之上
   * </pre>
   *
   * <p>★★ **它是"累积"这件事本身**：{@code stress} 是状态（住在 {@link PopulationGroup#physiologicalStress}），
   * 本方法只给出"明天比今天多多少"。于是"五天中断"与"半年不足"的差别**不是**两个分支，而是同一个式子在时间上积出来的两个值。
   *
   * <p>★ **满足率的口径**：1000‰ = 完全满足（当期的需求全部被满足），0 = 一点都没满足。它由调用方从**经济侧的当日发生额**折出 （需求与实得都在那里，见 {@code
   * 旧结算引擎（R3a 已删除）} 的逐日流水）—— 本类不猜、不另算一遍。
   *
   * @param stress 今天的压力（压力点）；不得为负
   * @param grainSatisfactionPerMille 当日**粮**的满足率（‰；∈ [0, 1000]）
   * @param clothSatisfactionPerMille 当日**布**的满足率（‰；∈ [0, 1000]）
   * @return 明天的压力（≥ 0）
   */
  public static long stressAfter(
      long stress, long grainSatisfactionPerMille, long clothSatisfactionPerMille) {
    if (stress < 0L) {
      throw new IllegalArgumentException("生理压力不得为负: " + stress);
    }
    long grainShortfall =
        Math.max(0L, FULL_PER_MILLE - requirePerMille(grainSatisfactionPerMille, "粮满足率"));
    long clothShortfall =
        Math.max(0L, FULL_PER_MILLE - requirePerMille(clothSatisfactionPerMille, "布满足率"));
    long add =
        grainShortfall * GRAIN_STRESS_PER_MILLE_PER_DAY / FULL_PER_MILLE
            + clothShortfall * CLOTH_STRESS_PER_MILLE_PER_DAY / FULL_PER_MILLE;
    long satisfied = Math.min(grainSatisfactionPerMille, clothSatisfactionPerMille);
    long decay =
        STRESS_DECAY_PER_DAY * Math.max(0L, Math.min(FULL_PER_MILLE, satisfied)) / FULL_PER_MILLE;
    return Math.max(0L, stress + add - decay);
  }

  // ── 月度：出生与死亡 ───────────────────────────────────────────────────────────────────

  /**
   * ★★ **一次月度结算**：算出每批次的出生与死亡，并给出**新的批次表**（人数已改、含当月新生批次）。
   *
   * <p>★ **纯函数**：{@code data} 一字不改，返回 {@link Outcome}（新表 + 逐批次变动）。**幂等性不在本方法**（同一天跑两次会生两次）——
   * "每个结算日只跑一次"由调用方（协调器：{@code day % 30 == 0}）保证，与"周期末收获"同款。
   *
   * <p>★★ **它同时是"人口守恒"的落点**：{@code Σ新人数 == Σ旧人数 + Σ出生 − Σ死亡}（逐值可核：两条账都显式给出来了）。
   *
   * @param data 当前批次表（人口的真值源）
   * @param nowTick 结算日（世界日）：新生批次的年龄锚点就是它；育龄按{@code ageDaysAt(nowTick)} 经 {@link CalendarAge} 现算整历法年
   * @param clock 历法时钟：tick→JDN 的唯一换算点（死亡档位按整历法年现算；C4b 起必传，social 内部不造默认时钟）
   */
  public static Outcome monthly(SocialData data, long nowTick, CalendarClock clock) {
    return monthly(
        data, nowTick, clock, BASE_MORTALITY_PER_MILLE_PER_MONTH, MORTALITY_SEX_FACTOR_PER_MILLE);
  }

  /**
   * 同 {@link #monthly(SocialData, long, CalendarClock)}，但**两张参数表可注入**（**包内可见**的旋钮）：一条用例据此证明
   * "年龄档与性别**真的参与了**死亡折算"（改任一张表 ⇒ 死亡数逐值变化）。公开入口恒喂默认值。
   */
  static Outcome monthly(
      SocialData data,
      long nowTick,
      CalendarClock clock,
      int[] baseMortalityPerBracket,
      Map<Sex, Integer> sexFactorPerMille) {
    Objects.requireNonNull(data, "data");
    if (nowTick < 0L) {
      throw new IllegalArgumentException("结算日不得为负: " + nowTick);
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock 不得为 null");
    }
    long currentDayNumber = clock.dayNumberOfTick(nowTick);
    Map<PeopleLotId, PopulationGroup> next = new LinkedHashMap<>(data.groups());
    Map<PeopleLotId, LotChange> changes = new LinkedHashMap<>();
    // ★ 新生批次按 (居所, 性别) 聚合：同一个月的婴儿在会计上是**一批**（同性别、同年龄、同锚点 ⇒ 属性确实完全相同）。
    //   键用批次 id 的"前缀"（mother 的 id 去掉最后一段）—— 它天然保住了 rural/urban 的命名约定（见 PopulationLots）。
    Map<PeopleLotId, PopulationGroup> born = new LinkedHashMap<>();
    Map<PeopleLotId, HouseholdId> bornHousehold = new LinkedHashMap<>();
    // ★★ P2-A：家户份额是成员关系的唯一权威 ⇒ 死亡按各户份额比例从本批次削、出生挂进（份额最大的）母亲家户。
    Map<HouseholdId, Household> households = new LinkedHashMap<>(data.households());
    long totalBirths = 0L;
    long totalDeaths = 0L;
    for (PopulationGroup group : data.groups().values()) {
      long ageDays = group.ageDaysAt(nowTick);
      long population = group.count();
      long deaths =
          population == 0L
              ? 0L
              : population
                  * mortalityPerMille(
                      group, ageDays, clock.system(), currentDayNumber, sexFactorPerMille)
                  / FULL_PER_MILLE;
      long births = birthsOf(group, ageDays, clock.system(), currentDayNumber);
      if (deaths == 0L && births == 0L) {
        continue;
      }
      next.put(
          group.id(), group.withCountAndStress(population - deaths, group.physiologicalStress()));
      if (deaths > 0L) {
        reduceShares(households, group.id(), deaths);
      }
      // ★ 家户架构 §4.2：{@code LotChange.at} 只能从所属家户的位置取（批次身上没有位置）。
      //   ★ 家户在 UNIT 上时没有格 ⇒ 本经济回写口径（S2 范围外）fail-closed 指名 S3。
      HexCoord at =
          data.hexOfLot(group.id())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "批次 "
                              + group.id()
                              + " 的家户不在 HEX 上（UNIT 家户的 LotChange.at 口径属 S3 消费方集成；S2 不接）"));
      changes.put(group.id(), new LotChange(group.id(), at, births, deaths));
      totalBirths += births;
      totalDeaths += deaths;
      if (births > 0L) {
        HouseholdId household = largestShareHolder(households, group.id());
        appendBirths(born, bornHousehold, household, group, births, nowTick);
      }
    }
    // ★ 新生批次必须挂进母亲的家户（members 份额制：新批次挂进该户，份额 = 新生儿数）。
    for (PopulationGroup newborn : born.values()) {
      HouseholdId owner = bornHousehold.get(newborn.id());
      Household household = households.get(owner);
      if (household == null) {
        throw new IllegalStateException("新生批次 " + newborn.id() + " 的目标家户不存在: " + owner);
      }
      PopulationGroup existing = data.groups().get(newborn.id());
      if (existing != null) {
        List<Household> existingOwners = data.householdsOfLot(newborn.id());
        if (existingOwners.size() > 1) {
          throw new IllegalStateException(
              "新生批次 " + newborn.id() + " 的 id 已被多个家户按份额持有: " + existingOwners.size());
        }
        HouseholdId existingOwner = existingOwners.isEmpty() ? null : existingOwners.get(0).id();
        if (existingOwner != null && !owner.equals(existingOwner)) {
          throw new IllegalStateException(
              "新生批次 " + newborn.id() + " 的 id 已被家户 " + existingOwner + " 占用，不能并入 " + owner);
        }
      }
      households.put(owner, household.withMember(newborn.id(), newborn.count()));
      next.put(newborn.id(), newborn);
    }
    return new Outcome(
        data.withGroupsAndHouseholds(next, households),
        Collections.unmodifiableMap(changes),
        totalBirths,
        totalDeaths);
  }

  /** 把某个批次上的一笔死亡按各持户**份额比例**（最大余数法、HouseholdId 升序）从份额表里削掉；削到 0 ⇒ 删条目。 */
  private static void reduceShares(
      Map<HouseholdId, Household> households, PeopleLotId lot, long deaths) {
    List<Map.Entry<HouseholdId, Household>> holders = new ArrayList<>();
    for (Map.Entry<HouseholdId, Household> entry : households.entrySet()) {
      if (entry.getValue().hasMember(lot)) {
        holders.add(entry);
      }
    }
    holders.sort(Comparator.comparing(entry -> entry.getKey().value()));
    if (holders.isEmpty()) {
      throw new IllegalStateException("批次 " + lot + " 没有任何家户持有却要削掉 " + deaths + " 人");
    }
    long[] weights = new long[holders.size()];
    long total = 0L;
    for (int i = 0; i < holders.size(); i++) {
      weights[i] = holders.get(i).getValue().memberCount(lot);
      total = Math.addExact(total, weights[i]);
    }
    if (total < deaths) {
      throw new IllegalStateException(
          "批次 " + lot + " 的份额合计 " + total + " 小于死亡数 " + deaths + "（状态不一致）");
    }
    long[] parts = ProportionalSplit.byDenominator(deaths, weights, total);
    for (int i = 0; i < holders.size(); i++) {
      if (parts[i] <= 0L) {
        continue;
      }
      HouseholdId id = holders.get(i).getKey();
      Household household = holders.get(i).getValue();
      long share = household.memberCount(lot) - parts[i];
      households.put(id, share == 0L ? household.withoutMember(lot) : household.withMember(lot, share));
    }
  }

  /** 某批次份额最大的持户（份额相同 ⇒ HouseholdId 升序）—— 新生儿落点的确定序。 */
  private static HouseholdId largestShareHolder(
      Map<HouseholdId, Household> households, PeopleLotId lot) {
    HouseholdId best = null;
    long bestShare = -1L;
    for (Map.Entry<HouseholdId, Household> entry : households.entrySet()) {
      if (!entry.getValue().hasMember(lot)) {
        continue;
      }
      long share = entry.getValue().memberCount(lot);
      if (best == null
          || share > bestShare
          || (share == bestShare && entry.getKey().value().compareTo(best.value()) < 0)) {
        best = entry.getKey();
        bestShare = share;
      }
    }
    if (best == null) {
      throw new IllegalStateException("批次 " + lot + " 没有任何家户持有（构造期不变式被破坏）");
    }
    return best;
  }

  /**
   * ★★ **一个批次的月度死亡率**（‰，封顶 1000）：{@code 年龄 × 性别 × 基础死亡率 × 生理压力} 四维齐备。
   *
   * <pre>
   * base = 基础死亡率[{@code AgeBracket.of(system, currentDayNumber, 该批次在结算日的年龄)}] × 性别系数[sex] ÷ 1000
   * over = max(0, 压力 − {@link #STRESS_MORTALITY_THRESHOLD}) × {@link #MORTALITY_PER_STRESS} ÷ 1000
   * 死亡率 = min(1000, base + over)
   * </pre>
   *
   * <p>★ **年龄档按整历法年现算**：{@code system}/{@code currentDayNumber} 由 {@link #monthly} 从 {@link
   * CalendarClock#dayNumberOfTick(long)} 算出，本方法不手算 365、不另造时钟。
   *
   * <p>★ **封顶 1000‰**：死亡率超过 1000‰ 意味着"死的人比人还多"，那不是状态而是坏数据 ⇒ 在构造下一个人数之前就夹住。
   */
  static long mortalityPerMille(
      PopulationGroup group,
      long ageDays,
      CalendarSystem system,
      long currentDayNumber,
      Map<Sex, Integer> sexFactorPerMille) {
    return mortalityPerMille(
        group,
        ageDays,
        system,
        currentDayNumber,
        BASE_MORTALITY_PER_MILLE_PER_MONTH,
        sexFactorPerMille);
  }

  /** 同上的**两张表都可注入**形态（包内可见的旋钮，见 {@link #monthly(SocialData, long, CalendarClock, int[], Map)}）。 */
  static long mortalityPerMille(
      PopulationGroup group,
      long ageDays,
      CalendarSystem system,
      long currentDayNumber,
      int[] baseMortalityPerBracket,
      Map<Sex, Integer> sexFactorPerMille) {
    int bracket = AgeBracket.of(system, currentDayNumber, ageDays).ordinal();
    Integer sexFactor = sexFactorPerMille.get(group.sex());
    if (sexFactor == null) {
      throw new IllegalStateException("性别 " + group.sex() + " 不在死亡系数表里（拒绝臆造）");
    }
    long base = baseMortalityPerBracket[bracket] * sexFactor / FULL_PER_MILLE;
    long over =
        Math.max(0L, group.physiologicalStress() - STRESS_MORTALITY_THRESHOLD)
            * MORTALITY_PER_STRESS
            / FULL_PER_MILLE;
    return Math.min(FULL_PER_MILLE, base + over);
  }

  /**
   * ★★ **一个批次的月度出生数**（人）：只有**育龄女性**批次能生，且受**历史生活资料满足情况**（= 生理压力）抑制。
   *
   * <pre>
   * 育龄：sex == FEMALE 且 {@link #FERTILE_MIN_YEARS} ≤ 该批次的**整历法年** &lt; {@link #FERTILE_MAX_YEARS}
   * 抑制：max(0, 1000 − 压力 × {@link #FERTILITY_SUPPRESSION_PER_STRESS} ÷ 1000) ÷ 1000
   * 出生：人数 × {@link #FERTILITY_PER_MILLE_PER_MONTH} ÷ 1000 × 抑制
   * </pre>
   *
   * <p>★ **年龄按整历法年现算**：{@code ageYears = CalendarAge.ageInYears(system, currentDayNumber - ageDays,
   * currentDayNumber)}；与年龄档（{@link AgeBracket} 的 15/60）同一口径，2/29 出生平年按 3/1 长岁。
   *
   * <p>★ **"具体年龄"是逐日精度的现算值**（{@code ageDaysAt} 交给 {@link CalendarAge} 换算整岁），不是年龄档 ——
   * 档只用于死亡与查询聚合，生育看上的是真实年龄的整历法年。
   */
  static long birthsOf(
      PopulationGroup group, long ageDays, CalendarSystem system, long currentDayNumber) {
    if (group.sex() != Sex.FEMALE) {
      return 0L;
    }
    long ageYears = CalendarAge.ageInYears(system, currentDayNumber - ageDays, currentDayNumber);
    if (ageYears < FERTILE_MIN_YEARS || ageYears >= FERTILE_MAX_YEARS) {
      return 0L;
    }
    long stressPerMille =
        Math.min(FULL_PER_MILLE, group.physiologicalStress() * FERTILITY_SUPPRESSION_PER_STRESS);
    long suppression = FULL_PER_MILLE - stressPerMille; // 压力 ≥ 500 ⇒ 抑制为 0（生不出来）
    return group.count()
        * FERTILITY_PER_MILLE_PER_MONTH
        / FULL_PER_MILLE
        * suppression
        / FULL_PER_MILLE;
  }

  /**
   * 把 {@code births} 个新生儿按**性别各半**放进"当月出生"批次（残差按最大余数法归男性 —— 与创世的性别切分同一口径）。
   *
   * <p>★ 新批次的 id 由 {@link PopulationLots#born} 给出（保住 rural/urban 前缀约定）；同一 (居所, 性别) 的多位母亲
   * 汇进**同一批**（属性确实相同：同性别、同年龄 0、同锚点）。
   *
   * <p>★ S2：新生批次同时登记"归属家户"（母亲的家户）⇒ 调用方把它挂进 memberLots（家户架构 §4.2）。
   */
  private static void appendBirths(
      Map<PeopleLotId, PopulationGroup> born,
      Map<PeopleLotId, HouseholdId> bornHousehold,
      HouseholdId household,
      PopulationGroup mother,
      long births,
      long nowTick) {
    long male = (births + 1L) / 2L; // 残差归男性：与 PopulationSeeder.SEX_SHARE_PER_MILLE 的最大余数法同口径
    long female = births - male;
    for (int i = 0; i < 2; i++) {
      Sex sex = Sex.values()[i];
      long count = i == 0 ? male : female;
      if (count <= 0L) {
        continue;
      }
      PeopleLotId id =
          PopulationLots.born(mother, sex, PopulationLots.bornCohort(nowTick, SETTLEMENT_DAYS));
      HouseholdId previous = bornHousehold.putIfAbsent(id, household);
      if (previous != null && !previous.equals(household)) {
        throw new IllegalStateException(
            "新生批次 " + id + " 同时落在两个家户（" + previous + " / " + household + "）—— 拒绝静默合批");
      }
      PopulationGroup existing = born.get(id);
      long merged = (existing == null ? 0L : existing.count()) + count;
      born.put(id, new PopulationGroup(id, sex, merged, 0L, nowTick));
    }
  }

  /** 满足率的口径守卫（‰；越界是坏数据，不静默夹取 —— 夹取会把"算错了"伪装成"满额满足"）。 */
  private static long requirePerMille(long perMille, String what) {
    if (perMille < 0L || perMille > FULL_PER_MILLE) {
      throw new IllegalArgumentException(what + " 必须 ∈ [0, 1000]: " + perMille);
    }
    return perMille;
  }

  /**
   * 一次月度结算的结果：**新批次表**（人数已改、含新生批次）+ **逐批次的两条账**（出生/死亡）+ 合计。
   *
   * <p>★ {@code changes} 的键集 = 本月**真的动过**的批次（两侧都为 0 的不落键：保住"空表"的纯形态）。
   *
   * @param data 新的批次表（{@link SocialData#withGroups} 的产物）
   * @param changes 逐批次的出生/死亡（键序 = 批次表序）
   * @param births 本月出生合计（人）
   * @param deaths 本月死亡合计（人）
   */
  public record Outcome(
      SocialData data, Map<PeopleLotId, LotChange> changes, long births, long deaths) {

    public Outcome {
      Objects.requireNonNull(data, "data");
      if (changes == null) {
        throw new IllegalArgumentException("Outcome.changes 不得为 null（无变动用空 map）");
      }
      if (births < 0L || deaths < 0L) {
        throw new IllegalArgumentException("Outcome 的出生/死亡不得为负: " + births + " / " + deaths);
      }
      Map<PeopleLotId, LotChange> copy = new LinkedHashMap<>();
      for (Map.Entry<PeopleLotId, LotChange> entry : changes.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("Outcome.changes 的键与值都不得为 null");
        }
        if (!entry.getKey().equals(entry.getValue().group())) {
          throw new IllegalArgumentException(
              "Outcome.changes 的键必须与 LotChange.group 一致：" + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      changes = Collections.unmodifiableMap(copy);
    }

    /** 本月有没有任何生死（调用方据此跳过整条回写路径）。 */
    public boolean isEmpty() {
      return births == 0L && deaths == 0L;
    }

    /** 逐批次的变动清单（保序）—— 供经济侧把同一份账摊回阶层行与劳动配额。 */
    public List<LotChange> changeList() {
      return List.copyOf(new ArrayList<>(changes.values()));
    }
  }
}
