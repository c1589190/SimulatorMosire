# V1 / V2 修复 + V3 只读调查 —— 报告

> 分支 `wsf2/v1v2`，worktree `.claude/worktrees/wsf2-v1v2`，基线 `849efe6`。
> 用户反馈原文：`docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md`（本批第 2 轮实测）。

## 〇 一句话结论

- **V1（左栏过窄 + 值逐字竖排）已修**：根因是 U5 把两侧栏改成 `width: fit-content`，配合 `.kv { grid-template-columns: auto 1fr }`，**长标签（284px）把值列挤到只剩 14px** ⇒ 每个汉字一行。已把侧栏改回稳定 300px 基准宽，并给标签列加 `fit-content(140px)` 上限。**值列 14px → 118px，`plains（平原）` 8 行 → 1 行**（像素/量宽证据见 §二）。
- **V2（常规模式也画区域名）已修**：`paintRegionNames` 增**模式门控**（`regionNamesVisible`，只在 `region`/`region-edit` 生效）。**常规模式绘制条数 252 → 0**（含"缩放阈值之上"这一前提，见 §二）。
- **V3（"最顶层"顺序）只调查、未实现**：候选清单 + 推荐见 §三。**结论：模型里没有"层"字段；现成可用的顺序只有 `GameMap.regions` 的插入序（`LinkedHashMap`）**，它已随 `/api/map/overview` 到前端。字典序（`RegionIndex`）是确定性 tie-break，**不可当层次**。

## 一 改动

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/resources/webui/styles.css` | `.col-left`/`.col-right`：`flex: 0 1 auto; width: fit-content; min-width: 200px` ⇒ **`flex: 0 1 300px; min-width: 240px; max-width: 340px`**（回到可用宽度、不随内容塌缩）；`.kv`：`auto 1fr` ⇒ **`fit-content(140px) minmax(0, 1fr)`**；`.kv dt/dd`：`word-break: normal; overflow-wrap: anywhere` |
| `simos-app/src/main/resources/webui/map.js` | 新增 `REGION_NAME_MODES = ["region","region-edit"]` + 纯函数 `regionNamesVisible(mode)`；`paintRegionNames` 与 `regionNameLayouts` 改用该门控；`paintRegionNames` 入口先 `regionNameDraws = 0`（避免切模式后读到陈旧条数）；`regionNameDebug()` 增 `mode`/`visible`；导出 `REGION_NAME_MODES`/`regionNamesVisible` |
| `simos-app/src/test/js/webui-fix2.test.cjs` | U5 宽度断言改写为 `side-columns-have-a-usable-stable-width`（U5 原意"右栏无内容不留空卡片"由另一条 `right-panel-has-data-modes-…` 原样保留）；新增 `kv-value-column-is-not-squeezed-to-one-character` / `region-names-only-show-in-the-two-region-modes` / `region-name-paint-is-gated-by-mode` |
| `simos-app/src/test/js/run-gate.cjs`、`gate-contract.test.cjs` | 下界 **184 → 187**（两处同改） |

★ **U5 原意未回退**：`#right-panel` 的 `data-modes="region region-edit decision"` 与 `[hidden]` 规则**一字未动**；`align-items: flex-start` 保留。只改了"侧栏宽度按内容"这一条（它正是 V1 的根因）。

## 二 判据实测（像素级 / 量宽级）

### V1 —— 真 Chromium，1280×800

| 量 | 修前（`5817` 旧字节，只读探针） | 修后（`5861` 新字节） |
|---|---|---|
| `.kv` 网格列 | **`284px 14px`** | **`140px 118px`** |
| 值列（`dd`）宽度 | **14px**（全部 9 个值列） | **118px** |
| `terrain = plains（平原）` 行数 | **8 行**（逐字竖排） | **1 行** |
| 最坏值（`231 格（并集…）`）行数 | **29 行** | **4 行**（正常换行，非竖排） |
| 左栏宽度 | 340px（但值列被标签吃光） | **300px**（`min-width 240 / max-width 340`） |
| `dd` 的 `word-break` | `break-word` | **`normal`** |

