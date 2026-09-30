package io.mosire.simos.economy.api.money;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>「钱从哪来」的唯一闸门</b>（H4；E3 起可登记，但零登记行为逐字保留）。
 *
 * <p>★★ <b>登记表是进程内静态的，不是世界状态</b>：权威世界状态在 {@code EconomyData.governments}（可持久、可回放、可分支）。 日结算开始时从当前世界的
 * {@code governments} 调 {@link #syncAuthorities(Collection)} 重建登记表；世界没有政府/没有发行人时 登记表为空，{@link
 * #requireIssuerOf(CurrencyId)} 的旧 fail-closed 行为<b>逐字保留</b>。
 *
 * <p>★★ <b>跨世界隔离（如实记）</b>：静态表仍是进程级的；同步按当前世界重建后，前一个世界的登记不会静默留下。直接调用 {@code register}/{@code
 * deregister} 的调用方必须自己保证成对使用；做不到时用 {@link #clear()}。累计发行/回笼的权威记录在 {@code
 * EconomyData.moneyIssuances}（可重放），不在这里再存一份。
 *
 * <p>★ <b>唯一注册点是这里</b>：任何"谁是发行人"的答案都从本类出去；调用方不得另存一份发行主体映射。
 */
public final class MoneyIssuance {

  /** 当前登记表（保序、不可变快照）。零登记 ⇒ 空表。 */
  private static List<MoneyAuthority> REGISTERED = List.of();

  /** 私有锁对象（USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION：不用类固有锁，避免外部持锁者干扰内部互斥）。 */
  private static final Object LOCK = new Object();

  private MoneyIssuance() {}

  /** 已登记的发行人（保序、不可变快照；零登记 ⇒ 空表）。 */
  public static List<MoneyAuthority> registered() {
    synchronized (LOCK) {
      return REGISTERED;
    }
  }

  /**
   * 全部<b>可被发行</b>的币种（= 已登记发行人各自 {@link MoneyAuthority#issuable()} 的并集；保序）。
   *
   * <p>★ 零登记 ⇒ 空集：世界上没有一条路径能造出钱。
   */
  public static Set<CurrencyId> issuable() {
    synchronized (LOCK) {
      Set<CurrencyId> currencies = new LinkedHashSet<>();
      for (MoneyAuthority authority : REGISTERED) {
        currencies.addAll(authority.issuable());
      }
      return currencies;
    }
  }

  /**
   * ★★ <b>登记一个发行人</b>：逐个 {@code issuable()} 内的币种校验 {@link MoneyAuthority#authorityOf(CurrencyId)} 非
   * null，且该币种在登记表里还没有<b>另一个</b>发行主体（一个币种只能有一个发行主体）。
   *
   * @throws IllegalArgumentException authority/币种/发行主体为空，或与已登记主体冲突
   */
  public static void register(MoneyAuthority authority) {
    synchronized (LOCK) {
      Objects.requireNonNull(authority, "MoneyIssuance.register 的 authority 不得为 null");
      Set<CurrencyId> currencies = authority.issuable();
      if (currencies == null) {
        throw new IllegalArgumentException("MoneyAuthority.issuable() 不得为 null");
      }
      if (REGISTERED.contains(authority)) {
        return; // 政府记录是不可变值；逐值相同的登记是幂等的
      }
      // 先只读校验：任何冲突都在表被改动之前抛出。
      for (CurrencyId currency : currencies) {
        ActorRef issuer = requireIssuer(authority, currency);
        ActorRef existing = issuerOfRegistered(currency);
        if (existing != null && !existing.equals(issuer)) {
          throw new IllegalArgumentException(
              "币种 " + currency + " 已有另一个发行主体 " + existing + "，不能再登记 " + issuer);
        }
      }
      List<MoneyAuthority> next = new ArrayList<>(REGISTERED);
      next.add(authority);
      REGISTERED = List.copyOf(next);
    }
  }

  /** 撤销一个已登记发行人（按 {@link Object#equals(Object)} 匹配；没登记过 ⇒ 返回 {@code false}，不抛）。 */
  public static boolean deregister(MoneyAuthority authority) {
    synchronized (LOCK) {
      Objects.requireNonNull(authority, "MoneyIssuance.deregister 的 authority 不得为 null");
      List<MoneyAuthority> next = new ArrayList<>(REGISTERED.size());
      boolean removed = false;
      for (MoneyAuthority registered : REGISTERED) {
        if (!removed && registered.equals(authority)) {
          removed = true;
          continue;
        }
        next.add(registered);
      }
      if (removed) {
        REGISTERED = List.copyOf(next);
      }
      return removed;
    }
  }

  /** 清空登记表（跨世界/测试隔离的显式口子；清空后 {@link #requireIssuerOf(CurrencyId)} 的行为与零登记逐字相同）。 */
  public static void clear() {
    synchronized (LOCK) {
      REGISTERED = List.of();
    }
  }

  /**
   * ★★ <b>按当前世界权威状态重建登记表</b>（幂等、确定性）：先清空，再逐个登记非空 {@code issuable()} 的发行人。任何
   * "一个币种两个发行主体"的冲突都在表被改动到一半之前抛出（本地先建索引，校验完再一次性替换）。
   *
   * <p>★ 空 issuer 集合 ⇒ 清空后仍为零登记：旧世界的 fail-closed 行为不变。
   *
   * @param authorities 当前世界的政府/发行人；不得为 null、元素不得为 null
   */
  public static void syncAuthorities(Collection<? extends MoneyAuthority> authorities) {
    synchronized (LOCK) {
      Objects.requireNonNull(authorities, "MoneyIssuance.syncAuthorities 的 authorities 不得为 null");
      Map<CurrencyId, ActorRef> issuers = new LinkedHashMap<>();
      List<MoneyAuthority> effective = new ArrayList<>();
      Set<MoneyAuthority> seen = new LinkedHashSet<>();
      for (MoneyAuthority authority : authorities) {
        Objects.requireNonNull(authority, "MoneyIssuance.syncAuthorities 的元素不得为 null");
        Set<CurrencyId> currencies = authority.issuable();
        if (currencies == null) {
          throw new IllegalArgumentException("MoneyAuthority.issuable() 不得为 null: " + authority);
        }
        if (currencies.isEmpty() || !seen.add(authority)) {
          continue;
        }
        for (CurrencyId currency : currencies) {
          ActorRef issuer = requireIssuer(authority, currency);
          ActorRef existing = issuers.putIfAbsent(currency, issuer);
          if (existing != null && !existing.equals(issuer)) {
            throw new IllegalStateException(
                "同一币种出现两个发行主体（当前世界状态冲突）：币种=" + currency + "，发行主体=" + existing + " vs " + issuer);
          }
        }
        effective.add(authority);
      }
      REGISTERED = List.copyOf(effective);
    }
  }

  /**
   * ★★ <b>唯一的发行查询</b>：这个币种的发行源是谁。<b>查不到 ⇒ 当场抛</b>（不是"返回 null 让调用方自己看着办"）。
   *
   * <p>★ 零登记时抛出的消息与 H4 逐字相同（E3 的兼容判据）。
   *
   * @param currency 币种；不得为 null
   * @return 发行主体（唯一一个可以透支/单边发行该币种的主体）
   * @throws IllegalArgumentException 币种为 null
   * @throws IllegalStateException 没有任何已登记的发行人发行该币种
   */
  public static ActorRef requireIssuerOf(CurrencyId currency) {
    synchronized (LOCK) {
      if (currency == null) {
        throw new IllegalArgumentException("CurrencyId 不得为 null（说不出币种就答不出发行人）");
      }
      for (MoneyAuthority authority : REGISTERED) {
        if (authority.issuable().contains(currency)) {
          return authority.authorityOf(currency);
        }
      }
      throw new IllegalStateException(
          "没有任何已登记的货币发行人发行 "
              + currency
              + "（H4：MoneyAuthority 在本批**没有实现** ⇒ 世界上没有'发行/回笼'这条路径）。"
              + "⇒ 付方余额不足以支付时不许透支（透支 = 发行），逐币种 Σ余额 因此恒定。"
              + "若确实需要发行，先实现 MoneyAuthority 并在 MoneyIssuance 登记它（唯一注册点）");
    }
  }

  /** 已登记表里这个币种的发行源；没有 ⇒ {@code null}（只读查询，不抛）。 */
  public static ActorRef issuerOfRegistered(CurrencyId currency) {
    synchronized (LOCK) {
      if (currency == null) {
        return null;
      }
      for (MoneyAuthority authority : REGISTERED) {
        if (authority.issuable().contains(currency)) {
          return authority.authorityOf(currency);
        }
      }
      return null;
    }
  }

  private static ActorRef requireIssuer(MoneyAuthority authority, CurrencyId currency) {
    if (currency == null) {
      throw new IllegalArgumentException("MoneyAuthority.issuable() 不得含 null: " + authority);
    }
    ActorRef issuer = authority.authorityOf(currency);
    if (issuer == null) {
      throw new IllegalArgumentException(
          "MoneyAuthority.authorityOf(" + currency + ") 不得为 null（说不出'谁发的'就不是发行人）: " + authority);
    }
    return issuer;
  }
}
