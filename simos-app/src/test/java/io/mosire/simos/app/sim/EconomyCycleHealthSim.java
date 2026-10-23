package io.mosire.simos.app.sim;

import static io.mosire.simos.app.sim.EconomyCycleReading.advance;
import static io.mosire.simos.app.sim.EconomyCycleReading.appendRow;
import static io.mosire.simos.app.sim.EconomyCycleReading.moneyText;
import static io.mosire.simos.app.sim.EconomyCycleReading.read;
import static io.mosire.simos.app.sim.EconomyCycleReading.replay;
import static io.mosire.simos.app.sim.EconomyCycleReading.tickOf;
import static io.mosire.simos.app.sim.EconomyCycleReading.writeSummary;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.GovGenesisSeedAccess;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.sim.EconomyCycleReading.Reading;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.time.MarketReportFeed;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.util.state.SimulationState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.logging.log4j.Level;

/**
 * ★★ <b>批 M（2026-10-15）：本条"多 tick 全真世界模拟"已从 JUnit 测试类搬成独立跑法</b>。
 *
 * <p>★★ <b>用户原话（逐字，2026-10-15）</b>：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑， 别塞进测试里！」
 *
 * <p>★ <b>搬了什么</b>：原 {@code
 * io.mosire.simos.app.time.EconomyCycleHealthTest#twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive}
 * 的**整整一段**（240 天 = 8 段 × 30 天 + 96 轮市场指纹），连同它的全部断言**逐字搬到这里**； 跑法从 surefire 换成 {@code
 * tools/multitick-sim.sh economy-cycle-health}（独立进程、独立 store、 落在 {@code
 * test-world/multitick/economy-cycle-health/}、不占端口、不进 {@code clean verify}）。 门禁里留的是**同名 smoke
 * {@code EconomyCycleHealthTest}**：1 段 30 天，判据只留"推进没被吞 / 货币守恒 / 关口市场开市"这三条最硬的（96 轮分布指纹与 8 段推进归这里）。
 *
 * <p>★ <b>判据没丢</b>：下面每一条 {@code assertThat(...)} 都是原测试的原文（含 {@code assertEveryMarketRoundTraded}
 * 的五条牙），红色语义一模一样 —— 只是红的时机从"门禁"变成"跑 {@code tools/multitick-sim.sh}"（见 §V2 变异自证）。
 */
public final class EconomyCycleHealthSim {

  private EconomyCycleHealthSim() {}

