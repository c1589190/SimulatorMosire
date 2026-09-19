# M8 关账报告 —— 地图编辑写面（T5/T6 · T9 · T11 · T12）

> **口径来源**：`.superpowers/sdd/2026-09-19-map-edit/progress.md` 台账 + 各 `tN-evidence/` **原始产物** + 主树合并后 `./mvnw clean verify` 当场日志（`t12-evidence/logs/`）。
> ★ 本报告只写**实测值**，不写"通过"。

---

## 〇 诚实披露：三处口径更正 + 一处装置差一（先说清楚，免得读者按错的口径核）

**1. ★★ 「R1~Rn 点验」在 M8 的 spec 与计划里不存在。**
`R1~Rn` 只在**计划 T12 定义行**（`docs/superpowers/plans/2026-09-19-map-edit-simos-plan.md:159`）出现一次；M8 的 spec（`docs/superpowers/specs/2026-09-19-map-edit-simos-design.md`）里**没有任何 R 表**。带 R1~R8 的是 **M7**（`docs/superpowers/specs/2026-09-19-webui-design.md:152-159`）⇒ 这是**模板泄漏**。
⇒ **本报告按 M8 spec §〇.2「裁定汇总」的 10 条做点验**（见 §三），**并明说这是替代口径、不是 R 表**；不为了让格式对得上而临时造一张 R 表。
★ **计数更正**：本条最初写作「11 条」，逐行数 spec §〇.2 的表**实为 10 行**（M8-U1 / S4 / S5 + Q1~Q7）——以 **10** 为准。

**2. ★ 「把裁定记入 `.serena/project.yml`」这条待办的前提是错的。**
实测：该文件当前唯一的改动是 **Serena 自身的版本重写**（`languages:` → `language_servers:`、注释块整体换版、新增一条 ignore 引号提示），**全文件零处「裁定」**（`git grep -c 裁定 -- .serena/` 无输出）。本仓记裁定的既有载体是三处：**spec §〇.2** / **`task-N-rulings.md`**（M2 期惯例）/ **HANDOFF §八**。
⇒ **不往配置里写裁定**：那是机器生成、随 Serena 升级整体重写的文件（写进去下次升级就没了），且把"配置"与"决策"混成一坨。裁定落在**本报告 §三** + spec §〇.2（原有）+ `CLAUDE.md`（载荷重的那几条）。
★ 附带澄清一段历史：`.serena/` 曾长期是**待用户裁决项**（"入库 / 加进 .gitignore / 不动"三选一，见 `2026-09-16-util-simos-plan/progress.md:1094-1096`）；**该文件现在是入库状态**（`git ls-files` 可见），那个三选一问题**已经由既成事实解决了**，不需要再问。

**3. ★ HANDOFF §一「`feat/adr1-core-scope` == `main` == `origin/*`」对 `main` 记错了。**
实测（2026-09-20）：

| ref | 值 | 说明 |
|---|---|---|
| 本地 `main` | `f5c8485`（2026-09-16） | = `origin/feat/m1-util-simos`，**M1 期的遗留**，**落后干线 316 个提交** |
| `feat/adr1-core-scope`（检出中） | 合并前 `73c5e89` → 合并后 `fbd09ee` | 干线；上游是 `origin/feat/adr1-core-scope` |
| `origin/main` | `73c5e89` | 与 `origin/feat/adr1-core-scope` 同点；reflog 显示它是被 **fetch 快进**更新的（即**别人 push 的**），不是本分支推的 |

⇒ 合并**落在 `feat/adr1-core-scope` 上**（push 目标是它的上游 `origin/feat/adr1-core-scope`）。**本地 `main` 这个落后分支没有被使用、也没有被改**（保持原样，留给用户处置：删掉或指过去，都是用户的选择）。

**4. 装置差一：计划 T12 行写 `BugInstance size is 0` ×7，实测 ×6。**
7/7 模块 SUCCESS（父 `SimulatorMosire` + 6 个代码模块），但**父 pom 不产 `BugInstance` 这一行** ⇒ SpotBugs 的 `BugInstance size is 0` 只有 **6** 行。台账一直记的 ×6 是对的，**计划的 ×7 是差一**。本报告报实测值。

**5. ★ 撤回我自己的一条判断（"缺 `agg_expect` 对拍"）。**
写 §四 第 4 条时，我据 **A 的中间输出**（`tail -c` 切片的 JSONL，且是**更早一版装置**的首轮）判断「m1~m4 未打印 `agg_expect` ⇒ 这 4 轮只有 `consumed_agg` 单侧」。**查最终产物后撤回**：`t12-evidence/m8r-rerun/mutants/logs/{clean,m1..m8}-e2e.log` **9 个文件逐个人工确认**，**每轮都有 `agg_expect=` 与 `consumed_agg=` 各 1 行**，且逐轮相等（如 `m1-e2e.log:68` 实测 `agg_expect=6803e16292b51e5b796f7ba2049d24d6` == `consumed_agg=6803e16292b51e5b796f7ba2049d24d6`，同轮 `mutant_md5=9ba61676eea07bfbc89fcfa6dc62c3ea` ≠ `orig_md5=014ffd3a…`）。
⇒ **8 轮全部两侧对拍**，"装置缺口"**不存在**，M8-L10 **作废**。★ 教训与本仓通则同型：**"缺行"也可能是我自己的取样窗口造成的** —— 判"缺"之前要看**定稿产物**，不看中间输出。

**6. ★ 门禁是第三次尝试才拿到的：前两次是被**杀**（既不是红、也不是绿）。**
本报告 §一⑦/§二 要引的门禁，是**在干线树 `fbd09ee` 上当场跑**的 `./mvnw clean verify`。attempt 1（后台任务 `bfmrkxmqf`）跑到 simos-unit 的编译时被**内存守卫终止**（日志 25,296 B，**无 rc**）；attempt 2（后台任务 `b30ebx76w`）**启动同一秒**被杀（日志 **0 字节**）。两次都**不是构建失败**：被杀轮日志内 `OutOfMemoryError|Killed|BUILD FAILURE|[ERROR]` 命中 **0** 条。**attempt 3 改前台运行，一次通过 `rc=0`。**
⇒ 这属于**环境/通道**条件，不动摇判据的成色；但读者有权知道"这一条不是一次跑成的"，故列在此，并**逐档留痕不删**：`logs/clean-verify.attempt{1,2}-killed.log` + `…attempt1-killed.note.txt`（守卫通知逐字原文）。★ attempt-1 被杀时我正**并发**做全仓 IO，**有我的责任**。通则见 §二。

---

## 一 判据（spec §〇.1）逐条实测值

> 约定：每条给 **(a) 值 → (b) 出处 `文件:行` → (c) 缺口**。★ 凡引"旧字节"处，两个 md5 都写出来。

### 判据① 地图编辑端到端（选地形 + 拖一串 hex ⇒ 真 `Command → ChangeSet → Revision` ⇒ 重放后地形是新值、时间线多节点、`head` 前进）

| 面 | 实测值 | 出处 |
|---|---|---|
| **重放（真档）** | `replayByteIdentical=true`（同坐标两次 `replay` 的块表 `toString()` 逐字节相同） | `t3-evidence/probe/real-archive-probe.out:12`；`t3-report.md:69` |
| **真档地形直方图** | 改块 + 整体重切后 **+10/−10 算术对上**（负例三连不留 revision） | `t3-report.md`（M8 台账 T3 段） |
| **命令路径** | `map.SetTerrain` 走真 `SimosApp.writeCommand`；词表 fail-closed | `t3-report.md` |
| **浏览器层（当前字节）** | `g2-brush-not-stolen: PASS {"palette":["desert"],"target":"desert","posts":1,"payload":{"hexes":[…] ,"terrain":"desert"},"headBefore":8,"headAfter":9}`（**真写**：一条 `map.SetTerrain` ⇒ head 前进）+ `a8-preflight-zero-write: PASS commands=0` | ★ **T12 本轮当场跑**：`t12-evidence/logs/clean-e2e-t12.log:34`、`:13` |
| **浏览器层（当前字节）** | 工具选择器 **4 项**、参数默认 `hidden`、`f0-terrain-tool-restored`；拖一串 ⇒ **一条**命令（T9 轮 `a1`/`a2`/`f0`） | `t9-evidence/logs/clean-e2e-final.log` |

**★ 缺口（当前字节的浏览器层）**：「**重放后**地形是新值」在浏览器层**未找到** —— 重放是服务端概念，现有 e2e 装置**不做重放**；该面唯一的实测是 **T3 的真档 Java 探针**。「时间线多一个节点 / `head` 前进」在**地形**这条命令上没有浏览器层实测（`head 9→12` 那次是 T9 的**三条 `map.CreateRegion`**，见判据②）。
★ 另：真档是**副本**（原档 md5 `2348b9365e5b107945a305d06fad8fab`，跑前跑后同值）；**本机现在已无真档**（`/tmp/m6-import-verify/test_integration` 不存在）⇒ 该探针**不可原地重跑**。

### 判据② 区域编辑端到端（含重叠：新建区域与已有区域重叠 ⇒ 成功不报错 ⇒ `/api/map/hex` 的 `regions` 同时列出两者 ⇒ 重放后一致）

