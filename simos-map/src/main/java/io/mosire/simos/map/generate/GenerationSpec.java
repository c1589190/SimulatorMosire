package io.mosire.simos.map.generate;

import java.util.Objects;

/**
 * 生成的**全部**输入。落盘进 {@code GameMap}，故**同 spec 必然同图**（L7）。
 *
 * <p>★ 本类型取代 GSimulator 的 {@code MapGenerator.generate(worldId, seed, mapRadius, mainRidges,
 * fragments, landRatio, coastRoughness, contourCacheMax)} 那 8 个形参，并把散在 {@code MapGenerator} 与
 * {@code ContourQueryEngine} 方法体内的阈值/频率/权重**全部提成字段**（分组见 {@link NoiseBands}/{@link
 * RidgeParams}/{@link FragmentParams}）。**默认值只有 {@link #defaults(long)} 这一份** —— GSimulator
 * 那边同样的三个数散在前端 JS、MCP 工具与 {@code MapConfig} 三处且已经分歧（radius 120 vs 80）。
 *
 * <p>★ **删掉两个装饰形参**（spec §6.4、侦察 D 实测）：
 *
 * <ul>
 *   <li>{@code worldId} —— 生成器**不需要**知道世界 ID：{@code MapGenerator.java:243-245} 收到它却从未读过。
 *   <li>{@code coastRoughness} —— **函数体内从未被引用**：它从 WebUI/MCP 一路传到 {@code MapGenerator.generate}
 *       的最内层重载（`:234-246`）后就没有下文，真正决定海岸的是 {@code coastFreq}（那是个**真**参数）。 要调海岸，就调 {@link
 *       NoiseBands#coastFreq()} 与 {@link NoiseBands#coastAmplitude()}。
 * </ul>
 *
 * <p>★ {@code landRatio} **改名 {@code baseSeaLevel}** —— 实测它只影响一个数 （{@code baseSeaLevel = 0.18 + (1
 * - landRatio) * 0.05}，{@code MapGenerator.java:130}）。 叫 {@code landRatio} 却只调海平面是误导，参数名必须诚实反映作用。
 *
 * <p>★ **全部范围校验在构造期抛异常，不静默夹取** —— GSimulator 的 {@code Math.max(1, Math.min(mainCount,
 * 2))}（`MapGenerator.java:61`）让传 5 静默变成 2， 传 0 静默变成 1：调用方以为改了参数、实际没有。本类型一律抛 {@link
 * IllegalArgumentException}。 **只守"越界会静默失效"的那些**：形状量（{@link RidgeParams} 的长度/权重/抖动）取负只是另一种分布，
 * 不设守卫（不为不存在的世界写代码）；{@link FragmentParams} 的除数为 0 会当场 {@link ArithmeticException}，也是响的，不另设守卫。
 *
 * <p>★ **U1：本类型（含三个子 record）里没有任何"按高度切地形"的阈值。** 允许留下的是**造海拔的过程参数** ——
 * 噪声频率、脊线数量与形状、海岸线的噪声振幅这类"海拔怎么长出来"的量；**不允许**的是"海拔多高算山" 这类分界。判据：**一个数是在塑造海拔值（可以），还是在给海拔分类（不行）**。
 * 地形名与高度带的**唯一持有者**是 {@link io.mosire.simos.map.terrain.TerrainCatalog} （{@code
 * TerrainType.minHeight/maxHeight}），GSimulator 的 {@code ContourQueryEngine.classify}
 * （`:229-258`）里那套 {@code 0.68 / 0.48 / 0.36 / 0.14 / 0.22 / 0.12} **一个都没有搬过来**。
 *
 * @param seed 随机种子（L7 的落盘点：同种子必须生成同一张图）。★ **入参的 seed 本身**被记录 —— GSimulator 写进 contour 的是 {@code
 *     rng.nextLong()} 派生值（`MapGenerator.java:147`），入参反而丢失，那是"不可复现"的根因
 * @param mapRadius 六边形网格半径（GSimulator 的 {@code TerrainCanvas.DEFAULT_MAP_RADIUS} / {@code
 *     MapConfig} 默认 80）
 * @param baseSeaLevel 海平面基准（GSimulator 的 {@code landRatio} 改名而来，见类注释）
 * @param mainRidges 主脊线条数，**合法区间 [1, 2]**（GSimulator 在此处静默夹取，本类型改为抛）
 * @param fragments 碎片预算总数（次级脊线与碎片条数从这里切，见 {@link FragmentParams}）
 * @param bands 噪声场
 * @param ridges 脊线形状与"脊线 → 海拔"的衰减/谷地参数
 * @param fragmentParams 碎片形状与预算切分规则
 * @param contourCacheMax 六边形采样缓存上限（GSimulator 的 {@code ContourQueryEngine.MAX_CACHE} / {@code
 *     MapConfig} 默认 5000）
 */