  /**
   * ★★ <b>真实世界经济循环体检</b>（本批新增）—— 用户原话：「有没有现有的实际经济循环测试给我看，以验证已有机制中正常？」。
   *
   * <p>★★ <b>这条测试在验什么</b>：在<b>真世界</b>（{@link WorldRegistry#THREE_POWERS}：37 格 / 3 个市场区 / 3 个 GOV /
   * 3 种货币 / 6,350 人）+ <b>真推进</b>（真 {@link Shell} + 真 {@code shell.advanceAndDrain(AdvanceTime)}）上跑
   * <b>2 个完整产业周期</b>（{@code CYCLE_DAYS = 120} ⇒ 240 天，分 8 段 × 30 天推进），每个关账日输出一行<b>人可读摘要</b>（人口 / 粮
   * / 布 / 债务本金 / 逐币种货币总量 / 本轮成交笔数 / 未成交归因 top3），并逐段断言下面这 <b>5 组不变量</b>。
   *
   * <p>★ <b>"关账日"在本测试里指什么</b>：段末 = 30 天的倍数 —— 它是 <b>GOV 官署的关账周期</b>（{@code
   * ThreePowersGovBootstrap.OFFICE_CYCLE_DAYS = 30}），同时必然命中市场例行轮（{@code day % 5 == 0}）；其中第 120 /
   * 240 天<b>同时</b>是 产业周期关账日（{@code CYCLE_DAYS = 120}）。⇒ 8 个段末都是"该有结算、该有开市"的日子，用它们当体检取样点。
   *
   * <p>★★ <b>为什么选 {@code three-powers} 而不是 {@code small-world}</b>：<b>①</b> 它有 <b>3 种货币</b>（silver
   * / copper / gold，每区法定币不同）⇒「逐币种货币总量守恒」这条判据在这里<b>非平凡</b>：跨区成交、外汇、跨币种工资任何一处"凭空造币/烧币"都会当场破；
   * 单币种世界里这条断言弱得多（少一个币种就少一类泄漏面）。<b>②</b> 它<b>没有政府家户</b>（{@code ThreePowersWorld} 建 {@code
   * PopulationSeeder.Seeding} 时 {@code governmentRef = null}），三个 GOV 走 {@code
   * economy.RegisterGovernment} 的 <b>GOV 单位</b>路径、不声明铸币政策（★★ W5 起字段名是 {@code mintScalePerCycle}，缺省
   * 0）⇒ 本世界<b>既没有铸币产业</b> （{@code EconomySeeder.seedGovernmentMint} 只播种给"有政府家户"的世界）<b>也没有 GM 发行</b>
   * ⇒ 货币总量<b>理应逐值不变</b>。 而 {@code small-world} 走的是"demo 世界政府家户"路径、出厂就带 {@code
   * GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI = 2,000} 毫/周期 + 一座铸币产业（W5 账本 N-5 / N-11）<b>真的产出货币</b> ⇒
   * 在那个世界上"货币总量不变"<b>本来就不该成立</b>（★ 旧口径是 {@code seignioragePerCycle} 在周期开始<b>直接加进国库</b>，W5 起改成"产业产出
   * + 上限 = 政策旋钮 {@code mintScalePerCycle}"；★★ W5-fix5 / 设计书 v3.18 §7 起"上一轮印量"只作真读数、<b>不参与任何
   * clamp</b>，用户原话 「铸币能进政府的家户，按照决策人预期被发行就行」）。 <b>③</b> 它是阶段 2 判据 G1 指定的验收世界（3 区 / 3 币 /
   * 辖区两两不相交），不是为这条测试临时造的夹具。
   *
   * <p>★★ <b>推进口径（为什么 30 天一段，不是一天一步）</b>：{@code
   * docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md} §12–§13 实测：单步推进 485 ms/天
   * vs 分段推进 48 ms/天（<b>10 倍</b>），因为每提交一次 revision 就要重建/落盘一次整份状态的派生差量。 故本测试一律 <b>30 天一段</b>（{@code
   * AdvanceTime} 原生支持 {@code from→to} 多天，内部仍逐日结算），只在段末读一次状态。
   *
   * <p>★★ <b>被断言的不变量清单（每条都是"机制正常"的判据，不是"跑出来的数字"）</b>：
   *
   * <ol>
   *   <li><b>①货币守恒（最硬）</b>：<b>逐币种</b>的货币总量 = Σ 全部账户（家户 / 组织 / 国库 / 单位）在该币种上的余额，<b>全程与创世基线逐值相同</b>。
   *       理由：本仓货币只有三个合法来源（创世 {@code INITIAL_ENDOWMENT} 发行记录、GM 的 {@code RecordMoneyIssuance}、 ★ W5
   *       起的 <b>铸币生产方式</b> {@code MINT_PRODUCTION} —— 旧口径的"周期铸币 {@code seignioragePerCycle}
   *       直充国库"已随双轨一并删除），而 {@code three-powers} 创世后<b>没有任何一个</b>在跑；一切经济行为（成交、借还、税、工资、迁移、跨区结算）都必须是
   *       <b>余额转移</b>。⇒ 任何一处"凭空造币 / 静默烧币"都会在这里当场红。★ 这条也顺带守住"货款腿与货腿原子"（只动钱不动物、或只动物不动钱，会先破别的判据）。
   *   <li><b>②非负</b>：人口 &gt; 0、粮存量 ≥ 0、布存量 ≥ 0、债务本金 ≥ 0。理由：库存与债务本金都是<b>存量</b>，负数是"透支"这种本仓明令不存在的语义
   *       （{@code HouseholdInventory} 构造期就拒负）；而人口为 0 意味着世界死了。
   *   <li><b>③人口不爆不灭</b>：每个关账日人口 &gt; 0，且相邻两段（30 天）人口的比值落在 <b>[1/2, 2]</b> 内。理由：30 天尺度上人口只由生死率
   *       （ppm/tick 量级）与迁移改变，一个 6,350 人的世界在 30 天内减半或翻倍<b>不是"机制正常"</b>，而是出生/死亡/迁移结算坏了；"数量级跳变"的字面判据是
   *       ×10，这里取 ±50% 是更保守的写法（真要跳变必先破这一条）。★ 不写死具体人数：那会把测试变成快照。
   *   <li><b>④市场在运转</b>：每个关账日必须读到<b>当日</b>的市场报告（{@code report.day() == 当日 tick}）且成交笔数 &gt;
   *       0。理由：市场调度是 "绝对日 {@code day % 5 == 0} 例行开市 + 关账日保底 + 低库存追加"（见 {@link
   *       io.mosire.simos.economy.time.MarketSettlement} 的 {@code triggerFor}）， 30 天的倍数必然命中例行轮 ⇒
   *       "报告日 ≠ 当日"或"成交 0 笔"都说明市场<b>冻结</b>了 —— 这正是本仓历史缺陷（市场冻结 / 借贷不触发）复发时的第一征兆， 这条专防它复发。★ 要求"报告日
   *       == 当日"而不是"最近一轮"，是为了排除"读到 25 天前的旧报告"这种假绿。 ★★ <b>W4b 加牙（2026-10-11 测试批）</b>：段末取样只有 8 个点，
   *       "零成交轮恰好不在段末"时它会全绿 ⇒ 现在**另加**一条<b>逐市场轮</b>断言：96 个市场轮里<b>零成交轮次必须 = 0</b>，
   *       且逐轮成交笔数的<b>整份分布指纹</b>与实测基线逐值相同（判别力说明见 {@link #assertEveryMarketRoundTraded(List)}）。★
   *       加牙**只加不放宽**：段末那条"&gt; 0"断言原样保留。
   *   <li><b>⑤账目闭合</b>：每个段末 {@code state.meta().timestamp().tick()} 必须等于该段的目标 tick（无静默跳过）；head
   *       revision <b>严格递增</b>； 段数必须等于 {@code 240 / 30 = 8}（{@code AdvanceTime} 被拒/被吞会让段数或 tick
   *       对不上）。理由：时间推进是本仓唯一的"世界前进"入口， 它静默少走几天，后面所有读数都会错位而没人知道。
   * </ol>
   *
   * <p>★★ <b>读数的口径与边界（如实写）</b>：
   *
   * <ul>
   *   <li>货币总量 / 粮存量 / 布存量 读的是 actor 切片的账户余额（{@code ActorData.accounts()} 的 {@code money()} /
   *       {@code balances()}）—— 那是本仓商品与货币余额的<b>唯一真源</b>；<b>在途货物不算在内</b>（跨区发运中的 {@code
   *       ShipmentBatch} 是与账户并列的另一个池子）， 故这里的"粮/布存量"读作<b>账上存量</b>；本测试不断言在途量（那是另一条判据）。
   *   <li>人口读的是 Social 切片（人口/生死的唯一权威：{@code SocialData.households()} 的成员份额之和），不读经济侧的物化视图。
   *   <li>债务本金 = Σ {@code EconomyData.debtContracts().values()} 的 {@code
   *       principal()}（连续本金余额，计息并入本金）。
   *   <li>成交笔数与未成交归因来自<b>进程内的</b> {@code MarketReport}（经 {@link MarketReportFeed}，不落盘、重启即失）——
   *       本测试在同一进程内推进后立即读，因此可读；这是它的合法用法。
   * </ul>
   *
   * <p>★★ <b>这条红了怎么办（fail-closed，不许靠放宽判据变绿）</b>：① 若红在<b>货币守恒</b>上 ⇒ 先查"这一批是不是新引入了货币的 发行/回笼"（W5 起的铸币
   * {@code MINT_PRODUCTION}、GM 的 {@code RecordMoneyIssuance}、税费/拆迁里的销毁）—— 若确实引入，正确的修法是让本测试把
   * <b>发行记录</b>并入等式（{@code Σ账户余额 = Σ INITIAL_ENDOWMENT + Σ MINT_PRODUCTION + Σ FISCAL_ISSUE − Σ
   * WITHDRAWAL}），<b>不是</b>把断言删掉或放宽成区间； 若没有引入发行，那它就是<b>真缺陷</b>（凭空造币/静默烧币），停下来如实报告。② 若红在<b>市场成交 0
   * 笔</b>上 ⇒ 那就是"市场冻结"复发， 同样按缺陷报，不许把这条判据降级成"允许 0 笔"。
   */

  /** 产业周期（天）：与 {@link EconomySeeder#CYCLE_DAYS} 同源（120）。 */
  private static final long CYCLE_DAYS = EconomySeeder.CYCLE_DAYS;

  /**
   * 每段推进的天数：★ 30 天一段（§12 的 10 倍效率口径），且 30 是关账日（GOV 官署 {@code OFFICE_CYCLE_DAYS = 30}、 也是市场例行轮
   * {@code day % 5 == 0}）。
   */
  private static final long SEGMENT_DAYS = 30L;

  /** 总推进天数 = 2 个完整周期（≥ 用户要求的下限）。 */
  private static final long TOTAL_DAYS = 2L * CYCLE_DAYS;

  /**
   * 段数（构造性等于 {@code TOTAL_DAYS / SEGMENT_DAYS} = 8；末段断言用它守"无静默跳过"）。
   *
   * <p>★ {@code public} 只为了让门禁 smoke 的摘要行能引用它（"多 tick 重跑=8 段"这句说明）—— 判据未动。
   */
  public static final int SEGMENTS = (int) (TOTAL_DAYS / SEGMENT_DAYS);

  /** 人口带：相邻两段人口的比值必须落在 [1/2, 2]（理由见类注不变量③；不写死人数）。 */
  private static final long POPULATION_BAND_NUMERATOR = 1L;

  private static final long POPULATION_BAND_DENOMINATOR = 2L;

  /**
   * 本测试世界的 mapId（★ 刻意<b>不用</b>缺省的 {@code Map1}）：进程内的市场报告投递点 {@link MarketReportFeed} 以 <b>mapId
   * 为键</b>，而同一个 surefire JVM 里还有别的世界（其它测试类）也叫 {@code Map1} —— 用自己的 mapId 才不会与它们互相串味（读侧"只在同一 tick
   * 内可信"那条边界）。
   */
  private static final String MAP_ID = "econ-cycle-health";

