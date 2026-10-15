package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.write.ProposeCallTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>判据 ②（2026-10-15 批 U）：把批 S 那条"写之后不许抛"的探针 pin 成仓内守卫</b> —— {@code propose} <b>不带 intent</b>
 * 时，<b>绝不允许「返回 {@code TOOL_ERROR} 而 head 涨了」</b>。
 *
 * <h2>它守的是哪个真缺陷（批 S 真机真跑挖出，{@code 44a1186c} 修）</h2>
 *
 * <p>{@code ProposeCallTool} 的 {@code finalIntentText} 允许为 {@code null}（= "不动包里已有的
 * intent"，见该类注的合成规则第 3 条），而写后组装视图的那行当年写的是 {@code if (!finalIntentText.isBlank())} ⇒ <b>NPE</b>。NPE
 * 发生在 {@code writes.submitWithReceipt(...)} <b>之后</b> ⇒ 工具被兜底 {@code catch (RuntimeException)} 折成
 * {@code TOOL_ERROR}，<b>而包已经落盘</b>（head +1）。批 S 的实测原文：
 *
 * <pre>
 * 修复前：code=TOOL_ERROR message=… NullPointerException …"finalIntentText" is null
 *         headBefore=1 headAfter=2 headDelta=1     ← ★ 报错，但包已落盘
 * 修复后：success=true code=null 视图里没有 intent 键 headDelta 正常
 * </pre>
 *
 * <p>★★ <b>判据（逐字照批 S 设计书 §5 的 V1/V2）</b>：不带 intent ⇒ <b>只有两种合法结局</b>：
 *
 * <ol>
 *   <li><b>成功</b>：{@code code != TOOL_ERROR}（成功时 {@code code == null}）且 head 正常推进（本世界 = +1）；
 *   <li><b>干净地拒</b>：{@code code} 是<b>明确拒因</b>且 <b>{@code headDelta == 0}</b>（世界零变化）。
 * </ol>
 *
 * ★ <b>绝不允许</b>的是第三种：{@code code == TOOL_ERROR} <b>而 head 涨了</b>。{@code TOOL_ERROR} 在本工具里是"兜底捕获到了
 * 异常"的代号（{@code catch (RuntimeException)}），所以它出现在这条路径上就等于"写后抛了"。
 *
 * <h2>★★ 同源入口（不是仿造）</h2>
 *
 * <p>形制照 {@code DecisionPacketEndToEndTest}：真 {@link GmActAsWorldFixture} 小世界（真 {@code Shell.start}
 * + 真工具桶 + 真 store + 真审批链），DM 侧调用走真 {@link DecisionCallerFactory}。★ <b>不</b>依赖批 R 那两个门控真 LLM 用例。
 *
 * <p>★ <b>本类还钉住写后视图的形状</b>（判据 ③-正向）：{@code ProposeCallTool} 写后那张视图的键与值类型<b>只含可序列化的标量与 嵌套
 * Map/List</b> —— 因为 {@code ToolResults.json(...)} 就在写之后被调用（它的"会抛"由 {@code
 * ToolResultsUnserializableViewTest} 钉住），视图里塞进不可序列化对象就会复活本形状。
 */
class ProposeWriteThenThrowGuardTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DM = GmActAsWorldFixture.DM;
  private static final String GOV_CENTRAL = GovWorldBootstrap.CENTRAL_GOV_ID;

  /** 一条真能被 propose 的目标工具（真 preview 通过；本类不执行它，只看 propose 这条链）。 */
  private static final String TARGET_TOOL = "simos.gov.issueMoney";

  private static final String INTENT = "U-② 对照：这条带 intent";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private ToolRegistry dmRegistry;
  private DecisionCallerFactory factory;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    dmRegistry = new ToolRegistry();
    dmRegistry.registerAll(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT));
    factory =
        DecisionCallerFactory.forDecisionFace(
            world.shell().toolAuthorizer(),
            ToolBuckets.namesOf(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  /**
   * ★★ <b>判据 ②：不带 intent ⇒ 绝不许「{@code TOOL_ERROR} 而 head 涨了」</b>（批 S 的 V1/V2 仓内化）。
   *
   * <p>覆盖批 S 探针那种"顶层与逐条都没有 intent"的<b>两种载荷形态</b>：单条形态（{@code {tool, args}}）与多条形态（{@code
   * {calls:[{tool,args}]}}，逐条也没有 {@code intent}）。
   */
  @Test
  void proposeWithoutIntentNeverReportsToolErrorWhileThePacketLanded() {
    // ① 单条形态，顶层没有 intent。
    long headBefore = world.head();
    ToolResult single = propose(TARGET_TOOL, issueMoneyArgs(), null);
    long delta = world.head() - headBefore;
    System.out.println(
        "[U-②] 单条/无 intent: success="
            + single.success()
            + " code="
            + single.code()
            + " headDelta="
            + delta
            + " body="
            + truncate(single.message(), 200));
    assertNeverToolErrorBearingARevision(single, delta, "单条形态（顶层无 intent）");

    // ② 多条形态：逐条也都没有 intent（批 S 的 S1 覆盖面 = 顶层与逐条都没有）。
    long headBefore2 = world.head();
    ToolResult multi = proposeCalls(List.of(call(TARGET_TOOL, issueMoneyArgs(), null)));
    long delta2 = world.head() - headBefore2;
    System.out.println(
        "[U-②] 多条/逐条无 intent: success="
            + multi.success()
            + " code="
            + multi.code()
            + " headDelta="
            + delta2
            + " body="
            + truncate(multi.message(), 200));
    assertNeverToolErrorBearingARevision(multi, delta2, "多条形态（逐条都无 intent）");

    // ★ 附带证据：两次提议各追加一条 call（不是同一 index 上叠两条）——批 S 的缺陷链正是"报了 TOOL_ERROR ⇒ 模型重试 ⇒
    //   重复 call ⇒ execute 撞 id ⇒ 整 tick 被拒"，故这条顺带把"重试不会叠在同一位"钉住。
    DecisionPacket packet = world.packetOf(DM);
    assertThat(packet).as("propose 之后本 tick 的包必须在").isNotNull();
    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < packet.calls().size(); i++) {
      indexes.add(packet.calls().get(i).callIndex());
    }
    System.out.println("[U-②] 两次 propose 后包内 callIndex=" + indexes);
    assertThat(indexes)
        .as("★ 两次提议的 callIndex 必须互不相同（同一位上叠两条 = 批 S 那条重复链的形状）")
        .doesNotHaveDuplicates();
  }

  /**
   * ★★ <b>对照（防"顺手把非 null 路径碰坏"）：带 intent 那条路径要能读到 {@code intent} 键</b>。
   *
   * <p>批 S 修复选定的 null 语义是「{@code null} ⇒ 视图里<b>没有</b> {@code intent} 键」（不是空串），唯一能把它与"非 null
   * 路径坏了"区分开的，就是这条对照：带 intent ⇒ 视图里既有这个键、值也逐字相同。
   */
  @Test
  void proposeWithIntentStillExposesTheIntentKeyInTheView() {
    long headBefore = world.head();
    ToolResult result = propose(TARGET_TOOL, issueMoneyArgs(), INTENT);
    long delta = world.head() - headBefore;
    System.out.println(
        "[U-②] 单条/带 intent: success="
            + result.success()
            + " code="
            + result.code()
            + " headDelta="
            + delta
            + " body="
            + truncate(result.message(), 200));
    assertThat(result.success()).as("带 intent 必须成功：%s", result.message()).isTrue();
    assertThat(result.code()).as("成功时 code 必须是 null（ToolResult 的成功口径）").isNull();
    assertThat(delta).as("propose 自己落一条 revision").isEqualTo(1L);
    assertThat(viewOf(result))
        .as("★★ 对照：带 intent ⇒ 视图里必须有 intent 键（非 null 路径没被 null-safe 改动碰坏）")
        .containsEntry("intent", INTENT);
  }

  /**
   * ★★ <b>判据 ③-正向：{@code ProposeCallTool} 写后那张视图的形状（键 + 值类型）只含可序列化的标量与嵌套 Map/List</b>。
   *
   * <p>★ <b>为什么是"写后"</b>：这些键由工具在 {@code writes.submitWithReceipt(...)} <b>之后</b>组装，再交给 {@code
   * DecisionPacketPayloads.fold → ToolResults.ok → ToolResults.json}（{@code
   * ProposeCallTool.java:306}）。{@code json} 对不可序列化值会抛（见 {@code ToolResultsUnserializableViewTest}）⇒
   * 视图里出现不可序列化对象 = 工具报 {@code TOOL_ERROR} 而包已落盘。本用例先用 {@code world.head()} 证明写已发生，再逐键检查形状。
   *
   * <p>★ <b>它钉住的是什么、钉不住什么（如实写清）</b>：钉住<b>键集合</b>与<b>每个键的值类型</b>（标量 / List / Map），以及
   * <b>整棵视图递归下来只有这些类型</b>；<b>钉不住</b>"某个可序列化的领域对象被塞进来"（Jackson 会把它渲染成 Map，形状检查看不出来） —— 那一侧靠 {@code
   * ToolResultsUnserializableViewTest} 的负向 + 本类的键集合精确断言兜底。
   */
  @Test
  void thePostWriteProposeViewCarriesOnlyJsonScalarsAndNestedContainers() {
    // ① 不带 intent：写后视图 = 9 个键（没有 intent）。
    long headBefore = world.head();
    ToolResult noIntent = propose(TARGET_TOOL, issueMoneyArgs(), null);
    assertThat(noIntent.success()).as("propose 必须成功：%s", noIntent.message()).isTrue();
    assertThat(world.head()).as("★ 写确实发生了（否则下面检查的不是「写后」视图）").isEqualTo(headBefore + 1L);

    Map<String, Object> view = viewOf(noIntent);
    System.out.println("[U-③] 写后视图键=" + view.keySet());
    assertThat(view.keySet())
        .as("★★ 写后视图的键集合（不带 intent）——增删一个键都必须显式改本行")
        .containsExactlyInAnyOrder(
            "packetId",
            "status",
            "callCount",
            "callIndex",
            "tool",
            "targets",
            "preview",
            "calls",
            "submission");

    assertThat(view.get("packetId")).as("packetId").isInstanceOf(String.class);
    assertThat(view.get("status")).as("status").isInstanceOf(String.class);
    assertThat(view.get("callCount")).as("callCount").isInstanceOf(Number.class);
    assertThat(view.get("callIndex")).as("callIndex").isInstanceOf(Number.class);
    assertThat(view.get("tool")).as("tool").isInstanceOf(String.class);
    assertThat(view.get("targets")).as("targets（List<Map>）").isInstanceOf(List.class);
    assertThat(view.get("preview")).as("preview（Map）").isInstanceOf(Map.class);
    assertThat(view.get("calls")).as("calls（List<Map>）").isInstanceOf(List.class);
    assertThat(view.get("submission")).as("submission（Map）").isInstanceOf(Map.class);
    assertOnlyJsonScalarsAndNestedContainers(view, "$");

    // ② 带 intent：多一个 intent 键，值必须是 String；形状规则不变。
    ToolResult withIntent = propose(TARGET_TOOL, issueMoneyArgs(), INTENT);
    assertThat(withIntent.success()).as("propose 必须成功：%s", withIntent.message()).isTrue();
    Map<String, Object> withIntentView = viewOf(withIntent);
    System.out.println("[U-③] 带 intent 写后视图键=" + withIntentView.keySet());
    assertThat(withIntentView.keySet())
        .as("★★ 带 intent 的写后视图 = 上面 9 个键 + intent")
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                "packetId",
                "status",
                "callCount",
                "callIndex",
                "tool",
                "targets",
                "preview",
                "calls",
                "submission",
                "intent"));
    assertThat(withIntentView.get("intent")).as("intent 必须是 String").isInstanceOf(String.class);
    assertOnlyJsonScalarsAndNestedContainers(withIntentView, "$");
  }

  // ────────────────────────────── 判据与形状小件 ──────────────────────────────

  /**
   * ★★ 判据本体：{@code propose} 不带 intent ⇒ 只许"成功（且 head 正常推进）"或"干净地拒（且 headDelta=0）"。
   *
   * <p>★ 先断言那条<b>被禁止的组合</b>（{@code TOOL_ERROR} + head 涨），再断言两种合法结局各自的形状 —— 前者给的是"批 S
   * 缺陷复活"的具名红，后者防止"以后有人把这条守卫读成『只要不是 TOOL_ERROR 就行』"。
   */
  private static void assertNeverToolErrorBearingARevision(
      ToolResult result, long headDelta, String scenario) {
    assertThat("TOOL_ERROR".equals(result.code()) && headDelta > 0)
        .as(
            "★★★ 判据（批 S V1，%s）：propose 不带 intent 绝不许「返回 TOOL_ERROR 而 head 涨了」"
                + " —— 那正是「写之后抛异常」的形状（世界已变、调用方以为失败）。实测 code=%s headDelta=%d body=%s",
            scenario, result.code(), headDelta, truncate(result.message(), 300))
        .isFalse();
    assertThat(result.code())
        .as("★★ 判据（%s）：TOOL_ERROR 是本工具的兜底异常码 ⇒ 这条路径上出现它就意味着写后抛了", scenario)
        .isNotEqualTo("TOOL_ERROR");
    if (result.success()) {
      assertThat(headDelta).as("★ 合法结局①：成功 ⇒ head 正常推进（propose 自己恰好落一条 revision）").isEqualTo(1L);
      assertThat(viewOf(result))
          .as("★ null intent 的语义 = 视图里没有 intent 键（不是空串），故成功路径上它必须缺席")
          .doesNotContainKey("intent");
    } else {
      assertThat(result.code())
          .as(
              "★ 合法结局②：干净地拒 ⇒ code 必须是**明确拒因**（BAD_REQUEST / FORBIDDEN / CONFLICT / …），不是笼统 TOOL_ERROR")
          .isNotNull();
      assertThat(headDelta).as("★ 合法结局②：干净地拒 ⇒ 世界零变化（headDelta=0）").isZero();
    }
  }

  /**
   * ★ 递归检查：值只能是 JSON 标量（{@code null} / String / Boolean / Number）或 Map / List；Map 的键必须是 String。
   *
   * <p>★ 失败信息带路径（{@code $.calls[0].preview.poison}），便于一眼看出是谁塞进来的。
   */
  private static void assertOnlyJsonScalarsAndNestedContainers(Object value, String path) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Number) {
      return;
    }
    if (value instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        assertOnlyJsonScalarsAndNestedContainers(list.get(i), path + "[" + i + "]");
      }
      return;
    }
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        assertThat(entry.getKey())
            .as("★ %s 的键必须是 String（JSON 对象的键）", path)
            .isInstanceOf(String.class);
        assertOnlyJsonScalarsAndNestedContainers(entry.getValue(), path + "." + entry.getKey());
      }
      return;
    }
    throw new AssertionError(
        "★★ 写后视图里出现了非标量 / 非 Map / 非 List 的值："
            + path
            + " = "
            + value.getClass().getName()
            + "（ToolResults.json 会对不可序列化值抛 ⇒ 这条路径会变成「报 TOOL_ERROR 而包已落盘」）");
  }

  private ToolResult propose(String tool, Map<String, Object> innerArgs, String intent) {
    Map<String, Object> args = new LinkedHashMap<>();
    if (intent != null) {
      args.put("intent", intent);
    }
    args.put("tool", tool);
    args.put("args", innerArgs);
    return factory.execute(
        dmRegistry, ProposeCallTool.NAME, world.dm(DM), world.state(), world.mapId(), args);
  }

  private ToolResult proposeCalls(List<Map<String, Object>> calls) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("calls", calls);
    return factory.execute(
        dmRegistry, ProposeCallTool.NAME, world.dm(DM), world.state(), world.mapId(), args);
  }

  private static Map<String, Object> call(
      String tool, Map<String, Object> innerArgs, String intent) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("tool", tool);
    item.put("args", innerArgs);
    if (intent != null) {
      item.put("intent", intent);
    }
    return item;
  }

  private Map<String, Object> issueMoneyArgs() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", GOV_CENTRAL);
    args.put("currency", "copper");
    args.put("amountMilli", 7777L);
    args.put("reason", "U-②/U-③：propose 判据用的目标载荷（本类不执行它）");
    return args;
  }

  /** 工具结果的成功载荷是 JSON 对象（{@code ToolResults.ok(json(view))}）⇒ 读回成 Map。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> viewOf(ToolResult result) {
    try {
      return JSON.readValue(result.message(), Map.class);
    } catch (Exception e) {
      throw new AssertionError(
          "工具结果不是 JSON 对象（message=" + truncate(result.message(), 300) + "）", e);
    }
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return "(null)";
    }
    return text.substring(0, Math.min(max, text.length()));
  }
}
