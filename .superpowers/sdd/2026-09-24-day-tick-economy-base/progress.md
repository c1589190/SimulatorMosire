# SDD 台账 — 计划：docs/superpowers/plans/2026-09-24-day-tick-economy-base-plan.md

> 依据（设计稿，绑定权威）：`POLITICAL_ECONOMY_DESIGN.md` §3（日 tick）/ §9（协调器）/ §10（落点）/ §12（验收）
> 工作区：主检出 `/home/cna/SimulatorMosire`，分支 `ts/m1`，基线 `ff93954`
> 用户裁定（2026-09-24）：① 无 store 要保（迁移工具延后）② 范围 = 计划 + 1a + 1b ③ `speed` 以小时为基准（MP/小时）+ 日预算 ×24 + 允许卡在两格之间（余量跨日，位置向"进入下一格之前"取整）
> 纪律：Maven 一次一个（`flock /tmp/simos-maven.lock`）；一个文件一个 owner；子 Agent 只报结论，控制器负责 `clean verify` 关账

## 步骤状态

| # | 步骤 | 状态 | 证据 |
|---|---|---|---|
| 1a-1 | Core 单日步长 + 连续性校验 | **完成**（提交 `9639af1`） | `TimeAdvanceTest` 18/18；core 全量 206/206 |
| 1a-2 | 存档 `time_base=DAY` 标签 + 旧档门禁 | **完成**（提交 `9639af1`） | `Envelope` 必填 `timeBase`；`SqliteStore` 增 `store_meta` + `ensureTimeBase`；`EnvelopeTest` 10/10、`SqliteStoreTest` 11/11；`gsimap_import.py` 与 `worlds/v17levant.json` 同步 |
| 1a-3 | unit：MP/小时 + 日预算 ×24 | **完成** | `UnitMoves.HOURS_PER_DAY=24`；`UnitMovesTest` 新增"一天恰 24 格 / 两天 48 格"（字面量）与"卡在两格之间、余量跨日"；变异（删 ×24）⇒ 7 条红，已还原；unit 304/304 |
| 1a-4 | sd/social 日语义文案 | **完成** | 11 个文件 javadoc/注释改日；sd+social 186/186（控制器自验） |
| 1a-5 | app/前端/MCP 快进改逐日 | **完成** | `AdvanceTool`/`GuiServer` 的 `to` 缺省 = `from+1`；`timeline.js` 逐日循环（N 天 = N 条命令、逐条用新 revision、失败即停并报第几天）；`readout.js` 速率 ×24；`panels.js`/`index.html` 文案；前端门禁 284/284 PASS |
| 1a-6 | app Java 测试重标定 + `clean verify` 关账 | **完成** | 8 个 app 测试类改逐日循环/重算期望；`clean verify` **BUILD SUCCESS**（7 模块全绿：1948 tests / 0 fail / 0 err / 0 skip；Spotless+Checkstyle+SpotBugs+前端门禁+shade 守卫） |
| 1b-1..5 | 多模块提案 + 六切片创世 | 未开始 | — |

## 关账数字（2026-09-24 22:04 本轮 `clean verify`）

util 170 / map 379 / social 130 / unit 304 / core 206 / sd 186 / app 573 = **1948 tests，0 failures / 0 errors / 0 skipped**；
前端门禁 `tests=284 pass=284 fail=0`（下界两处同为 284）。

## 变异自证（新护栏的判别力，2026-09-24）

| 变异 | 结果 | 还原 |
|---|---|---|
| M1 删"`to == from+1`"判据（`if (false && …)`） | `TimeAdvanceTest` 18 中 1 红（多日推进用例），rc=1 | md5 与原件相同 |
| M2 删连续性判据 | `TimeAdvanceTest` 1 红（from 与 base 不符用例） | md5 相同 |
| M3 删"非空库无标签 ⇒ 抛"（`if (false && anyRevision)`） | `SqliteStoreTest` 11 中 1 红（旧档门禁用例） | md5 相同 |
| ×24 变异（unit agent 自做） | `UnitMovesTest` 13 中 7 红 | 已还原 |

## 既有红点的发现与处置（与本次改动无关，但挡关账）

- `simos-app` 的 `clean verify` 在**基线 `ff93954` 上就是红的**：9 条 SpotBugs（`EI_EXPOSE_REP`/`EI_EXPOSE_REP2`/`MS_PKGPROTECT`/`THROWS×2`/`URF_UNREAD_FIELD`/`REC_CATCH×2`/`NP_BOOLEAN_RETURN_NULL`）。
  证据：`git worktree add --detach /tmp/simos-baseline ff93954` + `./mvnw -pl simos-app -am clean verify -DskipTests` ⇒ 同样 9 条（BUILD FAILURE）。
- 处置：9 条全部当场修（返回只读接口 / 包内可见性 / 转发语义豁免 / 删死字段 / 抛新实例 / 收窄 catch / `Optional<Boolean>`），**零行为变更**；修后 `clean verify` 全绿。
- 记一条教训：此前"verify 绿"的台账来自**别的机器/别的时点**；本轮之前的最后一次 app 门禁从未在这台机器上跑过（此前 app 从未进过 SpotBugs 阶段是因为 core 先红 ⇒ app 被 SKIPPED）。


## 提交锚点

- `60eed41` docs: 设计稿入库（用户 2026-09-24 置于根目录，本计划以其为绑定权威）
- `9639af1` feat(core): 日制底座（1a-1 + 1a-2 + 计划/台账）


## 实施留痕

- **1a-1**：`TimeAdvance.run` 在 ④ 第 0 项补 `to == from + 1`（纯语法、不查库、在 ① 之前）；在 ① 装配状态之后、② simulate 之前补 `range.from.tick == base.meta.timestamp.tick`（只比 tick：label 是显示信息）。两条各自带"参与者/装配器自爆装置"的用例，钉住"拒绝发生在 simulate 之前"。
- **1a-2**：`Envelope.encode` 落 `"timeBase":"DAY"`；`decode` 缺字段即拒（旧档）且异值即拒。`SqliteStore.initialize` 建 `store_meta` 后跑 `ensureTimeBase`：有标签必须是 DAY；无标签且 `revisions` 空 ⇒ 就地打标（`time_base=DAY`、`format_version=1`）；无标签且有 revision ⇒ 抛（点名旧小时档与设计稿 §3）。`open` 新增 `catch (RuntimeException)` 回收连接（门禁抛的不是 SQLException，原来的 catch 不会关连接 ⇒ 泄漏）。
- **外部件**：`tools/gsimap_import.py`（`build_checkpoint` 落 timeBase；`write_db` 建 `store_meta` 并写标签）与签入资源 `simos-app/src/main/resources/worlds/v17levant.json`（补 `"timeBase":"DAY"`）——否则 `RichWorld` 的真读路径与导入器产出的库都会被自己的门禁拒掉。

## 待决/风险

- `SimosTimestamp` 的 `calendarLabel` 在推进时被 `range.to` 覆盖（客户端通常不带 label）——既有行为，1a 不动，记在此备查。
- 世界 seed 的 `PopulationSeries` 增长率在日制下被解释为"每日"：属设计稿 §3 的既定迁移语义（新世界用日参数），人口批次化（PeopleLot）在增量 2 处理。
