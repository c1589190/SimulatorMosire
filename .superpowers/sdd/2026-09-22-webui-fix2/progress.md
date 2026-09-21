# SDD 台账 —— WebUI 第 2 批修复（U1 / U2 / U3 / U5）

> 分支 `wsf2/u1u2u3u5`，worktree `.claude/worktrees/wsf2`，基线 `3cb823f`（2026-09-22）。
> 设计：`docs/superpowers/specs/2026-09-22-webui-fix2-design.md`；计划：`docs/superpowers/plans/2026-09-22-webui-fix2-plan.md`。
> 用户原话：`docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md`。**U4 不做**（用户明说等前两项修完）。

## 一 裁定

| # | 裁定 | 依据 |
|---|---|---|
| **U1-1** | 压暗 = 全画布深色 scrim（`#0a0d12` α0.55），只在 `region`/`region-edit`，插在**地形之后、高亮之前** | feedback U1；`render()` 原本中间无压暗层 |
| **U2-1** | 区域名锚点**服务端**给（overview 增发 `regions[].label` 质心 hex），不客户端拉全部 hex | M9 教训：overview 是块级载荷，逐区域 hex 会再引入 N+1 + MB 级 |
| **U2-2** | 渲染配方照 GSimulator（√hexCount 字号 ÷ zoom、黑描边白字）+ 最小缩放阈值 0.25 + 默认开 + localStorage | feedback U2；世界视图 97 个标签会堆叠 |
| **U3-1** | `highlightKind` = `group`（tag 全选/多从属格，等亮 0.42，**沿用**）/ `single`（单区域 0.62 + 淡色只压同 tag） | feedback U3 用户原定计划 |
| **U3-2** | 单区域来源：右栏列表 + 地图点击（owner 数 == 1）；owner>1 仍全部等亮（保留 M8-U1） | feedback U3；M8-U1 多从属语义 |
| **U5-1** | 右侧空卡片根因 = `#right-panel` 无 `data-modes` ⇒ 加 `data-modes="region region-edit decision"` | 5817 实测：view 模式右栏 300×631 全空 |
| **U5-2** | 面板高度/宽度**按内容**（`align-items:flex-start` + 内容宽 max 340） | feedback U5；根因 M7g 的固定 300px + stretch |
| **U3-3（执行期新增）** | **高亮请求竞态**：大世界"tag 全选（97 区域，数秒）"后立刻点单区域，慢轮晚到会覆盖新选择 ⇒ 加 `regionHighlightToken` 丢弃过期轮 | e2e 实测（首轮 U3 全红即此竞态）；见 `t1-report.md §四` |

## 二 门禁（★ 现场重算）

