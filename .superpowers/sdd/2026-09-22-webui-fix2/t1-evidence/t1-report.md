# 报告 —— WebUI 第 2 批修复（U1 / U2 / U3 / U5）

> 分支 `wsf2/u1u2u3u5`（worktree `.claude/worktrees/wsf2`，基线 `3cb823f`）。设计 `docs/superpowers/specs/2026-09-22-webui-fix2-design.md`，计划 `docs/superpowers/plans/2026-09-22-webui-fix2-plan.md`，台账 `.superpowers/sdd/2026-09-22-webui-fix2/progress.md`。
> ★ **最终绿轮文件名**：`.superpowers/sdd/2026-09-22-webui-fix2/t1-evidence/logs/clean-verify.attempt1.log`（`rc=0`，md5 `78ab63ea6bd5c980115e04d009c5db32`）。
> ★ **U4 不做**（用户原话「这个得等修完前两项再说」）。

## 一 每条用户缺陷 → 实测值

### U1 区域模式地形压暗（全新层）
- 实现：`map.js` 常量 `REGION_DIM_*` + 纯函数 `terrainDimAlpha(mode)` + `paintTerrainDim`（全画布 `fillRect`，屏幕空间）；插在 `paintTerrain/blit` 之后、`paintHighlights` 之前。
- **取色级（真 Chromium，富世界 59223 格）**：同一屏幕点 / 同一世界点，`view` 像素亮度 **184.92** → `region` **89.72**（三通道各自 ≤ 且亮度严格下降）；`dimPasses=1`、`terrainDimAlpha=0.55`（`u1-region-mode-terrain-is-darker-pixel` / `u1-dim-layer-actually-painted` PASS）。
- 纯函数：`view/map-edit/unit/decision → 0`，`region/region-edit → 0.55`。
- 证据：`t1-evidence/e2e/e2e-values.json`（`u1`）。

### U2 区域名显示（缺失 → 补上）
- 服务端：`ApiViews.mapOverview` 的每个 region 增发 `label = {q,r}`（hex 质心，`long` 累加、`round`）；Java 测试逐值（r-1 `{(0,0),(2,0)}` ⇒ 质心 `(1,0)`）+ 两次调用逐字节相同。
- 前端：`regionLabelLayout`（字号 `max(8,min(40,√hexCount×1.8))/zoom`，照 GSimulator `render.js:348-364`）+ `paintRegionNames`（黑描边白字）+ 默认开/可持久化开关 + 最小缩放 0.25。
- **取色级**：富世界缩放后 **drawn=98 / labels=98**；在"阿尔兰"标签 7×7 邻域扫到 **21 个近白像素**（`u2-region-name-renders-near-white-pixels` PASS）。
- 证据：`e2e-values.json`（`u2`）+ `u2-label-pixels`（同上）。

### U3 选择粒度（tag 全等亮 / 单区域更亮 + 同 tag 淡色）
- 实现：`app.js` 的 `highlightKind`；`buildRegionHighlightPlan` 增 `singleFocusAlpha`/`fadeScope`；`REGION_SINGLE_HIGHLIGHT`（焦点 0.62、淡色 0.13、`fadeScope:"same-tag"`）；来源分档（tag=`group`、列表单项/单从属格=`single`）。
- **逐值**：tag 全选 97 区域等亮 `alpha=0.42`；单选中目标区域 `alpha=0.62`、同 tag 兄弟 `0.13`、**异 tag 区域零条目**（`fadedCount=96`，排除异 tag 的"阿尔兰"）。
- **取色级**：同一区域，tag 全选像素 vs 单选中像素，到区域原色 `#f062cb` 的距离 **224 → 86**（更饱和/更亮）。
- 证据：`e2e-values.json`（`u3`）。

