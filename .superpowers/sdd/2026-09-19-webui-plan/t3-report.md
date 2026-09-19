# M7 T3 报告 —— 底部线型时间轴（U1）

> 任务：WebUI 可视化骨架第三个任务。工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7t3`，
> 分支 `m7/t3`，基线 `fb0af22`（`docs(m7): T2 关账`）。设计见 `docs/superpowers/specs/2026-09-19-webui-design.md`
> §〇.1 判据② / §〇.2 用户裁定 U1 / §五 时间轴交互 / §七 R1·R2；计划见 `docs/superpowers/plans/2026-09-19-webui-plan.md` T3。

---

## 一 改了什么 / 为什么

**纯前端**（`simos-app/src/main/resources/webui/` 五文件）；**零 Java 改动、零新端点、零新依赖、无 npm/构建/CDN**。

| # | 文件 | 改动 | 为什么 |
|---|---|---|---|
| 1 | `webui/api.js` | 新增 `withTarget(path, target)` 与 `timeline(branch)`；**所有只读取数**（resolve/facets/mapOverview/mapHex/units/unit/population）加可选 `target` 形参，`?branch=&revision=` 自动拼接；导出 `withTarget`/`timeline` | MUST DO #2「所有面板取数必须带上 `?branch=&revision=`」。约定做进取数层，**T4~T7 直接复用**；旧三页不传 target ⇒ 行为不变（服务端缺省 head(main)） |
| 2 | `webui/app.js` | 状态机加 `branches`/`heads`；新增 `refreshState()`/`setServerState()`/`target()`；`pollState` 改为**只在首次（revision 为 null）初始化游标**，此后 5s 轮询只更新 heads/branches | 否则轮询会把用户拖到中间节点的游标弹回末端，U1 的"只读预览"当场失效。`target()` 是面板取数的唯一来源 |
| 3 | `webui/index.html` | 底栏加 `#timeline-create`（创建节点）/`#timeline-fork`（分岔）两按钮（初始 `disabled`）与 `#timeline-status` 提示位 | 写入口与 409 提示的落点；按钮状态由 `timeline.js` 依末端判定驱动 |
| 4 | `webui/timeline.js` | **本任务主体**：纯函数 `isAtTip(state, branchHeads)`/`shortCommandType`/`nextBranchName`；按 `/api/state` 的 branches 每分支画一条线、每 revision 一个节点（标签 `rev n · 命令短名`）；点击/指针拖动游标（**只改状态机，不发写**）；末端才启用两按钮；`onCreate` 走 `/api/advance`、`onFork` 走 `/api/fork`；409 ⇒ 提示"末端已移动"并自动重取 | 判据② / U1 / R1 / R2 的全部落点 |
| 5 | `webui/styles.css` | 时间轴布局：多分支线（`.timeline-line` + 伪元素横线）、节点胶囊（`.tl-node`，选中 `.active`）、动作区、`button:disabled` 灰化（覆盖 `.primary` 的蓝底）、状态提示色 | 无框架的线型时间轴视觉；disabled 必须**看起来**也灰 |

**未改**：`simos-core` / `GuiServer` / `ApiViews`（**零 Java 改动**，`git status` 实证）；`panels.js`/`unitTree.js`/`map.js`/`unit.js`/`social.js`；旧三页 HTML；`api.js` 的写端点（`advance`/`fork` 包装早已存在，本次只接线）。

### 与派单书的分歧（以源码为准）

1. **"创建节点"选了 `POST /api/advance`（推进一格）**，不是 `/api/command`。参数以 `AdvanceTime` 契约为准：`from = 末端节点的 tick`，`to = tick + 1`。依据：`TimeAdvance.run` **第 0 项校验**是「`range.to` 缺失 ⇒ Rejected（开区间落不成 revision）」——所以必须给 `to`；而"推进一格"最自然的语义就是 `[tick, tick+1)`。实测每点一次「创建节点」head +1、tick +1（见 §三 e 之后的 h 步：`head 3 → 4`、`tick 5 → 6`）。
2. **"拖"与"点"都实现了**：`#timeline-mount` 上 `pointerdown/move/up` 拖动 scrub + 节点 `click`。e2e 两条路径都测（`e-*` 用 click，`e2-drag-preview` 用真 `page.mouse` 拖动）。
3. **`/api/timeline` 的 JS 包装在 T1/T2 没接**：T1 加了端点、T2 没加 `api.timeline()`。本任务补上（否则前端无从取节点清单）——属 T3 范围内的接线，非新增端点。

