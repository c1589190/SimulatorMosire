### Task 2: `terrain` 包 —— 唯一词表

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/terrain/TerrainCatalogTest.java`

**这个任务解的是 L9**（至少 9 份地形词表副本，两份在同一批 key 上完全分叉）。

> **★ 词表已由用户裁决 U1 重定**（详见 spec §〇.0 与 §6.1）：**7 项，按高度从小到大**。
> GSimulator 那 9 项（`water/lowland/plains/hills/mountain/forest/swamp/desert/tundra`）**整个作废**，
> **不是改名**。本节下面的内容已按 U1 重写，**不要再去 GSimulator 抄那份 9 项表**。

- [ ] **Step 1: 写 `TerrainType`**

```java
package io.mosire.simos.map.terrain;

/**
 * 地形类型。**高度带是类型自己的属性** —— 用户裁决 U1 要求"按高度从小到大"。
 *
 * <p>★ 带进词表、**不进分类器的代码**：这样判据可断言（带连续、不重叠、覆盖 [0,1]），
 * 分类器退化成一个查表，**不再是第二个藏着阈值的词表**（GSimulator 的 L9 正是那么来的）。
 *
 * <p>★ 逐格的**海拔值**仍在 {@code HexCell}；本类型携带的是它的**带**。
 */
public record TerrainType(
    String key,
    String name,
    String color,
    double minHeight,
    double maxHeight,
    int food,
    int gold,
    int stone,
    int moveCost,
    String description) {

  public TerrainType {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key 不得为空白");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不得为空白");
    if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB 形式: " + color);
    }
    if (!(minHeight >= 0.0 && minHeight < maxHeight && maxHeight <= 1.0)) {
      throw new IllegalArgumentException("高度带非法: [" + minHeight + ", " + maxHeight + "]");
    }
    if (moveCost < 1) throw new IllegalArgumentException("moveCost 必须 >= 1: " + moveCost);
    if (food < 0 || gold < 0 || stone < 0) throw new IllegalArgumentException("产出不得为负");
  }
}
```

★ **10 个字段**（数一遍：`key`/`name`/`color`/`minHeight`/`maxHeight`/`food`/`gold`/`stone`/`moveCost`/`description`）。
**高度带的左闭右开**（`minHeight <= h < maxHeight`）—— 这样相邻带天然不重叠，无需特判边界。

- [ ] **Step 2: 写 `TerrainCatalog`（★ 唯一词表）**

**7 项 key 固定为**（U1 的序即**高度升序**，**这个顺序是语义序，落盘就用它**）：

| # | 名称 | key | 特性（★ 控制器补裁，用户未给数值，**需过目**） |
|---|---|---|---|
| 1 | 海洋 | `ocean` | 最低；不可通行；无产出 |
| 2 | 平原 | `plains` | 产能最高、最好走 |
| 3 | 沙漠 | `desert` | **★ 额外的低湿度门**；贫瘠、难走 |
| 4 | 低矮丘陵 | `low_hills` | 产量中等、略难走；矿藏起点 |
| 5 | 山地 | `mountains` | 石/矿富集、很难走 |
| 6 | 平缓高原 | `plateau` | **高但平坦** —— 海拔高却相对好走 |
| 7 | 高原山地 | `plateau_mountains` | 最高；几乎不可通行 |

★ **7 行的具体数值（颜色 / 产出 / moveCost / 高度带边界）执行期由实现者定，本计划故意不给** ——
控制器**没有实测过**它们，写进来就是编造（总纲 §六 的硬门）。
**但两条硬约束是给定的**：① 高度带必须**连续、不重叠、覆盖 `[0,1]`**；
② `moveCost` 的**相对大小必须与上表"特性"栏一致**（海洋最难走、平原最好走）。
**数值必须在报告里明写为"本任务新定，非来自 GSimulator"** —— 与从 GSimulator 抄来的值分开标。

```java
package io.mosire.simos.map.terrain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ **全模块唯一的地形词表。**
 *
 * <p>GSimulator 有至少 9 份互不相同的副本，其中两份在同一批 key 上完全分叉
 * （{@code plains} 在 A 里是"平原"/绿/3,1，在 B 里是"山区"/土黄/2,2），
 * 且 {@code ContourQueryEngine.terrainColor} 的兜底色正是 A 的平原绿 —— 串味铁证。
 */
