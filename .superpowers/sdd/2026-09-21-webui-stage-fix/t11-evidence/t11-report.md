# T11 关账报告 —— 富世界地图 + 区域复刻（`--demo` 升级）

> 分支 `wsf/t11`，worktree `.claude/worktrees/wsf-t11`，基线 `b4a2e12`（含 T10 合并提交 `sd.StartDecision`）。
> 证据目录 `.superpowers/sdd/2026-09-21-webui-stage-fix/t11-evidence/`。

## 〇 一句话

把 `v17levant` 复刻成 simos 数据集，签入 `simos-app/src/main/resources/worlds/v17levant.json`；`--demo` 改由 `RichWorld` 种入它（`DemoWorld` 保留为测试夹具）。复刻走**真读路径**（`Envelope.decode` + 模块 codec），并经**真引擎**（`bootstrapGenesis` + `Replay`）往返一致。

## 一 交付物

| 文件 | 变更 |
|---|---|
| `tools/gsimap_import.py` | 扩：连通性融合优先级 `edges > edgeTags > riverMask`；`edgeTags[dir] → EdgeRef` 忠实转换；`riverMask` 交叉校验；`lowland` 补入 `LOSSY_KEYS`；unit 载荷补 `commandChains`（T1 后必填） |
| `tools/check_v17levant_import.py` | 新：对拍脚本（资源 ↔ 源档逐值 + 导入器行为自证），不入 reactor |
| `simos-app/src/main/resources/worlds/v17levant.json` | 新：导入器产出的 checkpoint 信封，**逐字节签入**（3,814,513 B，`md5=76384c9f89208c3bb8f5de210cd61812`） |
| `simos-app/.../app/demo/RichWorld.java` | 新：资源 → `SimulationState`（真读路径解码；补空 sd 切片） |
| `simos-app/.../app/ShellMain.java` | `--demo` 改用 `RichWorld`；抽出 `shouldSeedGenesis` 供 C25 测试 |
| `simos-app/.../demo/RichWorldTest.java` | 新（8 条）：计数 / 直方图 / 边 / 多对多 / 真引擎往返 / 非空库不覆盖 |
| `simos-app/.../ShellMainSeedGuardTest.java` | 新（1 条）：C25 空库判定 |

## 二 判据逐条实测（spec §七.5）

| 判据 | 实测值 | 证据 |
|---|---|---|
| **C23** hexCount | **59223**（源档同值） | `logs/checker.txt` `hexes.count PASS` |
| **C23** provinces | **98** | 同上 `provinces.count PASS` |
| **C23** 地形直方图 | simos 5 类：`ocean 14927 / plains 28347 / desert 746 / low_hills 14107 / mountains 1096`（合 59223）；**lossy 算术**：`plains = lowland 16933 + plains 11315 + swamp 99` | `checker.txt` `histogram.matches-lossy-merge PASS`；`import-report.txt` |
| **C23** 河流边 | **240** 条（全部 `kind=river`、两端相邻） | `checker.txt` `edges.count` / `edges.adjacent-and-all-river PASS` |
| **C24** tag 分布 | **`Nation` 97 + `王国` 1 = 98** | `checker.txt` `regions.tags PASS` |
| **C24** 多对多 | 81 个 hex 同属 2 区域；样例 `(-105,67) → {区域14, 石冠诸部}` | `checker.txt` `regions.multi-owner-sample` / `regions.has-at-least-one-multi-owner PASS` |
| **C25** 非空库不覆盖 | `shouldSeedGenesis(true, {main}) == false`；`bootstrapGenesis` 二次创世抛 `IllegalStateException`（"库非空"） | `logs/shellmainseedguard.txt`；`RichWorldTest.bootstrapRefusesToOverwriteANonEmptyStore` |
| **C26** 绝不录 Info | 资源信封 `info={"bySubject":{}}`；`RichWorld.state(...).info()` == `InMemoryInfoSystem.empty()` | `RichWorldTest.infoStaysEmpty`（m3 可杀，见 §四） |

### ★ `lowland` LOSSY（单列，控制器裁定）