---

## 二 测试条数与逐模块数字

- **基线**（本任务开工时当场跑，`logs/baseline-full-verify.log`）：`./mvnw clean verify` rc=0，**833** 条 = **170 / 255 / 45 / 131 / 154 / 78**（util/map/social/unit/core/app）。
- **终态**（`logs/full-verify.log`）：rc=0，**833** 条 = **170 / 255 / 45 / 131 / 154 / 78**，`BugInstance size is 0` ×6，`[ERROR]` **0** 行。
- **逐模块 delta 全 0**——本任务纯前端，**未加/未改任何 Java 测试**（R1/R2 的护栏是 e2e 行为断言，不是 Maven 用例；见 §四）。
- **定向**（`logs/targeted.log`）：`WebuiAssetsTest` **8** 条全绿（T2 已有；`timeline.js` 已在 `ALL_ASSETS` 内、非空/无绝对 URL 判定器继续通过）。

---

## 三 端到端实测（真 `ShellMain --demo` + 真 `StaticHandler` + Playwright 驱动真页面）

装置：`e2e/run-e2e.sh`（起 `ShellMain --store <fresh> --demo --gui-port <port> --mcp-port 0 --approval-port 0`）
+ `e2e/e2e.cjs`（Playwright 1.63.0，chromium `~/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome`）。
**一次跑完 14 项全 PASS**，原始输出 `e2e/e2e-clean.log`：

| 步 | 断言 | 实测值 |
|---|---|---|
| b | 造 ≥3 revision（bootstrap + 2×`unit.RenameUnit`） | `head=3 rows=3` |
| c | `/api/timeline` 的 head 与 nodes 一致 | `head=3 nodes=3` |
| d | 页面节点数 == head | `nodes=3 head=3` |
| d | 节点标签 = `rev n · 命令短名` | `["rev 1 · Bootstrap","rev 2 · RenameUnit","rev 3 · RenameUnit"]` |
| d | 纯函数 `isAtTip`（中间/末端/缺分支/null） | `{mid:false, tip:true, missing:false, empty:false}` |
| e | 点中间节点 ⇒ 游标到 rev 2 | `{create:true,fork:true,rev:2,branch:"main",meta:"分支 main · rev 2 · head 3 · tick 5"}` |
| e | **中间节点两按钮置灰（R2）** | `create_disabled=true fork_disabled=true` |
| e | **只读预览不写盘（R1）** | **`head 3->3 rows 3->3`** |
| f | 点末端 ⇒ 两按钮可用 | `{create:false,fork:false}` |
| e2 | **拖动**游标 ⇒ 只读预览 + 置灰 | `{"rev":1,"create":true,"fork":true} head=3 rows=3` |
| g | 分岔 ⇒ `/api/state` 多分支 + 第二条线 | `branches=["b2","main"] lines=2` |
| g | 分岔后游标在新末端、按钮可用 | `{create:false,fork:false,meta:"分支 b2 · rev 1 · head 1 · tick 5",active:"rev 1 · ForkBranch"}` |
| h | **409 路径**：外部先推 head 一格，再点「创建节点」 | `status="末端已移动，已自动重取最新状态" create_disabled=true meta="分支 main · rev 3 · head 4 · tick 6"` |