  /**
   * ★★★ <b>W4b 加牙（2026-10-11 测试批）：市场轮总数基线</b>。
   *
   * <p>为什么要有它：{@code 0→240} 的市场轮 = "每 5 天例行 + 关账日 + 粮紧张追加" ⇒ 绝对日决定、**逐进程可复现** （W4 验收
   * §2.1：两个独立进程连存档都逐字节相同）。钉住它有两个作用：① 让"零成交轮次 = 0"这条断言**知道自己扫了几轮** ——少扫到轮次（读侧漏采）会让判据静默变弱；②
   * 轮数一变（例如市场触发口径被改）当场可见，而不是悄悄换个样本量。
   */
  private static final int BASELINE_MARKET_ROUNDS = 96;

  /**
   * ★★★ <b>W4b 加牙：逐市场轮成交笔数的分布指纹（笔数 → 轮次数）</b>。
   *
   * <p>实测来源：本测试自身的 {@code -Dsimos.economy.logLevel=DEBUG} 等价形态（进程内 {@link AppLogCapture} 把 {@code
   * MARKET_REPORT} 逐轮收进内存）+ 逐轮解析，实得 <b>Σ = 96 轮</b>。
   *
   * <p>★★ <b>为什么钉整份分布，而不是只钉一个下界</b>：W4 初版的"市场冻结"回归里，96 轮中有 15 轮零成交（day 13/15/18/63/65/68/70/73/
   * 75/78/80/83/85/88/90）却**只有落在段末（day 90）的那一轮**被旧断言逮到；另一次同族坏法里零成交轮恰好全落在非段末日 ⇒ 旧断言（每段 >
   * 0）会**全绿**。只钉"最小值 ≥ 17"仍会漏掉"最小值没变、但中位/尾部整体塌下去"的形态；分布指纹对
   * "任何一轮的成交笔数变了"都当场红（这是**基线守卫**：世界行为有意改变时必须显式重新基线化，不是放宽判据）。
   *
   * <p>★★★ <b>W5.5 测试批（2026-10-11）：显式重新基线化（旧值 → 新值 + 依据）</b>：
   *
   * <pre>
   * 旧指纹（W4b 基线，改前世界）：
   *   {17=10, 18=1, 21=7, 22=9, 23=5, 24=12, 31=1, 34=1, 35=3, 36=4, 37=8, 38=1,
   *    39=2, 40=13, 41=2, 44=1, 53=8, 54=1, 55=3, 56=2, 66=1, 131=1}      min/中位/max = 17 / 35.0 / 131
   * W5.5 指纹（被 W7 取代，留作对照）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 43=4, 44=5, 45=24,
   *    46=2, 47=5, 48=1, 49=1, 50=1, 51=1, 56=1, 57=1, 66=1, 92=1, 140=1}   min/中位/max = 15 / 43.0 / 140
   * </pre>
   *
   * <p>★★ <b>依据（W5.5 那三条一起，逐条具名）</b>：
   *
   * <ol>
   *   <li><b>W5.5 的货币腿</b>（S-12：「先借钱再买货」）：现金不足的家户先借入再成交 ⇒ <b>每轮成交笔数整体上移</b> （20 及以下的轮次从 11 轮降到 1
   *       轮、40+ 的轮次从 21 轮升到 30 轮以上）；
   *   <li><b>R2 的参与者收窄</b>（军户/官吏户只买不卖）：单位户回到买盘 ⇒ 它们的买单进入逐轮成交（W5.5-fix 账本 ⑤-③ 实测 120 天 43 条 {@code
   *       MARKET_HOUSEHOLD_BUY_ORDER}、全部现金付清）；
   *   <li><b>W5.5-fix2 的单位户创世粮</b>（每户 75000 毫粮）：单位户首日不再断顿 ⇒ 它们的卖/买行为不因饥饿缩量。
   * </ol>
   *
   * <p>★★★ <b>W7 测试批（2026-10-11）：再次显式重新基线化（旧值 → 新值 + 依据）</b>：
   *
   * <pre>
   * W5.5 旧指纹（本次实跑复现的第一行红 —— 与本文件原先钉的值逐值相同）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 43=4, 44=5, 45=24,
   *    46=2, 47=5, 48=1, 49=1, 50=1, 51=1, 56=1, 57=1, 66=1, 92=1, 140=1}   min/中位/max = 15 / 43.0 / 140，Σfills = 3522
   * W7 新指纹（本批实跑真值；★ 单一形态，见下"两套独立装置各测一次"）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   *    45=8, 46=5, 47=10, 48=3, 49=1, 50=1, 53=1, 55=1, 56=1, 66=1, 151=1, 198=1}
   *   min/中位/max = 15 / 42.0 / 198，轮数 = 96，零成交轮次 = 0，Σfills = 3648（Δ = +126）
   *   ★ 逐档差异（旧 → 新）：消失 51×1 / 57×1 / 92×1 / 140×1；新增 42×3 / 53×1 / 55×1 / 151×1 / 198×1；
   *     计数变化 43: 4→2、44: 5→10、45: 24→8、46: 2→5、47: 5→10、48: 1→3。
   * </pre>
   *
   * <p>★★ <b>W7 依据（四条一起，逐条具名；每条都给本批真读数可核）</b>：
   *
   * <ol>
   *   <li><b>① 运力池全市场区共享</b>（用户原话「运力提供不应该也是全市场区共享的吗」，v3.26 选定「全国/跨区也能被雇」）：
   *       候选集从"发货格的池"改成<b>全池</b>、行程闸改成"自家格 → 发货格 → 收货格 ≤ 派生半径"。⇒ 本世界里跨格成交的**可成交集**变了 （发货格没有承运方的
   *       lane 从"根本不建"变成"可被接单"），逐轮成交笔数随之改变 —— 这是本批分布变动的主因（成交笔数整体从"45×24" 这一档散开）；
   *   <li><b>② {@code haul} 进 §2.1 自适应定价</b>：运力 D/S 注入各自市场区的 {@code quantities[haul]}（唯一算式 {@code
   *       adaptiveNextPrice} 一字未改）⇒ 运费牌价随缺口上升、充足时回落 ⇒ 运费改变 ⇒ 跨格贸易的成交与笔数改变。本世界实测（本批 tp 真跑， day 3 起
   *       264 轮）：{@code HAUL_PRICE_SIGNAL} 三市场牌价 5 → 109,604（copper）/ 5 → 60,448（silver）/ 5 →
   *       2,128,784（gold）；
   *   <li><b>③ 非城市格可长出跑商</b>（按需 {@code trade@hex}）：★ <b>本世界里一条都没长出来</b>（本批 tp 真跑： {@code
   *       MERCHANT_TRADE_INDUSTRY_CREATED} = 0、{@code ONDEMAND_TRADE_MIGRATION_SKIPPED} = 0）⇒
   *       <b>这一条对本次指纹变动 没有贡献</b>（如实记，不把它算进依据）；
   *   <li><b>④ 两把尺统一</b>（{@code providerCapacityMilli}）：决策层的候选门槛与结算层的议价权读同一份派生量 ⇒ 候选集与排序输入改变
   *       （改前"手上有服务货、劳动为 0"的户被判 {@code no-bargaining-power}）。本世界实测：{@code
   *       no-deliverable-service-capacity} 这条**新理由**已经从"两把尺同一口径"里发出来（tp 本批真跑也有该理由的行）。
   * </ol>
   *
   * <p>★★ <b>为什么这不是"放宽判据"</b>：世界行为被 W7 有意改变了（用户裁定的 ①②④），指纹守卫的作用正是"行为一变就必须显式重新基线化" ——
   * 本次**只换期望值**，"零成交轮次 = 0"、轮次数 96、同日不得两轮、相邻间隔 ≤ 5 天、{@code Σ逐轮 fills == MARKET_FILL 行数}
   * 四条牙**一字未动**（见下 {@code assertEveryMarketRoundTraded}）。
   *
   * <p>★★★ <b>单一形态的两套独立装置各测一次（AGENTS §三.5：见到"两个结果"先核产物，不先怀疑非确定性）</b>：
   *
   * <ol>
   *   <li><b>装置 A（本测试自身）</b>：JUnit 进程内真 {@code Shell} + {@code AppLogCapture}，本批实跑 （{@code
   *       tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest'
   *       -Dsurefire.failIfNoSpecifiedTests=false}） ⇒ 分布逐值 = 上面 W7 新指纹；
   *   <li><b>装置 B（真世界 HTTP）</b>：W7 shaded jar（md5 {@code
   *       783ec45439f2e50d54b4828a9c54df44}）从**创世新播**的 three-powers 世界、{@code POST /api/advance}
   *       0→660、日志档只留白名单事件 （装置与读数：{@code .superpowers/sdd/2026-10-11-w7-test-batch/}）⇒ 取同一窗口 day
   *       0–240（96 轮）的 {@code MARKET_REPORT} 逐轮 fills ⇒ 分布与本指纹**逐值相同**（Σ = 3648、零成交轮 = 0、min/中位/max
   *       = 15/42.0/198）。
   * </ol>
   *
   * ⇒ 两套装置（进程内 vs 真 HTTP + 独立 store）给出同一份指纹 ⇒ 它**不是**产物陈旧的假象，也不是非确定性；本测试据此收回单一期望。 ★
   * 实跑命令、完整分布、逐窗口读数与账本见 {@code .superpowers/sdd/2026-10-11-w7-test-batch/impl-ledger.md}。
   *
   * <p>★★★ <b>W7e 测试批（2026-10-11）：第三次显式重新基线化（旧值 → 新值 + 依据）—— W7-fix / W7-fix2 落地后再钉一次</b>。
   *
   * <pre>
   * W7 旧指纹（W7d 测试批按当时工作树实跑钉的；本批实跑复现的第一行红 —— 与本文件原先钉的值逐值相同）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   *    45=8, 46=5, 47=10, 48=3, 49=1, 50=1, 53=1, 55=1, 56=1, 66=1, 151=1, 198=1}
   *   min/中位/max = 15 / 42.0 / 198，轮数 = 96，零成交轮次 = 0，Σfills = 3648
   * W7e 新指纹（本批实跑真值；★ 装置 A/B 逐值相同，见下）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   *    45=8, 46=5, 47=10, 48=3, 49=1, 52=1, 53=1, 55=1, 57=1, 66=1, 151=1, 198=1}
   *   min/中位/max = 15 / 42.0 / 198（★ 三项都没变），轮数 = 96，零成交轮次 = 0，Σfills = 3651（Δ = +3）
   *   ★ 逐档差异（旧 → 新，**只有两档动**）：50×1 → 52×1（某一轮 +2 笔）、56×1 → 57×1（某一轮 +1 笔）；
   *     其余 22 档逐值相同、min/中位/max 三项逐值相同。
   * </pre>
   *
   * <p>★★ <b>W7e 依据（为什么这次分布动了 3 笔，逐条具名）</b>：
   *
   * <ol>
   *   <li><b>① 跑商可行性判据从"自备牲口"换成"劳动 + 工具"</b>（用户裁定 v3.28 §7「**能不能先把牲口要求取消掉啊？目前是在跑测试，
   *       算作工具需求得了**」）：{@code ExpectedProfitBook.merchantProspect} 的候选集与拒绝理由随之改变（{@code
   *       no-carrier-asset} → {@code no-labor} / {@code no-tool-input}）⇒ 迁移轮的候选与排序输入变了 ⇒ 有户换了主业/换了格
   *       ⇒ 市场参与者与成交笔数跟着动；
   *   <li><b>② 作业资本那一维 CATTLE → TOOL，且规模锚刻意保持 100</b>（W7-fix 换维、W7-fix2 把 {@code TOOL_PER_UNIT} 定成
   *       {@code 12,000 ÷ 100 = 120}）：城市格 {@code trade} 的规模逐值仍是 100（产出 100,000 毫服务 / 周期、 周期工具投入
   *       10,000 毫），但**押住规模的那一维资产**从牲口换成工具 ⇒ 该产业的资产占用/租赁/随迁口径随之改变；
   *   <li><b>③ F1 归因改读全池</b>（{@code out-of-derived-radius}）与 <b>④ F2 两条理由合并到唯一拼写点</b>：两者都是**只读**面
   *       （归因与日志字段），**对成交笔数零影响** —— 如实记：它们**不是**本次 +3 笔的原因。
   * </ol>
   *
   * <p>★★ <b>为什么这不是"放宽判据"（五条牙一字未动）</b>：本次**只换期望值**（两档）； {@code BASELINE_MARKET_ROUNDS =
   * 96}、同日不得两轮、相邻间隔 ≤ 5 天、<b>零成交轮次 = 0</b>、{@code Σ逐轮 fills == MARKET_FILL 行数} 五条牙与 {@code
   * assertEveryMarketRoundTraded} 的断言体**一个字符都没改**（{@code git diff} 可核：本文件本批的改动只有本注释块与 {@link
   * #baselineFills()} 的两档计数）。
   *
   * <p>★★★ <b>两套独立装置各测一次（AGENTS §三.5）</b>：
   *
   * <ol>
   *   <li><b>装置 A（本测试自身）</b>：JUnit 进程内真 {@code Shell} + {@code AppLogCapture}，本批实跑 ⇒ 分布逐值 = 上面 W7e
   *       新指纹 （<b>Σfills = 3651、轮数 = 96、零成交轮 = 0、min/中位/max = 15/42.0/198</b>； {@code
   *       tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest'
   *       -Dsurefire.failIfNoSpecifiedTests=false} ⇒ {@code Tests run: 1, Failures: 0}， 五条牙的读数行落在
   *       {@code simos-app/target/economy-cycle-invariant/summary.txt}）；
   *   <li><b>装置 B（真世界 HTTP）</b>：W7e shaded jar（md5 {@code
   *       4180bedd2f9a9566dd880c7e9c760614}）从**创世新播**的 three-powers 世界、{@code POST /api/advance}
   *       0→360（再逐日 361→660），取同一窗口 day 0–240（96 轮）的 {@code MARKET_REPORT} 逐轮 {@code fills} ⇒
   *       <b>逐值相同</b>：96 轮 / 零成交轮 = 0 / 同日两轮 = 否 / 相邻最大间隔 = 3 天 / min/中位/max = 15/42.0/198 /
   *       <b>Σfills = 3651 == MARKET_FILL 3651 行</b> / 分布 24 档逐值相同 （读数：{@code
   *       .superpowers/sdd/2026-10-11-w7e-test-batch/results/tp360.fingerprint-0-240.txt}）。
   * </ol>
   *
   * <p>★★★ <b>W9e 测试批（2026-10-11）：第四次显式重新基线化（旧值 → 新值 + 依据）—— W9 / W9-fix 落地后按实跑真值重钉</b>。
   *
   * <pre>
   * W7e 旧指纹（本次实跑复现的第一行红 —— 与本文件原先钉的值逐值相同）：
   *   {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   *    45=8, 46=5, 47=10, 48=3, 49=1, 52=1, 53=1, 55=1, 57=1, 66=1, 151=1, 198=1}
   *   min/中位/max = 15 / 42.0 / 198，轮数 = 96，零成交轮次 = 0，Σfills = 3651
   * W9 首版（★ 踩牙的那一轮，留作对照；不是本文件钉的值）：
   *   Σfills = 2027（−44.5%），**零成交轮次 = 21**（day 38,40,43,…），min/中位/max = 0 / 24.0 / 120
   * W9e 新指纹（本批实跑真值；装置 A = 本测试自身；★ W9-fix 后"零成交轮次 = 0"恢复）：
   *   {15=1, 21=18, 22=8, 23=8, 24=8, 25=1, 27=1, 35=3, 36=14, 37=3, 38=3, 40=2,
   *    41=5, 42=8, 43=1, 44=1, 45=2, 46=3, 47=1, 48=1, 49=1, 66=1, 167=1, 193=1}
   *   min/中位/max = 15 / 35.5 / 193，轮数 = 96，**零成交轮次 = 0**，Σfills = 3337（Δ = −314，−8.6%）
   *   ★ 逐档差异（旧 → 新）：消失 26×10 / 30×1 / 52×1 / 53×1 / 55×1 / 57×1 / 151×1 / 198×1；
   *     新增 27×1 / 35×3 / 36×14 / 37×3 / 38×3 / 40×2 / 167×1 / 193×1；
   *     计数变化 21: 11→18、23: 4→8、24: 6→8、25: 5→1、41: 1→5、42: 3→8、43: 2→1、44: 10→1、
   *     45: 8→2、46: 5→3、47: 10→1、48: 3→1；未变 15×1 / 22×8 / 49×1 / 66×1。
   * </pre>
   *
   * <p>★★ <b>W9e 依据（W9 / W9-fix 的哪些改动改了世界行为，逐条具名）</b>：
   *
   * <ol>
   *   <li><b>① 转业速度 = 预期收益差驱动</b>（用户裁定 v3.30 §7 第 2 条；旧口径是 {@code max(10‰, f(债务/资产))}）：
   *       迁移的目的地、笔数与落点全变 ⇒ 市场参与者/成交笔数跟着变 —— 本次分布变动的<b>主因</b>；
   *   <li><b>② W9 首版的分母退化</b>（{@code max(1, |收益_当前|)} ⇒ 收益 ≈ 0 时打满 1000‰ ⇒ 单笔搬 199 人 = 整户迁空）在
   *       three-powers 造出 <b>21 个零成交轮</b> + Σfills 2027 + min·中位·max = 0·24.0·120；
   *   <li><b>③ W9-fix 的分母修正</b>（{@code max(|当前|, |其他|, 收益规模基准)}；基准 = max(每单位规模劳动的毛收入, 口粮成本)）——
   *       <b>这是把肇事链按死的那一件</b>：零收益户 1000‰ ⇒ ≤ 100‰ ⇒ <b>零成交轮次 21 → 0</b>、 Σfills 2027 → 3337；
   *   <li><b>④ W9-fix 的人口闸</b>（{@code MIGRATION_SPEED_CAP_PER_MILLE = 100} ⇒ 每计划轮每户 ≤ max(1,
   *       ⌊人口×100÷1000⌋)）—— 主要在 {@code 收益_当前 &lt; 0} 的反号支吃紧（那里分母修正不减小速度）： 把"一轮搬空整户"的上界钉在人口的 10%；
   *   <li><b>⑤ W9-fix 的 U1（源户候选表与并入目标表分开）与 U5（规模 = 0 ⇒ 最低档）</b>：迁入落点从"新建户"改回 "并入既有户"、极小户不再被抬到最高档 ⇒
   *       目标格的人/货/单位落点改变 ⇒ 逐轮流量的分布改变。
   * </ol>
   *
   * <p>★★ <b>为什么这不是"放宽判据"（五条牙一字未动）</b>：本次<b>只换期望值</b>（分布 + min/中位/max）； {@code
   * BASELINE_MARKET_ROUNDS = 96}、同日不得两轮、相邻间隔 ≤ 5 天、<b>零成交轮次 = 0</b>、 {@code Σ逐轮 fills ==
   * MARKET_FILL 行数} 五条牙与 {@code assertEveryMarketRoundTraded} 的断言体 <b>一个字符都没改</b>。★★ 特别记一句：<b>W9
   * 首版曾把"零成交轮次 = 0"这条牙踩红 21 轮</b>（W9d 实测），按纪律 当时<b>拒绝重基线</b>（重基线等于把"市场冻结"洗白）；W9-fix
   * 修好后，本批才按实跑真值重基线。
   *
   * <p>★★ <b>装置与读数（本批实跑）</b>：装置 A = 本测试自身（{@code tools/mvn-lock.sh test -pl simos-app -am
   * -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false}，本批实跑 {@code Tests run:
   * 1}）； 四个数另由<b>原始日志独立重算</b>（不经测试代码）：96 行 {@code MARKET_REPORT} ⇒ 轮数 96 / 零成交 0 / Σfills 3337 ==
   * {@code MARKET_FILL} 3337 行 / min·中位·max = 15·35.5·193 / 同日两轮 = 否 / 相邻最大间隔 = 3 天。证据落 {@code
   * .superpowers/sdd/2026-10-11-w9e-test-batch/evidence/}。
   *
   * <p>★★★ <b>W5.5-fix3 的诊断结论：这里没有非确定性 —— 曾经读到的"第二个形态"是 {@code target/classes} 陈旧造成的另一份编译产物</b>
   * （权威：W5.5-fix3 账本 §②；纪律：AGENTS §三.5「"同一个输入跑出两个结果"的第一嫌疑是产物陈旧，不是非确定性」）。诊断链： ① 那个"第二形态"与 W5.5-fix2
   * <b>之前</b>的读数<b>逐值相同</b> ⇒ 它是"fix2 的创世粮没生效"的世界； ② <b>判别实验（决定性）</b>：把 <b>pre-fix2</b> 的 {@code
   * ThreePowersGovBootstrap.class} 放在 classpath 最前（其余全用当前字节）⇒ 分布<b>当场变成那一族</b>；③ 反面对照：同 JVM
   * 内先后两个世界<b>逐字节相同</b>（排除逐对象 identity hash）、 {@code ImmutableCollections.SALT32L} 钉 12 个盐的实验与
   * identity-hash 位移 12 个步数的实验<b>全部落同一形态</b>（排除盐化迭代序与哈希序）、 独立 JVM 采样 16 次全等 ⇒ 无运行期非确定源。修完后 56 次实跑（含
   * 5 次单类 + 3 次合并命令）<b>全部落在这一个形态</b> ⇒ 守卫<b>收回单一期望</b>（逐值指纹 + min/中位/max），任何第三个值即红。
   *
   * <p>★ <b>没动的那条牙</b>：{@code assertEveryMarketRoundTraded} 的"<b>零成交轮次 = 0</b>"与三条读侧自检 （轮次数 96 /
   * 同日不得两轮 / 相邻轮间隔 ≤ 5 天）<b>一字未动</b>；{@code Σfills == MARKET_FILL 行数}的交叉验证也原样保留。 ★ 实测命令与读数见账本
   * {@code .superpowers/sdd/2026-10-11-w5.5-test-batch/impl-ledger.md} 的 {@code # W5.5e} 节。
   */
  private static final FillRegime BASELINE_FILLS = baselineFills();

