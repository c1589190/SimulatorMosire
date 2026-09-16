package io.mosire.simos.util.info;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec §十-D4：按 key + 时刻取值、有效期左闭右开、`put` 返回新实例。 */
class InMemoryInfoSystemTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");
  private static final SubjectId SOURCE = new SubjectId("map.hex", "h-0001");
  private static final TimeRange SINCE_ZERO = TimeRange.since(SimosTimestamp.of(0));

  @Test
  void readsByKeyAndMoment() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "河口渡口", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("icon", "anchor", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(5))).map(InfoEntry::value).contains("河口渡口");
    assertThat(info.get(HEX, "icon", SimosTimestamp.of(5)))
        .map(InfoEntry::value)
        .contains("anchor");
    assertThat(info.get(HEX, "unknown", SimosTimestamp.of(5))).isEmpty();
    assertThat(info.get(Address.parse("map:Map1"), "alias", SimosTimestamp.of(5))).isEmpty();
  }

  @Test
  void validityWindowIsHalfOpen() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(
                HEX,
                entry(
                    "alias",
                    "旧名",
                    new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(10)))))
            .put(HEX, entry("alias", "新名", TimeRange.since(SimosTimestamp.of(10))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(9))).map(InfoEntry::value).contains("旧名");
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(10)))
        .map(InfoEntry::value)
        .contains("新名"); // 切换点归新条目
  }

  @Test
  void theLastInsertedOverlappingEntryWins() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "先写的", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("alias", "后写的", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(1))).map(InfoEntry::value).contains("后写的");
  }

  @Test
  void putReturnsANewInstanceAndLeavesTheOriginalUntouched() {
    InMemoryInfoSystem base = InMemoryInfoSystem.empty();
    InMemoryInfoSystem next =
        base.put(HEX, entry("alias", "甲", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(base.get(HEX, "alias", SimosTimestamp.of(0))).isEmpty();
    assertThat(next.get(HEX, "alias", SimosTimestamp.of(0))).isPresent();
  }

  // ---- 以下为 brief 未给、控制器点名补的守卫用例（brief 只有取值的 4 条，守卫全空转） ----

  /** 空 key 与 null key 合并成同一条 IAE；判据连字段名一起钉，哪一臂缺席都能指名道姓地转红。 */
  @Test
  void blankOrNullKeyIsRejected() {
    assertThatThrownBy(() -> new InfoEntry(" ", "值", SINCE_ZERO, SOURCE, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key");
    assertThatThrownBy(() -> new InfoEntry(null, "值", SINCE_ZERO, SOURCE, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key");
  }

  /** 四条 `Objects.requireNonNull` 逐臂钉字段级消息——只钉 NPE 类型会被其他守卫同型掩盖（T4-M7 的坑）。 */
  @Test
  void nullPartsAreRejected() {
    assertThatNullPointerException()
        .isThrownBy(() -> new InfoEntry("alias", null, SINCE_ZERO, SOURCE, Optional.empty()))
        .withMessage("value");
    assertThatNullPointerException()
        .isThrownBy(() -> new InfoEntry("alias", "值", null, SOURCE, Optional.empty()))
        .withMessage("valid");
    assertThatNullPointerException()
        .isThrownBy(() -> new InfoEntry("alias", "值", SINCE_ZERO, null, Optional.empty()))
        .withMessage("source");
    assertThatNullPointerException()
        .isThrownBy(() -> new InfoEntry("alias", "值", SINCE_ZERO, SOURCE, null))
        .withMessage("note");
  }

  /** `put` 的两条 `requireNonNull` 同样没有 brief 用例；消息判据必须钉死（否则下游的第二次 NPE 会顶包）。 */
  @Test
  void putRejectsNullSubjectAndEntry() {
    InMemoryInfoSystem info = InMemoryInfoSystem.empty();
    assertThatNullPointerException()
        .isThrownBy(() -> info.put(null, entry("alias", "甲", SINCE_ZERO)))
        .withMessage("subject");
    assertThatNullPointerException().isThrownBy(() -> info.put(HEX, null)).withMessage("entry");
  }

  private static InfoEntry entry(String key, Object value, TimeRange valid) {
    return new InfoEntry(key, value, valid, new SubjectId("map.hex", "h-0001"), Optional.empty());
  }
}
