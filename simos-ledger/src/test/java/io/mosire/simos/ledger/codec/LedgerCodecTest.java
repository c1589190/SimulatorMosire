package io.mosire.simos.ledger.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.LedgerData;
import io.mosire.simos.ledger.LedgerSnapshot;
import io.mosire.simos.ledger.change.LedgerChangeSet;
import io.mosire.simos.ledger.model.Account;
import io.mosire.simos.ledger.model.Claim;
import io.mosire.simos.ledger.model.ClaimKind;
import io.mosire.simos.ledger.model.EconomyMeta;
import io.mosire.simos.ledger.model.Transfer;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ledger 模块的 JSON 往返守卫（照 {@code SocialCodecTest} 同制，夹具是 ledger 自己的）。
 *
 * <p>★ 覆盖：{@code Optional<EconomyMeta>} 两侧向（未激活 / 已激活）、{@code OptionalLong}（{@code
 * lastClosedDay}/{@code settledDay} 两侧向）、{@code Optional<String>}/{@code
 * Optional<CommodityId>}/{@code Optional<ClaimId>}、四个自定义键（{@code AccountId}/{@code ClaimId}/{@code
 * TransferId}/{@code CommodityId}，后者的用法在**嵌套 map 的键**上）、{@code FieldDelta} 四变体、单值组件的投影往返、
 * **字节级**往返（含"派生判断 {@code empty} 不进线格式"的观察点），以及旧档缺键的兼容。
 */
class LedgerCodecTest {

  private static final AccountId A1 = new AccountId("acc-1");
  private static final AccountId A2 = new AccountId("acc-2");
  private static final AccountId A3 = new AccountId("acc-3");
  private static final ClaimId C1 = new ClaimId("cl-1");
  private static final ClaimId C2 = new ClaimId("cl-2");
  private static final TransferId T1 = new TransferId("tr-1");
  private static final TransferId T2 = new TransferId("tr-2");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final ActorRef LOT = new ActorRef(ActorKind.PEOPLE_LOT, "lot-1");
  private static final ActorRef GOV = new ActorRef(ActorKind.GOVERNMENT, "gov-1");
  private static final ActorRef ORG = new ActorRef(ActorKind.ORGANIZATION, "guild-1");
  private static final ActorRef UNIT = new ActorRef(ActorKind.UNIT, "unit-1");

  private static final LedgerCodec CODEC = new LedgerCodec();

  @Test
  void namespaceIsLedger() {
    assertThat(CODEC.namespace()).isEqualTo("ledger");
  }

  /** 非平凡快照往返：带历注 + 三张表都非空 + 两层自定义键（表键与商品键）+ 各 Optional 的有值侧。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    LedgerSnapshot snapshot = snapshotOf(fullData(), SimosTimestamp.of(10, "弘光元年"));

    LedgerSnapshot back = (LedgerSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    LedgerSnapshot snapshot = snapshotOf(fullData(), SimosTimestamp.of(11));

    LedgerSnapshot back = (LedgerSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
  }

  /** ★ **未激活**：{@code economyMeta} 为空 {@code Optional}，且两张实体表为空——"空切片 ≠ 已激活"。 */
  @Test
  void snapshotRoundTripsWithUnactivatedEconomyMeta() {
    LedgerSnapshot snapshot = snapshotOf(LedgerData.empty(), SimosTimestamp.of(12, "弘光元年"));

    LedgerSnapshot back = (LedgerSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.data().economyMeta()).as("未激活必须原样回来").isEmpty();
  }

  /** 变更集往返：四条变体各造一条（都落在 {@code accounts} 组件上），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    LedgerData full = fullData();
    LedgerData base = dataWithAccounts(accountsFrom(account(A1, LOT, 100L), account(A2, GOV, 50L)));
    LedgerData moved =
        dataWithAccounts(accountsFrom(account(A1, LOT, 200L), account(A3, ORG, 10L)));

    LedgerChangeSet unchanged = LedgerChangeSet.between(full, full);
    LedgerChangeSet upsert = LedgerChangeSet.between(LedgerData.empty(), full);
    LedgerChangeSet remove = LedgerChangeSet.between(full, LedgerData.empty());
    LedgerChangeSet patch = LedgerChangeSet.between(base, moved);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.accounts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.accounts()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.accounts()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.accounts()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /**
   * ★ 值类型的绑定不能在读入侧丢成 Map：{@code Account} 得还是 {@code Account}， 且它内部的商品键得还是 {@code CommodityId}（嵌套
   * map 的键反序列化器在这里受检）。
   */
  @Test
  void deltaValuesSurviveAsAccountWithCommodityKeys() {
    LedgerChangeSet back =
        (LedgerChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(LedgerChangeSet.between(LedgerData.empty(), fullData())));

    FieldDelta.Upsert<Account> upsert = (FieldDelta.Upsert<Account>) back.accounts();
    Account account = upsert.entries().get("acc-1");

    assertThat(account).isInstanceOf(Account.class);
    assertThat(account.goods()).containsOnlyKeys(GRAIN, CLOTH);
    assertThat(account.reserved()).containsOnlyKeys(GRAIN);
  }

