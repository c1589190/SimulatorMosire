package io.mosire.simos.ledger.change;

import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.LedgerData;
import io.mosire.simos.ledger.model.Account;
import io.mosire.simos.ledger.model.Claim;
import io.mosire.simos.ledger.model.EconomyMeta;
import io.mosire.simos.ledger.model.Transfer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 账本状态的变更集。**组件与 {@link LedgerData} 的 record 组件一一对应**（当前 4 个：{@code economyMeta} / {@code accounts}
 * / {@code claims} / {@code transfers}）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code LedgerRoundTripTest} 的**反射枚举**把守——新增状态组件若不进 变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} 共用同一份机制， R1
 * 守卫把守"全仓恰一份"）。
 *
 * <p>★★ **{@code economyMeta} 是单值组件，用"单键表"投影进同一份机制**：{@code FieldDelta} 是对**表**的差异 （键 → 值），而 {@code
 * economyMeta} 是 {@code Optional<EconomyMeta>}。若为它另写一份"单值差异"机制，就有 了与 {@code FieldDelta} 分叉的第二份实现（正是
 * R1 要挡的漂移）。故把 {@code Optional} 投影成 至多一行的表（键 = {@link #ECONOMY_META_KEY}），diff/rebuild 全走既有机制，再投影回
 * {@code Optional}。 语义是纯的：{@code 空 → 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值
 * → 另一个值} = {@code Upsert}。同 {@code SdState.info} 的键选择，记入说明。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十）：该接口已收窄为**标记接口**，实现它不带来 任何新义务。
 */
public record LedgerChangeSet(
    FieldDelta<EconomyMeta> economyMeta,
    FieldDelta<Account> accounts,
    FieldDelta<Claim> claims,
    FieldDelta<Transfer> transfers)
    implements ChangeSet {

  /** {@code economyMeta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String ECONOMY_META_KEY = "economyMeta";

  public LedgerChangeSet {
    // ★ **旧档兼容**（spec §11 的口径，照 {@code SocialChangeSet.cities}）：升级前落盘的这条变更集没有这些
    //   键时，Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (economyMeta == null) {
      economyMeta = new FieldDelta.Unchanged<>();
    }
    if (accounts == null) {
      accounts = new FieldDelta.Unchanged<>();
    }
    if (claims == null) {
      claims = new FieldDelta.Unchanged<>();
    }
    if (transfers == null) {
      transfers = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static LedgerChangeSet between(LedgerData base, LedgerData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new LedgerChangeSet(
        FieldDelta.diff(metaTable(base.economyMeta()), metaTable(target.economyMeta())),
        FieldDelta.diff(base.accounts(), target.accounts()),
        FieldDelta.diff(base.claims(), target.claims()),
        FieldDelta.diff(base.transfers(), target.transfers()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static LedgerData apply(LedgerChangeSet cs, LedgerData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new LedgerData(
        metaOf(
            FieldDelta.rebuild(
                metaTable(base.economyMeta()), cs.economyMeta(), Function.identity())),
        FieldDelta.rebuild(base.accounts(), cs.accounts(), AccountId::parse),
        FieldDelta.rebuild(base.claims(), cs.claims(), ClaimId::parse),
        FieldDelta.rebuild(base.transfers(), cs.transfers(), TransferId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(economyMeta.changed()
        || accounts.changed()
        || claims.changed()
        || transfers.changed());
  }

  /** {@code Optional<EconomyMeta>} → 至多一行的表（键固定为 {@link #ECONOMY_META_KEY}）。 */
  private static Map<String, EconomyMeta> metaTable(Optional<EconomyMeta> meta) {
    return meta.map(value -> Map.of(ECONOMY_META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<EconomyMeta> metaOf(Map<String, EconomyMeta> table) {
    return Optional.ofNullable(table.get(ECONOMY_META_KEY));
  }
}
