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
