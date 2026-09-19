# T11 预期在先 —— **修正记录**（不得回改原件）

原文件：`t11-expectations.md`（**一字未动**，它是我跑之前写下的预测）。
本文件是**追加的修正件**：某条预测被实测推翻后，把"原预测 / 实测 / 为什么错 / 修正后的断言 /
**再跑之前**写下的新预测"记在一起。★ 顺序是"先写修正、再重跑"，不是事后补记结果。

---

## 一 `s3` —— 原预测被实测推翻（干净轮 rc=1 的唯一红点）

**断言名**：`s3-replace-leaves-only-the-new-kind`（原名）
**实测（干净轮 5821，逐字）**：

```
STEP s3-replace-leaves-only-the-new-kind: FAIL {"posts":1,
 "payload":{"kind":"river","edges":["1_2|1_3"],"mode":"replace"},
 "hex12Edges":[{"edge":"1_2|1_3","pathways":["river","road"]}],
 "hex11Edges":[]}
```

命令**发出去且被接受**了（1 条 POST、mode 原样 = replace），但 `hex(1,2).pathways` 实测
`["river","road"]`，而我预测的是 `["road"]`。

**为什么原预测错（我的推理错误，不是代码错）**：`replace river` 的语义是"**先摘掉全图的 river，
再按 payload 加 river**"。我把 E2 放进了 payload 的边集里 ⇒ 它的 river 被摘掉之后**又被加回来**
⇒ 结果当然是 `["river","road"]`。要测"整份覆盖把 payload 之外的边也摘掉"，**命令的边集里必须不含
那条边**——我把这件事漏了。

**修正依据（跑之前从实现读出来的、不是从结果倒推的）**：`simos-map/.../ops/EdgeOperations.java`
的类注释原文：

> `replace` 覆盖**整张图**上该 kind 的标注：payload 之外的边同样会被摘掉该 kind（那正是"整份覆盖"），
> 该 kind 之外的标注（如 road 之于 river 的 replace）**一字不动**。

**修正后的断言（`s3-replace-strips-that-kind-across-the-map`）**——改用 **E1 = `1_1|1_2`**，
即一条**不在**既有 river 标注里、且**不在** E2 上的边：

| 项 | 跑之前写下的预测 |
|---|---|
| POST 数 | 1 |
| payload | `{kind:"river", edges:["1_1|1_2"], mode:"replace"}` |
| `hex(1,2)` 实测 | `["1_2|1_3[road]"]` ★ river **消失**、road **仍在** |
| `hex(1,1)` 实测 | `["1_1|1_2[river]"]` ★ 新边落在 payload 指定的位置上 |
| 状态行 | 含 `已改 river 1 条边（replace` |

**E1 怎么拖到**：`(1,1)` 的**格心**会被 `pickAt` 判成 `kind:"unit"`（demo 单位 u-1 在那里），
所以只能用**偏心取样**：取 `(1,1)` 内、离格心 25 世界单位的点（单位命中半径 12.24 ⇒ 让开；
六边形内切半径 29.4 ⇒ 仍在格内）。这条路已由 `x1a-jump-start-is-hex-1-1-and-detour-is-offmap`
实测证过（`jumpPick={kind:"hex",q:1,r:1,inMap:true}`）。

**修正后比原来强在哪（判别力）**：原断言在 **merge 实现下也会绿**（`merge river [E2]` 的产物同样是
`river+road`）——它根本没测到 `replace` 与 `merge` 的分叉点。修正后的断言在 merge 实现下**必红**
（merge 不会摘掉 E2 的 river ⇒ `hex(1,2)` 会是 `["river","road"]`）。这正合 §5 形态 3
"输入必须落在两种实现会分叉的地方"。

---

## 一之二 `s3`（修正后的版本）—— **同一条断言第二次红：又是我的预期错，代码是对的**

**实测（干净轮 5821 第二次，逐字）**：

```
STEP s3-replace-strips-that-kind-across-the-map: FAIL {"posts":1,
 "payload":{"kind":"river","edges":["1_1|1_2"],"mode":"replace"},
 "hex12Edges":[{"edge":"1_1|1_2","pathways":["river"]},{"edge":"1_2|1_3","pathways":["road"]}],
 "hex11Edges":[{"edge":"1_1|1_2","pathways":["river"]}],
 "hex12Norm":"[\"1_1|1_2[river]\",\"1_2|1_3[road]\"]",
 "hex11Norm":"[\"1_1|1_2[river]\"]",
 "status":"已改 river 1 条边（replace，一条命令，head 已前进）"}
```

