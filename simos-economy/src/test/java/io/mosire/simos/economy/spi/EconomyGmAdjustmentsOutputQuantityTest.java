package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-032 迁移后：{@code outputQuantityOverrides} 两 kind 的<b>具名拒</b>与白名单收缩</b>。
 *
 * <p>★★ <b>取代关系（本类改写自 {@code EconomyGmAdjustmentsOutputQuantityTest} 的旧语义）</b>：旧用例断言的是 {@code
 * setOutputQuantity} / {@code clearOutputQuantity} 的<b>投影成功路径</b>（四类具名拒绝 {@code INDUSTRY_NOT_FOUND}
 * / {@code COMMODITY_NOT_IN_RECIPE} / {@code QUANTITY_OUT_OF_RANGE} / {@code NO_OVERRIDE_TO_CLEAR}
 * 各一条）。D-032 把 GM 产出数值覆盖的权威迁到 <b>production</b> （{@code ProductionData.outputQuantityOverrides}）⇒
 * 这两条命令在 economy 侧<b>不再实现</b>，改为 <b>具名拒</b> {@code output-quantity-moved-to-production-definition}
 * 并指路 {@code production.UpsertIndustry} 的 {@code outputPerUnit}（B2b/B2c 裁定；见 {@code
 * EconomyGmAdjustments} 类注与 {@code :149}）。
 *
 * <p>★ <b>为什么"改写"而不是"删掉"</b>：这两条命令仍在白名单里（GM 仍会点到它们），所以"点到会发生什么"必须 有断言；删除整类会把"具名拒 + 零状态变化"这条判据一起丢掉。
 */
class EconomyGmAdjustmentsOutputQuantityTest {

  private static final String MOVED_REASON = "output-quantity-moved-to-production-definition";
  private static final long DAY = 7L;

  @Test
  void whitelistIsExactlySevenKindsAndCarriesNoModifierKind() {
    // ★ T5（a 桶计数欠账）：白名单轨迹 12 → 10 → 9 → **7**。
    //   12 = HEAD 的全集；−2（Z1 两个产出覆盖 kind，B2b 裁定：迁 production、economy 侧只发具名拒）
    //   = 10；−1（upsertCandidate，B2c 裁定八：候选面整体退役）= 9；−2（upsertProductionMode /
    //   deactivateProductionMode，B5 裁定十八：economy 的 modes 组件已删）= **7**。
    //   ★ 这 5 个已迁出的 kind **仍在** EconomyGmAdjustments.MOVED_ADJUSTMENTS 里、命中时给具名拒
    //   （见下一条用例与 EconomyGmAdjustments.project 的 switch）⇒ "不在白名单"不等于"掉进通用拒绝"。
    assertThat(EconomyGmAdjustments.ADJUSTMENTS)
        .as("§2/§4：GM 只能改源状态；现存白名单恰 7 项（逐值断言，不是 isNotEmpty）")
        .hasSize(7)
        .containsExactly(
            EconomyGmAdjustments.FORGIVE_DEBT,
            EconomyGmAdjustments.SET_LIQUIDATION_POLICY,
            EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE,
            EconomyGmAdjustments.UPSERT_CLASS_POSITION,
            EconomyGmAdjustments.UPSERT_PRODUCTION_RELATION,
            EconomyGmAdjustments.UPSERT_ASSET_RULE,
            EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION);
    assertThat(EconomyGmAdjustments.MOVED_ADJUSTMENTS)
        .as("★ 已迁出、但仍会被点到的 5 个 kind：它们**不**进白名单，各自有具名拒理由（不是通用拒绝）")
        .containsExactly(
            EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
            EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY,
            EconomyGmAdjustments.UPSERT_CANDIDATE,
            EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
            EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE);
    assertThat(
            EconomyGmAdjustments.ADJUSTMENTS.size() + EconomyGmAdjustments.MOVED_ADJUSTMENTS.size())
        .as("两条表合起来 = HEAD 的 12 项全集（没有 kind 在迁移中被静默丢掉）")
        .isEqualTo(12);
    assertThat(EconomyGmAdjustments.ADJUSTMENTS)
        .as("修正参数没有 GM kind（§2 写口矩阵 / §11.6 负向）")
        .noneMatch(kind -> kind.toLowerCase(java.util.Locale.ROOT).contains("modifier"))
        .noneMatch(kind -> kind.contains("修正") || kind.contains("Modifier"));
    // ★ B2c/B5：三个 kind 已整体退役 ⇒ 不得再出现在白名单里。
    assertThat(EconomyGmAdjustments.ADJUSTMENTS)
        .as("退役的 kind 不得留在白名单")
        .doesNotContain("upsertProductionMode", "deactivateProductionMode", "upsertCandidate");
  }

  @Test
  void bothMovedKindsAreNamedRejectedWithZeroStateChange() {
    EconomyData base = EconomyData.empty();
    ProductionData definitions = ProductionData.empty();

    for (String kind :
        new String[] {
          EconomyGmAdjustments.SET_OUTPUT_QUANTITY, EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY
        }) {
      assertThatThrownBy(
              () ->
                  EconomyGmAdjustments.project(
                      base,
                      definitions,
                      Map.of(),
                      kind,
                      params("farm", "grain", 3L),
                      "gm:test",
                      DAY))
          .as("★ 产出数值覆盖已迁 production ⇒ 必须具名拒（不是静默失效、不是通用拒绝）: %s", kind)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(MOVED_REASON);
    }

    // ★★★ D-032 T1：原断言读的是 `base.outputQuantityOverrides()`（economy 侧覆盖表）—— 该分量已随迁移删除
    //   （I1：覆盖表唯一权威在 production）。判据**换源**（§14.3 第 3 条）：改读注入的 production 切片 ——
    //   "具名拒 ⇒ 零状态变化"必须落在**新的权威**上，否则这句话没有主语。
    assertThat(definitions.outputQuantityOverrides())
        .as("★ 具名拒 ⇒ 零状态变化：production 的覆盖表一字不动（唯一权威）")
        .isEmpty();
    assertThat(definitions.instances()).as("★ 具名拒 ⇒ 也没有任何实例被建出/改动").isEmpty();
  }

  @Test
  void projectRequiresDefinitionsAndUnitsProjectionExplicitly() {
    EconomyData base = EconomyData.empty();
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    null,
                    Map.of(),
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("farm", "grain", 3L),
                    "gm:test",
                    DAY))
        .as("★ 定义来源必填 ⇒ 传 null 当场拒（不得静默当空表）")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                EconomyGmAdjustments.project(
                    base,
                    ProductionData.empty(),
                    null,
                    EconomyGmAdjustments.SET_OUTPUT_QUANTITY,
                    params("farm", "grain", 3L),
                    "gm:test",
                    DAY))
        .as("★ 实例只读投影必填 ⇒ 传 null 当场拒（装配故障不许降级成业务拒绝）")
        .isInstanceOf(NullPointerException.class);
  }

  private static ObjectNode params(String industryId, String commodityId, long quantity) {
    ObjectNode node = objectNode("industryId", industryId, "commodityId", commodityId);
    node.put("quantity", quantity);
    return node;
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
