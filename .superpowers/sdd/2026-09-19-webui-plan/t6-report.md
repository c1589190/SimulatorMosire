# M7 T6 报告 —— 区域查看面板（判据④ / R4；收口 T4 偏离项 #1：区域填充色改用 `RegionMeta.color`）

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7t6`（分支 `m7/t6`，基线 `eecca75`）
- 边界：**零 Java 改动**、零依赖、零构建（无框架 / 无 npm / 无 CDN / 无外部字体）
- 本报告路径：`.superpowers/sdd/2026-09-19-webui-plan/t6-report.md`（证据在 `t6-evidence/`）

---

## 一 改了什么 / 为什么

| 文件 | 增删 | 改动 | 为什么 |
|---|---|---|---|
| `simos-app/src/main/resources/webui/panels.js` | +179 / -16 | 右栏 T6 占位 → **真区域面板**：`normalizeTag`/`groupByTag`（纯函数）、标签按钮 + 区域按钮列表、点区域/点标签高亮、区域详情 | 判据④ / R4。`groupByTag` 不查 IO、不碰 DOM；分组数据只来自 overview 的 `regions[].meta.tag`，零后端改动 |
| `simos-app/src/main/resources/webui/map.js` | +90 / -17 | **区域填充色改用 `RegionMeta.color`**（T4 偏离项 #1）：`resolveRegionColor`（纯函数，`#RRGGBB` 校验）、`withAlpha`、`setHighlightHexes` 由「key 数组」改为「`{key,color}` 数组」、按色分组填充、`debug()` 增 `highlightColors`/`regionFallback*` | MUST DO #5。T4 用固定 `rgba(255,210,80,0.42)`，与区域无关 ⇒ 现按区着色；非法/缺失用 `REGION_FALLBACK_COLOR` + `console.warn` 一次 |
| `simos-app/src/main/resources/webui/styles.css` | +57 | `.region-tag(.active)` / `.region-list` / `.region-item(.selected)` / `.region-detail` | 右栏分组的可用性（任务单未列，但无样式则标签/区域层级不可辨） |

**为什么动了 `map.js`**：任务单 §2.5 明确把「填充色改用 `RegionMeta.color`」归 T6 收口，而填充色住在 `map.js`。T4 已把 `app.js` 的 `highlightRegions` 与 `api.js` 的 `mapRegion` 接好，故 T6 的接线点只有 `panels.js`（写状态）+ `map.js`（画状态）。

**分组语义（纯函数）**：`groupByTag(regions) → [{tag, regions:[…]}, …]`。
- tag 归一化：`null` / `undefined` / 空串 / 纯空白 ⇒ 一律进「未标注」桶；桶名 `未标注` 恒排最后；
- 其余桶按 tag 字典序、桶内区域按 id 排序 ⇒ 输出与输入顺序无关（便于逐值断言）；
- 过滤掉 `id` 为 null/undefined 的坏记录；不改动入参。

**高亮语义**：点区域 ⇒ `setHighlightRegions([id])`（只此一个）；点标签 ⇒ `setHighlightRegions(该桶全部 id)`（一起高亮）。同一 hex 归属多个高亮区域时**先者胜**（`setHighlightHexes` 首个色生效；桶内 id 升序 ⇒ 可复现）。

## 二 逐模块数字（全量 `clean verify`）

命令 `./mvnw clean verify`，日志 `t6-evidence/logs/full-verify.log`：

- **rc=0**，**833 条 = 170 / 255 / 45 / 131 / 154 / 78**（util / map / social / unit / core / app）
- 基线 `eecca75` 同值 ⇒ **delta 0**（纯前端资源，不动任何测试）
- `BugInstance size is 0` **×6**；`[ERROR]` **0** 行；`BUILD SUCCESS`
- 定向 `./mvnw -q -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test` rc=0（`WebuiAssetsTest` **8/8**，报告 mtime 落本轮），日志 `logs/targeted.log`
- `./mvnw -q spotless:apply` rc=0（Spotless 只扫 Java；本轮无 Java 改动）

## 三 真实数据的 region `meta` 实测（**不假设，原样**）

数据集：M6 导入器现场生成——`python3 tools/gsimap_import.py ~/DevMosire/GSimulator/worlds/test_integration/nodes/n0000_map.json /tmp/t6-store`（19441 hex、2 条 province）。原样 JSON 在 `t6-evidence/e2e-run/real-regions.json`：

