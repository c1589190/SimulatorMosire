# T7 报告 —— 决策模式（一模式两子页）+ 决策人交互

> 分支 `wsf/t7`，基线 = **`62a0184`**（T6 合并后的 HEAD）。工作目录 = worktree
> `/home/cna/SimulatorMosire/.claude/worktrees/wsf-t7`（**不在主树**）。
> 依据：计划 `docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` §T7；spec
> `docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` §四（**决策模式与三件事模型**）、§〇.1 D4；
> research §B.1/§B.3/§B.6/§C.1/§C.5。
> 本文只写 T7，不越界 T8+。

---

## §〇 交付摘要

- 新增**第六模式「决策」**（id `decision`，`writes: []` ⇒ 只读 fail-closed），**下挂两个子页**：
  A「决策人查看」/ B「审批」。
- **三处交互**（用户原话逐条兑现）：
  1. **国家决策人** ⇒ 点中国家区域内的格 ⇒ 地图高亮**该国 tag 的全部区域**（C12）⇒ 左栏显示该国决策人信息（C13）。
  2. **单位决策人** ⇒ 选中**有决策人的单位**（含其后代，沿 `parent` 链上溯）⇒ 左栏显示该军队决策人信息（C14）；
     无决策人 ⇒ 明确显示「无决策人」（不静默空白）。
  3. **右栏** = **全部决策人按类型分类排列**（国家 / 军队，组内按 id 字典序，长度守恒）（C15）。
- 子页 B「审批」= `GET /api/approvals` 列表 + 批准/驳回（`POST /api/approvals/{id}`）。
- **零 Java 生产改动**；Java 只动测试 `WebuiAssetsTest`（六模式）。

### 文件清单（改动）

| 文件 | 改动 |
|---|---|
| `webui/modes.js` | +`{id:"decision", label:"决策", writes:[]}`；五→六的注释与计数 |
| `webui/index.html` | 模式栏按钮；左栏 `<section data-modes="decision">`（子页控件 + 详情 + 审批）；右栏 `<div data-modes="decision">`（分类列表 + 审批列表） |
| `webui/api.js` | +`decisionMakers` / `decisionMaker` / `cachedDecisionMakers`（只读）+ `approve`（`POST /api/approvals/{id}`，**非命令写**） |
| `webui/app.js` | 状态 +`decisionSubpage` / `decisionMakerFocus`；setter；`applyDecisionSubpage`（委托 panels.js 的 fail-closed 纯函数） |
| `webui/panels.js` | 决策模式纯函数（子页 / 分组 / 国家·单位解析 / 待决文本 / 字段投影）+ 左栏详情 + 右栏分类列表 + 审批列表 |
| `webui/map.js` | `nationTagOf` / `nationRegionIds` / `nationIdsOfRegions`（纯函数，C12）+ `selectNationOfHex` + `workbenchSelect` 接线 + `decisionModeDebug` |
| `webui/styles.css` | 决策模式样式（子页、分组、列表项、审批项） |
| `test/java/.../gui/WebuiAssetsTest.java` | `MODE_LABELS` +「决策」；`allFiveModes…` → `allSixModes…` |
| `test/js/modes.test.cjs` | 六模式 + 决策只读断言（+1 条） |
| `test/js/decision-mode.test.cjs` | **新文件**，23 条（纯函数 + render 流水线夹具 + 静态） |
| `test/js/write-allowlist.test.cjs` | 审批端点**逐条精确列出**（`/api/approvals/` 前缀 + 非空 id）+ 精确性自证 |
| `test/js/gate-contract.test.cjs` + `run-gate.cjs` | `REQUIRED_FILES` + **两处下界 114→138** |
| `test/js/map-edit-suboptions.test.cjs` | 模式按钮数 5→6（子选项仍不是新模式） |

---

## §一 判据逐条实测值（spec §七.3）