public final class TerrainCatalog {

  /** 7 项的 key，**顺序 = 高度升序 = 落盘顺序**（U1）。 */
  public static final java.util.List<String> KEYS =
      java.util.List.of(
          "ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");

  /**
   * ★ 唯一的默认词表。**迭代序 = 高度升序**。
   *
   * <p>★ **保序**：GSimulator 的 {@code Map.copyOf} 会打乱迭代序，使同一份表在两个存档里顺序不同，
   * 字节级往返因此不成立。此处用 {@link LinkedHashMap} 且**不 copyOf**。
   */
  public static Map<String, TerrainType> defaults() {
    Map<String, TerrainType> m = new LinkedHashMap<>();
    // ← 7 行在此；高度带必须连续、不重叠、覆盖 [0,1]（Step 4 有用例钉死）
    return java.util.Collections.unmodifiableMap(m);
  }

  /** 按 key 取；不存在即抛，**不兜底**（兜底正是 GSimulator 串味的来源）。 */
  public static TerrainType of(String key) {
    TerrainType t = defaults().get(key);
    if (t == null) throw new IllegalArgumentException("未知地形类型: " + key);
    return t;
  }
}
```

- [ ] **Step 3: ★ 调查 GSimulator 的旧词表（**是为了记录被丢弃的东西，不是为了抄**）**

**不要从 GSimulator 抄任何地形数值** —— U1 已把那份 9 项表整个作废。但**要留下记录**，
因为 **M6 的老存档导入器**得知道旧 key 要映射到什么：

```bash
cd ~/DevMosire/GSimulator
git grep -n "defaultTerrainTypes" -- '*.java'      # 词表 B 的定义处与全部副本
git grep -n "TerrainType.defaults" -- '*.java'     # 词表 A 的定义处
git grep -c "#6CC261" -- .                          # 串味的兜底色（A 的平原绿）有几个副本
```

**在报告里给一张映射表**：旧 key（9 项）→ 新 key（7 项）或"无对应"。
★ **这张表是给 M6 用的**，不是本任务的实现输入。**明写它属于 M6 的输入**，别让它看着像 M2 的需求。
（已知的难点：旧表的 `forest`/`swamp`/`lowland`/`tundra` 在新表的 7 项里**没有显然的对应物** ——
**如实写"无直接对应，待 M6 裁决"，不要替 M6 编一个映射**。控制器已知旧表的 `plains` 叫"山区"，是命名事故。）

- [ ] **Step 4: 写用例**

```
TerrainCatalogTest
  - catalogHasExactlySevenKeys            : KEYS.size() == 7
  - defaultsKeySetEqualsKeys              : defaults().keySet() 与 KEYS **顺序**一致   ← 钉保序
  - defaultsIterationOrderIsStable        : 连调两次 defaults()，key 序逐项相同
  - defaultsIsUnmodifiable                : put → UnsupportedOperationException
  - ofThrowsOnUnknownKey                  : of("nope") → IllegalArgumentException，消息含 "未知地形类型"
  - ofNeverFallsBack                      : ★ 断言 of() 里**没有** default 分支 —— 用变异证明（Step 5）
  - everyTypeHasDistinctNameAndColor      : 7 项 name 两两不同、color 两两不同
  - everyColorMatchesHexPattern           : 7 项全过 #RRGGBB
  - everyTypeIsConstructible              : 7 项都能构造（构造期校验不误伤）
  - ★ heightBandsAreContiguousAndCoverUnitInterval
                                          : 按 minHeight 升序排开，首带 minHeight == 0.0、
                                            末带 maxHeight == 1.0、且**相邻处**上一个 maxHeight
                                            == 下一个 minHeight（浮点直接 ==，见下）
  - ★ keysAreInAscendingHeightOrder       : ★ KEYS 的下标序 == 按 minHeight 升序排出的序
  - ★ moveCostOrderMatchesCharacteristics : plains 严格最小；ocean ≥ plateau_mountains；
                                            plateau < mountains
  - plainsIsPlainsNotMountains            : ★ plains 的 name **不含**"山"            ← 钉住命名事故
  - plainsGreenIsNotTheOldFallback        : ★ 任何一项的 color 都 != "#6CC261"       ← 见下