  /**
   * ★ **单值组件的投影往返**（{@code economyMeta} 是 {@code Optional<EconomyMeta>}，走"单键表"投影进 {@code
   * FieldDelta}）：未激活→已激活 = {@code Upsert}、已激活→未激活 = {@code Remove}、值→另一个值 = {@code Upsert}，三条都要过线。
   */
  @Test
  void economyMetaDeltaRoundTripsInAllThreeShapes() {
    LedgerData unactivated = LedgerData.empty();
    LedgerData activated = unactivated.withEconomyMeta(Optional.of(meta()));

    LedgerChangeSet act = LedgerChangeSet.between(unactivated, activated);
    LedgerChangeSet deact = LedgerChangeSet.between(activated, unactivated);
    LedgerChangeSet rev =
        LedgerChangeSet.between(activated, activated.withEconomyMeta(Optional.of(metaR2())));

    assertThat(act.economyMeta()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(deact.economyMeta()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(rev.economyMeta()).isInstanceOf(FieldDelta.Upsert.class);

    assertThat(LedgerChangeSet.apply(act, unactivated)).isEqualTo(activated);
    assertThat(LedgerChangeSet.apply(deact, activated)).isEqualTo(unactivated);
    assertThat(deact.isEmpty()).isFalse();

    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(act))).isEqualTo(act);
    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(deact)))
        .isEqualTo(deact);
    assertThat((LedgerChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(rev))).isEqualTo(rev);
  }

  /**
   * ★ **字节级往返**：编码 → 解码 → **再编码**，两次编码**逐字节相等**——它能抓住"解码时把有序容器换成 {@code
   * Map.copyOf}"/"键序漂移"这类内容相等而迭代序漂移的退化。
   *
   * <p>★ 同时钉住"派生判断 {@code empty} **不进线格式**"（{@code isEmpty()} 不是状态；写进去会让严格读入当场炸）。
   */
  @Test
  void encodingIsByteLevelStableForLedgerData() {
    LedgerData data = fullData();

    String snapshotOnce = CODEC.encodeSnapshot(snapshotOf(data, SimosTimestamp.of(10, "弘光元年")));
    String snapshotTwice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(snapshotOnce));
    assertThat(snapshotTwice).as("快照的字节级往返").isEqualTo(snapshotOnce);

    LedgerChangeSet changeSet = LedgerChangeSet.between(LedgerData.empty(), data);
    String changeOnce = CODEC.encodeChangeSet(changeSet);
    String changeTwice = CODEC.encodeChangeSet(CODEC.decodeChangeSet(changeOnce));
    assertThat(changeTwice).as("变更集的字节级往返").isEqualTo(changeOnce);

    assertThat(changeOnce).as("派生判断 empty 不得进线格式").doesNotContain("\"empty\"");
    assertThat(snapshotOnce).as("快照里也没有派生判断").doesNotContain("\"empty\"");
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    LedgerSnapshot base = snapshotOf(LedgerData.empty(), SimosTimestamp.of(10));
    LedgerChangeSet changeSet =
        LedgerChangeSet.between(base.data(), LedgerData.empty().withAccounts(oneAccount()));
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    LedgerSnapshot applied = (LedgerSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.data()).isEqualTo(LedgerChangeSet.apply(changeSet, base.data()));
    assertThat(base.data()).as("base 不得被就地改").isEqualTo(LedgerData.empty());
  }

  /**
   * ★ 下转型守卫的自证：喂一个**别的模块的切片**，{@code apply} 与 {@code encodeSnapshot} 都必须当场 {@link
   * IllegalStateException}（裸 cast 也会抛，所以断言钉的是**异常类型 + 消息**，不是"抛了就算"）。
   */
  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    LedgerChangeSet changeSet = LedgerChangeSet.between(LedgerData.empty(), LedgerData.empty());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 LedgerSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 LedgerSnapshot");
  }

  /**
   * {@code LedgerCodec} 同时实现 {@link io.mosire.simos.util.spi.ModuleDiffer}：语义委托 {@code between}。
   */
  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    LedgerSnapshot base = snapshotOf(LedgerData.empty(), SimosTimestamp.of(10));
    LedgerSnapshot target = snapshotOf(fullData(), SimosTimestamp.of(10));

    assertThat(CODEC.diff(base, target))
        .isEqualTo(LedgerChangeSet.between(base.data(), target.data()));
    assertThat(CODEC.diff(base, base))
        .as("全相等 ⇒ 各组件 Unchanged（仍是一份合法变更集）")
        .isEqualTo(LedgerChangeSet.between(base.data(), base.data()));
  }

  /** diff 的两侧切片都必须属于本模块：转错切片 ⇒ 当场炸，不给出一份错变更集。 */
  @Test
  void diffRejectsForeignSlice() {
    LedgerSnapshot ledger = snapshotOf(fullData(), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.diff(foreign, ledger))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 LedgerSnapshot");
  }

  /**
   * ★★ **旧档兼容：缺键的快照必须读得回来**（spec §11 的口径，照 {@code SocialData.cities}）。
   *
   * <p>字节刻意只留 {@code accounts}：{@code claims}/{@code transfers}/{@code economyMeta} 三个键**缺席**。
   * 若让构造器对 null 抛，等于"这个世界打不开"。缺省方向是 fail-closed：缺 ⇒ 空表 / 未激活。
   */
  @Test
  void legacySnapshotWithoutLedgerKeysDecodesToUnactivatedEmptyTables() {
    String legacy =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":0,\"calendarLabel\":null},"
            + "\"data\":{\"accounts\":{}}}";

    LedgerSnapshot back = (LedgerSnapshot) CODEC.decodeSnapshot(legacy);

    assertThat(back.data().accounts()).isEmpty();
    assertThat(back.data().claims()).as("旧档没提债权 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().transfers()).as("旧档没提流水 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().economyMeta()).as("旧档没提元信息 ⇒ 未激活，不抛").isEmpty();
  }

  /**
   * ★★ **旧档兼容：缺键的变更集必须读得回来**。缺省 = {@link FieldDelta.Unchanged}（"一字未动"）——读成 null 的话 {@code
   * isEmpty()} 与 {@code apply} 都会 NPE。
   */
  @Test
  void legacyChangeSetWithoutLedgerKeysDecodesToUnchangedComponents() {
    String legacy = "{\"accounts\":{\"@class\":\"unchanged\"}}";

    LedgerChangeSet back = (LedgerChangeSet) CODEC.decodeChangeSet(legacy);

    assertThat(back.economyMeta()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.claims()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.transfers()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("四个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
    assertThat(LedgerChangeSet.apply(back, LedgerData.empty())).isEqualTo(LedgerData.empty());
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "unit";
    }
  }

  // ── 夹具 ──

  /** 非平凡数据：三张表都非空、两层自定义键、各 Optional 的有值侧至少出现一次。 */
  private static LedgerData fullData() {
    Map<AccountId, Account> accounts = new LinkedHashMap<>();
    accounts.put(A1, richAccount());
    accounts.put(A2, govAccount());
    Map<ClaimId, Claim> claims = new LinkedHashMap<>();
    claims.put(C1, claimWithCommodity());
    claims.put(C2, moneyClaimSettled());
    Map<TransferId, Transfer> transfers = new LinkedHashMap<>();
    transfers.put(T1, transferWithGoodsAndSettles());
    transfers.put(T2, moneyOnlyTransfer());
    return new LedgerData(Optional.of(meta()), accounts, claims, transfers);
  }

  private static LedgerData dataWithAccounts(Map<AccountId, Account> accounts) {
    return new LedgerData(Optional.of(meta()), accounts, Map.of(), Map.of());
  }

  private static Map<AccountId, Account> oneAccount() {
    return Map.of(A1, richAccount());
  }

  private static Map<AccountId, Account> accountsFrom(Account... accounts) {
    Map<AccountId, Account> out = new LinkedHashMap<>();
    for (Account account : accounts) {
      out.put(account.id(), account);
    }
    return out;
  }

  private static LedgerSnapshot snapshotOf(LedgerData data, SimosTimestamp timestamp) {
    return new LedgerSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, data);
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "m1", 7L, OptionalLong.of(6L), "rules-2026-09", Optional.of("worlds/v17levant.json"));
  }

  private static EconomyMeta metaR2() {
    return new EconomyMeta("m1", 7L, OptionalLong.of(8L), "rules-2026-10", Optional.empty());
  }

  private static Account richAccount() {
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    goods.put(GRAIN, 100L);
    goods.put(CLOTH, 40L);
    return new Account(A1, LOT, goods, Map.of(GRAIN, 10L), 500L, 5L);
  }

  private static Account govAccount() {
    return new Account(A2, GOV, Map.of(), Map.of(), 900L, 0L);
  }

  private static Account account(AccountId id, ActorRef owner, long money) {
    return new Account(id, owner, Map.of(GRAIN, 1L), Map.of(), money, 0L);
  }

  private static Claim claimWithCommodity() {
    return new Claim(
        C1, ClaimKind.RENT, LOT, ORG, Optional.of(GRAIN), 300L, 30L, OptionalLong.empty());
  }

  private static Claim moneyClaimSettled() {
    return new Claim(
        C2, ClaimKind.WAGE, GOV, LOT, Optional.empty(), 120L, 10L, OptionalLong.of(9L));
  }

  private static Transfer transferWithGoodsAndSettles() {
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    goods.put(GRAIN, 5L);
    goods.put(CLOTH, 2L);
    return new Transfer(T1, 3L, LOT, ORG, goods, 12L, Optional.of(C1));
  }

  private static Transfer moneyOnlyTransfer() {
    return new Transfer(T2, 4L, GOV, UNIT, Map.of(), 77L, Optional.empty());
  }
}
