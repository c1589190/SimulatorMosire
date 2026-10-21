package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L1/L4：土地组件纯操作的正例 + 负例。
 *
 * <p>判据来自 `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md` §3/§4： 同 type 下除
 * id/quantity 外属性全同 ⇒ 合并（保留字典序最小 id、数量相加）；不同 owner/进度不合并； 部分转移拆分守恒；整块转移 id 不变；0/负数量与超卖失败；序号不回收。
 */
class LandComponentOperationsTest {

  private static final HexCoord HEX = new HexCoord(0, 0);

  @Test
  void seedSingleUsesStableCoordAwareIdAndDerivedTotal() {
    HexLand land =
        LandComponentOperations.seedSingle(
            HEX,
            LandTypeId.ARABLE,
            3_100_000L,
            Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a")));

    LandTypeArea area = land.byType().get(LandTypeId.ARABLE);
    assertThat(area).isNotNull();
    assertThat(area.components())
        .singleElement()
        .satisfies(
            component -> {
              assertThat(component.id().value()).isEqualTo("arable@0_0:0");
              assertThat(component.quantityMilliMu()).isEqualTo(3_100_000L);
              assertThat(component.attributes())
                  .containsEntry(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
            });
    assertThat(area.nextComponentOrdinal()).isEqualTo(1L);
    assertThat(land.totalMilliMu(LandTypeId.ARABLE)).isEqualTo(3_100_000L);
    assertThat(LandComponentOperations.total(land, LandTypeId.URBAN)).isZero();
  }

  @Test
  void equalAttributesMergeKeepingSmallestIdAndAddingQuantity() {
    Map<LandAttributeKey, LandAttributeValue> attrs =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    LandTypeArea area =
        new LandTypeArea(
            LandTypeId.ARABLE,
            List.of(
                new LandComponent(new LandComponentId("arable@0_0:2"), 200L, attrs),
                new LandComponent(new LandComponentId("arable@0_0:0"), 100L, attrs)),
            3L);

    LandTypeArea merged = LandComponentOperations.mergeEqualComponents(area);

    assertThat(merged.components())
        .singleElement()
        .satisfies(
            component -> {
              assertThat(component.id().value()).isEqualTo("arable@0_0:0");
              assertThat(component.quantityMilliMu()).isEqualTo(300L);
            });
    assertThat(merged.nextComponentOrdinal()).as("合并不得回收序号").isEqualTo(3L);
  }

  @Test
  void differentOwnerOrProductionAttributeNeverMerges() {
    Map<LandAttributeKey, LandAttributeValue> ownerA =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    Map<LandAttributeKey, LandAttributeValue> ownerB =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-b"));
    Map<LandAttributeKey, LandAttributeValue> progress1 = new LinkedHashMap<>(ownerA);
    progress1.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(1L));
    Map<LandAttributeKey, LandAttributeValue> progress2 = new LinkedHashMap<>(ownerA);
    progress2.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(2L));

    LandTypeArea area =
        new LandTypeArea(
            LandTypeId.ARABLE,
            List.of(
                new LandComponent(new LandComponentId("arable@0_0:0"), 100L, ownerA),
                new LandComponent(new LandComponentId("arable@0_0:1"), 100L, ownerB),
                new LandComponent(new LandComponentId("arable@0_0:2"), 100L, progress1),
                new LandComponent(new LandComponentId("arable@0_0:3"), 100L, progress2)),
            4L);

    assertThat(LandComponentOperations.mergeEqualComponents(area).components())
        .as("owner/progress 任一不同 ⇒ 四个 component 一个都不许并")
        .hasSize(4);
  }

