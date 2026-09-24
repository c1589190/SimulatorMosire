# SDD 台账 — 计划：docs/superpowers/plans/2026-09-24-day-tick-economy-base-plan.md

> 依据（设计稿，绑定权威）：`POLITICAL_ECONOMY_DESIGN.md` §3（日 tick）/ §9（协调器）/ §10（落点）/ §12（验收）
> 工作区：主检出 `/home/cna/SimulatorMosire`，分支 `ts/m1`，基线 `ff93954`
> 用户裁定（2026-09-24）：① 无 store 要保（迁移工具延后）② 范围 = 计划 + 1a + 1b ③ `speed` 以小时为基准（MP/小时）+ 日预算 ×24 + 允许卡在两格之间（余量跨日，位置向"进入下一格之前"取整）
> 纪律：Maven 一次一个（`flock /tmp/simos-maven.lock`）；一个文件一个 owner；子 Agent 只报结论，控制器负责 `clean verify` 关账

## 步骤状态

| # | 步骤 | 状态 | 证据 |
|---|---|---|---|
| 1a-1 | Core 单日步长 + 连续性校验 | **完成**（2026-09-24） | `TimeAdvanceTest` 18/18 绿（含新增 `multiDayAdvanceIsRejectedBeforeAnythingElseRuns`、`advanceThatDoesNotStartFromTheCurrentWorldDayIsRejected`） |
| 1a-2 | 存档 `time_base=DAY` 标签 + 旧档门禁 | 进行中 | `Envelope` 信封增 `timeBase` + decode fail-closed；`SqliteStore` 增 `store_meta` 表与 `ensureTimeBase` 门禁；`gsimap_import.py` 与 `worlds/v17levant.json` 已补标签；新增 `EnvelopeTest`/`SqliteStoreTest` 用例待跑 |
| 1a-3 | unit：MP/小时 + 日预算 ×24 | 未开始 | 待派 |
| 1a-4 | sd/social 日语义文案 | 已派子 Agent（纯文案） | 待回报 |
| 1a-5 | app/前端/MCP 快进改逐日 | 未开始 | 待派（依赖 1a-1/1a-2 稳定） |
| 1a-6 | 全量重标定 + `clean verify` 关账 | 未开始 | — |
| 1b-1..5 | 多模块提案 + 六切片创世 | 未开始 | — |

## 实施留痕

- **1a-1**：`TimeAdvance.run` 在 ④ 第 0 项补 `to == from + 1`（纯语法、不查库、在 ① 之前）；在 ① 装配状态之后、② simulate 之前补 `range.from.tick == base.meta.timestamp.tick`（只比 tick：label 是显示信息）。两条各自带"参与者/装配器自爆装置"的用例，钉住"拒绝发生在 simulate 之前"。
- **1a-2**：`Envelope.encode` 落 `"timeBase":"DAY"`；`decode` 缺字段即拒（旧档）且异值即拒。`SqliteStore.initialize` 建 `store_meta` 后跑 `ensureTimeBase`：有标签必须是 DAY；无标签且 `revisions` 空 ⇒ 就地打标（`time_base=DAY`、`format_version=1`）；无标签且有 revision ⇒ 抛（点名旧小时档与设计稿 §3）。`open` 新增 `catch (RuntimeException)` 回收连接（门禁抛的不是 SQLException，原来的 catch 不会关连接 ⇒ 泄漏）。
- **外部件**：`tools/gsimap_import.py`（`build_checkpoint` 落 timeBase；`write_db` 建 `store_meta` 并写标签）与签入资源 `simos-app/src/main/resources/worlds/v17levant.json`（补 `"timeBase":"DAY"`）——否则 `RichWorld` 的真读路径与导入器产出的库都会被自己的门禁拒掉。

## 待决/风险

- `SimosTimestamp` 的 `calendarLabel` 在推进时被 `range.to` 覆盖（客户端通常不带 label）——既有行为，1a 不动，记在此备查。
- 世界 seed 的 `PopulationSeries` 增长率在日制下被解释为"每日"：属设计稿 §3 的既定迁移语义（新世界用日参数），人口批次化（PeopleLot）在增量 2 处理。