| 面 | 实测值 | 出处 |
|---|---|---|
| **重叠 = 允许** | `ownersAfter(-18_0)=[t4_overlap, test_annex_target, test_nation]` —— **三条**同时从属（真档副本） | `t4-evidence/probe/real-archive-probe.out:14` |
| **端点同时列出两者** | `API_JSON=status=200 {…"regions":["t4_overlap","test_annex_target","test_nation"]…}` + `apiListsBoth=true` | 同上 `:16`、`:17` |
| **重放后一致（真档）** | `replayRegionsByteIdentical=true` | 同上 `:18` |
| **字典序** | `GuiApiTest.java:256-258` `hasSize(2)` + `"r-1"` / `"r-3"` | 门禁轮 `t9-evidence/logs/full-verify-postmutants.log:1067` **27/27 绿** |
| **浏览器层（当前字节）** | `h0-hex-1-2-has-exactly-two-owners: PASS {"regions":["t9_a","t9_b"]}`（服务端自报 2 从属）+ `h1-three-regions-created-through-the-real-write-path: PASS {"posts":3,"headBefore":9,"headAfter":12}` | `t9-evidence/logs/clean-e2e-final.log`；★ **T12 本轮当场跑（当前字节）**：`t12-evidence/logs/clean-e2e-t12.log:37`、`:36` |

**★ 缺口**：① 浏览器层「**重放后**一致」**未找到**（同判据①，装置不做重放）；② **三元素字典序无断言**（当前只有 2 元素的 `r-1`/`r-3` 与 `t9_a`/`t9_b`；≥3 从属的字典序只在单元层 `RegionIndexTest.java:54-56` 的 `a,b,c` 证过）；③ 断言计数 **27**（实际 `GuiApiTest`）vs 台账曾写的 **26**，以 **27** 为准。

### 判据③ 多从属地基（M8-U1：重叠 hex 解析出**多个** region，不存在"谁赢"；`regions` 按 RegionId **字典序** ⇒ 同一状态两次输出**逐字节相同**）

**★ 本条在**当前字节 + 门禁内 + 端点层**有实测（不是"只有 T1 期"）：**

- `simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java:286-294` `mapHexRegionsAreByteIdenticalAcrossTwoCalls()`：
  `:291` `assertThat(regions).as("先断言聚合非空，别把空==空当成功").isNotNull();` / `:292` `isEqualTo(2)` / **`:293` `assertThat(second).as("同一 revision 两次响应逐字节相同").isEqualTo(first);`**（比的是**整份响应体**）
- 门禁轮实测绿：`t9-evidence/logs/full-verify-postmutants.log:928` `Running …GuiApiTest` → `:1067` `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`；该轮 `simos-app` 合计 **100**（`:1144`）——**当前字节**（T9 零 Java 改动之后）
- **≥3 从属真的到过 3**：真档探针 `t4-evidence/probe/real-archive-probe.out:14`（3 条）+ 真档副本浏览器 `t10-evidence/logs/clean-after-mutants-e2e.log:10` `hex(-18,0).regions=["t10_overlap","test_annex_target","test_nation"]` + 单元 `RegionIndexTest.java:54-56` `.as("3 个区域同盖一格 ⇒ 三条都在，字典序 a,b,c")`
- **不存在"谁赢"**：`RegionIndex` `hex→Set<RegionId>`（M8 T1）；`RegionIndexTest.java:57-59` `.as("结果与入参迭代序无关 ⇒ 逐值相同")`
- ★ **T12 本轮当场跑（当前字节）另外三项**：`t12-evidence/logs/clean-e2e-t12.log:38` `h0b-fixture-matches-the-hardcoded-expectations: PASS {"unionAB":3,"sumAB":4,"sharedAB":1,…,"fadeB":"#8a8adc"}`（**夹具与硬编码期望自洽**，含裁定 72.1 的并集/求和分界）+ `:39` `h2a-highlight-count-equals-regions-length: PASS {"highlighted":["t9_a","t9_b"],"regions":["t9_a","t9_b"]}`（UI 高亮集 == 服务端 `regions`）+ `:46` `h3b-total-is-the-union-not-the-summed-hexcounts: PASS {"union":"3","sumAB":4,…}`（**和 ≠ 并集**，裁定 72.1 的杀点）

**★ 缺口**：T9/T11 的 `--demo` **3 格**世界与 T1 真档交集（701/201）本身**只到 2** 从属 ⇒ 浏览器层的多从属高亮只在 2 上验过；≥3 的浏览器实测在 **T10 轮**（真档副本），不在 T9 那轮。
★ **台账三处仍是旧口径**（**实质同、字面只有一处**——我先前写成"三处都写'最多 2'"，**实测后按逐字更正**）：`t9-evidence/notes/t9-report.md:133` 逐字「端点与浏览器上最多 **2** 个从属」；`t1-evidence/t1-report.md:86` 逐字「**端点侧的 >2 从属只用合成夹具证过**（`RegionIndexTest` 3 区域、`GameMapTest` 3 区域），未在端点/浏览器上跑 >2」；`CLAUDE.md` 的 M8 行逐字「>2 从属只在合成夹具（`RegionIndexTest`/`GameMapTest` 各 3 区域）证过，未在端点/浏览器上跑 >2」⇒ **缺口是台账未随 T4/T10 更新，不是产物缺失**；本报告即更正。

### 判据④ `EdgeTags` 语义显式（`replace` 与 `merge` 两条都有实测，且 `merge` 不丢既有 tag）

| 面 | 实测值 | 出处 |
|---|---|---|
| **浏览器层 merge** | `s2-merge-keeps-existing-river: PASS {…"hex12Edges":[{"edge":"1_2|1_3","pathways":["river","road"]}]…}` | `t11-evidence/logs/e2e-clean-after-mut.log:17`（该轮 `map.js=f6eeed79…`，**旧字节**）；★ **T12 本轮当场跑 = 当前字节**：`t12-evidence/logs/clean-e2e-t12.log:18`（该日志抬头 `:1` 自记 `map_js_md5=014ffd3aac68dbf282d91e49365d4b0d`、`agg_md5=4294de6d…`）；T9 轮同含此步 |
| **浏览器层 replace** | `s3b-replace-stripped-the-kind-off-the-other-edge: PASS`（`pathways=["road"]`：road 一字不动、river 没了）+ `s3-replace-strips-that-kind-across-the-map` | 同上 `:19`、`:20`；★ **T12 本轮（当前字节）**：`t12-evidence/logs/clean-e2e-t12.log:20`（`s3b-…: PASS hex(1,2) 上 1_2|1_3 的 pathways=["road"]`）、`:19`（`s3a-…`）、`:21`（`s3-replace-…`，payload `mode:"replace"`） |
| **core-e2e（真命令路径）** | `simos-core/.../MapSetEdgeEndToEndTest.java:94` `assertThat(result).isEqualTo(new CommandResult.Committed(ref(2)));`；`:102-104` `assertThat(…EDGE_LEFT…byPathway())` + `.as("别的 kind 一字不动")` + `containsOnlyKeys("road")`；`:105` `containsOnlyKeys("river")` | 当前 HEAD |
| **门禁** | map 模块 **22** 条（`SetEdgeHandlerTest` 9 + `EdgeOperationsTest` 13）+ core **4** | M8 台账 T5/T6 段 |

**★★ 判别力（本条最有价值的一条）**：`t5-m2` 的**两轮 `orig_md5`/`mutant_md5` 完全相同**（`4c717d2d…`/`0b7c5bca…`），**只换夹具** ⇒ `protected_assertion_hits` 从 **2** 升到 **4**，且失败原文里**两条 ★ 方法名都在**（`MapSetEdgeEndToEndTest.mergeSurvivesTheRealCommandPathAndKeepsTheExistingTag`、`EdgeOperationsTest.mergeAddsTheNewTagAndKeepsEveryExistingTag`）。代码内自陈旧说明在 `simos-map/.../ops/EdgeOperationsTest.java:44-47`（核心句 `:45`「…**两种语义结果相同** ⇒ 用例成装饰」）。

**★ 缺口**：① **真档上的 `edges` 往返 = 未找到** —— T5/T6 自陈逐字：「本机实测**没有**真档…**因此本台账不对"真档上的 `edges` 往返"作任何断言**；M6 的开口项（`edges` 非空无真实样本）**依然成立**」（`t5t6-evidence/notes/t5t6-conclusion.md:156-163`）。② **浏览器层没有自己的变异杀点**：T11 六轮变异里 `s2`/`s3` 全程 PASS ⇒ 该层的判别力靠 Java 层 m2 的兑现 + 构造说明，不靠它自己红过。

### 判据⑤ 模式白名单（五个模式各自的**写权限可断言**；常规/区域查看模式**写不了**；**非 GET 清单按模式断言**）

**★★ 门禁层逐模式可断言（B 曾以 `git grep nonGet` 判"零断言"，那是正则漏了 —— `nonGet` 是 e2e 的字段名，门禁里管这事的是下面两个文件）：**