修前证据：`logs/before-5817-layout-probe.json`（`out.view.kvCols = "284px 14px"`，`dds[].lines = [2,2,8,4,5,1,3,9,29]`）。
修后证据：`e2e/e2e-values.json`（`view.kvCols = "140px 118px"`，`dds[].lines = [1,1,1,1,1,1,1,3,4]`）+ 截图 `e2e/v1-left-panel-fixed.png`、`e2e/v1-region-edit-left-panel.png`（用户截图就是区域编辑模式）。

### V2 —— 真 Chromium 取色 + 绘制计数

| 检查 | 实测 |
|---|---|
| 常规模式（缩放已过阈值 `scaleOk=true`） | `visible=false`、**`drawn=0`、`labels=0`** |
| 区域查看模式 | `visible=true`、**`drawn=252`、`labels=252`** |
| 区域编辑模式 | `visible=true`、**`drawn=252`** |
| 取色（区域模式标签锚点 11×11 邻域） | **33 个近白像素**（白字） |
| 同锚点切回常规模式 | **0 个近白像素**（名字消失） |
| **修前**（`5817` 旧字节，常规模式 + 缩放） | **`drawn=252`**（正是用户截图的现象） |

证据：`e2e/e2e-values.json`（`viewName/regionName/regionEditName/labelHit/viewAtLabelAnchor`）、截图 `e2e/v2-view-no-region-names.png`、`e2e/v2-region-names-shown.png`、修前 `e2e/before-5817-view-region-names-zoomed.png`。
e2e 干净轮 **15/15 PASS**，日志 `logs/e2e-clean.log`（`e2e_rc=0`）。

★ e2e 跑在**变异前的最终字节**上；之后 7 个变异轮**逐字节还原**（md5 与 `logs/prod-md5.txt` 相同）⇒ e2e 证据对最终字节仍成立。

## 三 V3 只读调查 —— "顶层序"可依据的候选清单

> **未改任何实现**。当前行为：`map.js:2810-2828` 的 `selectRegionOfHex` 在"多从属"时把**全部** owner 等亮高亮（`group`），**没有**"选一个最顶层"。

| # | 顺序来源 | 证据锚 | 是否可用 | 语义 / 说明 |
|---|---|---|---|---|
| **A** | **`GameMap.regions` 插入序**（`LinkedHashMap`） | `GameMap.java:78`（`Collections.unmodifiableMap(copyOf(regions,…))`）+ `GameMap.java:224,228`（`copyOf` 用 `new LinkedHashMap<>()`）+ javadoc `GameMap.java:41-42`（"老仓用 `Map.copyOf` 冻结，迭代序按哈希表散开…本类型只把顺序那一项换掉"）；`RegionOperations.java:60-62` create **追加**、`:94-96` update **保持原位**、`:115-117` delete 移除；`/api/map/overview` 按 `map.regions().values()` **直接发**（`ApiViews.java:179`）；★ **实测**：5817 上 252 个 id **不是**字典序（`大沪王朝, 奥斯曼帝国, 区域30, …`） | ★ **可用（现成、确定、已到前端）** | "后插入者在上"（= 导入/创建序） |
| **B** | **`RegionIndex` 字典序** | `RegionIndex.java:41`（`ordered.sort(comparing(id().value()))`）+ `:56-59`；`/api/map/hex` 经 `MapResolver.regionOfHex`（`MapResolver.java:134-137`）用它；`ApiViews.java:334` 注释明写"按 `RegionId` 字典序" | 可用但 **不可当层次** | 只为"同一批区域无论什么顺序进来都得同一份有序列表"的**确定性 tie-break** |
| **C** | **`annexedBy`** | `RegionMeta.java:8`；★ **实测**：252 区中仅 **4** 个非空（`东川→大蜀`、`瓦拉几亚侯国→奥斯曼属瓦拉几亚占领区`、`区域14→石冠诸部`、`区域1464352524→石冠诸部`） | 部分可用 | "吞并者高于被吞并者"——**偏序**，非全序；且实测**与 A 不一致**（见下） |
| **D** | **显式层/优先级字段** | **不存在**：`Region.java:18-19` 只有 `id/name/hexes/boundary/meta`；`RegionMeta.java:8` 只有 `color/tag/description/annexedBy` | 不可用（**需新增**） | 真正的"层"语义；见"最小新增方案" |
| **E** | 旧仓 GSimulator 的层叠先例 | provinces 是 `Map.copyOf`（`MapData.java:63`）⇒ **无序**；`resolveHex` 取**第一个**（`GsimapResolver.java:121-124`，无序 Map 的"第一个"= 任意）；render 对**每个**区域都画名（`render.js:349-361`，仅全局 `showRegionNames` 开关）⇒ **无 province topmost**。★ "last = topmost" **只用于 `terrainBlocks`**（`MapData.java:19`、`TerrainCanvas.java:29,117`） | **不可用**（无 province 先例） | — |

