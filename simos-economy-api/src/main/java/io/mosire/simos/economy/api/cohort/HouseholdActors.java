package io.mosire.simos.economy.api.cohort;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★ <b>家户身份（{@link HouseholdId}）与经济主体身份（{@link ActorRef}）之间的唯一拼写点</b> （2026-09-27 裁定 D3-C / K1 /
 * <b>K9</b>；H1 的契约；<b>2026-09-28 S1 起身份换成 HouseholdId</b>）。
 *
 * <p>★★ <b>为什么需要它</b>：家户是持有商品与货币的经济主体，而人口数 / 劳动 / 需求 / 压力是同一批人的<b>视图</b> （{@code
 * HouseholdEconomy.view}）。同一个身份因此有两个名字空间：<b>经济状态表的键</b>（{@link HouseholdId}）与 <b>actor 的 id</b>。
 * 两者各写一份拼法 = 同一个身份的<b>两处拼写点</b>（本仓明令禁止，它会静默漂开）；本类把它们钉在一起。
 *
 * <p>★★ <b>为什么 actor id 不能直接取旧视图串 {@code CohortKey#toString()}</b>（K9，一处会炸的接缝冲突）： {@code
 * HouseholdAccountKey} 的规范串是 {@code <owner>|<location>}、按<b>第一个 {@code |}</b> 切，而它的类注明文依赖"全仓现行的
 * actor id 必然不含 {@code |}"。若家户 actor 的 id 取 {@code 0_0|rural|poor_peasant}，那么 {@code
 * HouseholdAccountKey.toString()} 会产出 {@code HOUSEHOLD:0_0|rural|poor_peasant|0_0}， {@code parse}
 * 按第一个接缝切之后会把 {@code rural|poor_peasant|0_0} 整段喂给 {@code HexCoord.parse} ⇒ <b>存盘 / 读档的往返当场抛</b>。
 *
 * <p>★ <b>故本类的 actor id 规则</b>：{@code idOf(HouseholdId) = household.value().replace('|', ':')} ——
 * 新档 id（{@code hh-…}）本来就没有 {@code |}，替换是恒等；旧档迁移 id（{@code legacy-0_0|rural|poor_peasant}） 由此变成
 * {@code legacy-0_0:rural:poor_peasant}（与旧三段批次 id 同族、且不含 {@code |}，与 {@code HouseholdAccountKey}
 * 的接缝约定相容）。{@link #householdOf(ActorRef)} 是它的逆。
 *
 * <p>★ <b>旧 API 保留但只准迁移用</b>：{@link #of(CohortKey)} / {@link #idOf(CohortKey)} / {@link
 * #cohortOf(ActorRef)} 服务旧档（三段 actor id）的读取；运行期新代码一律走 {@link #of(HouseholdId)} 一族。
 */
public final class HouseholdActors {

  /** 家户 actor id 的段分隔符 —— <b>只在 {@link #idOf(CohortKey)} 与 {@link #cohortOf(ActorRef)} 两处被读</b>。 */
  private static final String SEGMENT_SEPARATOR = ":";

  private HouseholdActors() {}

  /**
   * ★★ <b>新档：家户 actor 的 id</b>（唯一拼写点）= {@code HouseholdId.value()} 里的 {@code |} 换成 {@code :}。
   *
   * <p>★ 这是"状态表键 → actor id"的唯一映射；反向是 {@link #householdOf(ActorRef)}。
   */
  public static String idOf(HouseholdId household) {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdId 不得为 null（家户身份由它决定）");
    }
    return household.value().replace('|', ':');
  }

  /** 家户身份的 actor 引用（种类恒为 {@link ActorKind#HOUSEHOLD}）。 */
  public static ActorRef of(HouseholdId household) {
    return new ActorRef(ActorKind.HOUSEHOLD, idOf(household));
  }

  /**
   * ★★ <b>反查（新档）：actor 引用 → 家户身份</b>（{@link #of(HouseholdId)} 的逆）。
   *
   * <p>★ 旧档迁移生成的 actor id 带 {@link HouseholdIds#LEGACY_PREFIX} 前缀（冒号分隔三段）⇒ 还原成 {@link
   * HouseholdIds#ofLegacy(CohortKey)} 的同一身份；新档 id 直接 {@link HouseholdId#parse}。
   *
   * <p>★ <b>宁抛不静默</b>：种类不是 {@code HOUSEHOLD}、或旧档 id 段数/段内容非法 ⇒ 抛。
   */
  public static HouseholdId householdOf(ActorRef actor) {
    if (actor == null) {
      throw new IllegalArgumentException("ActorRef 不得为 null");
    }
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalArgumentException("只有 HOUSEHOLD 才是家户：本引用是 " + actor.kind() + " / " + actor);
    }
    String id = actor.id();
    if (id.startsWith(HouseholdIds.LEGACY_PREFIX)) {
      return HouseholdIds.ofLegacy(
          legacyCohortOfActorId(id.substring(HouseholdIds.LEGACY_PREFIX.length())));
    }
    return HouseholdId.parse(id);
  }

  /** ★ 旧档：家户 actor 的 id（{@code <q>_<r>:<residence>:<stratum>}）——<b>只准旧档迁移/兼容读调用</b>。 */
  @Deprecated
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

  /** ★ 旧档：家户身份的 actor 引用——<b>只准旧档迁移/兼容读调用</b>（新代码走 {@link #of(HouseholdId)}）。 */
  @Deprecated
  public static ActorRef of(CohortKey cohort) {
    return new ActorRef(ActorKind.HOUSEHOLD, idOf(cohort));
  }

  /**
   * ★ 旧档反查：actor 引用 → 旧视图（{@link #of(CohortKey)} 的逆）——<b>只准旧档迁移/兼容读调用</b>。
   *
   * <p>★ 它<b>不认</b>带 {@code legacy-} 前缀的新 actor id（那是 {@link #householdOf(ActorRef)} 的职责）。
   *
   * <p>★ <b>宁抛不静默</b>：① 种类不是 {@code HOUSEHOLD} ⇒ 抛；② 段数不为 3、或任一段为空 ⇒ 抛。
   */
  @Deprecated
  public static CohortKey cohortOf(ActorRef actor) {
    if (actor == null) {
      throw new IllegalArgumentException("ActorRef 不得为 null");
    }
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalArgumentException(
          "只有 HOUSEHOLD 才是家户（裁定 S1 复用该档）：本引用是 " + actor.kind() + " / " + actor);
    }
    return legacyCohortOfActorId(actor.id());
  }

  /** 旧三段 actor id（{@code <q>_<r>:<residence>:<stratum>}）→ 旧视图；两处旧档入口共用这一份解析。 */
  private static CohortKey legacyCohortOfActorId(String id) {
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