- `simos-app/src/test/js/modes.test.cjs`：`view-allows-no-write` / `region-allows-no-write` / `map-edit-allows-exactly-four-writes` / `region-edit-allows-three-writes` / `unit-allows-route-and-editor-writes` / `fail-closed-on-unknown-mode-and-empty-type` / `allowedWrites-returns-a-snapshot` ⇒ **五模式写权限逐模式断言 + 未知模式 fail-closed**
- `simos-app/src/test/js/write-allowlist.test.cjs`：`api.js-declares-exactly-the-three-allowed-write-endpoints`（静态扫 `postJson` == `{/api/command,/api/advance,/api/fork}`）/ `no-other-webui-asset-has-a-write-call` / `scanner-has-teeth-on-undeclared-endpoint`（**扫描器自带牙**）/ `dynamic-write-functions-hit-only-allowed-endpoints`（真加载 `api.js` + 记录型 fetch）/ `module-path-resolution-is-non-empty`（防"空==空"）
- **非 GET 清单按模式（浏览器层，当前字节）**：`t9-evidence/logs/clean-e2e-final.log:9` `LEFT_PAN_ALL_MODES [{"mode":"view",…,"nonGet":[]},…共 5 个模式]` + `:10` `a5-five-modes-left-drag-zero-write: PASS ["view:0","region:0","map-edit:0","region-edit:0","unit:0"]`（装置实现即 `perModeLeft.length === 5 && every(r => r.nonGet.length === 0)`，`e2e.cjs:267-271`）；★ **T12 本轮当场跑**：`t12-evidence/logs/clean-e2e-t12.log:9`、`:10`（逐值同上）
- ★★ **同一行 `LEFT_PAN_ALL_MODES` 还带 `moved` 字段，且当前字节上五模式全 `true`**（`t12-evidence/logs/clean-e2e-t12.log:9`：`view/region/map-edit/region-edit/unit` 逐条 `moved:true`）—— 而 **`moved` 不进任何断言**（`e2e.cjs:267-270` 只断言 `nonGet`）⇒ 这正是 **M8-L12** 的当场对照：A 的 m6 轮里**唯一** `map-edit: moved:false`（`m8r-rerun/mutants/logs/m6-e2e.log:9`）而 `a5` 照样 PASS。**同一装置上的干净差分对**，见 §五 遗留 5.2 的 M8-L12。
- **零写期待（当前字节，六处）**：`a8-preflight-zero-write` / `e1-unselected-mode-zero-write` / `x1-nonadjacent-jump-zero-write` / `r0a-empty-selection-zero-write-with-warning` / `r0b-selection-is-zero-write-and-persists` / `r0c-empty-seed-zero-write-with-warning` —— **逐条 `posts=0`**（T12 本轮逐条：`t12-evidence/logs/clean-e2e-t12.log:13`、`:15`、`:23`、`:25`、`:26`、`:27`）

**★ 缺口**：① **「写不了」的口径是"操作没发出写"**（左键拖动 / 只读点击 / 空选区 / 空 seed），**不是"发出的写命令被拒绝"** —— **服务端 mode-denied 这一层零断言**（`e2e.cjs` 里只有一处注释提到白名单，无断言步）；② **逐模式非 GET 的枚举只覆盖"左键拖动"这一种操作**，不是每种操作 × 每个模式。

### 判据⑥ 既有能力不退化（M7/M7b~M7g：右键路线、左键取消移动、tick 分组、推进 N、全屏底图、浮层不穿透 **全绿**）

**★★ 本条要分两层说，因为"全绿"这个字面口径在 M7 期就不成立：**

**(1) 字面口径不成立**：M7c/M7e 的浏览器 e2e **有既有失败**（`HANDOFF.md` §五 债 2：已用**旧字节对照**证明**非 M8/M9 引入**，**根因未查**）⇒ 「M7/M7b~M7g **全绿**」**从来没有绿过**。这不是 M8 的退化，但判据⑥ 按字面**不可满足**。

**(2) 六项在当前字节上的实测值 —— 逐项查过，落到三种情形：**

| 项 | 当前字节实测 | 说明 |
|---|---|---|
| **推进 N** | ★ **M8 全程未找到** | 代码面在（`webui/api.js:221 advance()` + `index.html:225 #timeline-advance-n`），**当前字节上无任何浏览器断言** |
| **右键路线** | ★ **当前字节无断言** | 代码面在（`index.html:49-53` `#unit-route-toggle`/`-send`/`-clear`/`-preview`、`map.js:25-27`、`api.js:194` 提到 `unit.PlanRoute`）；最近一次浏览器实测在 **T7 轮**（`map.js=f6eeed79…`，**旧字节**；且 T7 装置 `ROOT=/home/cna/...` 属**另一台机器**，不可原地重跑） |
| **左键取消移动** | ★ **被 M8-R 按键模型取代** | **不是"退化"**：M8-R **裁定**「左键拖动 = 平移地图（全模式统一）」⇒ 旧行为被**有意替换**。当前字节有 `a5-five-modes-left-drag-zero-write`（左键=平移 + 零写），**但"取消移动"那条语义已不存在** |
| **tick 分组** | ★ **当前字节未找到** | 当前 `webui/` 下与 `group` 相关的只有 CSS 类名（`unit-edit-group`/`region-edit-group`）与 `role="group"`；**没有独立的"分组"断言** |
| **全屏底图** | ⭕ **部分有** | 代码面在：`map.js:620-626` 按 `devicePixelRatio` 设 canvas 尺寸、`:930`/`:944` `setTransform(dpr…)`；当前字节断言 `a6-canvas-box-in-viewport`（`box.y + box.height <= VH + 1`，**只覆盖"在视口内"**），**不含** M7g 的 `dpr sharp` / `no box` / `covers` 三条 |
| **浮层不穿透** | ⭕ **代码面在、当前字节无断言** | `index.html:13-14`（`.wb-overlay` `pointer-events:none`）+ `styles.css:839`/`:868`；当前字节断言**未找到**（M7g 的 `c-panel-no-passthrough-*` 在旧装置上） |

**⇒ 判据⑥ 的实测值：六项中 4 项在当前字节上无浏览器断言（推进 N / 右键路线 / tick 分组 / 浮层不穿透），1 项部分覆盖（全屏底图），1 项被有意替换（左键取消移动）；且"全绿"的字面口径因 M7c/M7e 既有失败从未成立。** 这不是"通过"，也**不是"M8 引入的退化"**（旧字节对照已排除），而是**判据⑥ 从未在当前字节上被真正测过** —— 归还见 §五 遗留 ①。★ **不得把 T9 那轮 57/57 当作判据⑥ 的证据**：`clean-e2e-final.log` 的 57 步里**没有**这六项（T9 的步骤名可逐一核）。

### 判据⑦ 门禁（`./mvnw clean verify` rc=0；前端单元测试已在门禁内；每任务**变异轮全杀**）

**`./mvnw clean verify` → `rc=0`、`BUILD SUCCESS`、7/7 `SUCCESS`**（干线树 `fbd09ee`，`Total time 07:41 min`；逐项见 §二）。★ 本条**经三次尝试才拿到**：前两次**后台**运行被本机内存守卫**杀掉**（不是红、不是绿），第三次改**前台**一次通过 —— 见 §二 与 §〇 第 6 条。

**块③ 每任务变异轮汇总**：见 §四。★ **不写成"全杀"** —— 四处要如实说：**M8-R 按新锚点重跑 8 轮 0 被杀**（旧锚点 9/9 KILLED 不复现，两种归因都还开着）、T2 有 1 轮**曾存活**（后修）、T5/T6 的 `mut_rc=0` **不是绿**（装置带 `-Dmaven.test.failure.ignore=true`）、T1 的 3 轮跑在"新增用例之前"的测试集上（详见 §四）。

---

## 二 门禁（主树，合并后 `fbd09ee`）

**结果：`rc=0`、`BUILD SUCCESS`、7/7 `SUCCESS`**（`logs/verify-rc.txt` = `rc=0`；`logs/clean-verify.log:1683`）。树 = 干线 `/home/dev/SimulatorMosire`，HEAD = `fbd09ee`，2026-09-20 07:42:00 起、`Total time 07:41 min`（`:1685`；`time` 实测 `real 7m48s`）。

| 项 | 实测 | 出处（`logs/clean-verify.log`） |
|---|---|---|
| 逐模块测试数 | **977** = util **170** / map **362** / social **45** / unit **131** / core **169** / app **100** | `:125`、`:270`、`:347`、`:444`、`:608`、`:1144`（六条均为 `Failures: 0, Errors: 0, Skipped: 0`） |
| 与基线比 | **逐值相同**（T9 期 `t9-evidence/logs/full-verify-postmutants.log` 的 170/362/45/131/169/100） | 同上 |
| SpotBugs | `BugInstance size is 0` ×**6** ＋ `Error size is 0` ×6 | `:145`、`:290`、`:367`、`:464`、`:628`、`:1669` |
| `[ERROR]` | **0** 行（全日志 `grep -c '\[ERROR\]'` = 0） | — |
| 前端门禁 | `[frontend-gate] OK tests=82 pass=82 fail=0` | `:1650` |
| Reactor | 7/7 SUCCESS：`SimulatorMosire` 14.6s / Util 1:20 / Map 1:52 / Social 35.8s / Unit 50.1s / Core 1:17 / App 1:30 | `:1675`-`:1681` |

★ **`BugInstance` 是 ×6 不是 ×7**：父 pom 模块不产该行（`:150-152` 的实测位置只有 6 处）—— 与 §〇 第 4 条互证。

**★★ 本条的取得过程必须记账（三次尝试，两次被杀）**：

| 尝试 | 通道 | 结局 | 证据 |
|---|---|---|---|
| 1 | 后台（任务 `bfmrkxmqf`） | **被杀**（不是红、不是绿）：已过 util/map/social，停在 simos-unit 的 `compiler:3.16.0:compile` | `logs/clean-verify.attempt1-killed.log`（25,296 B）+ `…attempt1-killed.note.txt`（守卫通知逐字原文） |
| 2 | 后台（任务 `b30ebx76w`） | **被杀**：启动**同一秒**，日志 **0 字节** | `logs/clean-verify.attempt2-killed.log`（0 B） |
| 3 | **前台** | **`rc=0` 一次通过** | `logs/clean-verify.log` + `logs/verify-rc.txt` |