```json
[
  { "id": "test_annex_target", "name": "test_annex_target", "hexCount": 201,
    "meta": { "color": "#fc6dce", "tag": "Nation", "description": "", "annexedBy": "test_nation" } },
  { "id": "test_nation",       "name": "test_nation",       "hexCount": 701,
    "meta": { "color": "#d370d6", "tag": "Nation", "description": "", "annexedBy": "" } }
]
```

- **两条 tag 相同**（都是 `"Nation"`）⇒ 真实数据**没有 null tag**，故真实数据下「未标注」桶为空。
- **两条 color 都合法**（`#fc6dce` / `#d370d6`，均 `#RRGGBB`）⇒ 兜底色分支在真实数据下**不会触发**。
- **两区重叠**：`test_annex_target` 的 201 格是 `test_nation` 701 格的**子集** ⇒ `|union| = 701`，`hexCount 之和 = 902`。

**哪条由哪层覆盖（任务单要求写明）**：

| 断言 | 真实数据 e2e | node 纯函数自检 |
|---|---|---|
| 分组结果（桶/顺序/成员） | ✅ `group-result` == 冻结期望 | — |
| 重复 tag 聚合（两区同桶） | ✅ 真实数据即两区同 `Nation` | ✅ 夹具 Alpha 两成员 |
| **null / undefined / 空串 / 纯空白 / 缺 meta ⇒ 未标注** | ⚠️ 真实数据无 null tag ⇒ **e2e 用页面内纯函数夹具**覆盖（`group-null-bucket-fixture`，见 §七.3） | ✅ 夹具 5 种形态 |
| **区域色非法/缺失 ⇒ 兜底** | ⚠️ 真实两色都合法 ⇒ **e2e 覆盖不到** | ✅ `resolveRegionColor` 3 条 |

## 四 `groupByTag` 冻结夹具 + 断言（`node group-check.cjs`，rc=0，14/14）

夹具（覆盖 null / undefined / 空串 / 纯空白 / 缺 meta / 重复 tag / 非法 id）：

```
r-null(tag=null) r-undef(tag=undefined) r-blank(tag="") r-space(tag="   ")
r-b(tag=Beta) r-a/r-a2(tag=Alpha) r-nometa(meta=null) [id=null](坏记录)
```

逐条实测（`t6-evidence/logs/group-check.log`）：

| # | 断言 | 结果 | 实测值 |
|---|---|---|---|
| ① | `tags-order-untagged-last` | PASS | `["Alpha","Beta","未标注"]` |
| ② | `alpha-bucket-sorted-by-id` | PASS | `["r-a","r-a2"]` |
| ③ | `beta-bucket` | PASS | `["r-b"]` |
| ④ | `untagged-bucket-covers-…` | PASS | `["r-blank","r-nometa","r-null","r-space","r-undef"]` |
| ⑤ | `invalid-id-excluded` | PASS | 三桶均无 null id |
| ⑥ | `input-not-mutated` | PASS | 入参顺序不变 |
| ⑦ | `empty-input` / `null-input` | PASS | `[]` / `[]` |
| ⑧ | `normalizeTag` | PASS | `null→未标注`、`"  "→未标注`、`" Nation "→Nation` |
| ⑨ | `order-independent` | PASS | 乱序入参仍 `["Alpha","Beta","未标注"]` |
| ⑩ | `region-color-valid` | PASS | `#fc6dce` |
| ⑪ | `region-color-invalid-falls-back-and-warns` | PASS | `{fallback:"#00e5ff", warned:1}` |
| ⑫ | `region-color-missing-meta-falls-back` | PASS | `{fallbackNull:"#00e5ff", warnedNull:1}` |
| ⑬ | `region-color-shorthand-rejected` | PASS | `#fff` 不通过 |

`GRP RESULT: PASS`（rc=0）。

## 五 e2e 每步实测值（Playwright + 真 `ShellMain --store /tmp/t6-store`，**不给 `--demo`**）

日志 `t6-evidence/logs/e2e-clean.log`，`E2E RESULT: PASS`，`e2e_rc=0`，`server_ready_tries=3 code=200`。

