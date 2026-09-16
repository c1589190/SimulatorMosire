package io.mosire.simos.map.generate;

import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;

/**
 * 从海拔与气候判定地形。**必须覆盖 {@link TerrainCatalog} 的全部 7 项。**
 *
 * <p>★ **高度判定是查表，不是阈值**：按高度落进 {@link TerrainCatalog} 里唯一那条带（带构成 [0,1] 的划分，故落点唯一）。**本类里不许出现任何高度字面量**
 * —— GSimulator 的 L9 正是"词表一份、阈值另一份"长出来的：分类器只产出 6 种， {@code forest}/{@code desert}/{@code tundra}
 * 明明在词表里却永远产生不出来。 本类把"高度 → 带"整段交给词表，7 项因此**每一项都有产出路径**（由 {@code
 * TerrainClassifierTest.everyCatalogKeyIsProducible} 钉住）。
 *
 * <p>★ **落带约定**（带**左闭右开**，最高带上端 1.0 本身不含在内，而本方法必须是总函数）：升序找**第一条**满足 {@code height < maxHeight}
 * 的带；**一条都没有**（{@code height >= 1.0}）⇒ 取**最高带**。域外输入（负值、非有限值）**结构性落带**、**不抛**： 负值落最低带，{@code NaN}
 * 因为任何比较都不成立而落最高带 —— 这里是同一个结构的两端，不是两段特判。全程**零夹取算术**。
 *
 * <p>★ {@code temperature} **目前收下、但不参与判定** —— U1 的 7 项里没有靠温度区分的项。参数是**签名预留**：气候要素（`temperature`
 * 参与的植被带 / 季节细分）属 **M3+**，先把它放进签名，是为了让调用点（{@code MapGenerator}，M2 Task 10）现在就把第二个气候通道接上，将来不必改签名。
 */
public final class TerrainClassifier {

  /**
   * 沙漠带**低湿度门**的阈值：湿度**严格小于**本值才判 {@code desert}，否则退 {@link #DESERT_FALLBACK}。
   *
   * <p>★ 这是本类**唯一的数字**，且不是高度阈值 —— 高度那侧一个数都没有（全部在 {@link TerrainCatalog}）。
   *
   * <p>★ **本值（0.35）为 M2 Task 9 新定，不是从 GSimulator 抄来的阈值**：原项目**有**湿度通道 —— {@code
   * ContourQueryEngine.java:230} 的 {@code noise2(px * 0.02 + 500, py * 0.02 + 500)}，并用过 0.10 / 0.0
   * / 0.15 三个阈值（{@code :242} / {@code :243} / {@code :257}）；但它们服务于被 U1 作废的 9 项词表（hills / plains /
   * swamp），且判在**有符号**噪声域上，与本表的 [0,1] 湿度契约没有对应关系。选它的依据只有一条："沙漠应当只长在 [0,1] 湿度的**低端**（约下 35%）"——0.35
   * 落在低端，又给"中性湿度"（0.5） 留出稳定余量，使中值湿度无论怎么抖动都不判沙漠。
   */
  public static final double DESERT_MAX_HUMIDITY = 0.35;

  /**
   * ★ 沙漠门过不了时的落点：**写死的常量**，不是"相邻低带"那种算法。
   *
   * <p>理由：词表将来插一项（例如"荒漠草原"）就可能改掉"相邻"是谁，而 {@code plains} 不会因此变意思 —— 此处要的是一个常量，不是一条随词表漂移的规则。
   */
  private static final String DESERT_FALLBACK = "plains";

  /** 沙漠的 key —— 全类唯一需要点名的带（其余带按结构性落带处理，不点名）。 */
  private static final String DESERT = "desert";

  private TerrainClassifier() {}

  /**
   * 判定。输入的 humidity 与 temperature 都在 [0,1]（域外值不抛，见类注释的落带约定）。
   *
   * <p>★ 只有 {@code desert} 带带**气候门**（低湿度）：落在 desert 带而湿度不低时**退到 {@link #DESERT_FALLBACK}** ——
   * **总函数**，任何输入都有返回值，且返回值恒在 {@link TerrainCatalog#KEYS} 内。
   *
   * @param height 海拔；落带由 {@link TerrainCatalog} 的带决定，本方法不含任何高度字面量
   * @param humidity 湿度；只被沙漠那道门使用
   * @param temperature 温度；**目前不参与判定**（M3+ 预留，见类注释）
   * @return 命中的地形 key，恒在 {@link TerrainCatalog#KEYS} 内
   */
  public static String classify(double height, double humidity, double temperature) {
    // ★ 落带（唯一一处"高度 → 带"的判定）：升序找**第一条** height < maxHeight 的带。
    //   起始值 = 最高带 —— 它同时就是"全不中"（height >= 1.0 / NaN）时的答案：
    //   "总函数"来自这个结构，而不是某条兜底分支；这里也没有任何终点字面量或夹取。
    String candidate = TerrainCatalog.KEYS.getLast();
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      if (height < t.maxHeight()) {
        candidate = t.key();
        break;
      }
    }

    // ★ 带内的气候门：只有沙漠有。过不了门就退写死的常量（不是"相邻低带"算法）。
    if (DESERT.equals(candidate) && humidity >= DESERT_MAX_HUMIDITY) {
      return DESERT_FALLBACK;
    }
    return candidate;
  }
}
