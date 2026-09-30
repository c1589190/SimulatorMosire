package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code economy.UnitBorrow} 处理器（收尾期 T4a · 地方债第一腿）：命令面 / targetPaths 边界、money 与 grain 两条 happy
 * 路径的逐值账（放贷方余额、双边腿十字段、变更集过线往返）、SETTLED 身份重开、逐条具名拒因、以及"镜像腿非 0 = 状态损坏 ⇒ {@link
 * IllegalStateException}（不是 {@code Rejected}）"。
 *
 * <p>★ 判别力：happy 路径把 <b>lender 余额与 goods 表</b>、<b>两条腿的
 * id/owner/counterparty/unit/terms/rate/due/net/ interestAccrued/status 全部逐值</b>钉住；"少扣一笔 / 记错方向 /
 * terms 缺省没生效 / 把 SETTLED 的旧字段抄过来"都会当场红。拒因断言带 principal / available / 净额等数字与指路词。
 *
 * <p>★ 夹具 = 只含 {@code classFirst}（lenders + 可选 legs）的 {@link EconomyData}；tick 由 {@link
 * SimulationState#meta()} 提供（handler 读它判 due）。不碰 app / Shell。
 */
class UnitBorrowHandlerTest {

  private static final UnitBorrowHandler HANDLER = new UnitBorrowHandler();
  private static final EconomyCodec CODEC = new EconomyCodec();
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final String UNIT_ID = "u-1";
  private static final String LENDER_ID = "GOV-LENDER";
  private static final String MONEY = PilotModel.MONEY;
  private static final String GRAIN = PilotModel.GRAIN;

  private static final ClassFirstAccountId DEBT_MONEY_ID =
      ClassFirstAccountId.idOf(UNIT_ID, LENDER_ID, MONEY);
  private static final ClassFirstAccountId MIRROR_MONEY_ID =
      ClassFirstAccountId.idOf(LENDER_ID, UNIT_ID, MONEY);
  private static final ClassFirstAccountId DEBT_GRAIN_ID =
      ClassFirstAccountId.idOf(UNIT_ID, LENDER_ID, GRAIN);
  private static final ClassFirstAccountId MIRROR_GRAIN_ID =
      ClassFirstAccountId.idOf(LENDER_ID, UNIT_ID, GRAIN);

  private static final PilotModel.Lender LENDER =
      new PilotModel.Lender(LENDER_ID, 10_000L, Map.of(GRAIN, 500L, "cloth", 7L), 20L, 60L, 1000L);

  // ── 命令面 ──────────────────────────────────────────────────────────────────────────

  @Test
  void typeIsEconomyUnitBorrow() {
    assertThat(HANDLER.type()).isEqualTo("economy.UnitBorrow");
    assertThat(UnitBorrowHandler.TYPE).isEqualTo("economy.UnitBorrow");
    assertThat(UnitBorrowHandler.TERMS_UNIT_DEBT).isEqualTo("unit-debt");
  }

  /** ★ 裸账目原语：标 {@link GmOnlyCommand}，且合法载荷（缺省 terms 与显式 terms 各一次）返回空目标表。 */
  @Test
  void handlerIsGmOnlyAndTargetPathsAreEmptyForValidPayloads() {
    assertThat(HANDLER).isInstanceOf(GmOnlyCommand.class);
    assertThat(HANDLER).isInstanceOf(CommandTargets.class);

    assertThat(
            HANDLER.targetPaths(
                "Map1", borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null)))
        .as("本命令没有 economy 命名空间里的格键目标：合法载荷 = 空表")
        .isEmpty();
    assertThat(
            HANDLER.targetPaths(
                "Map1", borrowJson(UNIT_ID, LENDER_ID, GRAIN, 100L, 20L, 50L, "harvest-loan")))
        .isEmpty();
  }

  /** ★ 坏载荷（非法 JSON / 缺字段 / 词表外 unit / principal 越界）在 targetPaths 就抛，不静默返回空表。 */
  @Test
  void targetPathsThrowOnBadPayloads() {
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                HANDLER.targetPaths(
                    "Map1", borrowJson(UNIT_ID, LENDER_ID, "silver", 100L, 20L, 50L, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit");
    assertThatThrownBy(
            () ->
                HANDLER.targetPaths(
                    "Map1", borrowJson(UNIT_ID, LENDER_ID, MONEY, 0L, 20L, 50L, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("principal");
  }

  // ── happy：money / grain ────────────────────────────────────────────────────────────

  /**
   * ★ money happy：放贷方 money 10000→8000（其余字段原样）、借款腿 {@code -2000} / 镜像腿 {@code +2000} 十字段逐值、Σ=0；
   * terms 省略 ⇒ 缺省 {@code unit-debt}；变更集过 JSON 线往返后重建仍等于 target。
   */
  @Test
  void borrowMoneyDebitsLenderAndLandsBothLegsWithDefaultTerms() {
    EconomyData target =
        apply(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 2000L, 20L, 50L, null), economyWith(LENDER), 10L);

    PilotModel.Lender after = lenderOf(target);
    assertThat(after.id()).isEqualTo(LENDER_ID);
    assertThat(after.money()).as("放贷方 money 逐值减 principal").isEqualTo(8000L);
    assertThat(after.goods()).as("money 借入不动 goods 表").isEqualTo(LENDER.goods());
    assertThat(after.interestRatePerMille()).isEqualTo(20L);
    assertThat(after.nextDueTick()).isEqualTo(60L);
    assertThat(after.collectionPower()).isEqualTo(1000L);

    ClassFirstAccount debt = target.classFirst().accounts().get(DEBT_MONEY_ID);
    ClassFirstAccount mirror = target.classFirst().accounts().get(MIRROR_MONEY_ID);
    assertLeg(
        debt,
        DEBT_MONEY_ID,
        UNIT_ID,
        LENDER_ID,
        MONEY,
        "unit-debt",
        20L,
        50L,
        -2000L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
    assertLeg(
        mirror,
        MIRROR_MONEY_ID,
        LENDER_ID,
        UNIT_ID,
        MONEY,
        "unit-debt",
        20L,
        50L,
        2000L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
    assertThat(debt.cumulativeNet() + mirror.cumulativeNet()).as("双边账户 Σ=0").isZero();
    assertThat(Math.abs(debt.cumulativeNet())).as("借款腿绝对值 == principal").isEqualTo(2000L);
    assertThat(target.classFirst().accounts()).as("恰好落两条腿").hasSize(2);
  }

  /**
   * ★ grain happy：放贷方 goods.grain 500→100（cloth 等其它键原样保留）、借款腿 / 镜像腿逐值、terms 显式 {@code harvest-loan}
   * 逐字落账（不吃缺省）。
   */
  @Test
  void borrowGrainDebitsGoodsAndLandsBothLegsWithExplicitTerms() {
    EconomyData target =
        apply(
            borrowJson(UNIT_ID, LENDER_ID, GRAIN, 400L, 7L, 44L, "harvest-loan"),
            economyWith(LENDER),
            10L);

    PilotModel.Lender after = lenderOf(target);
    assertThat(after.money()).as("grain 借入不动 money").isEqualTo(10_000L);
    assertThat(after.goods())
        .as("grain 逐值减，其它商品键与值原样")
        .containsEntry(GRAIN, 100L)
        .containsEntry("cloth", 7L)
        .hasSize(2);

    ClassFirstAccount debt = target.classFirst().accounts().get(DEBT_GRAIN_ID);
    ClassFirstAccount mirror = target.classFirst().accounts().get(MIRROR_GRAIN_ID);
    assertLeg(
        debt,
        DEBT_GRAIN_ID,
        UNIT_ID,
        LENDER_ID,
        GRAIN,
        "harvest-loan",
        7L,
        44L,
        -400L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
    assertLeg(
        mirror,
        MIRROR_GRAIN_ID,
        LENDER_ID,
        UNIT_ID,
        GRAIN,
        "harvest-loan",
        7L,
        44L,
        400L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
    assertThat(debt.cumulativeNet() + mirror.cumulativeNet()).isZero();
  }

  /** ★ grain 扣到 0 时 key 保留为 0（不归一、不移除）——"这一格这个人手里还有 0 斤粮"是事实。 */
  @Test
  void grainDebitToZeroKeepsTheKeyAtZero() {
    EconomyData target =
        apply(
            borrowJson(UNIT_ID, LENDER_ID, GRAIN, 500L, 20L, 50L, null), economyWith(LENDER), 10L);

    assertThat(lenderOf(target).goods())
        .as("grain 正好扣到 0：key 必须保留为 0，不得移除")
        .containsEntry(GRAIN, 0L)
        .containsEntry("cloth", 7L);
  }

  /** ★ SETTLED 身份可复用重开：terms/rate/due/net 全覆盖，interestAccrued 归零、status 回 ACTIVE。 */
  @Test
  void settledLegIdentityCanBeReopenedWithFreshTerms() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID,
                LENDER_ID,
                MONEY,
                "old-terms",
                999L,
                5L,
                0L,
                777L,
                PilotModel.AccountStatus.SETTLED),
            leg(
                LENDER_ID,
                UNIT_ID,
                MONEY,
                "old-mirror-terms",
                998L,
                6L,
                0L,
                888L,
                PilotModel.AccountStatus.SETTLED));

    EconomyData target =
        apply(borrowJson(UNIT_ID, LENDER_ID, MONEY, 700L, 13L, 44L, "new-terms"), base, 10L);

    assertLeg(
        target.classFirst().accounts().get(DEBT_MONEY_ID),
        DEBT_MONEY_ID,
        UNIT_ID,
        LENDER_ID,
        MONEY,
        "new-terms",
        13L,
        44L,
        -700L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
    assertLeg(
        target.classFirst().accounts().get(MIRROR_MONEY_ID),
        MIRROR_MONEY_ID,
        LENDER_ID,
        UNIT_ID,
        MONEY,
        "new-terms",
        13L,
        44L,
        700L,
        0L,
        PilotModel.AccountStatus.ACTIVE);
  }

  // ── 拒因：逐条（Rejected，不是抛） ──────────────────────────────────────────────────

  @Test
  void rejectsWhenClassFirstIsEmpty() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null), EconomyData.empty(), 10L);

    assertThat(reason).contains("economy.UnitBorrow").contains("只在 class-first 世界可用");
  }

  @Test
  void rejectsUnknownLenderAndListsExistingIds() {
    PilotModel.Lender other = new PilotModel.Lender("OTHER", 100L, Map.of(), 1L, 2L, 3L);
    EconomyData base = economy(classFirst(Map.of(), Map.of(ExternalLenderId.of("OTHER"), other)));

    String reason =
        rejection(borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null), base, 10L);

    assertThat(reason).contains("放贷方不存在").contains(LENDER_ID).contains("现有放贷方").contains("OTHER");
  }

  @Test
  void rejectsUnknownUnitVocabulary() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, "silver", 100L, 20L, 50L, null),
            economyWith(LENDER),
            10L);

    assertThat(reason).contains("unit").contains("silver").contains("money").contains("grain");
  }

  @Test
  void rejectsPrincipalBelowOne() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 0L, 20L, 50L, null), economyWith(LENDER), 10L);

    assertThat(reason).contains("principal").contains(">= 1").contains("0");
  }

  @Test
  void rejectsNegativeInterestRate() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, -1L, 50L, null), economyWith(LENDER), 10L);

    assertThat(reason).contains("interestRatePerMille").contains(">= 0").contains("-1");
  }

  @Test
  void rejectsNextDueTickNotInTheFuture() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null), economyWith(LENDER), 50L);

    assertThat(reason)
        .contains("nextDueTick 必须大于当前 tick")
        .contains("nextDueTick=50")
        .contains("当前 tick=50");
  }

  @Test
  void rejectsMoneyPrincipalAboveLendableAmount() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, MONEY, 10_001L, 20L, 50L, null),
            economyWith(LENDER),
            10L);

    assertThat(reason)
        .contains("放贷方可贷 money 不足")
        .contains("principal=10001")
        .contains("available=10000");
  }

  @Test
  void rejectsGrainPrincipalAboveLendableAmount() {
    String reason =
        rejection(
            borrowJson(UNIT_ID, LENDER_ID, GRAIN, 501L, 20L, 50L, null), economyWith(LENDER), 10L);

    assertThat(reason)
        .contains("放贷方可贷 grain 不足")
        .contains("principal=501")
        .contains("available=500");
  }

  @Test
  void rejectsWhenAnUnsettledLegAlreadyExists() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                UNIT_ID,
                LENDER_ID,
                MONEY,
                "unit-debt",
                20L,
                50L,
                -500L,
                0L,
                PilotModel.AccountStatus.ACTIVE),
            leg(
                LENDER_ID,
                UNIT_ID,
                MONEY,
                "unit-debt",
                20L,
                50L,
                500L,
                0L,
                PilotModel.AccountStatus.ACTIVE));

    String reason =
        rejection(borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null), base, 10L);

    assertThat(reason)
        .contains("已存在未结清的地方债")
        .contains("unit.repayDebt")
        .contains(DEBT_MONEY_ID.value())
        .contains("-500");
  }

  // ── 状态损坏：IllegalStateException，不得折成 Rejected ───────────────────────────────

  /** ★ 借款腿不存在（或已结清）而镜像腿净额非 0 = 双边 Σ=0 被破坏 ⇒ 必须当场炸。 */
  @Test
  void inconsistentMirrorLegThrowsIllegalStateExceptionNotRejected() {
    EconomyData base =
        economyWith(
            LENDER,
            leg(
                LENDER_ID,
                UNIT_ID,
                MONEY,
                "unit-debt",
                20L,
                50L,
                500L,
                0L,
                PilotModel.AccountStatus.ACTIVE));

    assertThatThrownBy(
            () ->
                HANDLER.handle(
                    state(base, 10L), borrowJson(UNIT_ID, LENDER_ID, MONEY, 100L, 20L, 50L, null)))
        .as("状态损坏必须响亮，不得被 catch(IllegalArgumentException) 折成 Rejected")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态损坏")
        .hasMessageContaining("镜像腿")
        .hasMessageContaining("非 0")
        .hasMessageContaining(String.valueOf(500L));
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  /** 执行一次 borrow：Applied ⇒ 变更集 ⇒ 直连 apply + JSON 线往返各重建一次，二者必须与 target 逐值相等。 */
  private static EconomyData apply(String payload, EconomyData base, long tick) {
    HandlerOutcome outcome = HANDLER.handle(state(base, tick), payload);
    assertThat(outcome).as("happy 载荷必须 Applied").isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.classFirst().changed()).as("classFirst 组件必须被报成改变").isTrue();
    EconomyData target = EconomyChangeSet.apply(changeSet, base);

    EconomyChangeSet decoded =
        (EconomyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(EconomyChangeSet.apply(decoded, base)).as("变更集过 JSON 线往返后重建逐值不变").isEqualTo(target);
    return target;
  }

  /** 执行一次 borrow，断言走到 {@link HandlerOutcome.Rejected} 并返回拒因。 */
  private static String rejection(String payload, EconomyData base, long tick) {
    HandlerOutcome outcome = HANDLER.handle(state(base, tick), payload);
    assertThat(outcome).as("该载荷必须走 Rejected（而不是抛异常）").isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static void assertLeg(
      ClassFirstAccount leg,
      ClassFirstAccountId id,
      String owner,
      String counterparty,
      String unit,
      String terms,
      long rate,
      long due,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    assertThat(leg).as("腿必须存在: %s", id.value()).isNotNull();
    assertThat(leg.id()).as("id = idOf(owner,counterparty,unit) 纯函数身份").isEqualTo(id);
    assertThat(leg.id()).isEqualTo(ClassFirstAccountId.idOf(owner, counterparty, unit));
    assertThat(leg.ownerId()).isEqualTo(owner);
    assertThat(leg.counterpartyId()).isEqualTo(counterparty);
    assertThat(leg.unit()).isEqualTo(unit);
    assertThat(leg.terms()).isEqualTo(terms);
    assertThat(leg.interestRatePerMille()).isEqualTo(rate);
    assertThat(leg.nextDueTick()).isEqualTo(due);
    assertThat(leg.cumulativeNet()).isEqualTo(net);
    assertThat(leg.interestAccrued()).isEqualTo(interestAccrued);
    assertThat(leg.status()).isEqualTo(status);
  }

  private static PilotModel.Lender lenderOf(EconomyData data) {
    return data.classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
  }

  private static EconomyData economyWith(PilotModel.Lender lender, ClassFirstAccount... legs) {
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> accounts = new LinkedHashMap<>();
    for (ClassFirstAccount leg : legs) {
      accounts.put(leg.id(), leg);
    }
    return economy(classFirst(accounts, Map.of(ExternalLenderId.of(lender.id()), lender)));
  }

  private static EconomyData economy(ClassFirstState classFirst) {
    return EconomyData.empty().withClassFirst(classFirst);
  }

  private static ClassFirstState classFirst(
      Map<ClassFirstAccountId, ClassFirstAccount> accounts,
      Map<ExternalLenderId, PilotModel.Lender> lenders) {
    return new ClassFirstState(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        accounts,
        lenders,
        ClassFirstMeta.empty());
  }

  private static ClassFirstAccount leg(
      String owner,
      String counterparty,
      String unit,
      String terms,
      long rate,
      long due,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    return new ClassFirstAccount(
        ClassFirstAccountId.idOf(owner, counterparty, unit),
        owner,
        counterparty,
        unit,
        terms,
        rate,
        due,
        net,
        interestAccrued,
        status);
  }

  private static SimulationState state(EconomyData data, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new SimulationState(
        new StateMeta(REF, at),
        Map.of("economy", new EconomySnapshot(REF, at, data)),
        InMemoryInfoSystem.empty());
  }

  private static String borrowJson(
      String unitId,
      String lenderId,
      String unit,
      long principal,
      long interestRatePerMille,
      long nextDueTick,
      String terms) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unitId);
    payload.put("lenderId", lenderId);
    payload.put("unit", unit);
    payload.put("principal", principal);
    payload.put("interestRatePerMille", interestRatePerMille);
    payload.put("nextDueTick", nextDueTick);
    if (terms != null) {
      payload.put("terms", terms);
    }
    return json(payload);
  }

  private static String json(Map<String, Object> fields) {
    try {
      return JSON.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new AssertionError("测试载荷序列化失败", e);
    }
  }
}
