package io.mosire.simos.core.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 事件类型常量表**冻结**（C20 / spec §7.1）：八种，多一个少一个都要红。
 *
 * <p>★★ **为什么值得单写一个用例**：这八个串是**跨版本兼容面**——历史库里的 {@code events.type} 是裸字符串，
 * 改一个字母等于让旧档读不出来（没有任何编译期检查兜得住）。故在这里**逐字钉死**，改动必须是有意识的。
 *
 * <p>★ 特别是 {@code simos.revision.created} **必须不在**表里：spec §7.1 冻结的八种里没有它， 而它是个**极容易被顺手加进来**的名字（"落了
 * revision 嘛，发一条多自然"）——故专门为它写一条反向断言。 {@code committed} 才是"revision 落了"的那条事件。
 */
class EventTypesTest {

  /** spec §7.1 的八种，**逐字**抄自规范（不是抄自 {@link EventTypes}——从被测对象抄断言等于问它"你觉得自己对吗"）。 */
  private static final List<String> FROZEN =
      List.of(
          "simos.command.received",
          "simos.command.rejected",
          "simos.command.conflicted",
          "simos.command.committed",
          "simos.time.advance.started",
          "simos.time.advance.finished",
          "simos.module.proposal",
          "simos.timeline.conflict");

  @Test
  void allHoldsExactlyTheEightFrozenTypes() {
    assertThat(EventTypes.ALL)
        .as("C20 / spec §7.1 冻结八种；实得 %s", EventTypes.ALL)
        .containsExactlyElementsOf(FROZEN);
    assertThat(EventTypes.ALL).hasSize(8);
  }

  /** ★ 反向断言：{@code simos.revision.created} **不在**表里（它是最容易被顺手加进来的名字，见类注）。 */
  @Test
  void revisionCreatedIsDeliberatelyNotAType() {
    assertThat(EventTypes.ALL)
        .as("revision 落盘由 simos.command.committed 承载，不再另发一条")
        .doesNotContain("simos.revision.created");
    assertThat(EventTypes.isKnown("simos.revision.created")).isFalse();
  }

  @Test
  void everyFrozenTypeIsKnown() {
    for (String type : FROZEN) {
      assertThat(EventTypes.isKnown(type)).as("%s 必须在册", type).isTrue();
    }
  }

  @Test
  void unknownTypeIsNotKnown() {
    assertThat(EventTypes.isKnown("simos.command.teleported")).isFalse();
    assertThat(EventTypes.isKnown("")).isFalse();
  }

  /** {@code ALL} 是不可变清单：外部拿到它**改不动**表（改了就等于运行时偷偷改了冻结面）。 */
  @Test
  void allIsImmutable() {
    assertThatThrownBy(() -> EventTypes.ALL.add("simos.bogus"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ {@code isKnown(null)} **抛**而不是返回 false：吞掉 null 会让"类型串丢了"伪装成"类型不认得"。 */
  @Test
  void isKnownRejectsNull() {
    assertThatThrownBy(() -> EventTypes.isKnown(null)).isInstanceOf(NullPointerException.class);
  }
}
