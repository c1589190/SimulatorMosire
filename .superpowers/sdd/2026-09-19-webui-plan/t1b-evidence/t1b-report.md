# M7b T1 报告 —— 时间轴：可见 knob + 列坐标布局 + 分岔连线

> 工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7bt1`，分支 `m7b/t1`，基线 `af70b83`。
> 交付 = 纯前端两文件（`timeline.js` + `styles.css`）+ 本证据目录。**零 Java / 零端点 / 零依赖 / 零构建改动。**

## 一 改了什么 / 为什么

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/resources/webui/timeline.js` | ① 布局常量 `COL_WIDTH=110` / `LABEL_WIDTH=72` + 纯函数 `columnX`/`columnOf`/`nodeX`/`orderedBranches`；② `renderTrack` 改列坐标绝对定位、main 固定第一条、建 `.tl-knob`、调 `renderForkLinks`；③ 新增 `renderForkLinks`（`.tl-fork-link` 竖线）与 `mountOrigin`；④ `renderCursor` 同步 knob 位置/显隐/`data-*`；⑤ `wireEvents` 用 `setPointerCapture` + `lineNearest` 连续吸附（指针纵向偏离行仍继续）；⑥ `#timeline-meta` 的 tick 改为**游标节点**的 tick（缺省回退 head tick） |
| `simos-app/src/main/resources/webui/styles.css` | `.timeline-track{position:relative}`；`.timeline-line` 改定高 + 绝对子元素；`.tl-branch-label` / `.tl-node` 绝对定位；新增 `.tl-fork-link`、`.tl-knob`（可见可抓，圆点）|

### 为什么这样修（对着 S1 / 取证第 7~9 条）

- **缺陷①"抓不住"**：旧实现游标只是 `.tl-node.active` 类，拖动监听 `pointermove` 且要求 `event.target.closest(".timeline-line")` 非空——指针**纵向一偏**，target 不再在行内，拖动即断。修法：`pointerdown` 时 `mount.setPointerCapture(pointerId)`，`pointermove` **不再看 target**，而是用 `lineNearest(clientY)` 取纵向最近的行、`scrubTo(line, clientX)` 吸附最近节点。并渲染一个**真元素** `.tl-knob` 吸附在游标节点中心。
- **缺陷②"布局靠 flex / b2 插到 main 上方"**：旧布局让每行从 label 后从最左独立排；分支按 `ORDER BY branch` 字典序。修法：节点 `left = columnX(columnOf(branch, revision))`，`columnOf` 对非 main 分支的 rev1 递归取其 `parent` 节点所在列；行顺序 `orderedBranches()` 把 main 钉死在第一条。
- **分岔连线**：`b2@1.parent = {branch:"main", revision:3}` 数据本就具备（取证第 9 条，无需新端点）。按同列性质画一条竖线，**新类名 `.tl-fork-link`**——`g-fork` 断言 `.timeline-line` 恰 2 条，复用会打破它。

### 行序选择理由

`main` 固定第一条；其余分支按**字典序升序**。理由：① 后端 `/api/state` 的 `branches` 本就是字典序，前端保持确定性、不引入第二份顺序真相；② API 未暴露分支创建时刻，无法得到可靠创建序；③ 字典序可断言、跨次运行稳定。分支名由 `nextBranchName` 生成（b2/b3…），字典序与创建序在常规使用下一致。

## 二 门禁数字（逐模块）

| 轮次 | 命令 | util / map / social / unit / core / app | 合计 | BugInstance 0 | ERROR |
|---|---|---|---|---|---|
| 定向 | `-Dtest=WebuiAssetsTest` | — | 8 pass | — | 0 |
| 全量（改动后） | `./mvnw clean verify` | 170 / 255 / 45 / 131 / 154 / 78 | **833** | ×6 | 0 |

- **基线 833 = 170/255/45/131/154/78 ⇒ delta 0**（纯前端资源改动，用例数不变）。
- 日志：`logs/full-verify-final.log`（`logs/full-verify.log` 为首次同样绿的一轮）。

## 三 e2e 每步实测值（Playwright + 真 `ShellMain --demo`）

脚本 `e2e/e2e.cjs`，装置 `e2e/run-e2e.sh`（端口 5821~5825，**已全部收干净**；`5817/5818` 未动）。干净轮 `logs/e2e-clean-final.log` / `logs/e2e-clean-after-mutants.log`。

