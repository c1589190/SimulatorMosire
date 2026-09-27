package io.mosire.simos.economy.api.cohort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>家户 actor 身份的两条契约：<strong>文本形状</strong>钉死 + 往返恒等</b>
 *
 * <p>★★ <b>为什么要有本文件</b>（关账期变异自证跑出来的缺口）：「把 {@code SEGMENT_SEPARATOR} 从 {@code ":"} 改成 {@code
 * "|"}」这个变异体**当时没有任何用例变红** —— 既有引用点全是 {@code HouseholdActors.of(...)} / {@code cohortOf(...)}
 * 的**同时调用**（两侧读同一个常量 ⇒ 自洽），于是"id 的文本形状"这件事**一条断言都没有**。而它是一条真契约：
 *
 * <ul>
 *   <li>它是 {@code GoodsAccountKey} 的**主语那一半**（{@code owner|location}），形状里多一个 {@code "|"} 会把那条键切错；
 *   <li>它是**落盘**里 actor 的标识（状态表按键压成文本），换形状 = 换身份。
 * </ul>
 *
 * <p>★ 本文件把两件事分开钉：① {@link #actorIdIsPinnedToTheCanonicalShape()} 用**冻结字面量**钉形状 （换分隔符当场红）；② {@link
 * #identityRoundTripsThroughItsText()} 钉 {@code of} / {@code cohortOf} 互为逆 （换解析逻辑当场红）。★
 * 边界（如实记）：**磁盘往返**（SqliteStore 存取之后身份不变）仍由 {@code simos-core} 的存储用例与 600 天批次覆盖，本文件只钉文本契约本身。
 */
class HouseholdActorsTest {

  private static final HexCoord HEX = new HexCoord(3, -7);

  private static final CohortKey PEASANT =
      new CohortKey(HEX, ResidenceKind.RURAL, new SocialClassId("poor_peasant"));

  /** ★ 形状：{@code <q>_<r>:<residence>:<stratum>} —— 冻结字面量（不是把实现抄一遍）。 */
  @Test
  void actorIdIsPinnedToTheCanonicalShape() {
    assertThat(HouseholdActors.idOf(PEASANT))
        .as("★ 家户 actor 的 id 形状：3_-7:rural:poor_peasant（换分隔符 = 换身份 ⇒ 本条必须红）")
        .isEqualTo("3_-7:rural:poor_peasant");
    assertThat(HouseholdActors.of(PEASANT))
        .as("★ 种类的唯一取值：家户")
        .isEqualTo(new ActorRef(ActorKind.HOUSEHOLD, "3_-7:rural:poor_peasant"));
  }

  @Test
  void identityRoundTripsThroughItsText() {
    assertThat(HouseholdActors.cohortOf(HouseholdActors.of(PEASANT)))
        .as("★ cohortOf 是 of 的逆（逐字段恒等）")
        .isEqualTo(PEASANT);
    for (ResidenceKind residence : ResidenceKind.values()) {
      for (String stratum : new String[] {"poor_peasant", "rich_peasant", "landlord"}) {
        CohortKey key = new CohortKey(HEX, residence, new SocialClassId(stratum));
        assertThat(HouseholdActors.cohortOf(HouseholdActors.of(key)))
            .as("★ 往返对每个（居住类型 × 阶层）组合都恒等：%s", key)
            .isEqualTo(key);
      }
    }
  }

  @Test
  void nonHouseholdKindsAndMalformedTextAreRejectedLoudly() {
    assertThatThrownBy(
            () -> HouseholdActors.cohortOf(new ActorRef(ActorKind.WORKSHOP, "craft@3_-7")))
        .as("★ 作坊不是家户（把它的账当家户账读 ⇒ 静默答错）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HOUSEHOLD");
    assertThatThrownBy(
            () -> HouseholdActors.cohortOf(new ActorRef(ActorKind.HOUSEHOLD, "3_-7|rural")))
        .as("★ 少一段 ⇒ 抛（不猜阶层）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> HouseholdActors.cohortOf(new ActorRef(ActorKind.HOUSEHOLD, "3_-7||poor_peasant")))
        .as("★ 空段 ⇒ 抛（不把空居住类型当成一档）")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
