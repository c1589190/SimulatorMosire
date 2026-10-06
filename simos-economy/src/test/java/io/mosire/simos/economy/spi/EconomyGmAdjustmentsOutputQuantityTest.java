package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>§4/§11.4：{@code setOutputQuantity} / {@code clearOutputQuantity} 的纯函数投影与四类具名拒绝</b>。
 *
 * <p>handler（{@code simos-economy}）与 GM 窄写工具（{@code simos-app}）共用 {@link
 * EconomyGmAdjustments#project} 这一份语义落点，故本类在 economy 侧把命令边界逐条钉死：白名单恰 12 项、 成功投影只写 {@code
 * outputQuantityOverrides}、四个 reason 逐字、清除不留空内层。被拒绝一律 INFO 由 handler 负责（本模块测试无
 * 日志绑定，见台账「未验证」节；此处断言的是拒绝语义，即 {@link IllegalArgumentException} 的具名 reason）。
 */
class EconomyGmAdjustmentsOutputQuantityTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final long DAY = 7L;

  @Test
  void whitelistIsExactlyTwelveSourceStateKindsAndCarriesNoModifierKind() {
    assertThat(EconomyGmAdjustments.ADJUSTMENTS)
        .as("§2/§4：GM 只能改源状态，白名单恰 12 项")
        .containsExactly(
            EconomyGmAdjustments.FORGIVE_DEBT,
            EconomyGmAdjustments.SET_LIQUIDATION_POLICY,
            EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
            EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE,
            EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE,
            EconomyGmAdjustments.UPSERT_CLASS_POSITION,
            EconomyGmAdjustments.UPSERT_PRODUCTION_RELATION,
            EconomyGmAdjustments.UPSERT_ASSET_RULE,
            EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION,
            EconomyGmAdjustments.UPSERT_CANDIDATE,
            EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
            EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY);
    // ★★ §5 负向：修正参数**永不加** GM 写口。任何新 kind 名字里出现 modifier / 修正 都是违约。
    assertThat(EconomyGmAdjustments.ADJUSTMENTS)
        .as("修正参数没有 GM kind（§2 写口矩阵 / §11.6 负向）")
        .noneMatch(kind -> kind.toLowerCase(java.util.Locale.ROOT).contains("modifier"))
        .noneMatch(kind -> kind.contains("修正") || kind.contains("Modifier"));
  }

  @Test
  void setOutputQuantityUpsertsOnlyTheOverrideTableAndRoundTripsThroughTheChangeSet() {
    EconomyData base = base();

    EconomyGmAdjustments.Projection projection =
        EconomyGmAdjustments.project(
            base,
            EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
            params("farm", "grain", 3L),
            "gm:test-set",
            DAY);

    assertThat(projection.projected().outputQuantityOverrides())
        .as("只写覆盖表：farm → grain = 3")
        .containsExactlyInAnyOrderEntriesOf(Map.of(FARM, Map.of(GRAIN, 3L)));
    assertThat(projection.projected().industries()).as("其余组件一字不动").isEqualTo(base.industries());
    assertThat(projection.projected().units()).isEqualTo(base.units());
    assertThat(projection.changeSet().outputQuantityOverrides().changed())
        .as("覆盖表必须进变更集（否则命令看似成功、状态没落盘）")
        .isTrue();
    assertThat(EconomyChangeSet.apply(projection.changeSet(), base))
        .isEqualTo(projection.projected());
    assertThat(projection.changes()).hasSize(1);
    assertThat(projection.changes().get(0).component())
        .as("§4：投影组件名固定为 outputQuantityOverrides")
        .isEqualTo("outputQuantityOverrides");
    assertThat(projection.changes().get(0).keyId())
        .as("§4：keyId = industryId + \"/\" + commodityId")
        .isEqualTo("farm/grain");
    assertThat(projection.changes().get(0).before()).as("新增 ⇒ before 为空").isNull();
    assertThat(projection.changes().get(0).after()).isEqualTo(3L);
  }

  @Test
  void setOutputQuantityAcceptsZeroAndTheConfiguredMaximum() {
    EconomyData base = base();

    EconomyData stopped =
        EconomyGmAdjustments.project(
                base,
                EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                params("farm", "grain", 0L),
                "gm:0",
                DAY)
            .projected();
    assertThat(stopped.outputQuantityOverrides().get(FARM)).containsEntry(GRAIN, 0L);

    EconomyData maxed =
        EconomyGmAdjustments.project(
                base,
                EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                params("farm", "grain", EconomyData.MAX_OUTPUT_QUANTITY),
                "gm:max",
                DAY)
            .projected();
    assertThat(maxed.outputQuantityOverrides().get(FARM))
        .containsEntry(GRAIN, EconomyData.MAX_OUTPUT_QUANTITY);
  }

  @Test
  void clearOutputQuantityRemovesTheOverrideAndDropsTheEmptyInnerLine() {
    EconomyData withOverride =
        EconomyGmAdjustments.project(
                base(),
                EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                params("farm", "grain", 3L),
                "gm:set",
                DAY)
            .projected();

    EconomyGmAdjustments.Projection projection =
        EconomyGmAdjustments.project(
            withOverride,
            EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
            clearParams("farm", "grain"),
            "gm:clear",
            DAY);

    assertThat(projection.projected().outputQuantityOverrides()).as("清除后覆盖表回到空表（不留空内层）").isEmpty();
    assertThat(projection.changeSet().outputQuantityOverrides().changed()).isTrue();
    assertThat(projection.changes().get(0).before()).isEqualTo(3L);
    assertThat(projection.changes().get(0).after()).as("删除 ⇒ after 为空").isNull();
    assertThat(projection.changes().get(0).keyId()).isEqualTo("farm/grain");
    assertThat(EconomyChangeSet.apply(projection.changeSet(), withOverride))
        .isEqualTo(projection.projected());
  }

  @Test
  void theFourNamedRejectionsAreExact() {
    EconomyData base = base();
    EconomyData withOverride =
        EconomyGmAdjustments.project(
                base,
                EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                params("farm", "grain", 3L),
                "gm:set",
                DAY)
            .projected();

    // ① 产业不存在
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("ghost", "grain", 3L),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("INDUSTRY_NOT_FOUND");

    // ② 商品不在该产业配方产出键里（不许开新商品）
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("farm", "cloth", 3L),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMMODITY_NOT_IN_RECIPE");

    // ③ 数量缺失 / 非整数 / < 0 / > 上限 ⇒ 同一具名 reason
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    objectNode("industryId", "farm", "commodityId", "grain"),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUANTITY_OUT_OF_RANGE");
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("farm", "grain", -1L),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUANTITY_OUT_OF_RANGE");
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("farm", "grain", EconomyData.MAX_OUTPUT_QUANTITY + 1L),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUANTITY_OUT_OF_RANGE");
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    objectNode("industryId", "farm", "commodityId", "grain", "quantity", 1.5d),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUANTITY_OUT_OF_RANGE");

    // ④ 没有既有覆盖 ⇒ 不做静默幂等
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
                    clearParams("farm", "grain"),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NO_OVERRIDE_TO_CLEAR");
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    withOverride,
                    EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
                    clearParams("farm", "cloth"),
                    "gm:test",
                    DAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMMODITY_NOT_IN_RECIPE");
  }

  /** 一个最小自洽产业：配方产出键只有 grain。 */
  private static EconomyData base() {
    return EconomyData.empty().withIndustries(Map.of(FARM, industry()));
  }

  private static Industry industry() {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(),
        0L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new AllocationRule.Split(1000, 0));
  }

  private static ObjectNode params(String industryId, String commodityId, long quantity) {
    ObjectNode node = objectNode("industryId", industryId, "commodityId", commodityId);
    node.put("quantity", quantity);
    return node;
  }

  private static ObjectNode clearParams(String industryId, String commodityId) {
    return objectNode("industryId", industryId, "commodityId", commodityId);
  }

  private static ObjectNode objectNode(Object... keyValues) {
    ObjectNode node = SimosObjectMapper.create().createObjectNode();
    for (int i = 0; i < keyValues.length; i += 2) {
      String key = (String) keyValues[i];
      Object value = keyValues[i + 1];
      if (value instanceof String text) {
        node.put(key, text);
      } else if (value instanceof Long number) {
        node.put(key, number);
      } else if (value instanceof Double number) {
        node.put(key, number);
      } else {
        node.set(key, (JsonNode) value);
      }
    }
    return node;
  }
}
