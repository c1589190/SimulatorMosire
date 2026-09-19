# M7 T7 报告 —— 单位移动与编辑模式（判据⑤ / R8）

- 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/m7t7`（分支 `m7/t7`，基线 `8ab87cb`）
- 边界：**零 Java 改动**、零依赖、零构建（无框架 / 无 npm / 无 CDN / 无外部字体）
- 本报告路径：`.superpowers/sdd/2026-09-19-webui-plan/t7-report.md`（证据在 `t7-evidence/`）

---

## 一 改了什么 / 为什么

| 文件 | 增删 | 改动 | 为什么 |
|---|---|---|---|
| `simos-app/src/main/resources/webui/app.js` | +100 / -10 | 新增 `commandEnvelope(type,payload)` + `writeCommand(type,payload)`（唯一写入口：组信封 → `POST /api/command` → 成功重取 head 并推进游标；409 ⇒ 重取 + 拉到 `current.revision`；422 ⇒ 原样回 `reason`）；抽出 `renderShellState(meta)` 让顶栏在**写后**也刷新（不再只靠 5s 轮询） | 判据⑤ 的写路径与 MUST DO #5/#6；`writeCommand` **不碰 DOM**，显示位置归 map.js |
| `simos-app/src/main/resources/webui/index.html` | +44 | 左栏加 `<section class="unit-editor" data-modes="unit">`：路线模式三按钮 + 改上级 / 改编制 / 解散 / 新建单位四个 fieldset | MUST DO #2/#3/#4；`data-modes="unit"` ⇒ **只在单位模式可见**（其它模式仍只读） |
| `simos-app/src/main/resources/webui/map.js` | +401 / -10 | ① `positionOf(id)`（渲染器内）；② `setSelectedUnit(id)` + 选中单位圆点外描环（MUST DO #2「选中并高亮」）；③ `workbenchSelect` 在 `mode==="unit"` 时把「点格」路由成移动/加点，其它模式**原样只读**；④ `hexDistance`/`isAdjacent`/`parseEquipmentText` 三个纯函数；⑤ 编辑器全部接线（`submitPlaceAt`/`submitRoute`/`submitReparent`/`submitStrength`/`submitDisband`/`submitCreate`）；⑥ `window.SimosMap.unitPosition` 暴露给 e2e | 判据⑤ 全部交互；写一律经 `app.writeCommand`（R8 的 `/api/command`） |
| `simos-app/src/main/resources/webui/styles.css` | +50 | `.unit-editor` / `.unit-edit-group` 等 | 编辑器表单在窄左栏里的可用性（全复用既有 `--*` 变量） |

**交互形态（我定的）**：
- **点选式移动**：单位模式下点单位 ⇒ 选中（地图描环 + 左栏详情）；再点**目标格** ⇒ 发 `unit.PlaceAt {id, hex:{q,r}}`。选中态**保持**（可连续移动）。
- **路线式移动**：点「路线模式」开 ⇒ 依次点**相邻格**连成点列（非相邻/重复格当场拒绝并提示）⇒ 点「下路线」发 `unit.PlanRoute {id, waypoints:[单位当前位置, …点列]}`。起点由 `GET /api/unit/{id}` 当场取**权威位置**（不用渲染缓存），服务端 `Route` 再验一遍逐格相邻。
- **编制**：改上级（输入父 id，留空=清根）、改编制（人数 + `步枪=50；火炮=2` 文本 ⇒ `{名:整数}`，整份替换）、解散（清掉选中态）、新建单位（含 `parent`）。

---

## 二 逐模块数字（全量 `clean verify`）

命令 `./mvnw clean verify`，日志 `t7-evidence/logs/full-verify.log`：

- **rc=0**，**833 条 = 170 / 255 / 45 / 131 / 154 / 78**（util / map / social / unit / core / app）
- 基线 `8ab87cb` 同值 ⇒ **delta 0**（纯前端资源，不动任何测试）
- `BugInstance size is 0` **×6**；`[ERROR]` **0** 行；`BUILD SUCCESS`
- 定向 `./mvnw -q -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test` rc=0（`WebuiAssetsTest` **8/8**，报告 mtime 落本轮），日志 `logs/targeted.log`
- `./mvnw -q spotless:apply` rc=0（Spotless 只扫 Java；本轮无 Java 改动）

---

## 三 e2e 每步实测值（真 `ShellMain --demo` + Playwright；34 步全 PASS）

脚本 `t7-evidence/e2e/{run-e2e.sh,e2e.cjs}`，日志 `logs/e2e-clean.log`，原样值 `e2e-run/e2e-values.json`。数据集 = `--demo` 首启的 DemoWorld（`u-1`@(1,1)，三格沙漠走廊，`main@1`，tick 5）。

| 步 | 断言 | 实测值 |
|---|---|---|
| mode | 进入单位模式 | `body[data-mode]=unit` |
| **a** | 点单位选中 | `{kind:"unit", id:"u-1"}` |
| a | 移动前位置 | `{q:1,r:1}` |
| a | **`position` 变目标格** | `{q:1,r:1}` → **`{q:1,r:2}`**（`GET /api/unit/u-1`） |
| a | **head +1** | **1 → 2** |
| a | **时间轴节点 +1** | **1 → 2**（DOM `.tl-node[data-branch=main]`） |
| a | 地图层也刷到新位置 | `window.SimosMap.unitPosition("u-1") = {q:1,r:2}` |
| a | 顶栏写后刷新 | `#shell-state` 含 `rev 2` |
| a | 状态文案 | `"已移动 u-1 → (1,2)"` |
| **a2** | 路线点列 | `[{q:1,r:3}]`（与当前位置 (1,2) 相邻） |
| a2 | **`unit.PlanRoute` 真发** | `/api/unit/u-1` 的 `movement` **false → true**；head 2 → 3 |
| **b1** | `CreateUnit` u-2（无上级） | `/api/unit/u-2 = {id:u-2, position:{1,3}, parent:null}`；head 3 → 4 |
| **b2** | **`ReparentUnit` u-1 → u-2** | `parent` **null → "u-2"**；head 4 → 5 |
| **b3** | `SetStrength` | `member` **100 → 123**、`equipment` **{步枪:50} → {步枪:7}**；head 5 → 6 |
| **b4** | `CreateUnit` u-3（**带 parent=u-2**） | `/api/unit/u-3 = {parent:"u-2", position:{1,1}}`；head 6 → 7 |
| **b5** | 点地图选中 u-3 ⇒ `DisbandUnit` | 选中 `{kind:"unit",id:"u-3"}`；`GET /api/unit/u-3` **404**；head 7 → 8 |
| **c** | **422 ⇒ 显示 `reason` 原文** | `"父单位不存在: ghost-parent"`（页面 `#unit-edit-status` 原文）；head 不变（8） |
| **d** | 外部推 head（page 外 `POST /api/advance`） | head 8 → **9**；页面 `revision` 仍 **8**（过期） |
| d | **409 ⇒ 提示"末端已移动"** | `"末端已移动，已自动重取最新状态"` |
| d | **head 自动重取** | 页面 `revision` **8 → 9** == 新 head |
| d | 409 不写 | 位置保持 `{q:1,r:3}` |
| **e** | **写路径 allowlist（R8）** | 全过程 **9 个非 GET**，path 去重 = **`["/api/command"]`** ⊂ `{/api/command,/api/advance,/api/fork}`；原样清单 `e2e-run/non-get-requests.json` |
| **f** | **只读模式未被污染** | 切「常规查看」后点单位 + 点格：非 GET **9 → 9**（新增 0）；选择仍可用（`{kind:"hex",q:1,r:1}`） |

