package io.mosire.simos.app.world;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 创世农村/城镇人口的劳动折算、配额发放与口粮换算。 */
class RuralLaborSeeder extends WorldBalanceSeeder {

  /** 一群批次的人数：{@code Σ count}（"每格人数"的唯一算法）。 */
  static long populationOf(List<PopulationGroup> pool) {
    long total = 0L;
    for (PopulationGroup group : pool) {
      total += group.count();
    }
    return total;
  }

  /**
   * ★★ **该池的"产业日劳动"**：{@code Σ_i (行劳动_i × 槽位投入率_i ÷ 1000)}，其中 {@code 行劳动_i = 该槽位人数_i × 池毛劳动 ÷
   * 池人数}（与 {@link #householdEconomy} 写进载荷的那两个数**同一个算法**）。
   *
   * <pre>
   * 人数     people    = splitByShares(Σcount, CLASS_SHARE_PER_MILLE)      // 450/350/150/50
   * 行劳动   rowLabor_i = people[i] × poolLabor ÷ poolCount                 // 阶层间"年龄性别同分布"
   * 日劳动   Σ_i rowLabor_i × CLASS_LABOR_PER_MILLE[i] ÷ 1000              // 贫农 950‰ … 地主 100‰
   * </pre>
   *
   * ★★ **这条算式不是新口径，是"改口径前的结算**逐字**复刻**：R2 之前 {@code 旧结算引擎（R3a 已删除）} 每天的 {@code laborToday} 就是
   * {@code Σ(行 laborMilli × participationPerMille ÷ 1000)}，而两个乘数都由本类写进载荷 ⇒
   * 本方法算出的数**恰好**等于改口径前每一天累加进 {@code IndustryDefinitionView.cycleLaborMilli()} 的那个数。R2 把它改从"劳动配额表"取
   * （见 {@code 旧结算引擎（R3a 已删除）.laborByActor}），故**配额之和必须逐值等于这个数** —— 这就是真档数字一个都不变的原因。
   *
   * <p>★ 为什么**在这里**（生成器）算而不是在结算里算：结算看不见人口（economy 不认识 social 的 {@code PopulationGroup}），
   * 而"人有多少劳动"这件事只能从人口推；创世一次算好、落成配额，正是 R1 那条"app 一次算出、同一份喂两条命令"的接缝的延续。
   */
  static long industryDailyLabor(List<PopulationGroup> pool) {
    return industryDailyLabor(
        splitByShares(populationOf(pool), CLASS_SHARE_PER_MILLE),
        laborMilli(pool),
        populationOf(pool));
  }

