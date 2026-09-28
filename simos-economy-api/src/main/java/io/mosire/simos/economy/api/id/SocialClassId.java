package io.mosire.simos.economy.api.id;

import java.util.List;

/**
 * ★★ **社会阶层 id**（S1 spec §2.6）：**产业无关**的人口身份。
 *
 * <p>★★ **为什么必须与它取代的 {@code ClassSlotId} 分开** —— 那个类型已在 S1 阶段 1 删除，所以它在这里只能写 {@code @code}（写
 * {@code @link} 会留下悬空引用）。它的类注原文是"**一个产业内**同一制度允许的一个阶层槽位 （贫农/中农/地主…）"——
 * 它是"**制度允许哪些角色**"的清单。而**阶层是人的属性**：同一个 {@code poor_peasant} 出现在农业行与手工业行里，指的是**同一个社会阶层**。
 *
 * <p>★ **当前四模板确实同构，但那是填充习惯、不是类型约束**：三处产业模板共用一对全局数组 （{@code EconomySeeder.CLASS_IDS} / {@code
 * CLASS_LABOR_PER_MILLE}）⇒ 换成新类型后， "谁都可以往槽位里填别的值"这件事**在类型上被堵死**。这正是本类型存在的理由。
 *
 * <p>★ **词表外即抛**（fail-closed）：静默接受会让"写错阶层"变成运行时幽灵。
 *
 * <p>★ **它不携带任何经济参数**：劳动参与率住在 {@code ClassSlot.laborParticipationPerMille}（**产业侧**）， 不随身份走 ——
 * 阶层身份与"这个产业里这个阶层投多少劳动"是两件事。
 *
 * <p>★ **裸值 + {@code parse} 三件套**（铁律 1）：{@code toString()} 是规范串的第二段。
 */
public record SocialClassId(String value) {

  /**
   * ★★ **词表（保序：贫农 → 中农 → 富农 → 地主）—— 值的唯一来源。**
   *
   * <p>★★ **它必须是 {@code String} 字面量、且声明在四个常量之前**：四个常量都要经过本类型的**构造器**， 而构造器要拿词表做校验 —— 若校验读的是
   * `List<SocialClassId>` 那个常量， 就会在**静态初始化期**读到 `null`（{@code
   * ExceptionInInitializerError}，本类的首版就这么挂的， 是 {@code SocialClassIdTest} 当场抓出来的）。
   */
  private static final List<String> VALUES =
      List.of(
          "poor_peasant",
          "middle_peasant",
          "rich_peasant",
          "landlord",
          "landless_laborer",
          "artisan",
          "official");

  /** 贫农。 */
  public static final SocialClassId POOR_PEASANT = new SocialClassId(VALUES.get(0));

  /** 中农。 */
  public static final SocialClassId MIDDLE_PEASANT = new SocialClassId(VALUES.get(1));

  /** 富农。 */
  public static final SocialClassId RICH_PEASANT = new SocialClassId(VALUES.get(2));

  /** 地主。 */
  public static final SocialClassId LANDLORD = new SocialClassId(VALUES.get(3));

  /** ★ S3：无地雇农/佃工（无资产份额且净卖劳动）。 */
  public static final SocialClassId LANDLESS_LABORER = new SocialClassId(VALUES.get(4));

  /** ★ S3：手工业者（laborSource=WAGE/FAMILY 且经营手工业的 fallback 档）。 */
  public static final SocialClassId ARTISAN = new SocialClassId(VALUES.get(5));

  /** ★ S3：官署依附/公职（官署产业或 communal 权的 fallback 档）。 */
  public static final SocialClassId OFFICIAL = new SocialClassId(VALUES.get(6));

  /** 词表（**保序**）—— 由常量派生，供遍历与断言用（★ S3 只追加，旧四档位置不变）。 */
  private static final List<SocialClassId> ALL =
      List.of(
          POOR_PEASANT,
          MIDDLE_PEASANT,
          RICH_PEASANT,
          LANDLORD,
          LANDLESS_LABORER,
          ARTISAN,
          OFFICIAL);

  public SocialClassId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("SocialClassId 不得为空白");
    }
    if (!VALUES.contains(value)) {
      throw new IllegalArgumentException("词表外的社会阶层: " + value + "（合法值: " + VALUES + "）");
    }
  }

  /** 词表（保序）—— 供遍历与断言用（{@code EconomySeeder} 按此顺序对齐参与率表）。 */
  public static List<SocialClassId> all() {
    return ALL;
  }

  @Override
  public String toString() {
    return value;
  }

  /** 按文本解析：**词表外的值即抛**（见类注）。 */
  public static SocialClassId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("SocialClassId 不得为空白: " + text);
    }
    return new SocialClassId(text); // ★ 构造期已做词表校验
  }
}