  /** 本世界 96 轮市场成交笔数分布的<b>单一</b>实测基线（指纹 + min/中位/max 三个读数）。 */
  private record FillRegime(
      Map<Long, Integer> distribution, long minFills, double medianFills, long maxFills) {

    FillRegime {
      // ★ 保序不可变（禁 Map.copyOf：迭代序不是内容的纯函数；I7）。
      distribution = Collections.unmodifiableMap(new TreeMap<>(distribution));
    }
  }

  /** ★ 本世界 96 轮成交笔数分布（min/中位/max = 15 / 35.5 / 193；96 轮的中间两项是 35 与 36）。 */
  private static FillRegime baselineFills() {
    Map<Long, Integer> distribution = new TreeMap<>();
    distribution.put(15L, 1);
    distribution.put(21L, 18);
    distribution.put(22L, 8);
    distribution.put(23L, 8);
    distribution.put(24L, 8);
    distribution.put(25L, 1);
    // ★ W9e（2026-10-11）：26×10 / 30×1 消失（26 与 30 两档在本世界不再出现），27×1 / 35×3 新增。
    distribution.put(27L, 1);
    distribution.put(35L, 3);
    distribution.put(36L, 14);
    distribution.put(37L, 3);
    distribution.put(38L, 3);
    distribution.put(40L, 2);
    distribution.put(41L, 5);
    distribution.put(42L, 8);
    distribution.put(43L, 1);
    distribution.put(44L, 1);
    distribution.put(45L, 2);
    distribution.put(46L, 3);
    distribution.put(47L, 1);
    distribution.put(48L, 1);
    distribution.put(49L, 1);
    // ★ W9e（2026-10-11）：52×1 / 53×1 / 55×1 / 57×1 消失；66×1 未变。
    distribution.put(66L, 1);
    // ★ W9e（2026-10-11）：151×1 / 198×1 消失，167×1 / 193×1 新增（长尾上限 198 ⇒ 193）。
    distribution.put(167L, 1);
    distribution.put(193L, 1);
    return new FillRegime(distribution, 15L, 35.5, 193L);
  }