  /**
   * 同上，但**按"已切好的阶层人数 + 池毛劳动 + 池人数"**给（不读批次）—— 服务**手搭的夹具** （{@code EconomyTestWorld} 那类没有 {@link
   * PopulationGroup} 的世界：它用 {@link #laborMilli(long)} 的窄口径造行）。
   *
   * <p>★ 与 {@link #industryDailyLabor(List)} **是同一个算式**（后者只是先切人、再转调本方法）⇒
   * "真档的配额之和"与"手搭世界的配额之和"不可能漂开。
   */
  static long industryDailyLabor(long[] people, long poolLabor, long poolCount) {
    long total = 0L;
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★ M1.8：按参与率折算的唯一算法在 HouseholdEconomy（本处与结算/读口调的是同一个函数）—— 这里不再自己写
      //   `rowLabor * CLASS_LABOR_PER_MILLE / 1000`（公式同值，但两处写法就是两份会漂开的真相）。
      total +=
          HouseholdEconomy.participationAdjustedLaborMilli(
              rowLaborMilli(people[i], poolLabor, poolCount), CLASS_LABOR_PER_MILLE[i]);
    }
    return total;
  }

  /** 一个批次的**毛劳动**（千分劳动）：{@code 人数 × 年龄×性别系数} —— {@link #laborMilli(List)} 的单批次形态。 */
  static long grossLaborMilli(PopulationGroup group) {
    return group.count() * perCapitaLaborPerMille(group);
  }

  /**
   * ★★ <b>M1.8：一个批次按阶层参与率折扣后的每日可用劳动</b>（千分劳动/日）= 毛额 × {@link #CLASS_WEIGHTED_LABOR_PER_MILLE} ÷
   * 1000。
   *
   * <p>★★ <b>它是批次侧的唯一"可用"口径</b>：{@link #laborBudget}（配额上限）与 {@link #appendAllocation}（权重）都读它 ——
   * 两处不许各乘一次（同一份劳动最多折算一次）。逐行的折算见 {@link HouseholdEconomy#participationAdjustedLaborMilli()}：
   * 两条投影在池上的总量相等（同一批人、同一套阶层份额与参与率）。
   *
   * <p>★ <b>为什么不是把折算写进 {@code LaborSupply.grossLaborMilli}</b>：那个字段的契约口径是"毛额 = Σ(人数 × 年龄×性别系数)"
   * （{@code LaborSupply} 的类注），改它的语义会牵动 codec/往返与"已服役 + 已承诺 ≤ 毛额"那条不变量 ⇒ 本批<b>零契约改动</b>：
   * 状态里仍是毛额，折扣发生在"预算/权重"这一使用点上。{@code EconomyData} 的构造期守卫因此仍以毛额为更宽的一道网（见 {@link #laborBudget}）。
   */
  static long participationAdjustedLaborMilli(PopulationGroup group) {
    return HouseholdEconomy.participationAdjustedLaborMilli(
        grossLaborMilli(group), CLASS_WEIGHTED_LABOR_PER_MILLE);
  }

  /**
   * 配额 id 的**唯一拼写点**：{@code alloc-<产业 id>-<批次 id>}。
   *
   * <p>★ **确定性**：{@code (产业, 批次)} 的纯函数 ⇒ 同一对必然给出同一个 id（重放/分支可比），且同一对不会重复。 ★ **不含 {@code "."}**：产业
   * id 形如 {@code farm@0_0}、批次 id 形如 {@code rural:0_0:MALE:s0-1}，两者都不含点 ⇒ 地址 {@code
   * economy:<mapId>:allocation.<id>} 不会被 {@code AddressParser} 在第一个点处截断。
   */

  // ── 三个产业（**只留制度 + 配方 + 产能**；行已搬到 {@link #ruralCohort} / {@link #urbanCohort}）──────

  /** 本格**可耕地**（千分亩）= {@code 每格基准亩 × 地形系数} —— ★ **与人口无关**（没人种的格，地还在）。 */
  static long landMilliMuOf(String terrain) {
    return MU_PER_HEX * MILLI_MU_PER_MU * arablePerMilleOf(foodOf(terrain)) / 1000L;
  }

  /**
   * 本格农业**一个周期**的纤维副产（毫纤维）= 本格可耕地**亩数** × {@link #FIBER_OUTPUT_PER_MU} × 1000 毫/单位。
   *
   * <p>★ **与产出那一路同源**（{@code outputPerUnit[fiber]} 就是每亩 6 单位）⇒ 这份"初始库存"是从配方推出来的，不是第二个拍出来的数。
   * （旧版从农业四行的 {@code LAND} **逐行**换算成亩再向下取整后求和；现在直接由**产业产能**那一处算， 取整因此**逐格只发生一次** ——
   * 两者在"格土地不是千分亩整数倍"时可能差不到一亩的量级，见 {@code EconomyRealScaleClothTest} 的实测。）
   */
  static long fiberStockMilli(long landMilliMu) {
    return landMilliMu
        / MILLI_MU_PER_MU
        * FIBER_OUTPUT_PER_MU
        * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
  }

  /** 一座作坊**一个周期**的纤维用量（毫纤维）= 产布 × 每匹耗纤维。★ 与 {@code cycleInputPerUnit} **同一条口径**（唯一拼写点）。 */
  static long fiberPerWorkshopMilli() {
    return CLOTH_PER_WORKSHOP_PER_CYCLE * FIBER_MILLI_PER_CLOTH;
  }

  /**
   * 一座作坊**一个周期**的铁用量（毫铁）= 产工具 × 每件耗铁。★ 同上（唯一拼写点）。
   *
   * <p>★ <b>H5 起它只喂创世库存</b>（{@link #urbanCohort} 给城镇家户那一箱铁的用度估计），**没有配方读它** —— 铁按用户裁定留位（见 {@link
   * #TOOL_MILLI_PER_WORKSHOP_CYCLE} 的注释）。
   */
  static long ironPerWorkshopMilli() {
    return TOOL_PER_WORKSHOP_PER_CYCLE * IRON_MILLI_PER_TOOL;
  }

  /**
   * 一座作坊**一个周期**的工具用量（毫工具）= {@link #TOOL_MILLI_PER_WORKSHOP_CYCLE} —— ★ 与 {@code
   * handicraft().cycleInputPerUnit} **同一条口径**（唯一拼写点）。
   */
  static long toolPerWorkshopMilli() {
    return TOOL_MILLI_PER_WORKSHOP_CYCLE;
  }

  /**
   * 每格可耕地系数（千分）：由 **map 的** {@link TerrainType#food()} 折算，**不自建地形表**。
   *
   * <p>★ 为什么不自建：v1 在 economy 侧另写了一份**两档**表（平原 1.0 / 低丘 0.6），而 map 的 {@code TerrainType} 早就有**六档**
   * {@code food}（平原 3 / 低丘 2 / 平缓高原 1 / 沙漠 0 / 山地 0 / 海洋 0）—— 同一个事实两处， 且 map 那份更细。真相只能有一个拼写点。
   *
   * <p>★ **为什么不抛**：{@code food == 0} 是**合法产能**（沙漠/山地/海洋），得 0 亩即可；抛会把"这格不产粮" 误报成"数据坏了"。（v1
   * 对非平原/低丘一律抛 ⇒ 地形直方图一变就整批 worldgen 回滚。）
   */
  public static int arablePerMilleOf(int food) {
    return food * 1000 / FOOD_AT_FULL_ARABLE;
  }

  /** 地形 key → map 的产能档；未知地形由 {@link TerrainCatalog#of} fail-closed 抛（不许当 0）。 */
  public static int foodOf(String terrainKey) {
    return TerrainCatalog.of(terrainKey).food();
  }

  /**
   * 年龄（天，**创世 tick 0 时的年龄**）落在哪一档（0-14 / 15-59 / 60+，与 {@link #AGE_SHARE_PER_MILLE} 同序）。
   *
   * <p>★ **档是现算的，不存档位**（设计稿 §三：年龄运行时只有逐日精度，"档间转移"因此不存在）。 ★ 超出末档上界一律归末档（年龄没有上界）。
   *
   * <p>★★ **边界与换算的唯一定义处都在 social**：本方法把创世 tick 0 的 ageDays 交给 {@link AgeBracket#of}（15/60
   * 是**整历法年**， 2/29 的生日惯例见 {@code CalendarAge}），本类不再另写 365 天的档界 —— 否则"批次按一套边造、劳动按另一套边折算、读口按第三套边显示"
   * 会被三张表悄悄漂开（本仓最忌"注释声称一致、其实不一致"）。
   */
  static int ageBracketOf(long ageDays) {
    if (ageDays < 0L) {
      throw new IllegalArgumentException("ageDays 不得为负: " + ageDays);
    }
    return AgeBracket.of(GENESIS_CLOCK.system(), GENESIS_CLOCK.dayNumberOfTick(0L), ageDays)
        .ordinal();
  }

  /**
   * **一个批次的每人千分劳动**：它的性别与年龄档 → 系数（默认表；★ 表可按性别覆盖，见 {@link #AGE_LABOR_COEF_BY_SEX}）。
   *
   * <p>★ 量纲：一个人满劳动 = 1000 千分劳动 ⇒ 青壮 1000、老年 300、未成年 0（D4 preset）。
   */
  static long perCapitaLaborPerMille(PopulationGroup group) {
    return perCapitaLaborPerMille(group, AGE_LABOR_COEF_BY_SEX);
  }

  /** 同上的**可注入表**重载（包内可见的旋钮：单测据此证明"性别真的进了折算"，见类注）。 */
  static long perCapitaLaborPerMille(PopulationGroup group, Map<Sex, int[]> coefficientsBySex) {
    int[] coefficients = coefficientsBySex.get(group.sex());
    if (coefficients == null) {
      throw new IllegalStateException("性别 " + group.sex() + " 不在劳动系数表里（拒绝臆造）");
    }
    return coefficients[ageBracketOf(group.ageAtAnchorDays())];
  }

  /**
   * ★★ **有效劳动（千分劳动）= Σ 批次 (count × 该批次的每人系数)**：人口按"性别 × 年龄"分组的**唯一**折算入口 （设计稿 §四：{@code
   * availableLabor = Σ(count × ageSexCoefficient)}）。
   *
   * <p>★ **性别在这里进入折算**：每人系数按 {@code group.sex()} 取表（默认两性同表 ⇒ 与 R1 之前逐值相同）。
   */
  static long laborMilli(List<PopulationGroup> groups) {
    return laborMilli(groups, AGE_LABOR_COEF_BY_SEX);
  }

  /** 同上的**可注入表**重载（包内可见的旋钮：R2 的"男耕女织"与它的判别力都落在这里）。 */
  static long laborMilli(List<PopulationGroup> groups, Map<Sex, int[]> coefficientsBySex) {
    long total = 0L;
    for (PopulationGroup group : groups) {
      total += group.count() * perCapitaLaborPerMille(group, coefficientsBySex);
    }
    return total;
  }

  /**
   * ★★ <b>P2-A §13.4：一个家户每 tick 的时间预算（毫小时）</b> = Σ_{属于它的批次} count × {@link
   * HouseholdLaborTimeTable#perPersonMilliHours}(年龄档, 性别)。年龄档 = {@link #ageBracketOf(long)}（与 D4
   * 三档同序）。
   *
   * <p>★ 这是 {@code HouseholdEconomy.laborMilli} 在创世时的唯一算法；运行时每 tick 由协调器按同一张表从 Social 重算。
   */
  static long householdBudgetMilli(
      List<PopulationGroup> allPool, PoolCohorts cohorts, HouseholdId household) {
    long total = 0L;
    for (PopulationGroup group : allPool) {
      if (!cohorts.householdOfGroup(group).equals(household)) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(
                  group.count(),
                  HOUSEHOLD_LABOR_TIME_TABLE.perPersonMilliHours(
                      ageBracketOf(group.ageAtAnchorDays()), group.sex())));
    }
    return total;
  }

  /**
   * 某行分到的有效劳动 = 本行人口 × **池的人均劳动**（{@code poolLaborMilli ÷ poolCount}，逐行取整；池空 ⇒ 0）。
   *
   * <p>★ 与 R1 之前**逐值同形**：池的年龄构成恰是 D4 preset、且系数表两性同值时，{@code poolLaborMilli == poolCount × 580} ⇒
   * 本式退化为 {@code 人口 × 580}（那就是旧口径）。整数运算，不丢精度、不做除零。
   */
  private static long rowLaborMilli(long population, long poolLaborMilli, long poolCount) {
    if (poolCount == 0L) {
      return 0L;
    }
    return population * poolLaborMilli / poolCount;
  }

  /**
   * 有效劳动（千分劳动）的**窄入口**：人口 × Σ(年龄档占比 × 档内系数)（§十"D4 默认"，= 每人 580‰）。
   *
   * <p>★ **它不读批次**（没有批次可读时用它：{@code EconomyTestWorld} 那类手搭的夹具）。口径是"人口未按性别/年龄分组"， 故与 {@link
   * #laborMilli(List)} 在默认 preset 下**同值**（两性同系数 ⇒ 每个性别的人均系数都是 580‰）。
   */
  public static long laborMilli(long population) {
    long perCapitaPerMille = 0L;
    for (int i = 0; i < AGE_SHARE_PER_MILLE.length; i++) {
      perCapitaPerMille += (long) AGE_SHARE_PER_MILLE[i] * AGE_LABOR_COEF_PER_MILLE[i] / 1000L;
    }
    return population * perCapitaPerMille;
  }

  /**
   * **第 1 天**的口粮（毫粮）：{@link EconomyVocabulary#dailyRationMilli}(人口, 1) —— 创世写进 {@code naturalNeeds}
   * 的初值。
   *
   * <p>★★ **为什么带"第 1 天"**（V5）：日耗不是一个常量，而是**绝对日号的函数**（累计口粮的逐日差分：10,000 毫粮/人 ÷ 120 天 除不尽， 残差必须逐日补足，见
   * {@link EconomyVocabulary}）。★ 结算**每天**会把当天需求覆写进 {@code naturalNeeds}（spec §八.8 的"一条真相"） ⇒
   * 这一笔只在"尚未结算过"时可见（读口/GUI 首帧）。
   */
  public static long firstDayRationMilli(long population) {
    return EconomyVocabulary.dailyRationMilli(population, 1L);
  }

  /**
   * 初始库存（毫粮）：{@code 人口} 人 **{@code 该阶层天数} 天**的口粮 = {@link
   * EconomyVocabulary#cumulativeRationMilli}(人口, 天数)。
   *
   * <p>★ 口径校验：这份储备**恰好**够吃到第 {@code days} 天末（第 1..days 天的日耗之和 == 该累计值，逐日差分 telescopes）。 ★ 不许写成"人口 ×
   * 一天的量 × 天数"—— 日耗逐日不同，乘不出来。
   */
  public static long rationMilli(long population, String slot) {
    return EconomyVocabulary.cumulativeRationMilli(population, initialRationDays(slot));
  }

  /** 某阶层"每人几天的口粮"（版本化参数表 {@link #INITIAL_RATION_DAYS_BY_CLASS}）；查不到 ⇒ fail-closed（拒绝臆造）。 */
  public static int initialRationDays(String slot) {
    Integer days = INITIAL_RATION_DAYS_BY_CLASS.get(new SocialClassId(slot));
    if (days == null) {
      throw new IllegalArgumentException("阶层槽位 " + slot + " 不在初始口粮天数表里（拒绝臆造）");
    }
    return Math.multiplyExact(days, GENESIS_GRAIN_RESERVE_MULTIPLIER);
  }

  /**
   * ★★★ <b>W5.5-fix2：单位户（{@code hh-unit:<unitId>} —— 官吏户 / 军户）的创世口粮天数</b> —— 与**创世流民户**
   * 同一个口径：查**同一张** {@link #INITIAL_RATION_DAYS_BY_CLASS}、槽位取**最贫一档**（{@link #CLASS_IDS}{@code
   * [0]}）。
   *
   * <p>★★ <b>为什么不查该户自己的阶层槽位（回代码核过）</b>：单位户在经济行里的 {@code stratum} 是 {@code official} （官吏户，{@code
   * GovWorldBootstrap.registerHouseholdPayload}）或 {@code landless_laborer}（军户，{@code
   * unit.tools.write.RaiseUnitPlan}/{@code unit.tools.write.SpawnArmyPlan}）—— <b>两者都不在初始口粮天数表里</b>
   * ⇒ {@link #initialRationDays(String)} 对它们 fail-closed（"拒绝臆造"，不是能查到 0 天）。而"**没有阶层槽位的家户怎么给
   * 创世粮**"在本类里已经有**唯一先例**：{@link #DISPLACED_SLOT} 的流民户走 {@code initialRationDays(CLASS_IDS[0])} （见
   * {@code classRows}）⇒ 本方法沿用它，**不新造常数、不改那张表、不动任何时点/相位**。
   */
  public static String unitHouseholdRationSlot() {
    return CLASS_IDS[0];
  }

  /** 上一条的"每人几天"（走 {@link #initialRationDays(String)} 的同一张表与同一个倍数）。 */
  public static int unitHouseholdRationDays() {
    return initialRationDays(unitHouseholdRationSlot());
  }

  /**
   * ★★★ <b>W5.5-fix2：一个单位户的创世粮（毫粮）</b> —— 与普通家户**逐字同一条算式**（{@link #rationMilli}： {@code
   * cumulativeRationMilli(人口, 该阶层天数)}，普通家户的落点是 {@code openingStock} 的第一项），只是阶层槽位按 {@link
   * #unitHouseholdRationSlot()} 取（理由见上）。
   *
   * <p>★ <b>为什么需要它（真根因）</b>：单位户由 {@code GovWorldBootstrap}/{@code ThreePowersGovBootstrap} 在 {@code
   * actor.Seed} **之后**才建户建账（创世只给国库注入粮）⇒ 第 1 天它们手里一本空账；而当日消费（吃粮段）发生在当天 **俸禄日结之前**、且第 1 天结构上没有市场轮 ⇒ 第
   * 1 天整日需求全落空（satiety −150‰）⇒ 饥饿逃亡率逐日累加到 day8 ⇒ 逃 1 人 ⇒ {@code GOV_SERVICE}
   * 承诺永久腰斩。本方法把**创世口径的一致性**补上（不是逐日给养通道，那是 P4c）。
   *
   * @param population 该户当前人口（取 Social 权威，不是调用方手里那个预期值）；&lt; 0 ⇒ 拒
   */
  public static long unitHouseholdGenesisGrainMilli(long population) {
    return rationMilli(population, unitHouseholdRationSlot());
  }

  /**
   * ★★ **给一个产业发配额**（R2；R3 起按活动加性别权重；M1.8 起按**折扣后的可用劳动**加权）：把 {@code total} 按各批次的 **加权可用劳动**成比例切给它们
   * （最大余数法，{@code Σ 配额 == total}）。
   *
   * <pre>
   * 权重(batch) = 该批次**按阶层参与率折扣后的可用劳动** × ACTIVITY_SEX_WEIGHT_PER_MILLE[活动][该批次性别] ÷ 1000
   * 配额(batch) = total × 权重 ÷ Σ权重                              // Σ == total（精确，残差按最大余数法）
   * </pre>
   *
   * <p>★★ <b>"同一批次可以供给多个产业"因此是结构上成立的</b>：本方法只写"某一 (批次, 产业) 对"的一条配额，同一批次被另一个活动 再调用一次就会拿到**第二条**配额。
   * 创世给农村批次发**两条** （农业 900‰ + 家庭纺织 100‰），**这正是 spec §四 那条压力测试**（"同一批人口能否同时参与多个生产过程，并保持劳动力守恒"）。
   *
   * <p>★★ **性别在这里进入配置**：权重按批次性别取 {@link #ACTIVITY_SEX_WEIGHT_PER_MILLE} ⇒ 纺织的配额默认偏向女性
   * （"男耕女织"），而**不是**"女 = 纺织"的硬编码（男人照样有一条非零的纺织配额，只是权重低）。
   *
   * <p>★★ **每一批次的配额受 {@code budget} 约束**（见 {@link #laborBudget}）：切出来的份额若超过该批次**剩下的** 可支配劳动，
   * 就压到上限（**多出来的部分留在预算里，也就是"分不满"** —— R2 明说配额之和可以小于可用劳动）。 ⇒ "Σ 该批次在各产业的配额 ≤
   * 其可用劳动"**构造性成立**，不依赖"默认权重恰好不越界"。★ 这道预算是实测出来的： 把 {@link #WEAVE_SHARE_PER_MILLE} 改成 0（一个完全合理的 GM
   * 配置）时，性别权重的偏斜会让男性青壮批次要 4,202,381 —— 改前（预算 = 毛额 4,072,000）它被压到毛额；★ M1.8 起预算 = 折扣后的可用
   * 3,501,920，压得更早、更紧（这正是"参与率进计算"的落点）。★ 没有预算时载荷会被 {@code EconomyData} 的构造期守卫**当场拒**（世界播不出来）。
   *
   * <p>★ **{@code total ≤ 0} 时不发**（该池没活干，或该活动拿到的份额取整为 0）；**权重为 0 的批次也不发**（"没有配额"与"0 的配额"逐值同效）。
   *
   * @param allocations 出参：本格的配额行（每 (批次, 产业) 一条）
   * @param budget 该池各批次的**剩余可支配劳动**（{@link #laborBudget}；**就地扣减**）
   * @param total 该 unit 本次要切出去的劳动总量（千分劳动；= 该池日劳动 × 该活动的份额）
   * @param unitId 收劳动的生产单元（R3B.2 起配额的 id 与 activity 都按它拼；见 {@code HouseholdLaborCommitment.idOf}）
   * @param operator 该 unit 的经营者（收劳动的主体；actor 列按它落载荷）
   * @param activity 这笔劳动干什么的**标签**（本仓当前用产业种类标签：{@code farm} / {@code weave} / {@code craft}；
   *     只有性别权重表读它，载荷的 activity 列写的是 unit id）
   */
  static void appendAllocation(
      List<Map<String, Object>> allocations,
      Map<PeopleLotId, Long> budget,
      List<PopulationGroup> pool,
      HexCoord hex,
      ProductionUnitId unitId,
      ActorRef operator,
      String activity,
      long total) {
    if (total <= 0L) {
      return;
    }
    Map<Sex, Integer> sexWeights = ACTIVITY_SEX_WEIGHT_PER_MILLE.get(activity);
    if (sexWeights == null) {
      throw new IllegalStateException("活动 " + activity + " 不在性别权重表里（拒绝臆造）");
    }
    List<PopulationGroup> workers = new ArrayList<>(pool.size());
    List<Long> weights = new ArrayList<>(pool.size());
    for (PopulationGroup group : pool) {
      // ★★ M1.8：权重 = **按阶层参与率折扣后的**可用劳动（不是毛额）—— 与 total（也是折扣后的口径）同侧。
      long available = participationAdjustedLaborMilli(group);
      if (available > 0L) {
        workers.add(group);
        weights.add(available * sexWeightOf(sexWeights, group.sex()) / 1000L);
      }
    }
    if (workers.isEmpty()) {
      return; // 池里没有能干活的人（毛劳动全 0）⇒ total 必为 0，上面已经挡掉；这里是防御性的第二道
    }
    long[] weightArray = new long[weights.size()];
    for (int i = 0; i < weightArray.length; i++) {
      weightArray[i] = weights.get(i);
    }
    long[] shares = splitProportional(total, weightArray); // Σ shares == total（最大余数法，一个人不丢）
    ResidenceKind residence = ResidenceKind.ofLot(pool.get(0).id());
    for (int i = 0; i < workers.size(); i++) {
      PopulationGroup group = workers.get(i);
      // ★★ 预算约束（见方法注释）：份额超过该批次**剩下的**可支配劳动 ⇒ 压到上限，多出来的留在预算里。
      long share = Math.min(shares[i], budget.getOrDefault(group.id(), 0L));
      budget.put(group.id(), budget.getOrDefault(group.id(), 0L) - share);
      if (share <= 0L) {
        continue; // 该批次在这一活动上的权重为 0 / 取整为 0 / 预算已用尽 ⇒ 不发 0 配额
      }
      int slot = stratumSlotOfLot(group);
      if (slot >= 0) {
        // ★★ P2-A：批次按 (居住 × 阶层) 分组 ⇒ 这份劳动整份归它自己的家户（不再按阶层比例拆给四个户）。
        HouseholdId household =
            HouseholdIds.ofSeed(hex, residence, new SocialClassId(CLASS_IDS[slot]));
        allocations.add(allocationNode(unitId, operator, group, household, share));
        continue;
      }
      // ★ 旧形状夹具（批次 cohort 段不是 s<slot>-<n>）：退回"按阶层比例拆到四个家户"的旧口径。
      long[] byHousehold = splitByShares(share, CLASS_SHARE_PER_MILLE);
      for (int stratum = 0; stratum < CLASS_IDS.length; stratum++) {
        if (byHousehold[stratum] <= 0L) {
          continue;
        }
        HouseholdId household =
            HouseholdIds.ofSeed(hex, residence, new SocialClassId(CLASS_IDS[stratum]));
        allocations.add(allocationNode(unitId, operator, group, household, byHousehold[stratum]));
      }
    }
  }

  /** 批次的阶层槽位（`s<slot>-<age>` 形状）；旧形状/流民批次 ⇒ -1。 */
  static int stratumSlotOfLot(PopulationGroup group) {
    String tag = PopulationLots.cohortOf(group.id());
    int dash = tag.indexOf('-');
    if (dash <= 1 || tag.charAt(0) != 's') {
      return -1;
    }
    try {
      int slot = Integer.parseInt(tag.substring(1, dash));
      return slot >= 0 && slot < CLASS_IDS.length ? slot : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * 一条 {@code HouseholdLaborCommitment} 载荷（id/group/household/actor/activity/laborMilli/period）——
   * 唯一拼写点。
   */
  private static Map<String, Object> allocationNode(
      ProductionUnitId unitId,
      ActorRef operator,
      PopulationGroup group,
      HouseholdId household,
      long laborMilli) {
    Map<String, Object> allocation = new LinkedHashMap<>();
    // ★★ R3B.2：id 与 activity 都按 **unit** 拼；actor = unit.operator（收劳动的主体）。
    allocation.put("id", HouseholdLaborCommitment.idOf(unitId, group.id(), household).value());
    allocation.put("group", group.id().value());
    allocation.put("household", household.value());
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("kind", operator.kind().name());
    actor.put("id", operator.id());
    allocation.put("actor", actor);
    allocation.put("activity", unitId.value());
    allocation.put("laborMilli", laborMilli);
    allocation.put("period", FIRST_PERIOD);
    return allocation;
  }

  /**
   * ★★ <b>S1.4：tick0 份额守恒自检</b>：逐 lot 的 {@code Σ membership.count} 必须等于该 {@link PopulationGroup}
   * 的人数。
   *
   * <p>★ seed 是份额的**构造点**（拆分的最大余数法在 {@link #appendMemberships} 里）⇒ 出口这一道是"拆分没丢/没多" 的判别力所在：不等 ⇒
   * 当场抛，把坏载荷挡在命令面之前（播进去的状态再想对账就晚了）。
   */

  /**
   * ★★ **M1.8：一个池的劳动预算**：每批次**按阶层参与率折扣后的可用劳动**（{@link
   * #participationAdjustedLaborMilli(PopulationGroup)}） —— 不再是毛额。
   *
   * <p>★★ <b>为什么要有它</b>：{@link #appendAllocation} 按"性别权重 ×
   * 可用劳动"切活动总量，而**一个池的日劳动本身已经是各行的参与率折扣后的数**（≈ 毛额的 86%）—— 两个活动先后发配额时，偏重的那一性完全可能被分到超过自己那份**可用**劳动
   * （{@link #WEAVE_SHARE_PER_MILLE} 设 0 时男性青壮批次实测 4,202,381 &gt; 它的毛额 4,072,000，更远超它的折扣后可用
   * 3,501,920）。 那时载荷会被 {@code EconomyData} 的构造期守卫拒 ⇒ **世界直接播不出来**。有了预算，那种配置退化成"分不满"（合法状态）。
   *
   * <p>★★ <b>M1.8 修的是什么</b>：改前预算是**毛额** ⇒ "地主 100‰ / 贫农 950‰"的差别只在产业**总量**里出现过一次，逐批次上限里完全不存在
   * （真档实测四阶层 {@code labor/pop} 全 = 562.0‰）。现在预算与权重都走**同一个折扣后的可用劳动** ⇒ 参与率的差别在配额这一步就咬合。
   *
   * <p>★ <b>两条网各自的口径（如实记）</b>：本预算 = <b>紧</b>的那道（按参与率折扣，配额的实际上限）；{@code EconomyData} 的构造期守卫仍以 {@code
   * LaborSupply.availableLabor()} = <b>毛额</b>为上限（宽的那道，零契约改动的代价）。 两者不矛盾：折扣后的配额必然 ≤
   * 毛额，故守卫保持绿；"同一份劳动不得被两个产业各算一次满额"这条不变量由守卫守， 而"不得超过参与率折扣后的可用"由本预算守。
   */
  static Map<PeopleLotId, Long> laborBudget(List<PopulationGroup> pool) {
    // ★★ P2-A §13.4：配额上限改为**该 batch 每 tick 的时间预算**（毫小时）—— 配额 ≤ 所属家户的
    //   HouseholdEconomy.laborMilli 由此构造性成立（splitIndustry 只搬不加）。参与率不再当硬上限，
    //   只作为 appendAllocation 的分配权重（旧口径的"折扣后劳动"不再是权威）。
    Map<PeopleLotId, Long> budget = new LinkedHashMap<>();
    for (PopulationGroup group : pool) {
      long perPerson =
          HOUSEHOLD_LABOR_TIME_TABLE.perPersonMilliHours(
              ageBracketOf(group.ageAtAnchorDays()), group.sex());
      long available = Math.multiplyExact(group.count(), perPerson);
      if (available > 0L) {
        budget.put(group.id(), available);
      }
    }
    return budget;
  }

  /** 某性别在某活动上的默认权重（‰）；表缺 ⇒ fail-closed（拒绝臆造）。 */
  static int sexWeightOf(Map<Sex, Integer> weights, Sex sex) {
    Integer weight = weights.get(sex);
    if (weight == null) {
      throw new IllegalStateException("性别 " + sex + " 不在活动权重表里（拒绝臆造）");
    }
    return weight;
  }
}