- 两次被杀**都不是构建失败**：被杀轮日志内 `OutOfMemoryError|Killed|BUILD FAILURE|[ERROR]` 命中 **0** 条；且**没有 rc 落盘**（包装脚本的 rc 语句没执行到）⇒ 不得据被杀轮报"通过"或"失败"。
- ★ **通则入档（环境级，与通道绑定）**：本机（1.8 GiB RAM、`sysctl vm.drop_caches` = **permission denied**、`PSI some avg300=9.37` 处于真实换页压力）下，**后台**跑整树 `clean verify` 会被内存守卫杀，**前台**可跑完 ⇒ **"被杀"与"用哪条通道跑"绑定**；判定门禁必须走**前台**通道，被杀轮**留档不删**。（`posix_fadvise(DONTNEED)` 试过：只覆盖 115 文件 / 189 MiB，缓存反升 ⇒ 无效，不再依赖。）
- ★ attempt-1 的归因**含我自己的责任**：被杀时我正**并发**跑全仓 `find` / 126 文件 md5 对拍 / git 读操作 ⇒ **"门禁独占"不只是"不跑第二个 Maven"**，同机任何重 IO 都会把门禁推向被杀。
- ★ 本条**推翻了我先前"要塞是内存不够"的直觉**：真正的分界是**通道**，不是模块数、也不是缓存。attempt 2 在 0 字节处被杀即为反证。

---

## 三 裁定 10 条点验（**替代口径，不是 R 表** —— 见 §〇.1）

> 逐条给：**裁定原文（spec §〇.2）→ 点验证据 `文件:行` → 缺口 / 与实现的出入**。
> 口径：**只有能点到当前字节的，才写"点到了"**；点不到的一律进"缺口"列，不拿相邻证据顶替。

**★★ 点验前必须先说的一件事：spec §〇.2 那张表本身不全。**
逐条核过之后发现，M8 期**至少还有两条裁定不在表里**，而它们都改了产品行为：

1. **统一按键模型（用户裁定）**：**左键拖动 = 平移地图（全模式统一，永不误改）**；右键按模式分派（区域编辑=套索 / 地形编辑=`map.SetTerrain` / 常规·单位移动=`PlanRoute`）；**Shift+右键 = 逐格画/擦**（M8 台账 `:87-88`）。★ **这条直接决定判据⑥ 的"左键取消移动"被有意替换**（见 §一 判据⑥）。
2. **区域边界 = 精确 hex 外缘**（M8-S **①**，**明确写着"推翻 §八 第 3 条"**）：RDP 整段删除、改用精确环（M8 台账 `:99-100`）。

⇒ 本报告 §三 点验的是 **spec §〇.2 表内那 10 条**（因为计划 T12 与我先前记的口径都以那张表为准），**但"表内 10 条全点到"≠"M8 的裁定全点到"**。上两条的落点分别在 §一 判据⑥ 与 §四（M8-S 的 m10「重新引入 RDP ⇒ 判据 14 四条等式全红」⇒ 说明"精确环"这条**有护栏**）。**表与裁定的这处不同步，归还为 §五 M8-L9 / §六**。

| # | 裁定 | 点验证据（当前字节） | 缺口 / 出入 |
|---|---|---|---|
| **M8-U1** | 多对多：hex 可**同时属于多个区域**，**不存在"谁赢"** | `RegionIndex` 的存储就是 `Map<HexCoord, List<RegionId>>`（`simos-map/.../region/RegionIndex.java:42-47` 累积、`:55-58` 返回**全部**）——数据结构上**没有"唯一归属"这个位置**；真档 `ownersAfter(-18_0)=[t4_overlap, test_annex_target, test_nation]`（`t4-evidence/probe/real-archive-probe.out:14`）；单元 `RegionIndexTest.java:54-56` 三条同盖一格、`:57-59` `.as("结果与入参迭代序无关 ⇒ 逐值相同")` | **无**。★ 这是 M8 唯一"被用户原话直接钉住"的地基项，也是唯一在**真档**上验过从属条数 >2 的项 |
| **S4** | **语义化命令**，**不做**通用 `ApplyChangeSet` | `ApplyChangeSet` 在**代码里 0 命中**（`git grep ApplyChangeSet -- .` 只命中 spec:22 与台账:16 **两处"不做"的句子**）—— 这个符号**根本不存在**；命令面恰六个 `simos-map/.../spi/*Handler.java`：`SetTerrain` / `SetEdge` / `CreateRegion` / `UpdateRegion` / `DeleteRegion` / `RandomizeRegion` | **无**。"不做"以"**符号不存在**"的形式兑现，比"留个空实现"强 |
| **S5** | 圈选随机化**允许任意选区** | `RandomizeRegionHandler.java:42` `Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes")` —— 载荷直接给 hex 集合，**无连通性/凸性/形状校验**；浏览器层 `x1-nonadjacent-jump-zero-write`（**非相邻跳跃**）与 T11 的 `r1`/`r2` 选区随机化（`t11-evidence/logs/e2e-clean-after-mut.log`） | **"任意"没有全域枚举**：只证过"非相邻也受理"，没证过"任意形状都受理"（这是**全称命题的可证伪面**问题，不是缺口） |
| **Q1** | **不做**撤销/重做；想撤销就**回退到前一节点**（分叉） | `undo`/`redo` 在三个 `src/main`（app/core/map）**0 命中**（`git grep -niE '\bundo\b\|\bredo\b'`）；UI 只有一条**反向**告知：`webui/index.html:170` + `webui/map.js:3443`「删除不可撤销：点「确认删除」才真正发出 `map.DeleteRegion`」；回退路径 = `/api/fork`（在写白名单三端点内） | **UI 层"回退到前一节点"无一条端到端实测**（`fork` 有端点、有白名单、有 `TimelineTest`，但**没有**"编辑后 fork 再重放"的浏览器断言） |
| **Q2** | 命令**必须显式声明** `replace` 还是 `merge`；**默认 `replace`** 但必须显式写 | **前半句点到了**：`MapPayloads.requireText(payload,"mode")`（定义体 `MapPayloads.java:50-56`：字段缺席**或**非字符串 ⇒ `throw`）⇒ 服务端**没有"缺 mode 就兜 replace"这条路**；T11 变异 **m1「UI 静默兜 replace」被 KILLED**（红在 `e1-*`）⇒ 断言**正面禁止**UI 兜默认 | ★ **后半句「默认 `replace`」在实现里不存在** —— 协议**必填**，没有默认值可兜。这不是违背前半句，是**选了更严的一读**：`MapPayloads.java:58-60` 明写这套载荷的纪律是「**没有默认值**：…兜底会让『同一操作两次不同』从一条被拒的载荷变成一次静默的非法写」。⇒ **该半句无任何可点证据**，如实归还（不是缺陷，是**裁定内部两半自相张力**时实现取了严的一半） |
| **Q3** | 区域命令面**三条**；**`RegionId` 由调用方给**（不自动生成） | `CreateRegionHandler.java:16` 逐字写「`regionId` **由调用方给**（Q3）」+ `:31`；`UpdateRegionHandler.java:16`「hexes 与 meta 至少给一个」；`DeleteRegionHandler.java:13`（只收 id）；命令面三条 = 上表六个里的三个 `*RegionHandler` | **无**。★ 附带实测：命令面**没有** `RenameRegion`（`UpdateRegion` 改 `name` **无 UI 入口**，T4 已记） |
| **Q4** | `GenerationSpec` **不可改**（铁律 5：`spec` **不进变更集**） | `GenerationSpec` 的出现面**只有** `simos-core/.../GameMap.java` 与 `.../generate/`（`git grep -l GenerationSpec`）—— **六个 spi 载荷 / Handler 里一个都不含 `spec`**；`GameMap.java:86`「spec 不得为 null」+ `withSpec` 走**不可变拷贝** | **无**。"不进变更集"以"**载荷里根本没有这个字段**"的形式兑现 |
| **Q5** | 地形画图粒度：**一条命令带多个 hex**（拖一串 = 一条） | `MapPayloads.java:78-85` `Set<HexCoord> hexes = new LinkedHashSet<>()`（一次收一串，**不是一格一条**）；`:118` 明写「**缺席与空数组是两回事**」；浏览器层拖一串 ⇒ 恰一条命令：T9 轮 `a1`/`a2`/`f0-terrain-tool-restored`（`t9-evidence/logs/clean-e2e-final.log`）；T7+T8 变异 **m3「每格一条命令」被 KILLED** | **无** |
| **Q6** | `regions` 按 **RegionId 字典序**（否则 JSON 随哈希序变） | ★ **是构造期排序，不是读时排序**（这是我实际读代码才敢写的）：`RegionIndex.java:40-41` 建索引时 `ordered.sort(Comparator.comparing(region -> region.id().value()))` ⇒ `:42-47` 每格 owner 列表**按此序累积** ⇒ `:55-58` `regionOf` **原样返回不重排** ⇒ `GuiServer.java:389` 取用 ⇒ `ApiViews.java:305-315` **只做投影**（`region.value()` 进 `ArrayList`，无第二个 `sort`）。当前字节断言：`GuiApiTest.java:286-294`（整份响应体逐字节相同）+ `:256-258` `hasSize(2)`/`"r-1"`/`"r-3"` | **三元素以上的字典序在端点层无断言**（真档 3 从属只在探针 `:14` 与单元 `RegionIndexTest.java:54-56` 验过，见 §一 判据② 缺口②） |
| **Q7** | 五模式，**每模式一份写权限白名单** | `webui/modes.js:1`（白名单表）、`:7`/`:19`/`:70`；门禁 `modes.test.cjs` **七条**（五模式逐模式 + 未知模式/空 type fail-closed + 返回快照）+ `write-allowlist.test.cjs` **五条**（含 `scanner-has-teeth-on-undeclared-endpoint` 自证） | **服务端 mode-denied 零断言**（同 §一 判据⑤ 缺口①）：白名单是**前端**概念，"写不了"验的是"操作没发出写" |