- 源档 `lowland` **16933 格（28.6%）**，simos 词表**无对应** ⇒ 映射到 `plains`，**显式标 LOSSY**（本次把 `lowland` 补进 `LOSSY_KEYS`，此前是"未标 LOSSY 的静默丢失"）。
- 另一条既有 LOSSY：`swamp 99 格 → plains`。
- 合计 `plains` 承接 **28347 格 = 11315（源 plains）+ 16933（lowland）+ 99（swamp）**，直方图算术对得上（`checker.txt`）。
- 导入报告逐条打印 LOSSY（`logs/import-report.txt` 的 "LOSSY 映射" 段）。

### ★★ 与任务书数字的差异（诚实披露）

- 任务书写"**河流边数（248）**"。**实测 240**。248 是"`edgeTags` / `riverMask` **非空的 hex 格数**"（两者各 248 格，且是同一集合）；有向条目共 **480**，每条无向边被两端各记一次 ⇒ 唯一无向边 = **480 ÷ 2 = 240**。判据按**忠实转换**（240）落，**不**按 248 造数。
- spec §五.5 称"**带洞区域 … `v17levant` 是真样本**"。**实测不成立**：98 个 province 的环数分布 = `{1: 98}`（每个恰 1 环）⇒ **无带洞区域、无断开分量**。spec 这一句与源档不符（本任务不改 spec 正文，如实记于此）。

## 三 铁律 1/2/5 与"真读路径"证明

- **不是绕过 `Command → ChangeSet → Revision`**：富世界经 `bootstrapGenesis`（CLAUDE.md 在案的**唯一**绕过 `submit` 的写路径、**只在空库**）种入；它落一行 `(main,1)` revision + 一份 `CheckpointEncoder` 编码的 checkpoint。
- **真读路径读回且往返一致**（M6 的证法）：
  1. **单元级**：`RichWorldTest.bootstrapsAndReplaysThroughTheRealEngine` —— `RichWorld.state` 解码资源 → `bootstrapGenesis` 编码落盘 → `Replay((main,1))` 解码 == 原状态（`SimosObjectMapper`/4 codec 全真）。
  2. **进程级**：`java -jar simos-app-*-shaded.jar --store <导入目录>`（**不给 `--demo`**）起在 5911/5915/5913/5916，`GET /api/map/overview` → **200 / 601,486 B**，`hexCount=59223`、`regions=98`、`edges=240`、`blocks=782`、`terrainTypes` 5 类；**日志零异常**；导入档 `simos.db` 与 checkpoint 的 md5 **前后一致**（未覆盖）。证据 `logs/store-proof.log` / `store-proof-overview.json` / `store-proof-summary.txt`。
- **`Region` 硬校验通过**：overview 的 `regions` 能解出，说明 `Region` 构造期的 `boundary.equals(RegionBoundary.of(hexes))` 接受了 py 算出的边界（环算法移植成功）——这是唯一"不是搬字段"的活。
- **可复现**：重跑导入器产出的 checkpoint 与签入资源**逐字节相同**（`cmp` 无输出）。

## 四 门禁

- `./mvnw clean verify` **rc=0**、**第 1 次尝试**（`logs/clean-verify.attempt1.log`，md5 `cd4c04af2ea9a36776b0430e7aec4e0f`）。
- **★ 最终绿轮 = `logs/clean-verify.after-mutants-GREEN.log`**（变异轮之后复跑，md5 `8edb2a1605b64b0d64fa71fdf9d02060`，rc=0）。
- 模块 **8/8 SUCCESS**：`SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp`。
- 用例总数**现场重算**（只取模块汇总行）：**1356** = `170 / 368 / 45 / 259 / 178 / 129 / 207`。
- **增量干净**：T10 终态 = `170/368/45/259/178/129/198`（1347）⇒ **app 198→207 = +9**（`RichWorldTest` 8 + `ShellMainSeedGuardTest` 1），其余六个模块逐字不变。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**；前端 `[frontend-gate] OK tests=164 pass=164 fail=0`。
- `DemoWorldTest`（5 条）**不动仍绿**。

## 五 变异（装置 `mutants/mut-run.sh`，九道门禁）

