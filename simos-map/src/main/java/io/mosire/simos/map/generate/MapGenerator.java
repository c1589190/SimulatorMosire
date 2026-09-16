package io.mosire.simos.map.generate;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 从头生成一张地图。**全项目唯一的生成入口**。
 *
 * <p>★ 与 GSimulator 的差别（L7 的病灶逐条）：
 *
 * <ul>
 *   <li>入参 {@code seed} **被记录**：脊线布局的 {@link Random} 与噪声的 {@link SimplexNoise} **都直接用 {@code
 *       spec.seed()}**。GSimulator 把 {@code rng.nextLong()} 那个派生值写进
 *       contour（`MapGenerator.java:147`）， 入参本身反而丢失 —— 拿 seed 复现不出同一张图。
 *   <li>生成**只跑一遍**：GSimulator 的 HTTP 入口在同一请求里算了两遍地形。
 *   <li>产出的 {@link GameMap} **自带 {@code spec}**，复现不需要任何外部输入（"seed 随结果落盘"）。
 *   <li>**只此一条路径**：GSimulator 的 MCP 路径不写 contour，故 MCP 生成的地图不可复现。
 * </ul>
 *
 * <p>★ **能影响结果的每一个输入都在 {@link GenerationSpec} 里** —— 故 {@code generate} **只有一个形参** （由 {@code
 * MapGeneratorTest.generateHasExactlyOneParameter} 与 {@code noSecondPathToGenerate} 反射钉住）。
 * 本类里剩下的常数只有四类、且都不承载可调语义：去相关相位平移、退化线段判据、温度占位值、水体 key（见各常量注释）。
 *
 * <p>★ **湿度不做夹取**：噪声 {@code m} 经 {@code (m + 1) / 2} 落到 [0,1]（实测全域包络 ±0.71、默认采样域 ±0.60）， 越界也**不夹**
 * —— {@link TerrainClassifier} 对域外输入是**总函数**（见其类注释的落带约定），夹取只会多一道 把噪声削平的手脚，且那道手脚正是"静默修正"的一种。
 *
 * <p>★ **高度的构造管线**逐行移植 GSimulator 的 {@code ContourQueryEngine.compute}（`:125-175`）：域扭曲 → 脊线 → 大陆架 →
 * 多频带 → 谷地 → 合成（{@code ridgeH*w + shelf*w + multi*w - valley}）→ {@code Math.max(0, h)} → {@code
 * Math.pow(h, gamma)} → 海岸噪声定海平面 → 判水 → 分类。**三层废弃噪声不移植**（{@code hillsNoise} / {@code plainsNoise} /
 * {@code patch}，`ContourQueryEngine.java:236-254`）：它们服务的是被 U1 作废的 9 项词表， 7
 * 项词表下无消费者；湿度那一行（`:230`）则**必须**移植，否则沙漠门恒不过、图上永远出不了沙漠。
 *
 * <p>★ **U1**：本类不含任何高度带阈值 —— "海拔多高算哪种地形"整段交给 {@link TerrainCatalog}（经 {@link TerrainClassifier}
 * 查表）。本类里与地形名有关的只有一个常量 {@link #OCEAN}（那是 GSimulator 词表里的 {@code "water"} 在 7 项词表下的对应项）。
 *
 * <p>★ {@code spec.contourCacheMax()} **本任务不消费**（没有 contour 引擎可缓存）；留待 M2 关账裁决。
 */
public final class MapGenerator {

  /** 水体地形的 key。GSimulator 写作 {@code "water"}，不在 U1 的 7 项词表内，故按词表取 {@code "ocean"}。 */
  private static final String OCEAN = "ocean";

  /**
   * 温度通道的占位值。M2 **没有**温度通道（GSimulator 也没有），{@link TerrainClassifier#classify} 目前不消费该形参 （Task 9
   * 已裁决）。取中值而不是 0 / 1，是为了将来接上真通道时，"未实现"不会伪装成某个极端气候。
   */
  private static final double NO_TEMPERATURE_CHANNEL = 0.5;

  /**
   * 各噪声带的**去相关相位平移**：同一个坐标上让各带不同相。
   *
   * <p>它们是**任意常数**（改成任何别的值都只是换一张同样合理的图），故不进 {@link NoiseBands} ——
   * 那会让参数面凭空多出几个"看起来可调、实际调不出东西"的旋钮。数值照抄 GSimulator：域扭曲 {@code :140} 的 {@code +70}、三个噪声带 {@code
   * :150-152} 的 {@code +100/+300/+500}、海岸 {@code :164} 的 {@code +77}、湿度 {@code :230} 的 {@code
   * +500}。
   */
  private static final double WARP_PHASE = 70;

  private static final double LOW_PHASE = 100;
  private static final double MID_PHASE = 300;
  private static final double HIGH_PHASE = 500;
  private static final double COAST_PHASE = 77;
  private static final double MOISTURE_PHASE = 500;

  /** 退化线段（两端点几乎重合）的判据：{@code len2} 小于它就按"点到端点"算距离（GSimulator `:215`）。 */
  private static final double DEGENERATE_SEGMENT_LEN2 = 0.001;

  private final GenerationSpec spec;
  private final int radius;
  private final Random rng;
  private final SimplexNoise noise;
  private final List<Ridge> ridges = new ArrayList<>();

  private MapGenerator(GenerationSpec spec) {
    this.spec = spec;
    this.radius = spec.mapRadius();
    // ★ R-10-a：两个随机源都**直接用入参 seed**，不派生 —— 派生值写进落盘数据正是 GSimulator 不可复现的根因。
    this.rng = new Random(spec.seed());
    this.noise = new SimplexNoise(spec.seed());
  }

  /** 由 spec 完全决定。**同 spec 必然同图**（跨进程、跨机器：本方法不读任何环境量）。 */
  public static GameMap generate(GenerationSpec spec) {
    return new MapGenerator(spec).build();
  }

  private GameMap build() {
    placeRidges();

    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    // ★ R-10-h：先按自然序（q 升、r 升）排好再逐格计算、按该序放进 hexes。withinRadius 内部是 Set.copyOf，
    //   迭代序 = 散列槽位序（跨 JVM 的哈希盐不同）⇒ 不排序则落盘序跨进程不稳，字节级往返不成立。
    for (HexCoord coord :
        HexGrid.withinRadius(new HexCoord(0, 0), radius).stream().sorted().toList()) {
      hexes.put(coord, sampleAt(coord));
    }

    // 生成只产出 hexes 与词表：区域/城市/线/组/边是编辑期的东西，生成期一律为空。
    return new GameMap(
        hexes, Map.of(), Map.of(), TerrainCatalog.defaults(), Map.of(), Map.of(), Map.of(), spec);
  }

  // ═══════════════════════════════════════════════════════
  //  脊线摆放（逐行移植 MapGenerator.placeRidges，:56-118）
  // ═══════════════════════════════════════════════════════

  /**
   * ★ **逐次 RNG 抽取的次序与次数照抄** —— 次序变一处就换一张图。三个循环各自的抽取点见下方行内注释。
   *
   * <p>★ **不夹取 {@code mainRidges}**：GSimulator 的 {@code Math.max(1, Math.min(mainCount, 2))}（`:61`）
   * 就是被根除的那族"静默夹取"（传 5 静默变 2），{@link GenerationSpec} 已在构造期把它限在 [1, 2]，越界值到不了这里。
   */
  private void placeRidges() {
    RidgeParams p = spec.ridges();
    FragmentParams f = spec.fragmentParams();
    double mainAngle = rng.nextDouble() * Math.PI; // 主方向 0–180°

    // ── 主山脉（1–2 条，偏离中心，沿主方向伸展）──
    for (int i = 0; i < spec.mainRidges(); i++) {
      // ★ 三元表达式里藏着两签：i == 0 时**一签都不抽**，i == 1 时先 nextDouble 再 nextBoolean。
      double angle =
          mainAngle
              + (i == 0
                  ? 0
                  : (rng.nextDouble() * p.mainCompanionAngleSpan() + p.mainCompanionAngleMin())
                      * (rng.nextBoolean() ? 1 : -1));
      double len = radius * (p.mainLengthMin() + rng.nextDouble() * p.mainLengthSpan());
      double perpAngle = angle + Math.PI / 2;
      double startOff = radius * (p.mainOffsetMin() + rng.nextDouble() * p.mainOffsetSpan());
      double sx =
          Math.cos(perpAngle) * startOff + rng.nextGaussian() * radius * p.mainStartJitter();
      double sy =
          Math.sin(perpAngle) * startOff + rng.nextGaussian() * radius * p.mainStartJitter();
      double curve = rng.nextDouble() * radius * p.mainCurveSpan() * (rng.nextBoolean() ? 1 : -1);

      // ★ 每个点的 x 先于 y 求值（Java 实参从左到右）—— 两个高斯抽签的先后对调就换一张图。
      double tailX =
          sx
              - Math.cos(angle) * len * p.mainTailLength()
              + rng.nextGaussian() * radius * p.mainTailJitter();
      double tailY =
          sy
              - Math.sin(angle) * len * p.mainTailLength()
              + rng.nextGaussian() * radius * p.mainTailJitter();
      double curveX = sx + Math.cos(perpAngle) * curve;
      double curveY = sy + Math.sin(perpAngle) * curve;
      double headX =
          sx
              + Math.cos(angle) * len * p.mainHeadLength()
              + rng.nextGaussian() * radius * p.mainHeadJitter();
      double headY =
          sy
              + Math.sin(angle) * len * p.mainHeadLength()
              + rng.nextGaussian() * radius * p.mainHeadJitter();
      double weight = p.mainWeightMin() + rng.nextDouble() * p.mainWeightSpan();
      ridges.add(
          new Ridge(
              List.of(new Pt(tailX, tailY), new Pt(curveX, curveY), new Pt(headX, headY)), weight));
    }

    // ── 次级山脉（大致平行/斜交主方向）──
    int secondary = f.secondaryCount(spec.fragments());
    for (int i = 0; i < secondary; i++) {
      double offAngle =
          mainAngle
              + (rng.nextDouble() * p.secondaryAngleSpan() + p.secondaryAngleMin())
                  * (rng.nextBoolean() ? 1 : -1);
      double perpDist =
          radius
              * (p.secondaryOffsetMin() + rng.nextDouble() * p.secondaryOffsetSpan())
              * (rng.nextBoolean() ? 1 : -1);
      double len = radius * (p.secondaryLengthMin() + rng.nextDouble() * p.secondaryLengthSpan());
      // ★ 起点的两轴各抽一签（sx 的签先于 sy 的签），且抽取落在乘式**内部** —— 与 GSimulator 的写法逐字一致。
      double sx =
          Math.cos(mainAngle)
                  * radius
                  * (p.secondaryAlongMin() + rng.nextDouble() * p.secondaryAlongSpan())
              + Math.cos(mainAngle + Math.PI / 2) * perpDist;
      double sy =
          Math.sin(mainAngle)
                  * radius
                  * (p.secondaryAlongMin() + rng.nextDouble() * p.secondaryAlongSpan())
              + Math.sin(mainAngle + Math.PI / 2) * perpDist;
      double tailX =
          sx
              - Math.cos(offAngle) * len * p.secondaryTailLength()
              + rng.nextGaussian() * radius * p.secondaryJitter();
      double tailY =
          sy
              - Math.sin(offAngle) * len * p.secondaryTailLength()
              + rng.nextGaussian() * radius * p.secondaryJitter();
      double headX =
          sx
              + Math.cos(offAngle) * len * p.secondaryHeadLength()
              + rng.nextGaussian() * radius * p.secondaryJitter();
      double headY =
          sy
              + Math.sin(offAngle) * len * p.secondaryHeadLength()
              + rng.nextGaussian() * radius * p.secondaryJitter();
      double weight = p.secondaryWeightMin() + rng.nextDouble() * p.secondaryWeightSpan();
      ridges.add(new Ridge(List.of(new Pt(tailX, tailY), new Pt(headX, headY)), weight));
    }

    // ── 碎片（随机位置，低权重）──
    int frags = f.remainingCount(spec.fragments());
    for (int i = 0; i < frags; i++) {
      double angle = rng.nextDouble() * 2 * Math.PI;
      double dist = radius * (f.distMin() + rng.nextDouble() * f.distSpan());
      double cx = Math.cos(angle) * dist;
      double cy = Math.sin(angle) * dist;
      double flen = radius * (f.lenMin() + rng.nextDouble() * f.lenSpan());
      double fangle = angle + rng.nextGaussian() * f.angleJitter();
      double weight = f.weightMin() + rng.nextDouble() * f.weightSpan();
      ridges.add(
          new Ridge(
              List.of(
                  new Pt(
                      cx - Math.cos(fangle) * flen * f.tipLength(),
                      cy - Math.sin(fangle) * flen * f.tipLength()),
                  new Pt(
                      cx + Math.cos(fangle) * flen * f.tipLength(),
                      cy + Math.sin(fangle) * flen * f.tipLength())),
              weight));
    }
  }

  // ═══════════════════════════════════════════════════════
  //  高度管线（逐行移植 ContourQueryEngine.compute，:125-175）
  // ═══════════════════════════════════════════════════════

  /** 一个格的完整采样：先算高度、再判水、最后（只在陆地上）判地形。 */
  private HexCell sampleAt(HexCoord coord) {
    double px = coord.q() + coord.r() * 0.5;
    double py = coord.r() * 0.8660254;

    NoiseBands bands = spec.bands();
    // ★ 频率**先除好再用**（GSimulator `MapGenerator.java:131-135` 就是先除）：写成 `wpx * 1.8 / radius`
    //   会多一次舍入。"六个频带"里只有这五个海拔带是"每单位半径"，气候带是绝对频率（见下）。
    double shelfFreq = bands.shelfFreq() / radius;
    double lowFreq = bands.lowFreq() / radius;
    double midFreq = bands.midFreq() / radius;
    double highFreq = bands.highFreq() / radius;
    double coastFreq = bands.coastFreq() / radius;

    // 域扭曲
    double wpx =
        px + noise.noise2(px * bands.warpFreq(), py * bands.warpFreq()) * bands.warpAmplitude();
    double wpy =
        py
            + noise.noise2(px * bands.warpFreq() + WARP_PHASE, py * bands.warpFreq() + WARP_PHASE)
                * bands.warpAmplitude();

    double ridgeH = ridgeHeightAt(wpx, wpy);

    double shelf = noise.noise2(wpx * shelfFreq, wpy * shelfFreq);
    shelf = Math.max(0, shelf * bands.shelfScale() + bands.shelfOffset());

    double n1 = noise.noise2(wpx * lowFreq + LOW_PHASE, wpy * lowFreq + LOW_PHASE);
    double n2 = noise.noise2(wpx * midFreq + MID_PHASE, wpy * midFreq + MID_PHASE);
    double n3 = noise.noise2(wpx * highFreq + HIGH_PHASE, wpy * highFreq + HIGH_PHASE);
    double multi = n1 * bands.lowWeight() + n2 * bands.midWeight() + n3 * bands.highWeight();

    double valley = valleyPenaltyAt(wpx, wpy);

    double height =
        ridgeH * spec.ridges().heightWeight()
            + shelf * bands.shelfHeightWeight()
            + multi * bands.multiHeightWeight()
            - valley;
    // ★ 顺序不能换：截到非负**在**幂次整形**之前**（负数取 0.92 次幂是 NaN）。
    height = Math.max(0, height);
    height = Math.pow(height, bands.gamma());

    double coastNoise = noise.noise2(wpx * coastFreq + COAST_PHASE, wpy * coastFreq + COAST_PHASE);
    double seaLevel = spec.baseSeaLevel() + coastNoise * bands.coastAmplitude();

    // ★ R-10-f：判水在分类**之前**（GSimulator `:167`）。水下的高度**原样记**（不记 0）—— 它是同一根管线的产物。
    if (height < seaLevel) {
      return new HexCell(OCEAN, height);
    }

    // ★ R-10-b：湿度用**未扭曲**的 px/py（GSimulator 传给 classify 的就是 `:135-136` 那对），
    //   且频率是**绝对值**（`px * 0.02`，不除 radius）。少了这一行，沙漠门恒不过 ⇒ 词表里的沙漠永远是谎话。
    double moisture =
        noise.noise2(
            px * bands.moistureFreq() + MOISTURE_PHASE, py * bands.moistureFreq() + MOISTURE_PHASE);
    return new HexCell(
        TerrainClassifier.classify(height, (moisture + 1) / 2, NO_TEMPERATURE_CHANNEL), height);
  }

  /** 到最近脊线的指数衰减（GSimulator `computeRidgeHeight`，`:181-190`）。 */
  private double ridgeHeightAt(double px, double py) {
    RidgeParams p = spec.ridges();
    double best = 0;
    for (Ridge ridge : ridges) {
      double d = distToRidge(px, py, ridge.points());
      double k = p.decayBase() + ridge.weight() * p.decayPerWeight();
      double h = Math.exp(-d * k / radius);
      if (h > best) {
        best = h;
      }
    }
    return best;
  }

  /** 两条最近脊线之间的高斯谷地惩罚（GSimulator `computeValleyPenalty`，`:192-205`）。 */
  private double valleyPenaltyAt(double px, double py) {
    RidgeParams p = spec.ridges();
    if (ridges.size() < p.valleyMinRidges()) {
      return 0;
    }
    double d1 = Double.MAX_VALUE;
    double d2 = Double.MAX_VALUE;
    for (Ridge ridge : ridges) {
      double d = distToRidge(px, py, ridge.points());
      if (d < d1) {
        d2 = d1;
        d1 = d;
      } else if (d < d2) {
        d2 = d;
      }
    }
    double sigma = radius * p.valleySigma();
    return Math.exp(-d1 * d1 / (2 * sigma * sigma))
        * Math.exp(-d2 * d2 / (2 * sigma * sigma))
        * p.valleyWeight();
  }

  /** 点到折线的最短距离（GSimulator `distToRidge`，`:207-227`）。 */
  private double distToRidge(double px, double py, List<Pt> pts) {
    double minD = Double.MAX_VALUE;
    for (int i = 0; i < pts.size() - 1; i++) {
      Pt a = pts.get(i);
      Pt b = pts.get(i + 1);
      double dx = b.x() - a.x();
      double dy = b.y() - a.y();
      double len2 = dx * dx + dy * dy;
      double d;
      if (len2 < DEGENERATE_SEGMENT_LEN2) {
        d = Math.sqrt((px - a.x()) * (px - a.x()) + (py - a.y()) * (py - a.y()));
      } else {
        double t = Math.max(0, Math.min(1, ((px - a.x()) * dx + (py - a.y()) * dy) / len2));
        double cx = a.x() + t * dx;
        double cy = a.y() + t * dy;
        d = Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
      }
      if (d < minD) {
        minD = d;
      }
    }
    return minD;
  }

  /** 一条脊线的折线（2~3 个控制点）与权重。 */
  private record Ridge(List<Pt> points, double weight) {
    Ridge {
      // ★ 冻在赋值处：SpotBugs 只认它看得见的那一步（GameMap 的类注释记着这条 —— 藏进 helper 会照报 EI_EXPOSE_REP）。
      points = List.copyOf(points);
    }
  }

  /** 折线上的一个控制点。 */
  private record Pt(double x, double y) {}
}
