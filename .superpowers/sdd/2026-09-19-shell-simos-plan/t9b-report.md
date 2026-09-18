# M5 T9b 报告 —— 可运行性收尾（core 创世 bootstrap + `--demo` 首启 + 地图单位标记）

> **任务**：M5 T9b（控制器裁定 60 的收尾；**不在** `2026-09-19-shell-simos-plan.md` 的 T1~T12 里）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t9b`，分支 `m5/t9b`，基线 `a218a7f`（PREFLIGHT 已自证）
> **日期**：2026-09-19
> **范围**：core 创世写路径 + app `--demo` 首启 + `map.js` 单位标记；**不动** `GuiServer`/`ApiViews`/`StaticHandler`/`Shell`/`ShellConfig`。

---

## 1 交付物

### A. `CoreSimos.bootstrapGenesis(SimulationState genesis)`（simos-core）

- 空库（`timeline.branches()` 为空）⇒ 落一行 `(main, 1)`：`parent` 空、变更集 `WorldChangeSet.empty()`、
  时刻取 `genesis.meta().timestamp()`、`command_type="core.Bootstrap"`、`initiator="system:bootstrap"`、
  `commandId`/`correlationId` 各一个新 UUID；随后写 `(main,1)` 的 checkpoint（`CheckpointEncoder` + 已注册 codec）。
- 库非空 ⇒ `IllegalStateException`（消息含"空库"），**绝不覆盖已有世界**。
- ★ **唯一绕过 `submit` 的写路径**（世界创建发生在命令面存在之前）。见 §4.1 的 S8 偏离。

### B. `simos-app/.../app/demo/DemoWorld.java`（新）

- `state(String mapId)`：确定性创世状态（无随机/时钟），坐标 `(main,1)`、时刻 `SimosTimestamp.of(5)`。
- 走廊三格 `[1,1]→[1,2]→[1,3]`（desert）；单位 `u-1`「第一连」在 `[1,1]`（member 100、mobility 500‰ ⇒
  desert 逐边 **1500** 毫 MP）；`[1,1]` 人口 anchor 10000 @0、10%/tick ⇒ t=5 时 **15000**。
- `mapId` 只做非空白校验（`GameMap` 无 id，M2 遗留；见 §4.5）。

### C. `ShellMain` `--demo`

- 解析五个开关（原四 + `--demo`）；`parse` 返回 `Parsed(ShellConfig, boolean demo)`。
- 空库时 `coreSimos().bootstrapGenesis(DemoWorld.state(config.mapId()))`，并打**可点击**的
  `WebUI 就绪（点击打开）：http://127.0.0.1:<boundGuiPort()>/`；非空库不碰。

### D. `map.js` 单位标记层

- 叠加 `/api/units` 的 `effectivePosition` 到 Canvas：`#e8503a` 实心圆 + 白环 + id 短标签；图例加"单位 N"。
- **悬停**显示 `id · 名称`（`canvas.title` + pointer 光标）；**点击**标记选中其所在格，状态区显示
  `单位 <id>：<name>`，并照常加载该格 facet。只读、同源（无绝对 URL）。
- `map.html` 描述行补一句"红色圆点为单位标记"（minor）。

### E. 测试

| 文件 | 新增条数 |
|---|---|
| `simos-core/.../CoreSimosTest.java`（扩） | **+3**（`bootstrapGenesisOnEmptyStoreWritesRowCheckpointAndReplaysExactState` / `...RefusesANonEmptyStoreBeforeAnyCommand` / `...AfterASubmit`） |
| `simos-app/.../app/demo/DemoWorldTest.java`（新） | **5**（确定性 + 三切片 / 走廊三格 / 单位位置 + 逐边 1500 / 人口 15000 / 空白 mapId 拒绝） |

---

## 2 实测数字

### 定向

`./mvnw -pl simos-core,simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CoreSimosTest,GuiApiTest,WebuiAssetsTest,AppWritePathGuardTest,DemoWorldTest' test`
⇒ `rc=0`；`CoreSimosTest 10` + `GuiApiTest 12` + `WebuiAssetsTest 5` + `AppWritePathGuardTest 2` + `DemoWorldTest 5`，全绿。

### 全量（`./mvnw clean verify`，`logs/full-verify.log`）

