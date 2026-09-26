package io.mosire.simos.actor.api.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
