package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>账户分区键</b>（R1 线程安全内核的<strong>唯一账户身份</strong>）：{@code (ActorRef, HexCoord)} 的规范串。
 *
 * <p>★★ <b>为什么必须有这一个类型</b>：并行推进要求"同一本账的所有意向落在同一个分区"。而"哪一本账"的拼写在 本仓已经有三处雏形 —— {@code
 * GoodsAccountKey}（actor 切片）、旧 {@code AccountSession.ActorAccountKey}、 以及散落在各处的 {@code (owner,
 * location)} 二元组。三处各拼一份必然漂开（本仓最忌"同一事实两处拼写点"）； 本类把 <b>economy 侧可见的那一份</b>钉成一个不可变值对象，并同时给出：
 *
 * <ol>
 *   <li>{@link #canonical()} —— 规范串（{@code <actor>|<q>_<r>}，actor 的规范串复用 {@link
 *       ActorRef#toString()}， 格的规范串复用 {@link HexCoord#toString()}，本类<b>不复述</b>它们的格式）；
 *   <li>{@link #partitionIndex(int)} —— 确定性分区函数：只依赖规范串（{@code Math.floorMod(canonical.hashCode(),
 *       N)}）， <b>1/4/8 线程走同一函数</b>；
 *   <li>{@link #compareTo(AccountPartitionKey)} —— canonical 字典序（分区内序、求和序、tie-break 的唯一序）。
 * </ol>
 *
 * <p>★★ <b>为什么按第一个 {@code |} 切</b>：照 {@code GoodsAccountKey} 的接缝约定 —— {@code ActorRef.id()} 是任意非空白
 * 文本，而 {@code HexCoord} 的规范串是 {@code <q>_<r>}（数字与 {@code _}）必然不含 {@code |}。若某个 actor id 里含 {@code
 * |}，切歪之后余下那段喂不进 {@link HexCoord#parse}，<b>当场抛</b>（fail-closed），不会静默造一个错的账户身份。 全仓现行的 actor id
 * 形状（{@code farm@0_0} / {@code legacy-0_0:rural:poor_peasant} / {@code craft@-3_2}）不含 {@code |}。
 *
 * <p>★ <b>本类不持有账户余额</b>：它只是"哪一本账"的键。账户的可变状态住在 {@link AccountSession}，对 worker 的只读视图住在 {@link
 * AccountSnapshot}。
 */
public record AccountPartitionKey(ActorRef actor, HexCoord location)
    implements Comparable<AccountPartitionKey> {

  /** 规范串的段分隔符 —— <b>只在 {@link #canonical()} 与 {@link #parseCanonical(String)} 两处被读</b>。 */
  private static final String SEGMENT_SEPARATOR = "|";

  public AccountPartitionKey {
    Objects.requireNonNull(actor, "AccountPartitionKey.actor 不得为 null");
    Objects.requireNonNull(location, "AccountPartitionKey.location 不得为 null");
  }

  /** 规范串：{@code <actor>|<q>_<r>}（与 {@code GoodsAccountKey#toString()} 同形，本类只复用上游拼写）。 */
  public String canonical() {
    return actor + SEGMENT_SEPARATOR + location;
  }

  /** {@link #toString()} = 规范串（本类型进日志/提交序时的唯一读法）。 */
  @Override
  public String toString() {
    return canonical();
  }

  /** canonical 字典序：分区内序、求和序、tie-break 的<b>唯一</b>序（不许另立一套）。 */
  @Override
  public int compareTo(AccountPartitionKey other) {
    Objects.requireNonNull(other, "other");
    return canonical().compareTo(other.canonical());
  }

  /**
   * ★★ <b>确定性分区函数</b>：{@code floorMod(canonical.hashCode(), partitionCount)}。
   *
   * <p>★ 只依赖规范串 ⇒ <b>同一实体在 1/4/8 线程下必然落同一分区</b>；{@code String.hashCode()} 是 JLS 钉死的纯函数， 不含线程 id /
   * 时间戳 / 随机源。★ 分区数与分区内顺序都来自 {@link PartitionPlan}，本方法不自己决定分组。
   *
   * @param partitionCount 分区数（≥ 1；= 并行 worker 数）
   */
  public int partitionIndex(int partitionCount) {
    return partitionIndexOf(canonical(), partitionCount);
  }

  /** 规范串版分区函数（{@link PartitionPlan} 对 hex/区键复用它，保证 1/4/8 线程同一函数）。 */
  public static int partitionIndexOf(String canonical, int partitionCount) {
    Objects.requireNonNull(canonical, "canonical");
    if (canonical.isBlank()) {
      throw new IllegalArgumentException("分区键的规范串不得为空白");
    }
    if (partitionCount < 1) {
      throw new IllegalArgumentException("分区数必须 ≥ 1: " + partitionCount);
    }
    return Math.floorMod(canonical.hashCode(), partitionCount);
  }

  /**
   * {@link #canonical()} 的逆（只服务"提交序只带规范串、提交时需要还原账户键"这一条路）。
   *
   * <p>★ <b>按第一个 {@code |} 切</b>（理由见类注）；段缺失/在首/在尾一律抛。
   */
  public static AccountPartitionKey parseCanonical(String canonical) {
    if (canonical == null || canonical.isBlank()) {
      throw new IllegalArgumentException("非法账户分区键: " + canonical);
    }
    int actorEnd = canonical.indexOf(SEGMENT_SEPARATOR);
    if (actorEnd <= 0) {
      throw new IllegalArgumentException("非法账户分区键（actor 段缺失或在首）: " + canonical);
    }
    if (actorEnd == canonical.length() - SEGMENT_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法账户分区键（格段缺失）: " + canonical);
    }
    return new AccountPartitionKey(
        ActorRef.parseCanonical(canonical.substring(0, actorEnd)),
        HexCoord.parse(canonical.substring(actorEnd + SEGMENT_SEPARATOR.length())));
  }
}
