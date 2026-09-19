# M7c T1 报告 —— 取消工作台"左键瞬移"

- 仓根：`/home/cna/SimulatorMosire`；工作树：`.claude/worktrees/m7ct1`（分支 `m7c/t1`）
- 基线：`ea44378 M7b 关账…`（预检 `git log --oneline -1` 实测一致，`status` 干净）
- 本任务纯前端（`simos-app/src/main/resources/webui/`），**零 Java 改动、零依赖**。

## 一、改了什么 / 删了什么

| 文件 | 位置（改后） | 改动 |
|---|---|---|
| `webui/map.js` | `workbenchSelect` `:965-971` | 原 `if (mode==="unit" && selectedUnitId())` 内分「路线模式→`appendRoutePoint`；否则→`submitPlaceAt`」两支；改为 **`if (mode==="unit" && selectedUnitId() && host.routeMode)`** 单支（保留 `appendRoutePoint` + 单位选中），**非路线模式的左键落回 `app.setSelection({kind:"hex",q,r})`**。 |
| `webui/map.js` | `:1114`（删除点） | **删除 `submitPlaceAt(...)` 整个函数**（17 行，原有：唯一写点是 `unit.PlaceAt`）。删前 grep 全仓（见下）确认删除调用点后**零调用者**。 |
| `webui/map.js` | `:998-999` | 段注释由「点目标格 ⇒ unit.PlaceAt」改为「**右键目标格 ⇒ A* 下路线**；左键点格只切 hex 选中、不瞬移；路线模式 ⇒ 左键逐格加点」。 |
| `webui/map.js` | `:969-970` | 新增两行注释，记录本改动的**用户裁定出处**（与全文注释风格一致）。 |
| `webui/index.html` | `:33` | 初始提示文案 `点选一个单位开始。` → `点选一个单位开始；右键点目标格自动下路线（左键点格只选中）。` |

净变化（`git diff --stat`）：`map.js +8/-27`、`index.html +1/-1`。

### 删除前的全仓 grep（`git grep --untracked "PlaceAt"`，排除 target）
`submitPlaceAt` 唯一调用者就是被改的 `workbenchSelect`；删除后全仓对 `unit.PlaceAt` 的**生产引用**仅剩：
- `webui/unit.js:50` —— **旧调试页 `/unit` 的显式表单**（本任务按 M7b 裁定**保留**，见 §五）；
- `simos-app/.../Shell.java:193` 的 `new PlaceAtHandler()`、`tools/read/CatalogTool.java:35`（目录键）、`simos-unit/.../PlaceAtHandler.java`、以及 Java 测试 —— **均为控制台/MCP/领域层**，是 U2「瞬移归控制台」的合法落点，非本任务范围。

## 二、门禁与逐模块数字

- `./mvnw -q spotless:apply` → rc=0（无改动残留）。
- 定向 `WebuiAssetsTest`（`-pl simos-app -am`）→ rc=0。
- 全量 `./mvnw clean verify` → **rc=0、BUILD SUCCESS**，日志 `logs/full-verify.log`。

| 模块 | 用例 |
|---|---|
| simos-util | 170 |
| simos-map | 255 |
| simos-social | 45 |
| simos-unit | 131 |
| simos-core | 154 |
| simos-app | 86 |
| **合计** | **841** |

**delta = 0**（基线 170/255/45/131/154/86 = 841）。`BugInstance size is 0` **×6**（每模块一次）；`[ERROR]` 行数 **0**。纯前端资源改动，用例数不动，与预期一致。

## 三、e2e a~f 实测值（Playwright + 真 `ShellMain --demo`，端口 5819，全新 store）

日志：`logs/clean.log`（首轮）与 `logs/clean-after-mutants.log`（变异后复跑）。两轮 **E2E RESULT: PASS**。

| 步 | 断言 | 实测 |
|---|---|---|
| a | 模式=unit；点 (1,1) 选中 u-1；position | `a-unit-selected {"kind":"unit","id":"u-1"}`；`position {q:1,r:1}`；`revStart=1 head=1 nodes=1` |
| b | **左键点 (1,2) 不瞬移** | `b-no-write {0→0}`；`b-position-unchanged {before:{1,1}, after:{1,1}}`；`b-head-unchanged {rev 1, head 1}`；`b-no-new-revision {nodes 1→1}`；`b-selection-hex {"kind":"hex","q":1,"r":2}`；`b-not-teleported {q:1,r:1}` |
| c | **右键 (1,3) 仍正常下路线** | 重选 u-1 后：`/api/map/path → {reachable:true, path=[(1,1),(1,2),(1,3)]}`；`c-head-advanced {1→2}`；`c-route-path-values (1,1)->(1,2)->(1,3)`；`c-timeline-node-plus-1 {1→2}`；`c-polyline-3-points totalPoints=3 status=IN_TRANSIT` |
| d | **路线模式下左键仍能加点** | 开路线模式 → 左键 (1,2)：`routePath=[{q:1,r:2}]`（1 点）、选择仍是 `{kind:"unit",id:"u-1"}`、`d-no-write {1→1}` |
| e | **R8 allowlist** | `NON_GET_LIST=["/api/command"]` ⊆ `{/api/command,/api/advance,/api/fork}`；`violations=[]`；且**全长仅 1 条写**（正是 c 的 `PlanRoute`，证明 b/d 无写） |
| f | **零 pageerror** | `f-no-page-errors []` |

