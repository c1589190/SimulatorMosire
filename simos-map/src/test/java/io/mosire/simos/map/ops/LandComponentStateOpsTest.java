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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-032 T1（U3）：土地 component 级状态纯操作的正例 / 负例。
 *
 * <p>判据来自任务书 §8.1 / §5.6：整块只改 OWNER；部分转移复制除 OWNER 外全部属性；split overrides 重置新件、源保留； set/remove 不写/不清
 * OWNER；数量守恒；0/负/超量/缺 component/OWNER 改写均具名拒。
 *
 * <p>变异自证锚点：{@link #partialTransferCopiesAllNonOwnerAttributesAndConservesQuantity()} 在“只改 owner、丢
 * state” 的改坏下必须变红。
 */
class LandComponentStateOpsTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final LandTypeId TYPE = LandTypeId.ARABLE;
  private static final LandComponentId SOURCE_ID = new LandComponentId("arable@0_0:0");
  private static final String CROP = "wheat";
  private static final String STAGE2 = "stage2";

  @Test
  void wholeTransferOnlyChangesOwnerAndKeepsEverythingElse() {
    HexLand base = statefulLand("h1", 10_000L);

    HexLand next =
        LandComponentOperations.transferFromComponent(base, HEX, TYPE, SOURCE_ID, "h2", 10_000L);

    LandComponent result = onlyComponent(next);
    assertThat(result.id()).as("整块转移 id 不变").isEqualTo(SOURCE_ID);
    assertThat(result.quantityMilliMu()).isEqualTo(10_000L);
    assertThat(LandComponentOperations.ownerOf(result)).isEqualTo("h2");
    assertThat(withoutOwner(result.attributes()))
        .as("整块转移除 OWNER 外逐值保留")
        .isEqualTo(withoutOwner(baseAttributes()));
    assertThat(LandComponentOperations.total(next, TYPE))
        .as("整块转移数量守恒")
        .isEqualTo(LandComponentOperations.total(base, TYPE));
  }

  @Test
  void partialTransferCopiesAllNonOwnerAttributesAndConservesQuantity() {
    HexLand base = statefulLand("h1", 10_000L);

    HexLand next =
        LandComponentOperations.transferFromComponent(base, HEX, TYPE, SOURCE_ID, "h2", 3_000L);

    LandTypeArea area = next.byType().get(TYPE);
    assertThat(area.components()).hasSize(2);
    LandComponent source = LandComponentOperations.componentOf(next, TYPE, SOURCE_ID);
    assertThat(source).isNotNull();
    assertThat(source.quantityMilliMu()).isEqualTo(7_000L);
    assertThat(LandComponentOperations.ownerOf(source)).isEqualTo("h1");

    LandComponent target =
        area.components().stream()
            .filter(component -> !component.id().equals(SOURCE_ID))
            .findFirst()
            .orElseThrow();
    assertThat(target.id().value()).as("部分转移使用 next ordinal 生成新 id").isEqualTo("arable@0_0:1");
    assertThat(target.quantityMilliMu()).isEqualTo(3_000L);
    assertThat(LandComponentOperations.ownerOf(target)).isEqualTo("h2");
    assertThat(target.attributes().get(LandAttributeKey.CROP))
        .as("新件复制 CROP")
        .isEqualTo(LandAttributeValue.ofString(CROP));
    assertThat(target.attributes().get(LandAttributeKey.STAGE))
        .as("新件复制 STAGE")
        .isEqualTo(LandAttributeValue.ofString(STAGE2));
    assertThat(target.attributes().get(LandAttributeKey.PROGRESS))
        .as("新件复制 PROGRESS")
        .isEqualTo(LandAttributeValue.ofLong(10L));
    assertThat(target.attributes().get(LandAttributeKey.CYCLE_PROGRESS))
        .as("新件复制 CYCLE_PROGRESS")
        .isEqualTo(LandAttributeValue.ofLong(40L));
    assertThat(target.attributes().get(LandAttributeKey.START_INPUT_PAID))
        .isEqualTo(LandAttributeValue.ofBoolean(true));
    assertThat(withoutOwner(target.attributes()))
        .as("部分转移：新件除 OWNER 外逐值等于源")
        .isEqualTo(withoutOwner(source.attributes()));
    assertThat(LandComponentOperations.total(next, TYPE))
        .as("部分转移数量守恒")
        .isEqualTo(LandComponentOperations.total(base, TYPE));
  }

  @Test
  void splitComponentWithOverridesResetsNewPieceAndKeepsSource() {
    HexLand base = statefulLand("h1", 10_000L);
    Map<LandAttributeKey, LandAttributeValue> overrides = new LinkedHashMap<>();
    overrides.put(LandAttributeKey.STAGE, LandAttributeValue.ofString("stage0"));
    overrides.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(0L));
    overrides.put(LandAttributeKey.CYCLE_PROGRESS, LandAttributeValue.ofLong(0L));
    overrides.put(LandAttributeKey.START_INPUT_PAID, LandAttributeValue.ofBoolean(false));

    HexLand next =
        LandComponentOperations.splitComponentWithOverrides(
            base, HEX, TYPE, SOURCE_ID, 1_000L, overrides);

    LandComponent source = LandComponentOperations.componentOf(next, TYPE, SOURCE_ID);
    assertThat(source.quantityMilliMu()).as("源保留减少后的数量").isEqualTo(9_000L);
    assertThat(source.attributes().get(LandAttributeKey.STAGE))
        .as("源保留原 stage")
        .isEqualTo(LandAttributeValue.ofString(STAGE2));
    assertThat(source.attributes().get(LandAttributeKey.PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(10L));

    LandComponent reset =
        next.byType().get(TYPE).components().stream()
            .filter(component -> !component.id().equals(SOURCE_ID))
            .findFirst()
            .orElseThrow();
    assertThat(reset.quantityMilliMu()).isEqualTo(1_000L);
    assertThat(LandComponentOperations.ownerOf(reset)).as("split 不改 owner").isEqualTo("h1");
    assertThat(reset.attributes().get(LandAttributeKey.STAGE))
        .as("新件 overrides 生效")
        .isEqualTo(LandAttributeValue.ofString("stage0"));
    assertThat(reset.attributes().get(LandAttributeKey.PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(0L));
    assertThat(reset.attributes().get(LandAttributeKey.CYCLE_PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(0L));
    assertThat(reset.attributes().get(LandAttributeKey.CROP))
        .as("未覆盖键继续复制")
        .isEqualTo(LandAttributeValue.ofString(CROP));
    assertThat(LandComponentOperations.total(next, TYPE))
        .as("split 数量守恒")
        .isEqualTo(LandComponentOperations.total(base, TYPE));
  }

  @Test
  void setComponentAttributesOnlyOverwritesGivenKeysAndKeepsOwner() {
    HexLand base = statefulLand("h1", 10_000L);
    Map<LandAttributeKey, LandAttributeValue> patch = new LinkedHashMap<>();
    patch.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(15L));
    patch.put(LandAttributeKey.STAGE, LandAttributeValue.ofString("stage3"));

    HexLand next =
        LandComponentOperations.setComponentAttributes(base, HEX, TYPE, SOURCE_ID, patch);

    LandComponent result = onlyComponent(next);
    assertThat(LandComponentOperations.ownerOf(result)).as("set 不清/不写 OWNER").isEqualTo("h1");
    assertThat(result.attributes().get(LandAttributeKey.CROP))
        .as("未给出的键保留")
        .isEqualTo(LandAttributeValue.ofString(CROP));
    assertThat(result.attributes().get(LandAttributeKey.PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(15L));
    assertThat(result.attributes().get(LandAttributeKey.STAGE))
        .isEqualTo(LandAttributeValue.ofString("stage3"));
    assertThat(result.attributes().get(LandAttributeKey.CYCLE_PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(40L));
    assertThat(result.quantityMilliMu()).isEqualTo(10_000L);
    assertThat(LandComponentOperations.setComponentAttributes(base, HEX, TYPE, SOURCE_ID, Map.of()))
        .as("空 map 原样返回")
        .isSameAs(base);
  }

  @Test
  void removeComponentAttributesKeepsOwnerAndOnlyRemovesGivenKeys() {
    HexLand base = statefulLand("h1", 10_000L);

    HexLand next =
        LandComponentOperations.removeComponentAttributes(
            base,
            HEX,
            TYPE,
            SOURCE_ID,
            Set.of(
                LandAttributeKey.STAGE,
                LandAttributeKey.PROGRESS,
                LandAttributeKey.CYCLE_PROGRESS));

    LandComponent result = onlyComponent(next);
    assertThat(LandComponentOperations.ownerOf(result)).as("remove 不清 OWNER").isEqualTo("h1");
    assertThat(result.attributes())
        .doesNotContainKeys(
            LandAttributeKey.STAGE, LandAttributeKey.PROGRESS, LandAttributeKey.CYCLE_PROGRESS);
    assertThat(result.attributes().get(LandAttributeKey.CROP))
        .as("未给出的键保留")
        .isEqualTo(LandAttributeValue.ofString(CROP));
    assertThat(result.quantityMilliMu()).isEqualTo(10_000L);
    assertThat(
            LandComponentOperations.removeComponentAttributes(base, HEX, TYPE, SOURCE_ID, Set.of()))
        .as("空 set 原样返回")
        .isSameAs(base);
  }

  @Test
  void transferRejectsNonPositiveOverQuantityAndMissingComponent() {
    HexLand base = statefulLand("h1", 10_000L);

    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(base, HEX, TYPE, SOURCE_ID, "h2", 0L))
        .as("0 数量")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(
                    base, HEX, TYPE, SOURCE_ID, "h2", -1L))
        .as("负数量")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(
                    base, HEX, TYPE, SOURCE_ID, "h2", 10_001L))
        .as("超量")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过源 component 面积");
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(
                    base, HEX, TYPE, new LandComponentId("arable@0_0:9"), "h2", 1L))
        .as("缺 component")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.COMPONENT_NOT_FOUND_REASON);
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(base, HEX, TYPE, SOURCE_ID, "  ", 1L))
        .as("空目标 owner")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.transferFromComponent(base, HEX, TYPE, SOURCE_ID, "h1", 1L))
        .as("同 owner")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerWritesThroughSetRemoveSplitAreRejectedNamed() {
    HexLand base = statefulLand("h1", 10_000L);
    Map<LandAttributeKey, LandAttributeValue> ownerPatch =
        Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("h2"));

    assertThatThrownBy(
            () ->
                LandComponentOperations.setComponentAttributes(
                    base, HEX, TYPE, SOURCE_ID, ownerPatch))
        .as("set 不得写 OWNER")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.OWNER_WRITE_THROUGH_TRANSFER_ONLY_REASON);
    assertThatThrownBy(
            () ->
                LandComponentOperations.removeComponentAttributes(
                    base, HEX, TYPE, SOURCE_ID, Set.of(LandAttributeKey.OWNER)))
        .as("remove 不得删 OWNER")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.OWNER_WRITE_THROUGH_TRANSFER_ONLY_REASON);
    assertThatThrownBy(
            () ->
                LandComponentOperations.splitComponentWithOverrides(
                    base, HEX, TYPE, SOURCE_ID, 1_000L, ownerPatch))
        .as("split overrides 不得写 OWNER")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.OWNER_WRITE_THROUGH_TRANSFER_ONLY_REASON);
  }

  @Test
  void splitRejectsNonPositiveWholeAndMissingComponent() {
    HexLand base = statefulLand("h1", 10_000L);

    assertThatThrownBy(
            () ->
                LandComponentOperations.splitComponentWithOverrides(
                    base, HEX, TYPE, SOURCE_ID, 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.splitComponentWithOverrides(
                    base, HEX, TYPE, SOURCE_ID, -5L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.splitComponentWithOverrides(
                    base, HEX, TYPE, SOURCE_ID, 10_000L, Map.of()))
        .as("整块应走 setComponentAttributes")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                LandComponentOperations.splitComponentWithOverrides(
                    base, HEX, TYPE, new LandComponentId("arable@0_0:9"), 1L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LandComponentOperations.COMPONENT_NOT_FOUND_REASON);
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────

  private static HexLand statefulLand(String owner, long quantityMilliMu) {
    LandComponent component =
        new LandComponent(SOURCE_ID, quantityMilliMu, statefulAttributes(owner));
    LandTypeArea area = new LandTypeArea(TYPE, List.of(component), 1L);
    Map<LandTypeId, LandTypeArea> byType = new LinkedHashMap<>();
    byType.put(TYPE, area);
    return new HexLand(byType);
  }

  private static Map<LandAttributeKey, LandAttributeValue> statefulAttributes(String owner) {
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    attributes.put(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner));
    attributes.put(LandAttributeKey.CROP, LandAttributeValue.ofString(CROP));
    attributes.put(LandAttributeKey.STAGE, LandAttributeValue.ofString(STAGE2));
    attributes.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(10L));
    attributes.put(LandAttributeKey.CYCLE_PROGRESS, LandAttributeValue.ofLong(40L));
    attributes.put(LandAttributeKey.START_INPUT_PAID, LandAttributeValue.ofBoolean(true));
    return attributes;
  }

  private static Map<LandAttributeKey, LandAttributeValue> baseAttributes() {
    return statefulAttributes("h1");
  }

  private static LandComponent onlyComponent(HexLand land) {
    LandTypeArea area = land.byType().get(TYPE);
    assertThat(area.components()).hasSize(1);
    return area.components().get(0);
  }

  private static Map<LandAttributeKey, LandAttributeValue> withoutOwner(
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    Map<LandAttributeKey, LandAttributeValue> copy = new LinkedHashMap<>(attributes);
    copy.remove(LandAttributeKey.OWNER);
    return copy;
  }
}
