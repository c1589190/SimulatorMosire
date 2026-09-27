package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.time.EconomyOwnershipTimeParticipant;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Market;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>M0.3：逐格粮食诊断的读口验收</b>（{@link ApiViews#economyHex} 里那一栏 {@code grainDiagnosis}）。
 *
 * <p>★★ <b>判别力从哪来（每条断言都能被一行错代码弄红）</b>：
 *
 * <ul>
 *   <li><b>覆盖天数</b>用 {@code grainStock ÷ 日耗} 逐值对拍 —— 它是"这一格还能撑几天"唯一的算法， 写错分母（用整周期需求而不是日耗）会差 120 倍；
 *   <li><b>满足率 + 未满足人日</b>与 {@code FlowRow.unmetNeed} 两条**独立可算**的路对上（一条走人日反解、
 *       一条走累计需求），任一处窗口错（读成"当期"或"整周期混用"）当场红；
 *   <li><b>购买力那三项</b>必须与"钱 × 1000 ÷ 价"逐值一致，且 {@code purchasingGap} 是它的补；
 *   <li>★★ <b>三项"做不到"必须具名在 {@code unavailable} 里</b>（生产自给率 / 物流缺口 / 支付工具缺口）——
 *       这条钉的是"缺栏不许静默消失"：把某一项从表里删掉、或填成 0，本条红。
 *   <li>★ <b>窗口标注必须真的写出本周期需求</b>（{@code window} 非空且含周期需求数）—— 报数前先核窗口是本仓的纪律。
 * </ul>
 *
 * <p>★ 本用例**只读**：不推进世界之外的任何状态；窗口 = 一个整周期（第 120 天 = 关账日），因为诊断里三项是**本期累计**。
 */
class EconomyGrainDiagnosisTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final HexCoord PLAINS = new HexCoord(0, 0);

  @TempDir Path tempDir;

  @Test
  void theGrainDiagnosisReportsTheFourComputableItemsAndNamesTheThreeItCannot() {
    try (CoreSimos core = freshCore()) {
      advanceTo(core, 120L); // 关账日：本周期累计读数才是"整周期"的量
      SimulationState state = core.replay(new StateRef(MAIN, core.head(MAIN).orElseThrow()));
      EconomyData data = ((EconomySnapshot) state.module("economy").orElseThrow()).data();
      ActorData books = ((ActorSnapshot) state.module("actor").orElseThrow()).data();

      Map<String, Object> view = ApiViews.economyHex(PLAINS, data, books);
      @SuppressWarnings("unchecked")
      Map<String, Object> diagnosis = (Map<String, Object>) view.get("grainDiagnosis");
      assertThat(diagnosis).as("M0.3：economyHex 必须带上 grainDiagnosis 这一栏").isNotNull();

      long stock = ((Number) view.get("grainStock")).longValue();
      long dailyNeed = ((Number) diagnosis.get("grainDailyNeed")).longValue();
      long cycleNeed = cycleNeedAt(data);
      long unmet = unmetAt(data);
      Market market = data.markets().get(PLAINS);
      assertThat(market).as("夹具的每一格都有市场（价表来自 EconomySeeder.MARKET_FACTORY）").isNotNull();
      long price = market.prices().get(EconomyTestWorld.GRAIN);
      // ★ actorMoneyTotal 是**逐币种**的表（"跨币种求和"不是有意义的运算）⇒ 先取表、再按计价货币取值。
      @SuppressWarnings("unchecked")
      Map<String, Object> moneyTotals = (Map<String, Object>) view.get("actorMoneyTotal");
      long money = ((Number) moneyTotals.get(market.numeraire().value())).longValue();

      // ② 覆盖天数（向下取整；分母必须是**日耗**）。
      assertThat(((Number) diagnosis.get("coverageDays")).longValue())
          .as("覆盖天数 == 库存 ÷ 日耗（%d ÷ %d）", stock, dailyNeed)
          .isEqualTo(stock / dailyNeed);
      assertThat(dailyNeed).as("日耗非 0（这一格有 1000 人）").isPositive();

      // ③ 预计进口需求 == 本周期累计未满足。
      assertThat(((Number) diagnosis.get("importDemand")).longValue())
          .as("预计进口需求 == Σ行 FlowRow.unmetNeed[grain]")
          .isEqualTo(unmet);
      assertThat(unmet).as("第一周期这一格真的缺粮（否则下面的满足率是恒 1000‰）").isPositive();

      // ④ 购买力：上限、缺口、以及"钱 × 1000 ÷ 价"。
      assertThat(((Number) diagnosis.get("numeraireMoney")).longValue())
          .as("计价货币的余额与视图的 actorMoneyTotal 同源")
          .isEqualTo(money);
      assertThat(((Number) diagnosis.get("grainPrice")).longValue()).isEqualTo(price);
      long affordable = money * 1000L / price;
      assertThat(((Number) diagnosis.get("affordableGrain")).longValue())
          .as("买得起的量 == 钱 × 1000 ÷ 价")
          .isEqualTo(affordable);
      assertThat(((Number) diagnosis.get("purchasingGap")).longValue())
          .as("购买力缺口 == max(0, 未满足 − 买得起)")
          .isEqualTo(Math.max(0L, unmet - affordable));

      // ⑦ 满足率（千分）与未满足人日 —— 两条独立可算的路。
      long expectedPerMille = (cycleNeed - unmet) * 1000L / cycleNeed;
      assertThat(((Number) diagnosis.get("satisfactionPerMille")).longValue())
          .as("满足率 == (本周期需求 %d − 未满足 %d) × 1000 ÷ 需求", cycleNeed, unmet)
          .isEqualTo(expectedPerMille);
      assertThat(expectedPerMille).as("这一格真的不满足（满足率 < 1000‰）").isLessThan(1000L);
      assertThat(((Number) diagnosis.get("unmetPersonDays")).longValue())
          .as("未满足人日 == 未满足 ÷ 每人每日口粮（与满足率是同一件事的两种写法）")
          .isEqualTo(
              unmet
                  / (EconomyVocabulary.RATION_MILLI_PER_PERSON
                      / EconomyVocabulary.RATION_CYCLE_DAYS));

      // ★★ 三项做不到的**具名列出**（缺栏不许静默消失）。
      @SuppressWarnings("unchecked")
      Map<String, Object> unavailable = (Map<String, Object>) diagnosis.get("unavailable");
      assertThat(unavailable)
          .as("M0.3：今天算不出的三项必须具名列出（不是留空、更不是填 0）")
          .containsKeys("productionSelfSufficiency", "logisticsGap", "paymentInstrumentGap");
      assertThat(String.valueOf(unavailable.get("productionSelfSufficiency")))
          .as("★ 生产自给率那条要说清「为什么算不出 + 落点」（M2.7 措辞更新：ledger 当日瞬态 ⇒ 要等持久读数组件）")
          .contains("ledger")
          .contains("周期累计")
          .contains("落点");
      assertThat(String.valueOf(unavailable.get("logisticsGap")))
          .as("★ 物流缺口那条要说清「缺的是哪一份」：L2 的进程内 MarketReport 不落盘、当前读口拿不到")
          .contains("MarketReport")
          .contains("不落盘");

      // ★ 窗口标注必须把本周期需求数写出来（报数前先核窗口）。
      assertThat(String.valueOf(diagnosis.get("window")))
          .as("★ 窗口标注必须写出本周期需求（%d）—— 否则读的人不知道这是「到现在为止」还是「整周期」", cycleNeed)
          .contains(String.valueOf(cycleNeed))
          .contains("本周期累计");
    }
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  private CoreSimos freshCore() {
    CoreSimos core =
        new CoreSimos(new CoreConfig(tempDir, CHECKPOINT_INTERVAL, SimosObjectMapper.create()));
    core.register(new EconomyCodec());
    core.register(new ActorCodec());
    core.register(new EconomyOwnershipTimeParticipant(EconomyTestWorld.MAP_ID));
    core.bootstrapGenesis(EconomyTestWorld.genesis());
    return core;
  }

  /** **一次**推进到 {@code to}（一条 revision；等价性由 {@code EconomySettlementEndToEndTest} 守）。 */
  private static void advanceTo(CoreSimos core, long to) {
    long head = core.head(MAIN).orElseThrow().value();
    CommandResult result =
        core.submit(
            new AdvanceTime(
                "cmd-advance-0",
                "corr-advance-0",
                "player:test",
                MAIN,
                new RevisionId(head),
                new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(to)))));
    assertThat(result)
        .as("一次推进到第 %d 天 ⇒ 恰落一条 revision", to)
        .isEqualTo(new CommandResult.Committed(new StateRef(MAIN, new RevisionId(head + 1))));
  }

  private static List<CohortKey> keysAt(EconomyData data) {
    return data.classes().keySet().stream().filter(key -> key.hex().equals(PLAINS)).toList();
  }

  private static long cycleNeedAt(EconomyData data) {
    // ★★ M2.7/丙条：周期需求分母 = ClassRow 的逐日累加器 cycleNaturalNeedMilli（日初人口），
    //   不再用"读口时刻人口 × 整周期配额"现算（两者口径不可比，M0.3 的旧分母已退休）。
    long total = 0L;
    for (CohortKey key : keysAt(data)) {
      ClassRow row = data.classes().get(key);
      total += row.cycleNaturalNeedMilli();
    }
    return total;
  }

  private static long unmetAt(EconomyData data) {
    long total = 0L;
    for (CohortKey key : keysAt(data)) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        total += flow.unmetNeed().getOrDefault(EconomyTestWorld.GRAIN, 0L);
      }
    }
    return total;
  }
}