| 步骤 | 结果 | 实测值 |
|---|---|---|
| `real-regions-and-meta` | PASS | 2 区，meta 见 §三（原样落盘 `real-regions.json`） |
| `group-result` | PASS | DOM 分组 == 冻结期望：`[{tag:"Nation", ids:["test_annex_target","test_nation"]}]` |
| `group-matches-page-pure-function` | PASS | DOM 分组 == 页面内 `groupByTag(regions)` 输出 |
| `real-data-has-no-untagged-bucket` | PASS | 真实数据桶标签 `["Nation"]`（无未标注） |
| `group-null-bucket-fixture` | PASS | 页面内 `groupByTag(夹具)` == `[{Alpha,[r-a,r-a2]},{Beta,[r-b]},{未标注,[r-blank,r-nometa,r-null,r-space,r-undef]}]` |
| `region-click-only-this-id` | PASS | `highlightRegions == ["test_nation"]` |
| `region-click-hex-count` | PASS | `expected 701 == actual 701` |
| `region-fill-color-is-meta-color` | PASS | `basePixel [168,179,106]`；`filledPixel [186,151,151]`；`expectedFill [186,151,151]` == `blend(#d370d6, base, 0.42)`；`highlightColors ["#d370d6"]` |
| `region-detail-fields` | PASS | 详情文本含 `test_nation / 701 / Nation / #d370d6 / annexedBy` |
| `tag-click-all-region-ids` | PASS | `["test_annex_target","test_nation"]`（集合相等） |
| `tag-click-distinct-hex-count-is-union` | PASS | `expectedUnion 701 == actual 701`（`sumOfHexCounts 902`，见 §七.2） |
| `tag-click-both-colors` | PASS | `["#fc6dce","#d370d6"]`（两区各自色都在场） |

截图（`t6-evidence/e2e-run/`）：`screenshot-region-panel.png`（右栏分组列表 + `test_nation` 详情）、`screenshot-tag-highlight.png`（`Nation` 标签激活、两区同时高亮、地图上两色可见）。**两份我都亲眼看过**：分组层级（标签 `Nation 2` → 两个区域项 `201 格 / 701 格`）清晰，点标签后标签按钮变蓝、两区域项均带选中描边，地图填充为两种粉色（`#fc6dce` 较浅 / `#d370d6` 较深）。

## 六 两变异轮的红点 + 九道门禁

装置 `t6-evidence/mutants/mut-round-e2e.sh`：目标 `panels.js`，**源 + classpath 两份推送**（StaticHandler 从 classpath `/webui` 读字节），起真 `ShellMain` + Playwright 观察红点。

| 轮 | 目标行 | 变异 | md5 | 期望红 | 实测红点 |
|---|---|---|---|---|---|
| **m1** | `groupByTag` 内 `var tag = normalizeTag(…)` 之后 | 加 `if (tag === UNTAGGED_LABEL) { return; }`（**丢掉未标注桶**） | `92bd4ce5ec3d2971a020a370c175d3bd` | `group-null-bucket-fixture` | ✅ `STEP group-null-bucket-fixture: FAIL [{Alpha,[r-a,r-a2]},{Beta,[r-b]}]`——**未标注桶整个消失**，红的就是分组完整性本身 |
| **m2** | 标签按钮 click 处理器 | `setHighlightRegions(ids.slice())` → `ids.slice(0, 1)`（**只高亮第一个**） | `2c51b5e7fb4fff385c8c916873c05169` | `tag-click-all-region-ids` | ✅ `STEP tag-click-all-region-ids: FAIL ["test_annex_target"]`；连带 `tag-click-distinct-hex-count-is-union`（701→201）与 `tag-click-both-colors`（只剩 `#fc6dce`）也红 |

**九道门禁逐条**（两轮均过）：

| # | 门禁 | m1 | m2 |
|---|---|---|---|
| 1 | 干净世界（源 == 备份 == classpath） | `f696891393e23a611318edb940842274` 三处一致 | 同 |
| 2 | 变异体字节不同 | `92bd4ce5… ≠ f6968913…` | `2c51b5e7… ≠ f6968913…` |
| 3 | 两份推送一致 | `mutant_md5 == pushed_classes_md5` | 同 |
| 4 | 服务器真起 | `m1.server.log` 含「GUI 服务器已启动」 | 同 |
| 5 | e2e 真跑 | `e2e_rc=1`，`E2E RESULT: FAIL` | 同 |
| 6 | 红点是被保护断言 | `STEP group-null-bucket-fixture: FAIL` | `STEP tag-click-all-region-ids: FAIL` |
| 7 | server 日志 mtime 落轮内 | `server_log_mtime=1789783744 ≥ round_start=1789783744` | `…803 ≥ …770` |
| 8 | 源 + classpath 逐字节还原 | `restored_src == restored_classes == f6968913…` | 同 |
| 9 | 日志自指 | `m1.log` 尾部「装置补记」含 `orig_md5/mutant_md5/pushed_classes_md5/restored_*` | `m2.log` 同 |