### 真实数据：9 个多从属 hex（富世界 252 区，`logs/multi-owner-hexes.json`）

扫 252 个区域质心 ⇒ **13 条（9 个唯一 hex）有 ≥2 从属**。**每一对**里 A 与 B 的"最后/最大"都**不同**：

| hex | `/api/map/hex`（B 字典序） | B 取"最后" | A 取"overview 序最后" |
|---|---|---|---|
| `[54,-67]` | `[东川, 大蜀]` | 大蜀 | **东川** |
| `[33,19]` | `[区域29, 苍崖领]` | 苍崖领 | **区域29** |
| `[-99,64]` | `[区域14, 石冠诸部]` | 石冠诸部 | **区域14** |
| `[29,16]` | `[区域28, 铁峡伯国]` | 铁峡伯国 | **区域28** |
| `[-10,-55]` | `[奥斯曼属瓦拉几亚占领区, 瓦拉几亚侯国]` | 瓦拉几亚侯国 | **奥斯曼属瓦拉几亚占领区** |
| `[45,-40]` | `[区域745634564, 石桥自由市]` | 石桥自由市 | **区域745634564** |
| `[-100,64]` | `[区域14, 石冠诸部]` | 石冠诸部 | **区域14** |
| `[21,14]` | `[区域27, 灰角伯国]` | 灰角伯国 | **区域27** |
| `[-21,-87]` | `[区域1464352524, 石冠诸部]` | 石冠诸部 | **区域1464352524** |

★ **与 `annexedBy` 对拍**：3 对涉及吞并——`[54,-67]`（东川 被 大蜀 吞并）A 取**东川**（被吞并者在上）、`[-99,64]`（区域14 被 石冠诸部 吞并）A 取**区域14**（被吞并者）、`[-10,-55]`（瓦拉几亚侯国 被 奥斯曼属…占领区 吞并）A 取**占领区**（吞并者）。⇒ **A 与吞并语义不一致（2/3 相反）**。

### 推荐定义 + 理由

**推荐 A**：定义"顶层" = 该 hex 的 owners 中，在 **`/api/map/overview` 的 `regions` 数组序**里**最后**出现者。

理由：
1. **现成、确定、已到前端**（`overview.regions` 数组下标即 rank）⇒ **零服务端改动、零 schema 改动**，前端 `selectRegionOfHex` 加一个 rank 表即可。
2. 与仓库既有的 "last = topmost" 约定**同形**（`terrainBlocks`）。
3. 语义务实：后创建/后导入的区域盖在先前之上（新划的飞地/新并入的区自然在上）。

**必须写明的代价（诚实披露）**：A 是**隐式**顺序——它的语义是"导入/创建序"，**不是被作者显式编排的层**；且实测**不与 `annexedBy` 对齐**。对 v17levant 而言，该序来自 `tools/gsimap_import.py:484-499` 按源档 `provinces` 键序写入，而源档键序源自旧仓 `Map.copyOf`（哈希序）⇒ **确定但非人为编排**（实测 252 区实名/通用**混排**，不是"基础在前、新增在后"）。

**若用户要"吞并者在最上"**：在 A 之上加一条**偏序优先**——若某 owner 的 `annexedBy` 指向同集合里的另一个 owner，则取那个被指向者；否则回退 A。代价：4/252 有值，绝大多数回退 A；且 `annexedBy` 是**元数据**、可被 `map.UpdateRegion` 改。

**若用户要"可编排的层"（最小新增方案）**：`RegionMeta` 加 `Integer layer`（默认 0，可空）；`map.UpdateRegion` **已带 meta** ⇒ **无需新命令**。代价：动 `RegionMeta` record + `MapCodec` + 变更集 + payload 契约，且**存量档需迁移**。**不建议现在做**，除非用户要显式编排。

## 四 门禁

