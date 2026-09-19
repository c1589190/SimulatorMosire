# M8 T2 报告 —— 前端单元测试接入 Maven 门禁（关掉"前端护栏不进 CI"系统性开口项）

- worktree：`.claude/worktrees/m8t2`，分支 `m8/t2`，基线 `5d841a0`
- 权威依据：`docs/superpowers/specs/2026-09-19-map-edit-simos-design.md` **§五**；`CLAUDE.md` 里 M7"本项目没有 JS 测试器"
- 结论：`./mvnw clean verify` 绿（rc=0）；**node 测试在 `test` 阶段跑过且计数（61/61）**；故意违规⇒门禁红；
  Node 缺失⇒构建红；真档 e2e 复跑 **23/23 PASS、0 pageerror**；原档 md5 写前=写后=`2348b936…`；变异 7 轮有效红、1 轮**故意的"装置失效"反证**。

## 一 改动（逐文件 + 行）

### 生产前端（仅"可测性抽取"，行为逐字节不变）

| 文件 | 行（改后） | 改动 | 行为影响 |
|---|---|---|---|
| `webui/map.js` | `:121-208` | `cornerKey` + `regionBoundaryRings` **从 `createRenderer` 闭包内上提到顶层**（函数体逐字节不变，仅缩进 −2 空格）。二者只依赖顶层 `hexToPixel`/`hexCorners`，无 renderer 状态 | 无（纯搬移） |
| `webui/map.js` | `:3771-3780` | `window.SimosMap` 增加导出：`axialNeighbors` / `axialDistance` / `hexLine` / `regionBoundaryRings` | 无（追加键） |
| `webui/timeline.js` | `:744-750` | 导出 `__setModelForTest(tickGroups, tickByRevision)`，给读模块 `model` 的两个纯函数注入快照 | 无（页面路径不调用） |

**搬移的自证（形态 1）**：从 `HEAD:map.js` 取出原块并去 2 空格缩进，与新文件中的块**逐字节相同**（`sha256` 前 16 位 `e16d93bdc4b5d64c`，88 行）；`git diff --stat` = 88 插入 / 88 删除 ⇒ 纯搬移。

### 门禁（`simos-app/pom.xml`）

- `:18-20` 新增属性 `node.executable`（默认 `node`，可 `-Dnode.executable=<path>` 覆盖）。
- `:82-108` 新增 `exec-maven-plugin:3.4.1` execution `frontend-unit-tests`，绑 **`test` 阶段**，可执行 `${node.executable}`，参数 `${project.basedir}/src/test/js/run-gate.cjs`，工作目录 `${project.basedir}`。

### 新增测试（`simos-app/src/test/js/`，9 文件 884 行；放在此处的理由：test-only、不进 jar、与 Java 测试同级；所有路径由 `__dirname` 推出 ⇒ 主树/worktree/任意 cwd 都成立）