截图（`e2e-run/`）：`screenshot-move-after.png`（u-1 从 (1,1) 移到 (1,2)，带选中环，左栏 position=q=1,r=2）、`screenshot-composition.png`（倒树：**根 u-2 在下**、u-1/u-3 在上，u-1 的 parent=u-2 / member=123 / 步枪=7 / movement=true；地图三单位各就各位）。

---

## 四 变异表（2 轮，0 存活；装置 `mutants/mut-round-e2e.sh`，九道门禁逐条自证）

| m | 护栏 | 变异体（源 + classpath 两份推送） | 期望红 | 实测红点 | 结论 |
|---|---|---|---|---|---|
| **m1** | **R8 写路径 allowlist** | `api.js`：`postJson("/command",…)` → `postJson("/raw-write",…)`（全路径 `/api/raw-write`） | e2e e 步 | **`STEP e-write-allowlist: FAIL`**（+ 写全 404 的连带红：a/b 系列；e 步断言仍跑到并红） | 杀死 |
| **m2** | **移动真的生效** | `map.js`：`PlaceAt` 载荷 hex 改成 `active.positionOf(id)`（选中单位**自身**所在格） | e2e a 步「position 变成目标格」 | **`STEP a-position-after-target: FAIL {"before":{1,1},"after":{1,1}}`**（+ 连带 `a-map-reflects-move`） | 杀死 |