| # | 判据 | 实测 |
|---|---|---|
| **C10** | 模式栏**恰 6 个**按钮含「决策」；`modes.test.cjs` 白名单与 `writes` 一致 | `modes.test.cjs:mode-ids-order` ⇒ `["view","region","map-edit","region-edit","unit","decision"]`；`mode-labels` ⇒ `decision → "决策"`；`decision-allows-no-write` ⇒ `allowedWrites("decision") == []` 且 6 条命令（含 `sd.StartDecision`/`sd.IssueDirective`/`sd.SetViewScope`）**全部 false**；`decision-mode.test.cjs:index-html-has-six-modes-and-decision-panel` ⇒ 模式栏按钮数 **6** + `data-mode="decision"` |
| **C11** | 两个子页切换控件；A ⇒ 决策人查看、B ⇒ 审批 | `DECISION_SUBPAGES` = `[view→决策人查看, approval→审批]`；`subpage-visibility-is-mutually-exclusive` ⇒ 恰一个 true、未知 id ⇒ **两个都 false**（fail-closed）；`index.html` 有 `#decision-subpages` + `name="decision-subpage"` + 两个 `data-decision-subpage` 容器 |
| **C12** | 选中一个国家 tag ⇒ 高亮区域集合 = **该 tag 的 Region 集合（逐值相等，不是子集）** | `nation-region-ids-are-set-equal-not-subset` ⇒ `nationRegionIds(REGIONS,"nation:n1") == ["r-a","r-b"]`（**长度 2**，且 `notDeepEqual … ["r-b"]` 直接钉住"只高亮第一个"是错的）；`nation-ids-of-regions-unions-distinct-nations` ⇒ 一格多属 ⇒ `["n1","n2"]`；`map-js-wires-decision-click-to-nation-highlight` ⇒ `workbenchSelect` 在 `decision` 且视图子页时调 `selectNationOfHex` |
| **C13** | 选中某区域 ⇒ 左栏出现该国决策人信息（字段非空、与 `GET /api/sd/decision-makers/{id}` 逐值一致） | `decision-maker-fields-project-the-server-shape` 钉住字段投影（id/归属/cadence/allowedTools/viewScope 6 项/待决）；`render-left-shows-nation-decision-maker-for-a-nation-region-hex` ⇒ 真 `renderDecisionLeft` 产出 `dl[data-decision-maker-id="dm-nation-a"]` |
| **C14** | 有决策人的单位 ⇒ 左栏显示该军队决策人；无 ⇒ 明确「无决策人」 | `decision-maker-for-unit-resolves-root-and-descendants` ⇒ 根 `u-root`、后代 `u-child`/`u-grand` 都解析到 `dm-army-a`；`u-lone`/未知 id/无军队 ⇒ `null`；`render-left-shows-army-decision-maker-for-a-descendant-unit` ⇒ `dl[data-decision-maker-id="dm-army-a"]`；`render-left-shows-no-decision-maker-for-an-unrelated-unit` ⇒ 文案含「无决策人」 |
| **C15** | 右侧按类型分类：国家一组、军队一组；组内 id 字典序；长度 = 服务端返回长度 | `decision-groups-put-nation-then-army-and-sort-by-id` ⇒ kinds `["nation","army"]`、labels `["国家","军队"]`、组内 `["dm-nation-a","dm-nation-b"]` / `["dm-army-a","dm-army-other","dm-army-z"]`、**总长 == 输入长**；`decision-groups-keep-unknown-kinds-and-preserve-length` ⇒ 未知 kind 落「其它（kind）」桶、**不丢条目** |
| **C31** | 门禁 | 见 §二 |
| **C32** | 两处下界同改 + 新文件进 `REQUIRED_FILES` | `MIN_TESTS=138` / `MIN_ASSERTIONS=138`（两处同值）；`decision-mode.test.cjs` 进 `REQUIRED_FILES` |
| **C33** | 新增/改动 catalog 命令 ⇒ `McpCoverageTest` | **不适用**：T7 零命令注册（决策模式 `writes: []`、不新增 catalog type） |

### 三件事模型（spec §四.1）的落地边界

| 步 | 归属 | T7 的处置 |
|---|---|---|
| ① 推进一 tick | 既有（`timeline.js` → `/api/advance`） | **未动** |
| ② 本 tick 哪些决策人**理论上有待决事项** | **T9** | **未实现**。展示位已留（左栏「待决状态」行），`due` 取不到 ⇒ **恒显示「—」**（`pending-status-text-never-fabricates` 钉住"不拿 false/空串顶替"） |
| ③ 列表显示 | T7 | 已实现（右栏分类列表） |
| ④ 「开始决策」按钮 | **T10** | **未实现**（无按钮、无行为）；`index-html-decision-mode-has-no-start-decision-button` 断言 `sd.StartDecision`/「开始决策」**不在** index.html |
| ⑤ 决策人 Agent 异步出令 | 既有渠道 / T10 | **未动** |
| ⑥ 落 revision | 既有 | **未动** |

