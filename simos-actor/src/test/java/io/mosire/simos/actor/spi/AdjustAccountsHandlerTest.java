package io.mosire.simos.actor.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code actor.AdjustAccounts} 处理器边界（收尾期 T2）：有符号净增量、整条原子、缺账纯正新建、负增量不破余额/冻结、重复与 0 值拒、 只动 {@code
 * accounts} 一张表、变更集重建与 JSON 线往返。
 *
 * <p>★ 断言逐值：拒因点名 owner / 格 / 维度 / 数字，成功路径把余额、键序、两张冻结表与未点名键逐条钉住。 ★ "原子性"一条的判别力在于：第一条合法 entry
 * 本可单独生效，第二条违例时整条必须 {@code Rejected}，且**基态一个键都不许多**（不是"部分生效"）。
 */
class AdjustAccountsHandlerTest {

  private static final AdjustAccountsHandler HANDLER = new AdjustAccountsHandler();

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef HH1 = new ActorRef(ActorKind.HOUSEHOLD, "hh-1");
  private static final ActorRef HH2 = new ActorRef(ActorKind.HOUSEHOLD, "hh-2");

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CommodityId FIBER = new CommodityId("fiber");

  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CurrencyId COPPER = new CurrencyId("copper");

  private static final ActorMeta META = new ActorMeta("Map1", 3L, "actor-v1");

  // ── 命令面 ──────────────────────────────────────────────────────────────────────────

  @Test
  void typeIsActorAdjustAccounts() {
    assertThat(HANDLER.type()).isEqualTo("actor.AdjustAccounts");
  }

  /** ★ 裸账目原语：实现 {@link GmOnlyCommand}（禁嵌入令 / RegisterEffect / 决策人 catalog 三条路）。 */
  @Test
  void handlerIsGmOnly() {
    assertThat(HANDLER).isInstanceOf(GmOnlyCommand.class);
    assertThat(HANDLER).isInstanceOf(CommandHandler.class);
  }

  /** 目标声明 = entries[] 逐格的 {@code <q>_<r>}（与共享层的助手同源），多 entry / 多格各一条。 */
  @Test
  void targetPathsAreOnePerEntryHex() {
    String payload = payload("{\"q\":1,\"r\":2},{\"q\":-3,\"r\":4},{\"q\":0,\"r\":0}");

    assertThat(HANDLER.targetPaths("Map1", payload))
        .containsExactly(
            ResourcePaths.actor(1, 2), ResourcePaths.actor(-3, 4), ResourcePaths.actor(0, 0));
  }