- **作废轮：无**。两轮服务器都真起（端口 5931/5932 未被占用，`server_ready_tries` 正常）。
- `COMPILATION ERROR=0` / `Tests run≥1` 两条形态不适用于资源类目标（不经 javac），以「e2e 真跑到断言」替代。

## 七 偏离 / 取代说明候选

1. **T4 的 e2e「填充像素期望值」失效（预期内，非回归）**：T4 e2e 断言 `expectedFill = blend(#ffd250, colorA, 0.42)`（固定黄）。T6 起填充色来自 `meta.color`（真实数据 `#d370d6` / `#fc6dce`）⇒ 该像素期望**必然不同**（本轮实测 `[186,151,151]` vs T4 的固定黄混合）。**T4 的 e2e 是 T4 的证据、不是 CI 断言**（本项目无 JS 测试器），故无构建红；如需重跑 T4 证据，其 `e-region-fill-color` 会红——那是设计变更的正确后果，不是缺陷。
2. **任务单 §4.3「多区域时 `hexCount` 之和一致」对重叠区域不成立**（任务单写错处）：真实数据 `test_annex_target` 是 `test_nation` 的**子集** ⇒ `|union|=701 ≠ sum=902`。我按**源码/数据真相**断言 `distinct hex == union (701)`，并把 `sumOfHexCounts=902` 一并打印（`tag-click-distinct-hex-count-is-union`）。若按任务单字面断言 902，则会把「重叠去重」这个正确行为判成红。
3. **任务单 §4.2 说「若真实数据没有 null tag ⇒ e2e 只断言真实数据的分组结果，未标注桶由 node 自检覆盖」**：我**两层都做了**——node 自检覆盖 5 种 null/空白形态，e2e 也额外用**页面内纯函数夹具**断言未标注桶。原因：m1 的红点必须落在 e2e 能观察到的断言上，否则「丢掉未标注桶」在真实数据（无 null tag）下**根本不改变 e2e 结果**，m1 无法在 e2e 装置里被杀。这是对任务单的**加强**（多一层，不是替代）。
4. **`map.js` 的 `setHighlightHexes` 契约变了**：入参由 `[key…]` 改为 `[{key,color}…]`；`debug().highlightHexCount` 由「数组长度」改为「**去重后的 key 数**」（重叠区域不再重复计数）。对单区域无影响（T4 的 `highlightHexCount === hexes.length` 仍成立）；对重叠多区域，旧口径会报 902，新口径报 701（更符合「高亮了几格」的语义）。
5. **移除 `HIGHLIGHT_FILL` 导出，新增 `HIGHLIGHT_ALPHA` / `REGION_FALLBACK_COLOR` / `resolveRegionColor`**：T4 的 e2e 未引用 `HIGHLIGHT_FILL`（它硬编码了 `#ffd250`），故无引用断裂。
6. **兜底色选了 `#00e5ff`（青色）**，与地形兜底色 `#ff00ff`（品红）区分开——两者语义不同（地形 vs 区域），不应共用一个值。
7. **e2e 用 `SimosMap.setView` 直接居中**目标 hex，而非 T4 的「滚轮缩放到 ≥1.5×」：T6 要证的是填充色/分组，不是缩放交互（缩放归 T4 证据）；`setView` 确定性更高、不依赖鼠标落点。
8. **任务单路径简写**：`webui/...` 实为 `simos-app/src/main/resources/webui/...`（简写，非错误）。
9. **未发现与本单矛盾的源码事实**；§七.2/§七.3 是任务单措辞与真实数据/装置需求的偏差，已在报告指出。

## 八 我未能核实的

