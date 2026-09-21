package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/** {@link RelativeOffset} 的逐值行为（spec §一.3 / P2：合法 HexCoord，不强制落图内）。 */
class RelativeOffsetTest {

  @Test
  void appliedToAddsTheComponents() {
    assertThat(new RelativeOffset(2, -1).appliedTo(new HexCoord(3, 4)))
        .isEqualTo(new HexCoord(5, 3));
  }

  @Test
  void aZeroOffsetIsTheIdentity() {
    HexCoord hex = new HexCoord(-7, 11);
    assertThat(new RelativeOffset(0, 0).appliedTo(hex)).isEqualTo(hex);
  }

  @Test
  void equalsIsByValue() {
    assertThat(new RelativeOffset(2, -1)).isEqualTo(new RelativeOffset(2, -1));
    assertThat(new RelativeOffset(2, -1)).isNotEqualTo(new RelativeOffset(-1, 2));
  }

  /**
   * ★ T10-a（T3-L1）：叠加溢出 int ⇒ **拒绝**，不得静默回绕。两个方向都判——只判"会抛"会让"把合法边界也拒了"蒙混过关。
   *
   * <p>P2 的"无范围约束"说的是偏移**本身**不设范围（不强制落图内）；叠加结果溢出是另一回事：`1 + Integer.MAX_VALUE`
   * 会回绕成负数，单位"瞬移"到地图另一头。此判据对 handler 路径与 codec 反序列化路径**同样**成立（`appliedTo` 是唯一叠加点）。
   */
  @Test
  void appliedToRejectsIntOverflowInsteadOfSilentlyWrapping() {
    assertThatThrownBy(() -> new RelativeOffset(Integer.MAX_VALUE, 0).appliedTo(new HexCoord(1, 0)))
        .as("q + dq 溢出 ⇒ 拒（旧实现静默得 -2147483648）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");
    assertThatThrownBy(
            () -> new RelativeOffset(0, Integer.MIN_VALUE).appliedTo(new HexCoord(0, -1)))
        .as("r + dr 向下溢出 ⇒ 拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");

    // 不溢出的大值边界仍必须通过（否则"拒溢出"会退化成"拒大偏移"）。
    assertThat(new RelativeOffset(Integer.MAX_VALUE, 0).appliedTo(new HexCoord(-1, 0)))
        .as("恰好不溢出 ⇒ 通过")
        .isEqualTo(new HexCoord(Integer.MAX_VALUE - 1, 0));
    assertThat(new RelativeOffset(Integer.MIN_VALUE, 0).appliedTo(new HexCoord(1, 0)))
        .isEqualTo(new HexCoord(Integer.MIN_VALUE + 1, 0));
  }
}
