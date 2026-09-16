package io.mosire.simos.map.generate;

/**
 * 噪声场的**形状参数**：六个频带（五个海拔带 + 一个气候带）的频率、各带合成海拔时的相对幅度与整形系数。
 *
 * <p>★ **U1（本类型为什么不是"第二份高度带"）**：本类型的每一个字段都是**造海拔/造气候的过程参数** ——
 * 频率决定噪声在**空间**上变化多快（每单位半径多少个起伏），权重/系数决定各带对海拔的**相对贡献**， {@code gamma} 只做整体幂次整形，{@code
 * warpFreq/warpAmplitude} 决定采样坐标被扰动得多厉害。 <b>本类型里没有任何"海拔多高算山"之类的分界</b>：它不知道任何地形名，字段里也出现不了 {@link
 * io.mosire.simos.map.terrain.TerrainCatalog} 的 key —— 本类型**连一个 String 组件都没有**， 而"某个高度算哪种地形"的唯一持有者是
 * {@code TerrainCatalog}（{@code TerrainType.minHeight/maxHeight}）。 这一条由 {@code
 * GenerationSpecTest.noTerrainHeightThresholds} **结构性**钉住（不依赖对字段语义的判断）。
 *
 * <p>★ **例外只有一个、且不破 U1**：{@link #moistureFreq} 塑造的是**湿度场**而不是高度，它同样是一个"场长得多快"的频率 —— 湿度的**判定**（沙漠那道
 * 0.35 的低湿度门）在 {@link TerrainClassifier} 里，本类型只是把噪声的尺度交出去。
 *
 * <p>★ **为什么海拔频率是"每单位半径"而不是绝对频率**：GSimulator 把那五个频率都写成 {@code 1.8 / radius} 这类**商**（{@code
 * MapGenerator.java:131-135}），真正可调的只有分子。 存分子、由 {@link MapGenerator}（M2 Task 10）按 {@code mapRadius}
 * 除掉 —— 于是"改半径" 不会连带改掉地形的手感，两个参数各自独立。 <b>唯一例外是气候带的 {@link #moistureFreq}</b>：GSimulator 原式是 {@code
 * px * 0.02}（`ContourQueryEngine.java:230`），**不除 radius** —— 湿度场的粗细与地图半径无关，是一条**绝对频率**。
 *
 * <p>★ 少数**有意不进字段**的数字：{@code ContourQueryEngine.java:150-152} 里的 {@code +100 / +300 / +500} 与
 * {@code :164} 的 {@code +77}。它们是给各带**去相关**用的相位平移 —— 任意常数，改成任何别的值都只是换一张同样合理的图，不承载语义。把它们做成字段会让参数面
 * 凭空多出四个"看起来可调、实际调不出东西"的旋钮。
 *
 * <p>★ 构造期只守**频率**一条（有限正数），见 {@link #requirePositiveFrequency}。其余字段是开放区间
 * 的幅度/权重，取负只是"另一种分布"，不属于"静默失效"，故**不设守卫**（不替不存在的世界写代码）。
 *
 * @param shelfFreq 大陆架噪声频率，**每单位半径**（GSimulator {@code MapGenerator.java:131} 的 {@code 1.8 /
 *     radius}）
 * @param lowFreq 低频带频率，每单位半径（`MapGenerator.java:132` 的 {@code 3.5 / radius}）
 * @param midFreq 中频带频率，每单位半径（`MapGenerator.java:133` 的 {@code 8.0 / radius}）
 * @param highFreq 高频带频率，每单位半径（`MapGenerator.java:134` 的 {@code 20.0 / radius}）
 * @param coastFreq 海岸线噪声频率，每单位半径（`MapGenerator.java:135` 的 {@code 3.5 / radius}）
 * @param moistureFreq 气候带（湿度）的噪声频率，**绝对频率、不除半径**（`ContourQueryEngine.java:230` 的 {@code px *
 *     0.02}；该行同时给采样坐标加了一个任意相位 {@code +500}）—— 六个频带里唯一的例外，见类注释
 * @param shelfScale 大陆架的放大系数（`ContourQueryEngine.java:147` 的 {@code shelf * 0.35}）
 * @param shelfOffset 大陆架的基线抬升（`ContourQueryEngine.java:147` 的 {@code + 0.15}）
 * @param shelfHeightWeight 大陆架对海拔的贡献权重（`ContourQueryEngine.java:159` 的 {@code shelf * 0.35}）
 * @param lowWeight 低频带对合成噪声的权重（`ContourQueryEngine.java:153` 的 {@code n1 * 0.40}）
 * @param midWeight 中频带对合成噪声的权重（`ContourQueryEngine.java:153` 的 {@code n2 * 0.25}）
 * @param highWeight 高频带对合成噪声的权重（`ContourQueryEngine.java:153` 的 {@code n3 * 0.12}）
 * @param multiHeightWeight 合成噪声对海拔的贡献权重（`ContourQueryEngine.java:159` 的 {@code multi * 0.45}）
 * @param coastAmplitude 海岸线噪声的振幅（`ContourQueryEngine.java:165` 的 {@code coastNoise * 0.35}）
 * @param gamma 海拔的幂次整形的指数（`ContourQueryEngine.java:161` 的 {@code Math.pow(height, 0.92)}）
 * @param warpFreq 域扭曲的采样频率（`ContourQueryEngine.java:139-140` 的 {@code px * 0.018}）
 * @param warpAmplitude 域扭曲的位移幅度（`ContourQueryEngine.java:139-140` 的 {@code * 10}）
 */