public record GenerationSpec(
    long seed,
    int mapRadius,
    double baseSeaLevel,
    int mainRidges,
    int fragments,
    NoiseBands bands,
    RidgeParams ridges,
    FragmentParams fragmentParams,
    int contourCacheMax) {

  public GenerationSpec {
    if (mapRadius < 1) {
      throw new IllegalArgumentException("mapRadius 必须 >= 1: " + mapRadius);
    }
    // ★ 写成 !(a && b) 而不是 (a < 0 || a > 1)：NaN 的任何比较都是 false，于是前者能挡住 NaN、
    //   后者会把 NaN 放行（NaN 海平面让 height < seaLevel 恒为 false ⇒ 整张图静默变成没有海）。
    if (!(baseSeaLevel >= 0.0 && baseSeaLevel <= 1.0)) {
      throw new IllegalArgumentException("baseSeaLevel 必须在 [0, 1]: " + baseSeaLevel);
    }
    // ★ R-48-a：合法区间 [1, 2]。上界与下界都要 —— spec §9.3 要求 mainRidges=5 必须抛，
    //   mainRidgesTwoIsAccepted 反证上界恰为 2（GSimulator 的 Math.min(mainCount, 2) 定的就是这个数）。
    if (mainRidges < 1 || mainRidges > 2) {
      throw new IllegalArgumentException("mainRidges 必须在 [1, 2]: " + mainRidges);
    }
    if (contourCacheMax < 1) {
      throw new IllegalArgumentException("contourCacheMax 必须 >= 1: " + contourCacheMax);
    }
    // ★ 三个子 record 非 null：它们的字段都是基本类型，null 不会在构造期炸，而会在生成时以一个
    //   离现场很远的 NPE 出现 —— 报错的时间点必须落在构造期。
    bands = Objects.requireNonNull(bands, "bands");
    ridges = Objects.requireNonNull(ridges, "ridges");
    fragmentParams = Objects.requireNonNull(fragmentParams, "fragmentParams");
    // ★ 那条可为负的差值（fragmentCount - secondary，GSimulator `MapGenerator.java:105`）在
    //   FragmentParams 里被挡（见其 requireNonNegativeRemaining）；这里只负责让它**在构造期发生**。
    fragmentParams.requireNonNegativeRemaining(fragments);
  }

  /**
   * **唯一一份**默认值。**收种子、不吞种子** —— 只有 {@link #seed()} 随入参变，其余逐组件是规范默认值。
   *
   * <p>数值来源逐条见 {@link NoiseBands}/{@link RidgeParams}/{@link FragmentParams} 的 {@code @param}；
   * 顶层四个标量的来源：{@code mapRadius=80} 与 {@code contourCacheMax=5000} 取 GSimulator {@code
   * MapConfig.DEFAULT}（`MapConfig.java:36`，即旧硬编码常量原值）， {@code mainRidges=2} / {@code fragments=5} 取
   * MCP 工具与 WebUI 的共同默认（`GsimapGenerateTool.java:60-62`）， {@code baseSeaLevel} 由默认 {@code
   * landRatio=0.55} 代进 {@code 0.18 + (1-landRatio)*0.05} 得到。
   *
   * <p>{@code GameMap.empty()} 用 {@code defaults(0L)}（空图没有生成历史，取规范种子 0，见 R-48-e）。
   */
  public static GenerationSpec defaults(long seed) {
    return new GenerationSpec(
        seed,
        80, // MapConfig.DEFAULT.defaultMapRadius（旧 TerrainCanvas.DEFAULT_MAP_RADIUS）
        0.2025, // landRatio 默认 0.55 ⇒ 0.18 + (1-0.55)*0.05
        2, // mainRidges 默认（:60）
        5, // fragments 默认（:62）
        new NoiseBands(
            // 五个频带（每单位半径）：MapGenerator.java:131-135
            1.8,
            3.5,
            8.0,
            20.0,
            3.5,
            // 大陆架整型与权重：ContourQueryEngine.java:147,159
            0.35,
            0.15,
            0.35,
            // 三个频带的相对权重与合成权重：ContourQueryEngine.java:153,159
            0.40,
            0.25,
            0.12,
            0.45,
            // 海岸线振幅、幂次整形、域扭曲：ContourQueryEngine.java:165,161,139-140
            0.35,
            0.92,
            0.018,
            10.0),
        new RidgeParams(
            // 主脊线：MapGenerator.java:63,64,67,68-69,71,75-76,79-80,81
            0.3,
            0.5,
            1.3,
            0.4,
            0.20,
            0.30,
            0.03,
            0.18,
            0.48,
            0.02,
            0.52,
            0.03,
            0.75,
            0.25,
            // 次级脊线：MapGenerator.java:87,88,89,90-92,96-100,101
            0.12,
            0.4,
            0.10,
            0.28,
            0.50,
            0.45,
            0.05,
            0.22,
            0.5,
            0.5,
            0.02,
            0.25,
            0.30,
            // 脊线 → 海拔：ContourQueryEngine.java:159,185,193,203,204
            0.68,
            5.5,
            2.0,
            2,
            0.10,
            0.30),
        new FragmentParams(
            // 形状：MapGenerator.java:108,111,112,114-115,116
            0.35,
            0.50,
            0.04,
            0.08,
            0.5,
            0.5,
            0.10,
            0.15,
            // 预算切分：MapGenerator.java:85 的 Math.max(2, fragmentCount / 2)
            2,
            2),
        5000); // ContourQueryEngine.MAX_CACHE / MapConfig.DEFAULT.contourCacheMax
  }
}