| 轮 | 靶子 | 结局 | 红点（被保护断言） |
|---|---|---|---|
| m1 | `RichWorld` 解码后丢掉 `edges`（丢河流） | **KILLED** | `RichWorldTest.riverEdgesAre240AdjacentAndAllRiver:101` `Expected size: 240 but was: 0` |
| m2 | `ShellMain.shouldSeedGenesis` 去掉空库判定 | **KILLED** | `ShellMainSeedGuardTest:21` `Expecting value to be false but was true` |
| m3 | `RichWorld` 往 `info` 塞一条（C26） | **KILLED** | `RichWorldTest.infoStaysEmpty`（`bySubject` 非空） |
| m4 | 导入器：`edges` 空时不回退 `edgeTags` | **存活（等价）** | 见下 |
| m4b | 导入器：**两份表示都不回退** ⇒ 0 边 | **KILLED** | `checker` `edges.count FAIL` + `RichWorldTest.riverEdges…:101` `240 but was: 0` |
| m5 | 导入器：去掉 `edgeTags`/`riverMask` 交叉校验 | **KILLED** | `checker` `cross-check.rejects-mismatch FAIL — 不一致却通过了` |
| m6 | 导入器：`lowland → desert`（地形映射写错） | **KILLED** | `checker` `histogram FAIL {plains 11414, desert 17679}` + `RichWorldTest.hexCountAndTerrainHistogram…:73` |

**m4 存活归因（等价变异体，非门禁漏跑）**：`v17levant` 的 `edgeTags` 与 `riverMask` **逐格一致**（248 格同一集合，交叉校验正是钉这条）。去掉 `edgeTags` 回退后，代码落到 `riverMask` 回退 ⇒ **产出同样的 240 条边** ⇒ 行为未变。m4b（把两份回退都去掉）当场被杀 ⇒ **"河流边数"这条判据确实有判别力**。九道门禁对 m4 全绿（`orig_md5 != mutant_md5`、`restored_md5 == orig_md5`、`COMPILATION ERROR=0`）。

## 六 我未能核实的

1. **`v17levant` 的 3 份副本只用了 `workspace/worlds/v17levant`**（与 `testspace/worlds/v17levant_2` 的 `n0000_map.json` 逐字节同源，md5 `6424529b…` 已核）；`testspace` 的 `n0009~n0012` 与 `caches/` **未用**（属 T12 文档产出）。
2. **`--demo` 的首启端到端（真起 `ShellMain --demo` 到空库 + GUI 点选）未跑**：C25 用 `shouldSeedGenesis` 纯函数 + `bootstrapGenesis` 拒绝非空库两条证明；`--demo` 的 `RichWorld` 种入路径本身未在真进程里走一遍（但 `bootstrapGenesis(RichWorld.state(...))` 已由 `RichWorldTest` 真引擎覆盖）。
3. **`simos-app` 的 3.8MB 资源对启动/内存的影响未测**（只在 `--store` 读回时观测到 overview 808ms/601KB）。
4. **`Region` 边界算法的 py↔Java 一致性只由"解码不抛"证明**，未逐环逐顶点对拍（M6 亦然）。
5. **导入器不幂等**：对**已存在**的输出目录重跑会 `UNIQUE constraint failed: revisions.branch, revisions.revision`（M6 既有行为，本次未改；证据目录的复现命令一律先 `rm -rf`）。
6. **`terrainBlocks` 的块切分（`TerrainBlocks.split`）是 Java 侧在解码时算的**，资源里不含块；`blocks=782` 是 Java 侧结果，未与源档任何字段对拍（源档的 `terrainBlocks` 已按 M6 口径丢弃）。
7. **跨 JVM 字节稳定性**未测（导入器输出在同一 Python 下逐字节稳定已证；跨解释器/哈希盐未测）。
8. **`--demo` 与 MCP/审批端口拓扑的交互**（T4 已关账）未在本任务复验。

## 七 与既有约定的关系

- **不越界**：未做 T12（文档 `.md` 产出）、未碰 spec/计划正文、未动别的 worktree、未 `git add -A`、未 `mvn install`、未 kill 5818。
- **同文件串行**：`ShellMain.java` 被 T4/T11 改，T4 已关账；本任务在其终态上改，未重跑 T4 变异轮（T4 变异靶子不在 `ShellMain`，且本次改动仅 `--demo` 分支与一个纯函数；如控制器要求可补）。
- **`tools/**` 不入 reactor**：导入器/对拍脚本的判据由 `check_v17levant_import.py` 自带（含行为自证）。
