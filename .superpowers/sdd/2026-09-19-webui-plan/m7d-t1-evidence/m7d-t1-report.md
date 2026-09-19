# M7d T1 报告 —— 修用户实测四缺陷（A 布局 / B 圆环 / C tick 刷新 / D 提示）

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7dt1`（分支 `m7d/t1`，基线 `1f52312`）
- 提交：`f79fe89`
- 改动：**纯前端 4 文件**（`index.html` / `styles.css` / `timeline.js` / `map.js`），**零 Java 改动**
- 门禁：`./mvnw clean verify` 绿（rc=0、**841** 条 = 170/255/45/131/154/86、`BugInstance size is 0` ×6、`[ERROR]` 0）
- e2e：真 `ShellMain --demo`（全新 store）+ 真 Playwright，**19 断言全 PASS**，5 张截图
- 变异：**4 轮 4 杀 0 存活**（源与 classpath 两份推送、逐字节还原、日志自指）

---

## 一、用户四条 → 结论

| 用户原话 | 事实 | 根因 | 修法 |
|---|---|---|---|
| A「时间轴被左边栏挤到浏览器最下面」 | **确认**：1280×800 下 `#timeline-bar` 落在 `barTop=1592`（视口 800），`docScrollHeight=1661` | 全站无视口高度约束，底栏在文档流末尾 | 工作台页 `body.workbench-page` 改视口高度 flex：主区吃剩余高度、三栏各自 `overflow:auto`、底栏 `flex:0 0 auto` |
| B「圆环完全拖不动」 | **复现，但根因 = A**：knob 中心 `document.elementFromPoint` 返回 `null`（y=1117 在视口外），指针事件根本到不了 | A 的下游症状；knob 拖动逻辑本身正确 | A 修好后即可拖（见 §三 B 实测）；未改拖动逻辑 |
| C「tick 随节点新建没有增加」 | **后端确在增**（revisions.tick 5→6→7）；显示层有**两处滞后**（见 §三 C） | ① `renderCursor` 用陈旧的 `model.heads` 判末端/显示 head ⇒ 写后瞬态把「创建节点」置灰；② refresh 在途时来的新 head 不排队补刷 ⇒ 可能停在陈旧模型直到下次 5s 轮询 | ① 改用权威 `state.heads`；② 加 `refreshQueued` 补一轮 |
| D「移动一次出现两个节点」 | **设计如此**（右键=`unit.PlanRoute`；创建=`core.AdvanceTime`），**不改语义** | 缺可理解性文案 | 右键成功提示「点『创建节点』推进时间，单位才会出发」+ 按钮 `title`/文案「创建节点（推进时间）」；节点标签已分辨 `PlanRoute`/`AdvanceTime` |

---

## 二、改了什么（逐处）

| 文件 | 位置 | 内容 |
|---|---|---|
| `index.html` | `:9` | `<body class="workbench-page">`（视口布局作用域，只影响工作台页） |
| `index.html` | `:96-97` | 创建按钮加 `title="推进时间一格（core.AdvanceTime）…"`、文案改「创建节点（推进时间）」 |
| `styles.css` | `:21` | `html { height:100%; }` |
| `styles.css` | `:368-396` | `body.workbench-page` 视口高度 flex；`.workbench` `flex:1 1 auto; min-height:0; grid-template-rows:minmax(0,1fr); align-items:stretch; overflow:hidden`；三栏 `min-height:0; overflow:auto`；底栏/顶栏/模式栏 `flex:0 0 auto` |
| `timeline.js` | `:137-147` | 新增 `refreshQueued` 与 `currentHeads(state)`（优先权威 `state.heads`，退化 `model.heads`） |
| `timeline.js` | `:184-190` | `refresh()` finally：若在途期间有新 head ⇒ 补一轮 |
| `timeline.js` | `:331` | `atTip` 改用 `currentHeads(state)` |
| `timeline.js` | `:342` | 底栏 `head` 改用 `currentHeads(state)` |
| `timeline.js` | `:516-521 / :545-550` | `onCreate`/`onFork` 的 head/isAtTip 改用 `currentHeads(state)` |
| `timeline.js` | `:573-585` | `onStateChanged`：loading 中若 head 有变 ⇒ `refreshQueued=true`；比较对象改用权威 heads |
| `map.js` | `:1141 / :1194` | 两条下路线成功提示追加「—— 点『创建节点』推进时间，单位才会出发」 |