**§三 小结（只写实测值）**：10 条里 **8 条**在当前字节上**主体可点**（M8-U1 / S4 / S5 / Q3 / Q4 / Q5 / Q6 / Q7），**2 条各缺一层**：**Q2** 的「默认 `replace`」半句**在实现里不存在**（协议必填、无默认；实现取了严读，见上），**Q1** 的「回退到前一节点」在 UI 层**无端到端实测**（`/api/fork` 只在端点与白名单层可点）。★ 没有一条裁定的**主体**是"没做"。

---

## 四 每任务变异轮汇总（T1~T12）

> **★ 口径先说死：本表不写"全杀"这个词。** 判据⑦ 第三条的字面是「每任务**变异轮全杀**」，而实测有三处**不满足字面口径**（下表 ★★ 行），另有四处**装置的坑**必须与"绿"分开记。只写：**轮数 / 实测结论 / 该轮判据是什么 / 出处**。

| 任务 | 轮数 | 实测结论 | 该轮的"红"判据 | 出处 |
|---|---|---|---|---|
| **T1** 多从属地基 | **3**（m1~m3） | 九道门禁逐轮通过 | 红点落**未改动**的 `MapResolverTest` + e2e | `t1-evidence/t1-report.md:65-84` |
| **T2** 前端测试进门禁 | **7** + **1** 反证 | ★★ **m7 首轮曾存活**（见下） | `gate_rc` + 违规必须红 | `t2-report.md:106`；`t2-evidence/mutants/logs/m7-assertion-floor.log:383-384` |
| **T3** `SetTerrain` | **4**（m1/m2/m3/**m3b**） | 逐轮 0 存活 | ★ m2（只改块字段不重切）被 `GameMap` 构造期**分割不变式**当场抓（`地形块键 desert@1_0 与块内容不符`） | `t3-report.md:81` |
| **T4** 区域三命令 | **4** | 逐轮 0 存活 | ★ **m3 = 方向性护栏**：给 `createRegion` 加"与已有区域相交就拒绝" ⇒ **重叠正例当场红** | `t4-report.md:114`、`:82-84` |
| **T5+T6** `SetEdge`+`RandomizeRegion` | **4**（hits `2`/`4`/`4`/`2`） | 逐轮 0 存活 | ★★ **不是 `rc`**：装置带 `-Dmaven.test.failure.ignore=true` ⇒ `mut_rc=0` **是装置行为、不是绿**；判据是 `protected_assertion_hits>0` + 红点落被保护断言 + `restored_md5 == orig_md5` | M8 台账 T5/T6 段（`:120-126`） |
| **T7+T8** 五模式框架 + 地图编辑 UI | **5** | 逐轮 0 存活 | e2e 步级红 | 同上（`:73`） |
| **T9** 区域查看模式 | **7**（m1~m7） | 逐轮 0 存活 | 门禁 `not ok N` **与** e2e 双路（m2 实测 `union:"4"`） | 同上（`:170`） |
| **T10** 区域编辑 UI | **3** | 逐轮 0 存活 | ★ m1「前端加相交就拒绝」⇒ **8 FAIL**（方向性，第 2 次兑现） | 同上（`:81`） |
| **T11** 连通性 + 随机化 UI | **6**（m1~m6） | 逐轮 0 存活 | 逐轮 `consumed_md5 == mutant_md5`、`restored_md5 == orig_md5` | 同上（`:147`） |
| **M8-R** 区域编辑器重做 | **9**（m1~m9，**m9 作废**）；★ 本次按**新锚点**重跑 **8**（m1~m8） | ★★ **重跑实测：0/8 被杀**，**旧锚点的「9/9 KILLED」在本机不复现**；**归因 = (c) 判据在本机不可观察**（≠护栏退化、≠锚点不等价，见下第 4 条） | m1~m5、m7、m8 = **`SURVIVED-UNOBSERVABLE`**（`gate_rc=0 # tests 82/# fail 0` **且** `e2e rc=0 pass=57 fail=0`）；m6 = **`预测未兑现`**（`NOT-KILLED: 预期红点 a5-five-modes-left-drag-zero-write 未出现`，但实测 **`moved` 显示变异体真生效**） | `t12-evidence/m8r-rerun/`（`predictions.txt` + `mutants/logs/`） |
| **M8-S** 边界撤销 RDP + 重名提示 | **3**（m10~m12） | 逐轮 0 存活 | 判据 14 四条等式全红（m10）/ 并集逐值红（m12） | 同上（`:101`） |
| **T12** 关账（本报告） | **0** | — | — | ★ **本轮不新增护栏** ⇒ 按裁定 42「新增护栏必须自带变异轮」**不新增变异轮**：裁定 42 约束的是**新护栏**，T12 只出报告与门禁，**没有新护栏**。明写，不是漏 |

**表内合计**：除 M8-R 外 **46 轮**；M8-R 按 **8** 轮有效计（m9 因 RDP 整段删除**永久作废**）⇒ **54 轮有效** + T2 的 **1 轮装置失效反证** = **55 次装置运行**。

**★★ 四处不许写成"全杀"的地方（逐条）—— 第 4 条是全报告最重的一条**：

4. ★★★★ **M8-R 按新锚点重跑：8 轮 0 被杀**（旧锚点曾 9/9 KILLED）。**这一条直接使判据⑦ 第三条「每任务变异轮全杀」在 M8-R 上实测不成立。**
   **★ 归因已被判出来（不是"两种解释都还开着"）—— 是第三种：(c) 判据住在"本机跑不了的装置"里**，且（对 m6）**能跑的判据严格弱于行为**：
   - **(c-1) m1~m5 / m7 / m8 = 不可观察**。A 在**任何测量之前**写死的 `predictions.txt` 里，逐轮预测就是"两侧全绿 ⇒ 不可观察"，并**逐轮点名判据所在的断言行**，例如 m1 的唯一判据是 `r-evidence/e2e/e2e.cjs:435 h1-overlap-allowed`、m2 是 `:843 g1-region-edit-right-is-lasso` / `:925 g3-…-not-planroute`、m3 是 `:477 b1-dots-match-boundary`、m4 是 `:727 d1-merge-equals-union`、m5 是 `:763 e1-exclude-equals-difference`、m7 是 `:812 f3-shift-right-paints-draft-no-write`。**这些装置在本机跑不了**，理由是装置级且可点：`r-evidence/e2e/run-e2e.sh:7` `ROOT=/home/<另一台机器>/…`、`:33,39` 需要**真档** `$STORE_SRC/simos.db`、`r-evidence/e2e/e2e.cjs:215` 断言 `overview0.hexCount === 19441`（真档 19441 格）⇒ **干净世界也必红，无判据价值**。**实测逐轮兑现了预测**（gate `82/# fail 0`、e2e `pass=57 fail=0`）。
   - **(c-2) m6 = 判据弱于行为（本次唯一"预测失败"的一轮，且是有信息量的失败）**。变异体**确实生效**：`LEFT_PAN_ALL_MODES` 实测 clean = 五模式 `moved:true`，m6 = **map-edit `moved:false`、其余四模式仍 `true`**（平移被 `beginPaint` 抢走）。**但它一条写都没发** —— `commitBrush` 在"没选地形"时早退（`webui/map.js:2517-2521` `if (!host.brushTerrain) { … return null; }`），而 a5 那一段的前提**恰恰是"还没点过调色板"**。⇒ 本机可跑的 `a5-five-modes-left-drag-zero-write` **只断言 `nonGet.length === 0`**（`t9-evidence/e2e/e2e.cjs:267-270`），**不含 `moved`**（`moved` 在 `:260` **算了、打了、没进断言**）。★ 对照：**旧装置杀掉 m6 的那条是 `k1-terrain-left-pans-zero-write`，断言 `panMoved9 && writes9 === 0` 且先把地形选上**（`r-evidence/e2e/e2e.cjs:1025-1030`、`:1040`）⇒ **a5 是 k1 的严格弱化版**（少了"平移确实发生"半边 + 少了"已选地形"的铺垫）。m6 又用绝对路径**重跑一轮确认**：`mutant_md5`/`consumed_agg`/`LEFT_PAN_ALL_MODES`/`pass=57` **逐值相同**。
   - **⇒ 明确排除的两种**：**不是"护栏退化"**（m6 的行为仍在——`map.js` 左键恒平移的分支在、其余四模式 `moved:true` 实测在）；**不是"锚点不等价/字节变了语义没变"**（变异体确实改变了可观测行为 `moved`）。**两者都不是**。
   - **⇒ 正确定性：(c) 这笔债在关账环境里"不可还"** —— 判据住在**本机跑不了的装置**（另一台机器 / 需真档）里，而能跑的两条判据**严格弱于**它。**旧结论「9/9 KILLED」在与它同装置的环境里并未被推翻**，但**在本机不可复现**。★ 通则再现：**"被杀"是与装置绑定的** —— 换装置，同一变异体的结论可以完全不同。
   - **依据**：`t12-evidence/m8r-rerun/predictions.txt`（先验预测 + **测量之后**追加的修正记录；A 声明原始预测文字**一字未改**）+ `mutants/logs/` 逐轮日志。
   - ★ **A 自陈的边界（必须一起读，否则会把"不可观察"读成"无影响"）**：m1~m5/m7/m8 这 7 轮**只有"代码路径在 + 锚点唯一"的推导 + "两侧全绿"的实测，没有差分证据**（唯一有差分证据的是 m6 的 `moved` 逐模式对比）⇒ 记 **M8-L13**；m8 的变异体**只覆盖「块边界描边」半边**，「逐格描边」在当前树**既无绘制点也无判据** ⇒ 记 **M8-L14**；根因（装置本机不可运行 + 缺真档）记 **M8-L15**。
   - ★ **对拍证据是两侧的、不是单侧**：8 轮**每轮** `agg_expect == consumed_agg` 且 8 个聚合值**互不相同**（`mutants/logs/m1-e2e.log:68` 起；9 个日志文件的逐文件确认见 **§〇 5**）⇒ "跑的是这一轮的字节"有证据；每轮 `node --check` rc=0、`restored_md5 == orig_md5`（源与 `target/classes/webui/map.js` **双侧**）。
   - ★ **顺带查出的真缺陷**：**M8-L12** —— `a5` 的 `moved` **算了、打了、没进断言**（"断言成装饰"的同型），这正是 m6 不可见的**直接原因**。
   - ★ **装置与检查器自指**：装置 md5 `f81ed3a3a48d290fd8d8d32d8ecb77f3`（10 轮**同一个装置**，未改过）；用到的检查器 `e2e.cjs=75ce5aa08c504c093ec6e27d28ce4c16`（= **T9 那一份**，与我 §七 的 T12 副本逐字节相同）、`run-e2e.sh=96288100077e8f58d271d5290b4ffde0`（**T9 的**，非 T12 副本 `9f203003…`）。★ A **未跑 Maven**（按派单：纯前端 delta，门禁是**直接** `node …/run-gate.cjs`），⇒ **Maven 那一层由本报告的 §二 门禁轮补**。

1. **T2 的 m7 首轮曾存活**。根因：静态计数把 `.test(`（正则/字符串里的方法调用）误算入断言数 ⇒ 该轮 `gate_rc=0`、`red_points=0`。**修完在终态字节上重跑**，自陈旧日志原样留着（`m7-assertion-floor.log:383-384`）。⇒ 这是**除第 4 条外唯一一处"变异体曾经活下来"**，且它暴露的是**装置**缺陷不是产品缺陷。
2. **T5/T6 的 `mut_rc=0` 不是绿**。装置为了让"红"以被保护断言的**命中数**表达，带了 `-Dmaven.test.failure.ignore=true` ⇒ `rc` 恒 0。**谁拿 `rc` 当判据，谁就会把这 4 轮读成"全绿"甚至是"没跑"** —— 本报告以 `hits` 为准。
3. **M8-R 的重跑**：见第 4 条（**旧证据对应旧字节 ⇒ 必须重跑**这条纪律本身没问题；问题在重跑**没复现**）。

**★ 四处装置的坑（与"绿"分开记，都是自曝）**：

- **T7+T8**：m1 还原时把 `src/modes.js` 写回**旧版** ⇒ m2~m5 出现**假红**（"先怀疑自己的装置"）。
- **M8-S 的 m11**：首轮 `e2e_rc=2` = **装置崩溃（不算红）**（无弹窗致 `page.click` 超时），稳定后 `rc=1` 才算红。
- **T11**：整树 verify 跑了 **5 次只有第 5 次可引用** —— ① 被系统因内存不足**杀掉**（243 行、停在 3/7、**无 `BUILD` 行** ⇒ 既不是红也不是绿）；② 真红（`MapHexEdgesApiTest.java:186 cannot find symbol: class UnitSnapshot`，缺 import）。
- **T1**：m1~m3 跑在"两个新增覆盖用例**之前**"的测试集上（生产字节三处 md5 与轮内 `orig` 备份逐字节相同；杀红点是未改动的 `MapResolverTest` 与 e2e）⇒ **未在终态测试集上重跑**，`t1-report.md:84` 自陈。

**★ 正面的事实（也是实测，但边界要说清）**：**除 M8-R 外**的 46 轮，**终态逐轮 0 存活**（唯一一次存活是 T2 的 m7 首轮，修完在终态字节上重跑后 0 存活）；**方向性护栏兑现 3 次**（T4 m3 / T10 m1 / M8-R m1，都是"给重叠加拒绝限制 ⇒ 正例立刻红"）；**M8-R 与 T7/T10/T11 的装置打显式 `KILLED` 标记**，其余任务的装置以"红点落被保护断言"为判据（口径不同，已在表内分列）。
★ **但 M8-R 的 8 轮重跑 0 被杀** ⇒ **判据⑦ 第三条在 M8-R 这一任务上实测不成立**。订正后的归因是 **(c) 判据在本机不可观察**（不是护栏退化、不是锚点不等价 —— 见上第 4 条的逐条排除）⇒ **M8-R 这一栏在"本机可跑的判据"下是"不可观察"，在"原装置"下仍是"9/9 KILLED"（未被推翻、也未在本机复核）**。**两种写法都不许单独出现**：只写前者会读成"护栏坏了"，只写后者会读成"债已还清"。★ 因此 **M8-R 的变异结论在本次关账后记为「不可观察（判据弱于行为 / 判据住在跑不了的装置里）」**。

---

## 五 遗留条目（**带裁定**，不静默）

> **计数更正**：本节最初按"8 条"占位，逐行数 spec §七 **实为 7 个条目 / 11 个子项** —— 以实数为准。
> 每条给：**内容 → 现状（M8 是否触碰）→ 裁定（谁在什么条件下处理）**。

### 5.1 spec §七 原有的 7 条（11 子项）

| # | 条目 | M8 是否触碰 | 裁定 |
|---|---|---|---|
| 1 | `GameMap` **无 id** ⇒ `map:<mapId>` 的 mapId **只回显不可校验**（M2 起挂起） | **未触碰** | 挂起不动。M8 的写面（六命令）**不依赖 mapId** ⇒ 不阻塞关账。留给"多地图"需求出现时（**用户决定何时**） |
| 2a | 属性段地址**不服务** | 未触碰 | 挂起 |
| 2b | 人口 cache **未做** | 未触碰 | 挂起 |
| 2c | A\* **规模与跨 JVM 决定论未测** | 未触碰（T11 的连通性走 BFS/邻接，非 A\*） | 挂起；★ 与 M8 的 `PlanRoute` 无关 |
| 3 | `PlanRoute` **稀疏路点缺口**（M7b 挂起） | **未触碰**（M8-R **有意**把右键在"常规/单位移动"模式下**保留**为 `PlanRoute`，未改其语义） | 挂起。★ 判据⑥ 的"右键路线"一项因此**只有旧字节证据**（见 §一 判据⑥） |
| 4 | 大图性能：服务端 overview 40–85ms，瓶颈 **~1MB 传输（浏览器内 ~2.6s）+ 首帧渲染 ~2.55s** ⇒ 视口分级 / gzip（**M7 裁定 70**） | 未触碰 | **裁定 70 仍然有效、未实施**。M8 的编辑面**不加重**这条（编辑走增量命令，不走 overview 全量） |
| 5 | **倒树方向（根在下）可推翻** | 未触碰 | 明确是**"可推翻"**：保持现状直到用户提。★ 注意：**推翻它是产品决定，不是缺陷修复** |
| 6a | 审批**超时与会话键未验** | 未触碰 | 挂起 |
| 6b | `fork` **不发事件** | ★ **本报告新增一条实测**：Q1 的"回退到前一节点"路径正是 `fork` ⇒ **判据①/② 的"时间线多节点"在 fork 路径上不成立**（`fork` 不落事件）。**M8 未改**，但**Q1 与 6b 由此耦合** | 挂起；★ **归还措辞**：Q1 裁定"想撤销就回退到前一节点"**成立**（`fork` 能用），但"回退会多一个时间线节点"**不成立**（不发事件）—— 这两句必须一起读，否则会误期待节点数增加 |
| 6c | **R9 长连分支未覆盖** | 未触碰 | 挂起 |
| 7 | `simos.db` **DDL 由 `tools/gsimap_import.py` 内联** ⇒ Core 改 DDL 时脚本**静默失配**（**无编译期护栏**） | **未触碰**（M8 零 DDL 改动 —— 六命令走既有 `ChangeSet` 通路） | 挂起。★ 这是 **HANDOFF §五 债 3** 的同一条，两处已对齐 |

### 5.2 M8 期**新产生**的遗留（都是本次关账查出来的，逐条带裁定）

| # | 遗留 | 裁定 | 出处 |
|---|---|---|---|
| **M8-L1** | ★★ **判据⑥ 从未在当前字节上被真正测过**：六项里 4 项当前字节无浏览器断言（推进 N / 右键路线 / tick 分组 / 浮层不穿透），1 项部分（全屏底图），1 项被有意替换（左键取消移动） | **归还**（不是"通过"、也不是"退化"）：判据⑥ **按字面不满足**，且因 M7c/M7e 既有失败，**"全绿"从未成立**。★ 后续处理二选一，**由用户定**：① 把六项补成当前字节的断言（**推荐**，与 M8-R 的按键模型同批）；② 把判据⑥ 改写成"可观测面清单 + 已知不覆盖"，承认它是清单不是判据 | §一 判据⑥ |
| **M8-L2** | ★★ **M8-R 的 m1~m8 重跑：0/8 被杀**（本机）；归因 **(c) 判据在本机不可观察**（≠护栏退化、≠锚点不等价） | **本单已还"重跑"这条债，结论是"不可观察"**：判据住在**本机跑不了的装置**里（`ROOT=/home/<另一机器>/…`、需真档 `simos.db`、断言 `hexCount=19441`），能跑的判据**严格弱于**它（m6 实测 `moved:false` 但零写 ⇒ a5 看不见）。★ **旧结论「9/9 KILLED」在与它同装置的环境里未被推翻、也未在本机复核** ⇒ 两种写法必须一起出现。★ **m9 永久作废**（RDP 整段删除） | §四 第 4 条、§六 B4 |
| **M8-L10** | **（已作废，留痕）** 我曾写「M8-R 重跑首轮 m1~m4 未打印 `agg_expect` ⇒ 这 4 轮只有单侧」 | **撤回**：查定稿日志 9 个文件，**每轮 `agg_expect` 与 `consumed_agg` 各 1 行且逐轮相等** ⇒ 不存在该缺口。详见 **§〇 5**（教训：判"缺"要看定稿产物，不看中间输出） | §〇 5 |
| **M8-L13** | ★ **"不可观察" ≠ "无影响"**：m1~m5/m7/m8 这 7 轮，**没有差分证据**证明变异体真的改变了浏览器行为（只有"代码路径在 + 锚点唯一"的**推导** + "两侧全绿"的**实测**）；唯一有差分证据的是 m6（`moved` 逐模式对比） | 归还：**这是判据面的空白，不是结论**。★ 引用时必须写"**不可观察**"，**不许**写成"变异体无影响/护栏无效"。裁定：与 M8-L2 的装置本机化同批 | A 报告 ⑤.2、§四 第 4 条 |
| **M8-L14** | ★ **m8 的变异体只覆盖一半**：新版只 stroke「**块边界描边**」，而「**逐格描边**」在当前树**既无绘制点也无判据**（唯一的逐格 stroke 是**已删**的选区预览层） | 归还：**判据覆盖面缩水**（M8-S 删 RDP/预览层之后，"逐格"这半边不存在了）。裁定：**接受现状但明写** —— 该半边的"不退化"**无法在当前字节上被断言** | A 报告 ②-m8、⑤.4 |
| **M8-L15** | **r-evidence / s-evidence / t10 的判据装置本机不可运行**（另一台机器路径 + 需真档 `simos.db`）⇒ m1~m5/m7/m8 这 7 条护栏行为**在关账后仍将处于无判据状态** | 归还：**这是 M8-R 变异债还不上（归因 c）的根因**。裁定（**用户定**）：① 装置**本机化** + 把 19441 格真档换成**可复现的合成大图夹具**（**A 的建议 2**，推荐）；② 或承认这 7 条行为**只由真档环境守**，在 CI 里标注为"环境受限未覆盖" | A 报告 ⑤.1、⑥ 建议 2 |
| **M8-L11** | **台账措辞与旧变异体本体不一致**：`progress.md:92` 把 m4 写作「**合并误用交集**」，而旧装置的 m4 本体是「**只取临时选区**」（`r-evidence/mutants/mut-run.sh:99,107-108`） | 归还：**台账笔误**（判据都是"并集"，变异不同）。本轮重跑**以旧装置本体为准**。裁定：**改台账那一句**（用户批）或在下单引用时明写"以装置本体为准" | `predictions.txt` 修正记录 ④ |
| **M8-L12** | ★ **"断言成装饰"的同型**：`a5-five-modes-left-drag-zero-write` 的 `moved` **算了、打了、没进断言**（`t9-evidence/e2e/e2e.cjs:260` 算 → `:267-270` 只断言 `nonGet.length === 0`）⇒ **m6 因此不可见**。★ **T12 本轮已补上"同一装置上的差分对照"**：当前字节 `t12-evidence/logs/clean-e2e-t12.log:9` 五模式**全 `moved:true`**，m6 轮 `m8r-rerun/mutants/logs/m6-e2e.log:9` **仅 `map-edit:moved:false`**，而两轮的 `a5` **都 PASS** ⇒ 判据与行为之间的缺口**不再是推导，是实测对拍** | 归还：**判据面缺陷**。裁定：**补 `moved` 进断言**（=把 a5 提升到 k1 的强度：`panMoved && writes === 0` + 先选地形）。★ 成本低（纯前端 e2e），**建议与 M8-L2/M8-L10 同批** | §四 第 4 条 c-2 |
| **M8-L3** | **Q2 的「默认 `replace`」半句在实现里不存在**（协议必填、无默认） | **判定为"实现取严读、无缺陷"**；★ 但 spec §〇.2 **那一格的字面**留着会误导 ⇒ **建议改写为「必须显式声明；无默认值」**（改的是 spec 措辞，**用户批**） | §三 Q2 |
| **M8-L4** | **Q1 的"回退到前一节点"在 UI 层无端到端实测**（`/api/fork` 只在端点/白名单层可点）+ **6b 的 `fork` 不发事件** | 两条**合起来**才是完整口径（见 5.1 #6b）。裁定：**保持**；补一条浏览器层 fork 断言列入后续（**推荐**） | §三 Q1、§五 5.1#6b |
| **M8-L5** | **服务端 `mode-denied` 零断言**：白名单是**前端**概念，"写不了"验的是"操作没发出写" | **归还**。裁定：**当前可接受**（写白名单**唯一**入口是前端 `modes.js`，服务端有端点级白名单兜底 —— 见 `write-allowlist.test.cjs` 的三端点），但**"服务端拒绝"这条通路本身没被验过** ⇒ 不写进"已验证" | §一 判据⑤ 缺口① |
| **M8-L6** | **T1 的 3 轮变异跑在"两个新增覆盖用例之前"的测试集上**，未在终态重跑 | 裁定：**补跑**（成本 3 轮 × 单模块）。★ 与 M8-R **同批**处理更省（同模块）。`t1-report.md:84` 已自陈 | §四 T1 行 |
| **M8-L7** | **台账三处"最多 2 从属"是旧口径**（`t1-report.md:86`、`t9-report.md:133`、`CLAUDE.md:248`） | **本报告即更正**（≥3 的真档实测在 `t4-evidence/probe/real-archive-probe.out:14` 与 `t10-evidence/logs/clean-after-mutants-e2e.log:10`）⇒ 缺口是**台账未随 T4/T10 更新**，不是产物缺失 | §一 判据③ |
| **M8-L8** | **`HANDOFF.md` §一 对 `main` 记错** + §六 的浏览器路径 `chromium-1234` **已过期**（实为 **1244**） | 本报告 §〇.3 已更正 `main`；**HANDOFF 本体留待用户决定是否改**（它是干线独有文件、是给下一个会话看的入口） | §〇 3 |
| **M8-L9** | ★★ **spec §〇.2 的裁定表不全**：至少两条改了产品行为的裁定不在表里 —— ①**统一按键模型**（左键=平移，用户裁定）②**区域边界=精确 hex 外缘**（M8-S ①，**明写"推翻 §八 第 3 条"**） | **归还**：本报告 §三 点的是**表内 10 条**，**不等于** M8 裁定全集。建议把这两条**补进 spec §〇.2**（改 spec，**用户批**）；在那之前，"裁定 N 条全点到"这类句子都应读作"**表内 N 条**" | §三 引文、§一 判据⑥、§四 M8-S 行 |

### 5.3 HANDOFF §五 债的对照（4 条，逐条说清状态）

| 债 | 内容 | 本报告对应 | 状态 |
|---|---|---|---|
| 债 1 | M8-R m1~m8 需按新锚点重跑，**m9 作废** | **M8-L2** | **正在还**（A 在跑） |
| 债 2 | M7c/M7e 既有失败**根因未查**（已证非 M8/M9 引入） | **M8-L1** | **未还**，且它正是判据⑥ 字面不成立的原因 |
| 债 3 | DDL 双拷贝（`tools/gsimap_import.py` 内联） | 5.1 #7 | 与 spec §七 对齐，**挂起** |
| 债 4 | **无 shaded jar** | 本次未触碰 | 与 M8 无关（M8 零打包改动）；**挂起** |

---

## 六 我未能核实的

> 口径：**这一节不是"待办"，是"我不知道"**。凡写进这里的，**不得**在别处被引作已验。

**A. 装置/数据不可得（想核也核不了）**

1. **真档已不存在**：`/tmp/m6-import-verify/test_integration` 在本机**不存在**（T3/T4 跑的是**副本**，原档 md5 `2348b9365e5b107945a305d06fad8fab` 跑前跑后同值）⇒ **T3/T4 的真档探针不可原地重跑**。⇒ 判据①② 的真档那一层是**一次性的**、**不可复现**。
2. **T7 的 e2e 装置属另一台机器**（`ROOT=/home/cna/...`）⇒ **不可原地重跑**。判据⑥ 的"右键路线"最近一次浏览器实测出自它。
3. **浏览器层没有"重放"装置**：判据①② 的"**重放后**是新值 / 一致"**只有 Java 探针一层**；当前 e2e 装置（`t12-evidence/e2e/e2e.cjs`）**不做重放** ⇒ 这一面**在浏览器层无法断言**（不是没写，是**装置不支持**）。

**B. 本次关账时已出数、但归因未定**

4. ★★ **M8-R 的 m1~m8 重跑结果已出：0/8 被杀**（7 轮 `SURVIVED-UNOBSERVABLE` + 1 轮 `预测未兑现`；A 现场 `t12-evidence/m8r-rerun/`，m6 已用绝对路径确认重跑一轮，逐值相同）。
   ★ **归因已判出 = (c) 判据在本机不可观察**（逐条排除见 §四 第 4 条）：判据住在**本机跑不了的装置**里（`r-evidence/e2e/run-e2e.sh:7` 是**另一台机器**的 ROOT、`:33,39` 需**真档** `simos.db`、`r-evidence/e2e/e2e.cjs:215` 断言 `hexCount=19441`）；能跑的 `a5` 是旧杀手 `k1` 的**严格弱化版**。
   ★ **我未能核实的**：(1) **旧结论「9/9 KILLED」在本机无法复核**（装置跑不了）⇒ 它**既没被推翻也没被确认**；(2) **m1~m5/m7/m8 的变异体是否真改变了浏览器行为**——这 7 轮只有推导 + 两侧全绿，**没有差分证据**（M8-L13，唯一有差分的是 m6）；(3) **m8 的「逐格描边」半边**在当前树无绘制点 ⇒ 该半边**无法被断言**（M8-L14）。
   ★ 已排除的弱解释：不是"e2e 没跑到变异体" —— **8 轮每轮** `agg_expect == consumed_agg` 且 8 个聚合值互不相同（逐个日志文件确认见 §〇 5）。

**C. 覆盖面的空白（是我没测，不是测不过）**

5. **判据⑥ 的六项**（推进 N / 右键路线 / tick 分组 / 浮层不穿透 / 全屏底图 dpr / 左键取消移动）—— 当前字节**无断言**，详见 §一 判据⑥ 与 §五 M8-L1。
6. **服务端 `mode-denied`** —— 零断言（§五 M8-L5）。
7. **真档上的 `edges` 往返** —— T5/T6 自陈「本机实测**没有**真档…**不对"真档上的 `edges` 往返"作任何断言**」（`t5t6-evidence/notes/t5t6-conclusion.md:156-163`）；M6 的开口项（`edges` 非空无真实样本）**依然成立**。
8. **≥3 从属的浏览器渲染**：T9/T11 的 `--demo` 世界里最多 **2** 从属；**3 从属的浏览器实测在 T10 轮（真档副本）**，不在当前字节的 `--demo` 装置上。
9. **只 Chromium**：未跑第二视口（1024×700）、触摸、触控笔、**`dpr > 1`**（HiDPI 淡色像素采样）。M8-R 自陈同上。
10. **M8-S 的 RDP 包住性**（4 个焦点区 `20/20`）**只对本次 20 格区域证过**；带洞 / 多连通 / 凹形大区域**未构造**。★ **且 RDP 已整段删除**（M8-S ①）⇒ 这条已是**历史证据**。
11. **T2 只在 node v22.23.2 跑过**；`--test-reporter=tap` 需 node ≥19（更老版本会因坏选项**非零退出**，仍是 fail-closed，但**具体报错文案未实测**）。
12. **`UpdateRegion` 改 `name` 无 UI 入口**（T4 已记）⇒ 该字段的**浏览器层**改动无实测。

**D. 太慢/太大而没跑的**

13. **大图性能（spec §七 #4）**未复测 ⇒ 裁定 70 的"视口分级 / gzip"**仍是未实施**状态，本报告不给任何新数字。
14. **跨 JVM 决定论 / A\* 规模**（spec §七 #2c）未测。

**E. 我作为关账人**没做**的事（明说，免得被读成"做了"）**

15. **没有**新写任何浏览器断言去补判据⑥ —— 本单是**关账**，补断言属产品改动（会改字节 ⇒ 全部证据作废）。**归还**见 §五 M8-L1。
16. **没有**验证 §〇.2 之外的裁定全集（见 §三 引文与 §五 M8-L9）：本次只能证"**表内 10 条**"这一量词。
17. **没有**对 M7c/M7e 的既有失败做根因分析（HANDOFF §五 债 2）—— 只**引用**其"已证非 M8/M9 引入"的结论。

---

## 七 证据索引

> 全部路径相对 `/home/dev/SimulatorMosire`（**干线树**，被审字节所在的那一棵）。**本报告引用的一切都在仓内**，不在 `/tmp`。
> ★ 凡"旧字节"证据（T3/T4/T5T6/T7/T9/T10/T11 的 `tN-evidence/`）**保持原样未动**；T12 自己只**新增** `t12-evidence/`。

### 7.1 本报告直接产出的（T12）

| 文件 | 是什么 |
|---|---|
| `.superpowers/sdd/2026-09-19-map-edit/task-12-final-report.md` | **本报告** |
| `t12-evidence/logs/clean-verify.log` + `verify-rc.txt`（`rc=0`） | 干线 `./mvnw clean verify` 当场日志 + `rc`（§二）。★ 这是 **attempt 3 / 前台**通道；前两次**后台**被内存守卫杀，逐档留存：`clean-verify.attempt1-killed.log`（25,296 B）、`clean-verify.attempt1-killed.note.txt`（守卫通知**逐字原文**）、`clean-verify.attempt2-killed.log`（**0 B**） |
| `t12-evidence/logs/clean-e2e-t12.log`（+ 装置自指段 `:1`/`:2`）+ `verify-e2e-rc.txt`（`e2e_rc=0`） | **当前字节**的收口 e2e 当场日志：`STEP …: PASS` **57** 行、`FAIL` **0** 行、`:64` `E2E RESULT: ALL PASS`（§一 判据①②③④⑤ 的浏览器层）；`:1` 自记 `agg_md5=4294de6d…`/`map_js_md5=014ffd3a…` |
| `t12-evidence/e2e/run-e2e.sh` | 收口 e2e 装置。`md5=9f203003e4f4d9b737614bf95ecb1b4d`；与 T9 那份**只差第 8、9 行**（`ROOT=` 干线树 / `EV=` 指 t12-evidence） |
| `t12-evidence/e2e/e2e.cjs` | e2e 本体。`md5=75ce5aa08c504c093ec6e27d28ce4c16` —— **与 T9 那份逐字节相同**（实测 `md5sum` 两份同值） |
| `t12-evidence/m8r-rerun/**`（126 文件 / 5.1 MB） | **M8-R 变异债重跑的完整现场**（§四 第 4 条）：`mut-run.sh`（`f81ed3a3a48d290fd8d8d32d8ecb77f3`，10 轮同一装置）、`predictions.txt`（先验预测 + 修正记录）、`mutants/orig/map.js`、`mutants/logs/**`（9 轮 gate/e2e + `runs.log` + m6 首轮留档）、`notes/m8r-rerun-report.md`（`e9381d307c80bd812c28f6f43a6931a1`） |

★ **搬迁留痕（针对上一行）**：这 126 个文件**原本写在**工作树 `…/.claude/worktrees/m8t5/.superpowers/sdd/2026-09-19-map-edit/t12-evidence/m8r-rerun/`，T12 关账时由我 `cp -a` 原样搬进**干线**的本目录；搬后**逐文件 md5 对拍 126/126 全同**（两个点名文件与上表记录值一致：`m8r-rerun-report.md=e9381d30…`、`mut-run.sh=f81ed3a3…`）。
★ A 的报告**字节未改一字**：其正文自称的「工作树 `…/worktrees/m8t5`」指的是**它写作时的地点**，不是它现在的存放地点 —— 我不改作者的字节（md5 就是证据），故在此留痕而不是去改它。

★ **T12 副本 vs T9 副本的一句话**：**检查器本体逐字节相同、装置只换了"在哪棵树上跑 + 证据写给谁"**。⇒ T9 的证据出自**工作树** `…/worktrees/m8t5`、T12 的出自**干线** `/home/dev/SimulatorMosire`；**两棵树的被审字节 md5 相同**（§〇 已对拍）⇒ 这是"同一装置换棵树重跑"，不是"另一个装置"。

### 7.2 本报告引用、由**别人**产出的原始产物（未改动）

| 判据/条目 | 证据 |
|---|---|
| ① ② ④（重放面，真档） | `t3-evidence/probe/real-archive-probe.out:12`；`t4-evidence/probe/real-archive-probe.out:14,16,17,18` |
| ③（≥3 从属 / 字典序） | 同上 `:14`；`t10-evidence/logs/clean-after-mutants-e2e.log:10`；`RegionIndexTest.java:54-59`；`GuiApiTest.java:256-258,286-294` |
| ④（merge/replace 浏览器层） | `t11-evidence/logs/e2e-clean-after-mut.log:17,19,20`；`simos-core/.../MapSetEdgeEndToEndTest.java:94,102-104,105`；`simos-map/.../ops/EdgeOperationsTest.java:44-47` |
| ⑤（逐模式零写 / 端点白名单） | `t9-evidence/logs/clean-e2e-final.log:9,10`；`simos-app/src/test/js/{modes,write-allowlist}.test.cjs` |
| ⑥（缺口） | `HANDOFF.md` §五 债 2；`webui/{index.html,map.js,api.js,styles.css}` 逐行（见 §一 判据⑥ 表） |
| ⑦ 块③（每任务变异轮） | 各 `tN-evidence/` 的变异日志；M8 台账 `progress.md` 对应段 |
| §三（裁定 10 条） | `simos-map/.../spi/*Handler.java`、`MapPayloads.java`、`RegionIndex.java`、`ApiViews.java`、`GuiServer.java`、`webui/modes.js`、`simos-app/src/test/js/*.cjs` 逐行 |
| §四（M8-R 那 8 轮） | `t12-evidence/m8r-rerun/mutants/logs/**`（各轮自指段：`orig_md5`/`mutant_md5`/`agg_expect`/`consumed_agg`/`restored_md5`） |

### 7.3 报告里"证明装置本身没骗人"的那几条

| 主张 | 证据 |
|---|---|
| 要跑门禁的树 == 被审的字节 | `git diff m8/t5 HEAD` **只有一个文件**（HANDOFF.md +112）；四个关键文件**两棵树 md5 逐字节相同**（§〇 3） |
| e2e 加载的是**当前字节**（不是缓存/旧文件） | 装置自指段 `agg_md5`（T9 轮 `4294de6d…`）；装置起服务前把 `src/main/resources/webui` 全量 `cp` 进 `target/classes/webui` 并**逐文件 + 聚合 md5 对拍** |
| 变异轮"跑的是变异体字节" | 每轮 `consumed_agg == agg_expect` 且 **8 个聚合值互不相同**（§〇 5） |
| 变异轮之后**逐字节还原** | 每轮 `restored_md5 == orig_md5`（源）**且** `restored_classes_md5 == orig_md5`（`target/classes`）—— **不是 `git checkout --`** |
