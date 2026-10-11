package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * ★★★ <b>W7（2026-10-11）判据：非城市格能长出跑商 ——「按需创建模板、不凭空造资产」</b>（测试批按判据写，不按代码反推）。
 *
 * <p>★★ <b>判据出处</b>：
 *
 * <ol>
 *   <li><b>用户原话（逐字）</b>：「<b>跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？</b>」、「难道没有家户自动估算其他生产方式的利润，
 *       然后自动设置往其他所需要投入更低、利润更高的家户跑的机制吗？」（{@link MerchantTradeTemplate} 类注同引）；
 *   <li><b>W7 派单 ③</b>（本批任务书）：非城市格可长出跑商 —— 规划/执行按需创建 {@code trade@hex} 模板（同 revision 落变更集），
 *       作业资本<b>只能自备/随迁</b>（不凭空造），带不来 ⇒ INFO {@code ONDEMAND_TRADE_MIGRATION_SKIPPED}；
 *   <li><b>W7 派单 ⑤</b>：具名失败可观测（{@code NO_MERCHANT_CAPACITY:*} 的**理由**必须能在读数面读到）；
 *   <li><b>设计书 §6.3 判据 1</b> 的「<b>守恒式</b>」关键项 + 判据 3 的负向用例。
 * </ol>
 *
 * <p>★★★ <b>W7-fix（2026-10-11）：可行性判据 = 劳动 + 工具（牲口门槛已按用户裁定取消）</b>。
 *
 * <p>★★ <b>用户原话（最高权威，逐字）</b>：「<b>能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了</b>」（设计书 v3.28 §7）。
 *
 * <pre>
 * 决策层判据（{@link ExpectedProfitBook} 的 {@code merchantInputFailureReason}，唯一拼写点）：
 *   参与率折算后的劳动 &le; 0   ⇒ {@code NO_MERCHANT_CAPACITY:no-labor}
 *   手上的 {@code tool} 商品账 &le; 0 ⇒ {@code NO_MERCHANT_CAPACITY:no-tool-input}
 *   两者都 &gt; 0                ⇒ 这条闸放行（不再看 CATTLE：{@code no-carrier-asset} 已删）
 * 执行层判据（{@link ModeMigrationSettlement} 的 {@code filterOnDemandMerchantMoves}）：
 *   这一笔能随迁携带的作业资本（{@link MerchantTradeTemplate#capacityKinds()} = {TOOL}）≤ 0
 *   ⇒ 整笔跳过、具名 INFO {@code ONDEMAND_TRADE_MIGRATION_SKIPPED} + {@code no-tool-input-to-host-on-demand-trade-industry}
 * </pre>
 *
 * <p>★★★ <b>本批（W7e 测试批）的迁移记录：旧口径 → 新口径，逐条具名（§一.11：只在断言「旧机制本身」时删除）</b>：
 *
 * <table>
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧（W7 测试批写的）</th><th>新（本批）</th><th>依据</th></tr>
 *   <tr><td>{@code prospectWithoutTradeIndustryAndWithoutCattleIsInfeasibleByName}：断言 {@code
 *       NO_MERCHANT_CAPACITY:no-carrier-asset}</td>
 *       <td>{@link #prospectWithoutLaborIsInfeasibleByName()}（{@code no-labor}）+ {@link
 *       #prospectWithoutToolInputIsInfeasibleByName()}（{@code no-tool-input}）</td>
 *       <td>旧理由 {@code no-carrier-asset} **已从 main 删除**（W7-fix §一.11）；判据换成劳动 + 工具两维，故拆成两条**各自具名**的负向
 *           —— 只把断言改宽（例如仍断言 {@code NO_MERCHANT_CAPACITY} 前缀）会失去"卡在哪一道"的判别力</td></tr>
 *   <tr><td>{@code prospectWithSelfSuppliedCattlePassesTheCarrierAssetGate}：自备 CATTLE ⇒ 闸放行</td>
 *       <td>{@link #prospectWithLaborAndToolPassesTheInputGate()}：劳动 + 工具 ⇒ 闸放行</td>
 *       <td>用户裁定：CATTLE 不再是门槛（也不再是运力来源）</td></tr>
 *   <tr><td>{@code :182} 断言 {@code capacityPerUnit} 含 {@code CATTLE_PER_UNIT}（**编译不过**：常量已删）</td>
 *       <td>{@link #merchantTradeTemplateIsPureAndHasNoCattleField()}：含 {@code {TOOL: TOOL_PER_UNIT}}、且配方里**任何一处都没有
 *           CATTLE 键**</td>
 *       <td>{@code MerchantTradeTemplate.CATTLE_PER_UNIT} 已删（§一.11）；旧机制本身（牲口作业资本）已不存在，故删除该断言而不是改写它</td></tr>
 *   <tr><td>{@code :201}/{@code :250}/{@code :323} 执行层：能带多少 <b>CATTLE</b> ⇒ 建商号 / 跳过</td>
 *       <td>{@link #migrationWithoutCarriedToolInputIsSkippedByNameAndCreatesNothing()}、{@link
 *       #migrationWithCarriedToolInputCreatesTheTemplateAndConservesTool()}、{@link
 *       #fullMoveCarriesAllToolInputAndKeepsTheTotalUnchanged()}：能带多少 <b>TOOL 份额</b>（守恒式随之从 CATTLE 换成 TOOL）</td>
 *       <td>判据改读 {@link MerchantTradeTemplate#capacityKinds()}（= TOOL，唯一拼写点）；守恒对象随作业资本那一维改变</td></tr>
 *   <tr><td>理由串 {@code no-carrier-asset-to-host-on-demand-trade-industry}</td>
 *       <td>理由串 {@code no-tool-input-to-host-on-demand-trade-industry}（字段 {@code carriedCattle → carriedToolInput}）</td>
 *       <td>W7-fix 改名（§一.11 不留旧名）—— 本类**加断言**"旧字段名不得再出现"，把这次改名钉住</td></tr>
 * </table>
 *
 * <p>★★★ <b>本类里两类断言的分工（别读混）</b>：
 *
 * <ul>
 *   <li><b>决策层</b>（{@link ExpectedProfitBook#prospect}）：非城市格、无 {@code trade} 模板时，缺劳动 /
 *       缺工具**各自具名**；两者齐备 ⇒ 这条闸放行（理由不再是 {@code NO_MERCHANT_CAPACITY:*}）；
 *   <li><b>执行层</b>（{@link ModeMigrationSettlement#apply}）：带不来作业资本（TOOL 份额）的按需笔**整笔跳过**（具名
 *       INFO、不建商号、不搬人、不搬资产）； 带得来 ⇒ 建 {@code trade@hex} 并落同一份模板 —— 且 <b>TOOL 总量逐值不变</b>（源减 = 目标增）。
 * </ul>
 *
 * <p>★ <b>为什么"没作业资本 ⇒ 不迁"必须真跑（而不是只读代码）</b>：执行期 {@code createEnterpriseAndProcess} 在凑不齐目标产业租赁资产时是
 * <b>当场抛</b>的（fail-closed）—— 这道闸若失效，整个 tick 会炸、世界卡死。下面的 {@link
 * #migrationWithoutCarriedToolInputIsSkippedByNameAndCreatesNothing()} 就是它的负向。
 *
 * <p>★ <b>本类只测单测面</b>；真世界里"到底有没有长出新的跑商户"由批账本的真读数回答（★ W7e 实测答案：<b>仍然没有</b> —— 卡在执行期闸门：
 * 只有城市格的"商号本金主"持有 TOOL 作业资本份额，非城市格/其它城镇跑商户带不来份额 ⇒ 逐理由计数见账本 §真读数，遗留门槛 = W7-fix2 的 E3）。
 */
class MerchantOnDemandTradeW7Test {

  private static final HexCoord H1 = new HexCoord(0, 0);

  /** 目标格：**没有** {@code trade} 模板的非城市格（本批要按需建商号的地方）。 */
  private static final HexCoord H2 = new HexCoord(1, 0);

  private static final HouseholdId SOURCE = HouseholdId.parse("hh-w7-ondemand-source");
  private static final HouseholdId NEW_TARGET = HouseholdId.parse("hh-w7-ondemand-new-target");

  private static final SocialClassId POOR = new SocialClassId("poor_peasant");
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;

  /** 源户的一个"与跑商无关"的既有产业（让世界有产业表，且**不是**目标格的那个）。 */
  private static final IndustryId FARM = IndustryHexKeys.id("farm", H1.q(), H1.r());

  private static final long MOVED_POPULATION = 40L;

  /** 源户的劳动投入（毫小时）：★ 决策层用例用 —— 「可行性 = 劳动 + 工具」的第一维；为 0 时具名 {@code no-labor}。 */
  private static final long SOURCE_LABOR_MILLI = 100_000L;

  private static final long SOURCE_POPULATION = 100L;

  /** ★ 源户手上的工具（毫工具）—— 决策层那一条闸读的是**商品账**（{@code accounts.householdGoods()}），不是产权份额。 */
  private static final long SOURCE_TOOL_GOODS_MILLI = 12_000L;

  /**
   * ★ 源户自备的<b>作业资本</b>（{@code AssetKind.TOOL} 的产权份额，毫工具）。
   *
   * <p>逐值取 {@code 12,000} = 创世给城市格"商号本金主"播的那一份（{@code
   * EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}）—— 本类**不引用**它（app 在 economy 的 enforcer
   * 禁列里，跨模块不可引用）， 那份耦合由 {@code simos-app} 侧的守卫用例 {@code MerchantTradeScaleAnchorCouplingTest} 钉住。
   */
  private static final long SOURCE_TOOL_INPUT_MILLI = 12_000L;

  // ── ① 决策层：理由具名 + 只读（不落状态）─────────────────────────────────────────────

  /**
   * ★★★ <b>判据③ 负向（第一维）：非城市格 + 无 {@code trade} 模板 + 劳动 0 ⇒ 具名 {@code
   * NO_MERCHANT_CAPACITY:no-labor}</b>。
   *
   * <p>★ 夹具里工具**是够的**（账户有 12,000 毫工具）⇒ 这一条红的唯一原因就是劳动缺 ⇒ 判别力落在"卡在哪一道"上。
   */
  @Test
  void prospectWithoutLaborIsInfeasibleByName() {
    EconomyData base = base(Map.of(), 0L);
    ExpectedProfitBook.Prospect prospect =
        ExpectedProfitBook.prospect(
            base,
            SOURCE,
            DefaultProductionModes.MERCHANT,
            H2,
            null,
            market(),
            demand(),
            Map.of(),
            Set.of(),
            accountsWithTool(SOURCE_TOOL_GOODS_MILLI),
            Map.of(),
            Map.of(),
            topology(),
            0L);

    assertThat(prospect.feasible()).as("没有劳动 ⇒ 不可行（不假装可行）").isFalse();
    assertThat(prospect.reason())
        .as("★ 具名理由必须是「没有劳动」，不是改前的「没有自备承运资产」")
        .isEqualTo("NO_MERCHANT_CAPACITY:no-labor");
    assertThat(base.industries().keySet())
        .as("★ 决策层**不落任何状态**：预估不许把 trade 模板写进世界（它只是纯函数预估）")
        .doesNotContain(MerchantTradeTemplate.industryId(H2));
  }

  /**
   * ★★★ <b>判据③ 负向（第二维）：劳动够、但手上没有工具 ⇒ 具名 {@code NO_MERCHANT_CAPACITY:no-tool-input}</b>。
   *
   * <p>★ 这一条是 W7-fix 的**新**判据：改前（W7）这里看的是"自备 CATTLE > 0"，与工具无关；用户裁定后工具成为唯一第二维。
   */
  @Test
  void prospectWithoutToolInputIsInfeasibleByName() {
    EconomyData base = base(Map.of(), SOURCE_LABOR_MILLI);
    ExpectedProfitBook.Prospect prospect =
        ExpectedProfitBook.prospect(
            base,
            SOURCE,
            DefaultProductionModes.MERCHANT,
            H2,
            null,
            market(),
            demand(),
            Map.of(),
            Set.of(),
            accounts(),
            Map.of(),
            Map.of(),
            topology(),
            0L);

    assertThat(prospect.feasible()).as("劳动够但没工具 ⇒ 不可行").isFalse();
    assertThat(prospect.reason())
        .as("★ 具名理由必须是「没有工具投入」（劳动维已过 ⇒ 不可能是 no-labor）")
        .isEqualTo("NO_MERCHANT_CAPACITY:no-tool-input");
    assertThat(base.industries().keySet())
        .as("★ 决策层仍然**不落状态**")
        .doesNotContain(MerchantTradeTemplate.industryId(H2));
  }

  /**
   * ★★★ <b>判据③ 正向（决策层）：劳动 + 工具都够 ⇒ 这条闸放行</b>（不再要求自备牲口）。
   *
   * <p>★ 断言只钉"闸放行了"（理由不再是 {@code NO_MERCHANT_CAPACITY:*}）—— 后续收益率/资产规模由既有的诚实预估决定，不是本批改的东西（不越界断言）。
   */
  @Test
  void prospectWithLaborAndToolPassesTheInputGate() {
    EconomyData base = base(Map.of(), SOURCE_LABOR_MILLI);

    ExpectedProfitBook.Prospect prospect =
        ExpectedProfitBook.prospect(
            base,
            SOURCE,
            DefaultProductionModes.MERCHANT,
            H2,
            null,
            market(),
            demand(),
            Map.of(),
            Set.of(),
            accountsWithTool(SOURCE_TOOL_GOODS_MILLI),
            Map.of(),
            Map.of(),
            topology(),
            0L);

    assertThat(prospect.reason())
        .as("★ 劳动 + 工具齐备 ⇒ 「没有投入」这条闸必须放行（改前的 no-carrier-asset 已不存在）")
        .doesNotStartWith("NO_MERCHANT_CAPACITY");
    assertThat(base.industries().keySet())
        .as("★ 决策层仍然**不落状态**（模板是纯函数；只有执行期才写工作副本）")
        .doesNotContain(MerchantTradeTemplate.industryId(H2));
  }

  /** ★★ 判据③ 的模板侧：{@link MerchantTradeTemplate} 是**纯函数**、**不带存量**（不凭空造资产），且配方里**没有 CATTLE**。 */
  @Test
  void merchantTradeTemplateIsPureAndHasNoCattleField() {
    Industry first = MerchantTradeTemplate.industry(H2);
    Industry second = MerchantTradeTemplate.industry(H2);

    assertThat(second).as("纯函数：同一格两次调用逐字段同值（决策层预估与执行期写入是同一份）").isEqualTo(first);
    assertThat(first.id()).isEqualTo(MerchantTradeTemplate.industryId(H2));
    assertThat(first.regime().value()).isEqualTo("merchant");
    assertThat(first.recipe().capacityPerUnit())
        .as("★ 作业资本那一维 = TOOL（用户裁定：牲口要求取消）——每 1 单位规模压 TOOL_PER_UNIT 毫工具")
        .containsEntry(AssetKind.TOOL, MerchantTradeTemplate.TOOL_PER_UNIT);
    assertThat(first.recipe().capacityPerUnit())
        .as("★★ §一.11：配方里一个 CATTLE 字段都不留（旧机制本身已不存在，故删旧断言而不是放宽它）")
        .doesNotContainKey(AssetKind.CATTLE);
    assertThat(first.recipe().outputPerUnit())
        .containsEntry(
            MerchantTradeTemplate.HAUL_COMMODITY, MerchantTradeTemplate.HAUL_PER_UNIT_CYCLE);
    assertThat(first.cycleInputPerUnit())
        .as("★ 周期投入只挂在 TOOL 这一维（外层分路键随作业资本一起从 CATTLE 改成 tool）")
        .containsOnlyKeys(AssetKind.TOOL);
    assertThat(first.cycleInputPerUnit().get(AssetKind.TOOL))
        .as("周期投入（工具）—— 值 100 毫/规模·周期一字未改，只是分路键改了名")
        .containsEntry(
            MerchantTradeTemplate.TOOL_COMMODITY, MerchantTradeTemplate.TOOL_MILLI_PER_UNIT_CYCLE);
    assertThat(MerchantTradeTemplate.capacityKinds())
        .as("★ 作业资本那一维的唯一拼写点 = {TOOL}（执行期随迁算式读它，不各自写死 AssetKind）")
        .containsExactly(AssetKind.TOOL);
  }

  // ── ② 执行层：带不来作业资本 ⇒ 具名跳过；带得来 ⇒ 建商号 + 守恒 ────────────────────────────

  /**
   * ★★★ <b>判据③ 负向（执行层，最关键的一条）：带不来作业资本（TOOL 份额）的按需笔必须**整笔跳过**且具名</b>。
   *
   * <p>四条同时断言：① 具名 INFO {@code ONDEMAND_TRADE_MIGRATION_SKIPPED} 发出且理由具名；② 该格**没有**长出 {@code
   * trade@hex}；③ 人**没搬**（无 outbox）；④ 资产**没动**（没有凭空造出 TOOL 份额）。
   */
  @Test
  void migrationWithoutCarriedToolInputIsSkippedByNameAndCreatesNothing() {
    EconomyData base = base(Map.of(), 0L);
    EconomySession session = new EconomySession(base);
    Map<AssetShareId, OwnershipStake> sharesBefore =
        new LinkedHashMap<>(session.sheet().assetShares());
    long toolBefore = toolTotal(sharesBefore);

    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MIGRATION_LOGGER_NAME, Level.INFO)) {
      ModeMigrationSettlement.apply(
          session,
          accounts(),
          plan(move(DefaultProductionModes.MERCHANT, MOVED_POPULATION)),
          base,
          30L);

      assertThat(log.lines())
          .as("★ 必须有一条具名 INFO（世界可读：为什么这一笔没发生）")
          .anyMatch(line -> line.contains("ONDEMAND_TRADE_MIGRATION_SKIPPED"));
      assertThat(log.lines())
          .as("★ 理由必须具名（不是静默 continue）")
          .anyMatch(line -> line.contains("reason=no-tool-input-to-host-on-demand-trade-industry"));
      assertThat(log.lines())
          .as("★ 字段名随作业资本那一维改名：旧名 carriedCattle 不得再出现（§一.11）")
          .noneMatch(line -> line.contains("carriedCattle"));
      assertThat(log.lines())
          .as("★ 也不得反过来把商号建出来（建了却带不来作业资本 = 空壳商号）")
          .noneMatch(line -> line.contains("MERCHANT_TRADE_INDUSTRY_CREATED"));
    }

    assertThat(session.sheet().industries().keySet())
        .as("① 没有按需建出 trade@H2")
        .doesNotContain(MerchantTradeTemplate.industryId(H2));
    assertThat(session.pendingPopulationTransfers()).as("② 人没搬（整笔跳过）").isEmpty();
    assertThat(session.sheet().householdEconomies().get(SOURCE).population())
        .as("③ 源户人口逐值不变")
        .isEqualTo(SOURCE_POPULATION);
    assertThat(toolTotal(session.sheet().assetShares()))
        .as("④ 作业资本总量逐值不变（0 = 0：没有凭空造工具）")
        .isEqualTo(toolBefore)
        .isZero();
  }

  /**
   * ★★★ <b>判据③ 负向的第二支（W7-fix 专有）：自备 CATTLE 份额**不再**顶用 —— 牲口不是作业资本</b>。
   *
   * <p>★ 为什么必须有这一条：用户裁定的原话是「**能不能先把牲口要求取消掉啊**」—— 若执行期闸门偷偷退回"数 CATTLE"，本类其它用例（夹具里没有
   * CATTLE）仍然全绿，这条裁定就被静默推翻了。这里给源户**只有** 8,000 毫 CATTLE 份额 ⇒ 必须与"什么都没有"同命运（整笔跳过）。
   */
  @Test
  void cattleShareDoesNotSatisfyTheToolInputGate() {
    OwnershipStake cattle = share(SOURCE, AssetKind.CATTLE, 8_000L);
    Map<AssetShareId, OwnershipStake> shares = Map.of(cattle.id(), cattle);
    EconomyData base = base(shares, 0L);
    EconomySession session = new EconomySession(base);

    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MIGRATION_LOGGER_NAME, Level.INFO)) {
      ModeMigrationSettlement.apply(
          session,
          accounts(),
          plan(move(DefaultProductionModes.MERCHANT, MOVED_POPULATION)),
          base,
          30L);

      assertThat(log.lines())
          .as("★ 只有 CATTLE 份额 ⇒ 仍然必须具名跳过（牲口已不是作业资本）")
          .anyMatch(line -> line.contains("ONDEMAND_TRADE_MIGRATION_SKIPPED"));
      assertThat(log.lines())
          .as("★ 理由仍是 no-tool-input…（不是 no-carrier-asset…：那条已从 main 删除）")
          .anyMatch(line -> line.contains("reason=no-tool-input-to-host-on-demand-trade-industry"));
    }

    assertThat(session.sheet().industries().keySet())
        .as("★ 没有按需建出 trade@H2（带不来作业资本 ⇒ 整笔跳过）")
        .doesNotContain(MerchantTradeTemplate.industryId(H2));
    assertThat(session.pendingPopulationTransfers()).as("★ 人没搬").isEmpty();
    assertThat(toolTotal(session.sheet().assetShares()))
        .as("★ 更不得凭空把 CATTLE 折算成 TOOL：作业资本总量仍是 0")
        .isZero();
  }

  /**
   * ★★★ <b>判据③ 正向（执行层）+ 四类关键项的「守恒式」：自备作业资本（TOOL 份额 12,000 毫）⇒ 按需建商号、人搬走、TOOL 总量不变</b>。
   *
   * <pre>
   * 守恒式（逐值）：源份额减少量 == 目标份额增加量 ∧ Σ TOOL 前后逐值相同
   * 不丢失        ：长出来的商号逐字段等于 MerchantTradeTemplate.industry(H2)（预估与执行同一份）
   * 取整          ：部分迁移按人口比例 floor：⌊12,000 × 40 ÷ 100⌋ = 4,800（源留 7,200）
   * </pre>
   */
  @Test
  void migrationWithCarriedToolInputCreatesTheTemplateAndConservesTool() {
    OwnershipStake sourceTool = share(SOURCE, AssetKind.TOOL, SOURCE_TOOL_INPUT_MILLI);
    Map<AssetShareId, OwnershipStake> shares = Map.of(sourceTool.id(), sourceTool);
    EconomyData base = base(shares, 0L);
    EconomySession session = new EconomySession(base);
    long toolBefore = toolTotal(session.sheet().assetShares());

    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MIGRATION_LOGGER_NAME, Level.INFO)) {
      ModeMigrationSettlement.apply(
          session,
          accounts(),
          plan(move(DefaultProductionModes.MERCHANT, MOVED_POPULATION)),
          base,
          30L);

      assertThat(log.lines())
          .as("★ 按需创建必须具名（哪一格、什么理由）")
          .anyMatch(
              line ->
                  line.contains("MERCHANT_TRADE_INDUSTRY_CREATED")
                      && line.contains("reason=on-demand-trade-for-migration-no-template"));
      assertThat(log.lines())
          .as("★ 新读数面：作业资本那一维的每单位量随字段改名（toolPerUnitMilli，§一.11 不留旧名 cattlePerUnit）")
          .anyMatch(line -> line.contains("toolPerUnitMilli="))
          .noneMatch(line -> line.contains("cattlePerUnit"));
      assertThat(log.lines())
          .as("★ 带得来作业资本 ⇒ 不该发跳过（两条读数不是一回事）")
          .noneMatch(line -> line.contains("ONDEMAND_TRADE_MIGRATION_SKIPPED"));
    }

    Industry created = session.sheet().industries().get(MerchantTradeTemplate.industryId(H2));
    assertThat(created).as("① 目标格长出了按需商号（写在工作副本里 ⇒ 随本 tick 的变更集落同一 revision）").isNotNull();
    assertThat(created)
        .as("② 它必须与决策层预估的那一份**逐字段同值**（同一拼写点 ⇒ 不可能漂开）")
        .isEqualTo(MerchantTradeTemplate.industry(H2));
    assertThat(created.recipe().capacityPerUnit())
        .as("②′ 它的作业资本那一维 = TOOL（真长出来的商号不再押在牲口上）")
        .containsOnlyKeys(AssetKind.TOOL);

    // ★★ P0（2026-10-10）后的口径：迁移执行器**只推投影账 + 瞬态 outbox**，绝不写 householdEconomies 行的
    //   人口/劳动 —— App 的 MigrationSocialBridge 才按 outbox 把 Social 真值 delta 落下去（AGENTS 模块表 economy 行）。
    //   ⇒ "人搬了"在这里的可读证据是 outbox（不是经济行人口；经济行逐值不变才是**正确**的）。
    assertThat(session.pendingPopulationTransfers()).as("③ 人搬了（这一笔真的发生）").hasSize(1);
    EconomyPopulationTransfer transfer = session.pendingPopulationTransfers().get(0);
    assertThat(transfer.source()).isEqualTo(SOURCE);
    assertThat(transfer.target()).isEqualTo(NEW_TARGET);
    assertThat(transfer.population()).as("④ 搬走的人数逐值等于计划里那一笔").isEqualTo(MOVED_POPULATION);
    assertThat(transfer.targetMode()).isEqualTo(DefaultProductionModes.MERCHANT);
    assertThat(transfer.targetHex()).isEqualTo(H2);
    assertThat(session.sheet().householdEconomies().get(SOURCE).population())
        .as("④′ 经济行人口**不被执行器旁路写**（P0 防回归：只允许推投影账/outbox）")
        .isEqualTo(SOURCE_POPULATION);

    long toolAfter = toolTotal(session.sheet().assetShares());
    long sourceLeft = toolOwnedBy(session.sheet().assetShares(), SOURCE);
    long targetGot = toolOwnedBy(session.sheet().assetShares(), NEW_TARGET);
    // ★ 本笔是**部分迁移**（搬 40 / 源户 100 人）⇒ 随迁量按同一人口比例取整（floor）：
    //   carry = ⌊12,000 × 40 ÷ 100⌋ = 4,800 毫工具；源留 7,200 毫。
    assertThat(toolAfter)
        .as("★ 守恒式：TOOL（作业资本）总量前后逐值相同（%d → %d）", toolBefore, toolAfter)
        .isEqualTo(toolBefore)
        .isEqualTo(SOURCE_TOOL_INPUT_MILLI);
    assertThat(sourceLeft)
        .as("★ 源份额同量减少（12,000 → 7,200：⌊12,000 × 40 ÷ 100⌋ = 4,800 随迁）")
        .isEqualTo(7_200L);
    assertThat(targetGot).as("★ 目标侧同量增加（0 → 4,800）").isEqualTo(4_800L);
    assertThat(toolBefore - sourceLeft)
        .as("★★ 守恒式（逐值恒等式）：源减 == 目标增（%d − %d == %d）", toolBefore, sourceLeft, targetGot)
        .isEqualTo(targetGot);
    assertThat(session.sheet().assetShares().values())
        .as("⑤ 没有「凭空多出来的」作业资本：>0 的 TOOL 份额恰好 2 条（源 7,200 + 目标 4,800）")
        .filteredOn(share -> share.asset() == AssetKind.TOOL && share.quantity() > 0L)
        .hasSize(2);
    assertThat(session.sheet().assetShares().values())
        .as("⑤′ 目标侧那份份额必须挂在**新长出来的商号**下（trade@H2），不是别的产业")
        .filteredOn(
            share ->
                share.asset() == AssetKind.TOOL
                    && share.owner().equals(HouseholdActors.of(NEW_TARGET)))
        .allSatisfy(
            share -> assertThat(share.industry()).isEqualTo(MerchantTradeTemplate.industryId(H2)));
  }

  /**
   * ★★★ <b>判据③ 正向的第二支：**整户迁空** ⇒ 全部作业资本随迁、源侧归零、总量仍逐值不变</b>。
   *
   * <p>★ 为什么它与上面那笔分开测：执行期的随迁取整有**两条分支**（{@code empties} ⇒ 全部带走；否则按人口比例 floor）——
   * 只测部分迁移会让"整户迁空"这一支**静默存活**（写码批的 D8 正是在这一族口径上出过一次自身缺陷）。
   */
  @Test
  void fullMoveCarriesAllToolInputAndKeepsTheTotalUnchanged() {
    OwnershipStake sourceTool = share(SOURCE, AssetKind.TOOL, SOURCE_TOOL_INPUT_MILLI);
    Map<AssetShareId, OwnershipStake> shares = Map.of(sourceTool.id(), sourceTool);
    EconomyData base = base(shares, 0L);
    EconomySession session = new EconomySession(base);
    long toolBefore = toolTotal(session.sheet().assetShares());

    ModeMigrationSettlement.apply(
        session,
        accounts(),
        plan(move(DefaultProductionModes.MERCHANT, SOURCE_POPULATION)),
        base,
        30L);

    long sourceLeft = toolOwnedBy(session.sheet().assetShares(), SOURCE);
    long targetGot = toolOwnedBy(session.sheet().assetShares(), NEW_TARGET);
    assertThat(sourceLeft).as("整户迁空 ⇒ 源侧一点都不剩").isZero();
    assertThat(targetGot).as("★ 12,000 毫工具全部随迁（不是按比例 floor）").isEqualTo(SOURCE_TOOL_INPUT_MILLI);
    assertThat(toolTotal(session.sheet().assetShares())).as("★ 守恒式：总量仍逐值不变").isEqualTo(toolBefore);
    assertThat(session.sheet().householdEconomies().get(SOURCE).population())
        .as("P0：经济行人口仍不被执行器写（只推 outbox）")
        .isEqualTo(SOURCE_POPULATION);
    assertThat(session.pendingPopulationTransfers()).as("整户迁移的 outbox 一笔").hasSize(1);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  /** 一条挂在 {@link #FARM} 下的产权份额（作业资本那一维由调用方指定：TOOL = 新口径，CATTLE = 负向对照）。 */
  private static OwnershipStake share(HouseholdId owner, AssetKind asset, long quantity) {
    AssetShareId id =
        OwnershipStake.idOf(
            FARM,
            asset,
            HouseholdActors.of(owner),
            HouseholdActors.of(owner),
            OwnershipStake.RightKind.OWNED,
            0L);
    return new OwnershipStake(
        id,
        FARM,
        asset,
        HouseholdActors.of(owner),
        HouseholdActors.of(owner),
        quantity,
        OwnershipStake.RightKind.OWNED);
  }

  private static long toolTotal(Map<AssetShareId, OwnershipStake> shares) {
    long total = 0L;
    for (OwnershipStake share : shares.values()) {
      if (share.asset() == AssetKind.TOOL) {
        total += share.quantity();
      }
    }
    return total;
  }

  private static long toolOwnedBy(Map<AssetShareId, OwnershipStake> shares, HouseholdId owner) {
    ActorRef actor = HouseholdActors.of(owner);
    long total = 0L;
    for (OwnershipStake share : shares.values()) {
      if (share.asset() == AssetKind.TOOL && share.owner().equals(actor)) {
        total += share.quantity();
      }
    }
    return total;
  }

  private static HouseholdEconomy row(
      HouseholdId id, HexCoord hex, long population, long laborMilli) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, POOR),
        population,
        laborMilli,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id, ProductionModeId mode) {
    ClassPositionId position =
        DefaultProductionModes.classPositions().values().stream()
            .filter(role -> role.modeId().equals(mode))
            .map(role -> role.id())
            .sorted(java.util.Comparator.comparing(ClassPositionId::value))
            .findFirst()
            .orElseThrow();
    return new HouseholdClassMembership(
        id, position, position, Set.of(), Map.of(), 0L, 0L, "w7-ondemand-test");
  }

  private static Industry farm() {
    return new Industry(
        FARM,
        "farm-w7-ondemand",
        new RegimeId("household"),
        10L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 1L),
        Map.of(),
        List.of(new ClassSlot(POOR, "peasant", 1_000)),
        new AllocationRule.Split(500, 500));
  }

  private static EconomyData base(Map<AssetShareId, OwnershipStake> shares, long laborMilli) {
    return EconomyData.empty()
        .withModes(DefaultProductionModes.modes())
        .withClassStructures(DefaultProductionModes.classStructures())
        .withProductionRoles(DefaultProductionModes.classPositions())
        .withHouseholdEconomies(Map.of(SOURCE, row(SOURCE, H1, SOURCE_POPULATION, laborMilli)))
        .withClassMemberships(
            Map.of(SOURCE, membership(SOURCE, DefaultProductionModes.FAMILY_FARM)))
        .withIndustries(Map.of(FARM, farm()))
        .withOwnershipStakes(shares)
        .withMarketTables(EconomyFixtures.hexMarkets(Map.of(H1, market())));
  }

  /** 源户的账户（新建目标户的账户由迁移执行处自己登记 —— {@code ModeMigrationSettlement} 的 create 路径）。 */
  private static AccountSession accounts() {
    return accountsWithTool(0L);
  }

  /** 源户的账户，并带上指定数量的 {@code tool} 商品账 —— ★ 决策层那一条"有没有工具"读的就是这份**商品账**（不是产权份额）。 */
  private static AccountSession accountsWithTool(long toolMilli) {
    AccountSession accounts = AccountSession.empty();
    Map<CommodityId, Long> goods =
        toolMilli > 0L ? Map.of(MerchantTradeTemplate.TOOL_COMMODITY, toolMilli) : Map.of();
    accounts.registerHousehold(SOURCE, H1, goods, Map.of(), Map.of(), Map.of());
    return accounts;
  }

  private static MarketTable market() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(GRAIN, 10L);
    return EconomyFixtures.quote(MarketSettlementFixtures.SILVER, prices);
  }

  private static MarketDemandBook.Book demand() {
    return new MarketDemandBook.Book(Map.of(), 0L, 1L);
  }

  private static MarketTopology topology() {
    return MarketTopology.singleHex(EconomyFixtures.hexMarkets(Map.of(H1, market())), Map.of());
  }

  private static ModeMigrationPolicy.MigrationPlan plan(
      ModeMigrationPolicy.MigrationMove... moves) {
    return new ModeMigrationPolicy.MigrationPlan(List.of(moves));
  }

  /** 一笔"往 H2 转成 merchant"的迁移（10 参旧构造 ⇒ targetMode 显式给）。 */
  private static ModeMigrationPolicy.MigrationMove move(
      ProductionModeId targetMode, long population) {
    return new ModeMigrationPolicy.MigrationMove(
        SOURCE,
        NEW_TARGET,
        H2,
        targetMode,
        ModeMigrationPolicy.MIGRATION_PER_MILLE,
        population,
        0L,
        0L,
        "fixture:ondemand",
        Map.of());
  }
}
