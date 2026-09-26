package io.mosire.simos.economy.api.cohort;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>家户身份（{@link CohortKey}）与经济主体身份（{@link ActorRef}）之间的唯一拼写点</b>
 * （2026-09-27 裁定 D3-C / K1 / <b>K9</b>；H1 的契约）。
 *
 * <p>★★ <b>为什么需要它</b>：裁定 D3-C 让"<b>家户</b>"成为持有商品与货币的经济主体，
 * 而人口数 / 劳动 / 需求 / 压力仍是<b>同一批人的视图</b>（{@code ClassRow} 的键就是 {@link CohortKey}）。
 * 于是同一个身份有两个名字空间：<b>状态表的键</b>（{@link CohortKey}）与 <b>actor 的 id</b>。
 * 两者各写一份拼法 = 同一个身份的<b>两处拼写点</b>（本仓明令禁止，它会静默漂开）；
 * 本类把它们钉在一起，并提供<b>互逆</b>的一对函数。
 *
 * <p>★★ <b>为什么 actor id 不能用 {@link CohortKey#toString()}（K9，一处会炸的接缝冲突）</b>：
 * {@code GoodsAccountKey} 的规范串是 {@code <owner>|<location>}、按<b>第一个 {@code |}</b> 切，
 * 而它的类注明文依赖"<b>全仓现行的 actor id 必然不含 {@code |}</b>"。
 * 若家户 actor 的 id 取 {@code 0_0|rural|poor_peasant}，那么
 * {@code GoodsAccountKey.toString()} 会产出 {@code HOUSEHOLD:0_0|rural|poor_peasant|0_0}，
 * {@code parse} 按第一个接缝切之后会把 {@code rural|poor_peasant|0_0} 整段喂给
 * {@code HexCoord.parse} ⇒ <b>存盘 / 读档的往返当场抛</b>（一个只在落盘时才现形的晚期故障）。
 *
 * <p>★ <b>故 actor id 的段分隔符取 {@code ":"}</b>（与既有的批次 id 同族：{@code rural:0_0:MALE:1}），
 * 形状：{@code <q>_<r>:<residence>:<stratum>}，例 {@code 0_0:rural:poor_peasant}。
 * 它<b>不含 {@code |}</b> ⇒ 与 {@code GoodsAccountKey} 的接缝约定相容；
 * 而 {@code ActorRef.parseCanonical} 按<b>第一个 {@code :}</b> 切 ⇒ id 里含 {@code :} 是合法的。
 *
 * <p>★ <b>种类只取 {@link ActorKind#HOUSEHOLD}</b>（裁定 S1：<b>复用</b>，不为地主/贫农各造一档）——
 * 家户之间的差别来自它们与产业的关系（{@code ProductionRelation}），不来自"种类"。
 */
public final class HouseholdActors {

  /** 家户 actor id 的段分隔符 —— <b>只在 {@link #of(CohortKey)} 与 {@link #cohortOf(ActorRef)} 两处被读</b>。 */
  private static final String SEGMENT_SEPARATOR = ":";

  private HouseholdActors() {}

  /** 家户 actor 的 id（{@code <q>_<r>:<residence>:<stratum>}）—— <b>本类的唯一拼写点</b>。 */
  public static String idOf(CohortKey cohort) {
    if (cohort == null) {
      throw new IllegalArgumentException("CohortKey 不得为 null（家户身份由它决定）");
    }
    return cohort.hex()
        + SEGMENT_SEPARATOR
        + cohort.residence().value()
        + SEGMENT_SEPARATOR
        + cohort.stratum();
  }

  /** 家户身份的 actor 引用（种类恒为 {@link ActorKind#HOUSEHOLD}）。 */
  public static ActorRef of(CohortKey cohort) {
    return new ActorRef(ActorKind.HOUSEHOLD, idOf(cohort));
  }

  /**
   * 反查：actor 引用 → 家户身份（{@link #of(CohortKey)} 的逆）。
   *
   * <p>★ <b>宁抛不静默</b>：① 种类不是 {@code HOUSEHOLD} ⇒ 抛（庄园 / 作坊 / 政府不是家户，
   * 把它们的账当作家户账读，会让"谁收到了实物报酬"这一问悄悄答错）；② 段数不为 3、或任一段为空 ⇒ 抛。
   */
  public static CohortKey cohortOf(ActorRef actor) {
    if (actor == null) {
      throw new IllegalArgumentException("ActorRef 不得为 null");
    }
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalArgumentException(
          "只有 HOUSEHOLD 才是家户（裁定 S1 复用该档）：本引用是 " + actor.kind() + " / " + actor);
    }
    String id = actor.id();
    int first = id.indexOf(SEGMENT_SEPARATOR);
    int second = first < 0 ? -1 : id.indexOf(SEGMENT_SEPARATOR, first + 1);
    if (first <= 0 || second < 0 || second == first + 1 || second == id.length() - 1) {
      throw new IllegalArgumentException(
          "家户 actor id 形状非法（应为 <q>_<r>:<residence>:<stratum>）: " + id);
    }
    return new CohortKey(
        HexCoord.parse(id.substring(0, first)),
        ResidenceKind.parse(id.substring(first + 1, second)),
        SocialClassId.parse(id.substring(second + 1)));
  }
}
