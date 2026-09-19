# M7f T1 报告 —— 时间轴按 tick 分组（一个节点 = 一个 tick）+ 推进 N tick

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7ft1`，分支 `m7f/t1`
- 基线：`ce5186b`（M7e 关账）
- **纯前端改动，零 Java 改动**（`simos-app/src/main/resources/webui/` 三文件；Java/端点/依赖一门未动）
- 全量门禁与 M7e 同值 **841**（见 §三）

---

## 一、改了什么

| 文件 | 改动 |
|---|---|
| `webui/timeline.js` | ① `groupByTick` 把每分支节点按 tick 归并；② 列基准 `columnOfTick` 从 revision 改为 **tick 序号**；③ `tickNode` 渲染 tick 节点（内联明细 + `data-*` 断言锚）；④ 游标按 tick 区间高亮（`data-first-revision` ≤ rev ≤ `data-revision`）；⑤ 分岔连线改按 tick 区间找 parent 节点；⑥ `onCreate` 读「推进 N tick」输入（`readAdvanceSteps`），非法 N 提示且不发写 |
| `webui/index.html` | 底部动作区加 `<input id="timeline-advance-n" type="number" min="1" step="1" value="1">` +「推进 N tick」按钮（原 `#timeline-create` 保留 id，R2 断言不变） |
| `webui/styles.css` | `.tl-advance`（输入框）+ `.tl-detail`（tick 节点内联明细）样式 |

### 1. ★ 游标语义（点一个 tick 节点取该 tick 的最后一个 revision）

时间轴是**只追加**的 DAG（铁律 2：不能改写已落盘节点），故不能"为当前节点改单位移动状态"。改成按 tick 归并后，
**一个 tick 节点承载该 tick 内的全部 revision（命令）**。点这个节点时，游标落在 **`data-revision` = 该 tick 的最后一个 revision**：

- 左栏面板读到的是**该 tick 结束时的状态**（用户下完路线后，看到的正是路线生效后的态）；
- `data-first-revision` / `data-revision` 之间是「同一 tick」；
- **非末节点点击只改游标、不发写**（R1 不变）；
- 拖动 scrubbing 也取最近 tick 节点的末 revision。

**e2e 直证**：`f-tick5-cursor-is-last-revision` —— 点 main 的 tick5 节点后 `state.revision=2`，等于该节点 `data-revision=2`
（tick5 含 rev1 创世 + rev2 PlanRoute）。

### 2. ★ 列基准改为 tick 序号的理由

demo 的 tick 从 **5** 起。若直接把 tick 值当列号，`tick 5` 会落在第 5 列，前面留 4 个空列（浪费且看不出因果）。
故列 = **tick 组在分支内的出现顺序（1 起）**：`x = LABEL_WIDTH + (序号 − 1) × COL_WIDTH`。

**分岔对齐不破**：非 main 分支的**首个 tick 节点**对齐其 parent 所在 tick 节点列（`columnOfTick` 对非 main
分支 `= columnOfTick(parent分支, parentTick) + 组内序号`）。`parentTick` 由 `tickByRevision[parent.branch][parent.revision]` 取。
实测见 §二 e 步：`child.styleLeft == parent.styleLeft == "292px"`。

### 3. tick 明细的可断言形式

每个 `.tl-node`：

| 属性 / 内容 | 值 |
|---|---|
| 可见文案 | `tick 5 · 2 条命令`（单命令时 `tick 5 · Bootstrap`） |
| 悬停 `title` | `tick N（M 条命令）` + 逐行 `短命令名 · rev K · initiator` |
| `data-tick` | 该 tick 值 |
| `data-revision` | 该 tick **最后一个** revision |
| `data-first-revision` | 该 tick 第一个 revision |
| `data-count` | 命令条数 |
| `data-commands` | 命令类型全名逗号连接（`core.Bootstrap,unit.PlanRoute`） |

---

## 二、e2e 实测值（真 `ShellMain --demo`，全新 store，Playwright/Chromium）

日志：`logs/clean-after-mutants.log`（最终字节；`logs/clean.log` 同值）。全套 **40+ 断言全 PASS，`e2e_rc=0`，零 pageerror**。