  /** ★ W4b 加牙：市场例行轮的最大间隔（天）—— 用它守"读侧没漏采轮次"（每 5 天必有一轮例行开市）。 */
  private static final long MAX_MARKET_ROUND_GAP_DAYS = 5L;

  /**
   * ★ 独立跑法入口（**不是 JUnit 用例** —— surefire 的缺省 include 只收「Test 前缀 / Test 后缀 / Tests /
   * TestCase」四种类名，本类名不匹配 ⇒ 门禁永远不会跑到它；只有 {@code tools/multitick-sim.sh} 会调它）。
   *
   * @param storeDir 本跑法**自己的**独立 store（{@code test-world/multitick/economy-cycle-health/store}；空库 ⇒
   *     真创世）
   * @param summaryFile 人可读摘要落盘位置（不参与断言）
   */
  public static void run(Path storeDir, Path summaryFile) throws IOException {
    ShellConfig config =
        new ShellConfig(
            storeDir,
            ShellConfig.DEFAULT_CHECKPOINT_INTERVAL,
            0,
            0,
            ShellConfig.DEFAULT_MCP_PATH,
            ShellConfig.DEFAULT_MCP_INITIATOR,
            MAP_ID,
            ShellConfig.DEFAULT_BIND_ADDRESS,
            ShellConfig.DEFAULT_OPENING_SNAPSHOT,
            WorldRegistry.THREE_POWERS);
    // ★★ W4b 加牙的读数来源：{@code MARKET_REPORT} / {@code MARKET_FILL} 都发在 **settlement** 分类
    //   （{@code EconomySettlement} 的 {@code TRACE = EconomyLog.settlement()}）—— 不是 {@code .trace}
    // 那一档。
    //   ★ 首轮实测踩过：挂错 logger ⇒ 采到 0 轮，被下面的"轮次数 == 96"自检当场拦下（判据没有静默变弱）。
    try (AppLogCapture settlementLog =
            AppLogCapture.logger(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.DEBUG);
        // ★ 逐笔 {@code MARKET_FILL} 是 **TRACE** 档、发在 {@code .trace} 分类上（交叉验证要用它）。
        AppLogCapture traceLog = AppLogCapture.logger(EconomyLog.TRACE_LOGGER_NAME, Level.TRACE);
        Shell shell = Shell.start(config)) {
      assertThat(GovGenesisSeedAccess.seed(shell))
          .as("空库创世必须成功（worldId=%s）", WorldRegistry.THREE_POWERS)
          .isTrue();
      SimulationState genesis = replay(shell);
      assertThat(tickOf(genesis)).as("创世 tick").isZero();

      Reading genesisReading = read(genesis, shell, config.mapId());
      // ★ 非空转守卫：货币守恒若在"总量 = 0"的世界里断言，等于什么都没验。
      assertThat(genesisReading.moneyByCurrency().values())
          .as("创世每个币种的货币总量都必须 > 0（否则守恒断言是空转）：实得 %s", genesisReading.moneyTotalsText())
          .isNotEmpty()
          .allMatch(total -> total > 0L);
      assertThat(genesisReading.population()).as("创世人口必须 > 0").isPositive();
      assertThat(genesisReading.moneyByCurrency().keySet())
          .as(
              "three-powers 创世必须有 3 种货币（少一种 ⇒ 守恒覆盖面缩水，须当场可见）：实得 %s",
              genesisReading.moneyTotalsText())
          .hasSize(3);

      long initialPopulation = genesisReading.population();
      Map<CurrencyId, Long> genesisMoney = genesisReading.moneyByCurrency();

      List<String> summary = new ArrayList<>();
      summary.add(
          "=== 经济循环体检：world="
              + WorldRegistry.THREE_POWERS
              + " mapId="
              + config.mapId()
              + " · "
              + SEGMENTS
              + " 段 × "
              + SEGMENT_DAYS
              + " 天 = "
              + TOTAL_DAYS
              + " 天 = 2 个产业周期（CYCLE_DAYS="
              + CYCLE_DAYS
              + "）===");
      summary.add("单位：粮/布/债/货币 = 毫（最小定点单位）；人口 = 人。不变量①：逐币种货币总量全程 == 创世基线（下方每行应与创世行逐值相同）。");
      appendRow(summary, "创世", genesisReading);

      long previousPopulation = initialPopulation;
      long previousRevision = genesisReading.revision();
      for (int segment = 1; segment <= SEGMENTS; segment++) {
        long to = segment * SEGMENT_DAYS;
        advance(shell, to - SEGMENT_DAYS, to, segment);
        SimulationState state = replay(shell);
        Reading reading = read(state, shell, config.mapId());

        // ── ⑤ 账目闭合：段末 tick 与 revision 单调递增（无静默跳过） ──
        assertThat(reading.tick())
            .as("第 %d 段：段末 tick 必须等于目标 tick（时间推进被吞会在这里现形）", segment)
            .isEqualTo(to);
        assertThat(reading.revision())
            .as("第 %d 段：head revision 必须严格递增（前值 %d）", segment, previousRevision)
            .isGreaterThan(previousRevision);
        previousRevision = reading.revision();

        appendRow(summary, "段" + segment, reading);
        writeSummary(summary, summaryFile);

        // ── ② 非负 ──
        assertThat(reading.population()).as("第 %d 段（tick=%d）：人口必须 > 0", segment, to).isPositive();
        assertThat(reading.grainMilli())
            .as("第 %d 段（tick=%d）：粮存量不得为负（账上存量）", segment, to)
            .isNotNegative();
        assertThat(reading.clothMilli())
            .as("第 %d 段（tick=%d）：布存量不得为负（账上存量）", segment, to)
            .isNotNegative();
        assertThat(reading.debtPrincipalMilli())
            .as("第 %d 段（tick=%d）：债务本金不得为负", segment, to)
            .isNotNegative();

        // ── ① 货币守恒（最硬一条）：逐币种与创世基线逐值相同 ──
        assertThat(reading.moneyByCurrency())
            .as(
                "第 %d 段（tick=%d）：逐币种货币总量必须与创世基线逐值相同（凭空造币/静默烧币都会在这里现形）。" + "创世=%s 本段=%s",
                segment, to, moneyText(genesisMoney), reading.moneyTotalsText())
            .isEqualTo(genesisMoney);

        // ── ③ 人口不爆不灭：>0（上面已断）且相邻段比值在 [1/2, 2] ──
        assertThat(reading.population() * POPULATION_BAND_DENOMINATOR)
            .as(
                "第 %d 段（tick=%d）：人口不得暴跌（下界 1/2）：上段=%d 本段=%d",
                segment, to, previousPopulation, reading.population())
            .isGreaterThanOrEqualTo(previousPopulation * POPULATION_BAND_NUMERATOR);
        assertThat(reading.population())
            .as(
                "第 %d 段（tick=%d）：人口不得暴涨（上界 2 倍）：上段=%d 本段=%d",
                segment, to, previousPopulation, reading.population())
            .isLessThanOrEqualTo(previousPopulation * 2L);
        assertThat(reading.population() * POPULATION_BAND_DENOMINATOR)
            .as(
                "第 %d 段（tick=%d）：人口不得相对创世暴跌（下界 1/2）：创世=%d 本段=%d",
                segment, to, initialPopulation, reading.population())
            .isGreaterThanOrEqualTo(initialPopulation * POPULATION_BAND_NUMERATOR);
        assertThat(reading.population())
            .as(
                "第 %d 段（tick=%d）：人口不得相对创世暴涨（上界 2 倍）：创世=%d 本段=%d",
                segment, to, initialPopulation, reading.population())
            .isLessThanOrEqualTo(initialPopulation * 2L);
        previousPopulation = reading.population();

        // ── ④ 市场在运转：当日有报告、且成交笔数 > 0（"市场冻结"防线） ──
        assertThat(reading.market())
            .as("第 %d 段（关账日 tick=%d）：必须读到当日的市场报告 —— 读不到就是市场没有开市（冻结）", segment, to)
            .isPresent();
        MarketReport report = reading.market().orElseThrow();
        assertThat(report.day())
            .as(
                "第 %d 段（关账日 tick=%d）：读到的市场报告必须是当日轮次（报告日=%d ⇒ 否则是旧报告，等于当天没开市）",
                segment, to, report.day())
            .isEqualTo(to);
        assertThat(report.fills())
            .as(
                "第 %d 段（关账日 day=%d，trigger=%s）：成交笔数必须 > 0 —— 0 笔就是历史缺陷「市场冻结」复发（未成交归因：%s）",
                segment, to, report.trigger(), reading.unfilledTop3Text())
            .isNotEmpty();
      }

      // ── ⑤ 账目闭合（收口）：段数 = 段数 ──
      assertThat(summary)
          .as("摘要行数 = 1 表头 + 1 说明 + 1 创世 + %d 段（少一行说明有段被静默跳过）", SEGMENTS)
          .hasSize(2 + 1 + SEGMENTS);

      // ── ④′ W4b 加牙：逐市场轮（不只段末）的"市场冻结"守卫 + 基线分布指纹 ──
      List<String> marketLogLines = new ArrayList<>(settlementLog.messages());
      marketLogLines.addAll(traceLog.messages());
      summary.addAll(assertEveryMarketRoundTraded(marketLogLines));

      summary.add(
          "=== 体检通过："
              + SEGMENTS
              + " 段 / "
              + TOTAL_DAYS
              + " 天，5 组不变量全部成立（货币守恒 · 非负 · 人口不爆不灭 · 市场在运转 · 账目闭合）"
              + " + W4b 加牙："
              + BASELINE_MARKET_ROUNDS
              + " 个市场轮逐轮零成交 = 0 且分布指纹与实测基线逐值相同（单一形态；第三个值即红）===");
      writeSummary(summary, summaryFile);
      summary.forEach(System.out::println);
    }
  }

