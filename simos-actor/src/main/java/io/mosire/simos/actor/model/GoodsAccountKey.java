package io.mosire.simos.actor.model;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>家户账户的聚合键：一个家户一本账</b>（P2-A §13.3，2026-10-09 用户裁定）。
 *
 * <p>★★ <b>形状变化（如实记）</b>：改前是 {@code (ActorRef owner, HexCoord location)} —— 同一主体可在多格各有一本账，
 * 且 {@code ESTATE} / {@code WORKSHOP} 等非家户主体也持账。现在：
 *
 * <ul>
 *   <li><b>唯一主体 = 家户</b>：键就是 {@link HouseholdId}（不是 {@code ActorRef}：账户主体统一为家户，见 §13.3）；
 *   <li><b>没有 {@code HexCoord}</b>：位置从 {@code Household.location} 派生 —— 家户搬家，账自动跟走；
 *   <li>庄园/作坊不是账户主体：它们是生产方式/生产活动（{@code ProductionMode} / {@code ProductionOrganization} /
 *       {@code ProductionUnit}），投入/产出/收款走组织者/经营者<b>家户</b>的这本账。
 * </ul>
 *
 * <p>★★ <b>旧账户直接报废、不做迁移</b>（§13.3 的既定口径）：旧键 {@code <owner>|<hex>} 的 JSON 在新 codec 下解析即抛，
 * 旧世界重建。这里<b>不</b>保留 legacy 解析路径 —— 兼容读会把"旧账户还活着"这个错觉留进状态树。
 *
 * <p>★ <b>规范串 = 家户 id 本身</b>（{@link HouseholdId#toString()}）；{@link #parse(String)} 是它的逆
 * （{@code HouseholdId.parse}）。{@code FieldDelta}（{@code simos-util}）把状态表的键压成 {@code toString()} 的产物、
 * 重建时用 {@code parse} 还原 ⇒ 这一对是铁律 5 的键往返要求。
 *
 * @param household 账户主体（家户稳定身份）；不得为 null
 */
public record GoodsAccountKey(HouseholdId household) {

  public GoodsAccountKey {
    Objects.requireNonNull(household, "GoodsAccountKey.household 不得为 null");
  }

  /** 规范串 = 家户 id（既是变更集的 key，也是 JSON Map 的键）。 */
  @Override
  public String toString() {
    return household.value();
  }

  /**
   * 解析 {@link #toString()} 的产物（家户 id 的不透明文本；格式校验在 {@link HouseholdId#parse}）。
   *
   * @throws IllegalArgumentException 空白 / null（{@link HouseholdId#parse} 的口径）
   */
  public static GoodsAccountKey parse(String text) {
    return new GoodsAccountKey(HouseholdId.parse(text));
  }

  /**
   * ★★ <b>过渡期收口点（P2-A §13.3）：actor 引用 → 账户键</b>。
   *
   * <ul>
   *   <li>{@code HOUSEHOLD} actor ⇒ {@link HouseholdActors#householdOf} 反查出真实家户键（家户身份的唯一拼写点）；
   *   <li>其它 kind（UNIT / GOVERNMENT / ORGANIZATION …）⇒ <b>不再持账</b>：返回一个带
   *       {@value #RETIRED_NON_HOUSEHOLD_PREFIX} 前缀的键。它<b>永远取不到账</b>（不是坏数据、也不是抛），于是旧调用点会得到
   *       "这本账不存在"的具名结果而不是静默读到别人的账 —— 单位/政府国库改走家户账户属 P2-C。
   * </ul>
   *
   * <p>★ 它是旧 {@code (owner, hex)} 拼法在 P2-A 的唯一下沉点：P2-C 把政府/军队账改走家户后，本方法应删除。
   */
  @Deprecated
  public static GoodsAccountKey ofActor(ActorRef actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.kind() == ActorKind.HOUSEHOLD) {
      return new GoodsAccountKey(HouseholdActors.householdOf(actor));
    }
    return new GoodsAccountKey(HouseholdId.parse(RETIRED_NON_HOUSEHOLD_PREFIX + actor));
  }

  /** 旧非家户主体的占位键前缀（见 {@link #ofActor(ActorRef)}；永不与真实家户 id 相同）。 */
  @Deprecated public static final String RETIRED_NON_HOUSEHOLD_PREFIX = "retired-actor:";
}
