# T11 预期在先（写于 e2e 首跑之前）

> 纪律（形态 5）：**先把要断言什么、预期实测值多少写死在这里，再跑**。
> 实测与预期不符 ⇒ 报告里记「不符」，**不许回头改这份文件**。
> 本文件的"已实测"标注只指向**探针**（起 `ShellMain --demo` 直接打 API 的那一轮，见 §一），
> 与 e2e 是**两件装置**：探针证的是后端语义，e2e 要证的是 UI 真的发出/不发出命令。

## 一 装置事实（探针 5831 实测，作预期的地基）

| 事实 | 实测值 |
|---|---|
| demo 世界格数 | **3** —— `(1,1)` `(1,2)` `(1,3)`（`DemoWorld.corridorMap()`） |
| demo 世界的边 | **0 条**（`GameMap` 第 8 参 `Map.of()`） |
| 可能存在的边 | 恰两条：`E1=1_1|1_2`、`E2=1_2|1_3`（`EdgeRef` 规范序：按 `(q,r)`，不是字符串序） |
| regions | `[]`（无区域） |
| terrainTypes | 只有 `desert`；块数 1 |
| `/api/map/overview` 含 revision？ | **不含** ⇒ 两次响应原文**逐字节比较**是合法断言（不掺 meta） |
| 载荷缺 `mode` | `Rejected`：`字段 mode 必须是字符串: …` |

## 二 随机化指纹（`seed` 是 `(排序选区, seed)` 的纯函数，与 base 地形无关）

| seed | 预期 block 指纹 | 状态 |
|---|---|---|
| 7 | 选区 3 格 ⇒ `desert×2` + `plains×1` | 探针已实测 |
| 99 | 3 个单格块 | 探针已实测 |
| 0 | 与 99 同形 | 探针已实测 |

★ **不钉直方图**（T6 教训：`seed=1` 与 `seed=5` 直方图完全相同，只有块数分得开）⇒ 一律钉
**`/api/map/overview` 响应原文逐字节**。

e2e 断言与预期：

| # | 断言 | 预期 |
|---|---|---|
| r1 | `seed=7` 第一次 ⇒ 发**恰 1 条** `map.RandomizeRegion`，载荷 `seed === 7`（**原样送达**） | 1 条 |
| r2 | 再 `seed=7` ⇒ 发 1 条；两次 overview 原文**逐字节相同** | true |
| r3 | `seed=99` ⇒ 发 1 条；原文与 r2 **不同** | true |
| r4 | 回 `seed=7` ⇒ 发 1 条；原文与 r2 **逐字节相同**（历史无关） | true |

## 三 SetEdge 的 merge / replace（判别力：既有 tag 必须与命令**同 kind**）

命令序列与预期（每一步都读 `/api/map/hex`）：

| 步 | 命令 | 预期 `(1,1)` 的 `edges` | 预期 `(1,2)` 的 `edges` |
|---|---|---|---|
| s1 | `merge river E1` | `[{edge:"1_1|1_2", pathways:["river"]}]` | `[{edge:"1_1|1_2",pathways:["river"]}]`（入射边） |
| s2 | `merge road E1` | ★ `pathways:["river","road"]`（**river 仍在**） | 同左 |
| s3 | `replace river E2` | `pathways:["river","road"]`（**别的 kind 一字不动**） | `1_1|1_2:[river,road]` + `1_2|1_3:[river]` |

★ **s2 是判别力点**：若前端静默把 `merge` 当 `replace`（m1 或 T5 的 m2 形态），s2 之后
`(1,1)` 只剩 `["road"]` ⇒ 断言红。
★ 预期 `pathways` 内**字典序**（`river` < `road`），`edges` 数组按 `EdgeRef` 自然序。

## 四 UI 结构预期（工具选择器）

| # | 断言 | 预期 |
|---|---|---|
| u1 | 地图编辑模式有 `#map-edit-tools`，四档 `terrain`/`river`/`road`/`randomize` | 4 个 radio |
| u2 | 默认工具 = `terrain`；`#edge-controls` 与 `#randomize-controls` 均隐藏 | true |
| u3 | 切到 `river` ⇒ `#edge-controls` 可见、`#randomize-controls` 隐藏 | true |
| u4 | ★ `#edge-mode` 初值 = **空串**（**无预选**） | `""` |
| u5 | 切到 `randomize` ⇒ `#randomize-controls` 可见、`#edge-controls` 隐藏；`#randomize-seed` 初值空 | true |
| u6 | 两个"归 T11"置灰按钮不复存在；全文不含 `data-pending` | true |

## 五 五条核心断言：预期结果与预期红点

| 断言 | 干净轮预期 | 预期被哪个变异杀 |
|---|---|---|
| `e1-unselected-mode-zero-write`：未选 mode + 右键拖一条边 ⇒ 非 GET 数**不变**，且页面出现**可见提示** | PASS（0 写） | **m1** |
| `e2-merge-exactly-one-post`：选 merge + 右键拖 ⇒ **恰 1 条** `map.SetEdge`，载荷 `mode==="merge"` | PASS | m1 |
| `r1`/`r2`/`r3`/`r4`（见 §二） | PASS | **m2** |
| `x1-nonadjacent-zero-write`：从 `(1,1)` 跳到 `(1,3)`（中间**不经过** `(1,2)`）⇒ **0 写** | PASS | **m3** |
| `g2-brush-not-stolen`：地形工具下右键拖 ⇒ **恰 1 条** `map.SetTerrain`，且 `#edge-controls` 隐藏 | PASS | m3（分派写错时） |

★ `x1` 的机理：`(1,1)`→`(1,2)`→`(1,3)` **三点共线**，Playwright 直线拖动必然路过 `(1,2)` ⇒
必须**绕开**（从 `(1,1)` 先移到图外的远点，再移到 `(1,3)`）才能造出"非相邻跳"。
★ `x1` 的护栏是**前端的**：后端 `EdgeOperations` **不校验相邻性**（只校验两端点在图上），
所以没有这条护栏，非相邻的"边"会**静默进世界**——这是 m3 的价值所在（预期，待 m3 轮实测证）。

## 六 五模式左键 0 写（回归，M8-R 硬约束）

| # | 断言 | 预期 |
|---|---|---|
| w1 | 五个模式下各左键拖一次 ⇒ 每次非 GET 增量为 **0** | 5×0 |

## 七 形态 5 的自证条款

1. 若 `r3` 在 `seed=7` vs `seed=99` 上恰好同形 ⇒ 选值不判别，**当场换值并在报告里记"换过"**，
   不许把"恰好同形"当通过。
2. 若 m1/m2/m3 存活 ⇒ **如实记存活**，不许改断言救它。
3. 每轮变异的"红"必须落在**本条断言的原文**上；`rc=2`（装置崩溃）**不算红**，如实记账并修装置。

## 八 未能预期的部分（先声明，免得事后当"已预期"）

- 演示世界只有 3 格 ⇒ **"多格拖刷"最多 2 格**，比 T8 真档的 5 格小；不声称多格规模。
- 没有真档 ⇒ **不对真档上的 `SetEdge`/`RandomizeRegion` 作任何断言**（T5/T6/T7/T8 的同口径遗留仍在）。
- 触摸 / 触控笔 / HiDPI（dpr>1）未在计划内。