```

★ **`heightBandsAreContiguousAndCoverUnitInterval` 为什么用浮点 `==` 而不是容差**：带边界是
**同一批字面量**（上一个的 `maxHeight` 与下一个的 `minHeight` 写的是同一个数），不是两次数值计算的结果。
用容差会让"差 0.001 的缝"变成绿 —— 而那正是这个用例要抓的东西。**"容差"在这里是判别力的敌人**。

★ **`keysAreInAscendingHeightOrder` 的用意**：Step 2 的表把"顺序 = 高度升序"写成了**注释里的承诺**。
注释不算护栏。这条用例把它变成**可红的断言** —— 否则将来有人往中间插一项、注释还写着"升序"。

★ **`plainsGreenIsNotTheOldFallback` 的用意**：`#6CC261` 是**词表 A** 的平原绿，
它出现在 `ContourQueryEngine.terrainColor` 的 `default` 分支里 —— **跨词表串味的物证**。
新表**不得**再出现这个值。★ 注意它现在**不是**在钉"plains 的颜色"，而是在钉
"**这个已知污染值不许在任何一项上复活**" —— 一个**排除用例**（见 §9.3 的排除集合那类）。

★ **`moveCostOrderMatchesCharacteristics`** 把 Step 2 表"特性"栏里那句相对大小写成断言。
**具体断言给定如下**（这是控制器的裁定，别自己改）：
`plains` 严格小于其余六项；`ocean ≥ plateau_mountains`（"不可通行"不弱于"几乎不可通行"）；
`plateau < mountains`（"高但平坦、相对好走"）。**其余两两之间不设断言** —— 计划没给依据的，
不许编成断言。

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `of()` 加一个 `return defaults().get("plains")` 兜底 | **红** | `ofNeverFallsBack` 不是装饰 |
| `defaults()` 改用 `Map.copyOf` | **红** | `defaultsIterationOrderIsStable` / `defaultsKeySetEqualsKeys` 真的钉住了保序 |
| 把 `plains` 的 name 改回"山区" | **红** | `plainsIsPlainsNotMountains` 有判别力 |
| 删掉一项（6 项） | **红** | `catalogHasExactlySevenKeys` 有判别力 |
| ★ 把某一带的 `maxHeight` 缩小 0.01（造出一条缝） | **红** | `heightBandsAreContiguousAndCoverUnitInterval` 抓得住缝，且**证明它没用容差** |
| ★ 交换 `KEYS` 里 `plateau` 与 `plateau_mountains` | **红** | `keysAreInAscendingHeightOrder` 有判别力 |
| ★ 把 `plains` 的 `moveCost` 改成全表最大 | **红** | `moveCostOrderMatchesCharacteristics` 有判别力 |
| ★ 把某项的 `color` 改成 `#6CC261` | **红** | `plainsGreenIsNotTheOldFallback` 是排除用例、不是空转 |
| ★ 把某带的 `minHeight` 设成等于它的 `maxHeight` | **红** | **`TerrainType` 的构造器校验**有判别力（构造期护栏也要自证） |

★ 每个变异体**都要先自证**：编一份原件作参照、比 `md5`，**确认变异产物 ≠ 原件**再看测试结果。
否则"三向全绿"可能只是"变异根本没写进磁盘"。**这条自身也要有痕迹**（把两份 md5 贴进报告）。

- [ ] **Step 6: 跑门禁并提交**

同 Task 1 的形制，路径换成 `terrain/`，提交信息
`feat(map): terrain 包——唯一地形词表（7 项，高度升序，保序）`。

---

