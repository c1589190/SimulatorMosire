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
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
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
 * ★★ **日结算 + 周期收获与分配**的端到端验收：真 {@link EconomyCodec} + 真 {@link
 * io.mosire.simos.app.time.EconomyOwnershipTimeParticipant}（economy × actor 的会合点）+ 真 store + {@link
 * EconomyTestWorld}（5 格），全部经 {@code CoreSimos.submit(AdvanceTime)} 逐日推进。断言**逐值**。
 *
 * <p>★ 判据（用户点名 a~g）：日耗 / 缺口与 **E4b 信用容量** / 周期收获 / 守恒 / 多格独立 / 等价性 / **饿死惩罚**。
 *
 * <p>★★ **S1/E4 后的键与真值位置**：{@code classes}/{@code flows} 的键是 {@link HouseholdId}（不是 {@code
 * CohortKey}）， 行视图住在 {@link ClassRow#view()}；进度/周期劳动/实扣投入住在 {@link ProductionUnit}，实物产能住在 {@link
 * AssetShare} 总账。
 *
 * <p>★ 夹具账面（{@link EconomyTestWorld}）：(0,0) 平原 1000 人 3,100 亩 / (1,0) 低丘 500 人 2,064 亩 / (2,0) 只有城市
 * 300 人 / (3,0) 平原 1000 人（同格富农有真余粮）/ (4,0) 平原 1000 人（无人有余粮）。★ E4b 起"有粮可借"不再等于"借得到" ——(3,0) 也借不到，见
 * (b)。
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
   * ★★ <b>(0,0) 地主缸里**没贷出去**的自留余量</b>（毫粮；E4b 起"地主是放贷方"这条旧叙述作废，但自留额仍逐值可推）。
   *
   * <pre>
   * 四行（NORMAL 储备）：贫农 450 人（30 天）、中农 350 人（60 天）、富农 150 人（120 天）、地主 50 人（250 天）
   * 地主开缸 = cumulativeRationMilli(50, 250) = 1,041,666
   * 地主本周期自需 = cumulativeRationMilli(50, 120) = 500,000
   * ⇒ 周期末缸里剩下的自留额 = 1,041,666 − 500,000 = **541,666**
   * （贫农第 31 天、中农第 61 天见底，但 E4b 下借方的 headroom = 0 ⇒ 没有一笔借贷转出。）
   * </pre>
   *
   * <p>★ 判别力：若 E4b 的 headroom 闸门被拿掉（退回"有粮就贷"），地主会贷出 375,000、周期末只剩 {@code cumulativeRationMilli(50,
   * 40) = 166,666} ⇒ 用到本常量的 (c)/(e)/(g) 三条一起红。
   */
  private static final long PLAINS_LANDLORD_LEFTOVER =
      EconomyVocabulary.cumulativeRationMilli(50L, 250L)
          - EconomyVocabulary.cumulativeRationMilli(50L, 120L);

  /**
   * ★★ <b>H5 ②：关账日"缺粮行"在<strong>自己的收成</strong>里吃上的那一顿</b>（毫粮）。
   *
   * <pre>
   * (0,0) 平原 1000 人 ⇒ 贫农 450 / 中农 350 / 富农 150 / 地主 50
   * 缺粮的是**贫农与中农**（贫农第 30 天见底、中农第 61 天见底；E4b 下借不到粮）
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
   * 逐值：{@code dailyRationMilli(225,120) + dailyRationMilli(175,120) = 18,750 + 14,584 = 33,334}。
   */
  private static final long HILLS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY =
      rationOn(225L, 120L) + rationOn(175L, 120L);

  /**
   * ★★ <b>T4/T5 起：产出不再进阶层行，行里收到的是**关系入账**</b>—— 本常量算的正是那些入账。
   *
   * <pre>
   * 本格农业（feudal）的两族规则（{@link RegimeRelations} 的出厂值；逐条见 T3 的公式表）：
   *   给养 FIXED_IN_KIND_PER_LABOR × LABOR_AMOUNT：每 1000 千分劳动 144 毫粮，**每个阶层 cohort 一条**
   *   地租 OUTPUT_SHARE × GROSS_OUTPUT 300‰（粮）→ landlord cohort
   *   副产 OUTPUT_SHARE × NET_AFTER_INPUTS 1000‰（**纤维**）→ 四个阶层 cohort
   *
   * (0,0) 平原 1000 人 ⇒ 450/350/150/50；行劳动 = 人口 × 580‰ × 槽位投入率：
   *   贫 247,950 / 中 182,700 / 富 65,250 / 地 2,900（Σ = 498,800）
   *   ★★ **给养量的是"本周期"的劳动**（行 labor 是**每日**口径 ⇒ × cycleDays 120）：
   *     贫 29,754,000 · 中 21,924,000 · 富 7,830,000 · 地 348,000
   *     给养 = 29,754×144 + 21,924×144 + 7,830×144 + 348×144
   *          = 4,284,576 + 3,157,056 + 1,127,520 + 50,112 = **8,619,264**
   *   地租 = ⌊207,700,000 × 300 ÷ 1000⌋ = 62,310,000     （毛产 = 3,100 亩 × 67 粮/亩 × 1000）
   *   ⇒ **粮入账 = 8,619,264 + 62,310,000 = 70,929,264**（≤ 净产 201,469,000 ⇒ R6 的付款上限不咬合）
   *   ⇒ 纤维入账 = 净产 18,042,000（1000‰ × 四条 ∧ 上限逐条咬合 ⇒ Σ == 净产）
   * </pre>
   */
  private static final long PLAINS_INTAKE = 8_619_264L + 62_310_000L;

  /**
   * ★★ <b>(1,0) 低丘格的粮入账</b>（同 {@link #PLAINS_INTAKE} 的算式，人口 500 人 ⇒ 225/175/75/25）：
   *
   * <pre>
   * 行劳动 = 225×580×950‰ = 123,975 / 175×580×900‰ = 91,350 / 75×580×750‰ = 32,625 / 25×580×100‰ = 1,450
   * 本周期劳动（×120）= 14,877,000 / 10,962,000 / 3,915,000 / 174,000
   * 给养 = 14,877×144 + 10,962×144 + 3,915×144 + 174×144
   *      = 2,142,288 + 1,578,528 + 563,760 + 25,056 = **4,309,632**
   * 地租 = ⌊105,123,000 × 300 ÷ 1000⌋ = 31,536,900   （毛产 = 1,569 亩 × 67 × 1000；亩数由**劳动**瓶颈决定）
   * ⇒ 粮入账 = 4,309,632 + 31,536,900 = **35,846,532**
   * </pre>
   */
  private static final long HILLS_INTAKE = 4_309_632L + 31_536_900L;

  /**
   * ★★ <b>(2,0) 城市作坊本周期实际开动的座数与布入账</b>。
   *
   * <pre>
   * 作坊总数（= 实物产能）= 城市人口 300 ÷ URBAN_CAPITA_PER_WORKSHOP(50) = **6**
   * 实际开动 = **6**：纤维/铁按各自家户分到的作坊数取足、工具由经营主体开缸持有 ⇒ 规模三路都与产能对齐
   * ⇒ 布净产 = 6 座 × 60 匹 × 1000 × 0.97 = 349,200；600‰ 分成 = ⌊349,200 × 600 ÷ 1000⌋ = 209,520
   *   逐行按城市四行的劳动切、各自取整 ⇒ 贫 104,150 · 中 76,742 · 富 27,408 · 地 1,218
   * ⇒ 布入账 = **209,518**（逐行取整少 2）
   * </pre>
   *
   * <p>★ 本常量是**当前口径**的逐值锚（旧 H0.4 口径曾把它压到 139,678；现行投入口径与产能对齐后回到 209,518， 这是口径变化的结果，**不是**放宽断言）。
   */
  private static final long CRAFT_CLOTH_INTAKE = 209_518L;

  /** 本周期实际开动的作坊（见 {@link #CRAFT_CLOTH_INTAKE} 的算式）；创世时的作坊总数与它同值（实物总账 = 6）。 */
  private static final long CRAFT_WORKSHOPS_OPENED = 6L;

  /** ★★ (0,0) 创世时的织机总数 = 该格农村人口 1,000 ÷ RURAL_CAPITA_PER_LOOM(20) = **50**（= 实物产能）。 */
  private static final long WEAVE_LOOMS_SEEDED = 1_000L / EconomySeeder.RURAL_CAPITA_PER_LOOM;

  /**
   * 本周期实际开动的织机（见 {@link #WEAVE_LOOMS_SEEDED} 的算式）。
   *
   * <p>★★ <b>H5 ③ 起是 50</b>：缺口信号把农业用不上的 120 千分劳动给了有缺口的纺织 ⇒ 纺织配额 = 50,000， 与产能（50 台）、纤维（按产能 50 ×
   * 30,000 = 1,500,000 取足）三路对齐 ⇒ 规模 = 50（改前劳动只有 49,880 ⇒ 49 台）。
   */
  private static final long WEAVE_LOOMS_OPENED = 50L;

  /**
   * 织机**没取走**的那份创世纤维（毫纤维）：投入调拨按产能折出的规模一次取足 （{@code drawCycleInputs} 的 need = 规模 × 每单位用量）⇒ 50
   * 台那份正好取光，恒为 **0**。
   */
  private static final long WEAVE_FIBER_LEFTOVER = 0L;

  /**
   * ★★ <b>(0,0) 家庭纺织的布入账</b>（{@code household}：{@code OUTPUT_SHARE × LABOR_AMOUNT} 700‰，布）。
   *
   * <p>★★ <b>受方是"织布的人"</b>：行 = 家户，而那批人就是 {@code 0_0|rural|*} 四行。故逐行的分母是**农村四行的劳动**（Σ = 498,800）。
   *
   * <pre>
   * 布净产 = 50 台 × 30 匹 × 1000 × 0.97 = 1,455,000；分成那一笔 = ⌊1,455,000 × 700 ÷ 1000⌋ = 1,018,500
   *   四行按各自劳动（247,950 / 182,700 / 65,250 / 2,900，Σ = 498,800）逐行向下取整：
   *     贫 506,289 · 中 373,055 · 富 133,234 · 地 5,921
   * ⇒ 布入账 = **1,018,499**（逐行取整少 1）
   * </pre>
   *
   * ★ 口径为什么变：见 {@link #WEAVE_LOOMS_OPENED}（劳动配额经 H5 ③ 的缺口信号从 49,880 → 50,000）。
   */
  private static final long WEAVE_CLOTH_INTAKE = 1_018_499L;

  /**
   * ★★ <b>(1,0) 地主缸里没贷出去的自留余量</b>（毫粮；同 {@link #PLAINS_LANDLORD_LEFTOVER} 的算式， E4b 下同样没有一笔借贷）。
   *
   * <pre>
   * 地主开缸 = cumulativeRationMilli(25, 250) = 520,833
   * 地主本周期自需 = cumulativeRationMilli(25, 120) = 250,000
   * ⇒ 周期末自留额 = 520,833 − 250,000 = **270,833**
   * </pre>
   */
  private static final long HILLS_LANDLORD_LEFTOVER =
      EconomyVocabulary.cumulativeRationMilli(25L, 250L)
          - EconomyVocabulary.cumulativeRationMilli(25L, 120L);

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
          .as("(3,0) 地主缺口**没有**被富农补上：E4b 信用容量 = 0 ⇒ 有粮也不贷（缺口行只吃自己那 950 人的份）")
          .isEqualTo(hexDayNeed(before, 3, 0, 1L) - rationOn(50L, 1L));
      assertThat(hexDecrease(beforeBooks, before, afterBooks, after, 4, 0))
          .as("(4,0) 无粮可借 ⇒ 地主那 50 人的口粮吃不到（未满足的部分不凭空造粮）")
          .isEqualTo(hexDayNeed(before, 4, 0, 1L) - rationOn(50L, 1L));
      assertThat(hexDayNeed(before, 3, 0, 1L) - rationOn(50L, 1L))
          .as("★ 绝对锚：(3,0)/(4,0) 都只能吃掉非地主那 950 人的第 1 天口粮（地主 50 人 = 4,166 全缺）")
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

  // ── (b) 缺口：借粮须过 **E4b 信用容量**；容量为 0 时，有粮的邻居也不借 ────────────────────

  /**
   * ★★ <b>E4b 起"有粮可借"不再是借得到的充分条件</b>：可借额 = {@code min(剩余缺口, 放贷方余粮, DebtCapacity.headroom −
   * 本周期已借)}，而
   *
   * <pre>
   * headroom = max(0, ⌊κ × F ÷ 1000⌋ + 可自用余粮 + 政策 − 既有粮债本金)
   * F        = max(0, 本周期已实现粮所得 − 本周期累计口粮 − 下一轮必要投入 − 实缴税)
   * </pre>
   *
   * <p>★ 第 1 天（关账日之前）没有已实现所得、借方自己也没有余粮 ⇒ <b>headroom = 0</b>：即便同格富农有**真余粮**， 也不放贷 ——
   * 这不是"没粮可借"，是"借方的偿付基础为零"（E4b 明文：三项都没有 ⇒ 不借，不凭未来推断发信用卡）。 缺口如实留在 {@code unmetNeed}
   * 里，粮**没有**从富农缸里消失。
   *
   * <p>★ 判别力：把 E4b 的 headroom 拿掉（退回"有粮就贷"）⇒ 本条的 {@code debtContracts} 空、富农缸里的余额 逐值断言同时红。★
   * <b>不许</b>为了复现旧 "一条债" 的读数往夹具里注入所得/自有粮：那正是 E4b 要挡的凭未来信用。
   */
  @Test
  void deficitsCreateNoDebtWhenE4bCreditCapacityIsZero() {
    try (CoreSimos core = freshCore()) {
      advance(core, 1);
      EconomyData after = economy(core);
      ActorData afterBooks = actor(core);

      // (3,0)：地主（50 人）缺第 1 天口粮 4,166（= dailyRationMilli(50, 1)），富农有真余粮（120 天自需 + 20 万）。
      //   ★ S1：行键 = HouseholdId，视图（格/居住/阶层）住在 ClassRow.view()；这里按夹具视图点名。
      ClassRow landlord = rowOf(after, ruralKey(3, 0, LANDLORD));
      assertThat(landlord.debts()).as("E4b headroom = 0 ⇒ 地主不背债").isEmpty();
      Map<DebtContractId, DebtContract> debts = after.debtContracts();
      assertThat(debts).as("整场没有一条债务合同（E4b 的表为空）").isEmpty();
      assertThat(householdGoods(afterBooks, after, landlord.id(), GRAIN))
          .as("地主缸里本来就是 0；没借到，也没凭空造粮")
          .isZero();
      assertThat(after.flows().get(landlord.id()).unmetNeed().getOrDefault(GRAIN, 0L))
          .as("缺口如实记入 unmetNeed（不是静默消失）")
          .isEqualTo(EconomyTestWorld.LENDER_HEX_LANDLORD_DEFICIT);

      // 放贷方有真余粮，但借方过不了信用容量 ⇒ 余粮一分没动：缸 = 开缸 − 自己第 1 天那一顿。
      ClassRow rich = rowOf(after, ruralKey(3, 0, RICH));
      assertThat(householdGoods(afterBooks, after, rich.id(), GRAIN))
          .as("★ E4b：富农的余粮没有被借走（旧断言下的 '− 借出 4,166' 不再成立）")
          .isEqualTo(
              EconomyTestWorld.richSurplusOpeningStock(rich.population())
                  - EconomyVocabulary.dailyRationMilli(rich.population(), 1L));
      assertThat(householdGoods(afterBooks, after, rich.id(), GRAIN))
          .as("★ 等价说法：缸里仍 ≥ 它本周期**剩下的**自需（一分没贷出去）")
          .isGreaterThanOrEqualTo(
              EconomyVocabulary.cumulativeRationMilli(rich.population(), 120L)
                  - EconomyVocabulary.cumulativeRationMilli(rich.population(), 1L));

      // (4,0)：地主同样缺，但其余各行恰好吃干 ⇒ 无人可借；E4b 容量也同样是 0 ⇒ 一条债都没有。
      ClassRow noLenderLandlord = rowOf(after, ruralKey(4, 0, LANDLORD));
      assertThat(noLenderLandlord.debts()).as("无粮可借 ⇒ 不产生债务（也不凭空造粮）").isEmpty();
      assertThat(after.flows().get(noLenderLandlord.id()).unmetNeed().getOrDefault(GRAIN, 0L))
          .as("(4,0) 地主的缺口同样如实进 unmetNeed")
          .isEqualTo(EconomyTestWorld.LENDER_HEX_LANDLORD_DEFICIT);
    }
  }

  // ── (c) 周期收获：第 120 天一次性产粮，progressDays 归零、lastClosedCycle +1 ─────────────

  @Test
  void dayOneHundredTwentyHarvestsOnceAndResetsTheCycle() {
    try (CoreSimos core = freshCore()) {
      advance(core, 119);
      EconomyData beforeHarvest = economy(core);
      ActorData beforeHarvestBooks = actor(core);
      // ★★ (0,0) 第 119 天：地主 250 天开缸还没吃完，剩"第 120 天那一顿 + 没贷出去的自留额 541,666"；
      //    富农（恰好 120 天储备）只剩它第 120 天那一顿 12,500。贫农/中农第 30 / 61 天起见底，且 E4b 下借不到粮
      //    （headroom = 0 —— 见 PLAINS_LANDLORD_LEFTOVER 与用例 (b)）。
      assertThat(hexGrain(beforeHarvestBooks, beforeHarvest, 0, 0))
          .as("第 119 天 (0,0)：只剩富农与地主**第 120 天那一顿**，外加地主没贷出去的自留余量")
          .isEqualTo(PLAINS_LANDLORD_LEFTOVER + rationOn(50L, 120L) + rationOn(150L, 120L));
      assertThat(unitOf(beforeHarvest, FARM_0).progressDays()).isEqualTo(119L);
      // ★★ **H5 ③（裁定 C2）改了这两条的数值口径**（断言没动：仍是"每天累加的就是那一条配额 × 119 天"）：
      //   周期第一天按**缺口信号**重排 —— 纺织用得上 50 × 1,000 = 50,000（纤维按产能取足、织机 50 台），
      //   而它原来只有 498,800 × 100‰ = 49,880 ⇒ 缺 120，从农业（用不上那 120）手里吸走
      //   ⇒ 农业 = 448,920 − 120 = **448,800**、纺织 = **50,000**；两者之和仍是 498,800（一份不多、一份不少）。
      assertThat(unitOf(beforeHarvest, FARM_0).cycleLaborMilli())
          .as(
              "周期劳动累计 = 119 天 × **448,800** 千分劳动"
                  + "（★ R3 的 900‰ = 448,920；★ H5 ③ 把其中 120 给了有缺口的纺织）")
          .isEqualTo(119L * 448_800L);
      assertThat(unitOf(beforeHarvest, WEAVE_0).cycleLaborMilli())
          .as("家庭纺织那一份：119 天 × 50,000（★ H5 ③：49,880 的配额 + 从农业吸来的 120）")
          .isEqualTo(119L * 50_000L);

      advance(core, 1);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      // 实际投入亩 = min(可用 3,100 亩, 平均劳动 498,800 × LAND_MU_PER_LABOR(7) / 1000 = 3,491 亩)
      //   = 3,100 亩（**土地**是瓶颈）。★ 这里的 7 是 EconomySettlement.LAND_MU_PER_LABOR（亩/千分劳动），
      //   与亩产 67 无关 —— 两者在 v1 都是 7，改标定后只剩前者是 7，别混。
      // 毛产 = 3,100 × 67 粮/亩 × 1000 毫粮/粮；生产损耗（饲料 0‰ + 折旧 30‰）⇒ 净 97%。
      // ★ 收获当天（第 120 天）各行先照常吃自己那一顿：富农那 12,500 吃光、地主还剩 541,666（没贷出去的自留余量）。
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 0, 0))
          .as(
              "★ T4 起：库存 = **关系入账 70,929,264** + 周期末尚未吃完的地主自留额 541,666"
                  + " − ★ H5 ②：缺粮的两行在自己收成里吃上的那一顿 66,667")
          .isEqualTo(
              PLAINS_INTAKE + PLAINS_LANDLORD_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      // 第 120 天 (0,0) 实际吃掉的口粮：富农（12,500）与地主（4,167）照旧；★ H5 ② 起贫农/中农那 66,667
      // 也从自己的收成里吃上了（旧口径下它们借不到粮 ⇒ 那一顿吃不上）。
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

      assertThat(unitOf(afterHarvest, FARM_0).progressDays()).as("progressDays 归零").isZero();
      assertThat(unitOf(afterHarvest, FARM_0).cycleLaborMilli()).as("周期累计清零").isZero();
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
              "(0,0) 平原格：**关系入账**（给养 8,619,264 + 地租 62,310,000）+ 地主周期末未贷出的自留额"
                  + " − ★ H5 ②：缺粮行在自己收成里吃上的那一顿")
          .isEqualTo(
              PLAINS_INTAKE + PLAINS_LANDLORD_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
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
      //   ★ E4b 起没有借贷 ⇒ 低丘地主同样留下"250 天开缸 − 120 天自需"= 270,833（见 HILLS_LANDLORD_LEFTOVER）。
      assertThat(hexGrain(afterHarvestBooks, afterHarvest, 1, 0))
          .as("(1,0) 低丘格：关系入账按**劳动瓶颈**算出的毛产推（不是按地）+ 地主自留额" + " − ★ H5 ②：缺粮行在自己收成里吃上的那一顿")
          .isEqualTo(
              HILLS_INTAKE + HILLS_LANDLORD_LEFTOVER - HILLS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
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
      assertThat(unitOf(onceData, FARM_0).progressDays())
          .as("第 120 天收获后进入第 2 周期，又走 30 天")
          .isEqualTo(30L);
      assertThat(unitOf(dailyData, FARM_0).progressDays()).isEqualTo(30L);
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
      assertThat(unitOf(onceData, FARM_0).progressDays())
          .as("第 4 个周期刚开头（360 − 3×120 = 0 天）")
          .isZero();
      assertThat(flowConsumed(onceData))
          .as("流水已跨 360 天累加（远大于一个周期的口粮）")
          .isGreaterThan(3L * hexRationOver(onceData, 0, 0, 120L));
    }
  }

  // ── (g) 缺口：默认不致命（V4）——缺口读得到、一个人不少；E4b 无信用容量 ⇒ 下个周期仍有缺口 ─────────

  /**
   * ★★ <b>V4 端到端</b>：(0,0) 1000 人按阶层配储备（贫 30 / 中 60 / 富 120 / 地 250 天）⇒ 贫农第 31 天、中农第 61 天见底；<b>E4b
   * 起它们借不到粮</b>（借方的 F 与可自用余粮都是 0 ⇒ headroom = 0）⇒ 第一周期末有**缺口**， 但**默认致死率 0‰ ⇒ 一个人都不死** （用户
   * 2026-09-25：「可以先不做什么饿死人系统」）。
   *
   * <p>★★ 同一条用例把 **V5 的 §八.8 读口**也验收掉：读口（{@link ApiViews#economyHex}）的 {@code
   * grainDailyConsumption} == 结算当天写下的自然需求之和；流水的 {@code unmetNeed} 非 0、{@code deaths} == 0。
   *
   * <p>★★ <b>E4b 改变了"第二个周期"的结论</b>（如实记）：穷行每周期真正能支配的只有**上周期末缸里剩下的**粮 —— 收获日的 关系入账在当天结算的后半段才到，不补前 119
   * 天的饭；而 E4b 又不许凭"未来会收到给养"借粮。于是第二周期富农/中农/贫农 在周期后段仍会缺（地主缸里却堆着足够整格吃一整个周期的粮 ——
   * 这是**分配/信用容量**的结果，不是"颗粒无收"）。 缺口逐值 = Σ行 {@code max(0, 本周期需求 − 周期开始时的缸)} − 关账日那顿从**当日入账**里吃上的
   * {@code min(缺口, 当日口粮)}。
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

      // ★★ **E4b 起没有借贷**：地主 F = 0 且可自用余粮 = 0 ⇒ headroom = 0 ⇒ 富农的余粮一分没动。
      //   于是缺口 = Σ需求 − (开缸库存 − 地主周期末自己留下的余量) − 缺粮行在关账日吃上的那一顿：
      //   缸里少掉的那部分（含地主 250 天储备中的 120 天自需）是真正被吃掉的，剩下的
      //   PLAINS_LANDLORD_LEFTOVER（= 250 天 − 120 天 = 541,666）仍躺在地主缸里，不算进"实吃"。
      assertThat(hexUnmet(cycle1, 0, 0))
          .as("(0,0) 第一周期的缺口 = Σ行需求 − 整缸库存 + 地主周期末尚未吃完的自留余量" + " − ★ H5 ②：缺粮行在关账日自己收成里吃上的那一顿")
          .isEqualTo(
              need1
                  - hexStock(initialBooks, initial, 0, 0)
                  + PLAINS_LANDLORD_LEFTOVER
                  - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
      assertThat(PLAINS_LANDLORD_LEFTOVER)
          .as("★ 绝对锚：地主 50 人**没贷出去**的自留额 = 250 天开缸 − 120 天本周期自需 = 541,666 毫粮")
          .isEqualTo(541_666L);
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

      // ★★ 第二周期缺口逐行可推：周期开始时的缸 = cycle1 末的余额；关账日那顿由当日入账支付，最多冲减一日口粮。
      long expectedCycle2Unmet = 0L;
      for (HouseholdId key : classKeysAt(cycle1, new HexCoord(0, 0))) {
        ClassRow row = cycle1.classes().get(key);
        if (row.population() <= 0L) {
          continue;
        }
        long cycleNeed =
            EconomyVocabulary.cumulativeRationMilli(row.population(), cycleDaysAt(cycle1, 0, 0));
        long startStock = householdGoods(cycle1Books, cycle1, key, GRAIN);
        long preHarvestDeficit = Math.max(0L, cycleNeed - startStock);
        expectedCycle2Unmet +=
            preHarvestDeficit - Math.min(preHarvestDeficit, rationOn(row.population(), 240L));
      }
      assertThat(expectedCycle2Unmet)
          .as("★ 绝对锚：第二周期缺口 = 贫 215,424 + 中 342,944 + 富 359,980 = 918,348（逐行手推）")
          .isEqualTo(918_348L);
      assertThat(hexUnmet(cycle2, 0, 0))
          .as("★ E4b：第二周期仍有缺口（不许凭未来入账借粮；缺口 = 上周期末缸 − 本周期需求 − 关账日那顿）")
          .isEqualTo(expectedCycle2Unmet);
      assertThat(hexUnmet(cycle2, 0, 0)).as("缺口确实非 0（不是青黄不接已过）").isPositive();

      HouseholdId landlordId = rowOf(cycle1, ruralKey(0, 0, LANDLORD)).id();
      assertThat(householdGoods(cycle2Books, cycle2, landlordId, GRAIN))
          .as("★ 地主缸里的粮 > 整格一整个周期的需求（10,000,000）—— 第二周期的缺口是**分配/信用容量**的结果")
          .isGreaterThan(1_000L * EconomyVocabulary.RATION_MILLI_PER_PERSON);
      assertThat(hexGrain(cycle2Books, cycle2, 0, 0))
          .as("整格 Σ 缸仍为正（物理上不缺粮，缺的是贷到粮的路径）")
          .isPositive();
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
      ClassRow row = rowOf(day1, ruralKey(0, 0, PEASANT));

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
              PLAINS_INTAKE + PLAINS_LANDLORD_LEFTOVER - PLAINS_DEFICIT_ROWS_MEAL_ON_HARVEST_DAY);
    }
  }

  /**
   * ★★ **农村家庭纺织：同一批农村人、第二个生产过程**（spec §四 的压力测试；R3 的 T4）。
   *
   * <pre>
   * 农村那一池的日劳动 498,800 拆成农业 900‰ = 448,920 + 纺织 100‰ = 49,880；
   * H5 ③ 的缺口信号再把农业用不上的 120 给有缺口的纺织 ⇒ 纺织配额 = 50,000 千分劳动/日。
   * 纺织规模 = min(织机 50, 劳动 50,000 ÷ 1000 = 50, 纤维 1,500,000 ÷ 30,000 = 50) = **50**
   * 产布 = 50 × {@link EconomySeeder#CLOTH_PER_LOOM_PER_CYCLE}(30) × 1000 = 1,500,000 毫布（毛）⇒ 净 1,455,000
   * </pre>
   *
   * <p>★★ **判别力（逐条对着一种坏实现）**：
   *
   * <ul>
   *   <li>"只看土地/只看粮"⇒ 纺织那一路的规模算不出来（它的产能是**织机**）⇒ 布恒 0 ⇒ 红；
   *   <li>"纤维投入不按产能取足"（只取部分织机那份）⇒ 规模 &lt; 50 ⇒ 毛产与分成入账同时偏低 ⇒ 红；
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

      assertThat(unitOf(afterHarvest, WEAVE_0).cycleLaborMilli()).as("关账后周期劳动清零（与农业同处）").isZero();
      long grossCloth =
          WEAVE_LOOMS_OPENED
              * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 0, 0, EconomyTestWorld.CLOTH))
          .as("★★ T4：布落在**织布的人**手里（= 0_0|rural|* 四行）—— 700‰ 的分成入账 1,018,499")
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
      assertThat(unitOf(afterHarvest, FARM_0).cycleInputUsedMilli())
          .as("农业的投入累加器里**没有**纤维（纤维是它的产出，不是投入）")
          .doesNotContainKey(EconomyTestWorld.FIBER);
    }
  }

  /**
   * ★★ **城市作坊：非 LAND 生产成立 + 城市能产出自己的产品**（spec §六 给 T5 定的两个目的）。
   *
   * <pre>
   * (2,0) 300 城市人 ⇒ 作坊 6 座（{@link EconomySeeder#URBAN_CAPITA_PER_WORKSHOP} = 50；实物总账 6 座）
   * 规模 = min(作坊 6, 劳动 148,393 ÷ 1000 = 148, 纤维 360,000 ÷ 60,000 = 6, 铁 60,000 ÷ 10,000 = 6) = **6**
   *   （★ 三条约束全与土地**无关**：该格的 LAND 份额全在农业产业名下）
   * 产布 = 6 × 60 × 1000 = 360,000（毛）⇒ 净 349,200；产工具 = 6 × 5 × 1000 = 30,000（毛）⇒ 净 29,100
   * </pre>
   *
   * <p>★ 判别力：把规模写成"按土地算" ⇒ 该格作坊名下土地为 0 ⇒ 城市永远产不出东西（v1 的病态）⇒ 红。
   */
  @Test
  void theCityWorkshopProducesClothAndToolsWithoutAnyLand() {
    try (CoreSimos core = freshCore()) {
      EconomyData before = economy(core);
      ActorData beforeBooks = actor(core);
      advance(core, 120);
      EconomyData afterHarvest = economy(core);
      ActorData afterHarvestBooks = actor(core);

      // ★ H0.3（K3）/R3B.1：产能的真值是 **AssetShare 实物总账**（`Σ quantity`），不是旧档兼容位
      //   `Industry.capacity`（归一化后已清成中性）⇒ "城市生产不含土地"这条判据的落点随之搬家。
      assertThat(capacityOf(afterHarvest, CRAFT_2, AssetKind.LAND))
          .as("作坊的产能**不是土地**（土地全归农业）")
          .isZero();
      assertThat(capacityOf(afterHarvest, CRAFT_2, AssetKind.WORKSHOP))
          .as("作坊的实物产能 = 6 座（与上面 opened 用同一个数）")
          .isEqualTo(CRAFT_WORKSHOPS_OPENED);
      assertThat(
              capacityOf(
                  afterHarvest, IndustryHexKeys.id(EconomySeeder.FARM, 2, 0), AssetKind.LAND))
          .as("同格的土地全在**农业**产业的实物总账上（「谁有地」与「谁有作坊」分得开）")
          .isPositive();
      long grossCloth =
          CRAFT_WORKSHOPS_OPENED
              * EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      long grossTool =
          CRAFT_WORKSHOPS_OPENED
              * EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE
              * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 2, 0, EconomyTestWorld.CLOTH))
          .as("(2,0) 作坊的布入账 = 600‰ 分成（{@code handicraft}）× 布净产 349,200 ⇒ 逐行取整后 209,518")
          .isEqualTo(CRAFT_CLOTH_INTAKE);
      assertThat(accountOf(afterHarvestBooks, afterHarvest, CRAFT_2, EconomyTestWorld.CLOTH))
          .as("★ 余下 400‰ 留在 operator 账上（布净产 − 分成）")
          .isEqualTo(grossCloth * 970L / 1000L - CRAFT_CLOTH_INTAKE);
      assertThat(hexGoods(afterHarvestBooks, afterHarvest, 2, 0, EconomyTestWorld.TOOL))
          .as("★ T4：工具**没有规则付给 cohort** ⇒ 行里一件不进（它落 operator 的账 —— 见下一条）")
          .isZero();
      assertThat(accountOf(afterHarvestBooks, afterHarvest, CRAFT_2, EconomyTestWorld.TOOL))
          .as("(2,0) 作坊的工具净产 = 6 座 × 5 件 × 1000 × 0.97（第二件城市自己的产品，落在经营主体账上）")
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
   * <p>★★ T4/T5：参与者用 {@link EconomyOwnershipTimeParticipant}（**同时看得见 economy 与 actor**）—— 产出自本阶段起离开
   * {@code ClassRow}，必须由它落到 operator 的账上。★ 该参与者已被标 {@code @Deprecated} （真档由三片的 {@code
   * PopulationEconomyTimeParticipant} 承担），本夹具没有 social 片，故仍用它作为两片会合点； 旧的 {@code
   * EconomyTimeParticipant} 已删除。
   */
  private static CoreSimos freshCoreAt(Path dir) {
    CoreSimos core =
        new CoreSimos(new CoreConfig(dir, CHECKPOINT_INTERVAL, SimosObjectMapper.create()));
    core.register(new EconomyCodec());
    core.register(new ActorCodec());
    core.register(new EconomyOwnershipTimeParticipant(EconomyTestWorld.MAP_ID));
    // ★ M2 收尾：本类测日结算/收获/E4b 信用容量，闭式期望一律按"窗口内无市场"手推 ⇒ 用无市场创世
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
   * ★★ <b>{@code operator} 的账上某商品的余额</b>（T4/T5 起产出的落点）—— 账户 = {@code (operator, 该 unit 所在的格)}。
   *
   * <p>★★ <b>R3B.2 起 {@code operator} 的真值是 {@link ProductionUnit#operator()}（不是 {@code
   * Industry.operator()}的旧兼容位）</b>：产业是技术模板，实际生产活动与经营主体住在 unit 上。账户地点由 unit 的产业 id 解析（{@code
   * IndustryHexKeys} 是唯一拼写点）。
   */
  private static long accountOf(
      ActorData actor, EconomyData data, IndustryId industry, CommodityId commodity) {
    ProductionUnit unit = unitOf(data, industry);
    ActorRef operator = unit.operator();
    HexCoord hex = HexCoord.parse(IndustryHexKeys.hexKeyOf(unit.industry()).orElseThrow());
    GoodsAccount account = actor.accounts().get(new GoodsAccountKey(operator, hex));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /** 某个产业名下各行某商品的库存（逐行）。 */
  /** 某格某一组家户行持有的布（H0.2：行键 = {@code (格, 居住类型, 阶层)} ⇒ "哪一组"由这两维直接点名； ★ 它是"并账判据"的读法：同格两组账必须分得开）。 */
  private static List<Long> clothOfRowsAt(
      ActorData books, EconomyData data, int q, int r, ResidenceKind residence) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .filter(key -> rowOf(data, key).view().residence().equals(residence))
        .map(key -> householdGoods(books, data, key, EconomyTestWorld.CLOTH))
        .toList();
  }

  /**
   * ★★ <b>某个家户账上某商品的余额</b>（H1：商品库存的唯一真源 = actor 侧的 {@code GoodsAccount}）。
   *
   * <p>★ 账户键由 {@link OwnershipBooks#accountKeyOf} 拼（**本文件不复述家户 id 与账户键的形状**）；地点取自 {@code
   * ClassRow.view().hex()}（S1 起账 location 挂在视图上，身份只是键）；账本缺席 ⇒ 0 （该家户还没被播 ——
   * 本文件的创世把"每格两组四行"播全了，故这条兜底只在装配故障时才走到）。
   */
  private static long householdGoods(
      ActorData books, EconomyData data, HouseholdId household, CommodityId commodity) {
    ClassRow row = data.classes().get(household);
    if (row == null) {
      return 0L;
    }
    GoodsAccount account =
        books.accounts().get(OwnershipBooks.accountKeyOf(household, row.view().hex()));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /**
   * ★★ <b>按当前视图找一个家户行</b>（只服务本夹具的"阶层 × 居住 × 格"点名；真档的视图在 S3 阶层写回后会变， 身份才是稳定键）。
   *
   * <p>★ 夹具每格每个视图恰一行（空账行也有自己的视图）⇒ 只接受唯一命中；歧义/缺席都抛，不猜。
   */
  private static ClassRow rowOf(EconomyData data, CohortKey view) {
    List<ClassRow> hits =
        data.classes().values().stream().filter(row -> row.view().equals(view)).toList();
    if (hits.size() != 1) {
      throw new AssertionError("视图 " + view + " 命中 " + hits.size() + " 行（夹具应恰 1 行）");
    }
    return hits.get(0);
  }

  /** 同 {@link #rowOf}，但按家户 id 直取。 */
  private static ClassRow rowOf(EconomyData data, HouseholdId household) {
    ClassRow row = data.classes().get(household);
    if (row == null) {
      throw new AssertionError("没有家户 " + household);
    }
    return row;
  }

  private static EconomyData economy(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
    EconomySnapshot slice =
        (EconomySnapshot)
            state.module("economy").orElseThrow(() -> new AssertionError("状态里没有 economy 切片"));
    return slice.data();
  }

  /**
   * ★★ **该产业的唯一生产单元**（R3B.2：进度/周期劳动/实扣投入的真值住在 {@link ProductionUnit}，产业只是技术模板）。
   *
   * <p>★ 本夹具每产业恰一个 unit；多于一个 ⇒ 抛（拒绝用"随便取一个"掩盖装配错误）。
   */
  private static ProductionUnit unitOf(EconomyData data, IndustryId industry) {
    List<ProductionUnit> units =
        data.units().values().stream().filter(unit -> unit.industry().equals(industry)).toList();
    if (units.size() != 1) {
      throw new AssertionError("产业 " + industry + " 的 unit 数 = " + units.size() + "（期望恰 1）");
    }
    return units.get(0);
  }

  /**
   * ★★ **该产业名下某种实物资产的账面总量** = Σ {@link AssetShare#quantity}（R3B.1：实物总账的唯一真源； {@code
   * Industry.capacity} 只是旧档兼容位，归一化后已清成中性）。
   */
  private static long capacityOf(EconomyData data, IndustryId industry, AssetKind asset) {
    return data.assetShares().values().stream()
        .filter(share -> share.industry().equals(industry) && share.asset().equals(asset))
        .mapToLong(AssetShare::quantity)
        .sum();
  }

  // ── 逐格汇总（都经 IndustryHexKeys 认"产业属于哪一格"，不自己拼 id）────────────────────

  private static List<ClassRow> rowsAt(EconomyData data, int q, int r) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .map(key -> data.classes().get(key))
        .toList();
  }

  /**
   * 某格的家户行键（S1：键 = {@link HouseholdId}，地点/居住/阶层住在 {@link ClassRow#view()}；排序按 (居住类型, 阶层) ⇒ 可复现）。
   */
  private static List<HouseholdId> classKeysAt(EconomyData data, HexCoord coord) {
    return data.classes().values().stream()
        .filter(row -> row.view().hex().equals(coord))
        .sorted(
            Comparator.comparing((ClassRow row) -> row.view().residence().value())
                .thenComparing(row -> row.view().stratum().value())
                .thenComparing(row -> row.id().value()))
        .map(ClassRow::id)
        .toList();
  }

  /**
   * ★★ **某产业对应的家户行键**（H0.2/S1：行键里已经没有产业）。
   *
   * <p>★ 事实来源是**劳动配额表**：配额的 {@code activity} 指向本产业的 unit ⇒ 该配额的 {@link LaborAllocation#household()}
   * 就是这家户（{@code EconomySettlement.householdKeysOf} 同一口径）。 农村家户同时供给农业与家庭纺织 ⇒
   * 两者**返回同一组四行**（这正是"一个家户一份账"的形态）。
   *
   * <p>★ <b>为什么不再比较 {@code actor.id()}：</b>R3B.2 起 actor 是 unit 的经营主体（佃农/家户自营时 actor 的 id 与产业 id
   * 可以不同）；"这份劳动喂哪条生产活动"的唯一答案是 {@code activity} == unit id。
   */
  private static List<HouseholdId> classKeysOf(EconomyData data, IndustryId industry) {
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (LaborAllocation allocation : data.allocations().values()) {
      ProductionUnit unit = data.units().get(new ProductionUnitId(allocation.activity()));
      if (unit != null && unit.industry().equals(industry)) {
        households.add(allocation.household());
      }
    }
    return households.stream()
        .filter(data.classes()::containsKey)
        .sorted(Comparator.comparing(HouseholdId::value))
        .toList();
  }

  /** 一个农村家户键（{@code (格, RURAL, 阶层)}）—— 夹具的 5 格都是"农村四行 + 城镇四行"。 */
  private static CohortKey ruralKey(int q, int r, SocialClassId stratum) {
    return new CohortKey(new HexCoord(q, r), ResidenceKind.RURAL, stratum);
  }

  /** 某格 Σ 家户粮库存（毫粮）—— H1：库存住在 actor 侧的账本上（{@code books}）。 */
  private static long hexGrain(ActorData books, EconomyData data, int q, int r) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .mapToLong(key -> householdGoods(books, data, key, GRAIN))
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
    for (HouseholdId key : classKeysAt(data, new HexCoord(q, r))) {
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
    for (HouseholdId key : classKeysAt(data, new HexCoord(q, r))) {
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
    for (HouseholdId key : classKeysAt(data, new HexCoord(q, r))) {
      total +=
          EconomyVocabulary.cumulativeRationMilli(data.classes().get(key).population(), cycleDays);
    }
    return total;
  }

  /** 某格 Σ 饿死人口（取各行流水的累计 {@code deaths}）。 */
  private static long hexDeaths(EconomyData data, int q, int r) {
    long total = 0L;
    for (HouseholdId key : classKeysAt(data, new HexCoord(q, r))) {
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
        .mapToLong(key -> householdGoods(books, data, key, GRAIN))
        .sum();
  }

  /**
   * 一整个产业的当日**粮**所得合计（毛产出份额）。
   *
   * <p>★ R3：{@code income} 是**逐商品**的表（田里同时出粮与纤维）⇒ 必须指名粮那一维；把两维相加会得到"粮 + 纤维"的和。
   */
  private static long sumHarvestIncome(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (HouseholdId key : classKeysOf(data, industry)) {
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
    for (HouseholdId key : classKeysOf(data, industry)) {
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
   * <p>★★ **E4b 起这两项都得改**：①"收获后库存 == 净产"不再成立（地主缸里还留着没贷出去的自留额 {@link #PLAINS_LANDLORD_LEFTOVER} ⇒
   * 差要取 {@code 收获后 − 收获前}）；②收获日当天**真的有人吃得上饭**了 （富农 12,500 + 地主 4,167 + 缺粮的贫/中农 66,667）⇒
   * 不减掉那一笔的话，反推出来的"损耗"会虚高恰好等于它。
   *
   * <p>★ 它只对"本次推进内除日耗与本次收获外没有别的发生额"的产业成立（该夹具的 (0,0) 正是如此：第 120 天**无借贷** —— E4b 下地主自己（借方）的 headroom
   * = 0，不是旧口径的"停贷"）。
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
    for (HouseholdId key : classKeysOf(after, industry)) {
      stockBefore += householdGoods(beforeBooks, before, key, GRAIN);
      stockAfter += householdGoods(afterBooks, after, key, GRAIN);
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
        .mapToLong(key -> householdGoods(books, data, key, commodity))
        .sum();
  }

  /** 某格 **Σ 家户某商品库存**（该格两组四行都算进来）。 */
  private static long hexGoods(
      ActorData books, EconomyData data, int q, int r, CommodityId commodity) {
    return classKeysAt(data, new HexCoord(q, r)).stream()
        .mapToLong(key -> householdGoods(books, data, key, commodity))
        .sum();
  }

  /** 某产业对应的**那组家户行**（H0.2：行不再属于产业 —— 由劳动配额把"批次 → 居住类型"对上，见 {@link #classKeysOf}）。 */
  private static List<ClassRow> rowsOf(EconomyData data, IndustryId industry) {
    return classKeysOf(data, industry).stream().map(key -> data.classes().get(key)).toList();
  }
}