截图（`screenshots/`）：
- `screenshot-leftclick-select.png` —— 左键 (1,2) 后：单位仍在 (1,1)、左栏"未选中单位"、仅 hex 选中；
- `screenshot-rightclick-route.png` —— 右键 (1,3) 后：折线 3 点、时间轴 `main` 节点 +1。

## 四、变异（装置 `mutants/m7c-mut-round.sh`，资源类：源 + classpath 两份推送、逐字节还原、日志自指、红点落被保护断言、先断言聚合 md5 非空）

| m | 变异 | 期望红 | 实测 |
|---|---|---|---|
| m1 | 左键分支接回 `unit.PlaceAt`（= 基线 `map.js` 整份，`md5 db84b3a…`） | b 步"无写/position 未变" | **杀死**：`b-no-write FAIL {0→1}`、`b-position-unchanged FAIL {before:{1,1},after:{1,2}}`、`b-head-unchanged FAIL {1→2}`、`b-selection-hex FAIL null`……（红点 `b-position-unchanged`） |
| m2 | 右键处理**不再发 `PlanRoute`**（`handleContextMenu` 提前 `return false`，`md5 ccbdee5…`） | c 步下路线 | **杀死**：`c-path-status-200 FAIL null`、`c-head-advanced FAIL {1→null}`、`c-polyline-3-points FAIL null`（红点 `c-head-advanced`）；★ 同时 **b 步全 PASS**（左键取消未被误伤） |

两轮均满足九道门禁：干净世界（备份==工作树、源==classpath 副本）→ 变异体字节不同 → 两份推送一致 → 服务器真起（日志 mtime 落本轮）→ e2e 真红 → 红点落在被保护步骤 → 源与资源**逐字节还原**（`restored_src_md5=769f6141…` == `orig_md5`，资源聚合 md5 非空且相等）→ 日志自指（本轮 md5 写进日志本体）。
变异后复跑干净轮（`mut-runs/clean-after-mutants`）**27 步全 PASS**，确认工作树已回干净世界。

## 五、旧调试页不一致（★ 供用户决定）

- `webui/unit.js:50` 的**旧调试页 `/unit`** 仍保留 `unit.PlaceAt` **显式表单**：它是**显式提交**（用户填 q/r 点按钮），**不是隐式点击**，且 M7/M7b 已裁定旧三页**保留为调试页**。本任务**未动**它。
- **不一致点**：工作台（`/`）已无 `PlaceAt` 入口，而调试页 `/unit` 仍有；外加 MCP 目录 `CatalogTool` 仍列出 `unit.PlaceAt`、Java 侧 `PlaceAtHandler` 仍注册。⇒ 若用户希望"瞬移**完全**只归控制台、调试页也去掉"，可另开一个小任务；本任务按单据 §2.4 保留。

## 六、"我未能核实的"

1. **触摸/移动端未测** —— 本装置只跑 Playwright 鼠标左/右键；触屏下 `contextmenu` 语义不同（右键下路线在触屏无对应手势）。延续 M7b 的开口项。
2. **`b-selection-hex` 的"或至少不再瞬移的明确可观测态"** 我按**最强口径**断言了 `{kind:"hex",q:1,r:2}`；但这同时意味着**左键点格会取消单位选中**（左栏变"未选中单位"），随后右键下路线前**必须先重新点选单位**（e2e c 步即显式 `c-reselect-u1`）。这与单据 e2e "b 后直接 c 右键" 的隐含顺序不同 —— 单据未写"重选"，**以源码实际行为为准**并在此说明。是否要把"左键点格后仍保持单位选中"再调整，**待用户裁定**。
3. **`e-no-placeat-via-leftclick`（`list.length===1`）** 是"只有 c 那一次写"的强断言，本机两轮干净跑均成立；但它依赖 demo 世界不产生其它写，换世界需复核。
4. `PlaceAt` 的 Java handler / MCP 目录 / 领域语义**未核**——超出本任务范围（纯前端）。
5. 屏幕像素级折线渲染（颜色/线宽）未逐像素核，仅以 `SimosMap.debug().routes[].totalPoints` 与截图为准。

## 七、证据索引（均在 `.superpowers/sdd/2026-09-19-webui-plan/m7c-t1-evidence/`）

```
logs/full-verify.log                     全量门禁（841 / BugInstance 0 ×6 / ERROR 0）
logs/targeted.log                        定向 WebuiAssetsTest
logs/clean.log                           干净轮 e2e（E2E RESULT: PASS）
logs/clean.server.log                    干净轮服务器日志
logs/clean-after-mutants.log             变异后复跑干净轮（PASS）
logs/m1.log / m1.server.log              m1 变异轮（红点 b-position-unchanged）
logs/m2.log / m2.server.log              m2 变异轮（红点 c-head-advanced）
e2e/e2e.cjs                               e2e 脚本（a~f）
e2e/run-e2e.sh                            起 ShellMain --demo + node/Playwright
mutants/m7c-mut-round.sh                  变异装置（九道门禁 + 日志自指）
mutants/orig/map.js                       原件备份（==工作树）
mutants/orig/classes-resource/map.js      classpath 原件备份
mutants/m1/map.js                         m1 变异体（基线含左键瞬移）
mutants/m2/map.js                         m2 变异体（右键禁用）
mut-runs/clean/                           干净轮 e2e 产物（b/c/d/e json + 截图）
mut-runs/clean-after-mutants/             变异后复跑产物
mut-runs/m1/  mut-runs/m2/                变异轮 e2e 产物
screenshots/screenshot-leftclick-select.png
screenshots/screenshot-rightclick-route.png
```
