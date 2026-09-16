### Task 1: `hex` 包 —— 坐标、方向、网格

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexDirection.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexCoordTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexDirectionTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexGridTest.java`

**这个任务解的是 L3 与 L6**（8 份方向表 / 坐标表述不一致）。根因（侦察 A 实测）是
`TerrainGeometry.DIRS` 与 `MapService.HEX_DIRS` **都是 package-private、都不导出公共 API**
⇒ 消费方只能复制，**无编译期一致性约束**。本任务把它变成一个 `public` 枚举。

- [ ] **Step 1: 写 `HexCoord`**

```java
package io.mosire.simos.map.hex;

/**
 * 轴向坐标（axial）。**六边形格的唯一身份**。
 *
 * <p>cube 第三轴恒由 {@code s = -q - r} 导出，不存储。
 */
public record HexCoord(int q, int r) implements Comparable<HexCoord> {

  /** cube 第三轴。恒等式 {@code q + r + s == 0}。 */
  public int s() {
    return -q - r;
  }

  /**
   * 到另一格的六边形距离（cube 曼哈顿距离的一半）。
   *
   * <p>GSimulator 有 4 份代数恒等的实现（Java 3 + JS 1），此处合并为唯一一份。
   */
  public int distanceTo(HexCoord other) {
    return (Math.abs(q - other.q) + Math.abs(r - other.r) + Math.abs(s() - other.s())) / 2;
  }

  /** 沿一个方向走一步。 */
  public HexCoord neighbor(HexDirection d) {
    return new HexCoord(q + d.dq(), r + d.dr());
  }

  /** 六条邻格，**枚举序**（即边序号序）。 */
  public java.util.List<HexCoord> neighbors() {
    return HexDirection.ALL.stream().map(this::neighbor).toList();
  }

  @Override
  public int compareTo(HexCoord o) {
    int c = Integer.compare(q, o.q);
    return c != 0 ? c : Integer.compare(r, o.r);
  }

  /** 规范字符串形式，**只在 JSON 边界使用**（身份是类型，不是这个串）。 */
  @Override
  public String toString() {
    return q + "_" + r;
  }

  /** 解析 {@link #toString()} 的产物。 */
  public static HexCoord parse(String text) {
    int i = text.indexOf('_');
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法坐标串: " + text);
    }
    return new HexCoord(Integer.parseInt(text.substring(0, i)), Integer.parseInt(text.substring(i + 1)));
  }
}
```

- [ ] **Step 2: 写 `HexDirection`（★ 全模块唯一的方向表）**

**索引序固定为 A 序（顺时针）：`E, SE, SW, W, NW, NE`。**
依据（spec §3.2）：A 序是**位掩码兼容序** —— GSimulator 的 `riverMask` 明写
`bits 0-5 for edges E,SE,SW,W,NW,NE`，前端 `DIR_VECTORS` 也是 A 序，且该链自洽。
**不要选 B 序**（`E,NE,NW,W,SW,SE`）—— 选它会连位序带前端一起推翻，收益为零。

```java
package io.mosire.simos.map.hex;

import java.util.List;

/**
 * 六条边的方向。**索引即边序号，全模块唯一。**
 *
 * <p>序为顺时针 {@code E, SE, SW, W, NW, NE}，与 GSimulator 的 {@code riverMask} 位序一致。
 * <p>GSimulator 全仓有 8 份方向表（6 Java + 2 JS），归为两种互逆索引序，
 * 根因是两张主表都是 package-private、不导出 API，消费方只能复制。此处合并为唯一一份。
 */
public enum HexDirection {
  E(1, 0),
  SE(0, 1),
  SW(-1, 1),
  W(-1, 0),
  NW(0, -1),
  NE(1, -1);

  private final int dq;
  private final int dr;

  HexDirection(int dq, int dr) {
    this.dq = dq;
    this.dr = dr;
  }

  public int dq() {
    return dq;
  }

  public int dr() {
    return dr;
  }

  /** 反向边。由枚举序保证（恰隔 3 项），不是巧合。 */
  public HexDirection opposite() {
    return ALL.get((ordinal() + 3) % 6);
  }

  /** 顺时针下一方向。 */
  public HexDirection next() {
    return ALL.get((ordinal() + 1) % 6);
  }

  /** 逆时针下一方向。 */
  public HexDirection prev() {
    return ALL.get((ordinal() + 5) % 6);
  }

  /** 按枚举序的全部方向。 */
  public static final List<HexDirection> ALL = List.of(values());
}
```

- [ ] **Step 3: 写 `HexGrid`**

```java
package io.mosire.simos.map.hex;

import java.util.Map;
import java.util.Set;

