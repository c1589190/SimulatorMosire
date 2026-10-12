package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * ★★ <b>单格「可用地面积属性表」的字段键</b>（2026-10-12 批次 2 / 用户第五轮原话逐字：「把城市化率取消掉，额外搞一个 Map，
 * 目前只加两项，后面可以加自定义字段，已有默认字段分别是城市面积和乡村面积」+「自定义字段必须是一个 String，应当明确标注为"自定义字段"， 已有的城市/乡村走 enum 标签声明」）。
 *
 * <p>用户裁定「形状按 A」= 属性表塞进 {@link HexCell}；本类型是它的**键**，两个实现恰好对上那两句话：
 *
 * <ul>
 *   <li>{@link Builtin}（<b>enum 标签声明</b>）：{@link Builtin#URBAN 城市面积} / {@link Builtin#RURAL 乡村面积}
 *       —— 本轮的两个默认字段；
 *   <li>{@link Custom}（<b>String，类型上就标注了「自定义字段」</b>）：后续可加的自定义字段，名由调用方给。
 * </ul>
 *
 * <p>★ <b>线格式（落盘 / 变更集 / JSON 键）的唯一拼写点就是 {@link #wireKey()}</b>：内置 = enum 标签（{@code urban} / {@code
 * rural}），自定义 = {@code custom:<名>} —— 前缀让「自定义字段」与内置标签在**串上也分得开**，两者的取值域因此不可能撞车 （自定义字段可以叫 {@code
 * urban}，线格式是 {@code custom:urban}，与内置的 {@code urban} 不是同一个键）。反方向只有 {@link
 * #parse(String)}；<b>本文件之外不许再拼一遍这两个格式</b>。
 *
 * <p>★ <b>为什么 {@code @JsonValue} 标在接口方法上</b>：{@code GameMap.hexes} 的值里的 {@code areas} 是一张 {@code
 * Map<HexAreaKey, Long>}，键的线格式必须**自描述** —— 往返整图的裸 {@code SimosObjectMapper}（无模块那次，见 {@code
 * RegressionGuardsTest} 的 L7）也读得回来，不能靠"某个 codec 记得注册键序列化器"。 ★ 探针实测（2026-10-12，jackson 2.22.2，即本仓
 * pom 钉住的版本）：标在**接口方法**上时两个实现都取它（枚举键写标签而非 {@code name()}、record 键写 {@code
 * custom:<名>}）；标在实现类自己身上对枚举键**无效**（会写成 {@code name()}）⇒ 只能标在这里，且实现类不得重复标注（那会造出两个 "as-value"
 * 属性）。同款先例：{@code economy-api} 的 {@code CohortKey}（{@code @JsonValue} + {@code @JsonCreator} 的
 * record 键）。
 *
 * <p>★ <b>量纲</b>：值一律是<b>毫亩</b>（{@code long}），与 economy 侧 {@code EconomySeeder} 的土地量纲同源（默认值的算式见
 * {@link io.mosire.simos.map.terrain.TerrainCatalog#defaultUsableAreaMilliMu(String)}）。
 *
 * <p>★ <b>本批不与其他模块联动</b>（用户原话：「目前两者只给一个加减赋值，不和其他任何模块联动」）：本类型只是 map 自己的属性表键， 不含任何产能/人口语义。
 */
public sealed interface HexAreaKey {

  /** 自定义字段的线格式前缀（**唯一定义处**：{@link Custom#wireKey()} 与 {@link #parse(String)} 共用它）。 */
  String CUSTOM_PREFIX = "custom:";

  /** 线格式：落盘 / 变更集 / JSON 键 / 日志里的规范串（同时也是读面输出里的字段名）。 */
  @JsonValue
  String wireKey();

  /**
   * <b>内置字段</b>（enum 标签声明）：本轮的两个默认字段 —— 城市面积 / 乡村面积。
   *
   * <p>★ 标签是**小写**的（{@code urban} / {@code rural}）：它同时是线格式与载荷里 {@code key} 字段的取值 ⇒ 只有这一个拼写点，不接受
   * {@code URBAN} 这类大写变体（fail-closed 拒绝并列出合法标签）。
   */
  enum Builtin implements HexAreaKey {
    URBAN("urban"),
    RURAL("rural");

    private final String label;

    Builtin(String label) {
      this.label = label;
    }

    /** 内置标签（= 线格式）。 */
    public String label() {
      return label;
    }

    @Override
    public String wireKey() {
      return label;
    }

    /** 标签 → 内置字段；**词表外一律抛**（不兜底、不忽略大小写）。错误消息现取本枚举的标签，故新增一档不必改文案。 */
    public static Builtin parse(String text) {
      for (Builtin value : values()) {
        if (value.label.equals(text)) {
          return value;
        }
      }
      StringBuilder labels = new StringBuilder();
      for (Builtin value : values()) {
        if (labels.length() > 0) {
          labels.append(" | ");
        }
        labels.append(value.label);
      }
      throw new IllegalArgumentException("未知内置字段标签: " + text + "（合法标签: " + labels + "）");
    }
  }

  /**
   * <b>自定义字段</b>（String）：名由调用方给，线格式是 {@code custom:<名>}。
   *
   * <p>★ 名字**不得空白**（空白名是无从引用的字段）；不禁止用内置标签当名字（线格式带前缀，撞不上）。
   *
   * @param name 自定义字段名（非空白）
   */
  record Custom(String name) implements HexAreaKey {

    public Custom {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("自定义字段名不得为空白: " + name);
      }
    }

    @Override
    public String wireKey() {
      return CUSTOM_PREFIX + name;
    }
  }

  /**
   * 线格式 → 键（{@link #wireKey()} 的逆，**唯一入口**）：{@code custom:} 开头 ⇒ 自定义字段，否则按内置标签查词表。
   *
   * <p>★ 坏串一律 {@link IllegalArgumentException}（宁抛不静默）：读回一个半截的键比当场炸难查得多。它同时也是 Jackson 的 Map
   * 键入口（{@code @JsonCreator}，实测接口上的静态工厂对 Map 键生效）⇒ 落了 {@code {"areas":{"bogus":1}}} 的档读不回来时
   * 是**具名失败**，不是静默丢字段。
   */
  @JsonCreator
  static HexAreaKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("字段键不得为空白: " + text);
    }
    if (text.startsWith(CUSTOM_PREFIX)) {
      return new Custom(text.substring(CUSTOM_PREFIX.length()));
    }
    return Builtin.parse(text);
  }
}