**「建议」≠「开始决策」≠「决策人出令」**：T7 只做 ③ 与 A 子页的三处交互。

---

## §二 门禁（★ 现场重算，不引用任何文档的现成数字）

- **`./mvnw clean verify` rc=0**；日志 `t7-evidence/logs/clean-verify-final.log`（`md5=3f8a0cbecd224480fd2b45dc8eb1db79`），rc 落 `clean-verify-final-rc.txt`。
- **8/8 `SUCCESS [`**（父 POM + 7 模块）——`module-summary-lines-final.txt`：
  `SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp`。
- **用例总数 1324 = `170/368/45/259/178/124/180`**（只取**模块汇总行**，避免逐类行双重计数）。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**。
- 前端 `[frontend-gate] OK tests=138 pass=138 fail=0`。

### 增量（★ 基线本树实测，非推导）

基线 = `62a0184`，在临时 detached worktree `/tmp/opencode/wsf-t7-base` 实测
（`/tmp/opencode/baseline-verify.log`，rc=0）：**1324 = `170/368/45/259/178/124/180`**，
`BugInstance ×7`、`[ERROR]` 0、前端 **114/114**、模块 `SUCCESS [` **8**。

| 项 | 基线 | T7 | 增量 |
|---|---|---|---|
| Java 用例 | 1324 | 1324 | **0**（T7 零 Java 生产改动；`WebuiAssetsTest` 用例数 8→8） |
| 前端用例 | 114 | 138 | **+24**（`decision-mode.test.cjs` +23、`modes.test.cjs` +1） |
| 模块 SUCCESS | 8 | 8 | 0 |

### 尝试次数（照纪律"记门禁结果必须一并记第几次尝试"）

| 轮 | 用途 | rc | 备注 |
|---|---|---|---|
| 0 | **基线实测**（临时 worktree） | 0 | `/tmp/opencode/baseline-verify.log` |
| 1 | 实现后首跑 | 0 | `logs/clean-verify.log`（此轮时前端 135/135） |
| 2 | 变异轮前复跑 | 0 | `logs/clean-verify-after-mutants.log`（135/135） |
| 3 | **最终字节**（加 render 夹具、下界 138 后） | 0 | `logs/clean-verify-final.log`（138/138） |

★ 本机 `nproc=8`，全量门禁约 60s，**无被杀轮**（`nproc=2` 机器上的"摘后台/被杀"结论不适用本机）。

---

## §三 变异（11 轮 11 KILLED / 0 存活）

装置：`t7-evidence/mutants/mut-js.sh`（前端 10 轮，九道门禁的前端版）+ `mut-java.sh`（Java 1 轮）。
每轮：干净世界（从 `pristine/` 恢复）⇒ 记 `orig_md5` ⇒ 精确替换 ⇒ 断言 `mutant_md5 != orig_md5`
⇒ `node --check` 断言变异体**能解析**（不解析 ⇒ VOID，不把"没跑到"当红）⇒ 跑全量 `node --test`
⇒ KILLED 判定 = rc≠0 **且**期望的受保护用例名出现在 `not ok` ⇒ `cp` 逐字节还原并断言
`restored_md5 == orig_md5` ⇒ 把三个 md5 **追加进日志本身**（自指）。逐轮记录 `logs/mut-js.log` / `logs/mut-java.log`。

| 变异体 | 靶子 | 期望受保护用例 | 结果 | 红点 |
|---|---|---|---|---|
| t7m1 | `panels.js` `decisionMakerGroups` 混排（单桶） | `decision-groups-put-nation-then-army-and-sort-by-id` | **KILLED** | `not ok 11` |
| t7m2 | `map.js` `nationRegionIds` 只取第一个 | `nation-region-ids-are-set-equal-not-subset` | **KILLED** | `not ok 20` |
| t7m3 | `index.html` 去掉决策模式按钮 | `index-html-has-six-modes-and-decision-panel` | **KILLED** | `not ok 26` |
| t7m4 | `panels.js` 子页可见性两页同开 | `subpage-visibility-is-mutually-exclusive` | **KILLED** | `not ok 10` |
| t7m5 | `modes.js` 决策模式给 `sd.StartDecision` 写权限 | `decision-allows-no-write` | **KILLED** | `not ok 78` |
| t7m6 | `panels.js` `due=null` 显示「非待决」（造假） | `pending-status-text-never-fabricates` | **KILLED** | `not ok 17` |
| t7m7 | `panels.js` 单位解析不做祖先上溯 | `decision-maker-for-unit-resolves-root-and-descendants` | **KILLED** | `not ok 14` |
| t7m8 | `map.js` 去掉 `selectNationOfHex` 调用 | `map-js-wires-decision-click-to-nation-highlight` | **KILLED** | `not ok 28` |
| t7m9 | `app.js` 子页可见性不再委托纯函数 | `panels-js-and-app-js-delegate-subpage-visibility` | **KILLED** | `not ok 29` |
| t7m10 | `api.js` 审批端点改成 `/api/evil/` | `api.js-declares-exactly-the-allowed-write-endpoints` | **KILLED** | `not ok 132` |
| t7m11 | `index.html` 模式按钮标签 `决策`→`Decide`（**只让 Java 断言红**） | `WebuiAssetsTest.allSixModesAreEnabledRealControls` | **KILLED** | `[ERROR] … allSixModesAreEnabledRealControls -- … FAILURE!`（`WebuiAssetsTest.java:157`） |