**要说清代码对在哪**：这一次**语义全都兑现了**——
`hex(1,2)` 上 E2 那条边的 pathways 实测 `["road"]` ⇒ **river 被摘掉了**（我修正件里预测的正是这一条，
它绿）；`hex(1,1)` 实测 `["1_1|1_2[river]"]`（与我预测**逐字相同**，它也绿）。
红的是**我对 `hex(1,2)` 整份清单的预测少了一项**：我把 E1 写成了"只属于 (1,1)/(1,2) 边界"，
**忘了 `1_1|1_2` 同时接在 `(1,2)` 上** ⇒ `hex(1,2)` 的边清单本来就有两条。

**教训（记进报告）**：`/api/map/hex` 的 `edges` 是**该格的全部关联边**（两端都列）。
我第一次测 E2 时就见过这件事（`hex(1,2)` 列了 `1_2|1_3`），却在预测 E1 时没把它推广到另一端。
**同一族错误在一轮里犯了两次 ⇒ 这类"清单里到底有几项"的预期，应该先问"这条边接在几个格上"。**

**第二次修正后的断言（`s3-replace-strips-that-kind-across-the-map`）——跑之前写下的预测**：

| 项 | 跑之前写下的预测 |
|---|---|
| POST 数 | 1 |
| payload | `{kind:"river", edges:["1_1|1_2"], mode:"replace"}` |
| `hex(1,2)` 关联边（全清单，排序后） | `["1_1|1_2[river]","1_2|1_3[road]"]` |
| `hex(1,2)` 上 **E2** 那条边的 pathways | `["road"]` ★ **river 被整份覆盖摘掉**（判别点） |
| `hex(1,1)` 关联边（全清单） | `["1_1|1_2[river]"]` |
| 状态行 | 含 `已改 river 1 条边（replace` |

★ 并**把判别点单独拆成一条**（不再只藏在整清单比对里）：`pathwaysOf(hex12,"1_2|1_3") === ["road"]`——
`merge` 实现下这里会是 `["river","road"]` ⇒ **必红**。

---

## 二 其余偏差（全部是**实测**，如实记，不回改原件）

1. **拖动主体 E1 → E2（原计划）**：原预期 §三 把边钉在 `E1 = 1_1|1_2` 上，理由是"相邻两格"。
   实测 demo 单位 `u-1` 坐 `(1,1)`，其格心 `pickAt` 返回 `kind:"unit"` ⇒ `edgeAt`/`paintAt` 都跳过
   ⇒ **常规拖动只能碰到 `(1,2)`/`(1,3)`**。故 `e2`/`s2` 用 `E2`（保持"相邻、可判别"的结构），
   `s3` 用偏心取样回到 `E1`（见上）。★ 这是**装置的约束**，不是被测行为的偏差。
2. **选区只能是 2 格**：预期 §二 的随机化指纹按 3 格选区（`(1,1)(1,2)(1,3)`）预测；同一原因，
   UI 只能圈到 `(1,2)(1,3)`。故指纹按 2 格重测（`logs/t11-probe.json` 的 `randomizeTwoHex`）
   ⇒ **seed 7 的 overview 与"未随机化的世界"逐字节相同**（`3f5a712a…`，均 603B）。
   ⇒ `r2`（同 seed 两次相同）**判别力弱**，真正的强断言是 `r4`（99 之后再回到 7 必须复现 r1 的字节）。
3. **`m1` 的杀点必须用"相邻"的一拖**：若 `e1` 用非相邻的一拖，`m1`（UI 静默兜 replace）会**同时**被
   相邻性护栏挡住 ⇒ 存活。故 `e1` 固定拖 `(1,2)→(1,3)`（相邻）⇒ m1 一旦兜默认值必然发出 1 条命令。

---

## 三 `m6`（`randomizeSelectionState` 的空选区守卫）—— **补的一条 STEP：跑之前写下的预测**

**缘起**：报告 §八.7 自陈 `randomizeSelectionState` 是四个纯函数里**唯一没有变异体**的
（它当时的"自证"只有纯函数测试 #9，那是**实现即断言**、不是故意违规）。按判据 7 + 裁定 42，
这条要么当场补杀，要么就是装饰。**但现有 e2e 里没有任何一条断言会因为这个守卫被删而变红**：
`r0c` 测的是**空 seed**（走的是 seed 守卫），而走"空选区"那条路需要一条**新 STEP**。
⇒ 按 §5 形态 5（预期在先、实测在后），**先写预测，再写代码，最后才跑**。

