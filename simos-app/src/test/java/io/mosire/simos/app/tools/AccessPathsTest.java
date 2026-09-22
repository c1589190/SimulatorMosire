package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceScope;
import org.junit.jupiter.api.Test;

/**
 * Task 2 判据（spec §3.3）：资源路径语法助手**逐字**拼对。
 *
 * <p>★ **为什么逐字断言**：这些字符串是**权限围栏**的输入，而 {@link ResourceScope} 的匹配是**段边界前缀**—— 拼错一段（多一条 `/`、少一个段、把
 * `_` 写成 `/`）**不会抛任何异常**，只会让"该看见的看不见 / 不该看见的看得见"。 判"非空"或"包含 q"这类弱断言对这种失效毫无判别力，所以这里写死期望串、并同时钉住命名空间。
 *
 * <p>★ **坐标照写**：{@code q_r} 用**下划线**分隔（与 {@code ToolSupport#canonicalHex} 的 {@code hex.<q>_<r>}
 * 同源），负号原样带（真档的 hex 大量落在负坐标）。
 */
class AccessPathsTest {

  @Test
  void regionPathUsesTheMapNamespaceAndARegionSegment() {
    ResourceId id = ToolSupport.resourceRegion("demo", "701");

    assertThat(id.namespace()).isEqualTo("map");
    assertThat(id.path()).isEqualTo("demo/region/701");
    assertThat(id.fullId()).isEqualTo("map:demo/region/701");
  }

  @Test
  void hexPathKeepsTheUnderscoreSeparatorAndNegativeSigns() {
    ResourceId id = ToolSupport.resourceHex("demo", -5, -59);

    assertThat(id.namespace()).isEqualTo("map");
    assertThat(id.path()).isEqualTo("demo/hex/-5_-59");
    assertThat(id.fullId()).isEqualTo("map:demo/hex/-5_-59");
  }

  @Test
  void hexPathOnPositiveCoordinatesIsStillUnderscoreSeparated() {
    assertThat(ToolSupport.resourceHex("Map1", 3, 12).path()).isEqualTo("Map1/hex/3_12");
  }

  @Test
  void unitPathIsTheBareUnitIdUnderTheUnitNamespace() {
    ResourceId id = ToolSupport.resourceUnit("u-1");

    assertThat(id.namespace()).isEqualTo("unit");
    assertThat(id.path()).isEqualTo("u-1");
    assertThat(id.fullId()).isEqualTo("unit:u-1");
  }

  @Test
  void sdPathsAreKindThenId() {
    assertThat(ToolSupport.resourceSd("nation", "FRA").fullId()).isEqualTo("sd:nation/FRA");
    assertThat(ToolSupport.resourceSd("army", "a1").fullId()).isEqualTo("sd:army/a1");
    assertThat(ToolSupport.resourceSd("decision-maker", "dm-a").fullId())
        .as("kind 本身可含连字符（spec §3.3 的 decision-maker）")
        .isEqualTo("sd:decision-maker/dm-a");
    assertThat(ToolSupport.resourceSd("combat", "c1").fullId()).isEqualTo("sd:combat/c1");
  }

  /**
   * 段边界语义的**正向**证明：拼出来的前缀必须让 {@link ResourceScope#allows} 认自己那一条。
   *
   * <p>★ 反向（"不含邻格的兄弟资源"）**不在此断言**——那是 {@code ResourceScope} 自己的段边界职责，本类只负责拼对串。
   */
  @Test
  void thePiecesComposeIntoAPrefixThatAllowsItsOwnResource() {
    ResourceScope regionScope = ResourceScope.of(ToolSupport.resourceRegion("demo", "701").path());
    ResourceScope hexScope = ResourceScope.of(ToolSupport.resourceHex("demo", -5, -59).path());

    assertThat(regionScope.allows(ToolSupport.resourceRegion("demo", "701").path())).isTrue();
    assertThat(hexScope.allows(ToolSupport.resourceHex("demo", -5, -59).path())).isTrue();
    assertThat(hexScope.allows(ToolSupport.resourceHex("demo", -5, -58).path()))
        .as("相邻格是不同的资源")
        .isFalse();
  }
}