- ★ **t7m11 的隔离设计**：把按钮文字换成 ASCII 后 **JS 静态断言仍绿**（左栏 section 里还有「决策」字样、
  按钮数仍 6、`data-mode="decision"` 仍在）⇒ 红点**只**落在 `WebuiAssetsTest`（Java），证明该 Java 断言
  对"`modes.js` 有模式、`index.html` 按钮标签对不上"这一漂移**有独立判别力**（不是靠另一条护栏替它红）。
- ★ **t7m3 是"新模式从模式栏消失"的靶子**；t7m5 是"决策模式被给了写权限"的靶子——两条都在。
- ★★ **装置自身的坑（当场发现并修，留痕）**：`mut-js.sh` 首版把 `file` 参数写成**裸文件名**
  （`panels.js`）而非 `$SRC/panels.js` ⇒ 变异体被写到**证据目录**、真源**一个字节没动** ⇒ 10 轮全
  `SURVIVED`（假存活）。这正是 CLAUDE.md「把没发生伪装成没发生」同族：**被测对象不是我以为的那份字节**。
  修法 = 传绝对路径；并在日志里**自指**每轮的 `orig_md5/mutant_md5/restored_md5`（本轮日志可查）。
  修后 10/10 KILLED。★ 该轮次**已在 `logs/mut-js.log` 中重写**（同一文件名、只有一份记录），
  首版假存活的日志未留档——**如实披露**：那次没有产出有效证据，故不值得存档。
- ★ **`panels.js` 在首轮变异后被改过**（新增 `renderDecisionLeft`/`renderDecisionRight` 导出）⇒
  按「被测文件改动 ⇒ 旧证据作废」**刷新 pristine 并重跑全部 10 轮**（`pristine-md5.txt` 里 `panels.js`
  已变为 `29019a49…`）；t7m11 亦在最终字节上重跑。所有 `restored_md5 == orig_md5` 逐字节。

---

## §四 写权限与安全边界（★ 逐条）

- **决策模式 `writes: []`**（只读，fail-closed）；`isWriteAllowed("decision", …)` 对任何命令恒 false
  （含 `sd.StartDecision`/`sd.IssueDirective`/`sd.SubmitVerdict`/`sd.SetViewScope`）。
- **审批子页的写是"审批裁决"，不是命令写**：`POST /api/approvals/{id}`（体 `{decision,scope,by}`）。
  它**不进** `modes.js` 的命令白名单（那只管 `Command` 类型），而是作为**单独一条**显式列在
  `write-allowlist.test.cjs` 的 `ALLOWED_APPROVAL_PREFIX = "/api/approvals/"`，运行期 URL 另要求
  **前缀 + 非空 id**；`scanner-has-teeth-on-undeclared-endpoint` 用 `/api/approvals`（无斜杠）/
  `/api/approvals/`（无 id）/`/api/approvalsx/` 三个**反例**证明前缀规则**不是通配**。**未放宽 `isWriteAllowed`**。
- **数据来源走 T5 的只读查询面**（`/api/sd/decision-makers`、`/api/sd/decision-makers/{id}`）；
  **不在 app 层内省 sd 内部结构**（铁律 3）——前端只消费 JSON。
- 铁律 2：决策模式**不产生**任何 `Command → ChangeSet → Revision` 写；审批裁决走既有 AgentLib 审批面。

---

