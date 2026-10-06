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
 *
 * <p>★★ <b>S1 阶段 2 修复轮追加</b>：{@code toString()} 的逆是<b>新增</b>的 {@link
 * ActorRef#parseCanonical(String)} （两参签名仍一字未动）—— 本仓"裸值 {@code toString()} + {@code static
 * parse}"三件套。下面四条断言钉住它， 其中 ★ <b>含分隔符的 id</b> 那条是关键：它把"按<b>第一个</b>分隔符切"这条判据钉在上游， 于是各切片（{@code
 * FieldDelta} 的键解析）**不需要**自带第二份格式知识。
 */
class ActorTypesTest {

  /**
   * ★★ **逐值断言**（P2-A §13.3 由七档收敛到五档）：{@code ESTATE} / {@code WORKSHOP} 已随"庄园/作坊 = 生产方式，不是 ActorRef
   * 种类"的整体退役删除，本断言跟着改为五档。前四档的**次序与拼写一字不动** （它们已进过 JSON：{@code LedgerCodec} 写 {@code kind} 用 {@code
   * name()}）；{@code HOUSEHOLD} 仍在末尾。
   */
  @Test
  void actorKindCoversTheFiveDocumentedKinds() {
    assertThat(ActorKind.values())
        .as("设计稿 §2/§4/§5/§7 的四类主体 + P2-A 唯一账户主体家户（庄园/作坊已退役）")
        .containsExactly(
            ActorKind.PEOPLE_LOT,
            ActorKind.UNIT,
            ActorKind.GOVERNMENT,
            ActorKind.ORGANIZATION,
            ActorKind.HOUSEHOLD);
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
        .hasMessageContaining("HOUSEHOLD");

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

  /**
   * ★★ <b>{@code parseCanonical(toString()) == 自身}</b>（S1 阶段 2 修复轮裁定）。
   *
   * <p>★ 为什么这条是承重墙：本类型是 {@code FieldDelta} 的**键**（键由 {@code toString()} 产出、重建时用 {@code parse} 还原）⇒
   * 这条挂了会**静默丢主体**（往返重建出另一个身份，或当场炸）。
   *
   * <p>★★ <b>判别力全在第二个夹具上</b>：它的 {@code id} <b>自带两个分隔符</b>（人口批次的 id 就是这个形状） ⇒
   * 按<b>最后一个</b>分隔符切、或按全部切，都当场红 ——"按<b>第一个</b>切"是唯一能还原的切法。
   */
  @Test
  void actorRefCanonicalRoundTripsIncludingIdsThatContainTheSeparator() {
    ActorRef plain = new ActorRef(ActorKind.UNIT, "u-1");
    ActorRef colonHeavy = new ActorRef(ActorKind.PEOPLE_LOT, "rural:0_0:MALE:1");

    assertThat(plain).hasToString("UNIT:u-1");
    assertThat(colonHeavy)
        .as("前置：夹具的 id 确实自带两个分隔符（否则下面那条没有判别力）")
        .hasToString("PEOPLE_LOT:rural:0_0:MALE:1");

    assertThat(ActorRef.parseCanonical(plain.toString())).isEqualTo(plain);
    assertThat(ActorRef.parseCanonical(colonHeavy.toString()))
        .as("id 里的分隔符必须原样回到 id 那一侧")
        .isEqualTo(colonHeavy);
    // ★ 逐段还原（不是只比 equals）：段切错了但恰好 equals 相等的可能不存在，但读起来要能一眼看出切对了。
    assertThat(ActorRef.parseCanonical(colonHeavy.toString()).kind())
        .isEqualTo(ActorKind.PEOPLE_LOT);
    assertThat(ActorRef.parseCanonical(colonHeavy.toString()).id()).isEqualTo("rural:0_0:MALE:1");
  }

  /** ★ 没有分隔符 / 分隔符在首 / 分隔符在尾 ⇒ 一律抛（宁抛不静默，照 {@code ClassKey#parse} 的口径）。 */
  @Test
  void actorRefParseCanonicalRejectsTextWithoutAWellFormedSeparator() {
    for (String bad : new String[] {"UNIT", ":u-1", "UNIT:", "UNITu-1"}) {
      assertThatThrownBy(() -> ActorRef.parseCanonical(bad))
          .as("格式不对的规范串「%s」必须抛，且消息里点名", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("规范串");
    }
  }

  /** ★ 分隔符之前那段必须是 {@link ActorKind} 词表内的值（词表外即抛并列出合法值 —— 与两参形式同一份词表校验）。 */
  @Test
  void actorRefParseCanonicalRejectsKindsOutsideTheVocabulary() {
    assertThatThrownBy(() -> ActorRef.parseCanonical("NOPE:u-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PEOPLE_LOT")
        .hasMessageContaining("HOUSEHOLD");
  }

  /** ★ {@code null} / 空白即抛（空白不是身份）。 */
  @Test
  void actorRefParseCanonicalRejectsNullAndBlank() {
    assertThatThrownBy(() -> ActorRef.parseCanonical(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActorRef.parseCanonical("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
