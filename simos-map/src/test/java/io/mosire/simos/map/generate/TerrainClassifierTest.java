package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * spec §6.2（L9 后半 / U1）：分类器必须覆盖词表的全部 7 项，且**高度判定只是查表** —— 不许自带第二套阈值。
 *
 * <p>★ 本类的采样点**一律由被检验的那条带自己算出**（{@code minHeight()} / 带中点 / {@code Math.nextDown(maxHeight())}），
 * **一个高度字面量都不抄** —— 抄进来的话，"分类器里藏着自己一套数"的实现下断言会全等，用例就白写了。
 */
class TerrainClassifierTest {

  /**
   * 中性湿度：**不低于** {@link TerrainClassifier#DESERT_MAX_HUMIDITY}（由 {@link
   * #humidityFixturesStraddleTheDesertThreshold} 钉住），故中性湿度下沙漠带过不了门。
   */
  private static final double NEUTRAL_HUMIDITY = 0.5;

  /** 低湿度：**严格低于** {@link TerrainClassifier#DESERT_MAX_HUMIDITY}（同上），故低湿度下沙漠带判沙漠。 */
  private static final double LOW_HUMIDITY = 0.10;

  /** 温度本任务不参与判定（签名预留）；取值只为让三参数都**非退化**，不承载期望。 */
  private static final double NEUTRAL_TEMPERATURE = 0.5;

  /**
   * ★ 核心断言：词表 7 项**每一项都产得出** —— 直接钉住"词表不是谎话"。
   *
   * <p>GSimulator 的 L9：{@code ContourQueryEngine.classify} 只产出 6 种，{@code forest}/{@code
   * desert}/{@code tundra} 在词表里却永远产生不出来。本用例的存在就是为了让那种状态**不可能再悄悄回来**：删掉任何一项的产出路径，这里立即红。
   *
   * <p>证据点取**该项自己带的中点**（由带算出，不抄字面量）；湿度按各带自己的门（只有 desert 需要低湿度，见 {@link
   * #humidityFixturesStraddleTheDesertThreshold}）。
   */
  @Test
  void everyCatalogKeyIsProducible() {
    assertThat(TerrainCatalog.KEYS).as("词表必须是 7 项").hasSize(7);
    for (String key : TerrainCatalog.KEYS) {
      TerrainType t = TerrainCatalog.of(key);
      double midHeight = (t.minHeight() + t.maxHeight()) / 2.0;
      double humidity = key.equals("desert") ? LOW_HUMIDITY : NEUTRAL_HUMIDITY;
      assertThat(TerrainClassifier.classify(midHeight, humidity, NEUTRAL_TEMPERATURE))
          .as("地形 %s 必须产得出（其带 [%s, %s) 的中点）", key, t.minHeight(), t.maxHeight())
          .isEqualTo(key);
    }
  }