---

## 三、逐条根因 + e2e 实测值

### A. 视口高度布局
- **根因（文件:行）**：`styles.css:21-28`（body 无高度约束）、`styles.css:353-365`（`.workbench` `align-items:start`、三栏不滚动）、`styles.css:388-395`（`.timeline-bar` 在文档流）
- **修前**（`repro/repro-values.json`）：`viewportH=800, docScrollHeight=1661, barTop=1592, barBottom=1661, leftScrollH=1462`
- **修后**（`logs/clean-final.log`）：
  `a-timeline-in-viewport PASS {viewportH:800, docScrollHeight:800, barTop:731, barBottom:800, leftClientH:601, leftScrollH:1762, detailRows:21}`
  ⇒ 左栏 21 行 / 内容高 1762 > 可视 601（栏内滚动），时间轴 `barBottom==800` 恰在视口底。

### B. 圆环拖不动（根因 = A）
- **根因（文件:行）**：无独立缺陷。`timeline.js:426-467` 的 pointerdown **本就**把 `.tl-knob` 列为拖动把手；真正问题是 `styles.css:388-395` 的文档流布局让 knob 落在视口外。
- **证据（隔离实验）**：
  - 修前 1280×800 `repro-values.json`：`B_KNOB.elementAtCenter="null"`（视口外）→ `B_DRAG changed:false`
  - 超高视口（2200px，knob 可见）`probe-b`：`KNOB_DRAG {rev0:4, rev1:2, changed:true}` —— 证明**可见即可拖**
  - 1280×800 滚到底后 `probe-b2`：`SCROLLED_KNOB_DRAG {rev0:4, rev1:2, changed:true}`
- **修后（e2e）**：
  - `b-knob-visible PASS {hidden:false, w:14, h:14, pointerEvents:"auto", elementAtCenter:"tl-knob"}`
  - `b-knob-in-viewport PASS {inViewport:true}`
  - `b-knob-drag-changes-rev PASS {revB0:4, revB1:2}`（**真从 `.tl-knob` 的 `boundingBox` 中心按下**）
  - `b-offtip-disables-create PASS {createDisabled:true}`（拖离末端 ⇒ 只读预览，不误写）

### C. tick 显示刷新
- **事实核对**：`revisions.tick` 确在增（`apiRevs 1:5,2:5,3:6,4:7`），后端无问题。
- **根因（文件:行）**：
  1. `timeline.js:328`（改前 `var atTip = isAtTip(state, model.heads)`）与 `:339`（`model.heads[state.branch]`）用**异步滞后**的模型表 ⇒ `probe-c` 实测右键写后 +157ms：`head` 状态已 {main:2} 而模型仍 {main:1} ⇒ `createDisabled:true`、底栏 `head 1` 造假（**写后瞬态禁用 = 用户点第二次没反应**）。
  2. `timeline.js:555-565`（改前 `onStateChanged` 在 `model.loading` 时直接 return，不排队）⇒ 若新 head 在 refresh 在途时到达，模型会停在陈旧值直到下次 5s 轮询。
- **修后（e2e，连续两次「创建节点」）**：
  - `c-tick-advances-r1 PASS {revBefore:2, revAfter:3, newTick:6, shell:"…rev 3 · tick 6", meta:"…rev 3 · head 3 · tick 6"}`
  - `c-tick-advances-r2 PASS {revBefore:3, revAfter:4, newTick:7, shell:"…rev 4 · tick 7", meta:"…rev 4 · head 4 · tick 7"}`
  - `c-create-not-stuck-disabled-r1/r2 PASS`（写后按钮立即回可用）
  - `c-node-count-r1/r2 PASS`（DOM 节点数 == `/api/timeline` 行数）
  - 修前同刻 `timelineMeta` 曾为 `head 1`（`repro.log` 的 `D_TIMELINE_META`），修后同刻即为 `head 2`（`repro2` 的 `D_TIMELINE_META`）