装置自证（两份日志 `logs/m1.log` / `logs/m2.log` 的「装置补记」段）：
- 干净世界 md5 相等（源 == classpath == 备份）；变异体字节不同；两份推送一致；
- 服务器真起（server 日志含「GUI 服务器已启动」，mtime 落本轮）；
- e2e 真红且**红点是被保护断言**；
- 源与 classpath **逐字节还原**（`restored_src`/`restored_classes` == `orig_md5`）；装置日志自指（本轮推送的 md5 写进日志本身）。
- m1 `orig=c0e755dd…` / `mutant=bedfb2e9…`；m2 `orig=2abc4794…` / `mutant=9ffc419b…`。

**为让 m1 能被杀做的一处装置加强**：把 `waitRevisionAbove` 与编辑器按钮点击改成**不抛**（超时/禁用只记 `FAIL`/`CLICK-SKIP` 并继续）——否则 m1 在 a 步超时抛异常、`run()` 提前中止，**e 步的 allowlist 断言永远跑不到**，护栏就成了杀不掉的装饰。这是「变异要能被杀」的硬约束落点（同 T6 裁定 72.5）。

---

## 五 `PlanRoute` 稀疏路点缺口的处理（M5 挂起项 / spec §九.4）

- **后端现状（源码为准，非本单转述）**：`PlanRouteHandler` 把 `waypoints` **同时当作 `Route` 的 `path`**，`Route` 构造期要求 path **逐格相邻的简单路径**且首尾等于 waypoints 首尾。⇒ 载荷**只给 `waypoints`、没有独立的 `path` 字段**，因此**无法表达"稀疏路点 + 更细 path"**（M5 挂起项原文）。
- **T7 的处理**：路线模式收集的**每个点就是路径的一格**——客户端保证相邻（首点对单位权威位置、后续对前一点；非相邻/重复当场拒绝），发出去的 `waypoints` 即逐格 path。**不伪造稀疏语义**。
- **要真正支持稀疏路点**：需先补载荷字段（如 `{waypoints:[…], path:[…]}` 两段）并让 handler 分别映射——**M7 不做**，如实记为开口项（与 spec §九.4 一致）。

---

## 六 偏离候选

1. **422 的触发形态与派单书示例不同**：派单书 §4.2.c 举例「`unit.PlaceAt` 给一个不存在的单位 id」，但**点选式移动只能发选中单位的 id**（UI 无从输入任意 id）。我改用**UI 真能触发**的域拒绝：`ReparentUnit` 到一个不存在的父 id（表单可输入）⇒ `422 {result:"rejected", reason:"父单位不存在: ghost-parent"}`，页面显示 reason 原文。**判据（显示 reason 原文）等价，触发路径更真实**。
2. **`renderShellState` 顺带修了顶栏 5s 轮询的滞后**：写成功后顶栏 `rev` 立即跟上（原先要等下一次 5s 轮询）。这不是 MUST DO，但属「写后可见」的自然补全，且被 e2e `a-shell-state-refreshed` 钉住。可推翻。
3. **e2e 装置的一处真坑（记给后续任务）**：`page.fill` 会把页面滚下去，`#canvas` 的 `boundingBox().y` 变负 ⇒ `page.mouse.click` 落在视口外、**点击静默无效**（本轮 b5 第一次跑就中了：点 (1,1) 没选中 u-3）。修法是点击前 `scrollIntoViewIfNeeded()`。与 CLAUDE.md 的「把没发生伪装成没发生」同族。