| 文件 | 覆盖 |
|---|---|
| `helpers/webui-loader.cjs` | vm 最小宿主：把 IIFE 资产在 node 里加载并取 `window.Simos*` |
| `run-gate.cjs` | **驱动**：发现 `*.test.cjs` → `node --test --test-reporter=tap` → 解析汇总 → 卡 `MIN_TESTS`、拒 skipped/todo |
| `modes.test.cjs` | 五模式白名单：逐模式允许/拒绝、未知模式/空 type fail-closed、返回快照 |
| `map-geometry.test.cjs` | `axialNeighbors` 6 邻逐值、`axialDistance` 冻结值、`hexLine` 端点/长度/**无重复无空洞** |
| `region-boundary.test.cjs` | `regionBoundaryRings`：环数/顶点数（**独立公式 `6|S|−2·pairs` 对拍** + 冻结值）、闭合（相邻顶点距 1）、格点残留 <1e-3、**不在 hex 中心**（距 >0.9）、donut 外 18 内 6 |
| `timeline.test.cjs` | 列布局 `(列−1)×列宽`、**列由 parent 递归决定**、**同 tick 不分新节点**（`groupByTick`）、`isAtTip`/分支名/排序 |
| `unit-tree.test.cjs` | `buildTree` 深度/父子/分岔点/多根/悬空父/后代数 |
| `write-allowlist.test.cjs` | 静态扫 `api.js` 的 POST 端点 == `{/api/command,/api/advance,/api/fork}`、其它资产无写调用；动态真加载 `api.js` 记录 fetch 对表；**牙齿自证** |
| `gate-contract.test.cjs` | 文件在册 + **断言数下界**（`MIN_ASSERTIONS=61`，防"0 个测试也算通过"） |

## 二 ★ 门禁日志里 node 测试的片段（`logs/clean-verify-final.log`）

```
[INFO] --- exec:3.4.1:exec (frontend-unit-tests) @ simos-app ---
TAP version 13
# Subtest: all-required-test-files-are-present
ok 1 - all-required-test-files-are-present
...
# tests 61
# pass 61
# fail 0
# skipped 0
# todo 0
[frontend-gate] OK tests=61 pass=61 fail=0
```

## 三 ★ 故意违规的红证（MUST DO #4）

往 `api.js` 塞一条**未声明的写路径** `/api/evil`（`mutants/logs/m1-deliberate-violation.log`）：

```
not ok 55 - api.js-declares-exactly-the-three-allowed-write-endpoints
  error: '扫描必须非空且恰三条：["/api/advance","/api/command","/api/evil","/api/fork"]'
gate_rc=1  red_points=1
orig_md5=fb5a5bb4e3fdfea60f556f9bdd16ccfb  mutant_md5=06d6962190015da3f9f693b2c45c5de2  restored_md5=fb5a5bb4e3fdfea60f556f9bdd16ccfb
```

★ **装置自证（m1b）**：同一条违规 + 把 allowlist 用例改成**永真断言** ⇒ `gate_rc=0`（**违规不再被红**）。
这证明"红"确实来自那条守卫，而不是别的；永真化后的用例被**拒绝**为有效装置（`logs/m1b-guard-turned-evertrue.log`）。

## 四 ★ Node 不可用 ⇒ 明确失败（`logs/node-missing.log`）

```
[INFO] --- exec:3.4.1:exec (frontend-unit-tests) @ simos-app ---
[ERROR] Command execution failed.
java.io.IOException: Cannot run program "/nonexistent/node" (in directory ".../simos-app"): Exec failed, error: 2 (No such file or directory)
...
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal ...:exec (frontend-unit-tests) on project simos-app: Cannot run program "/nonexistent/node" ...
```

模拟法：`-Dnode.executable=/nonexistent/node`（**未动系统 node**，未改文件）。fail-closed、无 skip/if 守卫。

## 五 ★ 行为不变的 e2e 证据

`e2e/run-e2e.sh`（改编自 M8-S，指向 m8t2；端口 **5831**，避开 5817/5818）：
- **真档副本**：`/tmp/m8t1-e2e-store-5942/simos.db`（19441 格，`md5=2348b936…`）
- 结果：`E2E RESULT: ALL PASS`，**PASS=23 / FAIL=0**，`PAGEERRORS []`，`r-m9-blocks-still-44` PASS
- ★ 判据 14 逐值复现（M8-S 权威值）：`test_nation` **渲染=JS=Java 246 顶点**、`m8s_lasso` **30 顶点**、
  格点残留 `5.68e-5` / `4.61e-5`、到最近格心 `0.99996`
- 原档 md5：`store_src_md5_before=2348b936…` = `after`（**未改原档**）
- `webui_synced map.js_md5=c3caa275458e97ceedb4883fe4e95466 timeline.js_md5=21ffb793d01fc8add668b2af2c1d00cb`

## 六 变异表（7 轮有效红 + 1 轮反证；每轮都自证原/变异 md5 非空且不同、还原 md5 回原）

| 轮 | 目标 | 变异 | 期望 | 实测红点 |
|---|---|---|---|---|
| **m1** | `api.js` | 塞未声明写路径 `/api/evil` | 红 | `api.js-declares-exactly-the-three-allowed-write-endpoints` ✅ |
| **m1b** | `api.js` + `write-allowlist.test.cjs` | 违规 + 守卫改永真 | **装置失效**（不再红）⇒ 拒绝 | `gate_rc=0`（**反证成立**：门禁的牙在这条守卫） ✅ |
| **m2** | `map.js` `hexLine` | 循环内 `i++` 造空洞 | 红 | `hexLine-length…` / `-no-gaps` / `-long-diagonal` ✅ |
| **m3** | `map.js` `regionBoundaryRings` | 顶点隔点抽稀 | 红 | 顶点数×2 / donut / 单 hex 六角 / 闭合 ✅ |
| **m4** | `modes.js` | "区域查看"放行 `map.SetTerrain` | 红 | `region-allows-no-write` ✅ |
| **m5** | `unitTree.js` | 深度 `+1`→`+2` | 红 | `buildTree-depth-parent-children-per-node` ✅ |
| **m6** | `timeline.js` | tick 键掺 `revision`（同 tick 分裂） | 红 | `groupByTick-merges-same-tick-into-one-node` ✅ |
| **m7** | `unit-tree.test.cjs` | 一条 `test(` 改 `test.skip(` | 红 | `assertion-count-is-not-below-the-frozen-floor` ✅ |

**存活项**：**无**（7 轮全部被杀 + m1b 为设计好的"装置失效"反证）。
★ m7 首轮（`m7-assertion-floor.log`，硬编码前）曾**存活**——当时的静态计数把 `.test(`（正则/字符串里的方法调用）
误计 3 处，且 `run-gate` 不查 skipped ⇒ 跳过一条测试不触底。**已当场修**：计数改 `^\s*test\(`、`run-gate` 增加
`skipped/todo>0 ⇒ exit 6`；重跑即红（`m7-skipped-test.log`）。这正是"护栏必须自证"抓到的**真判别力缺陷**，如实存档。

## 七 ★ 与派单措辞的分歧（以源码为准）

- 派单说"本单加前端测试 + pom ⇒ 门禁数字会变"。**实测未变**：node 测试经 `exec` 跑、**不进 surefire**，
  且本单**未加 Java 测试** ⇒ 逐模块仍是 `170/321/45/131/161/96 = 924`。门禁**内容**变了（多了 61 条前端测试），
  surefire **计数**不变。派单的"会变"是**推断**，与实测不符——按源码/实测记。
- 派单 m1 的"期望红"一栏与描述（"装置自证失败（应被拒绝）"）本身略有张力。按描述实现：m1b 展示"守卫永真 ⇒
  违规不红"，即**红来自守卫**；m1 展示"违规 ⇒ 红"。两者合起来才是"故意违规会被抓"的完整自证。

## 八 ★ 我未能核实的

1. **跨机器/跨 node 版本**：只在**本机 node v22.23.2** 跑过。`--test-reporter=tap` 需 node ≥19（更老版本
   会因坏选项**非零退出 ⇒ 构建红**，仍是 fail-closed，但未实测老 node 的具体报错文案）。
   计划 T2 说"版本差异要记录而非绕过"——此即记录。
2. **`mvn test` 单阶段**：只跑了 `clean verify`（含 test 阶段）。未单独跑 `./mvnw test` 验证 exec 执行顺序，
   但 `verify` 日志已含该 execution，且它在 surefire 之后、jar 之前。
3. **`windows`/无 `node` 名（非典型安装）**：未测；`node.executable` 可覆盖，路径含空格未测。
4. **前端纯函数与页面真 DOM 路径的等价**：`regionBoundaryRings` 等已由 e2e 逐值证明；但 `hexLine`/`axialNeighbors`
   的门禁断言是纯函数级，未在门禁内对拍"页面套索实际调用路径"（e2e 覆盖了套索，属证据级）。

## 九 ★ 实测 vs 推断

| 项 | 实测 | 推断 |
|---|---|---|
| node 版本 | 本机 v22.23.2 跑通 | 老版本行为未测 |
| 门禁数字 | `924 = 170/321/45/131/161/96`（java）+ 61 node | 派单说"会变" ⇒ 与实测不符 |
| Node 缺失 | `Cannot run program "/nonexistent/node"` + `BUILD FAILURE` | 其它机器同型未测 |
| 行为不变 | e2e 23/23、c14 逐值、原档 md5 不变 | —— |
| 变异 | 7 轮红 + 1 反证，0 存活 | —— |
| `node --test` 无匹配文件的 rc | **rc=0**（实测）⇒ 必须自查计数，故有 `run-gate` | —— |

## 十 证据索引（`.superpowers/sdd/2026-09-19-map-edit/t2-evidence/`）

- `logs/clean-verify-final.log`（末轮全量门禁：node 61/61、6 模块 SUCCESS、BugInstance 0×6、ERROR 0）
- `logs/clean-verify.log`（首轮全量门禁，同绿）
- `logs/node-missing.log`（Node 缺失红证）
- `logs/e2e-clean.log` / `logs/e2e-clean-server.log`（真档 e2e 23/23）
- `e2e/run-e2e.sh` / `e2e/e2e.cjs` / `e2e/BoundaryProbe.java` / `e2e/run-clean/result.json`
- `mutants/mut-run.sh` / `mut-run-2.sh` / `logs/m*.log`（每轮含 `orig_md5`/`mutant_md5`/`restored_md5`）