- **h 步（409）当轮稳定复现**：外部 `POST /api/advance` 把 main 从 `head 3 / tick 5` 推到 `head 4 / tick 6`；页面手里的 `expectedRevision=3` 过期 ⇒ 点「创建节点」收 **409**；UI 出"末端已移动，已自动重取最新状态"，且 `#timeline-meta` 的 `head` **自动更新为 4**、按钮随之置灰（游标仍在 rev 3 ≠ 新 head）。**未静默重试写**（只重取）。
- **截图**：`screenshot-mid-disabled.png`（中间节点：`rev 2` 高亮、两按钮灰化）、`screenshot-forked-two-lines.png`（`b2` + `main` 两条线，`rev 1 · ForkBranch` 高亮）。均 1440×900 PNG。
- **前后快照**：`timeline-before.json` / `timeline-after-preview.json`（后者与前者 `head`/`nodes` 逐值相同 ⇒ R1）。
- 核验后进程已关（装置 `trap` 杀 `SERVER_PID`；见 §六 收尾核查）。

---

## 四 变异表（九道门禁，2 轮，0 存活）

装置 `mutants/mut-round-e2e.sh`：**资源类目标**照 T2 的形态——无「推成类名 / 清陈旧 `.class`」两步；
但 T3 的护栏是 **e2e 行为断言**（不是 Maven 用例），故变异体要推给 **源码 + classpath 两份**
（`StaticHandler` 从 classpath `/webui/` 读字节），起真 ShellMain + Playwright 观察红点。其余门禁一条不少：

- **门禁 1 干净世界**：备份 md5 == 工作树源 md5 == classpath 副本 md5（三者一致才开跑；防陈旧构建）。
- **门禁 2 字节不同**：变异体 md5 ≠ 原件 md5。
- **门禁 3 两份推送一致**：源与 classpath 推送后 md5 相等。
- **门禁 4 真跑到**：服务器日志含「GUI 服务器已启动」、e2e 日志含 `E2E RESULT`、server 日志 mtime ≥ 轮开始时刻。
- **门禁 5 红点是被保护断言**：grep `STEP <expect>: FAIL`（不是任意失败）。
- **门禁 6 逐字节还原**：源与 classpath 两份 md5 都回到原件。
- **门禁 7 日志自指**：`orig_md5`/`mutant_md5`/`pushed_classes_md5`/`restored_src`/`restored_classes` 追加进日志本身。

| m | 护栏 | 变异体（相对原件） | 期望红 | 实测红点（日志） |
|---|---|---|---|---|
| **m1** | **R1** 只读预览 | `timeline.js` 的 `moveCursor` 里**误发一次** `window.SimosApi.advance(branch, head, tick, tick+1)`（+1 行） | e2e 的 head/行数断言 | **`STEP e-readonly: FAIL head 3->4 rows 3->4`**；连带 `e2-drag-preview: FAIL ... head=4 rows=4`；`E2E RESULT: FAIL`（`logs/m1.log`） |
| **m2** | **R2** 末端才可写 | `timeline.js` 把两处 `create.disabled = !atTip || model.busy` 改成 `= model.busy`（**去掉末端判定**） | "中间节点两个按钮 disabled"断言 | **`STEP e-mid-disabled: FAIL create_disabled=false fork_disabled=false`**；连带 `e2-drag-preview` 置灰断言红；`E2E RESULT: FAIL`（`logs/m2.log`） |

- 两轮均：`rc=1`、红点落在**被保护断言本身**、源与 classpath **逐字节还原**（`restored_src`/`restored_classes` == `orig_md5`）、日志自指段在案（`grep -c 装置补记` = 1）。判词 `logs/m1-verdict.log` / `logs/m2-verdict.log`，md5 见 `mut-manifest.md5`。
- **"为什么红"**：m1 的红是 head 从 3 变 4、`revisions` 行数从 3 变 4——正是"预览误写盘"的后果本身，不是别处的连带错。m2 的红是 `create.disabled=false`——正是"末端判定被去掉"的直接后果。

---

## 五 偏离 / 取代说明候选（供控制器裁决）

