package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 ③-负向（2026-10-15 批 U）：{@code ToolResults.json(...)} 对<b>不可序列化对象</b>确实会抛</b> —— 于是"在
 * {@code writes.submit*(...)} <b>之后</b>调它"是一条<b>已知风险</b>，不是猜测。
 *
 * <h2>它守的是哪种坏法（批 S 的通用形状）</h2>
 *
 * <p>批 S（{@code docs/superpowers/plans/2026-10-15-batch-S-write-then-throw.md} §3）抽象出的形状是：
 *
 * <pre>
 * writes.submit*(...) 之后到 return 之间的任何一行都可能抛；
 * 一旦抛，就把"原子"变成了"部分成功"（世界已变、调用方以为失败）。
 * </pre>
 *
 * <p>而 {@link ToolResults#json(Object)} <b>恰恰就在那个区间里</b>被调用，且<b>遍及全部工具</b>：写工具返回前都要把"写之后的视图" 折成
 * {@code ToolResult}（{@code ToolResults.ok(view) → json(view)}；{@code ProposeCallTool.java:306} 的
 * {@code DecisionPacketPayloads.fold} 是其中一条）。批 S 的实测结论是：<b>今天</b>这些视图只含标量 / Map / List ⇒
 * 序列化不会抛；<b>但往写后视图里塞一个不可序列化对象，本形状立刻复活</b>（工具会返回 {@code TOOL_ERROR} 而世界已经变了）。
 *
 * <p>★ <b>本类证明的就是"复活"那一半</b>：既不是空口说"有风险"，也不做通用扫描（那会变成装饰）——只钉住 {@code json()}
 * 对不可序列化值的<b>实际行为</b>：<b>抛</b>（{@link IllegalStateException} 包住 Jackson 的 {@link
 * JsonProcessingException}），<b>不</b>静默发半个载荷、<b>不</b>把值丢掉。⇒ 任何"写后调它"的代码路径都因此背上 "视图里必须只有可序列化值"这条义务。
 *
 * <p>★★ <b>判别力自证</b>：同一个方法里先证"同一形状、值换成标量 ⇒ 正常出 JSON"（对照），再证"值换成 {@code new Object()} ⇒
 * 抛"。少了前一半，后面的抛可能来自任何别的原因。
 */
class ToolResultsUnserializableViewTest {

  /**
   * ★★ <b>不可序列化的值 ⇒ {@code json()} 必须抛（顶层 / 嵌套 Map / 嵌套 List 三个位置）</b>；同一方法里用"标量版同形视图"
   * 作对照，证明抛的原因就是那个对象。
   *
   * <p>★ 为什么三个位置都要：写后视图不是扁平表 —— {@code preview} 是 Map、{@code calls} 是 List&lt;Map&gt; ⇒ 不可序列化的值
   * 出现在<b>嵌套位置</b>同样致命（{@code ProposeCallTool} 的视图正是这个形状，见批 U 的另一条正向用例）。
   */
  @Test
  void anUnserializableValueMakesJsonThrowInsteadOfDroppingItOrEmittingAHalfPayload() {
    // ── 对照：同形视图，值全是可序列化标量 ⇒ 正常出 JSON（证明下面的抛不是"这个方法本来就会抛"）──
    //   ★ 用 LinkedHashMap 定义插入序（ToolResults 的输出序就是插入序，逐字节比对才有意义）。
    Map<String, Object> preview = new LinkedHashMap<>();
    preview.put("amountMilli", 7777L);
    preview.put("currency", "copper");
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("tool", "simos.gov.issueMoney");
    call.put("ok", true);
    Map<String, Object> scalarView = new LinkedHashMap<>();
    scalarView.put("preview", preview);
    scalarView.put("calls", List.of(call));
    assertThat(ToolResults.json(scalarView))
        .as("★ 判别力对照：同一形状（Map 套 Map / List）只要值是可序列化标量就必须正常出 JSON")
        .isEqualTo(
            "{\"preview\":{\"amountMilli\":7777,\"currency\":\"copper\"},"
                + "\"calls\":[{\"tool\":\"simos.gov.issueMoney\",\"ok\":true}]}");

    // ── ① 顶层值不可序列化 ──
    assertThatThrownBy(() -> ToolResults.json(new Object()))
        .as("★★ 顶层：不可序列化对象（无属性 bean，FAIL_ON_EMPTY_BEANS 默认开）必须抛，不许静默发半个载荷")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON 序列化失败")
        .hasCauseInstanceOf(JsonProcessingException.class);

    // ── ② 嵌套在 Map 的值位置（写后视图最常见的形状）──
    Map<String, Object> nestedInMap = new LinkedHashMap<>();
    nestedInMap.put("packetId", "pkt-dm-central-0");
    nestedInMap.put("preview", Map.of("poison", new Object()));
    assertThatThrownBy(() -> ToolResults.json(nestedInMap))
        .as("★★ 嵌套 Map 的值位置：同样必须抛（写后视图就是 Map 套 Map ⇒ 这里没有豁免）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON 序列化失败")
        .hasCauseInstanceOf(JsonProcessingException.class);

    // ── ③ 嵌套在 List 的元素位置（calls[] 的形状）──
    Map<String, Object> nestedInList = new LinkedHashMap<>();
    nestedInList.put("calls", List.of(Map.of("args", List.of(new Object()))));
    assertThatThrownBy(() -> ToolResults.json(nestedInList))
        .as("★★ 嵌套 List 的元素位置：同样必须抛（calls[] 的载荷就在这个位置）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON 序列化失败")
        .hasCauseInstanceOf(JsonProcessingException.class);
  }
}
