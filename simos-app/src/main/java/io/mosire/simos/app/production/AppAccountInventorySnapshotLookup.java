package io.mosire.simos.app.production;

import io.mosire.simos.economy.time.AccountInventorySnapshotLookup;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.AccountSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-Ib2b：组合根对 {@link AccountInventorySnapshotLookup} 的唯一只读实现</b>。
 *
 * <p>账户权威只有一份：调用方已经装载的 {@link AccountSession}。本类不复制余额表、不直接读 actor 的活账户 Map，也不缓存 revision
 * 之间的旧快照；每次读口调用都向会话取一次当刻 {@link AccountSnapshot}。
 *
 * <p>★ 缺失语义：账户会话本身可用 ⇒ {@link Optional#of}；某个 payer 没有登记账户由 {@link AccountSnapshot#actorKeyOrNull}
 * 返回 {@code null}，由 {@code WagePayableInventoryLookupImpl} 折成 {@link
 * Optional#empty()}。账户会话不可用/未装配时调用方应注入 {@code UnavailableWagePayableInventoryLookup}，不得把缺账读成 0。
 */
public final class AppAccountInventorySnapshotLookup implements AccountInventorySnapshotLookup {

  private final AccountSession accounts;

  public AppAccountInventorySnapshotLookup(AccountSession accounts) {
    this.accounts = Objects.requireNonNull(accounts, "accounts（账户权威会话）");
  }

  @Override
  public Optional<AccountSnapshot> accountSnapshot() {
    return Optional.of(accounts.snapshot());
  }
}
