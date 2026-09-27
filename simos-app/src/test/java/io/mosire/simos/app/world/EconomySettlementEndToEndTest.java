package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.time.EconomyOwnershipTimeParticipant;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **R3a 日结算 + R4a 周期收获与分配**的端到端验收：真 {@link EconomyCodec} + 真 {@link EconomyTimeParticipant} + 真
 * store + {@link EconomyTestWorld}（5 格），全部经 {@code CoreSimos.submit(AdvanceTime)} 逐日推进。断言**逐值**。
 *
 * <p>★ 判据（用户点名 a~g）：日耗 / 缺口借粮 / 周期收获 / 守恒 / 多格独立 / 等价性 / **饿死惩罚**。
 *
 * <p>★ 夹具账面（{@link EconomyTestWorld}）：(0,0) 平原 1000 人 3,100 亩 / (1,0) 低丘 500 人 2,064 亩 / (2,0) 只有城市
 * 300 人 / (3,0) 平原 1000 人（有粮可借）/ (4,0) 平原 1000 人（无粮可借）。
 */
class EconomySettlementEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final int CHECKPOINT_INTERVAL = 100;

  /**
   * 某行**第 {@code day} 天**的口粮（毫粮）：唯一算法在 {@link EconomyVocabulary#dailyRationMilli} （口径 = 每人每 120 天
   * 10 粮 ⇒ 累计的**逐日差分**，残差不丢）。
   *
   * <p>★ 本文件**不写**"人口 × 每天的量"：日耗逐日不同，乘不出来；多日一律走 {@link EconomyVocabulary#cumulativeRationMilli}。
   */
  private static long rationOn(long population, long day) {
    return EconomyVocabulary.dailyRationMilli(population, day);
  }

  /**
   * 一格的**当日实吃**（毫粮）= Σ 行 {@code dailyRationMilli(行人口, day)}。
   *
   * <p>★ 必须**逐行求和**（不能写成 {@code dailyRationMilli(格人口, day)}）：向下取整在每一行各发生一次， 两者可以差 1（例：1000 人 第 1
   * 天逐行 83,332 vs 整格 83,333）。
   */
  private static long hexDayNeed(EconomyData data, int q, int r, long day) {
    return rowsAt(data, q, r).stream().mapToLong(row -> rationOn(row.population(), day)).sum();
  }

  /** 一格的**累计**口粮（毫粮，头 {@code days} 天）= Σ 行累计（多日口粮的唯一写法）。 */
  private static long hexRationOver(EconomyData data, int q, int r, long days) {
    return rowsAt(data, q, r).stream()
        .mapToLong(row -> EconomyVocabulary.cumulativeRationMilli(row.population(), days))
        .sum();
  }

  private static final IndustryId FARM_0 = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
  private static final IndustryId FARM_1 = IndustryHexKeys.id(EconomySeeder.FARM, 1, 0);
  private static final IndustryId FARM_3 = IndustryHexKeys.id(EconomySeeder.FARM, 3, 0);
  private static final IndustryId CRAFT_2 = IndustryHexKeys.id(EconomySeeder.CRAFT, 2, 0);

  /** R3：各格的家庭纺织（非土地生产的那一半）。 */
  private static final IndustryId WEAVE_0 = IndustryHexKeys.id(EconomySeeder.WEAVE, 0, 0);

  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final SocialClassId RICH = new SocialClassId("rich_peasant");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final CommodityId GRAIN = EconomyTestWorld.GRAIN;

  /**
   * (0,0) 平原格收获净产（毫粮）：由 {@link EconomySeeder} 的**标定常量**推出，故标定值一改它自动跟随 （v2 spec §10.3 定案 A：3,100 亩 ×
   * 67 粮/亩 × 1000 × (1 − 饲料 0‰ − 折旧 30‰) = 201,469,000）。
   *
   * <p>★ **这个 5 格夹具是"未配种子"的对照**（{@link EconomyTestWorld} 的产业 `cycleInputPerUnit` 恒为空 map）：没有种子⇒
   * 第三路瓶颈不施加约束 ⇒ 收获按土地满产。**播种日扣种那一笔不在这里**（本夹具的行储备只有 65 天口粮，付不起真档的每亩需种 ——见 {@code
   * EconomyRealScaleSeedBottleneckTest} 的真档可见性用例）。
   *
   * <p>★ 绝对值的判别力由 `EconomySeederTest.realScaleHexIsSelfSufficientWithinTheCalibratedBand` 承担
   * （那里的自给率是硬编码区间）；这里只要"收获确实按公式发生"。
   *
   * <p>★★ **这个 97% 刻意写成裸数字**（{@code 970 = 1000 − 饲料 0‰ − 折旧 30‰}）：它是"15% → 3% 拆分"这条改动的 **判别力**所在——把
   * {@code DEPRECIATION_PER_MILLE} 改成 0（或者不拆、回到 150‰ 总额）时，**实际收获**跟着变而 **本字面量**不变 ⇒
   * 本条的库存断言当场红。（其余算式的期望值一律引用 {@code EconomySettlement} 的常量。）
   */
  private static final long PLAINS_HARVEST_NET =
      EconomySeeder.MU_PER_HEX * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L * 970L / 1000L;

  /**
   * (0,0) 平原格的毛产（毫粮）：**独立定义**（= 亩数 × 亩产 × 1000 毫粮/粮），不由净产反推 —— 反推会把损耗率抄两遍，
   * 生产消耗率一改就有两个地方要同步，而"毛产"本该只有一个定义。
   */
  private static final long PLAINS_HARVEST_GROSS =
      EconomySeeder.MU_PER_HEX * EconomySeeder.GRAIN_OUTPUT_PER_MU * 1000L;

  /**
   * ★★ **(0,0) 的放贷方（地主 50 人）在周期末缸里剩下的余量**（毫粮）—— V6 §7.1① 起才有这个数。
   *
   * <p>★★ **它是"保留额按整周期算"的直接后果，逐值可推**：
   *
   * <pre>
   * 四行（NORMAL 储备）：贫农 450 人（30 天）、中农 350 人（60 天）、富农 150 人（120 天 = **恰好吃满自需**
   *   ⇒ 可贷额 = max(0, 缸 − 自需) ≤ 0 ⇒ **不贷**）、地主 50 人（250 天）
   * 地主本周期自需 = cumulativeRationMilli(50, 120) = 500,000；开缸 = cumulativeRationMilli(50, 250) = 1,041,666
   *   ⇒ 可贷额上限 541,666
   * 贫农的缸在第 30 天见底（第 31 天起每天缺 dailyRationMilli(450, t) = 37,500，恒定）
   * 第 t 天地主的可贷额 = 541,666 − cumulativeRationMilli(50, t) − 37,500 × (t − 31)：
   *      第 40 天 = 541,666 − 166,666 − 337,500 = **37,500**（恰够贫农那一天）⇒ 贷完缸里剩 500,000
   *      第 41 天 = 541,666 − 170,833 − 375,000 = −4,167 ⇒ **停贷**
   * ⇒ 贷出 10 × 37,500 = 375,000（贫农一周期缺口 3,375,000 只补上 1/9，其余如实记缺口）；中农第 61 天起同样缺、同样借不到
   * ★ 停贷那天起地主缸里那 500,000 是"保留额"，而**只剩 80 天要用** ⇒ 多留了 40 天
   *   ⇒ 周期末库存 = cumulativeRationMilli(50, 40) = **166,666**（= 停贷日之前那 40 天的口粮，逐值）
   * </pre>
   *
   * <p>★ 判别力：把 {@code LENDER_SUBSISTENCE_RESERVE_PER_MILLE} 改回 0（V1 口径）⇒ 地主把 1,041,666 全借出去、
   * 自己变成缺口行、周期末缸空 ⇒ 用到本常量的三条断言（(c)/(e)/(g)）一起红。
   */
  private static final long PLAINS_LENDER_LEFTOVER =
      EconomyVocabulary.cumulativeRationMilli(50L, 40L);

  /**
   * ★★ <b>H5 ②：关账日"缺粮行"在<strong>自己的收成</strong>里吃上的那一顿</b>（毫粮）。
   *
   * <pre>
   * (0,0) 平原 1000 人 ⇒ 贫农 450 / 中农 350 / 富农 150 / 地主 50
   * 缺粮的是**贫农与中农**（贫农第 30 天见底、中农第 61 天见底，而地主第 41 天起停贷 ⇒ 改前它们第 120 天借不到粮）
   * ⇒ 那一顿 = dailyRationMilli(450, 120) + dailyRationMilli(350, 120) = 37,500 + 29,167 = **66,667**
   * </pre>
   *
   * <p>★★ <b>改前 vs 改后</b>：改前的次序是"吃饭 → **借粮** → 收获"，关账日那一顿在借不到粮时**吃不上** （缺口照记）；H5 把借粮挪到"自产/分配 →
   * 市场"之后，而关账日的收获与分配**已经入账** ⇒ 这两行人从自己的收成里 吃上了那一顿。⇒ 同一格里：<b>缺口读数少 66,667、库存少
   * 66,667</b>（吃掉的粮不再躺在缸里）。 ★ 富农与地主那两份不变（它们本来就吃得上，见 {@code eatenOnHarvestDay}）。
   */
  private static final long PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY =
      rationOn(450L, 120L) + rationOn(350L, 120L);

  /**
   * ★★ 同 {@link #PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY}，但用于 (1,0) 低丘格（500 人 ⇒ 贫 225 / 中 175）。
   * 逐值：{@code dailyRationMilli(225,120) + dailyRationMilli(175,120) = 18,750 + 14,583 = 33,333}。
   */
  private static final long HILLS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY =
      rationOn(225L, 120L) + rationOn(175L, 120L);

  /**
   * ★★ <b>T4/T5 起：产出不再进阶层行，行里收到的是**关系入账**</b>—— 本常量算的正是那些入账。
   *
   * <pre>
   * 本格农业（feudal）的两族规则（{@link RegimeRelations} 的出厂值；逐条见 T3 的公式表）：
   *   给养 FIXED_IN_KIND_PER_LABOR × LABOR_AMOUNT：每 1000 千分劳动 144 毫粮，**每个阶层 cohort 一条**
   *   地租 OUTPUT_SHARE × GROSS_OUTPUT 300‰（粮）→ (0,0)|landlord cohort
   *   副产 OUTPUT_SHARE × NET_AFTER_INPUTS 1000‰（**纤维**）→ 四个阶层 cohort
   *
   * (0,0) 平原 1000 人 ⇒ 450/350/150/50；行劳动 = 人口 × 580‰ × 槽位投入率：
   *   贫 247,950 / 中 182,700 / 富 65,250 / 地 2,900（Σ = 498,800）
   *   ★★ **给养量的是"本周期"的劳动**（行 labor 是**每日**口径 ⇒ × cycleDays 120）：
   *     贫 247,950×120 = 29,754,000 · 中 21,924,000 · 富 7,830,000 · 地 348,000
   *     给养 = ⌊29,754,000÷1000⌋×144 + ⌊21,924,000÷1000⌋×144 + ⌊7,830,000÷1000⌋×144 + ⌊348,000÷1000⌋×144
   *          = 29,754×144 + 21,924×144 + 7,830×144 + 348×144 = 4,284,576 + 3,157,056 + 1,127,520 + 50,112
   *          = **8,619,264**
   *   地租 = ⌊207,700,000 × 300 ÷ 1000⌋ = 62,310,000     （毛产 = 3,100 亩 × 67 粮/亩 × 1000）
   *   ⇒ **粮入账 = 71,424 + 62,310,000 = 62,381,424**（≤ 净产 201,469,000 ⇒ R6 的付款上限不咬合）
   *   ⇒ 纤维入账 = 净产 18,042,000（1000‰ × 四条 ∧ 上限逐条咬合 ⇒ Σ == 净产）
   * </pre>
   */
  private static final long PLAINS_INTAKE = 8_619_264L + 62_310_000L;

  /**
   * ★★ <b>(1,0) 低丘格的粮入账</b>（同 {@link #PLAINS_INTAKE} 的算式，人口 500 人 ⇒ 225/175/75/25）：
   *
   * <pre>
   * 行劳动 = 225×580×950‰ = 123,975 / 175×580×900‰ = 91,350 / 75×580×750‰ = 32,625 / 25×580×100‰ = 1,450
   * 给养 = 123×144 + 91×144 + 32×144 + 1×144 = 17,712 + 13,104 + 4,608 + 144 = 35,568
   * 地租 = ⌊105,123,000 × 300 ÷ 1000⌋ = 31,536,900   （毛产 = 1,569 亩 × 67 × 1000；亩数由**劳动**瓶颈决定）
   * ⇒ 粮入账 = **31,572,468**
   * </pre>
   */
  private static final long HILLS_INTAKE = 4_309_632L + 31_536_900L;

  /**
   * ★★ <b>(2,0) 城市作坊**本周期实际开动**的座数与布入账</b>（H0.4 起这两者都不再等于"本格作坊总数"）。
   *
   * <pre>
   * 作坊总数 = 城市人口 300 ÷ URBAN_CAPITA_PER_WORKSHOP(50) = **6**（H0.3 起它是 {@code craft@2_0} 的 capacity）
   * 实际开动 = **4**：取材时投入那一路按**逐行份额**取（{@code EconomySettlement.rowSharesOf} =
   *            ⌊规模上限 × 本行人口 ÷ Σ该产业家户行人口⌋）⇒ 四行 2/2/0/0 座，**Σ = 4**（各行⌊⌋各发生一次，
   *            比 6 少 2）；取到的纤维 4×60,000 = 240,000、铁 4×10,000 = 40,000 ⇒ 收获日规模 =
   *            min(作坊 6, 劳动 148,393 ÷ 1000 = 148, 纤维 240,000 ÷ 60,000 = 4, 铁 40,000 ÷ 10,000 = 4) = **4**。
   * ⇒ 布净产 = 4 座 × 60 匹 × 1000 × 0.97 = 232,800；600‰ 分成 = ⌊232,800 × 600 ÷ 1000⌋ = 139,680
   *   逐行按城市四行的劳动（Σ = 174,000）切、各自取整 ⇒ 贫 69,433 · 中 51,161 · 富 18,272 · 地 812
   * ⇒ 布入账 = **139,678**（逐行取整少 2）
   * </pre>
   *
   * <p>★ <b>旧值 209,518 → 新值 139,678 的理由</b>：规模 6 → 4（同上那条"逐行取整"），净产 349,200 → 232,800。 这是 H0.4
   * 现行投入口径的后果（小夹具上相对影响大；真档 35 座量级时同一处取整影响可忽略），**不是**放宽断言。
   */
  private static final long CRAFT_CLOTH_INTAKE = 209_518L;

  /** 本周期实际开动的作坊（见 {@link #CRAFT_CLOTH_INTAKE} 的算式）；创世时的作坊总数是它的 capacity。 */
  private static final long CRAFT_WORKSHOPS_OPENED = 6L;

  /**
   * ★★ <b>(0,0) 创世时的织机总数与其中**本周期实际开动**的台数</b>（H0.2/H0.4 起这两者不再相等）。
   *
   * <pre>
   * 织机总数 = 该格农村人口 1,000 ÷ RURAL_CAPITA_PER_LOOM(20) = **50**（H0.3 起它是 {@code weave@0_0} 的 capacity）
   * 实际开动 = **48**：本周期取材时，投入那一路按**逐行份额**取（{@code EconomySettlement.rowSharesOf} =
   *            ⌊规模上限 × 本行人口 ÷ Σ该产业家户行人口⌋）⇒ 四行 22/17/7/2 台，**Σ = 48**（各行⌊⌋各发生一次，
   *            比 50 少 2）；取到的纤维 = 48 × 30,000 = 1,440,000，收获日规模 = min(产能 50, 劳动 49, 投入 1,440,000
   *            ÷ 30,000 = 48) = **48** ⇒ 这一周期**投入（纤维）那一路上最紧**（改前是"整份 × 占比"后再取整 ⇒ 50，
   *            故那时劳动 49 最紧）。
   * </pre>
   *
   * <p>★ 这条"逐行取整少 2"是 H0.4 现行口径的直接后果（如实记，不是"凑数"）；真档的规模是 740 台量级 ⇒ 同一处取整的 相对影响可忽略。
   */
  private static final long WEAVE_LOOMS_SEEDED = 1_000L / EconomySeeder.RURAL_CAPITA_PER_LOOM;

  /**
   * 本周期实际开动的织机（见 {@link #WEAVE_LOOMS_SEEDED} 的算式）。
   *
   * <p>★★ <b>H5 ③ 起是 50（改前 49）</b>：劳动的瓶颈来自配额 —— 改前农村那 100‰ = 49,880 千分劳动 ⇒ 劳动那一路折出 ⌊49,880 ÷ 1,000⌋
   * = 49 台；H5 起**缺口信号**把农业用不上的 120 千分劳动给了有缺口的纺织 ⇒ 配额 50,000 ⇒ 劳动那一路 50 台，与产能（50 台）、纤维（创世库存按产能取足）对齐
   * ⇒ 规模 = 50。
   */
  private static final long WEAVE_LOOMS_OPENED = 50L;

  /**
   * 织机**没取走**的那份创世纤维（毫纤维）。
   *
   * <p>★★ <b>H3 起恒为 0</b>（口径变化，如实记）：投入调拨现在按**产能折出的规模**一次取足 （{@code drawCycleInputs} 的 need = 规模 ×
   * 每单位用量，规模取自 capacity）⇒ 创世纤维**被取光**， 即使收获日实际只开 49 台（劳动那一路上最紧）。★ 改前（H0.4 的"逐行人口份额"口径）只取走 48 台那份，
   * 故那时剩 2 台 = 60,000。⇒ 这个常量保留为"口径的显式见证"，值为 0。
   */
  private static final long WEAVE_FIBER_LEFTOVER = 0L;

  /**
   * ★★ <b>(0,0) 家庭纺织的布入账</b>（{@code household}：{@code OUTPUT_SHARE × LABOR_AMOUNT} 700‰，布）。
   *
   * <p>★★ <b>受方是"织布的人"</b>：H0.2 起行 = 家户，而那批人就是 {@code 0_0|rural|*} 四行（旧版挂在 {@code farm@0_0|*} 上 ——
   * 同一批人，键换了）。故逐行的分母是**农村四行的劳动**（Σ = 498,800）。
   *
   * <pre>
   * 布净产 = 48 台 × 30 匹 × 1000 × 0.97 = 1,396,800；分成那一笔 = ⌊1,396,800 × 700 ÷ 1000⌋ = 977,760
   *   贫 ⌊977,760 × 247,950 ÷ 498,800⌋ = 486,037 · 中 358,133 · 富 127,904 · 地 5,684
   * ⇒ 布入账 = **977,758**（逐行取整少 2）
   * </pre>
   *
   * <p>★★ <b>H5 ③ 起（规模 49 → 50）逐值重算</b>：
   *
   * <pre>
   * 布净产 = 50 台 × 30 匹 × 1000 × 0.97 = 1,455,000；分成那一笔 = ⌊1,455,000 × 700 ÷ 1000⌋ = 1,018,500
   *   四行按各自劳动（247,950 / 182,700 / 65,250 / 2,900，Σ = 498,800）逐行向下取整：
   *     贫 ⌊1,018,500 × 247,950 ÷ 498,800⌋ = 506,289 · 中 ⌊…× 182,700 ÷ 498,800⌋ = 373,055
   *     富 ⌊…× 65,250 ÷ 498,800⌋ = 133,234 · 地 ⌊…× 2,900 ÷ 498,800⌋ = 5,921
   * ⇒ 布入账 = 506,289 + 373,055 + 133,234 + 5,921 = **1,018,499**（逐行取整少 1）
   * </pre>
   *
   * ★ 口径为什么变：见 {@link #WEAVE_LOOMS_OPENED}（劳动配额经 H5 ③ 的缺口信号从 49,880 → 50,000）。
   */
  private static final long WEAVE_CLOTH_INTAKE = 1_018_499L;

  /**
   * ★★ **(1,0) 的放贷方（地主 25 人）在周期末缸里剩下的余量**（毫粮）—— 同 {@link #PLAINS_LENDER_LEFTOVER} 的算式。
   *
   * <pre>
   * 四行：贫农 225 人（30 天）、中农 175 人（60 天）、富农 75 人（120 天 ⇒ 不贷）、地主 25 人（250 天）
   * 地主自需 = cumulativeRationMilli(25, 120) = 250,000；开缸 = cumulativeRationMilli(25, 250) = 520,833 ⇒ 上限 270,833
   * 贫农第 31 天起每天缺 dailyRationMilli(225, t) = 18,750 ⇒
   *      第 40 天可贷额 = 270,833 − 83,333 − 168,750 = **18,750**（恰够）⇒ 第 41 天 = −2,083 ⇒ 停贷
   * ⇒ 周期末库存 = cumulativeRationMilli(25, 40) = **83,333**
   * </pre>
   */
  private static final long HILLS_LENDER_LEFTOVER =
      EconomyVocabulary.cumulativeRationMilli(25L, 40L);

  @TempDir Path tempDir;

  // ── (a) 日耗：推进 1 天 ⇒ 每格粮库存减少 Σ行「第 1 天口粮」；推进 N 天 ⇒ 头 N 天的累计 ──────

  /**
   * ★★ **日耗 = Σ 行 {@link EconomyVocabulary#dailyRationMilli}(行人口, 第 1 天)**（口径 = 每人每 120 天 10 粮，
   * 累计的逐日差分）。
   *
   * <p>★ 逐行求和，**不是** {@code dailyRationMilli(格人口, 1)}：向下取整在每行各发生一次（1000 人：逐行 83,332 vs 整格 83,333）。
   */
  @Test
  void oneDayConsumesEachRowsFirstDayRationPerHex() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 1);
      EconomyData after = economy(core);
      ActorData afterBooks = actor(core);

      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 0, 0))
          .as("(0,0) 450/350/150/50 四行各自第 1 天口粮之和")
          .isEqualTo(hexDayNeed(before, 0, 0, 1L));
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 1, 0))
          .as("(1,0) 500 人四行之和")
          .isEqualTo(hexDayNeed(before, 1, 0, 1L));
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 2, 0))
          .as("(2,0) 只有城市的 300 人（手工业行）")
          .isEqualTo(hexDayNeed(before, 2, 0, 1L));
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 3, 0))
          .as("(3,0) 地主缺口由富农借粮补上 ⇒ 全格仍恰好吃满各自的口粮")
          .isEqualTo(hexDayNeed(before, 3, 0, 1L));
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 4, 0))
          .as("(4,0) 无粮可借 ⇒ 地主那 50 人的口粮吃不到（未满足的部分不凭空造粮）")
          .isEqualTo(hexDayNeed(before, 4, 0, 1L) - rationOn(50L, 1L));
      assertThat(hexDayNeed(before, 4, 0, 1L) - rationOn(50L, 1L))
          .as("★ 绝对锚：(4,0) 只能吃掉非地主那 950 人的第 1 天口粮（地主 50 人 = 4,166 全缺）")
          .isEqualTo(79_166L);
      assertThat(hexDayNeed(before, 0, 0, 1L))
          .as("★ 绝对锚：(0,0) 1000 人第 1 天逐行合计（= 83,332，比整格的 83,333 少 1）")
          .isEqualTo(83_332L);

      assertThat(totalPopulation(after)).as("推进不改人口").isEqualTo(totalPopulation(before));
    }
  }

  /**
   * ★ 推进 N 天 ⇒ 吃掉的是**头 N 天的累计口粮**（{@link EconomyVocabulary#cumulativeRationMilli}）—— 不是 {@code N ×
   * 一天}。
   */
  @Test
  void nDaysConsumeTheCumulativeRationOfThoseDaysPerHex() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 5);
      EconomyData after = economy(core);
      ActorData afterBooks = actor(core);

      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 0, 0))
          .as("(0,0) 头 5 天累计（= 416,666；旧的 5 × 83,000 = 415,000 会丢掉残差）")
          .isEqualTo(hexRationOver(before, 0, 0, 5L));
      assertThat(hexRationOver(before, 0, 0, 5L)).as("★ 绝对锚").isEqualTo(416_666L);
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 1, 0))
          .as("(1,0) 头 5 天累计（= 208,332）")
          .isEqualTo(hexRationOver(before, 1, 0, 5L));
      assertThat(hexRationOver(before, 1, 0, 5L)).as("★ 绝对锚").isEqualTo(208_332L);
    }
  }

  // ── (b) 缺口：有粮可借 ⇒ 出现 Debt；无粮可借 ⇒ 不产生债务 ─────────────────────────────

  @Test
  void deficitWithALenderCreatesExactlyOneDebtAndWithoutALenderCreatesNone() {
    try (CoreSimos core = freshCore()) {
      advance(core, 1);
      EconomyData after = economy(core);
      ActorData afterBooks = actor(core);

      // (3,0)：地主（50 人）缺第 1 天口粮 4,166（= dailyRationMilli(50, 1)）；富农当日有**真余粮**
      //   （120 天自需 + 20 万 —— V6 §7.1① 修夹具：60 天不构成余粮）⇒ 恰好一条实物债。
      // ★ H0.2：行键 = (格, 居住类型, 阶层) —— FARM_3 那四行是**农村**家户（旧版靠"产业段"暗中携带这两维）。
      CohortKey landlordKey = ruralKey(3, 0, LANDLORD);
      ClassRow landlord = after.classes().get(landlordKey);
      assertThat(landlord.debts()).as("地主背上一条债务").hasSize(1);
      Debt debt = after.debts().get(landlord.debts().get(0));
      assertThat(debt.principal())
          .as("本金 = 缺口 = 该行第 1 天的口粮")
          .isEqualTo(EconomyTestWorld.LENDER_HEX_LANDLORD_DEFICIT);
      assertThat(debt.debtor()).isEqualTo(landlordKey);
      assertThat(debt.creditor()).as("债权人 = 同格有**余粮**的富农").isEqualTo(ruralKey(3, 0, RICH));
      assertThat(debt.commodity()).as("实物债（粮）").contains(GRAIN);
      assertThat(debt.ratePerMillePerCycle()).as("每周期 20‰").isEqualTo(20);
      assertThat(debt.dueCycle()).as("到期周期 = 当前周期 + 1 = 2").isEqualTo(2L);
      assertThat(debt.id().value())
          .as("★ §7.2：id 由 (周期, 债务人, 债权人, 商品) 确定性算出，且**不含 \".\"**")
          // ★ S1 阶段 1 手算重推（**不是抄实际值**）：格式 = debt-c<周期>-<债务人键>><债权人键>-<商品>；
          //   ★ H0.2：两段键都是 {@code CohortKey.toString()}（三段规范串，带居住维）
          //   ⇒ 债务人 = 3_0|rural|landlord、债权人 = 3_0|rural|rich_peasant；商品 = "grain"。
          .isEqualTo("debt-c1-3_0|rural|landlord>3_0|rural|rich_peasant-grain")
          .doesNotContain(".");
      assertThat(after.debts()).as("整场只此一条债").hasSize(1);
      assertThat(householdGoods(afterBooks, landlordKey, GRAIN)).as("地主借完就归零（它借的是缺口全额）").isZero();
      // ★ 放贷方**自己没被借空**（V6 §7.1①）：富农的缸 = 120 天自需 + 20 万 − 自己第 1 天那一顿 − 借出的 4,166。
      ClassRow richRow = after.classes().get(ruralKey(3, 0, RICH));
      assertThat(householdGoods(afterBooks, richRow.key(), GRAIN))
          .as("★ 富农借出后缸里的逐值余额（保留额那一份没被借走）")
          .isEqualTo(
              EconomyTestWorld.richSurplusOpeningStock(richRow.population())
                  - EconomyVocabulary.dailyRationMilli(richRow.population(), 1L)
                  - EconomyTestWorld.LENDER_HEX_LANDLORD_DEFICIT);
      assertThat(householdGoods(afterBooks, richRow.key(), GRAIN))
          .as("★ 等价说法：缸里仍 ≥ 它本周期**剩下的**自需（借出后它照旧吃得饱）")
          .isGreaterThanOrEqualTo(
              EconomyVocabulary.cumulativeRationMilli(richRow.population(), 120L)
                  - EconomyVocabulary.cumulativeRationMilli(richRow.population(), 1L));

      // (4,0)：地主同样缺，但其余各行恰好吃干 ⇒ 无人可借 ⇒ 一条债都没有。
      assertThat(after.classes().get(ruralKey(4, 0, LANDLORD)).debts())
          .as("无粮可借 ⇒ 不产生债务（也不凭空造粮）")
          .isEmpty();
    }
  }

  // ── (c) 周期收获：第 120 天一次性产粮，progressDays 归零、lastClosedCycle +1 ─────────────

  @Test
  void dayOneHundredTwentyHarvestsOnceAndResetsTheCycle() {
    try (CoreSimos core = freshCore()) {
      advance(core, 119);
      EconomyData beforeHarvest = economy(core);
      ActorData beforeHarvestBooks = actor(core);
      // ★★ V6 §7.1① 起，(0,0) 在第 119 天**不再见底**：放贷方（地主）按"库存 − 本周期自需"留口粮，停贷时留下的
      //    500,000 它自己要吃到周期末 ⇒ 第 119 天还剩"它最后一顿 + 多留的那 166,666"；富农（恰好 120 天）同理还剩
      //    它第 120 天那一顿 12,500。贫农/中农早已见底（第 30 / 61 天起，且借不到粮 —— 见 PLAINS_LENDER_LEFTOVER）。
      assertThat(hexGrain(beforeHarvestBooks, beforeHarvest, 0, 0))
          .as("第 119 天 (0,0)：只剩富农与地主**第 120 天那一顿**，外加地主多留的余量")
          .isEqualTo(PLAINS_LENDER_LEFTOVER + rationOn(50L, 120L) + rationOn(150L, 120L));
      assertThat(farm(beforeHarvest, FARM_0).progressDays()).isEqualTo(119L);
      // ★★ **H5 ③（裁定 C2）改了这两条的数值口径**（断言没动：仍是"每天累加的就是那一条配额 × 119 天"）：
      //   周期第一天按**缺口信号**重排 —— 纺织用得上 50 × 1,000 = 50,000（纤维按产能取足、织机 50 台），
      //   而它原来只有 498,800 × 100‰ = 49,880 ⇒ 缺 120，从农业（用不上那 120）手里吸走
      //   ⇒ 农业 = 448,920 − 120 = **448,800**、纺织 = **50,000**；两者之和仍是 498,800（一份不多、一份不少）。
      assertThat(farm(beforeHarvest, FARM_0).cycleLaborMilli())
          .as(
              "周期劳动累计 = 119 天 × **448,800** 千分劳动"
                  + "（★ R3 的 900‰ = 448,920；★ H5 ③ 把其中 120 给了有缺口的纺织）")
          .isEqualTo(119L * 448_800L);
      assertThat(farm(beforeHarvest, WEAVE_0).cycleLaborMilli())
          .as("家庭纺织那一份：119 天 × 50,000（★ H5 ③：49,880 的配额 + 从农业吸来的 120）")
          .isEqualTo(119L * 50_000L);

      advance(core, 1);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      // 实际投入亩 = min(可用 3,100 亩, 平均劳动 498,800 × LAND_MU_PER_LABOR(7) / 1000 = 3,491 亩)
      //   = 3,100 亩（**土地**是瓶颈）。★ 这里的 7 是 EconomySettlement.LAND_MU_PER_LABOR（亩/千分劳动），
      //   与亩产 67 无关 —— 两者在 v1 都是 7，改标定后只剩前者是 7，别混。
      // 毛产 = 3,100 × 67 粮/亩 × 1000 毫粮/粮；生产损耗（饲料 0‰ + 折旧 30‰）⇒ 净 97%。
      // ★ 收获当天（第 120 天）各行先照常吃自己那一顿：富农那 12,500 吃光、地主还剩 166,666（停贷后多留的余量）。
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 0, 0))
          .as(
              "★ T4 起：库存 = **关系入账 70,929,264** + 周期末尚未吃完的 166,666"
                  + " − ★ H5 ②：缺粮的两行在自己收成里吃上的那一顿 66,667")
          .isEqualTo(
              PLAINS_INTAKE + PLAINS_LENDER_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      // 第 120 天 (0,0) 实际吃掉的口粮：富农（12,500）与地主（4,167）照旧；★ H5 ② 起贫农/中农那 66,667
      // 也从自己的收成里吃上了（改前它们借不到粮 ⇒ 那一顿吃不上）。
      long eatenOnHarvestDay =
          rationOn(150L, 120L) + rationOn(50L, 120L) + PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY;
      assertThat(
              hexGrain(afterHarvestBooks, afterHarvest, 0, 0)
                  - hexGrain(beforeHarvestBooks, beforeHarvest, 0, 0))
          .as("本次推进的库存增量 = 关系入账 − 第 120 天实际吃掉的口粮（富农 + 地主 + ★ H5 起的贫农/中农）")
          .isEqualTo(PLAINS_INTAKE - eatenOnHarvestDay);
      // ★★ **生产损耗的可观测性变了**（如实记）：它**不再进 FlowRow.consumed**，只进 `ProductionLedger.losses`
      //   —— 而 Core 只落 economy/actor 两片的状态，ledger 不进状态树 ⇒ 端到端**读不到**那一笔。
      //   这里的等价说法：`净产 = 毛产 − 损耗`（损耗 = 毛产 × 30‰，由 `productionLossSplitsIntoFeedAndDepreciation`
      //   逐值钉住），而"净产 = 入账 + operator 账上留下的那一份"：
      assertThat(
              sumHarvestIncome(afterHarvest, FARM_0)
                  + accountOf(afterHarvestBooks, afterHarvest, FARM_0, GRAIN))
          .as("★ I4.1 的端到端形态：**净产 = 行侧入账 + operator 账上净增**")
          .isEqualTo(PLAINS_HARVEST_NET);
      assertThat(sumHarvestIncome(afterHarvest, FARM_0))
          .as("行侧所得 = 关系入账（不是毛产分成 —— T4 换的就是这条口径）")
          .isEqualTo(PLAINS_INTAKE);
      assertThat(accountOf(afterHarvestBooks, afterHarvest, FARM_0, GRAIN))
          .as("operator 账上剩的 = 净产 − 实付（R6 的上限之下，付出去的一分不剩给它）")
          .isEqualTo(PLAINS_HARVEST_NET - PLAINS_INTAKE);

      assertThat(farm(afterHarvest, FARM_0).progressDays()).as("progressDays 归零").isZero();
      assertThat(farm(afterHarvest, FARM_0).cycleLaborMilli()).as("周期累计清零").isZero();
      assertThat(afterHarvest.meta().orElseThrow().lastClosedCycle())
          .as("lastClosedCycle +1")
          .hasValue(1L);
    }
  }

  // ── (d) 守恒：粮（记账后）与人口在推进前后守恒 ─────────────────────────────────────────

  @Test
  void grainAndPopulationAreConservedAcrossADailyStepAndAHarvest() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 1);
      assertConserved(beforeBooks, before, actor(core), economy(core), "第 1 天（纯日耗）");

      advance(core, 118);
      EconomyData beforeHarvest = economy(core);
      ActorData beforeHarvestBooks = actor(core);
      advance(core, 1);
      assertConserved(
          beforeHarvestBooks, beforeHarvest, actor(core), economy(core), "第 120 天（日耗 + 收获）");
    }
  }

  // ── (e) 多格独立：人口不同 ⇒ 日耗不同、收获也不同 ─────────────────────────────────────

  @Test
  void adjacentHexesDifferByPopulationAndEachMatchesItsOwnFormula() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 1);
      EconomyData afterOneDay = economy(core);
      ActorData afterOneDayBooks = actor(core);

      long decrease0 = hexDecrease(beforeBooks, before, afterOneDayBooks, afterOneDay, 0, 0);
      long decrease1 = hexDecrease(beforeBooks, before, afterOneDayBooks, afterOneDay, 1, 0);
      assertThat(decrease0).as("(0,0) = Σ行第 1 天口粮").isEqualTo(hexDayNeed(before, 0, 0, 1L));
      assertThat(decrease1).as("(1,0) = Σ行第 1 天口粮").isEqualTo(hexDayNeed(before, 1, 0, 1L));
      assertThat(decrease0).as("两格日耗必须不相等（否则 isEqualTo 可能恒真）").isNotEqualTo(decrease1);

      advance(core, 119);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 0, 0))
          .as(
              "(0,0) 平原格：**关系入账**（给养 71,424 + 地租 62,310,000）+ 放贷方周期末尚未吃完的余量"
                  + " − ★ H5 ②：缺粮行在自己收成里吃上的那一顿")
          .isEqualTo(
              PLAINS_INTAKE + PLAINS_LENDER_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      // ★ 低丘格的绝对值不写死：标定后它的实际投入亩由**劳动瓶颈**（而非土地）决定。
      // ★ 诚实标注这条的**实际**判别力：它只挡"低丘格颗粒无收/两格用了同一条公式"这类粗错
      //   —— 注意它**挡不住**地形系数失效（低丘土地即便按平原算，劳动瓶颈仍给出 1,569 亩 ≠ 平原的 3,100 亩，两格照样不等）。
      //   地形系数本身由**精确值**钉在别处：EconomySeederTest.everyHexKeepsItsLandAndLandFollowsTerrainCoefficient
      //   与 WorldgenInitializeToolTest 的 Σ土地 == 408,127,400 千分亩。
      // ★ (1,0) 低丘格：**劳动是瓶颈，不是土地** ——
      //   可用亩 = 3,100,000 千分亩 × 666‰ ÷ 1000 = 2,064 亩；
      //   但 500 人 × 580‰ = 290,000 千分劳动，投入率按阶层加权 860‰ ⇒ 该池日劳动 249,400 千分劳动；
      //   ★★ **R3 起这一池的日劳动拆成两条配额**（农业 900‰ + 家庭纺织 100‰，见 EconomyTestWorld.farm）：
      //   农业拿 249,400 − 24,940 = **224,460** 千分劳动/日；
      //   可经营 = 224,460 ÷ 每亩需劳动 143 = **1,569 亩** < 2,064 ⇒ 实际投入 1,569 亩。
      //   （★ 旧口径 = floor(249,400 × 7 ÷ 1000) = 1,745 亩；1 × 143 = ⌈1000/7⌉ 的取整差在这里显现出 176 亩 ——
      //     真档不受影响：那里土地才是瓶颈（可经营 51,646 亩 ≫ 3,100 亩）。）
      //   毛产 = 1,569 × 67 × 1000 = 105,123,000；扣生产损耗（饲料 0‰ + 折旧 30‰）⇒ 净 **101,969,310**。
      //   ★ V6 §7.1① 起再加"放贷方周期末尚未吃完的余量"（低丘地主 25 人 ⇒ 83,333，算式见 HILLS_LENDER_LEFTOVER）。
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 1, 0))
          .as("(1,0) 低丘格：关系入账按**劳动瓶颈**算出的毛产推（不是按地）+ 放贷方余量" + " − ★ H5 ②：缺粮行在自己收成里吃上的那一顿")
          .isEqualTo(HILLS_INTAKE + HILLS_LENDER_LEFTOVER - HILLS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 0, 0))
          .as("两格收获必须不相等（地形系数与劳动瓶颈都在起作用）")
          .isNotEqualTo(hexGrain(afterHarvestBooks, afterHarvest, 1, 0));
    }
  }

  // ── (f) §十一 等价性（本轮核心护栏）：一次 N 天 == N 次单日，**硬断言终态逐值相等** ─────────────

  /**
   * ★★ **§十一 等价性护栏**：同一份创世，一份世界 {@code advance(0 → 150)} **一次**，另一份走 **150 次单日** advance ⇒
   * **两份终态逐值相同**（{@link EconomyData} 整个 record 相等：产业进度/周期劳动、队伍库存/货币/债务、 债务表、流水、meta 全在内）。
   *
   * <p>★ 150 天刻意跨过第 **120** 天那个周期末：逐日循环必须**照样在那一天收获 + 分配**（天数是绝对日，不是"推进次数"）。 判别力：若 {@code
   * EconomySettlement} 把一次 N 天压成"只按 to 结算一天"（或逐日循环只跑第一天）⇒ 第 120 天不收获、 逐日消费与债务都不对 ⇒ 本条红。
   */
  @Test
  void oneHundredFiftyDaysInOneCommandEqualsOneHundredFiftyDailySteps() throws Exception {
    Path onceDir = tempDir.resolve("once");
    Path dailyDir = tempDir.resolve("daily");
    java.nio.file.Files.createDirectories(onceDir);
    java.nio.file.Files.createDirectories(dailyDir);

    try (CoreSimos once = freshCoreAt(onceDir);
        CoreSimos daily = freshCoreAt(dailyDir)) {
      long head = once.head(MAIN).orElseThrow().value();
      CommandResult oneShot =
          once.submit(
              new AdvanceTime(
                  "cmd-once",
                  "corr-once",
                  "player:test",
                  MAIN,
                  new RevisionId(head),
                  new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(150)))));
      assertThat(oneShot)
          .as("一次 150 天 ⇒ 恰落一条 revision")
          .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));

      advance(daily, 150); // 另一份：150 次单日（每次用上一条的新 revision）

      EconomyData onceData = economy(once);
      EconomyData dailyData = economy(daily);
      ActorData onceBooks = actor(once);
      ActorData dailyBooks = actor(daily);

      // ★★ H1：**家户账也纳入**这份等价性 —— 家户账是会话副本（{@code EconomyDayStepper.householdGoods()}），
      //   由协调器逐日**落回 actor** ⇒ "一次 N 天 == N 次单日"必须在这本账上也逐值成立（否则副本的落盘有漏日）。
      assertThat(onceBooks)
          .as("§十一：家户账（actor 侧的 GoodsAccount）一次 150 天 == 150 次单日")
          .isEqualTo(dailyBooks);

      // ★★ 唯一证据：两份终态**逐值相同**（不是"都没报错"）。
      assertThat(onceData)
          .as("§十一：advance(0→150) 的终态 == 150 次单日 advance 的终态（覆盖第 120 天收获/逐日消费/债务）")
          .isEqualTo(dailyData);
      // ★ 流水**也纳入**这份终态比较，且非平凡：150 天的发生额逐日累加（不是"两边都只留最后一天"的平凡相等）。
      assertThat(onceData.flows()).as("一次 150 天与 150 次单日的流水逐值相同").isEqualTo(dailyData.flows());
      assertThat(flowConsumed(onceData))
          .as("流水已跨日累加（远大于一天的口粮）")
          .isGreaterThan(10L * rationOn(hexPopulation(onceData, 0, 0), 1L));

      // ★ 再钉一条可读的绝对日数字：150 天只跨过第 120 天一次 ⇒ 关账周期 1、周期进度 30。
      assertThat(onceData.meta().orElseThrow().lastClosedCycle())
          .as("150 天跨过第 120 天那个周期末 ⇒ 关账周期序号 1")
          .hasValue(1L);
      assertThat(farm(onceData, FARM_0).progressDays())
          .as("第 120 天收获后进入第 2 周期，又走 30 天")
          .isEqualTo(30L);
      assertThat(farm(dailyData, FARM_0).progressDays()).isEqualTo(30L);
    }
  }

  // ── (f2) M0.1 ★★ 推进路径一致性：一次 360 天 == 三次 120 天（判据基线 = 一年期）────────────

  /**
   * ★★ <b>M0.1：一次 360 天 == 三次 120 天</b>（一年期的推进路径一致性；本仓的仪器判据，不碰模型）。
   *
   * <p>★★ <b>它与 (f) 那条 150 天的关系</b>：(f) 守的是"一次 N 天 == N 次<b>单日</b>"（150 天刻意跨过第 120 天那个周期末）。
   * 本条守的是**另一条路**：一次 N 天 == 若干次**跨周期的大步**（120 + 120 + 120）—— 真档的一年期读数走的正是这条路 （{@code
   * h6sim_full_*.sh} 按周期分段推进）。两条路都要与"一次算完"逐值相同，否则<b>读数取决于怎么推</b>。
   *
   * <p>★★ <b>判别力</b>：任何"按调用边界维护的跨日状态"（累加器、相位、关账标志、劳动配额的重排时点）只要在段边界上被重置或 重复施加一次，两次的终态就会不同。★
   * 已知的严格更强版本（"逐项一致"而非"不相等就报错"）由 {@code EconomyData} 的 整份 record 相等承担 ——
   * 它含产业进度/周期劳动、家户与经营者库存/货币/债务、债务表、流水、meta；再加上 actor 侧那一整份账。
   *
   * <p>★ <b>刻意不比较的</b>：revision 条数（一次 = 1 条、三次 = 3 条 —— 那是"分几条 revision"的差别，不是结算的差别）。
   */
  @Test
  void threeHundredSixtyDaysInOneCommandEqualsThreeBatchesOfOneHundredTwenty() throws Exception {
    Path onceDir = tempDir.resolve("once360");
    Path batchesDir = tempDir.resolve("batches360");
    java.nio.file.Files.createDirectories(onceDir);
    java.nio.file.Files.createDirectories(batchesDir);

    try (CoreSimos once = freshCoreAt(onceDir);
        CoreSimos batches = freshCoreAt(batchesDir)) {
      advanceRange(once, 0L, 360L); // 一次 360 天（≈ 真档一年期的一条大推进）
      advanceRange(batches, 0L, 120L); // 三次 120 天（= 真档按周期分段的推法）
      advanceRange(batches, 120L, 240L);
      advanceRange(batches, 240L, 360L);

      EconomyData onceData = economy(once);
      EconomyData batchData = economy(batches);
      ActorData onceBooks = actor(once);
      ActorData batchBooks = actor(batches);

      // ★★ 两层终态逐值相等：economy 切片整份 record + actor 侧整份账（家户/经营者/货币/债务都住在里面）。
      assertThat(onceData)
          .as("M0.1：advance(0→360) 的 economy 终态 == 三次 120 天的终态")
          .isEqualTo(batchData);
      assertThat(onceBooks).as("M0.1：advance(0→360) 的 actor 账 == 三次 120 天的账").isEqualTo(batchBooks);
      assertThat(onceData.flows()).as("M0.1：流水逐值相同（不是两边都只留最后一天）").isEqualTo(batchData.flows());

      // ★ 非平凡：一年真的走完了三个周期，且流水跨周期累加过（不是"两边都是空表"）。
      assertThat(onceData.meta().orElseThrow().lastClosedCycle())
          .as("360 天 ⇒ 恰走过 3 个周期末")
          .hasValue(3L);
      assertThat(farm(onceData, FARM_0).progressDays())
          .as("第 4 个周期刚开头（360 − 3×120 = 0 天）")
          .isZero();
      assertThat(flowConsumed(onceData))
          .as("流水已跨 360 天累加（远大于一个周期的口粮）")
          .isGreaterThan(3L * hexRationOver(onceData, 0, 0, 120L));
    }
  }

  // ── (g) 缺口：默认不致命（V4）——缺口读得到、一个人不少；标定后的收获结束青黄不接 ────────────

  /**
   * ★★ **V4 端到端**：(0,0) 1000 人按阶层配储备（= 该格 **65 天**口粮）⇒ 第 66~120 天全缺 ⇒ 第一周期末有**缺口**， 但**默认致死率 0‰ ⇒
   * 一个人都不死**（用户 2026-09-25：「可以先不做什么饿死人系统」）。
   *
   * <p>★★ 同一条用例把 **V5 的 §八.8 读口**也验收掉：读口（{@link ApiViews#economyHex}）的 {@code
   * grainDailyConsumption} == 结算当天写下的自然需求之和；流水的 {@code unmetNeed} 非 0、{@code deaths} == 0。
   *
   * <p>★ 第二周期（第 121~240 天）：第 120 天的收获净产 201,469 粮/格 **远超**第二周期需求 10,000 粮/格（1000 人 × 10,000） ⇒
   * **青黄不接已过**（第二周期缺口 == 0）。标定前（1,000 亩 × 7 粮/亩）收获只够约 4% 的需求 ⇒ 那里这条必红。
   */
  @Test
  void theFirstLeanSeasonLeavesAGapInTheLedgerButKillsNoOne() {
    try (CoreSimos core = freshCore()) {
      EconomyData initial = economy(core);
      ActorData initialBooks = actor(core);
      long need1 = hexNeed(initial, 0, 0);

      advanceRange(core, 0L, 120L); // 一次推进到第 1 个周期末（§十一 等价性由 (f) 单独守）
      EconomyData cycle1 = economy(core);
      ActorData cycle1Books = actor(core);
      // ★ 读口（同一份状态）：缺粮 ⇒ unmetNeed 非 0，deaths == 0（默认不致命）
      Map<String, Object> readout1 = ApiViews.economyHex(new HexCoord(0, 0), cycle1, cycle1Books);

      // ★★ **V6 §7.1① 起缺口 = "需求 − 缸 + 放贷方多留的那部分"**：借不到的粮**不凭空生出来**，它留在放贷方缸里
      //   （周期末还剩 166,666，见 PLAINS_LENDER_LEFTOVER）⇒ 实吃 = 缸 − 余量，缺口比"需求 − 缸"**多**这一笔。
      //   ★ 这正是"只有真缺粮的行缺粮"：富农（恰好 120 天）与地主（250 天）都不再被借空，饿的是贫农与中农。
      assertThat(hexUnmet(cycle1, 0, 0))
          .as("(0,0) 第一周期的缺口 = Σ行需求 − 整缸库存 + 放贷方周期末尚未吃完的余量" + " − ★ H5 ②：缺粮行在关账日自己收成里吃上的那一顿")
          .isEqualTo(
              need1
                  - hexStock(initialBooks, initial, 0, 0)
                  + PLAINS_LENDER_LEFTOVER
                  - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      assertThat(PLAINS_LENDER_LEFTOVER)
          .as("★ 绝对锚：地主 50 人停贷后那 40 天的口粮 = 166,666 毫粮")
          .isEqualTo(166_666L);
      assertThat(hexUnmet(cycle1, 0, 0)).as("缺口确实非 0（「缺粮」这件事被记下来了）").isPositive();
      assertThat(hexDeaths(cycle1, 0, 0)).as("★ 默认 0‰：一个人都不死").isZero();
      assertThat(hexPopulation(cycle1, 0, 0)).as("人口 1000 一个不少").isEqualTo(1000L);
      assertThat(flowUnmet(readout1)).as("★ 读口读到非 0 的 unmetNeed（缺口不是「没在记」）").isPositive();
      assertThat(flowDeaths(readout1)).as("★ 读口读到 deaths == 0（结论，不是静默字段）").isZero();

      assertThat(hexNeed(cycle1, 0, 0))
          .as("第二周期需求 = 人口 × 10,000（人口未变）")
          .isEqualTo(1_000L * EconomyVocabulary.RATION_MILLI_PER_PERSON);
      assertThat(hexNeed(cycle1, 0, 0)).as("人口没少 ⇒ 需求与第一周期相同").isEqualTo(need1);

      advanceRange(core, 120L, 240L); // 到第 2 个周期末
      EconomyData cycle2 = economy(core);
      ActorData cycle2Books = actor(core);

      assertThat(hexUnmet(cycle2, 0, 0)).as("★ 青黄不接已过：第二周期一天不缺").isZero();
      assertThat(hexGrain(cycle2Books, cycle2, 0, 0)).as("缸被收获填满").isPositive();
      assertThat(hexDeaths(cycle2, 0, 0)).as("两个周期都没死人").isZero();
      assertThat(hexPopulation(cycle2, 0, 0)).as("人口始终 1000").isEqualTo(1000L);
    }
  }

  // ── (h) §八.8：读口与结算同源（日耗 == 结算当日实吃）────────────────────────────────────

  /**
   * ★★ **读口的"日耗"就是结算写下的那个数**（v2 spec §八.8 的"一条真相"）：{@code ApiViews.economyHex(coord,
   * data).grainDailyConsumption} == Σ 行 {@code ClassRow.naturalNeeds[grain]} == 结算当天逐行算出的需求之和 ==
   * **该格当天的实际消费**（储备够的格：实吃 == 需求）。
   *
   * <p>★ 判别力：把结算侧"写回 naturalNeeds"那一步删掉 ⇒ 读口会读创世时的旧值（第 1 天的 83,332）⇒ 第 2 天这条红。
   */
  @Test
  void theReadoutDailyConsumptionIsTheSameNumberSettlementUsed() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 1);
      EconomyData day1 = economy(core);
      ActorData day1Books = actor(core);
      advance(core, 1);
      EconomyData day2 = economy(core);
      ActorData day2Books = actor(core);

      Map<String, Object> readout1 = ApiViews.economyHex(new HexCoord(0, 0), day1, day1Books);
      Map<String, Object> readout2 = ApiViews.economyHex(new HexCoord(0, 0), day2, day2Books);
      long readDay1 = ((Number) readout1.get("grainDailyConsumption")).longValue();
      long readDay2 = ((Number) readout2.get("grainDailyConsumption")).longValue();

      assertThat(readDay1).as("第 1 天的读口日耗 == Σ 行当日需求").isEqualTo(hexDayNeed(day1, 0, 0, 1L));
      assertThat(readDay2).as("第 2 天的读口日耗 == Σ 行当日需求").isEqualTo(hexDayNeed(day2, 0, 0, 2L));
      assertThat(readDay2)
          .as("★ 判别力：第 2 天的数必须 ≠ 第 1 天读到的数（否则「天天覆写」与「写一次就不动」分不出来）")
          .isNotEqualTo(readDay1);
      // ★ 读口日耗 == 结算当日实吃：该格储备够（65 天）⇒ 实吃 == 需求 == 库存减少。
      assertThat(hexDecrease(beforeBooks, before, day1Books, day1, 0, 0))
          .as("(0,0) 第 1 天库存减少 == 读口日耗（同源）")
          .isEqualTo(readDay1);
      assertThat(hexDecrease(day1Books, day1, day2Books, day2, 0, 0))
          .as("(0,0) 第 2 天库存减少 == 读口日耗（同源；旧的「人口 × 83」会给 83,000）")
          .isEqualTo(readDay2);
    }
  }

  // ── (i) R3：多商品产出 + 非土地生产（夹具刻意混合：田里同时出粮与纤维、城乡各有一个非土地产业）──────

  /**
   * ★★ **需求口径带商品维度**（R3 的 T1；spec §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）： 结算写进 {@code naturalNeeds}
   * 的是**每商品一条** —— 粮与布各一条，**本轮只做形状**（阈值与死亡作用留 R4）。
   *
   * <pre>
   * naturalNeeds = { grain: dailyRationMilli(人口, day), cloth: dailyClothNeedMilli(人口, day) }
   * </pre>
   *
   * <p>★ 判别力：把 {@code withDailyNeed} 退回"只写粮那一条"（需求还是个标量）⇒ 本条红。
   */
  @Test
  void theDailyNeedsCarryOneEntryPerCommodity() {
    try (CoreSimos core = freshCore()) {
      advance(core, 1);
      EconomyData day1 = economy(core);
      ClassRow row = day1.classes().get(ruralKey(0, 0, PEASANT));

      assertThat(row.naturalNeeds().keySet())
          .as("★ 每商品一条需求，且保序（词表序 = 粮、布）")
          .containsExactly(GRAIN, EconomyTestWorld.CLOTH);
      assertThat(row.naturalNeeds().get(EconomyTestWorld.CLOTH))
          .as("布那一条 = 衣着口径的逐日差分（每人每 365 天 1 匹）")
          .isEqualTo(EconomyVocabulary.dailyClothNeedMilli(row.population(), 1L))
          .isPositive();
      assertThat(row.naturalNeeds().get(GRAIN))
          .as("粮那一条与旧口径逐值相同（R3 只加了维度，没动粮）")
          .isEqualTo(EconomyVocabulary.dailyRationMilli(row.population(), 1L));
    }
  }

  /**
   * ★★ **田里同时出粮与纤维**（R3 的多商品产出）：一次收获后，农业行的**纤维**库存 = 规模 × 每亩纤维 × 1000 × 0.97 ——
   * 与粮走同一条公式、同一组权重，只是商品不同。
   *
   * <pre>
   * (0,0) 平原 3,100 亩 × {@link EconomySeeder#FIBER_OUTPUT_PER_MU}(6) × 1000 = 18,600,000 毫纤维（毛）
   * 扣生产损耗（饲料 0‰ + 折旧 30‰）⇒ 净 18,042,000（落进农村四行的缸）
   * ★★ H0.2 起**创世的 1,500,000 毫纤维也在这四行上**（旧版在"纺织四行"上）⇒ 该格 Σ 行纤维 = 净产 + 织机没取走的
   *    {@link #WEAVE_FIBER_LEFTOVER}(60,000)。要单独量"农田这一周期的纤维净产"，走**流水所得**那一维（下一条）。
   * </pre>
   *
   * <p>★ 判别力：产出键若还写死 {@code GRAIN} ⇒ 纤维一分不产、本条与下一条一起红（"只知道粮"的实现挡在这里）。
   */
  @Test
  void theFieldsYieldFiberAsWellAsGrain() {
    try (CoreSimos core = freshCore()) {
      advance(core, 120);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      long grossFiber =
          PLAINS_HARVEST_GROSS
              / EconomySeeder.GRAIN_OUTPUT_PER_MU
              * EconomySeeder.FIBER_OUTPUT_PER_MU;
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 0, 0, EconomyTestWorld.FIBER))
          .as("(0,0) Σ 行纤维 = 农田净产（毛 18,600,000 × 0.97）+ 织机没取走的那份创世库存")
          .isEqualTo(grossFiber * 970L / 1000L + WEAVE_FIBER_LEFTOVER);
      assertThat(sumHarvestFiber(afterHarvest, FARM_0))
          .as("★ T4：流水所得里纤维那一维 = **关系入账**（= 纤维净产；毛产那份进不了行 —— 它已经不是行的所得了）")
          .isEqualTo(grossFiber * 970L / 1000L);
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 0, 0))
          .as("★ 非平凡：粮与纤维**同时**落进同一批行（两条公式共用一次规模，商品各记各的）")
          .isEqualTo(
              PLAINS_INTAKE + PLAINS_LENDER_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
    }
  }

  /**
   * ★★ **农村家庭纺织：同一批农村人、第二个生产过程**（spec §四 的压力测试；R3 的 T4）。
   *
   * <pre>
   * 农村那一池的日劳动 498,800 拆成农业 900‰ = 448,920 + 纺织 100‰ = 49,880（同一批批次，两条配额）
   * 纺织规模 = min(织机 50, 劳动 49,880 ÷ 1000 = 49, 纤维 1,500,000 ÷ 30,000 = 50) = **49**（劳动最紧）
   * 产布 = 49 × {@link EconomySeeder#CLOTH_PER_LOOM_PER_CYCLE}(30) × 1000 = 1,470,000 毫布（毛）⇒ 净 1,425,900
   * </pre>
   *
   * <p>★★ **判别力（逐条对着一种坏实现）**：
   *
   * <ul>
   *   <li>"只看土地/只看粮"⇒ 纺织那一路的规模算不出来（它的产能是**织机**）⇒ 布恒 0 ⇒ 红；
   *   <li>"规模不取最紧约束"（例如只看织机 ⇒ 50 台）⇒ 布 = 1,500,000 而非 1,425,900 ⇒ 红；
   *   <li>"投入不扣"（纤维凭空用）⇒ 纤维的逐商品守恒式当场不平 ⇒ 下一条红；
   *   <li>"农村批次只发一条配额"（农业拿满 498,800）⇒ 纺织的 cycleLaborMilli 为 0 ⇒ 规模 0 ⇒ 红。
   * </ul>
   */
  @Test
  void theRuralHouseholdWeavesClothOutOfItsOwnFiber() {
    try (CoreSimos core = freshCore()) {
      advance(core, 120);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      assertThat(farm(afterHarvest, WEAVE_0).cycleLaborMilli()).as("关账后周期劳动清零（与农业同处）").isZero();
      long grossCloth =
          WEAVE_LOOMS_OPENED
              * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 0, 0, EconomyTestWorld.CLOTH))
          .as("★★ T4：布落在**织布的人**手里（H0.2 起 = 0_0|rural|* 四行）—— 700‰ 的分成入账 977,758")
          .isEqualTo(WEAVE_CLOTH_INTAKE)
          .isPositive();
      // ★★ H0.2：旧版"weave 四行一行都不持有布"那条判据**没有对象了** —— 那四行不存在（人口恒 0 ⇒ 永不是受方）。
      //   取而代之的是**并账判据**：布落在**同一批农村人**的那一本账上，而**同格的城镇四行一分没有**。
      assertThat(clothOfRowsAt(afterHarvestBooks, afterHarvest, 0, 0, ResidenceKind.RURAL))
          .as("★★ 布的入账落在 (0,0) 的**农村四行**（= 织布的那批人；农业与纺织共用一本账 —— V9/I1.2）")
          .allMatch(value -> value > 0L)
          .hasSize(EconomySeeder.CLASS_IDS.length);
      assertThat(clothOfRowsAt(afterHarvestBooks, afterHarvest, 0, 0, ResidenceKind.URBAN))
          .as(
              "★★ **同一格的城镇四行一行都不持有布**（两组账不并：`(格,居住,阶层)` 那一维就是为此而加）"
                  + "—— 该格没有城镇批次 ⇒ 那四行是空账（这正是不并账的判别力所在）")
          .allMatch(value -> value == 0L)
          .hasSize(EconomySeeder.CLASS_IDS.length);
      assertThat(sumHarvestCommodity(afterHarvest, WEAVE_0, EconomyTestWorld.CLOTH))
          .as("★ 纺织的**关系入账**同样落在农村四行上（行 = 家户，一个家户给两个产业出劳动也只有一个身份）")
          .isEqualTo(WEAVE_CLOTH_INTAKE);
      assertThat(accountOf(afterHarvestBooks, afterHarvest, WEAVE_0, EconomyTestWorld.CLOTH))
          .as("★ 剩下的 300‰ 留在 operator 账上（裁定 E2：'实物分成给劳动者 + 自留'）")
          .isEqualTo(grossCloth * 970L / 1000L - WEAVE_CLOTH_INTAKE);
      assertThat(farm(afterHarvest, FARM_0).cycleInputUsedMilli())
          .as("农业的投入累加器里**没有**纤维（纤维是它的产出，不是投入）")
          .doesNotContainKey(EconomyTestWorld.FIBER);
    }
  }

  /**
   * ★★ **城市作坊：非 LAND 生产成立 + 城市能产出自己的产品**（spec §六 给 T5 定的两个目的）。
   *
   * <pre>
   * (2,0) 300 城市人 ⇒ 作坊 6 座（capacity）（{@link EconomySeeder#URBAN_CAPITA_PER_WORKSHOP} = 50）
   * 规模 = min(作坊 6, 劳动 148,393 ÷ 1000 = 148, 纤维 240,000 ÷ 60,000 = 4, 铁 40,000 ÷ 10,000 = 4) = **4**
   *   （H0.4 起"取到的投入"是逐行⌊⌋之和 ⇒ 4 —— 见 {@link #CRAFT_CLOTH_INTAKE} 的算式；★ 那一路上仍然
   *    **没有一寸土地**：三条约束全与土地无关）
   * 产布 = 4 × 60 × 1000 = 240,000（毛）⇒ 净 232,800；产工具 = 4 × 5 × 1000 = 20,000（毛）⇒ 净 19,400
   * </pre>
   *
   * <p>★ 判别力：把规模写成"按土地算" ⇒ 该格土地为 0（土地全归农业）⇒ 城市永远产不出东西（v1 的病态）⇒ 红。
   */
  @Test
  void theCityWorkshopProducesClothAndToolsWithoutAnyLand() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 120);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      // ★ H0.3（K3）：作坊的产能住在**产业**上（行上不再有生产资料）⇒ "城市生产不含土地"这条判据的落点随之搬家。
      assertThat(farm(afterHarvest, CRAFT_2).capacity())
          .as("作坊的产能**不是土地**（土地全归农业）")
          .doesNotContainKey(AssetKind.LAND);
      assertThat(farm(afterHarvest, IndustryHexKeys.id(EconomySeeder.FARM, 2, 0)).capacity())
          .as("同格的土地全在**农业**产业的产能上（「谁有地」与「谁有作坊」分得开）")
          .containsKey(AssetKind.LAND);
      long grossCloth =
          CRAFT_WORKSHOPS_OPENED
              * EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      long grossTool =
          CRAFT_WORKSHOPS_OPENED
              * EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 2, 0, EconomyTestWorld.CLOTH))
          .as("(2,0) 作坊的布入账 = 600‰ 分成（{@code handicraft}）× 布净产 232,800 ⇒ 逐行取整后 139,678")
          .isEqualTo(CRAFT_CLOTH_INTAKE);
      assertThat(accountOf(afterHarvestBooks, afterHarvest, CRAFT_2, EconomyTestWorld.CLOTH))
          .as("★ 余下 400‰ 留在 operator 账上（布净产 − 分成）")
          .isEqualTo(grossCloth * 970L / 1000L - CRAFT_CLOTH_INTAKE);
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 2, 0, EconomyTestWorld.TOOL))
          .as("★ T4：工具**没有规则付给 cohort** ⇒ 行里一件不进（它落 operator 的账 —— 见下一条）")
          .isZero();
      assertThat(accountOf(afterHarvestBooks, afterHarvest, CRAFT_2, EconomyTestWorld.TOOL))
          .as("(2,0) 作坊的工具净产 = 4 座 × 5 件 × 1000 × 0.97（第二件城市自己的产品，落在经营主体账上）")
          .isEqualTo(grossTool * 970L / 1000L);
      assertThat(hexGoods(beforeBooks, before, 2, 0, EconomyTestWorld.CLOTH))
          .as("★ 非平凡：创世时一件布都没有（上面那个数确实是产出来的）")
          .isZero();
    }
  }

  /**
   * ★★ **逐商品守恒**（§6.1 在 R3 之后的形式）：{@code Δ库存_j == Σ消费_j − Σ所得_j}，**五种商品各成立一次**。
   *
   * <p>★★ 这是"多商品不是装饰"的硬判据：只要结算把某一种商品的产出记进流水、却把它的投入漏在 {@code consumed} 外 （或反过来），那一种商品的等式当场不成立 —— 而
   * v1 的守恒式**只看得见粮**，另外四种怎么错都不会红。
   *
   * <p>★ 窗口 = 创世 → 第 120 天（一个整周期，含两个产业的收获与作坊/织机的现扣投入）。
   */
  @Test
  void everyCommodityIsConservedAcrossAFullCycle() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 120);
      EconomyData after = economy(core);
      ActorData afterBooks = actor(core);

      for (CommodityId commodity :
          List.of(
              GRAIN,
              EconomyTestWorld.FIBER,
              EconomyTestWorld.CLOTH,
              EconomyTestWorld.TOOL,
              EconomyTestWorld.IRON)) {
        assertGoodsConserved(beforeBooks, before, afterBooks, after, commodity, 1L, "第 1 个周期");
      }
      assertThat(flowConsumedOf(after, EconomyTestWorld.FIBER))
          .as("★ 非平凡：这一周期里纤维**真的被消耗了**（织机与作坊的原料），否则上面的等式是恒等式")
          .isPositive();
      assertThat(flowIncomeOf(after, EconomyTestWorld.FIBER)).isPositive();
    }
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  private CoreSimos freshCore() {
    return freshCoreAt(tempDir);
  }

  /**
   * 在 {@code dir} 下起一个装 economy + actor 两片的真 core（两份世界对比时各用各的目录，避免共用同一个 sqlite 文件）。
   *
   * <p>★★ T4/T5：参与者换成 {@link EconomyOwnershipTimeParticipant}（**同时看得见 economy 与 actor**）——
   * 产出自本阶段起离开 {@code ClassRow}，必须由它落到 operator 的账上。{@code EconomyTimeParticipant} 已删除 （它的多日静态入口
   * fail-closed）。
   */
  private static CoreSimos freshCoreAt(Path dir) {
    CoreSimos core =
        new CoreSimos(new CoreConfig(dir, CHECKPOINT_INTERVAL, SimosObjectMapper.create()));
    core.register(new EconomyCodec());
    core.register(new ActorCodec());
    core.register(new EconomyOwnershipTimeParticipant(EconomyTestWorld.MAP_ID));
    // ★ M2 收尾：本类测日结算/收获/借粮，闭式期望一律按"窗口内无市场"手推 ⇒ 用无市场创世
    //   （M2 市场会重新分配同格库存，见 EconomyTestWorld.genesisWithoutMarkets 的类注）。
    core.bootstrapGenesis(EconomyTestWorld.genesisWithoutMarkets());
    return core;
  }

  /** 逐日推进 {@code days} 天（每次一天、用上一次返回的新 revision——日制裁定）。 */
  private static void advance(CoreSimos core, int days) {
    long head = core.head(MAIN).orElseThrow().value();
    long from = core.replay(new StateRef(MAIN, new RevisionId(head))).meta().timestamp().tick();
    for (int i = 0; i < days; i++) {
      CommandResult result =
          core.submit(
              new AdvanceTime(
                  "cmd-advance-" + from,
                  "corr-advance-" + from,
                  "player:test",
                  MAIN,
                  new RevisionId(head),
                  new TimeRange(
                      SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(from + 1)))));
      assertThat(result)
          .as("推进第 %d 天（tick %d → %d）", i + 1, from, from + 1)
          .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
      head++;
      from++;
    }
  }

  /** **一次**推进 {@code (from, to]}（§十一：一条 revision；用于跨整个周期的大步，等价性由 (f) 守）。 */
  private static void advanceRange(CoreSimos core, long from, long to) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-advance-" + from,
                "corr-advance-" + from,
                "player:test",
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进 %d 天（tick %d → %d）", to - from, from, to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
  }

  /** actor 切片（T5 起产权账住在它里面；产出的落点就在这儿）。 */
  private static ActorData actor(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    ActorSnapshot slice =
        (ActorSnapshot)
            state.module("actor").orElseThrow(() -> new AssertionError("状态里没有 actor 切片"));
    return slice.data();
  }

  /**
   * ★★ <b>{@code operator} 的账上某商品的余额</b>（T4/T5 起产出的落点）—— 账户 = {@code (operator, 该产业所在的格)}。
   *
   * <p>★ 键由 {@link GoodsAccountKey} 构造（<b>不自己拼串</b>）；地点取自产业 id（{@code IndustryHexKeys} 是唯一拼写点）。
   */
  private static long accountOf(
      ActorData actor, EconomyData data, IndustryId industry, CommodityId commodity) {
    ActorRef operator = data.industries().get(industry).operator();
    HexCoord hex = HexCoord.parse(IndustryHexKeys.hexKeyOf(industry).orElseThrow());
    GoodsAccount account = actor.accounts().get(new GoodsAccountKey(operator, hex));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /** 某个产业名下各行某商品的库存（逐行）。 */
  /** 某格某一组家户行持有的布（H0.2：行键 = {@code (格, 居住类型, 阶层)} ⇒ "哪一组"由这两维直接点名； ★ 它是"并账判据"的读法：同格两组账必须分得开）。 */
  private static List<Long> clothOfRowsAt(
      ActorData books, EconomyData data, int q, int r, ResidenceKind residence) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .filter(key -> key.residence().equals(residence))
        .map(key -> householdGoods(books, key, EconomyTestWorld.CLOTH))
        .toList();
  }

  /**
   * ★★ <b>某个家户账上某商品的余额</b>（H1：商品库存的唯一真源 = actor 侧的 {@code GoodsAccount}）。
   *
   * <p>★ 账户键由 {@link OwnershipBooks#accountKeyOf} 拼（**本文件不复述家户 id 与账户键的形状**）；账本缺席 ⇒ 0 （该家户还没被播 ——
   * 本文件的创世把"每格两组四行"播全了，故这条兜底只在装配故障时才走到）。
   */
  private static long householdGoods(ActorData books, CohortKey key, CommodityId commodity) {
    GoodsAccount account = books.accounts().get(OwnershipBooks.accountKeyOf(key));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  private static EconomyData economy(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    EconomySnapshot slice =
        (EconomySnapshot)
            state.module("economy").orElseThrow(() -> new AssertionError("状态里没有 economy 切片"));
    return slice.data();
  }

  private static Industry farm(EconomyData data, IndustryId id) {
    Industry industry = data.industries().get(id);
    if (industry == null) {
      throw new AssertionError("没有产业 " + id);
    }
    return industry;
  }

  // ── 逐格汇总（都经 IndustryHexKeys 认"产业属于哪一格"，不自己拼 id）────────────────────

  private static List<ClassRow> rowsAt(EconomyData data, int q, int r) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .map(key -> data.classes().get(key))
        .toList();
  }

  /** 某格的家户行键（H0.2：行挂在**格**上；排序按 (居住类型, 阶层) ⇒ 可复现）。 */
  private static List<CohortKey> classKeysAt(EconomyData data, HexCoord coord) {
    return data.classes().keySet().stream()
        .filter(key -> key.hex().equals(coord))
        .sorted(
            Comparator.comparing((CohortKey key) -> key.residence().value())
                .thenComparing(key -> key.stratum().value()))
        .toList();
  }

  /**
   * ★★ **某产业对应的家户行键**（H0.2：行键里已经没有产业）。
   *
   * <p>★ 事实来源是**劳动配额表**：供给这个产业的批次住哪种居住类型（{@link ResidenceKind#ofLot}，唯一拼写点） ⇒
   * 该格那一组四行。农村家户同时供给农业与家庭纺织 ⇒ 两者**返回同一组四行**（这正是"一个家户一份账"的形态）。
   */
  private static List<CohortKey> classKeysOf(EconomyData data, IndustryId industry) {
    String hexKey = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    LinkedHashSet<ResidenceKind> residences = new LinkedHashSet<>();
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.actor().id().equals(industry.value())) {
        residences.add(ResidenceKind.ofLot(allocation.group()));
      }
    }
    int q = Integer.parseInt(hexKey.substring(0, hexKey.indexOf('_')));
    int r = Integer.parseInt(hexKey.substring(hexKey.indexOf('_') + 1));
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .filter(key -> residences.contains(key.residence()))
        .toList();
  }

  /** 一个农村家户键（{@code (格, RURAL, 阶层)}）—— 夹具的 5 格都是"农村四行 + 城镇四行"。 */
  private static CohortKey ruralKey(int q, int r, SocialClassId stratum) {
    return new CohortKey(new HexCoord(q, r), ResidenceKind.RURAL, stratum);
  }

  /** 某格 Σ 家户粮库存（毫粮）—— H1：库存住在 actor 侧的账本上（{@code books}）。 */
  private static long hexGrain(ActorData books, EconomyData data, int q, int r) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .mapToLong(key -> householdGoods(books, key, GRAIN))
        .sum();
  }

  private static long hexDecrease(
      ActorData beforeBooks,
      EconomyData before,
      ActorData afterBooks,
      EconomyData after,
      int q,
      int r) {
    return hexGrain(beforeBooks, before, q, r) - hexGrain(afterBooks, after, q, r);
  }

  /** 某格 Σ 家户粮库存（毫粮）。 */
  private static long hexStock(ActorData books, EconomyData data, int q, int r) {
    return hexGrain(books, data, q, r);
  }

  /** 某格 Σ 行本周期**粮**的未满足需求（毫粮）—— 本期口径（§八.5：新周期第一天归零）。★ R4 起 unmetNeed 逐商品。 */
  private static long hexUnmet(EconomyData data, int q, int r) {
    long total = 0L;
    for (CohortKey key : classKeysAt(data, new HexCoord(q, r))) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        total += flow.unmetNeed().getOrDefault(GRAIN, 0L);
      }
    }
    return total;
  }

  /** 某格 Σ 行本周期**布**的未满足需求（毫布）。★ R4（T2）：与粮**同一套记账**、但**各自一条**。 */
  private static long hexUnmetCloth(EconomyData data, int q, int r) {
    long total = 0L;
    for (CohortKey key : classKeysAt(data, new HexCoord(q, r))) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        total += flow.unmetNeed().getOrDefault(EconomyTestWorld.CLOTH, 0L);
      }
    }
    return total;
  }

  /** 读口视图（{@link ApiViews#economyHex}）里该格 Σ 行的 {@code unmetNeed}。 */
  private static long flowUnmet(Map<String, Object> hexView) {
    return sumRowFlowCommodity(hexView, "unmetNeed", "grain");
  }

  /** ★ R4：读口视图里该格 Σ 行的 {@code births}（与 {@code deaths} 对称的那一项）。 */
  private static long flowBirths(Map<String, Object> hexView) {
    return sumRowFlows(hexView, "births");
  }

  /** 读口视图里 {@code flow[field][commodity]} 的合计（★ R4 起 {@code unmetNeed} 是逐商品的表）。 */
  private static long sumRowFlowCommodity(
      Map<String, Object> hexView, String field, String commodity) {
    long total = 0L;
    // ★ H0.2：家户行在**格级**（`classes`），不再挂在每个产业对象下。
    for (Object row : (List<?>) hexView.get("classes")) {
      Map<?, ?> flow = (Map<?, ?>) ((Map<?, ?>) row).get("flow");
      Object table = flow.get(field);
      if (table instanceof Map<?, ?> byCommodity) {
        Object value = byCommodity.get(commodity);
        total += value == null ? 0L : ((Number) value).longValue();
      }
    }
    return total;
  }

  /** 读口视图里该格 Σ 行的 {@code deaths}。 */
  private static long flowDeaths(Map<String, Object> hexView) {
    return sumRowFlows(hexView, "deaths");
  }

  /** 读口视图里 {@code classes[].flow[field]} 的合计（★ H0.2：行在格级；读口把 flow 整份挂在每一行上）。 */
  private static long sumRowFlows(Map<String, Object> hexView, String field) {
    long total = 0L;
    for (Object row : (List<?>) hexView.get("classes")) {
      Map<?, ?> flow = (Map<?, ?>) ((Map<?, ?>) row).get("flow");
      total += ((Number) flow.get(field)).longValue();
    }
    return total;
  }

  /** 该格产业的周期天数（H0.2：行不再带产业 ⇒ 相位/周期按**格**取；同格各产业同步走 ⇒ 取最大与旧口径同值）。 */
  private static long cycleDaysAt(EconomyData data, int q, int r) {
    long days = 0L;
    for (IndustryId id : IndustryHexKeys.at(data.industries(), q, r)) {
      days = Math.max(days, data.industries().get(id).cycleDays());
    }
    return days == 0L ? 1L : days;
  }

  /** 某格 Σ 人口。 */
  private static long hexPopulation(EconomyData data, int q, int r) {
    return rowsAt(data, q, r).stream().mapToLong(ClassRow::population).sum();
  }

  /**
   * 某格本周期总需求（毫粮）= Σ 行 {@link EconomyVocabulary#cumulativeRationMilli}(行人口, 该行产业的周期天数)。
   *
   * <p>★ **不许写成"人口 × 一天的量 × 天数"**：日耗是逐日差分，乘不出来；累计函数才是口径的载体。
   */
  private static long hexNeed(EconomyData data, int q, int r) {
    long total = 0L;
    long cycleDays = cycleDaysAt(data, q, r);
    for (CohortKey key : classKeysAt(data, new HexCoord(q, r))) {
      total +=
          EconomyVocabulary.cumulativeRationMilli(data.classes().get(key).population(), cycleDays);
    }
    return total;
  }

  /** 某格 Σ 饿死人口（取各行流水的累计 {@code deaths}）。 */
  private static long hexDeaths(EconomyData data, int q, int r) {
    long total = 0L;
    for (CohortKey key : classKeysAt(data, new HexCoord(q, r))) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        total += flow.deaths();
      }
    }
    return total;
  }

  private static long totalPopulation(EconomyData data) {
    return data.classes().values().stream().mapToLong(ClassRow::population).sum();
  }

  /** 全系统 Σ 家户粮库存（H1：从 actor 侧的账本读；行里已经没有 goods 这一栏）。 */
  private static long totalGrain(ActorData books, EconomyData data) {
    return data.classes().keySet().stream()
        .mapToLong(key -> householdGoods(books, key, GRAIN))
        .sum();
  }

  /**
   * 一整个产业的当日**粮**所得合计（毛产出份额）。
   *
   * <p>★ R3：{@code income} 是**逐商品**的表（田里同时出粮与纤维）⇒ 必须指名粮那一维；把两维相加会得到"粮 + 纤维"的和。
   */
  private static long sumHarvestIncome(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (CohortKey key : classKeysOf(data, industry)) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        sum += flow.income().getOrDefault(GRAIN, 0L);
      }
    }
    return sum;
  }

  /** 一整个产业的当日**纤维**所得合计（R3 的多商品产出：田里同时出粮与纤维）。 */
  private static long sumHarvestFiber(EconomyData data, IndustryId industry) {
    return sumHarvestCommodity(data, industry, EconomyTestWorld.FIBER);
  }

  /** 一整个产业的当日**某商品**所得合计（R3：产出逐商品，故读数也必须逐商品）。 */
  private static long sumHarvestCommodity(
      EconomyData data, IndustryId industry, CommodityId commodity) {
    long sum = 0L;
    for (CohortKey key : classKeysOf(data, industry)) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        sum += flow.income().getOrDefault(commodity, 0L);
      }
    }
    return sum;
  }

  /**
   * 一整个产业当日记入流水的生产消耗合计 = Σ流水所得 − 库存增量 − **本次推进内实际吃掉的口粮**。
   *
   * <p>★ 用一个**独立可算**的差来反推消耗（损耗率只在断言那一侧出现一次），而不是把生产消耗率再抄一遍。
   *
   * <p>★★ **V6 §7.1① 起这两项都得改**：①"收获后库存 == 净产"不再成立（放贷方周期末还剩 {@link #PLAINS_LENDER_LEFTOVER} ⇒ 差要取
   * {@code 收获后 − 收获前}）；②收获日当天**真的有人吃得上饭**了（富农与地主各留了一顿 166,667）⇒ 不减掉那一笔的话，反推出来的"损耗"会虚高恰好等于它。
   *
   * <p>★ 它只对"本次推进内除日耗与本次收获外没有别的发生额"的产业成立（该夹具的 (0,0) 正是如此：第 120 天**无借贷** —— 地主的可贷额已为 0）。
   *
   * @param eatenOnThatDay 该产业各行**本次推进内实际吃掉的口粮合计**（毫粮）
   */
  private static long harvestProductionLoss(
      ActorData beforeBooks,
      EconomyData before,
      ActorData afterBooks,
      EconomyData after,
      IndustryId industry,
      long eatenOnThatDay) {
    long stockBefore = 0L;
    long stockAfter = 0L;
    for (CohortKey key : classKeysOf(after, industry)) {
      stockBefore += householdGoods(beforeBooks, key, GRAIN);
      stockAfter += householdGoods(afterBooks, key, GRAIN);
    }
    return sumHarvestIncome(after, industry) - (stockAfter - stockBefore) - eatenOnThatDay;
  }

  /**
   * 守恒（§6.1 的账要平）：粮库存的减少 == **本次推进**的流水消费 − 本次推进的流水所得；**人口**则满足 §六 的逐格守恒 ——{@code Σ人口变化 ==
   * −Σ死亡}（2026-09-25 起：周期末的饿死会让总人口下降，这条取代旧的"人口逐值不变"）。
   *
   * <p>★ 流水**跨日/跨推进累加**（§十一，{@code FlowRow} 是"本期累计发生额"）⇒ 本次推进的发生额取 {@code after.flows() −
   * before.flows()} 的差，而不是 {@code after.flows()} 本身（否则 119 天的累计会把第 120 天的一天账淹没）。
   *
   * <p>★ **人口那一条只对"窗口不跨周期末"成立**（§八.5 起 {@code deaths} 是**本期**口径：新周期第一天归零）： 本文件的两处调用（第 1 天、第 119→120
   * 天）都落在同一个周期内，故差值法成立。
   */
  private static void assertConserved(
      ActorData beforeBooks,
      EconomyData before,
      ActorData afterBooks,
      EconomyData after,
      String what) {
    long consumed = flowConsumed(after) - flowConsumed(before);
    long income = flowIncome(after) - flowIncome(before);
    assertThat(totalGrain(beforeBooks, before) - totalGrain(afterBooks, after))
        .as("%s：库存减少 == 本期流水消费 − 本期流水所得（损耗/产出都显式落账）", what)
        .isEqualTo(consumed - income);
    long deaths = flowDeaths(after) - flowDeaths(before);
    assertThat(totalPopulation(after))
        .as("%s：Σ人口变化 == −Σ死亡（逐格守恒；未死则人口不变）", what)
        .isEqualTo(totalPopulation(before) - deaths);
  }

  /** Σ 各行的粮消费（毫粮）。 */
  private static long flowConsumed(EconomyData data) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(GRAIN, 0L))
        .sum();
  }

  /** Σ 各行的饿死人口（人）。 */
  private static long flowDeaths(EconomyData data) {
    return data.flows().values().stream().mapToLong(FlowRow::deaths).sum();
  }

  /** Σ 各行的流水**粮**所得（★ R3：income 逐商品 ⇒ 守恒式也逐商品，本文件量的是粮那一维）。 */
  private static long flowIncome(EconomyData data) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.income().getOrDefault(GRAIN, 0L))
        .sum();
  }

  /** Σ 各行的流水**某商品**所得。 */
  private static long flowIncomeOf(EconomyData data, CommodityId commodity) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.income().getOrDefault(commodity, 0L))
        .sum();
  }

  /** Σ 各行的流水**某商品**消费。 */
  private static long flowConsumedOf(EconomyData data, CommodityId commodity) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(commodity, 0L))
        .sum();
  }

  /**
   * 某商品的**逐商品守恒**（§6.1 在 R3 之后的形式）：{@code Σ(推进前库存) − Σ(推进后库存) == Σ流水消费 − Σ流水所得} —— **每一种商品各成立一次**。
   *
   * <p>★★ 判别力：只要结算把某一种商品的产出记进了 {@code income} 而把它的投入漏在 {@code consumed} 外（或反过来）， 那条商品的等式当场不成立。★
   * 只在**这个窗口内除日耗/收获外没有别的发生额**的商品上成立（本文件的窗口正是如此）。
   *
   * @param eatenOnThatDay 该商品在本次推进内**实际吃掉/消耗掉**的量（毫单位）
   */
  private static void assertGoodsConserved(
      ActorData beforeBooks,
      EconomyData before,
      ActorData afterBooks,
      EconomyData after,
      CommodityId commodity,
      long eatenOnThatDay,
      String what) {
    long beforeStock = goodsTotal(beforeBooks, before, commodity);
    long afterStock = goodsTotal(afterBooks, after, commodity);
    long consumed = flowConsumedOf(after, commodity) - flowConsumedOf(before, commodity);
    long income = flowIncomeOf(after, commodity) - flowIncomeOf(before, commodity);
    assertThat(beforeStock - afterStock)
        .as("%s：%s 的库存减少 == 本期流水消费 − 本期流水所得", what, commodity.value())
        .isEqualTo(consumed - income);
    assertThat(eatenOnThatDay).as("%s：本次推进内该商品的显式消耗（毫单位）", what).isPositive();
  }

  /** Σ 各**家户**的某商品库存（H1：从 actor 侧的账本读）。 */
  private static long goodsTotal(ActorData books, EconomyData data, CommodityId commodity) {
    return data.classes().keySet().stream()
        .mapToLong(key -> householdGoods(books, key, commodity))
        .sum();
  }

  /** 某格 **Σ 家户某商品库存**（该格两组四行都算进来）。 */
  private static long hexGoods(
      ActorData books, EconomyData data, int q, int r, CommodityId commodity) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .mapToLong(key -> householdGoods(books, key, commodity))
        .sum();
  }

  /** 某产业对应的**那组家户行**（H0.2：行不再属于产业 —— 由劳动配额把"批次 → 居住类型"对上，见 {@link #classKeysOf}）。 */
  private static List<ClassRow> rowsOf(EconomyData data, IndustryId industry) {
    return classKeysOf(data, industry).stream().map(key -> data.classes().get(key)).toList();
  }
}
