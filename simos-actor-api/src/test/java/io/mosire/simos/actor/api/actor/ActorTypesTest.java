package io.mosire.simos.actor.api.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ {@link ActorRef} / {@link ActorKind} 的契约护栏（设计稿 §2；铁律 1）。
 *
 * <p>★ **为什么在这里**（S1 阶段 2，裁定 R7 的 A 段）：这两个类型的家是 {@code io.mosire.simos.actor.api.actor}，
 * 而在上移之前它们的护栏住在 {@code EconomyIdsTest}（那时定义在 economy-api）。**契约测试跟着类型走** ——
 * 留在旧模块会变成"类型搬了家、护栏还指着旧地址"。四条断言**一字不改地搬过来**（不是复制：旧处那四条已删）。
 *
 * <p>★★ 其中 {@link #actorRefParseHasBothRefusalReasons} 钉住 {@code ActorRef.parse} 的**两参**签名 —— 它不是
 * {@code toString()} 的逆（{@code "UNIT:u-1"} 喂不回去）。这是裁定 R4 的**故意不对称**：上移不改变它， 也不许顺手"修好"（那会改读侧契约）。
 */
class ActorTypesTest {

  /**
   * ★★ **逐值断言**（R2 由四档扩到七档）：扩枚举是 {@code LaborAllocation.actor} 选了 {@code ActorRef} 的代价 （第三阶段设计稿
   * §八.1 明写"要扩枚举 + 同步改 {@code EconomyIdsTest} 的逐值断言"，故本条就是那份"连带改"）。★ S1 阶段 2：该断言随类型从 {@code
   * EconomyIdsTest} 搬到本类。
   *
   * <p>★ 前四档的**次序与拼写一字不动**（它们已进过 JSON：{@code LedgerCodec} 写 {@code kind} 用 {@code name()}）；
   * 新增三档追加在**末尾**，理由同上——插在中间会让"词表位置"这种没进线格式的东西产生 diff 噪声。
   */
  @Test
  void actorKindCoversTheSevenDocumentedKinds() {
    assertThat(ActorKind.values())
        .as("设计稿 §2/§4/§5/§7 的四类主体 + R2 的生产关系三类（家户/庄园/作坊）")
        .containsExactly(
            ActorKind.PEOPLE_LOT,
            ActorKind.UNIT,
            ActorKind.GOVERNMENT,
            ActorKind.ORGANIZATION,
            ActorKind.HOUSEHOLD,
            ActorKind.ESTATE,
            ActorKind.WORKSHOP);
  }

  @Test
  void actorKindParseRejectsTextOutsideTheVocabularyAndListsLegalValues() {
    assertThatThrownBy(() -> ActorKind.parse("NOPE"))
        .as("词表外的种类必须即抛，且消息里列出合法值")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PEOPLE_LOT")
        .hasMessageContaining("UNIT")
        .hasMessageContaining("GOVERNMENT")
        .hasMessageContaining("ORGANIZATION")
        .hasMessageContaining("HOUSEHOLD")
        .hasMessageContaining("ESTATE")
        .hasMessageContaining("WORKSHOP");

    assertThatThrownBy(() -> ActorKind.parse(null))
        .as("null 种类即抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActorKind.parse("  "))
        .as("空白种类即抛")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(ActorKind.parse("UNIT")).isEqualTo(ActorKind.UNIT);
  }

  @Test
  void actorRefValidatesKindAndId() {
    assertThatThrownBy(() -> new ActorRef(null, "u-1"))
        .as("kind 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActorRef(ActorKind.UNIT, null))
        .as("id 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActorRef(ActorKind.UNIT, "  "))
        .as("id 不得为空白")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(new ActorRef(ActorKind.UNIT, "u-1").toString()).isEqualTo("UNIT:u-1");
  }

  @Test
  void actorRefParseHasBothRefusalReasons() {
    // 拒因 1：kind 词表外
    assertThatThrownBy(() -> ActorRef.parse("NOPE", "u-1"))
        .as("kind 词表外即抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PEOPLE_LOT");

    // 拒因 2：id 空白
    assertThatThrownBy(() -> ActorRef.parse("UNIT", "  "))
        .as("id 空白即抛")
        .isInstanceOf(IllegalArgumentException.class);

    // 合法：kind 词表内 + id 非空白
    assertThat(ActorRef.parse("GOVERNMENT", "g-1"))
        .isEqualTo(new ActorRef(ActorKind.GOVERNMENT, "g-1"));
  }
}