1. **"创建节点" = `/api/advance` 推进一格**（`from=末端 tick`、`to=tick+1`）。派单书允许二选一并要求记理由——见 §一 分歧 1。**建议维持**。
2. **`isAtTip` 的形参命名**照派单书 `isAtTip(state, branchHeads)`；`branchHeads` 是 `/api/state` 的 `heads`（`{分支名: revision}`）。**纯函数、无 DOM、无 IO**，e2e 直接 `page.evaluate` 断言四种边界。
3. **`app.js` 的轮询语义微调**（只在首次初始化游标）：这是 U1 的必要条件（否则预览被轮询冲掉）。旧三页不依赖 `state.revision`（`grep` 实证零引用），行为面无回归。
4. **多分支画法**：`/api/state` 给 branches+heads，前端**逐分支**拉 `/api/timeline?branch=`。分支数少，代价可忽略；未做合并请求（无此端点，也不该加）。
5. **disabled 的视觉**：深色主题下"次级按钮的启用态"与"禁用态"像素接近，故显式给 `button:disabled` 覆写灰底/灰字 + `opacity:0.45`。**功能判定以 DOM `disabled` 属性为准**（e2e 断言的就是它），截图仅作辅助。

---

## 六 我未能核实的

1. **多分支 >2 条的渲染未实测**：e2e 只分岔一次（`b2` + `main` 两条线）。`renderTrack` 对 N 分支是同一段循环，但"3 条以上"没有当场证据。
2. **拖动的"跨分支"未测**：`e2-drag-preview` 只在 `main` 一条线上拖。多分支时 `scrubTo` 取 `event.target.closest(".timeline-line")`，理论上按行区分，但**没有实测**"从 main 拖到 b2 的线"。
3. **`?branch=&revision=` 的取数约定本身未被 e2e 直接消费**：T3 的面板还是骨架（不取数），e2e 只验证了时间轴自己的取数（`/api/timeline?branch=`）与状态机的 `{branch,revision}` 变化。**约定对 T4~T7 的可用性属于"读起来对"，未跑过**（形态 5）。
4. **409 的窗口依赖轮询间隔**：h 步靠"外部写与点击落在 5s 轮询窗口内"复现。本轮一次成功，但**不是结构保证**；若轮询恰好插在中间，按钮会先被置灰、点击不触发。装置对此**如实记 UNCOVERED 而不伪造**。
5. **时间轴节点数很大的性能未测**：spec §九-1 要求 T3 实测"拖动每次切换重放 19441 格地图的代价"。本任务的 e2e 用 `--demo` 小世界（3 revision、1 hex 地图），**大图/长历史的拖动手感未测**——这是 spec 明确挂给 T3 的实测项，我**只测了小规模**。建议归 T8 关账或 M8。
6. **CJK 渲染**只由截图间接证明（无豆腐块）；未做像素级字体断言。
7. **Playwright 版本耦合**：chromium-1234 与 playwright 1.63.0（期望 revision 1243）**版本不完全匹配**，本轮用 `executablePath` 显式指定才跑通；换机器需按本机缓存重取路径（运行环境，非代码）。

---

## 七 证据索引（`.superpowers/sdd/2026-09-19-webui-plan/t3-evidence/`）

| 文件 | 内容 |
|---|---|
| `logs/baseline-full-verify.log` | 基线 `clean verify`（833） |
| `logs/full-verify.log` | 终态 `clean verify`（833、delta 0、BugInstance 0 ×6、ERROR 0） |
| `logs/targeted.log` | `WebuiAssetsTest` 8 条 |
| `logs/e2e-shell.log` | 真 ShellMain 启动日志 |
| `e2e/e2e.cjs` / `e2e/run-e2e.sh` | e2e 装置（Playwright + 真服务） |
| `e2e/e2e-clean.log` | 干净轮 14 项全 PASS 原始输出 |
| `screenshot-mid-disabled.png` / `screenshot-forked-two-lines.png` | 中间节点置灰 / 分叉后两条线 |
| `timeline-before.json` / `timeline-after-preview.json` | `GET /api/timeline` 预览前后快照（逐值相同） |
| `logs/m1.log` / `logs/m1.server.log` / `logs/m1-verdict.log` | R1 变异轮 |
| `logs/m2.log` / `logs/m2.server.log` / `logs/m2-verdict.log` | R2 变异轮 |
| `mutants/orig/timeline.js`、`mutants/m1/timeline.js`、`mutants/m2/timeline.js` | 原件与两个变异体 |
| `mutants/mut-round-e2e.sh` | 变异装置（资源类目标 + e2e 观察红点） |
| `mut-manifest.md5` | 全部产物的 md5 |