| | 值 |
|---|---|
| 基线 `3cb823f`（现场另起 worktree 实测） | rc=0、**1358** = 170/368/45/259/178/129/**209**、8/8、前端 **164/164** |
| 本树（最终绿轮） | rc=0、**第 1 次尝试**、**1359** = 170/368/45/259/178/129/**210**、8/8 `SUCCESS [`、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 **184/184** |
| **最终绿轮文件名** | `.superpowers/sdd/2026-09-22-webui-fix2/t1-evidence/logs/clean-verify.attempt1.log`（md5 `78ab63ea6bd5c980115e04d009c5db32`，`verify-rc.txt`=`rc=0`） |
| delta | app Java +1（`MapOverviewBlocksTest` 新增 1 条）、前端 164→184（`webui-fix2.test.cjs` 20 条） |

## 三 变异（九道门禁）

- JS **8 体 8 KILLED**（u1m1/u1m2/u2m1/u2m2/u3m1/u3m2/u5m1/u5m2），逐轮：干净世界 → md5 自证字节不同 → 单次逐字替换 → TAP 有 `# tests`/`# fail` → 红点落**指定断言** → `cp` 逐字节还原（md5 与原件相同）→ 日志自指。日志在 `t1-evidence/mutants/logs/`。
- Java **1 体 1 KILLED**（`ApiViews.regionLabelHex` 质心→首格）：跑在 **spotless 后的最终字节**上（`jm1-final.log`），红在 `overviewEmitsRegionLabelAsCentroidAndStaysByteIdentical`（`expected: 1 but was: 0`）。
- ★ **诚实登记**：`selectRegionOfHex` 里写的 `regionIds.length === 1 ? "single" : "group"` 与 `setHighlightRegions` 的缺省推断**等价**（单元素推断 single、多元素推断 group）⇒ 它**不是可被杀的非等价变异点**，未据此报 kill；U3 的承重 kill 是 `singleFocusAlpha` 与 `fadeScope` 两条。
- ★ **裁定 42（改既有文件 ⇒ 重跑前置变异轮）的范围声明**：本批改了 `map.js`/`panels.js`/`app.js`/`index.html`/`styles.css`/`ApiViews.java`。**未**重跑上一阶段（wsf/t1~t13，约 122 轮）的历史变异体——它们的目标字节在别的 worktree/分支上，重算不产生新判别力；本批改用**最终字节上的全量 `clean verify`（1359 绿）+ 新 8+1 轮**作为当前字节的门禁。这是**范围声明，不是"已重跑"**。

## 四 我未能核实的

1. 真浏览器 e2e 跑在**新起的 `--demo` 富世界**（`/tmp/wsf2-demo`，5861）上；**未对 `/tmp/sept-rich` 那份既有档**再验。
2. dpr>1 / 触摸下的区域名清晰度未测（本机 dpr=1）。
3. `localStorage` 跨刷新持久化**未在 e2e 独立断言**（只验默认开 + toggle 接线）。
4. 质心标签在**带洞/细长**区域可能落到区域外（与 GSimulator 同口径，未为它做特殊处理）。
5. `regionLabelHex` 的空 `hexes ⇒ null` 分支：`Region` 构造器实际是否允许空 hex 集**未单独证**（防御性分支）。
6. U3 的 `faded` 只压同 tag ⇒ **异 tag 区域不画**；观感未与用户确认。
7. 上一阶段历史变异体未重跑（见 §三）。

---

# # V1 / V2 修复 + V3 只读调查（分支 `wsf2/v1v2`，基线 `849efe6`）

> 用户第 2 轮实测：「左侧边栏的问题你还是没修」「即使在常规模式，区域名称也被显示了」
> 「区域查看/编辑模式下，点击一个地方，就自动选中拥有这个 hex 的最顶层区域」。
> 报告 `v1v2-evidence/report.md`。

## 一 根因与改动

- **V1 根因**：U5 把 `.col-left/.col-right` 改成 `width: fit-content`（`36f49ac`），叠加 `.kv { grid-template-columns: auto 1fr }`
  ⇒ 长标签（`从属区域（各区域自己的 hexCount，不合并不求和）`，max-content 284px）**吃掉整行**，值列只剩 **14px** ⇒ 逐字竖排。
- **V1 修**：侧栏回 **`flex: 0 1 300px; min-width: 240px; max-width: 340px`**；`.kv` 标签列加 **`fit-content(140px)`** 上限；`dd` 改 `word-break: normal`。
- **V2 修**：`map.js` 新增 `REGION_NAME_MODES` + 纯函数 `regionNamesVisible(mode)`，`paintRegionNames`/`regionNameLayouts` 按模式门控；`paintRegionNames` 入口先 `regionNameDraws = 0`（防陈旧条数）。
- **U5 原意未回退**：`#right-panel` 的 `data-modes` + `[hidden]` + `align-items: flex-start` **一字未动**。

## 二 实测（真 Chromium）

- V1：`.kv` 列 **`284px 14px` → `140px 118px`**；`plains（平原）` **8 行 → 1 行**；最坏值 **29 行 → 4 行**；左栏 **300px**。
- V2：常规模式（`scaleOk=true`）**`drawn 252 → 0`**；区域查看/编辑 **`drawn=252`**；标签锚点近白像素 **33 → 0**（切回常规）。
- e2e **15/15 PASS**（`v1v2-evidence/logs/e2e-clean.log`，`e2e_rc=0`）。

## 三 门禁

- `./mvnw clean verify` **rc=0、第 1 次尝试**、**8/8 `SUCCESS [`**、**1360** = `170/368/45/259/178/129/211`、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 **187/187**。
- 基线现场重算 `docs/shade-evidence/logs/clean-verify.log` = **1360**（Java 零变化）；**delta = 前端 184→187（+3）**。
- **最终绿轮文件名** `v1v2-evidence/logs/clean-verify.attempt1.log`（md5 `4df9074a800263fee1331794e57ef2b8`）。

## 四 变异（九道门禁）

- **7 体 7 KILLED / 0 存活**：`v1m1`/`v1m2`（styles.css）、`v2m1`/`v2m2`/`v2m3`（map.js）、`rerun-u2a`/`rerun-u5a`（裁定 42 从最终字节重派生）。
- 每轮 `tests=187 / fail=1`、还原 md5 逐字节相同。日志 `v1v2-evidence/mutants/logs/`。

## 五 V3（只读，未实现）

- 候选：**A** `GameMap.regions` 插入序（`LinkedHashMap`，`GameMap.java:78/224/228`，已随 `/api/map/overview` 到前端，实测 252 id 非字典序）/ **B** `RegionIndex` 字典序（`RegionIndex.java:41`，**不可当层次**）/ **C** `annexedBy`（4/252，偏序）/ **D** 显式层字段（**不存在**）/ **E** 旧仓无 province topmost（`MapData.java:63` `Map.copyOf`、`GsimapResolver.java:121-124` 取首个、`render.js:349-361` 全画）。
- **推荐 A**（零 schema/服务端改动）；★ 但实测 A **与 `annexedBy` 不一致（3 对中 2 对相反）**，语义是"后插入者在上"而非"吞并者在上"。真实数据 9 个多从属 hex 见 `v1v2-evidence/logs/multi-owner-hexes.json`。
- 若要显式层：`RegionMeta` 加 `Integer layer`（`map.UpdateRegion` 已带 meta ⇒ 无需新命令），代价 = record/codec/变更集/payload + 存量迁移。

## 六 我未能核实的（详见报告 §六）

真档未换（仍是 `--demo` 富世界）；触摸/HiDPI/第二视口/极窄视口未测；V3 未实现；A 的顺序稳定性未在"增删改区域后"实测；`index.html:238` 多余的 `>`（顺带观察，未改）。

---

# # V3（第 3 批）—— 点击取「最顶层区域」＝定义序末位（分支 `wsf2/v3`，基线 `408b034`）

> 用户原话与裁定见 `docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md` §五；设计记账见 `...-design.md` §八。报告 `v3-evidence/v3-report.md`。

## 一 裁定 / 落地

- ★ **定义**：最顶层 = `GameMap.regions` **插入序**（定义序）的**末位**（用户答「1」= 后定义的在上）。
- ★ **推翻 M8-Q6**（`/api/map/hex` 的 `regions` 字典序 → **定义序**）与 **U3-2**（多从属退回 group → **取顶层那一个**）；点 tag 仍 group（不变）。M8-U1 不破（重叠仍全部保留）。
- **最小风险**：序**从 `GameMap.regions` 派生**；`RegionIndex` **结构零改动**（只管从属）；`MapResolver.regionOfHex` 用 index 取成员后按 `regions()` 插入序重排；前端纯函数 `topRegionId` 只取末位、`selectRegionOfHex` 恒 `single`。

## 二 门禁（★ 现场重算）

| | 值 |
|---|---|
| 基线 `408b034` | rc=0、**1360** = `170/368/45/259/178/129/211`、8/8、前端 187/187 |
| 本树（**最终绿轮**） | `v3-evidence/logs/clean-verify.final.log`（md5 `05d181a8…`、rc=0）⇒ **1362** = `170/369/45/259/179/129/211`、**第 1 次尝试**、8/8 `SUCCESS [`、`BugInstance 0 ×7`、`[ERROR] 0`、前端 188/188 |
| delta | map +1、core +1、前端 +1 |

## 三 变异（九道门禁；**7 体 / 0 存活**）

`js-m1`（取首位）/`js-m2`（退回字典序）/`js-m3`（退回 group）/`j-m1`（服务端退回字典序）/`j-m2`（顺序反转）/`j-m1b`（j-m1 绕短路补轮，命中 core 判据）/`e2e-m1`·`e2e-m3`（**浏览器层**：jar 副本改 `map.js`，真 Chromium 红在 `b1`~`c1`）。日志 `v3-evidence/mutants/logs/` 与 `v3-evidence/e2e/mutant/`。

## 四 实测（真富世界，真 Chromium）

夹具 hex `[54,-67]`：定义序 `[大蜀, 东川]`（末位 **东川**）、字典序 `[东川, 大蜀]`（末位 大蜀）——**真分叉**。点击 ⇒ `highlightRegions===["东川"]`、`single`、**零写**；**像素**：observed `(171,113,93)` ⊂ 预测 `0.62·东川+0.38·scrim` `(171.99,113.07,93.72)` ⇒ dist **1.22**（选错成大蜀则 dist **37.40**）。截图 `v3-evidence/e2e/v3-click-top-region.png`（**已人工看图确认**）。

## 五 我未能核实的

见 `v3-report.md` §五：region-edit 无点击选区域（按键模型未动）/ 浏览器只验到 2 从属 / 只一份真档 / N+1 高亮等待 / 兜底色分支未触发。
