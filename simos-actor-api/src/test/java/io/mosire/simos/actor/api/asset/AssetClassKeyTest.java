package io.mosire.simos.actor.api.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@link AssetClassKey} 的契约护栏（S1 spec §2.3；铁律 1 的"裸值 + parse/toString 三件套"）。
 *
 * <p>★★ **为什么"顺序"是这种类型的生死线**：它是 Map 的键（聚合键 {@code (owner, hex, assetClass)}）。 若 {@code
 * Map.of("arable","true","quality","B")} 与 {@code Map.of("quality","B","arable","true")}
 * 落成两个不同的身份，同一块地就会有**两份产权**，而且是**静默**的 —— 没有任何一处会报错。
 *
 * <p>★ **判别力**：把构造期的排序去掉（原样保留调用方的迭代顺序）⇒ 第 1 条红；把 {@code parse} 改成静默接受词表外的粗类型 ⇒ 第 4 条红。
 *
 * <p>★ **后三条（评审修复轮补的）**：{@code kind == null} / 分隔符 / 空白 —— 这三条规则原先**只有实现、没有断言**（删掉那些分支 5/5
 * 依然全绿）。补法照 "**一条规则一条断言**"，每条各自变异自证。
 */
class AssetClassKeyTest {

  /** ★★ **qualities 的顺序不许影响身份** —— 否则同一块地会有两份产权。 */
  @Test
  void keyOrderInQualitiesDoesNotChangeIdentity() {
    AssetClassKey a = new AssetClassKey(AssetKind.LAND, Map.of("arable", "true", "quality", "B"));
    AssetClassKey b = new AssetClassKey(AssetKind.LAND, Map.of("quality", "B", "arable", "true"));
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).hasToString(b.toString());
  }

  /** ★ spec §2.3 点名的那个实例。 */
  @Test
  void landArableQualityBIsExpressible() {
    assertThat(AssetClassKey.land(Map.of("arable", "true", "quality", "B")))
        .hasToString("LAND|arable=true|quality=B");
  }

  /** ★ 往返：`parse(toString()) == 自身`（它是 Map 的键，这条挂了会**静默丢产权**）。 */
  @Test
  void roundTripsThroughItsCanonicalString() {
    for (String text : List.of("LAND|arable=true|quality=B", "CATTLE", "TOOL|tech=T1")) {
      assertThat(AssetClassKey.parse(text)).hasToString(text);
    }
  }

  /** ★ **词表外即抛**（fail-closed）；qualities 的键值不许含分隔符（否则规范串不可逆）。 */
  @Test
  void rejectsMalformedText() {
    assertThatThrownBy(() -> AssetClassKey.parse("NOPE|a=b"))
        .as("粗类型词表外必须抛，且消息里点名那个值")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NOPE");
    assertThatThrownBy(() -> AssetClassKey.parse("LAND|noEqualsSign"))
        .as("qualities 段没有 `=` ⇒ 不可逆 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetClassKey.parse("LAND|k=a|k=b"))
        .as("★ 同一个键出现两次 ⇒ 规范串不可逆（解析回来只剩一个）⇒ 必须抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 无 qualities 的粗类型也算合法键（`CATTLE` 就是一个）；`null` 与空 Map 等价。 */
  @Test
  void bareKindIsAValidKey() {
    assertThat(AssetClassKey.parse("CATTLE"))
        .isEqualTo(new AssetClassKey(AssetKind.CATTLE, Map.of()));
    assertThat(new AssetClassKey(AssetKind.CATTLE, null))
        .as("null qualities 归一成空表，不是 NPE")
        .isEqualTo(new AssetClassKey(AssetKind.CATTLE, Map.of()));
  }

  /** ★ **null 粗类型即抛**：它是身份的第一元，静默成键会造出一个"没有种类"的产权。 */
  @Test
  void rejectsNullKind() {
    assertThatThrownBy(() -> new AssetClassKey(null, Map.of("arable", "true")))
        .as("kind 不得为 null（`null = 还没想好哪种资产`不是一种资产）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★★ **键/值里不许出现分隔符** —— 这条规则**承载可逆性**，是整套 fail-closed 里最不能少的一条。
   *
   * <p>删掉它 ⇒ `new AssetClassKey(LAND, Map.of("a|b", "1"))` 会产出规范串 `"LAND|a|b=1"`， 而 {@code parse}
   * **自己**拒绝它（`a` 段没有 `=`）—— 即构造函数造得出一个"自己的规范串解析不回来"的键。 故本用例同时钉住**两个入口**（构造器 /
   * parse）与**正向不变量**（构造得出来的键，必能解析回自身）。
   */
  @Test
  void rejectsSeparatorsInsideKeysAndValues() {
    assertThatThrownBy(() -> new AssetClassKey(AssetKind.LAND, Map.of("a|b", "1")))
        .as("★ 键里含 `|` ⇒ 规范串会被切成两段 ⇒ 抛（构造器入口）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetClassKey.parse("LAND|k=a=b"))
        .as("值里含 `=` ⇒ 规范串不可逆（parse 按**第一个** `=` 切）⇒ 抛（parse 入口）")
        .isInstanceOf(IllegalArgumentException.class);

    // ★ 正向不变量：凡构造得出来的键，其规范串必能被 parse 解析回**同一个**键。
    AssetClassKey key = new AssetClassKey(AssetKind.LAND, Map.of("quality", "B", "arable", "true"));
    assertThat(AssetClassKey.parse(key.toString())).isEqualTo(key);
  }

  /** ★ 键/值不得为**空白或 null**（退化输入）。口径同 {@code SocialClassId} / {@code ActorRef.id}：空白不是名字。 */
  @Test
  void rejectsBlankOrNullKeysAndValues() {
    assertThatThrownBy(() -> new AssetClassKey(AssetKind.LAND, Map.of("", "1")))
        .as("空白键 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssetClassKey(AssetKind.LAND, Map.of("k", "  ")))
        .as("空白值 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssetClassKey(AssetKind.LAND, Collections.singletonMap("k", null)))
        .as("null 值 ⇒ 抛（**不是 NPE**：构造器要的是 IAE）")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
