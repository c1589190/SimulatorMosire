### Task 14: L1~L9 逐条守卫

**Files:**
- Create: `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`（或按缺陷分文件）

**这是 M2 判据的第一条**（"GSimulator 的 L1~L9 逐条有对应用例"）。
**逐个缺陷点名**，每条一行注释指向它的来源，**让"哪条守哪条"是可读的**。

- [ ] **Step 1: 写 L1~L9 的守卫（每条一个 `@Test`，命名带 `L1`~`L9`）**

| 缺陷 | 守卫用例 | 落在哪个已完成的类上 |
|---|---|---|
| **L1** 子节点写 `edges` 静默丢失 | `L1_edgesSurviveRoundTripOnNonRoot` | `MapChangeSet`（Task 6/7） |
| **L2** 双份连通性存储 | `L2_hexCellHasNoConnectivityField` | `HexCell`（Task 5） |
| **L3** 方向数组错位 | `L3_thereIsExactlyOneDirectionTable` | `HexDirection`（Task 1） |
| **L4** 三个 region 概念 | `L4_thereIsExactlyOneRegionType` | `region` 包（Task 3） |
| **L5** Province 归属线性扫描 | `L5_regionOfIsIndexedNotScanned` | `RegionIndex`（Task 3/13） |
| **L6** 坐标表述不一致 | `L6_hexCoordIsTheOnlyCoordinateType` | `HexCoord`（Task 1） |
| **L7** 无海拔无种子落盘 | `L7_heightAndSeedSurvivePersistence` | `HexCell` / `GenerationSpec`（Task 5/8/10） |
| **L8** 12 参数构造复制 | `L8_gameMapHasNoTwelveArgConstructor` | `GameMap`（Task 5） |
| **L9** 地形词表分裂 | `L9_thereIsExactlyOneTerrainCatalog` | `TerrainCatalog`（Task 2/9） |

★ **L1 必须包含一条非 root 的用例** —— GSimulator 只在**非 root** 丢数据
（root 走全量保存，**root 用例会掩盖它**）。

- [ ] **Step 2: 写用例（要点）**

```java
/** L3：全仓只有一张方向表。 */
@Test
void L3_thereIsExactlyOneDirectionTable() {
  // 反射断言 HexDirection 是 enum 且恰好 6 项
  // ★ 并断言不存在第二个 int[][] 方向常量 —— 用源码扫描：
  //   git grep -n "int\[\]\[\]" -- 'simos-map/src/main' 的命中集必须**不含**方向表形态
}

/** L6：HexCoord 是唯一坐标类型。 */
@Test
void L6_hexCoordIsTheOnlyCoordinateType() {
  // 源码断言："q_r" 这个拼接形式**只在 HexCoord.toString/parse 与 change 包的 keyOf/parseKey 出现**
  // 其余任何地方出现 ⇒ 红
}
```

★ **L3/L6 这类"全仓只有一份"的断言必须靠源码扫描**（`git grep`）而不是反射 ——
反射只能证明"当前这个类长这样"，证明不了"没有第二份"。
**源码扫描的用例要写在测试里**（读 `src/main` 目录树），**不要只写成报告里的一句话** ——
报告会失传，测试不会。

- [ ] **Step 3: ★ 护栏自证（G13）**

**逐条变异，每条证明它对应的 L 守卫会响**（9 条）：

| 变异 | 期望红的是 |
|---|---|
| 在 `MapChangeSet` 里把 `edges` 比较掉 | `L1_…` |
| 给 `HexCell` 加 `edgeTags` | `L2_…` |
| 在 `simos-map` 里加一个 `static final int[][] DIRS` | `L3_…` |
| 加第二个 region 类型 | `L4_…` |
| `RegionIndex.regionOf` 改线性 | `L5_…` |
| 某处手写 `q + "_" + r` | `L6_…` |
| 结果不带 `spec` | `L7_…` |
| 给 `GameMap` 加一个 12 参数的构造器 | `L8_…` |
| 加第二份地形表 | `L9_…` |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `test(map): L1~L9 逐条守卫 + 逐条自证`。

---