  // ── W4b 加牙：逐市场轮的"市场冻结"守卫 + 基线分布 ─────────────────────────────────────

  /**
   * ★★★ <b>W4b 加牙：逐市场轮断言"零成交轮次 = 0"+ 钉住实测分布指纹</b>（判据出处：设计书 v3.14 §7「老守卫加牙（测试批）」+ §6.3 判据 3 的负向面；W4
   * 验收报告 §2.9 给的实测分布）。
   *
   * <p>★★ <b>它比原来的"每段成交 &gt; 0"硬在哪</b>：旧断言只在 8 个段末（day 30/60/…/240）取样 —— 它必须**恰好**在段末撞上零成交轮才会红。 W4
   * 初版的坏法（买侧"预先切分"配给）实测 96 轮里 15 轮零成交，但零成交轮只有 day 90 落在段末 ⇒ 段 1/段 2 各自"恰好剩 1 笔"就放行了；
   * 换成另一份时序（零成交轮全在非段末日）时它**全绿**。本方法扫**每一轮**（用 {@code MARKET_REPORT} 的逐轮读数）， 并把整份分布指纹钉住 ⇒ "任何一轮塌成
   * 0"或"任何一轮的笔数变了"都当场红。
   *
   * <p>★ <b>读数来源与自检</b>：{@code MARKET_REPORT} 逐轮一条（DEBUG），字段 {@code day} / {@code fills}；本方法自带三条自检，
   * 防止"读侧漏采"把判据悄悄变弱：① 轮次数 == {@link #BASELINE_MARKET_ROUNDS}；② 同一天不得有两轮；③ 相邻两轮的间隔 ≤ {@link
   * #MAX_MARKET_ROUND_GAP_DAYS}（市场每 5 天必有一轮例行开市）；④ 逐轮 {@code fills} 之和 == {@code MARKET_FILL}
   * 逐笔行数（两处独立读数交叉验证）。
   *
   * @return 给人看的分布摘要行（追加进 {@code summary.txt}）
   */
  private static List<String> assertEveryMarketRoundTraded(List<String> logLines) {
    List<MarketRound> rounds = marketRounds(logLines);
    long fillLines =
        logLines.stream().filter(line -> "MARKET_FILL".equals(eventNameOf(line))).count();

    // ── 自检：读到的轮次是不是"全部轮次" ──
    assertThat(rounds)
        .as("读到的市场轮次数必须 == 基线 %d（少了 ⇒ 读侧漏采，后面的判据会静默变弱；多了 ⇒ 市场触发口径变了）", BASELINE_MARKET_ROUNDS)
        .hasSize(BASELINE_MARKET_ROUNDS);
    assertThat(rounds)
        .extracting(MarketRound::day)
        .as("同一天不得有两轮（一天一轮是市场机制的结构事实）")
        .doesNotHaveDuplicates();
    for (int index = 1; index < rounds.size(); index++) {
      long gap = rounds.get(index).day() - rounds.get(index - 1).day();
      assertThat(gap)
          .as(
              "相邻市场轮的间隔必须 ≤ %d 天（例行轮 day%%5==0；间隔更大说明有轮次没被读到）：前轮 day=%d 后轮 day=%d",
              MAX_MARKET_ROUND_GAP_DAYS, rounds.get(index - 1).day(), rounds.get(index).day())
          .isBetween(1L, MAX_MARKET_ROUND_GAP_DAYS);
    }

    // ── ① 零成交的市场轮次数 = 0（本批加的那颗牙） ──
    List<MarketRound> frozen = rounds.stream().filter(round -> round.fills() == 0L).toList();
    assertThat(frozen)
        .as(
            "★ 零成交的市场轮次数必须 = 0 —— 出现零成交轮就是历史缺陷「市场冻结」复发（该轮没有一笔成交）。" + "零成交轮明细=%s；全部分布=%s",
            frozen.stream().map(round -> "day" + round.day()).toList(), distributionText(rounds))
        .isEmpty();

    // ── ② 基线分布指纹（逐值钉住；不是区间、不是下界） ──
    //   ★★ W5.5e（2026-10-11）：守卫**收回单一形态**。早前"两个已实测形态之一"的折中是 W5.5d 在
    //   "疑似非确定性"下写的；W5.5-fix3 的判别实验证明那是 **target/classes 陈旧**（另一份编译产物），
    //   不是运行期非确定性（证据与实验见 BASELINE_FILLS 的注释）⇒ 现在整份分布 + min/中位/max 逐值 isEqualTo。
    Map<Long, Integer> actualDistribution = distributionOf(rounds);
    assertThat(actualDistribution)
        .as(
            "逐市场轮成交笔数的分布必须与实测基线**逐值相同**（任何一轮的笔数变了都当场红；" + "世界行为有意改变时必须显式重新基线化，不许放宽）。基线=%s 实测=%s",
            distributionText(BASELINE_FILLS.distribution()), distributionText(rounds))
        .isEqualTo(BASELINE_FILLS.distribution());

    List<Long> sorted = rounds.stream().map(MarketRound::fills).sorted().toList();
    double median = (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
    assertThat(sorted.get(0))
        .as("最小成交笔数（基线 %d；实测分布=%s）", BASELINE_FILLS.minFills(), distributionText(rounds))
        .isEqualTo(BASELINE_FILLS.minFills());
    assertThat(median)
        .as("中位成交笔数（基线 %s）", BASELINE_FILLS.medianFills())
        .isEqualTo(BASELINE_FILLS.medianFills());
    assertThat(sorted.get(sorted.size() - 1))
        .as("最大成交笔数（基线 %d）", BASELINE_FILLS.maxFills())
        .isEqualTo(BASELINE_FILLS.maxFills());

    // ── ③ 交叉验证：逐轮 fills 之和 == 逐笔 MARKET_FILL 行数（两个独立读数同源） ──
    long totalFills = rounds.stream().mapToLong(MarketRound::fills).sum();
    assertThat(totalFills)
        .as("Σ 逐轮 fills 必须 == MARKET_FILL 逐笔行数（对不上说明两处读数之一漏了/多了）")
        .isEqualTo(fillLines);

    List<String> evidence = new ArrayList<>();
    evidence.add(
        "★ W4b 加牙（逐市场轮）：轮次="
            + rounds.size()
            + " 零成交轮次="
            + frozen.size()
            + " min/中位/max="
            + sorted.get(0)
            + "/"
            + median
            + "/"
            + sorted.get(sorted.size() - 1)
            + " Σfills="
            + totalFills
            + "（== MARKET_FILL "
            + fillLines
            + " 行）");
    evidence.add("★ W4b 加牙（分布 笔数:轮数）：" + distributionText(rounds));
    return evidence;
  }

  /** 一个市场轮的读数（只从 {@code MARKET_REPORT} 日志行解析）。 */
  private record MarketRound(long day, long fills) {}

  /** 逐轮读数（按 day 升序；同日多条 = 上游异常，交由断言暴露）。 */
  private static List<MarketRound> marketRounds(List<String> logLines) {
    List<MarketRound> rounds = new ArrayList<>();
    for (String line : logLines) {
      if (!"MARKET_REPORT".equals(eventNameOf(line))) {
        continue;
      }
      rounds.add(new MarketRound(longFieldOf(line, "day"), longFieldOf(line, "fills")));
    }
    rounds.sort(Comparator.comparingLong(MarketRound::day));
    return List.copyOf(rounds);
  }

  /** 事件名（{@code event=NAME} 那个 token；没有该字段 ⇒ 空串）。 */
  private static String eventNameOf(String line) {
    String marker = "event=";
    int at = line.indexOf(marker);
    if (at < 0) {
      return "";
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      end++;
    }
    return line.substring(start, end);
  }

  /** 从事件行里取一个 long 字段；字段缺失或不是整数 ⇒ 当场抛（★ 不静默当 0 —— 那会让判据变钝）。 */
  private static long longFieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new IllegalStateException("日志行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length()
        && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
      end++;
    }
    if (end == start) {
      throw new IllegalStateException("日志行的字段不是整数 " + marker + " : " + line);
    }
    return Long.parseLong(line.substring(start, end));
  }

  /** 分布（笔数 → 轮次数；键升序）。 */
  private static Map<Long, Integer> distributionOf(List<MarketRound> rounds) {
    Map<Long, Integer> distribution = new TreeMap<>();
    for (MarketRound round : rounds) {
      distribution.merge(round.fills(), 1, Integer::sum);
    }
    return distribution;
  }

  /** 分布的可读形态：{@code 17×10, 18×1, …}（笔数升序）。 */
  private static String distributionText(List<MarketRound> rounds) {
    return distributionText(distributionOf(rounds));
  }

  /** 分布的可读形态（指纹 Map 版；键已按笔数升序 ⇒ 打印稳定）。 */
  private static String distributionText(Map<Long, Integer> distribution) {
    StringBuilder text = new StringBuilder();
    for (Map.Entry<Long, Integer> entry : distribution.entrySet()) {
      if (text.length() > 0) {
        text.append(", ");
      }
      text.append(entry.getKey()).append('×').append(entry.getValue());
    }
    return text.toString();
  }
}
