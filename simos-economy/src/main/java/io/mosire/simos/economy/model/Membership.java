package io.mosire.simos.economy.model;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.social.api.id.PeopleLotId;

/**
 * ★★ <b>成员份额</b>（S1）："人口批次 {@code lot} 里有 {@code count} 人属于家户 {@code household}"。
 *
 * <pre>
 * Σ_{lot 的每条 membership} count == PopulationGroup.count(lot)   // 由 app 协调器读 social 侧判死（S1.4 后置不变量）
 * </pre>
 *
 * <p>★★ <b>为什么人口批次不能整批归一个家户</b>：{@code PopulationGroup} 是"年龄×性别×居住地"的统计批次（social 的权威），
 * 而家户是经济主体；同一批次的人可以分别属于不同家户（部分人口迁移的第一条前提）。{@code Workforce} 用 {@code count} 把这条关系显式记下来：人口总量守恒 ⇒
 * {@code Σ count} 逐批可核。
 *
 * <p>★ <b>量纲</b>：人（整数）；{@code count ≥ 0}（0 份额是合法状态：人迁走后的空壳份额，保留它的身份直到迁移器清理）。 ★
 * <b>它是存量</b>（不是本期发生额）：出生/死亡由 app 协调器在同一 revision 内按 count 权重摊回（S1.4.1）。
 *
 * @param id 稳定身份（{@code membership-<lot>-<household>}；见 {@link #idOf(PeopleLotId, HouseholdId)}）
 * @param lot 人口批次（真值源在 social；本类型只持稳定身份）；不得为 null
 * @param household 成员归属的家户；不得为 null
 * @param count 本批次里属于该家户的人数；不得为负
 */
public record Membership(MembershipId id, PeopleLotId lot, HouseholdId household, long count) {

  public Membership {
    if (id == null) {
      throw new IllegalArgumentException("Membership.id 不得为 null");
    }
    if (lot == null) {
      throw new IllegalArgumentException("Membership.lot 不得为 null");
    }
    if (household == null) {
      throw new IllegalArgumentException("Membership.household 不得为 null");
    }
    if (count < 0L) {
      throw new IllegalArgumentException("Membership.count 不得为负: " + count);
    }
  }

  /**
   * ★★ 成员份额 id 的唯一拼写点：{@code membership-<lot>-<household>}（不含 {@code "."}，见 {@link MembershipId}）。
   */
  public static MembershipId idOf(PeopleLotId lot, HouseholdId household) {
    if (lot == null) {
      throw new IllegalArgumentException("Membership.idOf 的 lot 不得为 null");
    }
    if (household == null) {
      throw new IllegalArgumentException("Membership.idOf 的 household 不得为 null");
    }
    return new MembershipId("membership-" + lot.value() + "-" + household.value());
  }
}