**新 STEP**：`r0a-empty-selection-zero-write-with-warning`，插在 `r0` 之后、`r0b`（右键圈选）之前。
★ 关键设计（不去实测就定下来的）：**必须填一个合法的 seed**（`"7"`）。理由是读实现读出来的——
`submitRandomize` 里 **选区守卫在 seed 守卫之前**（`map.js:2623~2635`，两次 Read 复核）：

```
2623    var selState = randomizeSelectionState(host.randomizeSelection);
2624    if (!selState.ok) { … 选区为空 … return null; }     ← 守卫一（m6 的锚点）
2629    var seedNode = app.byId("randomize-seed");
2630    var seedState = parseSeedInput(seedNode ? seedNode.value : "");
2631    if (!seedState.ok) { … seed 非法 … return null; }    ← 守卫二
```

若 seed 留空，**守卫二会先开枪**，删掉守卫一的变异体照样 0 写 ⇒ 断言变成装饰。填合法 seed 之后，
"0 写"这件事才**只能**由守卫一负责 —— 这就是 m6 的杀点在"两种实现会分叉的地方"（§5 形态 3）。

**跑之前写下的预测（逐字，含从源码抄下来的文案）**：

| 项 | 预测值 | 依据 |
|---|---|---|
| `values.emptySelection.posts` | **0** | 守卫一 `return null` ⇒ 不发 `writeCommand` |
| `values.emptySelection.selection` | **`[]`** | `host.randomizeSelection` 初值 `[]`（`map.js:1931`），本 STEP 之前无右键圈选 |
| `values.emptySelection.warning` | **`选区为空：先在图上右键拖动圈选，**没有发出任何写命令**。`** | `map.js:2625` 字面量；`setWarning` 用 `textContent`（`map.js:2483`）⇒ `**` **不被渲染**，**原样留着** |
| `values.emptySelection.status` | **`选区为空 ⇒ 未发出任何写命令。`** | `map.js:2626` 字面量 |
| `debug().randomizeSeedText` | **`"7"`** | 本 STEP 先 `page.fill("#randomize-seed","7")` |
| 断言阈值 | `posts===0 && selection.length===0 && warning.length>0` | 第三条同时兼作**形态 8 的"点击真的发生了"证明**：若 `page.fill` 把页面滚走、点击静默落空，**warning 会是空串 ⇒ 红**（`r0c` 的 `warning.length>0` 是同一个道理） |
| 新总步数 | **31**（30 → 31） | 只在 `e2e.cjs` 加一条，不改 `map.js` 一个字节 |

**m6 变异体的预测（先写，再改）**：删掉 `randomizeSelectionState` 里的

```js
    if (!hexes.length) {
      return { ok: false, hexes: [] };
    }
```

⇒ 空选区被接受 ⇒ `submitRandomize` 走到 `writeCommand("map.RandomizeRegion", {hexes:[], seed:7})`。
预测**同一 STEP 的两半同时红**：
① `posts` 由 `0` 变 `1`（★ 这就是"守卫被删"的直接后果）；
② `warning` 变 `""` —— 因为守卫一缺席后代码继续走到 `map.js:2636` 的 `setWarning(…, "")`。
**raw detail 里两个数都会打出来**，所以"为什么红"可归因到守卫本身，而不是别的东西。

★ 另预测 **m6 在门禁层也有一杀**：纯函数测试 #9 `randomizeSelectionState-rejects-an-empty-selection`
断言 `randomizeSelectionState([]).ok === false` ⇒ 逐字红点是 `not ok 9 - …`，故本轮要求
`gate_rc != 0` 且该行出现（与 m4 的形态一致）。

★★ **对已有 m1~m4 杀点的影响：无。** 本 STEP 只动**装置**（`e2e.cjs`），被测字节 `map.js`
**一个字节都不动**（`f6eeed79e98ffca3a240ce86bf049084`）。但按纪律，**加完 STEP 必须重跑一轮干净 e2e**
（新端口），把总数从 30 更新为 31 —— 否则"30/30"这句话描述的是旧装置。

---

## 四 `m5`（Java 侧读路径 `ApiViews.incidentEdges`）—— **跑之前写下的预测**

**缘起**：§八.7 自陈的另一半——**Java 侧那条读路径只有 Java 测试、没有变异轮**。按判据 7 + 裁定 42，
新增护栏（`edges` 组件 + 4 条 `MapHexEdgesApiTest`）也必须自带故意违规。装置另写
`mutants/mut-run-java.sh`（前端装置不认识 javac/`.class`/surefire 报告）。

**变异体（先写清，再落盘）**：把"入射 = **两端任一**"砍成"**只查起点**"——

```java
      if (edge.a().equals(coord) || edge.b().equals(coord)) {     ← 原件（`ApiViews.java:336`，锚点全文件唯一）
      if (edge.a().equals(coord)) {                               ← 变异体
```