public record NoiseBands(
    double shelfFreq,
    double lowFreq,
    double midFreq,
    double highFreq,
    double coastFreq,
    double moistureFreq,
    double shelfScale,
    double shelfOffset,
    double shelfHeightWeight,
    double lowWeight,
    double midWeight,
    double highWeight,
    double multiHeightWeight,
    double coastAmplitude,
    double gamma,
    double warpFreq,
    double warpAmplitude) {

  public NoiseBands {
    shelfFreq = requirePositiveFrequency("shelfFreq", shelfFreq);
    lowFreq = requirePositiveFrequency("lowFreq", lowFreq);
    midFreq = requirePositiveFrequency("midFreq", midFreq);
    highFreq = requirePositiveFrequency("highFreq", highFreq);
    coastFreq = requirePositiveFrequency("coastFreq", coastFreq);
    // ★ 气候带这条守卫的靶子与海拔带略不同：取 0 时湿度场退化成**常数** ⇒ 沙漠门要么恒开要么恒关，
    //   "干旱/湿润"这层地貌从此不存在（图上要么全是沙漠环、要么永远没有沙漠），且不抛任何异常。
    moistureFreq = requirePositiveFrequency("moistureFreq", moistureFreq);
    warpFreq = requirePositiveFrequency("warpFreq", warpFreq);
  }

  /**
   * ★ **唯一的守卫**（频率面）：必须有限且为正，构造期抛、**不静默夹取**。
   *
   * <p>为什么这一条值得守：频率取 0 时该带在整张图上**退化成同一个常数**（{@code noise2(px * 0, py * 0)}）， 于是这一层尺度**静默消失** ——
   * 图还是生成得出来，只是某一层地貌从此不存在，不抛任何异常。 这正是本任务要根除的那一族病（GSimulator 的 {@code Math.max(1,
   * Math.min(mainCount, 2))} 同族）。
   *
   * <p>{@code !(value > 0.0)} 这个写法**顺带挡住 NaN**（NaN 的任何比较都是 false）， {@code Double.isFinite} 挡住
   * {@code +Infinity}（它 {@code > 0.0} 为真）。
   */
  private static double requirePositiveFrequency(String name, double value) {
    if (!(value > 0.0) || !Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " 必须是有限正数: " + value);
    }
    return value;
  }
}