### D. 语义可理解性（不改语义）
- **根因（文件:行）**：`map.js:1141/1194` 只报「已下路线…」；`index.html:96` 按钮只写「创建节点」。
- **修后（e2e）**：
  - `d-route-hint PASS "已下路线 u-1（2 格，替换原路线）—— 点「创建节点」推进时间，单位才会出发"`
  - `d-create-title PASS "推进时间一格（core.AdvanceTime）：单位沿已下路线出发/前进"`
  - `c-node-labels-distinguish-commands PASS "rev 1 · Bootstrap | rev 2 · PlanRoute | rev 3 · AdvanceTime | rev 4 · AdvanceTime"`
- **报告声明**：若用户想要「一次点击就走到位」（自动推进到抵达），那是**另一条设计**（需用户裁决）——**本轮不做**。

---

## 四、变异表（4 轮，每缺陷 1 轮）

装置：`mutants/m7d-mut-round.sh`（源 + classpath 两份推送；逐字节还原；日志自指；红点须落在被保护步骤；先断言聚合 md5 非空）。

| 轮 | 缺陷 | 变异体 | 目标 | 红点（被保护步骤） | 结果 |
|---|---|---|---|---|---|
| m1 | A | `styles.css`：`body.workbench-page` 打回 `display:block; overflow:visible; height:auto` | `simos-app/src/main/resources/webui/styles.css` | `a-timeline-in-viewport`（连带 `a-no-page-scroll`/`a-left-column-scrolls`/`b-knob-in-viewport`/`b-knob-hit-target`/`b-knob-drag-changes-rev`） | 杀 |
| m2 | B | `timeline.js`：pointerdown 把手只留 `closest(".timeline-line")`（去掉 `.tl-node`/`.tl-knob`） | `simos-app/src/main/resources/webui/timeline.js` | `b-knob-drag-changes-rev`（`b-knob-in-viewport`/`b-knob-hit-target` 仍 PASS） | 杀 |
| m3 | C | `timeline.js`：`onCreate` 去掉 `await refresh()` | `simos-app/src/main/resources/webui/timeline.js` | `c-tick-advances-r1`（连带 `c-create-not-stuck-disabled-r1`/`c-node-count-r1`） | 杀 |
| m4 | D | `map.js`：去掉右键成功提示的「推进时间」后缀 | `simos-app/src/main/resources/webui/map.js` | `d-route-hint` | 杀 |

- 每轮日志 `logs/mN.log` 末尾「装置补记」段记录本轮 `orig_md5 / pushed_src_md5 / pushed_cls_md5 / restored_src_md5 / restored_cls_md5 / server_log_mtime`（日志自指，均已核对）。
- **m1 顺带证明 B 是 A 的下游**：A 回退时 `b-knob-in-viewport`/`b-knob-hit-target`/`b-knob-drag-changes-rev` 同时红；m2 则相反（布局全绿、仅拖动红）——两个方向各自独立可杀。
- **m4 首轮作废**：端口 5814 被用户实例（pid 283796 `--approval-port 5814`，非本任务）占用 ⇒ `BindException` ⇒ 装置判「服务器没起」作废；换 5824 重跑，红点正确。作废日志存 `logs/m4-void-port.*`。
- 变异轮后各跑一次干净轮：`mut-runs/clean`（修后初验）、`mut-runs/clean-after-mutants`、`mut-runs/clean-final`，**均 PASS**；还原后源/classpath 与 `mutants/orig/*` 逐字节相等（md5 实测）。

---

## 五、门禁

| 项 | 值 |
|---|---|
| `spotless:apply` | 无改动（webui 非 Java，不受 spotless 触及） |
| 定向 `WebuiAssetsTest` | **8/8**，报告 mtime 13:16:17（落本轮内） |
| `./mvnw clean verify` | rc=0、**841** = 170/255/45/131/154/86、BUILD SUCCESS、`[ERROR]` 0、`BugInstance size is 0` ×6 |
| delta | **0**（纯前端，符合预期） |
| e2e | 19 断言 0 失败 + 5 截图；`z-no-page-errors PASS` |

---