`rc=0`；**789 = 170 / 255 / 45 / 131 / 150 / 38**（util / map / social / unit / core / app）——**7/7 reactor entries SUCCESS**；
`BugInstance size is 0` **×6**；`^[ERROR]` **0 行**；Spotless `keeping … files clean` ×6。

**对基线 781 = 170/255/45/131/147/33 的逐模块差**：

| 模块 | 基线 | 现在 | Δ | 来源 |
|---|---|---|---|---|
| util / map / social / unit | 170/255/45/131 | 同 | 0 | 未动 |
| core | 147 | 150 | **+3** | `CoreSimosTest` 3 条 |
| app | 33 | 38 | **+5** | `DemoWorldTest` 5 条 |
| **合计** | **781** | **789** | **+8** | |

前四个模块一个不动，增长恰好来自两个新/扩用例类，**与预期逐条相符**。

---

## 3 变异自证（2 轮，九道门禁）

| m | 目标 | 变异 | 期望红 | 实测红 |
|---|---|---|---|---|
| m1 | `CoreSimos.bootstrapGenesis` | 不写创世 checkpoint（守卫成 `if (at.revision().value() != 1)`，恒假） | `replay((main,1))` 往返 | ✅ `CoreSimosTest.bootstrapGenesis...:529 » IllegalState`（回退到头、无 checkpoint，`main@1`） |
| m2 | `CoreSimos.bootstrapGenesis` | 行时刻写 `SimosTimestamp.of(0)` 而非 `genesis.meta().timestamp()` | 行时刻/元信息断言 | ✅ `CoreSimosTest.bootstrapGenesis...:531`（`(main,1)` 逐值重建含时刻） |

**九道门禁逐项（`logs/m1.log` / `logs/m2.log`）**：

| 门禁 | m1 | m2 |
|---|---|---|
| 字节不同 | `30d559a…` → `2c1d314…` | `30d559a…` → `d061938…` |
| 干净世界 | worktree_before == orig_md5 | 同 |
| 白名单推成**目标类名** | `cp` 到 `CoreSimos.java`（非变异体文件名） | 同 |
| `COMPILATION ERROR`=0 且 `Tests run:`≥1 | 0 / 10 | 0 / 10 |
| surefire 报告 mtime 落本轮 | 1789767360 ≥ 1789767357 | 1789767368 ≥ 1789767364 |
| 红点落**被保护断言** | 是（:529） | 是（:531） |
| `cp` 逐字节还原（非 `git checkout --`） | 还原 == orig | 同 |
| 轮内 md5 追加进日志**自身** | "装置补记"在案 | 同 |

> ★ **m1 的红的理由**是被保护行本身：删掉 checkpoint 写 ⇒ C19 第③项判"应当有"而磁盘无 ⇒ `Replay` 回退到头。
> **m2 的红为何落在 replay 断言而非行断言**：`Replay` 的元信息时刻来自 revision 行（不是 checkpoint 信封），
> 故行时刻被改坏后，`replay((main,1))` 返回的 meta 与 `genesis` 分叉 ⇒ :531 先红；行断言（:543 附近）未及执行。
> 两条都是**真**红，且都落在被保护行为上（行断言在 :542，本轮未及执行）。

---

## 4 偏离 / 取代说明

1. ★★ **S8 写路径偏离（本条任务的核心）**：spec §〇.2 **S8** 写"core 只读扩展：`branches()`/`head()`，**不加**任何写面"。
   T9b 的 `bootstrapGenesis` **正是一处新的 core 写路径**——它是对 S8 的**有记录偏离**。理由：GUI/`--demo` 首启下
   "空库 ⇒ 世界从哪来"是 M4 Task 17 遗留的真缺口（S8 只回答了"读"，没回答"创世"）。它是**唯一**绕过 `submit` 的写路径，
   且**只在空库、只此一次**。改 spec 的文字归控制器（本报告即记账）。
2. **计划偏离**：T9b **不在** `2026-09-19-shell-simos-plan.md` 的 T1~T12 里，是控制器裁定 60 的追加任务
   （T9 的两条真缺口：可运行性 + 全局单位标记）。§4.1 的 S8 偏离即由它引出。
3. **bootstrap 时序**：`Shell.start` 把"装配"与"起 GUI 监听"绑在一个调用里，**没有更早的缝** ⇒ bootstrap 发生在
   GUI 监听**之后**、`stop.await()` 之前。主线程在此窗口内不会接受任何用户交互（微秒级），但严格说不是"起监听前播种"。