---

## 七 我未能核实的

1. **`PlanRoute` 在"多段稀疏路点"下的行为没测**——载荷结构本身不支持，只测了逐格相邻的单段路径（(1,2)→(1,3)）。
2. **路线模式的"非相邻/重复格拒绝"只在客户端验过逻辑**（`hexDistance`/重复检查），**没造 e2e 红**；服务端 `Route` 的相邻校验是既有单测覆盖，不是本轮。
3. **`CreateUnit`/`SetStrength`/`ReparentUnit`/`DisbandUnit` 的边界拒绝**（负人数、重 id、成环、有下属解散）**只测了成功路径**；唯一测到的拒绝是 `ReparentUnit` 的 ghost parent（422）。这些域规则由 M3 单测覆盖，本轮不重复。
4. **真实大图（19441 格）上的单位移动未测**——e2e 只用 `--demo` 三格走廊（派单书要求的就是 `--demo`）。大图的点击命中/重载代价未测。
5. **CJK 装备键（`步枪`）经 URL/JSON 往返**在本机通过；**换机字体/编码未复验**。
6. **`#shell-state` 显示的 `meta` 是默认分支 main 的 head**，不是当前游标分支——多分支下点分岔后顶栏可能仍显示 main；未测多分支场景。
7. **只读模式「无写」的证明是"页面级非 GET 计数不增"**，不含"页面内 JS 逻辑上不可达写函数"的静态证明（后者由 `AppWritePathGuardTest` 的 Java 侧扫描覆盖一半：它只扫 `simos-app/src/main/java`，**不扫 `webui/**` 的 JS**）。⇒ **前端 JS 仍无 CI 级 allowlist 静态护栏**（T5 已记为系统性开口项；本轮的 e2e 是行为级证据，Maven 里没有对应断言）。

---

## 八 证据索引（`t7-evidence/`）

| 路径 | 内容 |
|---|---|
| `logs/full-verify.log` | 全量 `clean verify`（rc=0、833、BugInstance 0×6、ERROR 0） |
| `logs/targeted.log` | 定向 `WebuiAssetsTest`（8/8） |
| `logs/spotless.log` | `spotless:apply` rc=0 |
| `logs/e2e-clean.log` | e2e 34 步全 PASS + `NON-GET-REQUESTS` 清单 |
| `logs/clean.server.log` | 干净轮 ShellMain 日志（含「GUI 服务器已启动」） |
| `logs/m1.log` / `logs/m1.server.log` | m1 变异轮（含装置补记自指） |
| `logs/m2.log` / `logs/m2.server.log` | m2 变异轮（含装置补记自指） |
| `e2e/e2e.cjs` / `e2e/run-e2e.sh` | e2e 脚本与起服装置 |
| `e2e-run/e2e-values.json` | a/a2/b/c/d/e/f 每步原样实测值 |
| `e2e-run/non-get-requests.json` | R8 抓到的全部非 GET 请求 |
| `e2e-run/screenshot-move-after.png` | ① 移动后（u-1 红点在 (1,2) + 选中环） |
| `e2e-run/screenshot-composition.png` | ② 编制改动后的倒树（u-2 在下为根） |
| `mutants/mut-round-e2e.sh` | 变异装置（源+classpath 双推、逐字节还原、日志自指） |
| `mutants/m1/api.js` / `mutants/m2/map.js` | 两个变异体 |
| `mutants/orig/{api.js,map.js}(.classpath)` | 干净世界备份 |
| `mut-manifest.md5` | 原件/变异体 md5 清单 |
| `mut-runs/m1/` / `mut-runs/m2/` | 变异轮的 e2e 输出（红点证据） |
