package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>worker 的账户活视图</b>（R2 并行内核）：把既有日结算代码习惯的 8 张会话表（家户/经营者 × 商品/货币 × 余额/冻结）包成<b>它们照旧读写的 {@code
 * Map} 形状</b>，而每一次写都落进线程本地的 {@link AccountIntentBuffer}，不触碰任何共享账户。
 *
 * <p>★★ <b>为什么需要这一层</b>：消费、现扣投入、收获/分账、同格借粮的算法已经写成"读账 → 算 → 写账"的 {@code Map}
 * 操作；把几千行算法重写成显式意向类型，既慢又容易在搬移中改掉逐值口径。本类让算法<b>一字不改</b>地跑在 意向缓冲上：读 = 快照 + 本地增量；写 =
 * 借/贷意向。提交仍只有一个口（{@link AccountSession#commit}）， 跨线程冲突仍由分区规则排除。
 *
 * <p>★★ <b>绝对值语义的差值化</b>：旧 helper（{@code setStock}/{@code setMoney}）从不原地改内层表，而是 "复制整张表 → 改一个键 →
 * {@code put} 回去"。本类的 {@code put} 因此是<b>绝对值替换</b>：对 "快照 + 本地增量"的当前值求差，差为正 ⇒ {@code credit*}，差为负 ⇒
 * {@code debit*}。最终提交的仍是逐账户净增量， 中间多次读改写只在本地缓冲里累积。
 *
 * <p>★ <b>冻结视图</b>由快照构造、按绝对值写；但 worker 缓冲（{@link AccountIntentBuffer#on} / {@link
 * AccountIntentBuffer#onHexPartition}）禁止冻结写（M8），故本阶段的冻结视图对 worker 是只读的 —— 跨区挂冻
 * 必须由协调器收齐需求、全局合并成一条绝对值，再用 {@link AccountIntentBuffer#forCoordinator} 产出。 写口形制保留，供 R3 的协调器路径复用。
 *
 * <p>★ <b>确定性</b>：视图的键序一律按 canonical 串排序（账户键 / 内层 id），绝不用 {@code HashMap} 裸迭代； 视图只服务单个 worker，不共享。
 */
final class BufferedAccountTables {

  private final AccountSnapshot snapshot;
  private final AccountIntentBuffer buffer;

  final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
  final Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
  final Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods;
  final Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney;
  final Map<ActorRef, Map<CommodityId, Long>> operatorGoods;
  final Map<ActorRef, Map<CurrencyId, Long>> operatorMoney;
  final Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods;
  final Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney;

  BufferedAccountTables(AccountSnapshot snapshot, AccountIntentBuffer buffer) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.householdGoods =
        new OuterView<>(snapshot.householdIndexKeySet(), snapshot::householdKey, GOODS);
    this.householdMoney =
        new OuterView<>(snapshot.householdIndexKeySet(), snapshot::householdKey, MONEY);
    this.householdFrozenGoods =
        new OuterView<>(snapshot.householdIndexKeySet(), snapshot::householdKey, FROZEN_GOODS);
    this.householdFrozenMoney =
        new OuterView<>(snapshot.householdIndexKeySet(), snapshot::householdKey, FROZEN_MONEY);
    this.operatorGoods =
        new OuterView<>(snapshot.operatorIndexKeySet(), snapshot::operatorKey, GOODS);
    this.operatorMoney =
        new OuterView<>(snapshot.operatorIndexKeySet(), snapshot::operatorKey, MONEY);
    this.operatorFrozenGoods =
        new OuterView<>(snapshot.operatorIndexKeySet(), snapshot::operatorKey, FROZEN_GOODS);
    this.operatorFrozenMoney =
        new OuterView<>(snapshot.operatorIndexKeySet(), snapshot::operatorKey, FROZEN_MONEY);
  }

  /** 商品轴（余额 / 冻结）。 */
  private interface GoodsAxisAdapter {
    long current(AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id);

    void setAbsolute(
        AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id, long value);

    Set<CommodityId> ids(AccountSnapshot.SnapshotAccount account);
  }

  /** 货币轴（余额 / 冻结）。 */
  private interface MoneyAxisAdapter {
    long current(AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id);

    void setAbsolute(
        AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id, long value);

    Set<CurrencyId> ids(AccountSnapshot.SnapshotAccount account);
  }

  /** 商品余额轴：绝对值替换 = 差值借/贷。 */
  private static final GoodsAxisAdapter GOODS =
      new GoodsAxisAdapter() {
        @Override
        public long current(AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id) {
          return buffer.goods(key, id);
        }

        @Override
        public void setAbsolute(
            AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id, long value) {
          long delta = Math.subtractExact(value, current(buffer, key, id));
          if (delta > 0L) {
            buffer.creditGoods(key, id, delta);
          } else if (delta < 0L) {
            buffer.debitGoods(key, id, -delta);
          }
        }

        @Override
        public Set<CommodityId> ids(AccountSnapshot.SnapshotAccount account) {
          return account.goods().keySet();
        }
      };

  /** 商品冻结轴：绝对值语义（0 = 解冻）。 */
  private static final GoodsAxisAdapter FROZEN_GOODS =
      new GoodsAxisAdapter() {
        @Override
        public long current(AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id) {
          return buffer.frozenGoods(key, id);
        }

        @Override
        public void setAbsolute(
            AccountIntentBuffer buffer, AccountPartitionKey key, CommodityId id, long value) {
          if (value < 0L) {
            throw new IllegalArgumentException("冻结额不得为负: " + value);
          }
          buffer.freezeGoods(key, id, value);
        }

        @Override
        public Set<CommodityId> ids(AccountSnapshot.SnapshotAccount account) {
          return account.frozenGoods().keySet();
        }
      };

  /** 货币余额轴。 */
  private static final MoneyAxisAdapter MONEY =
      new MoneyAxisAdapter() {
        @Override
        public long current(AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id) {
          return buffer.money(key, id);
        }

        @Override
        public void setAbsolute(
            AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id, long value) {
          long delta = Math.subtractExact(value, current(buffer, key, id));
          if (delta > 0L) {
            buffer.creditMoney(key, id, delta);
          } else if (delta < 0L) {
            buffer.debitMoney(key, id, -delta);
          }
        }

        @Override
        public Set<CurrencyId> ids(AccountSnapshot.SnapshotAccount account) {
          return account.money().keySet();
        }
      };

  /** 货币冻结轴。 */
  private static final MoneyAxisAdapter FROZEN_MONEY =
      new MoneyAxisAdapter() {
        @Override
        public long current(AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id) {
          return buffer.frozenMoney(key, id);
        }

        @Override
        public void setAbsolute(
            AccountIntentBuffer buffer, AccountPartitionKey key, CurrencyId id, long value) {
          if (value < 0L) {
            throw new IllegalArgumentException("冻结额不得为负: " + value);
          }
          buffer.freezeMoney(key, id, value);
        }

        @Override
        public Set<CurrencyId> ids(AccountSnapshot.SnapshotAccount account) {
          return account.frozenMoney().keySet();
        }
      };

  /** 外层视图：账户身份 → 内层余额表（键序 = 快照索引的 canonical 升序）。 */
  private final class OuterView<K, V> extends AbstractMap<K, Map<V, Long>> {

    private final Set<K> indexKeys;
    private final Function<K, AccountPartitionKey> resolver;
    private final Object axis;

    /**
     * ★★ 每个账户只持有一个 {@link InnerView}（键序 = 首次触达序，确定性）。
     *
     * <p>★★ <b>为什么必须缓存</b>：{@code InnerView.touched} 记录"本 worker 在这个账户上首次触达的键"——若每次 {@code
     * get}/{@code put}/{@code entrySet} 都 new 一个新视图，那么"先写一个快照里尚不存在的键（例如第一次收获的 fiber） →
     * 下一次读同一账户"会在新视图的 {@code entrySet} 里看不到那个键（它既不在快照、也不在这个新实例的 touched）， 于是本地读一致性承诺 （{@code
     * AccountIntentBuffer} 的类注）被违反：借记会被算成 0、不会产出。
     *
     * <p>★ 本视图只服务分区 worker 的单线程（见 {@link BufferedAccountTables} 类注），故这里按现有约定使用 LinkedHashMap，不引入
     * ConcurrentHashMap（后者会破坏迭代序的确定性）。
     */
    private final Map<AccountPartitionKey, InnerView<V>> innerViews = new LinkedHashMap<>();

    private OuterView(Set<K> indexKeys, Function<K, AccountPartitionKey> resolver, Object axis) {
      this.indexKeys = Objects.requireNonNull(indexKeys, "indexKeys");
      this.resolver = Objects.requireNonNull(resolver, "resolver");
      this.axis = Objects.requireNonNull(axis, "axis");
    }

    @Override
    public Map<V, Long> get(Object key) {
      AccountPartitionKey accountKey = resolve(key);
      return accountKey == null ? null : inner(accountKey);
    }

    @Override
    public boolean containsKey(Object key) {
      return resolve(key) != null;
    }

    @Override
    public Map<V, Long> put(K key, Map<V, Long> value) {
      Objects.requireNonNull(value, "value");
      AccountPartitionKey accountKey = resolve(key);
      if (accountKey == null) {
        throw new IllegalArgumentException("账户视图里没有这个键（拒绝静默造一本新账）: " + key);
      }
      InnerView<V> inner = inner(accountKey);
      Map<V, Long> previous = new LinkedHashMap<>();
      for (Map.Entry<V, Long> entry : inner.entrySet()) {
        previous.put(entry.getKey(), entry.getValue());
      }
      inner.replaceAll(value);
      return previous;
    }

    @Override
    public Set<Entry<K, Map<V, Long>>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public Iterator<Entry<K, Map<V, Long>>> iterator() {
          Iterator<K> keys = indexKeys.iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return keys.hasNext();
            }

            @Override
            public Entry<K, Map<V, Long>> next() {
              K key = keys.next();
              AccountPartitionKey accountKey = resolve(key);
              if (accountKey == null) {
                throw new NoSuchElementException("账户索引在迭代中变了: " + key);
              }
              Map<V, Long> value = inner(accountKey);
              return new AbstractMap.SimpleEntry<>(key, value) {
                @Override
                public Map<V, Long> setValue(Map<V, Long> replacement) {
                  put(key, replacement);
                  return value;
                }
              };
            }
          };
        }

        @Override
        public int size() {
          return indexKeys.size();
        }
      };
    }

    /** 同一 accountKey 复用同一个 {@link InnerView}（缺省新建并缓存；只对 {@code resolve} 已确认存在的账户调用）。 */
    private InnerView<V> inner(AccountPartitionKey accountKey) {
      return innerViews.computeIfAbsent(accountKey, InnerView::new);
    }

    @SuppressWarnings("unchecked")
    private AccountPartitionKey resolve(Object key) {
      if (key == null || !indexKeys.contains(key)) {
        return null;
      }
      return resolver.apply((K) key);
    }

    @SuppressWarnings("unchecked")
    private long currentValue(AccountPartitionKey key, V id) {
      if (axis instanceof GoodsAxisAdapter goodsAxis) {
        return goodsAxis.current(buffer, key, (CommodityId) id);
      }
      return ((MoneyAxisAdapter) axis).current(buffer, key, (CurrencyId) id);
    }

    @SuppressWarnings("unchecked")
    private void setValue(AccountPartitionKey key, V id, long value) {
      if (axis instanceof GoodsAxisAdapter goodsAxis) {
        goodsAxis.setAbsolute(buffer, key, (CommodityId) id, value);
      } else {
        ((MoneyAxisAdapter) axis).setAbsolute(buffer, key, (CurrencyId) id, value);
      }
    }

    @SuppressWarnings("unchecked")
    private Set<V> snapshotIds(AccountPartitionKey key) {
      AccountSnapshot.SnapshotAccount account = snapshot.requireAccount(key);
      if (axis instanceof GoodsAxisAdapter goodsAxis) {
        return (Set<V>) goodsAxis.ids(account);
      }
      return (Set<V>) ((MoneyAxisAdapter) axis).ids(account);
    }

    /** 轴的中文名（只服务 M2 的具名异常消息）。 */
    private String axisName() {
      if (axis == GOODS) {
        return "商品余额";
      }
      if (axis == FROZEN_GOODS) {
        return "商品冻结";
      }
      if (axis == MONEY) {
        return "货币余额";
      }
      return "货币冻结";
    }

    /** 内层视图：单本账、单条轴上的余额表（读 = 快照 + 本地增量；写 = 绝对值替换）。 */
    private final class InnerView<W> extends AbstractMap<W, Long> {

      private final AccountPartitionKey key;
      private final Set<W> touched = new LinkedHashSet<>();

      private InnerView(AccountPartitionKey key) {
        this.key = key;
      }

      @Override
      public Long get(Object id) {
        long value = currentValueUnchecked(id);
        return value == 0L ? null : value;
      }

      @Override
      public boolean containsKey(Object id) {
        return currentValueUnchecked(id) != 0L;
      }

      @Override
      public Long put(W id, Long value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        Long previous = get(id);
        setAbsolute(id, requireNonNegativeValue(id, value));
        touched.add(id);
        return previous;
      }

      @Override
      public Long remove(Object id) {
        Long previous = get(id);
        if (previous != null) {
          setAbsoluteUnchecked(id, 0L);
          touched.add(unchecked(id));
        }
        return previous;
      }

      /** 把整张表替换成 {@code replacement}（只对差异出意向；多余的键归零）。 */
      private void replaceAll(Map<W, Long> replacement) {
        LinkedHashSet<W> ids = new LinkedHashSet<>(snapshotIdsForThisView());
        ids.addAll(touched);
        ids.addAll(replacement.keySet());
        for (W id : ids) {
          Long target = replacement.get(id);
          long desired = target == null ? 0L : requireNonNegativeValue(id, target);
          if (desired != currentValueUnchecked(id)) {
            setAbsolute(id, desired);
            touched.add(id);
          }
        }
      }

      /**
       * ★★ <b>M2：绝对值的负值守卫</b> —— 负余额/负冻结不是一种状态（{@code GoodsAccount} 的构造期守卫同向），
       * 这里<b>显式抛具名异常</b>，绝不改成"负值静默钳到 0"：钳 0 会把符号写错的算式藏到落回 actor 才现形。
       */
      private long requireNonNegativeValue(W id, long value) {
        if (value < 0L) {
          throw new IllegalArgumentException(
              "账户绝对值写口的"
                  + axisName()
                  + "不得为负（护栏是红不是钳）：账户="
                  + key.canonical()
                  + " 键="
                  + id
                  + " 值="
                  + value);
        }
        return value;
      }

      @Override
      public Set<Entry<W, Long>> entrySet() {
        LinkedHashSet<W> ids = new LinkedHashSet<>(snapshotIdsForThisView());
        ids.addAll(touched);
        return new AbstractSet<>() {
          @Override
          public Iterator<Entry<W, Long>> iterator() {
            Iterator<W> iterator = ids.iterator();
            return new Iterator<>() {
              @Override
              public boolean hasNext() {
                return iterator.hasNext();
              }

              @Override
              public Entry<W, Long> next() {
                W id = iterator.next();
                Long value = get(id);
                return new AbstractMap.SimpleEntry<>(id, value) {
                  @Override
                  public Long setValue(Long replacement) {
                    return put(id, replacement);
                  }
                };
              }
            };
          }

          @Override
          public int size() {
            int count = 0;
            for (W id : ids) {
              if (get(id) != null) {
                count++;
              }
            }
            return count;
          }
        };
      }

      @SuppressWarnings("unchecked")
      private Set<W> snapshotIdsForThisView() {
        return (Set<W>) (Set<?>) snapshotIds(key);
      }

      @SuppressWarnings("unchecked")
      private long currentValueUnchecked(Object id) {
        if (id == null) {
          return 0L;
        }
        try {
          return OuterView.this.currentValue(key, (V) id);
        } catch (ClassCastException cast) {
          return 0L;
        }
      }

      @SuppressWarnings("unchecked")
      private void setAbsolute(W id, long value) {
        OuterView.this.setValue(key, (V) id, value);
      }

      @SuppressWarnings("unchecked")
      private void setAbsoluteUnchecked(Object id, long value) {
        OuterView.this.setValue(key, (V) id, value);
      }

      @SuppressWarnings("unchecked")
      private W unchecked(Object id) {
        return (W) id;
      }
    }
  }
}