4. **`ShellMain.parse` 的返回类型**：`ShellConfig` → `Parsed(ShellConfig, boolean demo)`。`parse` 是包级可见但**全仓无用例
   引用**（`git grep` 已核），故改签名无破坏面。
5. **`DemoWorld.state(mapId)` 的 `mapId` 只校验不存储**：`GameMap` 没有 id 字段（M2 挂起项），状态里无处安放它；
   app 层的 mapId 仍由 `ShellConfig`/GUI 路由持有。留形参以保持与 GUI 的 mapId 同一口径。
6. **`loadHex(q, r, statusLabel)` 新形参**：T9b 的**首个自动化 QA 当场抓到的真缺陷**——点单位标记时先显示
   `单位 id：名称`，随即被 `loadHex` 的 `q=x, r=y` 覆盖（写死状态文字所致）。修法：状态文字可注入，成功路径保留注入值、
   失败仍显示错误。**这正是"跑真 QA"的价值**（与 T9 两次真缺陷同族）。
7. **`map.js` 标记层数据源**：`/api/map/overview` 不含单位位置，故标记层**额外**拉 `/api/units`（裁定 60 第 3 条要求）。
   无 `/api` 改动（`GuiServer`/`ApiViews` 未动）。

---

## 5 我未能核实的

1. ★ **`--demo` 运行已实际执行**：是。`t9b-demo-evidence.sh` 起真 `ShellMain --store /tmp/t9b-demo-store --demo --gui-port 5817`，
   日志实测 `已种入演示世界（--demo）`；`/api/units` 返回 `u-1 @ {q:1,r:1}`；`/api/social/population` 返回 15000。
   （脚本每次 `rm -rf` store，故 bootstrap 走的是真空库；非空分支由 `CoreSimosTest` 两条拒绝用例覆盖。）
2. ★ **截图是否显示单位标记**：是。`MARKER_PIXELS 189`（对 `#e8503a` 的像素计数）、`MAP_STATUS 已载入 3 格（…单位 1）`、
   `map-unit-selected.png` 目视可见红色圆点带 `u-1` 标签。headless 无 CJK 字体 ⇒ 中文渲染为方框（**环境产物**，与 T9 同）。
3. ★ **悬停/点击显示 id/名称**：脚本实测 `HOVER_TITLE u-1 · 第一连`、`HOVER_CURSOR pointer`、`CLICK_STATUS 单位 u-1：第一连`。
   但这是**证据脚本**（playwright）的断言，**没有**进 `mvn` 测试套件（无浏览器测试基建）；因此它是"跑过并留痕"，不是"仓库门禁"。
4. **`--demo` 的真机浏览器目验未做**：仅 headless chromium；未在装有 CJK 字体的真机上目验中文与交互手感。
5. **非空库 `--demo` 的行为未在真运行里验**：脚本总用空库；"非空 ⇒ 跳过 bootstrap、不覆盖"由 `CoreSimosTest` 的
   `...RefusesANonEmptyStore*` 两条覆盖（方法级），**不是** `ShellMain` 端到端。
6. **`Canvas` 在超大/多单位/非连通地图上的标记表现未测**：本期只测 3 格 + 1 单位。
7. **m2 为何影响 `Replay` 的 meta**：红已实测、归因（元信息取自 revision 行）为**读码推断**，未单独探针自证。

---

## 6 证据索引

| 路径 | 说明 |
|---|---|
| `logs/full-verify.log` | `./mvnw clean verify`（rc=0；789 = 170/255/45/131/150/38；BugInstance 0 ×6；ERROR 0） |
| `logs/m1.log` / `logs/m2.log` | 两轮变异（九道门禁逐项 + 红的断言行 + 装置自指 md5） |
| `logs/evidence.txt` | `--demo` 起壳 + 页面/资产 200 + 关键 JSON + `node --check` ×5 + 截图/标记/HOVER/CLICK |
| `logs/shell-run.log` | `ShellMain --demo` 原始起动日志 |
| `screenshots/` | `index`/`map`/`unit`/`social` + `map-unit-selected`（红点 u-1 选中） |
| `mutants/` | `mut-round.sh`、`m1.CoreSimos.java`、`m2.CoreSimos.java`、`orig/CoreSimos.java` |
| `t9b-demo-evidence.sh` / `t9b-screenshot.mjs` | 一次性证据装置（非交付，留作复现） |
