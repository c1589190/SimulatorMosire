package io.mosire.simos.map.terrain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ **全模块唯一的地形词表。**
 *
 * <p>GSimulator 有至少 9 份互不相同的副本，其中两份在同一批 key 上完全分叉（{@code plains} 在 A 里是"平原"/绿/3,1，在 B
 * 里是"山区"/土黄/2,2），且 {@code ContourQueryEngine.terrainColor} 的兜底色正是 A 的平原绿 —— 串味铁证。
 *
 * <p>★ 用户裁决 U1 把 GSimulator 那份 9 项表**整个作废**（不是改名）：本表 7 项、**按高度从小到大**，由 {@link #KEYS} 固定其序。**7
 * 行的具体数值（颜色 / 产出 / moveCost / 高度带边界）为 M2 Task 2 新定，非来自 GSimulator。**
 *
 * <p>★ **最高带的边界另经 M2 Task 10 实测校正：下界 0.90 → 0.85。** 默认参数下 48 张图（24 种子 × 2 噪声变体）**没有一格**高度达到
 * 0.90（实测最大 0.8969）——{@code plateau_mountains} 会**结构性产不出来**，即"词表里有一项永远是谎话"。改为 0.85 后，24 个种子里有 6 个产出
 * 2~12 格（实测）。
 */
public final class TerrainCatalog {

  /** 7 项的 key，**顺序 = 高度升序 = 落盘顺序**（U1）。 */
  public static final List<String> KEYS =
      List.of(
          "ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");

  /**
   * ★ 唯一的默认词表。**迭代序 = 高度升序**。
   *
   * <p>★ **保序**：GSimulator 的 {@code Map.copyOf} 会打乱迭代序，使同一份表在两个存档里顺序不同，字节级往返因此不成立。此处用 {@link
   * LinkedHashMap} 且**不 copyOf**。
   *
   * <p>★ **每次新建、不做静态缓存**：静态初始化一旦抛异常会变成 {@code ExceptionInInitializerError}，比词表自己抛的 {@link
   * IllegalArgumentException} 难查得多——而"7 项都构造得出来"正是一条要看见 IAE 的用例。
   *
   * <p>★ 带边界的相接处（上一项的 {@code maxHeight} 与下一项的 {@code minHeight}）写的是**同一个字面量**， 不是两次数值计算的结果 ——
   * 所以"带连续"可以用浮点 {@code ==} 断言，无需容差。
   */
  public static Map<String, TerrainType> defaults() {
    Map<String, TerrainType> m = new LinkedHashMap<>();
    // 1 海洋：最低；不可通行（moveCost 999 是**不可通行的哨兵值**）；无产出。
    m.put(
        "ocean",
        new TerrainType("ocean", "海洋", "#1F5FA0", 0.00, 0.30, 0, 0, 0, 999, "海滨与内海的水体，不可通行、无产出"));
    // 2 平原：产能最高（food 3 全表最大）、最好走（moveCost 1 全表最小）。
    m.put(
        "plains",
        new TerrainType("plains", "平原", "#9CCB5B", 0.30, 0.45, 3, 0, 0, 1, "可耕作的核心地带，产能最高、最好走"));
    // 3 沙漠：高度带窄（0.10），且**另需低湿度门**（分类器的事）——否则每张图都会在这个高度长出一圈沙漠环。
    m.put(
        "desert",
        new TerrainType("desert", "沙漠", "#E7C86E", 0.45, 0.55, 0, 1, 1, 3, "干旱带上的贫瘠地形，产出少而难走"));
    // 4 低矮丘陵：产量中等、略难走；矿藏的起点。
    m.put(
        "low_hills",
        new TerrainType(
            "low_hills", "低矮丘陵", "#A8B36A", 0.55, 0.65, 2, 1, 1, 2, "低地与山地的过渡带，产量中等、略难走"));
    // 5 山地：石/矿富集（stone 3 全表最大）、很难走。
    m.put(
        "mountains",
        new TerrainType("mountains", "山地", "#7A7F85", 0.65, 0.78, 0, 2, 3, 6, "石与矿富集，很难走"));
    // 6 平缓高原：海拔高于山地却相对好走（moveCost 4 < 山地的 6）——与山地相反的取舍。
    m.put(
        "plateau",
        new TerrainType("plateau", "平缓高原", "#B99B6B", 0.78, 0.85, 1, 1, 1, 4, "海拔高但地势平坦，相对好走"));
    // 7 高原山地：最高；几乎不可通行（12，逼近但不到海洋的哨兵）。下界 0.85 为 Task 10 实测校正（见类注释）。
    m.put(
        "plateau_mountains",
        new TerrainType(
            "plateau_mountains", "高原山地", "#68798C", 0.85, 1.00, 0, 1, 2, 12, "海拔最高处，几乎不可通行"));
    return Collections.unmodifiableMap(m);
  }

  /** 按 key 取；不存在即抛，**不兜底**（兜底正是 GSimulator 串味的来源）。 */
  public static TerrainType of(String key) {
    TerrainType t = defaults().get(key);
    if (t == null) {
      throw new IllegalArgumentException("未知地形类型: " + key);
    }
    return t;
  }

  // ── 可用地面积的**地形默认值**（2026-10-12 批次 2）─────────────────────────────────────

  /**
   * 每格土地基准（亩）：**量纲标定值**，与 economy 的 {@code EconomySeeder.MU_PER_HEX}（3,100L，v2 spec §10.3 定案 A）
   * 同值。★ 两者的关系是**同源重复点**：本批（用户裁定"不与任何模块联动"）不动 {@code EconomySeeder}，故这个数今天有两处拼写； 将来定"土地权威归谁"时应把
   * economy 侧那一份改成引用本处（见批次报告"将来会撞的口子"）。
   */
  public static final long MU_PER_HEX = 3_100L;

  /**
   * 满可耕地的产能档 = 本表里可耕作核心那档的 {@code food}（{@code EconomySeeder.FOOD_AT_FULL_ARABLE} 的同源值）： {@link
   * #defaultUsableAreaMilliMu(String)} 的分母。
   */
  public static final int FOOD_AT_FULL_ARABLE = 3;

  /**
   * ★★ <b>某地形的默认可用地面积</b>（<b>毫亩</b>，2026-10-12 批次 2 / 用户原话「根据地形在初始化世界的时候先给乡村面积一个固定值」 +
   * 「这个可用地面积如果没有，那么读的时候就走默认，也就是单个地形的默认可用面积」）。
   *
   * <p>逐格默认值 = 该地形的可耕作系数（千分）× 每格土地基准：
   *
   * <ul>
   *   <li>{@code food} 最高那档（= {@link #FOOD_AT_FULL_ARABLE}）⇒ 满值 {@code 3_100 × 1000 = 3,100,000}
   *       毫亩；
   *   <li>次一档（{@code food = 2}）⇒ {@code 3_100 × 666 = 2,064,600} 毫亩；
   *   <li>再一档（{@code food = 1}）⇒ {@code 3_100 × 333 = 1,032,300} 毫亩；
   *   <li>其余（{@code food = 0}：水体与不毛之地）⇒ <b>0</b> 毫亩。
   * </ul>
   *
   * <p>★ <b>算法与 {@code EconomySeeder.arablePerMilleOf(food) = food * 1000 / FOOD_AT_FULL_ARABLE}
   * 逐字同源</b> （整数除法：{@code 2 × 1000 / 3 = 666}，不是 666.67 ⇒ 整数语义也要一致，别顺手改成浮点）。
   *
   * <p>★★ <b>为什么落在这里、且只按 {@code food} 派生</b>：{@code RegressionGuardsTest} 的 L9③ 禁止四个地形 key 出现在
   * <b>{@code simos-map/src/main} 里本文件之外的任何文件</b>（连注释都算）⇒ 默认值表**只能**住在 {@link TerrainCatalog}
   * 内，且不许按 key 写字面量分支，只能按 {@link TerrainType#food()} 派生。
   *
   * @param key 地形 key（必须在 {@link #KEYS} 内；词表外由 {@link #of} fail-closed 抛）
   * @return 该地形的默认可用地面积（毫亩，≥ 0）
   */
  public static long defaultUsableAreaMilliMu(String key) {
    return MU_PER_HEX * arablePerMilleOf(of(key).food());
  }

  /** 可耕作系数（千分）：与 economy 的 {@code EconomySeeder.arablePerMilleOf} 同式（整数除法，不四舍五入）。 */
  private static int arablePerMilleOf(int food) {
    return food * 1000 / FOOD_AT_FULL_ARABLE;
  }
}
