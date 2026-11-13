package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.NodeRef;
import org.junit.jupiter.api.Test;

/** D-036：节点地址 {@code treeXXX-tickXXX/...} 的语法与往返。 */
class NodeAddressTest {

  @Test
  void parsesNodeAndEntityPath() {
    NodeAddress address = NodeAddress.parse("tree1-360/unit/army/vg-berlin");

    assertThat(address.node()).isEqualTo(new NodeRef(new BranchId("tree1"), 360L));
    assertThat(address.path()).containsExactly("unit", "army", "vg-berlin");
    assertThat(address.type()).contains("unit");
    assertThat(address.rest()).containsExactly("army", "vg-berlin");
    assertThat(address.canonical()).isEqualTo("tree1-360/unit/army/vg-berlin");
  }

  @Test
  void branchMayContainHyphenTickIsTrailingNumber() {
    NodeAddress address = NodeAddress.parse("tree-alpha-42/hex/1,1");

    assertThat(address.node().branch()).isEqualTo(new BranchId("tree-alpha"));
    assertThat(address.node().tick()).isEqualTo(42L);
  }

  @Test
  void nodeOnlyAddressIsAllowed() {
    NodeAddress address = NodeAddress.parse("tree1-0");

    assertThat(address.path()).isEmpty();
    assertThat(address.type()).isEmpty();
    assertThat(address.canonical()).isEqualTo("tree1-0");
    assertThatThrownBy(address::requireEntityPath)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("缺少实体路径");
  }

  @Test
  void looksLikeNodeAddress() {
    assertThat(NodeAddress.looksLikeNodeAddress("tree1-360/unit/army/x")).isTrue();
    assertThat(NodeAddress.looksLikeNodeAddress("tree1-0")).isTrue();
    assertThat(NodeAddress.looksLikeNodeAddress("unit:u-1")).isFalse();
    assertThat(NodeAddress.looksLikeNodeAddress("tree1-")).isFalse();
    assertThat(NodeAddress.looksLikeNodeAddress("tree1-x")).isFalse();
    assertThat(NodeAddress.looksLikeNodeAddress("-360")).isFalse();
  }

  @Test
  void rejectsMalformedNodeSegment() {
    assertThatThrownBy(() -> NodeAddress.parse("tree1/unit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("branch>-<tick");
    assertThatThrownBy(() -> NodeAddress.parse("tree1-x/unit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick");
    assertThatThrownBy(() -> NodeAddress.parse("-360/unit"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NodeAddress.parse("tree1-99999999999999999999/unit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出");
  }

  @Test
  void rejectsBadPathSegments() {
    assertThatThrownBy(() -> NodeAddress.parse("tree1-0/unit//x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空段");
    assertThatThrownBy(() -> NodeAddress.parse("tree1-0/unit/../x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("..");
    assertThatThrownBy(() -> NodeAddress.parse("tree1 0/unit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白");
    assertThatThrownBy(() -> NodeAddress.parse(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
  }

  @Test
  void canonicalRoundTrips() {
    for (String text :
        new String[] {
          "tree1-0",
          "tree1-360/hex/1,1",
          "tree1-360/region/小世界",
          "tree1-360/unit/army/vg-berlin",
          "tree1-350/battle/berlinbattle",
          "tree1-350/battle/berlinbattle/stage/s1",
          "tree-alpha-42/unit/gov/gov-central"
        }) {
      assertThat(NodeAddress.parse(text).canonical()).isEqualTo(text);
    }
  }
}