### U5 三栏布局
- 根因（实测）：`#right-panel` 无 `data-modes` ⇒ `view`/`map-edit`/`unit` 模式留一个 **300×631 的空卡片**（截图 `u5-before-view-mode-empty-right-card.png` 取自预修构建 5817）；`.col-left/.col-right` 写死 `flex: 0 0 300px` + `.wb-body{align-items:stretch}` ⇒ 左栏一大块空白。
- 修法：`#right-panel` 加 `data-modes="region region-edit decision"`；`.wb-body{align-items:flex-start}`；两栏改内容宽（`flex:0 1 auto; width:fit-content; min/max`）。
- **布局级（真 Chromium）**：`view` 模式右栏 `display:none / offsetParent:null / w=0`（**空卡片消失**）；左栏宽 **221 < 300**（按内容）；`region` 模式右栏可见（`u5-*` 全 PASS）。
- 证据：`e2e-values.json`（`viewLayout`/`u5Region`）。

## 二 门禁（★ 现场重算，只取模块汇总行）

| | 值 |
|---|---|
| 基线 `3cb823f`（另起 worktree 实测） | rc=0、**1358** = 170/368/45/259/178/129/**209**、8/8、前端 164/164 |
| 本树 | rc=0、**第 1 次尝试**、**1359** = 170/368/45/259/178/129/**210**、**8/8 `SUCCESS [`**（`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp`）、`BugInstance size is 0` **×7**、`[ERROR]` **0**、前端 **`tests=184 pass=184 fail=0`** |
| **最终绿轮** | `t1-evidence/logs/clean-verify.attempt1.log`（`verify-rc.txt`=`rc=0`；基线日志 `baseline-3cb823f-verify.log`） |
| delta | app Java **+1**（`MapOverviewBlocksTest` 新增 1 条）；前端 **164→184**（新 `webui-fix2.test.cjs` 20 条，`run-gate.cjs` 与 `gate-contract.test.cjs` 两处下界同改 + `REQUIRED_FILES` 加项） |

## 三 变异（九道门禁）

- JS **8 体 8 KILLED**：`u1m1`（删压暗调用）/`u1m2`（压暗恒 0）/`u2m1`（删区域名调用）/`u2m2`（删 `fillText`）/`u3m1`（单区域不更亮）/`u3m2`（淡色不限同 tag）/`u5m1`（右栏去 `data-modes`）/`u5m2`（恢复写死 300px）——每轮 md5 自证 + 逐字节还原（日志 `mutants/logs/*.log`）。
- Java **1 体 1 KILLED**：`jm1-final`（质心→首格），跑在 **spotless 后的最终字节**上；红在 `overviewEmitsRegionLabelAsCentroidAndStaysByteIdentical`。
- ★ **诚实登记**：`selectRegionOfHex` 的 `? "single" : "group"` 与 `setHighlightRegions` 缺省推断**等价** ⇒ 不报 kill；U3 承重 kill 是 alpha 与 fadeScope 两条。
- ★ **裁定 42 范围声明**：**未**重跑上一阶段约 122 轮历史变异体（目标字节在别处）；以"最终字节上的全量 `clean verify` + 本批 9 轮"作为当前字节门禁。

## 四 ★ 执行期发现的真缺陷（非用户报的，e2e 当场抓到）

**高亮请求竞态**：`reloadRegionHighlight` 只按 `mode` 丢弃过期轮。大世界"tag 全选"要拉 97 个区域 hex（数秒），若其后立刻点单个区域，**慢的旧轮会晚到并把新选择覆盖回旧态**（e2e 首轮 U3 全红即此）。修法：`host.regionHighlightToken`（每次请求自增，回来时不等即丢）。修复后 `u3-single-reload-completed` 稳定 PASS。**这是"e2e 必须断言真的换过来了"的又一实例**（只点一下不断言 ⇒ 会读到旧投影还当成功）。

## 五 我未能核实的

见台账 §四（真档副本未再验 / dpr>1 与触摸未测 / localStorage 跨刷新未独立断言 / 质心可落区域外 / 空 hex 分支未单证 / 异 tag 不画的观感未与用户确认 / 历史变异体未重跑）。
