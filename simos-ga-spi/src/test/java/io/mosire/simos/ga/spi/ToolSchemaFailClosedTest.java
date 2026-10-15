package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 B：{@code ToolSchema} 的「坏输入当场抛 / 判不了不放行」逐条负向</b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>本类（被测类）的类注写着口径：<b>坏输入一律 {@code IllegalArgumentException}（由各工具的 {@code execute} 折成具名 {@code
 * BAD_REQUEST}，不静默按缺省办）——「判不了就放行 / 就当没给」正是本仓要消灭的形态</b>。而这里的坏法全都是<b>没有症状</b>的：
 *
 * <ul>
 *   <li>{@code optionalText} 对「给了但空串」静默回落成 fallback ⇒ 调用方以为拿到了模型给的值，实际拿到缺省值；
 *   <li>{@code optionalLong} 对 {@code "abc"} 静默回落 0 ⇒ 命令按 0 落盘；
 *   <li>{@code optionalBoolean} 对 {@code "yes"} 静默当 false ⇒ 一次本该被拒的调用按「关」执行。
 * </ul>
 *
 * <p>★ 每条负向都配<b>正向对照</b>（同形状的合法输入不抛、且返回原值）——否则「抛了」可能只是夹具本身坏了。★ 文本类返回值一律断言 <b>不
 * trim、不改字节</b>（工具载荷是对外载荷，改一个字节就是改语义）。
 */
class ToolSchemaFailClosedTest {

  // ────────────────────────── 文本 ──────────────────────────

  /** ★★ <b>必填文本：缺失 / 空串 / 全空白 / 非文本 ⇒ 当场抛，且报错点名参数</b>。 */
  @Test
  void requiredTextRefusesMissingBlankAndNonTextValues() {
    assertThatThrownBy(() -> ToolSchema.requiredText(argsWith("targetId", null), "targetId"))
        .as("★★ 判据 B：必填参数缺失 ⇒ 抛（不许当空串、不许当缺省办）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetId");
    assertThatThrownBy(() -> ToolSchema.requiredText(argsWith("targetId", ""), "targetId"))
        .as("★★ 判据 B：给了空串 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetId");
    assertThatThrownBy(() -> ToolSchema.requiredText(argsWith("targetId", "   "), "targetId"))
        .as("★★ 判据 B：全空白也算没给 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetId");
    assertThatThrownBy(() -> ToolSchema.requiredText(argsWith("targetId", 42), "targetId"))
        .as("★★ 判据 B：类型不对（模型给了数字）⇒ 抛，不许 toString 兜底")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetId");

    assertThat(ToolSchema.requiredText(argsWith("targetId", " u-1 "), "targetId"))
        .as("对照：合法值原样返回（不 trim、不改字节——工具载荷是对外载荷）")
        .isEqualTo(" u-1 ");
  }