**为什么挑这一条（判别力，§5 形态 3）**：夹具里 `E1 = (1,1)-(1,2)` 的 `a` 端是 `(1,1)`、
`E2 = (1,2)-(1,3)` 的 `a` 端是 `(1,2)` ⇒ 对 `(1,2)` 而言 **E1 是靠 `b` 端命中的**。
⇒ "只查 a 端"与"查两端"在这个输入上**必然分叉**（1 条 vs 2 条）。

**跑之前写下的预测（逐条）**：

| 项 | 预测 |
|---|---|
| `MapHexEdgesApiTest` 结果 | `Tests run: 4, Failures: 2, Errors: 0` |
| 红点一 | `mapHexOnASharedHexListsBothIncidentEdgesInEdgeRefOrder` —— 红在 `:124` `hasSize(2)`，实测 **1** |
| 红点二 | `mapHexEdgesAreByteIdenticalAcrossTwoCalls` —— 红在 `:147` `isEqualTo(2)`，实测 **1**（★ 它的"非空前置"在替第二件事站岗，形态 3 的副产物） |
| 保持绿的 | `mapHexCarriesIncidentEdgesSortedByEdgeRefAndPathwayKey`（`(1,1)`：`E1.a=(1,1)` ⇒ 仍命中）＋ `mapHexWithoutEdgesGivesEmptyArray`（`(1,4)`：不沾边） |
| `mvn_rc` | ≠ 0 |
| `COMPILATION ERROR` 行数 | **0**（★ 不为 0 就当场作废——那是"编译错误式的红"，不算杀） |
| 本轮重编的 `.class` | ≥ 1（先删陈旧 `.class` 再断言 `-newer 本轮时间戳`） |
| surefire 报告 | 存在且 `-nt` 本轮时间戳（否则**不读它的数字**） |
| 还原 | 源 `restored_md5 == orig_md5`；并**重编一次**把干净字节码写回（`target/` 不在还原范围） |
| 重启编译 rc | 0 |

★ 另记一条**这次不打算测**的（形态 5，区分"验过"与"推出来"）：把排序改成**字符串序**
（`Comparator.comparing(EdgeRef::toString)`）在本夹具上**看不出来**——`"1_1|1_2" < "1_2|1_3"`
与 `EdgeRef` 自然序同向（python 当场算的、**不是跑出来的**）。即：javadoc 警告的那个 bug
（`1_10` vs `1_2`）**本夹具判别不了**，属**已知的判别力边界**，如实记，不当结论。

---

## 五 装置 v2（加 `r0a` 时**顺手补掉的一处伪装绿**）—— 以及它对 m4 旧证据的影响

**发现**：`r0a` 会往 `#randomize-warning` 里写一条"选区为空…"。而 `r0c` 原来的判据是
`warning.length > 0` —— **加了 `r0a` 之后，`r0c` 的前置状态就不再是空的了**：
若 `r0c` 那一下点击**静默落空**（形态 8 的原形），`r0a` 留下的那条 warning 仍在 ⇒ `r0c` 照样绿，
**而它要测的"空 seed 守卫"根本没被执行**。⇒ 这是我**引入**的判别力损失，必须当场修，不 park。

**修法（两条判据各自"点名"到自己的主体）**：

| 断言 | 改前 | 改后（装置 v2） |
|---|---|---|
| `r0a-empty-selection-zero-write-with-warning` | `warning.length > 0` | `warning.indexOf("选区为空") >= 0` |
| `r0c-empty-seed-zero-write-with-warning` | `warning.length > 0` | `warning.indexOf("seed") >= 0` |

（两处文案分别来自 `map.js:2625` / `map.js:2632`，**跑前从源码抄的**，不是从结果倒推的。）

**对 `m4` 旧证据的影响（如实记）**：`m4` 那一轮是在**装置 v1**上跑出来的，其红点原文
（`posts:1,"warning":"","status":"已随机化 2 格（seed 0…）"`）在 v2 下**仍然红**——
`posts===0` 这一半已经失败了。但这是**推导**，不是实测（§5 形态 5：验过 ≠ 推出来）。
⇒ 处置：**v2 落盘后重跑一轮 `m4`**（前端装置，代价小），把"m4 在新装置下仍被杀"变成**实测**；
报告里那段随之改口径。**m1/m2/m3 的杀点不碰 warning**（`e1`/`r1`/`x1` 都不读它）⇒ 不受影响。
★ 被测字节 `map.js` 全程未动（`f6eeed79e98ffca3a240ce86bf049084`）—— 变的只有**装置**。