| 步 | 断言 | 实测值 | 结果 |
|---|---|---|---|
| b-seed | 造 ≥3 revision | `head=3 rows=3` | PASS |
| c-timeline | head==nodes | `head=3 nodes=3` | PASS |
| d-nodes | main 行节点数==head | `nodes=3 head=3` | PASS |
| d-labels | 首节点 rev1、次节点 RenameUnit | `["rev 1 · Bootstrap","rev 2 · RenameUnit","rev 3 · RenameUnit"]` | PASS |
| d-isAtTip | 纯函数四态 | `{mid:false,tip:true,missing:false,empty:false}` | PASS |
| **a** knob 存在可见 | 非 hidden、display!=none、`data-*` 与游标一致 | `knobHidden=false, display=block, knobRev=3, nodeRev=3` | PASS |
| **a** knob 跟随游标 | knob 中心 ↔ 游标节点中心 **<1px** | **`delta=0`**（node `{x:323,y:804}`，knob `{x:323,y:804}`） | PASS |
| **b** 拖 knob 横向吸附 | 从 rev3 拖到 rev2 | `rev=2`，`knobRev=2` | PASS |
| **b** 偏离后仍继续 | **offRowY=874（行中心 804，纵向 +70px）仍改 x ⇒ rev 变 2** | `offRowY=874`；`meta="分支 main · rev 2 · head 3 · tick 5"` | PASS |
| **b** 只读 | head / revisions 行数不变 | `head 3->3 rows 3->3` | PASS |
| **b2** 拖行入口 | 从行区(非节点/非 knob)起拖也吸附 | `atDown=1 after=2` | PASS |
| **c** 列等距 | 相邻差 == COL_WIDTH（容差 ≤1px） | `COL_WIDTH=110 d1=110 d2=110`；centers `x=103/213/323` | PASS |
| e-mid-rev / e-mid-disabled | 点中间节点只读、两按钮置灰 | `rev=2`，`create=true fork=true` | PASS |
| e-readonly | 预览不写盘 | `head 3->3 rows 3->3` | PASS |
| f-tip-enabled | 末端两按钮可用 | `create=false fork=false` | PASS |
| e2-drag-preview | 既有节点拖动回归 | `rev=1 create=true fork=true head=3 rows=3` | PASS |
| g-fork | 分支 2 + **`.timeline-line` 恰 2 条** | `branches=["b2","main"] lines=2` | PASS |
| g-postfork-tip | 分岔后游标在新分支末端 | `meta="分支 b2 · rev 1 · head 1 · tick 5"` | PASS |
| **e** 行顺序 | main 第一条 | `firstLine=main` | PASS |
| **d** 分岔对齐 | b2 rev1.x == main[k].x（容差 ≤1px） | `child.x=323 parent.x=323 dx=0` | PASS |
| **d** 垂直连线 | `.tl-fork-link` 存在且可见 | `[{x:323,y:804,h:40,w:2}]`（count≥1） | PASS |
| h-conflict | 409 提示 + 自动重取 | `status="末端已移动，已自动重取最新状态"`，`meta="… head 4 …"` | PASS |

**E2E RESULT: PASS**（干净轮 ×3：`e2e-run1.log`、`e2e-clean-final.log`、`e2e-clean-after-mutants.log`）。

截图 2 张：`screenshots/01-knob-selected.png`（选中态带 knob 圆点）、`screenshots/02-fork-link.png`（分岔后 main/b2 两行 + 竖连线）。
`look_at` 复核：图 1 圆点在 `rev 3` 上（x≈300–327）；图 2 竖线在 x≈300、y 812→835 连接 main rev3 与 b2 rev1。

## 四 变异（2 轮，全杀；红点均落在被保护断言本身）

| m | 护栏 | 变异（对 `timeline.js` 源） | 期望红 | **实测红点** |
|---|---|---|---|---|
| m1 | knob 跟随游标 | `renderCursor` 删掉更新 `knob.style.left/top` 两行（手柄停在 static 位置） | a 步 | **`STEP a-knob-on-cursor: FAIL delta=292.494… node={x:323,y:804} knob={x:31,y:787}`** |
| m2 | 分岔对齐 parent 列 | `columnOf` 非 main 分支直接 `return rev`（回到从最左第 1 列起） | d 步 | **`STEP d-fork-aligned: FAIL child.x=103 parent.x=323 dx=220`** |