| 步 | 断言 | 实测 |
|---|---|---|
| setup | 初始 1 个 tick 节点 | `[tick 5, count 1, commands "core.Bootstrap"]` |
| setup | 选中 u-1 | `{kind:"unit", id:"u-1"}` |
| **a** | 下路线**不新增节点** | `before 1 → after 1`（`a-node-count-unchanged` PASS） |
| **a** | 该 tick 明细 **+1 条命令** | `count 1→2`、`commands "core.Bootstrap,unit.PlanRoute"`、文案 `tick 5 · 2 条命令` |
| **a** | head 推进 + 只发 1 条写 | `head 1→2`、非 GET `0→1` |
| **a** | `/api/timeline` tick 集合不变 | `[5] → [5]` |
| **b** | 推进 1（默认）⇒ 节点 +1 | `1 → 2` |
| **b** | 新节点 tick == 旧末端+1 | `tipTick 5 → 6`（DOM 与 API 一致） |
| **b** | head +1；新节点是 `core.AdvanceTime` | `head 2→3`；`"core.AdvanceTime"` |
| **c** | 推进 **N=100** ⇒ 节点 +1 | `2 → 3` |
| **c** | ★ 新节点 tick == 旧 + 100 | `tipTick 6 → 106`（`/api/timeline` 读回 `lastTick=106`，DOM `data-tick=106`） |
| **c** | head +1；状态文案 | `head 3→4`；`已推进 100 tick（6 → 106）` |
| **d** | 非法 N=0 / -5 / abc ⇒ 提示、**不发写**、状态不变 | 三次均 `enabled=true`、status `推进步数必须是 ≥1 的整数（当前：0 / -5 / 空）`、`nonGet 3→3`、`nodes 3→3`、`head 4→4` |
| **e** | 分岔对齐（tick 列基准） | `parentTick 106`：`parentStyleLeft "292px" == childStyleLeft "292px"`、`centerX 323 == 323` |
| **e** | 子分支首节点 tick == parent tick | `childTick 106 == 106` |
| **e** | `.timeline-line` 每分支一条；fork 连线存在 | `linesE=2`、`linksE=1` |
| **f** | R2 非末端：推进/分岔**禁用** | 点 tick5 后 `disabledCreate=true`、`disabledFork=true` |
| **f** | R1 拖动**只读**且 rev 变 | `rev 2 → 4`（main head）、非 GET `4→4`（无写） |
| **f** | R2 末端：两按钮**启用** | `disabledCreate=false`、`disabledFork=false` |
| **f** | 零 pageerror | `[]` |
| **g** | R8 allowlist（打印清单） | `NON_GET_LIST=["/api/command","/api/advance","/api/advance","/api/fork"]`，`violations=[]`，三类各至少 1 条 |

截图（`element.screenshot()`，限定 `#timeline-bar`）：
- `screenshots/m7f-tick-detail.png`：同一 tick 两条命令的节点（可见 `tick 5 · 2 条命令`）。
- `screenshots/m7f-advance-100.png`：`tick 5 · 2 条命令` / `tick 6 · AdvanceTime` / `tick 106 · AdvanceTime`（末端高亮）+ 状态 `已推进 100 tick（6 → 106）`。

---

## 三、门禁

| 项 | 结果 |
|---|---|
| `./mvnw -q spotless:apply` | rc=0 |
| `-Dtest=WebuiAssetsTest`（`-pl simos-app -am`） | rc=0，`Tests run: 8, Failures: 0` |
| `./mvnw clean verify`（最终字节） | **rc=0**，`Tests run 841 = 170/255/45/131/154/86`（**delta 0**），7/7 模块，`BugInstance size is 0` ×6，`[ERROR]` 0 |
| 基线 | 841 = 170/255/45/131/154/86（与 M7b/M7c/M7d/M7e 一致） |

日志：`logs/full-verify-final.log`（最终字节）、`logs/full-verify.log`（加 `·` 分隔符之前）、`logs/targeted.log`。

---

## 四、变异（3 轮，九道门禁；装置 `mutants/m7f-mut-round.sh`）

装置形态：资源类（**源 + classpath 两份推送**、逐字节还原、**日志自指**（`mutant_md5` / `pushed_*_md5` / `restored_*_md5` 写进日志本身）、
红点落被保护断言、**先断言聚合 md5 非空**、**先 `node --check` 断言变异体语法有效**）。

| m | 护栏 | 变异 | 期望红步 | 实测红步（`logs/mN.log`） | 结果 |
|---|---|---|---|---|---|
| **m1** | 同 tick 归并 | `groupByTick` 的 key 由 `tick` 改回 `revision`（一条命令一个节点） | `a-node-count-unchanged` | `a-node-count-unchanged`,`a-detail-plus-one`,`a-command-is-planroute` | ✅ 杀死 |
| **m2** | 推进 N 生效 | `advance(..., tick + steps)` → `tick + 1`（忽略输入 N） | `c-new-tick-is-old-plus-100` | `c-new-tick-is-old-plus-100`（唯一红：`tipTickC=6 → apiLastTick=7`） | ✅ 杀死 |
| **m3** | 分岔对齐（tick 列基准） | `columnOfTick` 非 main 分支直接 `index + 1`（不对齐 parent 列） | `e-fork-aligned` | `e-fork-aligned`（唯一红：`childStyleLeft "72px" != parent "292px"`） | ✅ 杀死 |

