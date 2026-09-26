package io.mosire.simos.actor.change;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * actor 状态的变更集。<b>组件与 {@link ActorData} 的 record 组件一一对应</b>（当前 2 个：{@code meta} / {@code
 * actors}；Task 5 加 {@code holdings}、Task 6 加 {@code accounts}）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code ActorRoundTripTest} 的<b>反射枚举</b>把守——新增状态组件若不进变更集， 那个测试自动红。
 *
 * <p>★ <b>差异与重建的语义不在这里</b>：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} / {@code
 * LedgerChangeSet} / {@code EconomyChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★★ <b>{@code meta} 是单值组件，用"单键表"投影进同一份机制</b>（照 {@code EconomyChangeSet}）：{@code FieldDelta}
 * 是对<b>表</b>的差异（键 → 值），而 {@code meta} 是 {@code Optional<ActorMeta>}。若为它另写一份"单值差异" 机制，就有了与 {@code
 * FieldDelta} 分叉的第二份实现。故把 {@code Optional} 投影成至多一行的表（键 = {@link #META_KEY}）， 再投影回来。语义是纯的：{@code 空 →
 * 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值 → 另一个值} = {@code Upsert}。
 *
 * <p>★ <b>实现 util 的 {@code ChangeSet} 标记接口</b>：该接口已收窄为<b>标记接口</b>，实现它不带来任何新义务。
 */
public record ActorChangeSet(FieldDelta<ActorMeta> meta, FieldDelta<Actor> actors)
    implements ChangeSet {

  /** {@code meta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String META_KEY = "meta";

  /** 规范串里种类与 id 的分隔符 —— {@link ActorRef#toString()} 用的就是它。 */
  private static final char KIND_ID_SEPARATOR = ':';

  public ActorChangeSet {
    // ★ **旧档兼容**（照 LedgerChangeSet / EconomyChangeSet 的口径）：升级前落盘的这条变更集没有这些键时，
    //   Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (meta == null) {
      meta = new FieldDelta.Unchanged<>();
    }
    if (actors == null) {
      actors = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static ActorChangeSet between(ActorData base, ActorData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new ActorChangeSet(
        FieldDelta.diff(metaTable(base.meta()), metaTable(target.meta())),
        FieldDelta.diff(base.actors(), target.actors()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static ActorData apply(ActorChangeSet cs, ActorData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new ActorData(
        metaOf(FieldDelta.rebuild(metaTable(base.meta()), cs.meta(), Function.identity())),
        FieldDelta.rebuild(base.actors(), cs.actors(), ActorChangeSet::parseActorKey));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(meta.changed() || actors.changed());
  }

  /** {@code Optional<ActorMeta>} → 至多一行的表（键固定为 {@link #META_KEY}）。 */
  private static Map<String, ActorMeta> metaTable(Optional<ActorMeta> meta) {
    return meta.map(value -> Map.of(META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<ActorMeta> metaOf(Map<String, ActorMeta> table) {
    return Optional.ofNullable(table.get(META_KEY));
  }

  /**
   * 规范串 → {@link ActorRef}：{@link FieldDelta#rebuild} 对<b>新出现的</b>键要一个 {@code Function<String, K>}，
   * 而键是 {@code toString()} 的产物（{@code "<KIND>:<id>"}）。
   *
   * <p>★★ <b>为什么不直接调 {@code ActorRef.parse} 的单参形式</b>（裁定 R4）：{@code ActorRef.parse} 是<b>两参</b> 的
   * {@code parse(kindText, idText)}，<b>不是</b> {@code toString()} 的逆（{@code "UNIT:u-1"} 喂不回去）； 而 R4
   * 明令这个不对称<b>原封不动</b>（上移不改变它，也不许顺手"修好"——那会改读侧契约并打破既有断言）。 ⇒ 逆这一步只能在本切片里做。
   *
   * <p>★★ <b>按第一个 {@code ':'} 切</b>：{@code ActorKind} 的词表里一个 {@code ':'} 都没有，而 {@code id}
   * 里<b>可以</b>有（例如 {@code "rural:0_0:MALE:1"}）⇒ 只有"首个分隔符"这一个切法能还原。★ 这条判别力由 {@code
   * ActorRoundTripTest} 里那条"id 含冒号的主体"用例钉住：改成 {@code lastIndexOf} 或 {@code split(":")} 都当场红。
   *
   * <p>★ <b>段必须两段都非空</b>（照 {@code ClassKey#parse} 的"宁抛不静默"）：首尾是分隔符、没有分隔符，一律抛 ——
   * 静默造一个半截的身份，比当场炸难查得多。
   *
   * <p>★ <b>不写 {@code null} 分支</b>：这个方法只作为 {@link FieldDelta#rebuild} 的键解析器被调用，而键来自 {@code
   * Upsert}/{@code Remove} 的 {@code entries}/{@code keys} —— 那两个构造器自己就拒 {@code null}（见 {@link
   * FieldDelta}），故 {@code null} 进不来。<b>不为不存在的世界写代码</b>。
   */
  private static ActorRef parseActorKey(String text) {
    int i = text.indexOf(KIND_ID_SEPARATOR);
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法 actor 键: " + text);
    }
    return ActorRef.parse(text.substring(0, i), text.substring(i + 1));
  }
}