装置（`mutants/mut-round.sh`，自证项**写进轮日志本身**）：

- 每轮开跑前 `src==tgt==原件`（`clean_world_check` + `orig_*_md5`）；
- **白名单推送**：`cp mN.timeline.js → timeline.js` 到**源 + `target/classes`** 两份，推送后 md5 == `mutant_md5`（`pushed_src_md5`/`pushed_tgt_md5`）；
- 跑完**逐字节还原**，`restored_*_md5 == orig_*_md5`（两轮均 `restore_ok=yes`）；
- 变异体生成器 `gen-mutants.py` 断言锚点匹配恰 1 次、替换后字节必变；md5 清单 `mutants/mut-manifest.md5`。
- 原件 `e24956bf3eb0fe92d61337a496923baf`；m1 `8586dde8…`；m2 `5b5996b3…`。
- **红后重跑干净轮**：`e2e-clean-after-mutants.log` = PASS，证明还原的世界真干净（"验过 vs 推出来"分开）。
- 轮日志：`mutants/round1-m1/round.log`、`mutants/round2-m2/round.log`。

## 五 我未能核实的

1. **嵌套分岔**（fork 自 fork）的列递归 `columnOf` 只有代码路径，**没有真 e2e 样本**（实跑只到 main + b2 一层）。
2. **横向滚动**：`trackWidth` 溢出时 `overflow-x` 的表现未实测（样本仅 3~4 列）。
3. **窗口 resize** 重算连线/knob 的监听已加，但**无 e2e 步骤**触发 resize，只保证代码路径存在。
4. **输入设备**：只测了鼠标（Playwright mouse），**触摸/笔**未测。
5. **`columnOf` 的防环 `visited` 分支**未被触发（正常数据无环）。
6. e2e 仅在**本机 headless Chromium** 跑；未跨浏览器/跨机验证。
7. `noWebuiAssetContainsAnAbsoluteUrl` 只证明新 JS/CSS 无绝对 URL（门禁跑过），但**未逐行人工复核**每处注释形态（依赖门禁）。
8. `#timeline-meta` 的 tick 口径改为"游标节点 tick"是我按派单 §2.6 语义做的解释；**"沿用现有口径"是否指保留 head tick 未与控制器确认**（格式未变，仅数据源由 head 改为游标节点，缺省仍回退 head tick）。

## 六 与派单不一致处（以源码为准）

1. **派单 §2.2 的公式 "x = 左内边距 + (revision − 1) × 列宽" 对非 main 分支不成立**——源码/取证第 9 条要求 rev1 对齐 `parent` 列，故实际是 `x = 左内边距 + (列 − 1) × 列宽`，其中 main 的列==revision、分支的列由 `parent` 递归决定。已按此实现。
2. **派单 §2.6 "沿用现有口径"**：既有实现的 `tick` 取的是**分支 head 的 tick**，不是游标节点的。我按"游标所在节点…tick"的字面改为游标节点 tick（格式一字未改），已记入"未能核实"第 8 条。
3. 另：派单 §1 提到的既有 e2e 断言、后端契约、`.timeline-line` 计数约束**均与源码一致**，无误。

## 七 证据索引

```
t1b-evidence/
  logs/full-verify-final.log            # 全量 833 绿（最终）
  logs/full-verify.log                  # 全量 833 绿（首轮）
  logs/e2e-clean-final.log              # 干净 e2e PASS（含 b-row-drag）
  logs/e2e-clean-after-mutants.log      # 变异后干净 e2e PASS
  e2e/e2e.cjs  e2e/run-e2e.sh           # 装置（含 a~f 新步）
  e2e/e2e-run1.log                      # 首轮 e2e PASS
  e2e/probe-*.json                      # knob/drag/columns/fork 原始实测
  screenshots/01-knob-selected.png      # 截图①（knob 选中态）
  screenshots/02-fork-link.png          # 截图②（分岔竖连线）
  mutants/gen-mutants.py                # 变异体生成器（锚点自证）
  mutants/mut-round.sh                  # 轮装置（源+classpath 推送/还原/日志自指）
  mutants/m1.timeline.js m2.timeline.js # 变异体
  mutants/mut-manifest.md5              # md5 清单
  mutants/round1-m1/  round2-m2/        # 轮日志 + e2e 日志
```