1. **区域色兜底分支的「集成」未验**：`regionColor` 的 `console.warn` 只 warn 一次那条**只在真实数据出现非法/缺失色时触发**，而两份真实 region 的色都合法 ⇒ 该分支**只被 node 纯函数自检覆盖**（`resolveRegionColor` 的返回值与 warnFn 调用次数），**没有**一条 e2e 证明「真页面加载非法色区域时会 warn 且不崩」。
2. **「未标注」桶在真实服务端从未出现**：真实数据两区 tag 都是 `"Nation"`，故未标注桶的**渲染**（按钮/列表）只在夹具断言里验过纯函数，**未在真页面上渲染过**。
3. **多标签场景未测**：真实数据只有**一个** tag，故「多个标签按钮并存、点击某标签只影响该标签」的形态未用真实数据覆盖（夹具覆盖了多桶分组，但未驱动 DOM 点击）。
4. **重叠归属「先者胜」的逐格像素未验**：`tag-click-both-colors` 只证明两种色都出现在 `debug().highlightColors`；哪 201 格归 `#fc6dce`、哪 500 格归 `#d370d6`（先者胜）**未逐格取样**。`RegionIndex` 重叠时「权威解析归谁」在 T4 记为未核（`test_nation` 第 0 格实归 `test_annex_target`），本轮仍未查证。
5. **截图的美术判断是我自己看的**，未过独立视觉评审；右栏在窄屏下的换行/拥挤未测（e2e 视口固定 1600×1000）。
6. **Playwright / Chromium 版本耦合**：e2e 硬编码 `CHROME=/home/cna/.cache/ms-playwright/chromium-1234/...` 与 `NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules`——换机器/换版本即失效。
7. **大图传输代价仍在**：19441 格 overview 在浏览器内约 2.6s（T4 实测，瓶颈为 ~1MB 响应体传输），T6 未改变它；e2e 因此较慢，但无功能影响。
8. **`group-check.cjs` / e2e 不在任何 CI 门禁里**（本项目无 JS 测试器，T5 已记为系统性开口项）⇒ 本轮绿只在「当下手工跑过」的意义上成立，日后可能**静默腐烂**。
9. **T4 的 e2e 未重跑**：T4 证据目录在 T4 worktree/主树，本轮未重新执行其 `e-region-fill-color`；§七.1 的「会红」是**推导**（其期望色是固定黄），不是本轮实测的 T4 红点。
10. **旧页 `/map` 未受影响但也未 e2e**：`map.js` 是两页共用；`setHighlightHexes` 新契约只有工作台调用（旧页无高亮入口），旧页回归未单独跑。

## 九 证据索引

```
.superpowers/sdd/2026-09-19-webui-plan/
  t6-report.md                      ← 本报告
  t6-evidence/
    group-check.cjs                 分组/色解析纯函数自检（14 断言，rc=0）
    mut-manifest.md5                原件/变异体/classpath 副本的 md5
    logs/targeted.log               定向 WebuiAssetsTest（rc=0，8/8）
    logs/full-verify.log            全量 clean verify（rc=0，833 = 170/255/45/131/154/78，BugInstance 0×6，ERROR 0）
    logs/group-check.log            干净轮 14 断言 PASS
    logs/e2e-clean.log              e2e 干净轮（E2E RESULT: PASS，12 步）
    logs/clean-server.log           干净轮服务器日志（含「GUI 服务器已启动」）
    logs/m1.log / m1.server.log     m1 变异轮（红点 group-null-bucket-fixture）+ 服务器日志 + 自指补记
    logs/m2.log / m2.server.log     m2 变异轮（红点 tag-click-all-region-ids）+ 服务器日志 + 自指补记
    e2e/e2e.cjs, e2e/run-e2e.sh     e2e 装置
    mutants/mut-round-e2e.sh        变异轮装置（源+classpath 两份推送）
    mutants/orig/panels.js[.classpath]   原件与 classpath 副本（还原基准，f6968913…）
    mutants/m1/panels.js            变异体（92bd4ce5…）
    mutants/m2/panels.js            变异体（2c51b5e7…）
    e2e-run/real-regions.json       真实 region 的 meta 原样 JSON
    e2e-run/e2e-values.json         e2e 像素/色/计数实测值
    e2e-run/screenshot-region-panel.png     区域查看（分组列表 + 详情）
    e2e-run/screenshot-tag-highlight.png    点标签后多区域同时高亮
    mut-runs/m1/ · mut-runs/m2/     两轮变异轮的 e2e 输出与截图
```