  /**
   * ★★ <b>{@code optionalText} 只在「键缺失」时回落 fallback；「给了但空 / 类型不对」一律抛</b>。
   *
   * <p>★ 这是本类最要紧的一条：静默把「给了空串」读成「没给」是典型的放宽——调用方拿着缺省值继续跑，世界按缺省值改，而模型 以为自己指定了。
   */
  @Test
  void optionalTextFallsBackOnlyWhenTheKeyIsAbsent() {
    assertThat(ToolSchema.optionalText(new HashMap<>(), "reason", "缺省理由"))
        .as("对照：键缺失 ⇒ 回落 fallback（这是唯一允许的静默缺省）")
        .isEqualTo("缺省理由");
    assertThat(ToolSchema.optionalText(argsWith("reason", null), "reason", "缺省理由"))
        .as("对照：显式 null 与缺失同义 ⇒ 回落")
        .isEqualTo("缺省理由");

    assertThatThrownBy(() -> ToolSchema.optionalText(argsWith("reason", ""), "reason", "缺省理由"))
        .as("★★ 判据 B：给了空串不许静默回落成 fallback ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
    assertThatThrownBy(() -> ToolSchema.optionalText(argsWith("reason", "  "), "reason", "缺省理由"))
        .as("★★ 判据 B：给了全空白同样抛（不许当「没给」）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
    assertThatThrownBy(() -> ToolSchema.optionalText(argsWith("reason", 7), "reason", "缺省理由"))
        .as("★★ 判据 B：类型不对 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");

    assertThat(ToolSchema.optionalText(argsWith("reason", " x "), "reason", "缺省理由"))
        .as("对照：给了合法值 ⇒ 原样返回")
        .isEqualTo(" x ");
  }

  // ────────────────────────── 整数 ──────────────────────────

  /** ★★ <b>整数解析：{@code "abc"} / 空串 / 布尔 ⇒ 抛；缺失（必填）⇒ 抛；数字与数字串照收</b>。 */
  @Test
  void integersRefuseEverythingThatIsNotAnInteger() {
    assertThat(ToolSchema.requiredLong(argsWith("revision", "42"), "revision"))
        .as("对照：必填整数（数字串形态）照收")
        .isEqualTo(42L);
    assertThatThrownBy(() -> ToolSchema.requiredLong(new HashMap<>(), "revision"))
        .as("★★ 判据 B：必填整数缺失 ⇒ 抛（不许当 0）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");
    assertThatThrownBy(() -> ToolSchema.optionalLong(argsWith("revision", "abc"), "revision"))
        .as("★★ 判据 B：解析不了 ⇒ 抛（不许当 0）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");
    assertThatThrownBy(() -> ToolSchema.optionalLong(argsWith("revision", ""), "revision"))
        .as("★★ 判据 B：空串 ⇒ 抛（不许当 0、不许当缺省）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");
    assertThatThrownBy(() -> ToolSchema.optionalLong(argsWith("revision", true), "revision"))
        .as("★★ 判据 B：类型不对 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revision");

    assertThat(ToolSchema.optionalLong(new HashMap<>(), "revision"))
        .as("对照：键缺失 ⇒ null（可选参数意义上的「没给」）")
        .isNull();
    assertThat(ToolSchema.optionalLong(argsWith("revision", " 42 "), "revision"))
        .as("对照：数字串（含首尾空白）照收")
        .isEqualTo(42L);
    assertThat(ToolSchema.optionalLong(argsWith("revision", 7L), "revision"))
        .as("对照：数字原样取 long")
        .isEqualTo(7L);
    assertThat(ToolSchema.optionalLong(argsWith("revision", 3.9d), "revision"))
        .as("对照：浮点按 longValue 截断（契约写的是「数字原样取 long」）")
        .isEqualTo(3L);
  }

  // ────────────────────────── 布尔 ──────────────────────────

  /** ★★ <b>布尔解析：只认 {@code true}/{@code false}（忽略大小写与首尾空白），其余（含 {@code yes}、数字）⇒ 抛</b>。 */
  @Test
  void optionalsRefuseUnparsableBooleansInsteadOfGuessing() {
    assertThat(ToolSchema.optionalBoolean(new HashMap<>(), "dryRun"))
        .as("对照：键缺失 ⇒ Optional.empty()")
        .isEmpty();
    assertThat(ToolSchema.optionalBoolean(argsWith("dryRun", true), "dryRun"))
        .as("对照：真布尔照收")
        .contains(true);
    assertThat(ToolSchema.optionalBoolean(argsWith("dryRun", " TRUE "), "dryRun"))
        .as("对照：字符串形态（trim + 忽略大小写）照收")
        .contains(true);
    assertThat(ToolSchema.optionalBoolean(argsWith("dryRun", "false"), "dryRun"))
        .as("对照：false 是明确的 false，不是「没给」")
        .contains(false);

    assertThatThrownBy(() -> ToolSchema.optionalBoolean(argsWith("dryRun", "yes"), "dryRun"))
        .as("★★ 判据 B：yes 判不出 ⇒ 抛（不许猜成 false）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dryRun");
    assertThatThrownBy(() -> ToolSchema.optionalBoolean(argsWith("dryRun", 1), "dryRun"))
        .as("★★ 判据 B：数字判不出 ⇒ 抛（不许拿 0/1 猜真假）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dryRun");
    assertThatThrownBy(() -> ToolSchema.optionalBoolean(argsWith("dryRun", "  "), "dryRun"))
        .as("★★ 判据 B：全空白 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dryRun");
  }

  // ────────────────────────── has / schema 小件 ──────────────────────────

  /** ★ <b>{@code has} 的判据是「给了值」：显式 null = 没给；空串 = 给了（是否合法由文本解析器判）</b>。 */
  @Test
  void hasTreatsAnEmptyStringAsGiven() {
    assertThat(ToolSchema.has(new HashMap<>(), "x")).isFalse();
    assertThat(ToolSchema.has(argsWith("x", null), "x")).as("★ 显式 null = 没给").isFalse();
    assertThat(ToolSchema.has(argsWith("x", ""), "x"))
        .as("★ 空串 = 给了（是否合法由 requiredText/optionalText 判，不在这一层吞掉）")
        .isTrue();
    assertThat(ToolSchema.has(argsWith("x", 0), "x")).as("★ 0 是给了（不许按假值判）").isTrue();
  }

  /**
   * ★ <b>schema 小件的载荷形状：键序、{@code required} 的「空则整项不出现」、以及缺省分支名不是手抄的</b>。
   *
   * <p>守的坏法：{@code "required":[]} 与「整项不出现」对模型的含义不同；把 {@code DEFAULT_BRANCH} 的值手抄进描述文本 ⇒
   * 改缺省分支时描述与实际行为两处不一致（第二处拼写点，且无症状）。
   */
  @Test
  void schemaHelpersKeepTheExactWireShape() {
    assertThat(ToolSchema.prop("string", "说明"))
        .as("prop 的键序 = type, description")
        .containsExactly(entry("type", "string"), entry("description", "说明"));

    Map<String, Object> target = ToolSchema.targetProps();
    assertThat(target.keySet())
        .as("targetProps 的键序 = branch, revision")
        .containsExactly("branch", "revision");
    @SuppressWarnings("unchecked")
    Map<String, Object> branchProp = (Map<String, Object>) target.get("branch");
    assertThat(branchProp).containsEntry("type", "string");
    assertThat(branchProp.get("description"))
        .asInstanceOf(InstanceOfAssertFactories.STRING)
        .as("★ 缺省分支名必须是 DEFAULT_BRANCH 的实际值（手抄 main ⇒ 改名后描述与行为两处不一致）")
        .contains(ToolResources.DEFAULT_BRANCH);
    @SuppressWarnings("unchecked")
    Map<String, Object> revisionProp = (Map<String, Object>) target.get("revision");
    assertThat(revisionProp).containsEntry("type", "integer");

    Map<String, Object> noRequired =
        ToolSchema.schema(Map.of("a", ToolSchema.prop("string", "a")), List.of());
    assertThat(noRequired.keySet())
        .as("★ required 为空 ⇒ 整项不出现（不是空数组）")
        .containsExactly("type", "properties");
    assertThat(noRequired).containsEntry("type", "object");

    Map<String, Object> withRequired = ToolSchema.schema(Map.of(), List.of("a", "b"));
    assertThat(withRequired.keySet())
        .as("schema 的键序 = type, properties, required")
        .containsExactly("type", "properties", "required");
    assertThat(withRequired.get("required")).isEqualTo(List.of("a", "b"));
  }

  /** 带一个键的参数表（{@link HashMap}：允许 null 值——{@code Map.of} 会当场 NPE，测不到"显式 null"那一支）。 */
  private static Map<String, Object> argsWith(String name, Object value) {
    Map<String, Object> args = new HashMap<>();
    args.put(name, value);
    return args;
  }
}
