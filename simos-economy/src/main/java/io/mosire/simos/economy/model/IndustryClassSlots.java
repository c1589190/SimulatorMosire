package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.SocialClassId;
import java.util.List;

/**
 * ★★ <b>W7（2026-10-11）：产业模板的"四阶层槽位"表 —— 全仓唯一拼写点</b>。
 *
 * <p>★★ <b>为什么它必须上移到 economy</b>：W7 起"非城市格的跑商"允许在迁移时<b>按需创建</b>目标格的 {@code trade} 产业记录 （见 {@code
 * MerchantTradeTemplate}）—— 那条写路径在 <b>economy 的 tick 内部</b>（{@code ModeMigrationSettlement}），
 * 而它建出来的模板必须与创世播种器（{@code simos-app} 的 {@code EconomySeeder}）建出来的<b>逐字段同值</b>，
 * 否则"新长出来的商号"与"创世播的商号"就成了两个物种。槽位表此前只存在于 app（{@code EconomySeeder.CLASS_IDS} 一族）， economy 看不到它 ⇒
 * 只能在这一侧复制一份（两份拼写点，迟早漂开）。
 *
 * <p>⇒ 本类把它落成 economy 的常量，{@code EconomySeeder} 的那三个数组<b>改为指向本类</b>（名字与类型一字不变）： 一处拼写、两处引用。
 *
 * <pre>
 * id                       名字      劳动参与率‰（{@link ClassSlot#laborParticipationPerMille()}）
 * poor_peasant    贫农      950
 * middle_peasant  中农      900
 * rich_peasant    富农      750
 * landlord        地主      100
 * </pre>
 *
 * <p>★ 值逐字取自 {@code EconomySeeder} 的既成常量（{@code CLASS_IDS} / {@code CLASS_NAMES} / {@code
 * CLASS_LABOR_PER_MILLE}），<b>不是新标定</b>。★ 本类无状态、无公式，只有一张表 + 一个装配函数。
 */
public final class IndustryClassSlots {

  private IndustryClassSlots() {}

  /** 四个阶层槽位的规范 id（顺序即 {@link #slots()} 的顺序，与既有常量逐字同序）。 */
  public static final List<String> IDS =
      List.of("poor_peasant", "middle_peasant", "rich_peasant", "landlord");

  /** 与 {@link #IDS} 同序的展示名。 */
  public static final List<String> NAMES = List.of("贫农", "中农", "富农", "地主");

  /**
   * 与 {@link #IDS} 同序的劳动参与率（‰）。★ <b>私有</b>：可变数组不得 public 暴露（SpotBugs 的 {@code
   * MS_MUTABLE_ARRAY}；与它同族的纪律见 {@code Industry} 类注"冻结写在字段赋值处"）—— 外部只经 {@link #laborPerMille()}
   * 取一份拷贝。
   */
  private static final int[] LABOR_PER_MILLE = {950, 900, 750, 100};

  /** ★ 与 {@link #IDS} 同序的劳动参与率（‰）的**拷贝**（调用方改它不影响本表；唯一写口 = 本类）。 */
  public static int[] laborPerMille() {
    return LABOR_PER_MILLE.clone();
  }

  /** ★ 地主槽位的下标（= 3；与 {@code EconomySeeder.LANDLORD_SLOT_INDEX} 同值）。 */
  public static final int LANDLORD_SLOT_INDEX = 3;

  /** ★ 中农槽位的下标（= 1；城镇作坊主 / 跑商本金主的落点，与既有创世口径同值）。 */
  public static final int MIDDLE_PEASANT_SLOT_INDEX = 1;

  /**
   * ★ 四个槽位（{@link ClassSlot} 的保序不可变列表）——<b>产业模板建槽位的唯一入口</b>。
   *
   * <p>★ 返回 {@code List.copyOf}（不可变、保序）；逐项值 = {@link #IDS}/{@link #NAMES}/{@link #LABOR_PER_MILLE}
   * 同下标。
   */
  public static List<ClassSlot> slots() {
    return List.of(
        new ClassSlot(SocialClassId.parse(IDS.get(0)), NAMES.get(0), LABOR_PER_MILLE[0]),
        new ClassSlot(SocialClassId.parse(IDS.get(1)), NAMES.get(1), LABOR_PER_MILLE[1]),
        new ClassSlot(SocialClassId.parse(IDS.get(2)), NAMES.get(2), LABOR_PER_MILLE[2]),
        new ClassSlot(SocialClassId.parse(IDS.get(3)), NAMES.get(3), LABOR_PER_MILLE[3]));
  }
}
