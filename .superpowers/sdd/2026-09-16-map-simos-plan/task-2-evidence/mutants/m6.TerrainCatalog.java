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
 */
public final class TerrainCatalog {

  /** 7 项的 key，**顺序 = 高度升序 = 落盘顺序**（U1）。 */
  public static final List<String> KEYS =
      List.of(
          "ocean", "plains", "desert", "low_hills", "mountains", "plateau_mountains", "plateau");

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
        new TerrainType("plateau", "平缓高原", "#B99B6B", 0.78, 0.90, 1, 1, 1, 4, "海拔高但地势平坦，相对好走"));
    // 7 高原山地：最高；几乎不可通行（12，逼近但不到海洋的哨兵）。
    m.put(
        "plateau_mountains",
        new TerrainType(
            "plateau_mountains", "高原山地", "#68798C", 0.90, 1.00, 0, 1, 2, 12, "海拔最高处，几乎不可通行"));
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
}