## 六、偏离候选（与派单不符/需指出之处）

1. **★ B 不是独立缺陷**——派单把 B 列为独立缺陷并怀疑 `closest`/`pointer-events`。实测：`timeline.js` 的 pointerdown **本就**含 `.tl-knob`；knob 在可见时能拖（`probe-b`/`probe-b2` rev 4→2）。B 的"拖不动"是 **A 的下游**（knob 落视口外、`elementFromPoint=null`）。未改拖动逻辑，仅由 A 修复。
2. **C 的"tick 不刷新"不是 tick 值不刷新**，而是**瞬态滞后 + 可能停到 5s 轮询**：修后顶栏/底栏在写后立即正确。派单 §1 的"哪个显示没跟上"实测答案是：`#timeline-meta`（底栏）的 `head`/节点列表会滞后于 `#shell-state`，且写后瞬态把「创建节点」置灰。
3. 派单建议的 B 变异「加 `pointer-events:none` 到 `.tl-knob`」**杀不掉**——事件会落到其下的 `.tl-node`（同属拖动把手），拖动照常。故改用「把手只留 `.timeline-line`」的变异（圆环不在任何行内 ⇒ 拖动不启动），这才是判别该性质的正确变异。

---

## 七、我未能核实的

- **真实人类鼠标/触屏**：全程为 headless Chromium（`chromium-1234`）合成指针事件；触摸（`touch-action`）与真实设备 DPR 未测。
- **首帧/慢机上的瞬态窗口长度**：`probe-c` 在本机 headless 测得滞后约 80–160ms；真实慢机/真实浏览器上窗口多长未测，只能确认逻辑上已消除（`currentHeads` 同步 + `refreshQueued`）。
- **`clean-after-mutants` 的 server.log**：该轮用旧版 e2e（未含 D 截图滚动）跑过一次；`clean-final` 用最终 e2e 重跑（19 断言 PASS）。两次断言集合相同，仅 D 截图取景不同。
- **其他视口尺寸**（移动端/超窄屏 <1100px 单栏）下的 A：只在 1280×800 实测；`@media (max-width:1100px)` 单栏分支未跑 e2e。
- **`#timeline-meta` 的 tick 与 `state.heads` 在同一次 refresh 内的时间序**：修后 e2e 断言「≤该轮内正确」，未做微秒级时序取证。

---

## 八、证据索引

```
m7d-t1-evidence/
├── e2e/e2e.cjs、e2e/run-e2e.sh              # 19 断言 + 截图装置（真 ShellMain --demo + Playwright）
├── repro/                                    # 复现探针：repro(A/C/D 基线) / probe-b(高视口隔离 B) /
│                                             #   probe-b2(滚到底 B) / probe-c(C 时延采样)
├── logs/
│   ├── clean-final.log                       # ★ 修后最终干净轮（19 PASS）+ .server.log
│   ├── clean.log / clean-after-mutants.*     # 修后初验 / 变异后干净轮
│   ├── m1.log … m4.log + .server.log         # 4 变异轮（含"装置补记"自指段）
│   ├── m4-void-port.*                        # 端口占用作废轮（5814 属用户实例）
│   └── full-verify.log                       # ./mvnw clean verify（841、ERROR 0、SpotBugs 0×6）
├── mut-runs/
│   ├── clean-final/                          # e2e-values.json + 5 截图（A/B 前后/C/D）
│   ├── clean/、clean-after-mutants/、m1…m4/  # 各轮 e2e 值 + 截图
├── mutants/
│   ├── m1/styles.css m2/timeline.js m3/timeline.js m4/map.js   # 四个变异体
│   ├── orig/ + orig/classes-resource/        # 原件与 classpath 备份（逐字节还原基线）
│   └── m7d-mut-round.sh                      # 九道门禁装置
└── m7d-t1-report.md                          # 本报告
```

**截图**（`mut-runs/clean-final/`）：`screenshot-A-longcontent-timeline-visible.png`（长内容 + 时间轴钉在视口底）、`screenshot-B-knob-before/after.png`（拖动前后）、`screenshot-C-after-creates.png`、`screenshot-D-route-hint.png`（提示文案 + 底栏 `head 2`）。