  /**
   * ★ **本任务判别力最强的一条**：分类器**跟着词表的带走**。
   *
   * <p>采样点是 {@code t.minHeight()}、带中点、{@code Math.nextDown(t.maxHeight())} —— 全部**由 t 自己的带算出**。
   * {@code nextDown} 那一点钉**左闭右开**：{@code maxHeight} 本身属于**下一条**带，只有它前一个 double 才归本条带。
   *
   * <p>判别力所在：分类器里但凡藏着自己的一套高度阈值（GSimulator 的 L9 就是这么长出来的），词表一带挪动，断言值就会与之分叉 ——
   * 而用例的期望值永远是**词表的**那一份，于是红。
   *
   * <p>沙漠带按 §6.2 的门单独取期望：中性湿度过不了门 ⇒ {@code plains}（这样"带定位"与"气候门"在同一组采样点上都被覆盖， 沙漠的产出性由 {@link
   * #everyCatalogKeyIsProducible}、门的另一半由 {@link #desertBandFallsBackToPlainsWhenHumid} 负责）。
   */
  @Test
  void classifierFollowsCatalogBands() {
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      double midHeight = (t.minHeight() + t.maxHeight()) / 2.0;
      String expected = t.key().equals("desert") ? "plains" : t.key();
      for (double height : List.of(t.minHeight(), midHeight, Math.nextDown(t.maxHeight()))) {
        assertThat(TerrainClassifier.classify(height, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
            .as("高度 %s 落在 %s 的带 [%s, %s) 内（中性湿度）", height, t.key(), t.minHeight(), t.maxHeight())
            .isEqualTo(expected);
      }
    }
  }

  /**
   * ★ 沙漠那道气候门：**同高度**（沙漠带内、由带算出），**干燥 → 沙漠**、**湿润（中性）→ 平原**。
   *
   * <p>退回的是**写死的 {@code plains}**、不是"相邻低带"—— 用例给出的期望正是这条常量口径。
   *
   * <p>末两条钉门的**临界点**：门是 {@code humidity >= DESERT_MAX_HUMIDITY}（闭在阈值上），阈值前一个 double 仍判沙漠。
   * 这是两种口径会**分叉**的地方，不钉的话"&gt;="与"&gt;"两种实现下断言全等价。
   */
  @Test
  void desertBandFallsBackToPlainsWhenHumid() {
    TerrainType desert = TerrainCatalog.of("desert");
    double midHeight = (desert.minHeight() + desert.maxHeight()) / 2.0;

    assertThat(TerrainClassifier.classify(midHeight, LOW_HUMIDITY, NEUTRAL_TEMPERATURE))
        .as("沙漠带 + 低湿度 ⇒ desert")
        .isEqualTo("desert");
    assertThat(TerrainClassifier.classify(midHeight, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
        .as("沙漠带 + 中性湿度 ⇒ 退到 plains（既不是 desert，也不是别的）")
        .isEqualTo("plains");
    assertThat(
            TerrainClassifier.classify(
                midHeight,
                Math.nextDown(TerrainClassifier.DESERT_MAX_HUMIDITY),
                NEUTRAL_TEMPERATURE))
        .as("阈值前一个 double 仍过得了门")
        .isEqualTo("desert");
    assertThat(
            TerrainClassifier.classify(
                midHeight, TerrainClassifier.DESERT_MAX_HUMIDITY, NEUTRAL_TEMPERATURE))
        .as("阈值本身已过不了门（门闭在阈值上）")
        .isEqualTo("plains");
  }

  /**
   * ★ 整套用例的湿度夹具与分类器阈值**必须夹住**：低湿度采样 &lt; 阈值 ≤ 中性湿度采样。
   *
   * <p>若阈值被调走（例如挪到 0.05 以下），本用例先红并指明是哪一半失配，而不是让 {@link #everyCatalogKeyIsProducible} 的 desert 项
   * 给出自相矛盾的失败（"用过不了门的湿度去要 desert"）。夹具与实现的一致性因此是**被验过的**，不是碰巧的。
   */
  @Test
  void humidityFixturesStraddleTheDesertThreshold() {
    assertThat(LOW_HUMIDITY).as("低湿度采样必须过得了沙漠门").isLessThan(TerrainClassifier.DESERT_MAX_HUMIDITY);
    assertThat(NEUTRAL_HUMIDITY)
        .as("中性湿度采样必须过不了沙漠门")
        .isGreaterThanOrEqualTo(TerrainClassifier.DESERT_MAX_HUMIDITY);
  }

  /**
   * ★ "不兜底"：扫 [0,1]³ 网格（步长 0.1、含两端，共 1331 点），返回值**恒在 {@link TerrainCatalog#KEYS} 内**。
   *
   * <p>域**外**输入（负值 / &gt;1 / 非有限值）归 {@link #classifyIsTotal} —— 本用例只扫**合法输入域**，两边的红点因此不重叠。
   */
  @Test
  void classifyNeverReturnsUnknownKey() {
    for (int hi = 0; hi <= 10; hi++) {
      for (int ci = 0; ci <= 10; ci++) {
        for (int ti = 0; ti <= 10; ti++) {
          double height = hi / 10.0;
          double humidity = ci / 10.0;
          double temperature = ti / 10.0;
          assertThat(TerrainClassifier.classify(height, humidity, temperature))
              .as("classify(%s, %s, %s) 的返回值", height, humidity, temperature)
              .isIn(TerrainCatalog.KEYS);
        }
      }
    }
  }

  /**
   * 最低带 → {@code ocean}，**且与湿度温度无关** —— 气候门只该长在 desert 一处。
   *
   * <p>采样点由 ocean 自己的带算出；湿度/温度取 0 / 0.5 / 1 全组合（含两端的极值，门的任何形态都会在某一格上露出来）。
   */
  @Test
  void oceanIsLowestBand() {
    TerrainType ocean = TerrainCatalog.of("ocean");
    double midHeight = (ocean.minHeight() + ocean.maxHeight()) / 2.0;
    for (double humidity : List.of(0.0, 0.5, 1.0)) {
      for (double temperature : List.of(0.0, 0.5, 1.0)) {
        assertThat(TerrainClassifier.classify(midHeight, humidity, temperature))
            .as("湿度 %s、温度 %s 下最低带仍是 ocean", humidity, temperature)
            .isEqualTo("ocean");
      }
    }
  }

  /** 最高带的输入 → {@code plateau_mountains}。{@code 1.0} 这个域外端点由 {@link #classifyIsTotal} 单独钉。 */
  @Test
  void plateauMountainsIsHighestBand() {
    TerrainType top = TerrainCatalog.of("plateau_mountains");
    // 带上端用 nextDown 收进来：maxHeight 本身属于"下一条带"，而它是最后一条 ⇒ 属于域外（见 classifyIsTotal）。
    for (double height :
        List.of(
            top.minHeight(),
            (top.minHeight() + top.maxHeight()) / 2.0,
            Math.nextDown(top.maxHeight()))) {
      assertThat(TerrainClassifier.classify(height, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
          .as("高度 %s 落在最高带内", height)
          .isEqualTo("plateau_mountains");
    }
  }

  /**
   * 同输入两次同输出 —— 纯函数；也顺带钉住实现里没有取随机数 / 读外部状态之类的隐藏输入。
   *
   * <p>采样覆盖：三条不同带（含沙漠门的两种湿度）、两端（{@code 0.0} 与域外的 {@code 1.0}）、以及气候取极值的一种组合。
   */
  @Test
  void classifyIsDeterministic() {
    for (double[] triple :
        List.of(
            new double[] {0.0, 0.5, 0.5},
            new double[] {0.15, 0.5, 0.5},
            new double[] {0.50, LOW_HUMIDITY, 0.5},
            new double[] {0.50, NEUTRAL_HUMIDITY, 0.5},
            new double[] {0.95, 0.0, 1.0},
            new double[] {1.00, 0.5, 0.5})) {
      assertThat(TerrainClassifier.classify(triple[0], triple[1], triple[2]))
          .as("classify(%s, %s, %s) 两次调用必须同值", triple[0], triple[1], triple[2])
          .isEqualTo(TerrainClassifier.classify(triple[0], triple[1], triple[2]));
    }
  }

  /**
   * ★ **总函数**：全域有定义、**不抛**。三组物证：
   *
   * <p>① {@code height == 1.0} —— 带是**左闭右开**，最高带上端 1.0 本身不含在内，而分类器必须对它有定义 ⇒ **结构性落最高带** （R-9a
   * 裁定：一条带都没中 ⇒ 退末带，不是兜底常量）。
   *
   * <p>② 负值 → **结构性落最低带**，不抛、不夹取。
   *
   * <p>③ 更极端的域外输入（±∞、NaN、±大数）同样不抛且返回值仍在 KEYS 内 —— 落带结构对它们一视同仁。
   */
  @Test
  void classifyIsTotal() {
    assertThat(TerrainClassifier.classify(1.0, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
        .as("1.0 落在最高带右端之外，必须结构性落到最高带")
        .isEqualTo(TerrainCatalog.KEYS.getLast());
    assertThat(TerrainClassifier.classify(-0.5, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
        .as("负值必须结构性落到最低带")
        .isEqualTo(TerrainCatalog.KEYS.getFirst());

    for (double height :
        List.of(
            -1e9, -0.0, 1.5, 1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertThatCode(
              () -> TerrainClassifier.classify(height, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
          .as("域外输入 %s 不得抛", height)
          .doesNotThrowAnyException();
      assertThat(TerrainClassifier.classify(height, NEUTRAL_HUMIDITY, NEUTRAL_TEMPERATURE))
          .as("域外输入 %s 的返回值仍须在词表内", height)
          .isIn(TerrainCatalog.KEYS);
    }
  }
}