  @Test
  void wholeOwnerTransferKeepsComponentIdAndPartialTransferSplitsWithNewOrdinal() {
    HexLand seed =
        LandComponentOperations.seedSingle(
            HEX,
            LandTypeId.ARABLE,
            1_000L,
            Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-seller")));

    HexLand whole =
        LandComponentOperations.transferQuantity(
            seed, HEX, LandTypeId.ARABLE, "hh-seller", "hh-buyer", 1_000L);
    LandTypeArea wholeArea = whole.byType().get(LandTypeId.ARABLE);
    assertThat(wholeArea.components())
        .singleElement()
        .satisfies(
            component -> {
              assertThat(component.id().value()).as("整块转移只改 owner，id 不变").isEqualTo("arable@0_0:0");
              assertThat(component.attributes().get(LandAttributeKey.OWNER).stringValue())
                  .isEqualTo("hh-buyer");
              assertThat(component.quantityMilliMu()).isEqualTo(1_000L);
            });
    assertThat(wholeArea.nextComponentOrdinal()).as("未拆分 ⇒ 序号不前进").isEqualTo(1L);

    HexLand split =
        LandComponentOperations.transferQuantity(
            seed, HEX, LandTypeId.ARABLE, "hh-seller", "hh-buyer", 400L);
    LandTypeArea splitArea = split.byType().get(LandTypeId.ARABLE);
    assertThat(splitArea.components())
        .extracting(LandComponentOperations::ownerOf)
        .as("component 按 id 规范升序：原 id 保留卖方剩余，新 id 是买方")
        .containsExactly("hh-seller", "hh-buyer");
    assertThat(splitArea.totalMilliMu()).as("拆分后总量守恒").isEqualTo(1_000L);
    assertThat(splitArea.components())
        .extracting(component -> component.id().value())
        .containsExactly("arable@0_0:0", "arable@0_0:1");
    assertThat(split.byType().get(LandTypeId.ARABLE).nextComponentOrdinal())
        .as("拆分出的买方 component 用下一个落盘序号")
        .isEqualTo(2L);
  }

  @Test
  void transferRejectsOversellSameOwnerAndNonPositiveQuantity() {
    HexLand seed =
        LandComponentOperations.seedSingle(
            HEX,
            LandTypeId.ARABLE,
            100L,
            Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a")));

    assertThatThrownBy(
            () ->
                LandComponentOperations.transferQuantity(
                    seed, HEX, LandTypeId.ARABLE, "hh-a", "hh-b", 101L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("卖方土地数量不足");
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferQuantity(
                    seed, HEX, LandTypeId.ARABLE, "hh-a", "hh-a", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得相同");
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferQuantity(
                    seed, HEX, LandTypeId.ARABLE, "hh-a", "hh-b", 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于 0");
  }

  @Test
  void setArableOwnersRewritesAggregatesAndRejectsDuplicateOrUnsupportedShapes() {
    HexLand seed =
        LandComponentOperations.seedSingle(
            HEX,
            LandTypeId.ARABLE,
            1_000L,
            Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a")));

    HexLand twoOwners =
        LandComponentOperations.setArableOwners(seed, HEX, Map.of("hh-a", 600L, "hh-b", 400L));
    assertThat(twoOwners.totalMilliMu(LandTypeId.ARABLE))
        .as("重设 owner 聚合不改总面积语义")
        .isEqualTo(1_000L);
    assertThat(twoOwners.byType().get(LandTypeId.ARABLE).components())
        .extracting(LandComponentOperations::ownerOf)
        .containsExactly("hh-a", "hh-b");

    assertThatThrownBy(
            () ->
                LandComponentOperations.setArableOwners(
                    seed, HEX, Map.of("hh-a", 600L, "hh-a", 400L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LandComponentOperations.setArableOwners(seed, HEX, Map.of("hh-a", 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> LandComponentOperations.setArableOwners(seed, HEX, Map.of(" ", 1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
  }

  @Test
  void setArableOwnersRejectsAggregatingComponentsWithDifferentNonOwnerAttributes() {
    Map<LandAttributeKey, LandAttributeValue> p1 = new LinkedHashMap<>();
    p1.put(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    p1.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(1L));
    Map<LandAttributeKey, LandAttributeValue> p2 = new LinkedHashMap<>();
    p2.put(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    p2.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(2L));
    HexLand land =
        new HexLand(
            Map.of(
                LandTypeId.ARABLE,
                new LandTypeArea(
                    LandTypeId.ARABLE,
                    List.of(
                        new LandComponent(new LandComponentId("arable@0_0:0"), 100L, p1),
                        new LandComponent(new LandComponentId("arable@0_0:1"), 100L, p2)),
                    2L)));

    assertThatThrownBy(
            () -> LandComponentOperations.setArableOwners(land, HEX, Map.of("hh-a", 200L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("land-owner-aggregation-unsupported");
  }

  @Test
  void singleComponentSetterRejectsMultipleComponents() {
    Map<LandAttributeKey, LandAttributeValue> ownerA =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    Map<LandAttributeKey, LandAttributeValue> ownerB =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-b"));
    HexLand land =
        new HexLand(
            Map.of(
                LandTypeId.ARABLE,
                new LandTypeArea(
                    LandTypeId.ARABLE,
                    List.of(
                        new LandComponent(new LandComponentId("arable@0_0:0"), 100L, ownerA),
                        new LandComponent(new LandComponentId("arable@0_0:1"), 100L, ownerB)),
                    2L)));

    assertThatThrownBy(
            () ->
                LandComponentOperations.setSingleComponentQuantity(
                    land, HEX, LandTypeId.ARABLE, 300L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.MULTIPLE_COMPONENTS_REASON);
  }
}