## §五 我未能核实的（诚实清单）

1. **无真实浏览器 e2e**。本机 `~/.cache/ms-playwright` 只有 `chromium-1234`，而可用的 playwright
   是 1.63 / 1.64-alpha（分别要 revision **1243/1246**）⇒ `launch()` 报 executable 不存在，
   无匹配 revision。**未下载浏览器**（网络/体积）。⇒ C12/C13/C14 的**交互**由
   （a）纯函数、（b）**真 `renderDecisionLeft` + 替身 app/api 的 node 夹具**、（c）静态接线、
   （d）Java 资产断言 覆盖；**"真浏览器里点一下"这一环未做**。
2. **未在真实 sd 世界（带真 nation/army/决策人的档）上验**。所有夹具是合成小图 + 合成决策人；
   T5 的查询面已端到端验过，但 T7 的 UI 侧只对**形状**（JSON 字段）负责。
3. **国家 ⇒ 区域**的口径按 `RegionMeta.tag == "nation:<id>"`（R13 + `NationTag`）实现；
   `Nation.homeRegion` **未使用**。若某国区域**不带**该 tag、或一个 hex 的多属区域跨多个国家，
   行为是"高亮全部命中 tag 的区域"/"多个国家决策人一起显示"——**未在真档上对拍**。
4. **`decisionMakerForUnit` 的"后代也算有决策人"是设计选择**（沿 `parent` 链上溯，与单位树同口径）。
   spec 只写了"选中**有决策人的单位**"，未明说后代；**未取得上游逐字依据**（记为选择，非裁决）。
5. **审批列表的实时性**：只在进入子页 / 提交后重取，**无轮询**（通知栏另有 5s 轮询）。
6. `decisionModeDebug` / `decisionDebug` 是只读投影，**未在浏览器里调用过**。
7. 子页切换的 DOM `hidden` 只由 JS 切；**CSS `:has(input:checked)` 的视觉选中态未在浏览器验**。
8. 触摸 / HiDPI / 第二视口未测（沿 M8 遗留）。

---

## §六 带裁定的遗留

| # | 条目 | 裁定 |
|---|---|---|
| L1 | 「后代单位也算有决策人」 | **记为设计选择**（无 spec 逐字依据）；若上游要"只有根单位算"，改 `decisionMakerForUnit` 一处 + 一条用例 |
| L2 | 子页 B 的写 = 审批裁决 | 明确**不是**命令写；已在 write-allowlist 逐条列出。若 T10 引入 `sd.StartDecision`，须按 §四 同样方式显式登记（**不得**放宽 `isWriteAllowed`） |
| L3 | `due` 恒「—」 | T9 落地前**有意如此**；`pendingStatusText` 已把"null ⇒ —"钉死，T9 只需喂真值 |
| L4 | 无浏览器 e2e | 见 §五 1；归 T13 关账时可考虑补真浏览器跑（需匹配 revision 的 playwright） |
| L5 | `index.html` 的 `决策` 一词多处出现 | `WebuiAssetsTest` 的 `buttonTagsContaining` 只扫 `<button>` ⇒ 唯一命中模式按钮；已在 t7m11 隔离证明 |

---

## §七 证据索引（`t7-evidence/`）

```
t7-evidence/
├── t7-report.md                     本文件
├── logs/
│   ├── clean-verify.log             实现后首跑（rc=0，135/135）
│   ├── clean-verify-rc.txt
│   ├── clean-verify-after-mutants.log / -rc.txt    变异前复跑（135/135）
│   ├── clean-verify-final.log / -rc.txt            ★ 最终字节（rc=0，138/138，md5=3f8a0cbe…）
│   ├── module-summary-lines-final.txt              8/8 SUCCESS 逐行
│   ├── module-summary-lines.txt
│   └── pristine-md5.txt                            六个 webui 资产的基线 md5
└── mutants/
    ├── mut-js.sh / mut-java.sh                     装置（九道门禁）
    ├── py/t7m1..t7m10.py + _lib.py                 十个变异体的精确替换
    ├── pristine/{api,app,map,modes,panels}.js + index.html
    └── logs/
        ├── mut-js.log                              ★ 10 轮逐条（md5 自指 + verdict + 红点）
        ├── mut-java.log                            ★ 1 轮（Java 断言）
        ├── t7m1..t7m10.tap                         各轮 TAP 原文
        └── t7m11-java.log                          Java 轮 Maven 原文
```