- 三轮 `DEVICE_RC=0`、`e2e_rc=1`（装置自证真红）；装置补记已写入各自日志（含 `orig_md5 / mutant_md5 / pushed_src_md5 / pushed_classes_md5 / restored_src_md5 / restored_classes_md5`）。
- 变异体 md5：m1 `3259f138…`、m2 `06bc1eaa…`、m3 `49a23319…`（均 ≠ 原件 `2b63de52…`，装置已断言）。
- **还原自证**：三轮后源与 classpath 资源 md5 均 = `2b63de5261f6451573bcb7e403415a98`；随后 `clean-after-mutants` e2e **全 PASS、rc=0**（`logs/clean-after-mutants.log`）。
- m2/m3 各自只命中单一断言（判别力精确）；m1 连锁红在 a 步的三条同源断言，属预期。

---

## 五、我未能核实的

1. **「展开」只看悬停 `title`，无可点击展开的 DOM**：本单说"展开/悬停可见"，实现只做了悬停 `title`（原生 tooltip）+ `data-commands` 断言锚。**tooltip 的实际渲染未做像素/截图核验**（只断言了 `data-commands` 与可见文案）。
2. **非 main 分支上的分组与列序**：a/b/c 步只在 `main` 上验证；`b2` 只验证了**首个 tick 节点对齐**，未在 `b2` 上再下路线或推进 tick（其后缀列递增未真跑）。
3. **超大 N（如 10^9）未测**：本单要求 N=100；更大值理论上仍是单 revision 跳 tick，但未验证后端边界与浏览器显示。
4. **`input[type=number]` 的原生值规整边界未穷举**：`readAdvanceSteps` 用 `Number()`，`"1e3"` 会被接受为 1000（**已知放宽**，未被 e2e 覆盖，也未在 UI 文案里说明）。
5. **「看到某 tick 内部中间状态」无入口**：游标恒取 tick 末 revision（设计如此），故中间 revision 只能靠其它入口；未测其可达性。
6. **拖动只在 main 单次拖到末端触发**：跨分支拖动、多分支同时存在时的 `lineNearest` 选择未穷举。
7. **前端护栏仍不进 Maven 门禁**（M7 的系统性开口项）：本轮护栏靠 e2e 装置观察，非 CI 常驻。

---

## 六、证据索引

```
m7f-t1-evidence/
├── m7f-t1-report.md                          ← 本文件
├── e2e/run-e2e.sh, e2e/e2e.cjs               ← 装置（真 ShellMain --demo + Playwright，a~g 40+ 断言）
├── logs/
│   ├── full-verify-final.log                 ← 最终字节全量门禁（841）
│   ├── full-verify.log, targeted.log
│   ├── clean.log, clean-after-mutants.log    ← 干净轮（含装置自指本轮的 md5）
│   ├── m1.log, m2.log, m3.log                ← 各含「装置补记」自指段
│   └── *.server.log
├── mut-runs/
│   ├── clean/                                ← e2e JSON（a~g）+ 两张时间轴截图
│   ├── clean-after-mutants/                  ← 还原后复跑（PASS）
│   └── m1/ m2/ m3/                           ← 各轮 e2e JSON + 截图
├── mutants/
│   ├── m7f-mut-round.sh                      ← 九道门禁装置
│   ├── orig/timeline.js + orig/classes-resource/timeline.js
│   └── m1/ m2/ m3/timeline.js                ← 3259f138… / 06bc1eaa… / 49a23319…
└── screenshots/m7f-tick-detail.png, m7f-advance-100.png
```

- 最终源 == classpath 副本 md5：`2b63de5261f6451573bcb7e403415a98`
- 变异体 md5：m1 `3259f138637f9c4d23cf1e25b60b6cd5`、m2 `06bc1eaa39362a58abcc48e92a7c55ac`、m3 `49a2331939096d659d83f73383728ad4`

---

## 七、与本单/源码的出入（按"以源码为准"）

- 本单 §1 的"一个节点 = 一个 tick / 同 tick 归并 / tick 变化才长新点"与实现一致。
- 本单 §2 的列公式 `x = 左内边距 + (tick序号 − 1) × 列宽` 与实现一致（左内边距 = `LABEL_WIDTH=72`）。
- 本单 §4 的"`from` 取当前末端 tick、`to = from + N`"与实现一致（`onCreate` 用 `model.tickAtHead[分支]`）。
- 本单 §5 表 m1 期望红"A 步节点数不变"与实测红步 `a-node-count-unchanged` 一致。
- **未发现与源码矛盾之处。** 唯一需点名的是：本单把「展开/悬停」并列为可选，实现只落**悬停**（见 §五.1）。