  /** 判不出目标 ⇒ 抛（{@code CommandTargets} 契约：坏载荷不得静默返回空表）。 */
  @Test
  void targetPathsThrowOnBadPayload() {
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "{\"entries\":[]}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── happy：新建 / upsert / 多本账 ─────────────────────────────────────────────────────

  /** ★ 缺账 + 纯正增量 ⇒ 新建；未点名的组件（text 之外两张冻结表）为空表，meta / actors 一字不动。 */
  @Test
  void createsMissingAccountWithPurePositiveDeltasAndLeavesOtherTablesAlone() {
    ActorData base =
        ActorData.empty().withMeta(Optional.of(META)).withActor(new Actor(ESTATE, "庄园"));

    ActorData after =
        apply(
            payload(
                entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":120}", null),
                entry("HOUSEHOLD", "hh-2", 0, 0, null, "{\"silver\":30}")),
            base);

    assertThat(after.meta()).as("meta 原样").isEqualTo(base.meta());
    assertThat(after.actors()).as("actors 原样（不给 owner 建 Actor 行）").isEqualTo(base.actors());
    GoodsAccountKey grainKey = new GoodsAccountKey(HH1, H00);
    GoodsAccountKey moneyKey = new GoodsAccountKey(HH2, H00);
    assertThat(after.accounts()).containsOnlyKeys(grainKey, moneyKey);
    GoodsAccount grainAccount = after.accounts().get(grainKey);
    assertThat(grainAccount.balances())
        .as("纯正增量按载荷值落账；未点名的其余商品不存在")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 120L);
    assertThat(grainAccount.money()).as("goods-only 新建 ⇒ 货币表空").isEmpty();
    assertThat(grainAccount.frozenBalances()).as("新建账的两张冻结表为空").isEmpty();
    assertThat(grainAccount.frozenMoney()).as("新建账的两张冻结表为空").isEmpty();
    GoodsAccount moneyAccount = after.accounts().get(moneyKey);
    assertThat(moneyAccount.money())
        .as("money-only 新建 ⇒ 货币表只有点名的币种")
        .containsOnlyKeys(SILVER)
        .containsEntry(SILVER, 30L);
    assertThat(moneyAccount.balances()).as("money-only 新建 ⇒ 商品表空").isEmpty();
    assertThat(moneyAccount.frozenBalances()).isEmpty();
    assertThat(moneyAccount.frozenMoney()).isEmpty();
  }

  /**
   * ★ 已有账 upsert：**原键序保留**（余额 / 货币 / 两张冻结表各自）、两张冻结表逐值原样、余额表里的 0 保留不归一。
   *
   * <p>判别力：载荷给出的键序（fiber 在 grain 前）与账内原序（grain 在前）相反 ⇒ 实现若用载荷序重建余额表，键序断言当场红。
   */
  @Test
  void upsertsExistingAccountKeepingKeyOrderAndFrozenTables() {
    ActorData base = ActorData.empty().withAccount(baseAccount());

    ActorData after =
        apply(
            payload(
                entry("HOUSEHOLD", "hh-1", 0, 0, "{\"fiber\":2,\"grain\":-10}", "{\"copper\":7}")),
            base);

    GoodsAccount account = after.accounts().get(new GoodsAccountKey(HH1, H00));
    assertThat(new ArrayList<>(account.balances().keySet()))
        .as("原键序保留：[grain, cloth, fiber]（不是载荷顺序）")
        .containsExactly(GRAIN, CLOTH, FIBER);
    assertThat(account.balances()).containsEntry(GRAIN, 90L).containsEntry(CLOTH, 0L);
    assertThat(account.balances()).containsEntry(FIBER, 7L);
    assertThat(new ArrayList<>(account.money().keySet()))
        .as("货币表原键序保留")
        .containsExactly(SILVER, COPPER);
    assertThat(account.money()).containsEntry(SILVER, 10L).containsEntry(COPPER, 7L);
    assertThat(account.frozenBalances())
        .as("两张冻结表逐值原样带过（三参便捷构造器会静默清零）")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 40L);
    assertThat(account.frozenMoney())
        .as("货币冻结表逐值原样")
        .containsOnlyKeys(SILVER)
        .containsEntry(SILVER, 5L);
  }

  /** ★ 负增量把余额打到 0 ⇒ 键保留（0 是"现在手里是 0"，不归一成缺键）。 */
  @Test
  void keepsZeroBalanceWhenDeltaDrainsExactlyToZero() {
    ActorData base =
        ActorData.empty()
            .withAccount(new GoodsAccount(new GoodsAccountKey(HH1, H00), Map.of(GRAIN, 5L)));

    ActorData after =
        apply(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-5}", null)), base);

    GoodsAccount account = after.accounts().get(new GoodsAccountKey(HH1, H00));
    assertThat(account.balances())
        .as("0 保留、且键仍在（归一化会删掉这一条）")
        .containsOnlyKeys(GRAIN)
        .containsEntry(GRAIN, 0L);
  }

  /** ★ 一条命令同时给多本账增减：一本已有账扣减、一本缺账纯正新建。 */
  @Test
  void adjustsMultipleAccountsInOneCommand() {
    ActorData base =
        ActorData.empty()
            .withAccount(new GoodsAccount(new GoodsAccountKey(HH1, H00), Map.of(GRAIN, 100L)));

    ActorData after =
        apply(
            payload(
                entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-40}", null),
                entry("HOUSEHOLD", "hh-2", 1, 0, "{\"grain\":40}", "{\"silver\":5}")),
            base);

    assertThat(after.accounts()).hasSize(2);
    assertThat(after.accounts().get(new GoodsAccountKey(HH1, H00)).balances())
        .containsExactlyEntriesOf(Map.of(GRAIN, 60L));
    GoodsAccount created = after.accounts().get(new GoodsAccountKey(HH2, H10));
    assertThat(created.balances()).containsExactlyEntriesOf(Map.of(GRAIN, 40L));
    assertThat(created.money()).containsExactlyEntriesOf(Map.of(SILVER, 5L));
  }

  // ── 拒因：形状 / 类型 / 词表 ───────────────────────────────────────────────────────────

  @Test
  void rejectsEmptyOrMissingEntries() {
    assertThat(reason(payload(""))).contains("entries 不得为空");
    assertThat(reason("{}")).contains("字段 entries 必须是数组");
    assertThat(reason("not json")).contains("不是合法 JSON");
    assertThat(reason("[]")).contains("必须是 JSON 对象");
  }

  @Test
  void rejectsAnEntryMissingQOrR() {
    assertThat(
            reason(
                payload(
                    "{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"hh-1\"},\"r\":0,"
                        + "\"goods\":{\"grain\":1}}")))
        .contains("字段 q 必须是整数");
    assertThat(
            reason(
                payload(
                    "{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"hh-1\"},\"q\":0,"
                        + "\"goods\":{\"grain\":1}}")))
        .contains("字段 r 必须是整数");
    assertThat(reason(payload("[1]"))).as("元素不是对象").contains("元素必须是");
  }

  @Test
  void rejectsAnOwnerOutsideTheKindVocabularyOrWithABlankId() {
    assertThat(reason(payload(entry("MANOR", "hh-1", 0, 0, "{\"grain\":1}", null))))
        .contains("owner 不合法")
        .contains("MANOR");
    assertThat(reason(payload(entry("HOUSEHOLD", " ", 0, 0, "{\"grain\":1}", null))))
        .contains("owner 不合法")
        .contains("字段 id");
    assertThat(reason(payload("{\"q\":0,\"r\":0,\"goods\":{\"grain\":1}}")))
        .as("缺 owner 对象")
        .contains("字段 owner 必须是");
  }

  @Test
  void rejectsBothGoodsAndMoneyEmptyOrMissing() {
    assertThat(reason(payload(entry("HOUSEHOLD", "hh-1", 0, 0, null, null))))
        .contains("goods/money 至少一个必须非空")
        .contains("hh-1")
        .contains("0_0");
    assertThat(reason(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{}", null))))
        .contains("goods/money 至少一个必须非空");
  }

  @Test
  void rejectsZeroDeltaInEitherDimension() {
    assertThat(reason(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":0}", null))))
        .contains("的值不得为 0")
        .contains("goods.grain=0");
    assertThat(reason(payload(entry("HOUSEHOLD", "hh-1", 0, 0, null, "{\"silver\":0}"))))
        .contains("的值不得为 0")
        .contains("money.silver=0");
  }

  @Test
  void rejectsDuplicateOwnerAndHexInOnePayload() {
    assertThat(
            reason(
                payload(
                    entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":1}", null),
                    entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":2}", null))))
        .contains("同一份载荷里账目重复")
        .contains("hh-1")
        .contains("0_0");
  }

  // ── 拒因：数值语义 ──────────────────────────────────────────────────────────────────

  @Test
  void rejectsNegativeDeltaAgainstAMissingAccount() {
    assertThat(reason(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-1}", null))))
        .contains("缺账 + 负增量")
        .contains("hh-1")
        .contains("增量=-1");
    assertThat(reason(payload(entry("UNIT", "u-1", 0, 0, "{\"grain\":0}", null))))
        .as("0 优先于缺账判据（解析期先拒）")
        .contains("的值不得为 0");
  }

  @Test
  void rejectsNegativeResultBelowZero() {
    ActorData base =
        ActorData.empty()
            .withAccount(new GoodsAccount(new GoodsAccountKey(HH1, H00), Map.of(GRAIN, 50L)));

    HandlerOutcome outcome =
        HANDLER.handle(
            state(base), payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-51}", null)));

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .contains("负增量使余额 < 0")
        .contains("余额=50")
        .contains("增量=-51")
        .contains("结果 -1");
  }

  /** ★ 100 / 30 边界：可支配 = 70 ⇒ −70 放行、−71 拒（拒因把余额 / 冻结 / 可支配 / 增量一起报出来）。 */
  @Test
  void frozenBalanceBoundaryAllowsExactlyAvailableAndRejectsOneMore() {
    GoodsAccount frozen =
        new GoodsAccount(
            new GoodsAccountKey(HH1, H00),
            Map.of(GRAIN, 100L),
            Map.of(),
            Map.of(GRAIN, 30L),
            Map.of());
    ActorData base = ActorData.empty().withAccount(frozen);

    ActorData after =
        apply(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-70}", null)), base);
    GoodsAccount drained = after.accounts().get(new GoodsAccountKey(HH1, H00));
    assertThat(drained.balances()).as("−70 恰好吃掉可支配额 ⇒ 余额落到冻结额 30（不是负数）").containsEntry(GRAIN, 30L);
    assertThat(drained.frozenBalances()).as("冻结额原样").containsEntry(GRAIN, 30L);

    HandlerOutcome outcome =
        HANDLER.handle(
            state(base), payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-71}", null)));
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .contains("负增量侵占冻结额")
        .contains("余额=100")
        .contains("冻结=30")
        .contains("可支配=70")
        .contains("增量=-71");
  }

  // ── 原子性与不丢失 ───────────────────────────────────────────────────────────────────

  /**
   * ★★ **整条原子**：两条 entry，前者合法（对一个缺账纯正新建）、后者违例（缺账 + 负增量） ⇒ 返回 {@code Rejected}，且基态一个键都不多。
   *
   * <p>判别力：若实现"逐条施加、遇错才回滚"或"返回已生效的部分"，第一条那个新账就会在结果里留下 —— 本用例的那条断言当场红。故这里既查退回的结局， 也查**基态**（返回的 base
   * 本身）与"新键不出现"。
   */
  @Test
  void rejectsWholeCommandAtomicallyWithoutPartialEffect() {
    ActorData base =
        ActorData.empty()
            .withMeta(Optional.of(META))
            .withAccount(new GoodsAccount(new GoodsAccountKey(HH1, H00), Map.of(GRAIN, 10L)));

    HandlerOutcome outcome =
        HANDLER.handle(
            state(base),
            payload(
                entry("HOUSEHOLD", "hh-2", 0, 0, "{\"grain\":5}", null),
                entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-11}", null)));

    assertThat(outcome).as("任何一条违例 ⇒ 全拒，不返回半成品变更集").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因点名的是**第二条**（余额不足），不是第一条")
        .contains("负增量使余额 < 0")
        .contains("余额=10")
        .contains("增量=-11");
    assertThat(base.accounts())
        .as("基态一字未动：没有 hh-2 的新账，hh-1 仍是 10")
        .containsOnlyKeys(new GoodsAccountKey(HH1, H00));
    assertThat(base.accounts().get(new GoodsAccountKey(HH1, H00)).balances())
        .containsExactlyEntriesOf(Map.of(GRAIN, 10L));
    assertThat(base.accounts()).doesNotContainKey(new GoodsAccountKey(HH2, H00));
  }

  /**
   * ★★ **不丢失**：成功路径后 {@code meta} / {@code actors} 原样、账上未点名的商品 / 货币 / 两张冻结键逐值保留。
   *
   * <p>只点名 grain（−5）与 silver（+1）；cloth / fiber / copper 与两张冻结表都在结果里逐值可查。
   */
  @Test
  void successKeepsMetaActorsAndEveryUnnamedKey() {
    ActorData base =
        ActorData.empty()
            .withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "庄园"))
            .withAccount(richAccount());

    ActorData after =
        apply(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-5}", "{\"silver\":1}")), base);

    assertThat(after.meta())
        .as("meta 原样（含 mapId / activatedDay / rulesVersion）")
        .isEqualTo(Optional.of(META));
    assertThat(after.actors()).as("actors 原样（同一条 ESTATE 行）").isEqualTo(base.actors());
    GoodsAccount account = after.accounts().get(new GoodsAccountKey(HH1, H00));
    assertThat(account.balances())
        .as("点名键按增量、未点名键逐值保留（含 0）")
        .containsOnlyKeys(GRAIN, CLOTH, FIBER)
        .containsEntry(GRAIN, 95L)
        .containsEntry(CLOTH, 7L)
        .containsEntry(FIBER, 0L);
    assertThat(account.money())
        .containsOnlyKeys(SILVER, COPPER)
        .containsEntry(SILVER, 10L)
        .containsEntry(COPPER, 4L);
    assertThat(account.frozenBalances()).containsOnlyKeys(GRAIN).containsEntry(GRAIN, 20L);
    assertThat(account.frozenMoney()).containsOnlyKeys(SILVER).containsEntry(SILVER, 3L);
    assertThat(new ArrayList<>(account.balances().keySet()))
        .as("键序也保留：[grain, cloth, fiber]")
        .containsExactly(GRAIN, CLOTH, FIBER);
  }

  // ── 重建 + JSON 线往返 ──────────────────────────────────────────────────────────────

  /**
   * ★★ {@code apply(between(base, target), base)} 重建 + **过 JSON 线往返**（{@link
   * ActorCodec}）：线格式解回来的变更集必须 重建出逐字段相同的目标（余额 / 键序 / 两张冻结表都点名断言，不拿"整体 equals"当唯一判据）。
   */
  @Test
  void changeSetRebuildsAndSurvivesTheJsonWire() {
    ActorData base = ActorData.empty().withAccount(richAccount());
    ActorData direct =
        apply(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-5}", null)), base);

    ActorChangeSet fromHandler =
        appliedChangeSet(payload(entry("HOUSEHOLD", "hh-1", 0, 0, "{\"grain\":-5}", null)), base);
    ActorCodec codec = new ActorCodec();
    ActorChangeSet overWire =
        (ActorChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(fromHandler));
    ActorData rebuilt = ActorChangeSet.apply(overWire, base);

    assertThat(rebuilt).as("线往返重建 = 直接重建").isEqualTo(direct);
    GoodsAccount account = rebuilt.accounts().get(new GoodsAccountKey(HH1, H00));
    assertThat(account.balances())
        .containsOnlyKeys(GRAIN, CLOTH, FIBER)
        .containsEntry(GRAIN, 95L)
        .containsEntry(CLOTH, 7L)
        .containsEntry(FIBER, 0L);
    assertThat(account.frozenBalances()).containsEntry(GRAIN, 20L);
    assertThat(account.frozenMoney()).containsEntry(SILVER, 3L);
    assertThat(rebuilt.actors()).as("线往返不发明 actor 行").isEqualTo(base.actors());
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 键序刻意：余额 [grain, cloth, fiber]、货币 [silver, copper]、冻结各一条。 */
  private static GoodsAccount richAccount() {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 100L);
    balances.put(CLOTH, 7L);
    balances.put(FIBER, 0L);
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, 9L);
    money.put(COPPER, 4L);
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    frozenBalances.put(GRAIN, 20L);
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(SILVER, 3L);
    return new GoodsAccount(
        new GoodsAccountKey(HH1, H00), balances, money, frozenBalances, frozenMoney);
  }

  /**
   * 与 {@link #upsertsExistingAccountKeepingKeyOrderAndFrozenTables} 对照的基账：冻结 grain=40 / silver=5。
   */
  private static GoodsAccount baseAccount() {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 100L);
    balances.put(CLOTH, 0L);
    balances.put(FIBER, 5L);
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, 10L);
    money.put(COPPER, 0L);
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    frozenBalances.put(GRAIN, 40L);
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(SILVER, 5L);
    return new GoodsAccount(
        new GoodsAccountKey(HH1, H00), balances, money, frozenBalances, frozenMoney);
  }

  private static String payload(String... entriesJson) {
    return "{\"entries\":[" + String.join(",", entriesJson) + "]}";
  }

  private static String entry(
      String kind, String id, int q, int r, String goodsJson, String moneyJson) {
    StringBuilder json = new StringBuilder();
    json.append("{\"owner\":{\"kind\":\"")
        .append(kind)
        .append("\",\"id\":\"")
        .append(id)
        .append("\"}");
    json.append(",\"q\":").append(q).append(",\"r\":").append(r);
    if (goodsJson != null) {
      json.append(",\"goods\":").append(goodsJson);
    }
    if (moneyJson != null) {
      json.append(",\"money\":").append(moneyJson);
    }
    return json.append('}').toString();
  }

  private static HandlerOutcome outcome(String payload, ActorData base) {
    return HANDLER.handle(state(base), payload);
  }

  private static ActorChangeSet appliedChangeSet(String payload, ActorData base) {
    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome(payload, base);
    return (ActorChangeSet) applied.changeSet();
  }

  private static ActorData apply(String payload, ActorData base) {
    return ActorChangeSet.apply(appliedChangeSet(payload, base), base);
  }

  /** 拒因文本（先钉住"确实是 Rejected"，否则下面的 contains 会读到别的对象）。 */
  private static String reason(String payload) {
    HandlerOutcome outcome = HANDLER.handle(state(ActorData.empty()), payload);
    assertThat(outcome).as("载荷 %s 必须被拒", payload).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static SimulationState state(ActorData data) {
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of("actor", new ActorSnapshot(REF, T7, data)),
        InMemoryInfoSystem.empty());
  }
}