/**
 * 六边形格容器。格的身份是 {@link HexCoord}，不是字符串键。
 *
 * <p>★ 没有 {@code gridSize} 字段：GSimulator 那个字段只做构造期范围校验、不参与取格，
 * 且到处写死 30 而真实生成半径是 80 —— 范围由本类的 {@code minQ()/maxQ()/minR()/maxR()} 导出。
 */
public final class HexGrid {
  private final Map<HexCoord, ?> cells; // 具体化见 Task 5（HexCell 由 map 包提供）
  // …Task 5 落地后本类改为持有 Map<HexCoord, HexCell>；本任务先只做坐标运算部分
}
```

★ **本任务只交付坐标运算**。`HexGrid` 的**内容类型**是 `HexCell`，而 `HexCell` 在 Task 5 ——
⇒ 本任务先建 `HexGrid` 的**纯几何部分**（范围导出、邻域遍历、半径内枚举），
**不引入 `HexCell`**；Task 5 再补内容访问（`cell(HexCoord)` / `with(...)`）。

- [ ] **Step 4: 写用例**

```
HexCoordTest
  - toStringAndParseRoundTrip          : 冻结样例 (0,0) (0,-1) (-3,7) → toString → parse → equals
  - parseRejectsMalformed             : "" / "_" / "1_" / "_2" / "a_b" → IllegalArgumentException
  - sAxisInvariant                    : 遍历 q,r ∈ [-20,20]，断言 q + r + s() == 0
  - distanceIsSymmetricAndZeroOnSelf  : ∀ a,b : d(a,b) == d(b,a) 且 d(a,a) == 0
  - distanceMatchesCubeFormula        : 与独立写的 cube 公式逐点对拍（消除同源错误）
  - distanceOnKnownPairs              : 手算种子：d((0,0),(1,0))==1, d((0,0),(2,-1))==2, d((0,0),(-3,3))==3
  - neighborIsInvolutive              : ∀ d : a.neighbor(d).neighbor(d.opposite()).equals(a)
  - neighborsAreSixDistinctAtDistance1: 6 个邻格互不相同，且到中心距离恒为 1
  - compareToIsTotalOrder             : 先 q 后 r；与 (q,r) 字典序一致

HexDirectionTest
  - oppositeIsInvolution              : ∀ d : d.opposite().opposite() == d
  - oppositeIsNotSelf                 : ∀ d : d.opposite() != d      ← 钉住"隔 3 项"不是"隔 0 项"
  - nextAndPrevAreInverse             : ∀ d : d.next().prev() == d
  - allSixDistinctAndCoversEnum       : ALL.size()==6 且 equals values()
  - offsetsMatchFrozenTable           : ★ 冻结 6 行 (E,1,0) (SE,0,1) (SW,-1,1) (W,-1,0) (NW,0,-1) (NE,1,-1)
                                         —— 这一条是"单一方向表"的**唯一真值锚**，改序必红
  - neighborOffsetsAreAllDistinct     : 6 个 (dq,dr) 互不相同
  - everyNeighborIsAtDistanceOne      : ∀ d : new HexCoord(0,0).neighbor(d).distanceTo(new HexCoord(0,0)) == 1

HexGridTest
  - boundsDerivedFromCells            : 空集合的 min/max 抛 IllegalStateException；
                                        给 {(0,0),(3,-2),(-1,5)} → minQ=-1 maxQ=3 minR=-2 maxR=5
  - cellsWithinRadiusIsClosedBall     : radius=0 → 1 格；radius=1 → 7 格；radius=2 → 19 格
  - cellsWithinRadiusAllInRange       : ∀ c : c.distanceTo(center) <= radius
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `opposite()` 的 `+3` 改成 `+0` | **红** | `oppositeIsNotSelf` 不是装饰 |
| `HEX_DIRS` 冻结表里把 `SE` 与 `SW` 的偏移对调 | **红** | `offsetsMatchFrozenTable` 真的钉住了序 |
| `distanceTo` 的 `/ 2` 删掉 | **红** | 距离公式的 `/2` 不是装饰 |
| `neighbors()` 改成返回 5 个 | **红** | `neighborsAreSixDistinctAtDistance1` 有判别力 |

**变异体自证**：每条变异**读完测试结果之前**先比 `md5(变异产物) ≠ md5(原件参照)`。

- [ ] **Step 6: 跑门禁并提交**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
./mvnw -q -pl simos-map spotless:apply
git add simos-map/src/main/java/io/mosire/simos/map/hex/ simos-map/src/test/java/io/mosire/simos/map/hex/
git diff --cached          # 先扫
git commit -m "feat(map): hex 包——HexCoord 身份、唯一方向表、网格几何"
```

---