- `./mvnw clean verify` **rc=0**、**第 1 次尝试**、**8/8 `SUCCESS [`**（`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` + 父 POM）、**1360** = `170/368/45/259/178/129/211`、`BugInstance size is 0` **×7**、`[ERROR]` **0**、前端 **`tests=187 pass=187 fail=0`**。
- **基线现场重算**：`docs/shade-evidence/logs/clean-verify.log`（树 `849efe6`）= **1360** = 170/368/45/259/178/129/211、rc=0 ⇒ **Java 计数零变化**；**delta = 前端 184 → 187（+3）**。
- ★ **最终绿轮文件名**：`v1v2-evidence/logs/clean-verify.attempt1.log`（md5 `4df9074a800263fee1331794e57ef2b8`）。
- 全量耗时 `Total time: 01:09 min`（本机 `nproc=8`；无被杀轮）。

## 五 变异（九道门禁）

装置 `mutants/mut-round.sh`（①干净世界 ②变异体 md5≠原件 ③白名单推成目标文件 ④TAP 真跑到且 `# tests == 187` ⑤红点落被保护断言 ⑥逐字节还原比 md5 ⑦日志自指 ⑧红/没红都问为什么 ⑨每轮从锚点恢复）。**7 体 / 7 KILLED / 0 存活**：

| 体 | 目标 | 变异 | 红在 |
|---|---|---|---|
| `v1m1` | styles.css | 左栏回退 `width: fit-content`（U5 回归） | `side-columns-have-a-usable-stable-width` |
| `v1m2` | styles.css | `.kv` 回退 `auto 1fr`（去掉标签列上限） | `kv-value-column-is-not-squeezed-to-one-character` |
| `v2m1` | map.js | `regionNamesVisible` 去掉模式门控 | `region-names-only-show-in-the-two-region-modes` |
| `v2m2` | map.js | `paintRegionNames` 门控换回 `regionNamesEnabled` | `region-name-paint-is-gated-by-mode` |
| `v2m3` | map.js | `regionNameLayouts` 门控换回 `regionNamesEnabled` | `region-name-paint-is-gated-by-mode` |
| `rerun-u2a` | map.js | 删掉 `paintRegionNames(ctx);` 调用（裁定 42 重派生） | `region-names-are-actually-invoked-in-render-order` |
| `rerun-u5a` | styles.css | 左栏回退 `flex: 0 0 300px`（U5 旧缺陷，裁定 42） | `side-columns-have-a-usable-stable-width` |

每轮 `tests=187 / fail=1`（证明"跑到了、且只红在预期那条"）；还原 md5 与 `logs/prod-md5.txt` **逐字节相同**。日志在 `mutants/logs/*.log`。

## 六 我未能核实的

1. **V3 只调查、未实现**：`selectRegionOfHex` 仍是"多从属全亮"，没有"选一个顶层"。
2. **A vs B 的差异只在 9 个 hex 上展示**（富世界）。旧档 98 区时代记录的"81 个多从属格"**未在新 252 区档上复核**；也**未**验证 A 在**新增/改名/删除区域后**的顺序稳定性（只从代码路径（`RegionOperations` 追加/保持/移除）+ 5817 的实测序推断）。
3. **`annexedBy` 与 A 不一致**只在 3 个 hex 上对拍（4 个 `annexedBy` 中的 3 个），样本很小。
4. e2e 只在 **`--demo` 富世界（59223 hex / 252 区）** + **1280×800** 视口；**触摸 / HiDPI(dpr>1) / 第二视口**未测（M7g/M9 老开口项延续）。
5. **极窄视口**（< ~800px）下左栏 `flex: 0 1 300px; min-width: 240px` 的收缩/溢出行为**未测**。
6. `regionNamesEnabled` 的 localStorage 开关在**两模式内**仍生效；"关掉开关 + 切模式"的组合**未单独 e2e**（门控是 `enabled && mode∈两模式`，由纯函数钉住）。
7. 对 `5817`（控制器起的富世界实例）**全程只读**（GET + 截图）；未做任何写操作、未重启、未 kill。
8. `RegionMeta.layer` 最小新增方案**未实现、未测**。
9. 一处**顺带观察（未改）**：`index.html:238` 的 `#right-panel` 开始标签末尾多一个 `>`（`…data-modes="region region-edit decision">>`），会在右栏产生一个多余的文本节点 `>`。不在 V1/V2 射程内，故**未动**，留给控制器裁定。
