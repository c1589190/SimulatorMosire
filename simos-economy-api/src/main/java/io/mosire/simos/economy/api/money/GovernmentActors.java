package io.mosire.simos.economy.api.money;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.GovernmentId;

/**
 * ★★ <b>政府身份（{@link GovernmentId}）与 actor 身份（{@link ActorRef}）之间的唯一拼写点</b>（E3；照 {@code
 * HouseholdActors} 的形制）。
 *
 * <p>★★ <b>为什么需要它</b>：政府是发行主体，而国库是 actor 切片里的一本 {@code HouseholdInventory}；同一个身份因此有两个名字空间 ——财政状态表里的
 * {@link GovernmentId} 与账户里的 actor id。两处各拼一份 = 同一个身份的第二个拼写点（本仓明令禁止）。
 *
 * <p>★ actor id 的前缀是 {@code gov-}（唯一拼写点在本文件）；反查时前缀必须存在，且种类必须是 {@link ActorKind#GOVERNMENT}。 ★ 形制与
 * {@code HouseholdActors} 一致：id 里的 {@code '|'} 换成 {@code ':'}（账户键 {@code HouseholdAccountKey} 按第一个
 * {@code '|'} 切，政府 actor id 因此不得带 {@code '|'}）。
 */
public final class GovernmentActors {

  /** 政府 actor id 的固定前缀（唯一拼写点）。 */
  private static final String PREFIX = "gov-";

  private GovernmentActors() {}

  /**
   * ★★ <b>政府 actor 的 id</b>（唯一拼写点）= {@code "gov-" + GovernmentId.value()} 里的 {@code '|'} 换成 {@code
   * ':'}。
   */
  public static String idOf(GovernmentId government) {
    if (government == null) {
      throw new IllegalArgumentException("GovernmentId 不得为 null（政府身份由它决定）");
    }
    return PREFIX + government.value().replace('|', ':');
  }

  /** 政府身份的 actor 引用（种类恒为 {@link ActorKind#GOVERNMENT}）。 */
  public static ActorRef of(GovernmentId government) {
    return new ActorRef(ActorKind.GOVERNMENT, idOf(government));
  }

  /**
   * ★★ <b>反查：actor 引用 → 政府身份</b>（{@link #of(GovernmentId)} 的逆）。
   *
   * <p>★ <b>宁抛不静默</b>：种类不是 {@code GOVERNMENT}、或 id 没有 {@code gov-} 前缀、或前半段为空 ⇒ 抛。
   */
  public static GovernmentId governmentOf(ActorRef actor) {
    if (actor == null) {
      throw new IllegalArgumentException("ActorRef 不得为 null");
    }
    if (actor.kind() != ActorKind.GOVERNMENT) {
      throw new IllegalArgumentException("只有 GOVERNMENT 才是政府：本引用是 " + actor.kind() + " / " + actor);
    }
    if (!actor.id().startsWith(PREFIX) || actor.id().length() == PREFIX.length()) {
      throw new IllegalArgumentException("政府 actor id 必须以 " + PREFIX + " 开头且有后半段: " + actor.id());
    }
    return GovernmentId.parse(actor.id().substring(PREFIX.length()).replace(':', '|'));
  }
}
