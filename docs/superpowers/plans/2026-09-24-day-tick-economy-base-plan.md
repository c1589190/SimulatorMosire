# 计划：日制底座（增量 1a/1b）——POLITICAL_ECONOMY_DESIGN.md 的第一段路

**日期**：2026-09-24
**依据**：[POLITICAL_ECONOMY_DESIGN.md](../../../POLITICAL_ECONOMY_DESIGN.md)（设计稿）§3 / §9 / §10 / §12
**范围**：本计划只覆盖设计稿增量 1 拆出的 **1a（全局日制底座）** 与 **1b（多模块提案 + 六切片创世）**。
增量 2~4（人口/产权/账本、生产与税权、市场与政府扩展）不在本计划内。

## 〇、用户裁定（2026-09-24）

1. **没有要保留的旧 store** ⇒ 只落 fail-closed 门禁，离线迁移工具延后到确有档要导入时再做。
2. **范围 = 计划 + 1a + 1b**，1a 先关账、1b 随后；两段各自独立验收（不一口气铺开）。
3. **速度量纲**：`speed` 以**小时**为基准（MP/小时，数值与语义都不动）；
   每天按 `24 × speed` 的小时流预算结算，**逐格按地形成本付费**；付不起下一格时**允许停在两格之间**
   （位置向"进入下一格之前"取整、差多少记在 `IN_TRANSIT` 的差量里），**余量跨日保留**。
   ⇒ 与现有 `UnitMoves.evaluate` 的 `IN_TRANSIT` 语义同构，只在预算里补 `× 24` 常数；导入小时档的 ×24 换算也就不需要了。

## 一、1a：全局日制底座

**靶心**：`1 tick = 1 天` 成为**唯一**时间语义；一次 `AdvanceTime` 恰好前进一天；无日制标签的旧档 fail-closed。

| # | 步骤 | 落点 | 验收判据 |
|---|---|---|---|
| 1a-1 | 单日步长 + 连续性校验 | `simos-core`：`TimeAdvance.run` 在 ④ 之前补 `from == base.meta.timestamp.tick` 且 `to == from + 1` | 多日、倒退、from 与 base 不符、开区间 ⇒ 全部 `Rejected` 且**不留 revision**；一次合法推进仍提交；测试含"被拒后 head 与六域状态不变" |
| 1a-2 | 存档 `time_base=DAY` 标签 + 旧档门禁 | `simos-core`：`SqliteStore` 增 `store_meta(key,value)` 表；`Envelope` 信封增 `timeBase` 字段 | 空库首启写入标签；有 revision 且无标签的库 `open` 即抛（报错点名旧小时档与设计稿 §3）；信封缺/异标签 decode 拒绝；checkpoint 读侧按 C18 回退不失败 |
| 1a-3 | unit：小时基准 + 日预算 ×24 | `simos-unit`：`UnitMoves` 预算补 `HOURS_PER_DAY=24`；`Unit`/`Movement`/`MoveFixture`/`panels.js` 量纲文案统一"MP/小时" | 同一起点、同一路线：日预算 = `speed × 1000 × 24`；第 1 天卡在段中（`IN_TRANSIT` 给差量）、第 2 天走完；地形成本不同 ⇒ 走的格数不同 |
| 1a-4 | sd/social 日语义与文案 | `TriggerEvaluator`/`Trigger`/`DecisionMaker`/`Effect`/`AdjudicateTickTool` 文案改日；`Nation.adminBudgetPerTick` 注释改"每日行政预算（尚未被消费）"；`PopulationSeries` 增长率注释改"每 tick（日制=每日）" | 纯文案 + 注释；无行为变更；`git grep` 全仓无"每小时/小时"残留（除历史文档与迁移说明） |
| 1a-5 | app/前端/MCP 快进改逐日循环 | `AdvanceTool`、`GuiServer#advanceReply`：`to` 缺省 = `from+1`、描述改"推进一天"；`timeline.js`：N 天 = N 条命令，逐条用上一条返回的新 `expectedRevision`，中途失败停住并报在第几天 | 前端"推进 2 天"产生 2 条 revision；第 2 条用第 1 条的新 revision；失败停在第 1 天；前端门禁（MIN_TESTS/REQUIRED_FILES）同步 |
| 1a-6 | 全量测试重标定与关账 | 16 个用 `TimeRange` 的测试文件：多 tick 推进改逐日循环；移动/战斗/SD 期望值按 ×24 重算 | `./mvnw clean verify` 前台跑、SpotBugs/Checkstyle/前端门禁全绿；surefire 报告 mtime 落在本轮 |

**1a 不做什么**：不动 `TimeRange` 构造器（宽区间仍是合法只读查询，见设计稿 §3）；不建迁移工具；
不新增领域模块；不改 `SimulationState` 形状。

## 二、1b：多模块提案 + 六切片创世

| # | 步骤 | 落点 | 验收判据 |
|---|---|---|---|
| 1b-1 | 通用多模块提案 | `simos-util`：新增 `WorldTimeProposal(participantId, moduleChanges, reads, writes)`；`TimeAdvance` 对每个 moduleChange 逐项做现有四项校验；`TimeProposalResolver` 按外层参与者做写写/读写检查 | 旧单模块 `TimeProposal` 仍可用（兼容）；两外层提案改同一模块/地址 ⇒ 拒整次；单模块多阶段只以最终 `ChangeSet` 提交；原子性测试：让其中一个模块 `apply` 抛 ⇒ 六切片与 revision 全保持基态 |
| 1b-2 | `simos-economy-api` 模块 | 新模块：稳定 ID、`ActorRef`、`CommodityId`、`EconomicEvent`、`TransferIntent`、只读契约；pom + enforcer 禁反向依赖 | 不含 Snapshot/公式/存储；`mvn -pl simos-economy-api test` 绿；enforcer 越界即构建失败 |
| 1b-3 | 五个有状态模块骨架 | `simos-property`/`simos-production`/`simos-ledger`/`simos-market`/`simos-government`：各 `*Data`/`*Snapshot`/`*ChangeSet`/`*Codec`/Resolver + 往返测试 + codec 往返测试 | 逐组件往返不变式（铁律 5）与 codec 往返各模块自证；空表起步 |
| 1b-4 | 六切片创世与未激活语义 | `WorldgenInitializeTool`/`RichWorld` 写六切片空快照；`ledger.economyMeta` 记录地图 ID/激活日/规则版本；缺切片拒绝日推进 | 新库创世六切片齐；旧日制档（缺切片）走显式 schema 导入路径；`economyMeta` 缺失 = 未激活，只走简化人口查询 |
| 1b-5 | `EconomyDayCoordinator` 骨架 + 装配 | `simos-app`：日协调器按设计稿 §9 步骤序调用各模块纯服务（首版各步骤可为空实现）；`Shell` 注册六个 codec/handler/resolver | 一次推进恰一天、一条 revision、六切片原子提交；`Shell` 只从注册清单取个数、不写死 |

**1b 不做什么**：不实现生产/市场/税收公式（增量 2~4）；不接 GUI 经济视图；不接征募/战损去重（设计稿 §10.1，增量 4）。

## 三、延后项（记录在案，不在本计划）

- 小时档 → 日制创世的离线迁移工具（用户：无档要保）。
- 索取顺序中"债权/地租"的先后（设计稿自认的内部不一致）：增量 3 定在**具体合同模板**里。
- `Shell.advanceAndDrain` 的统一推进入口（GUI/MCP/SD 效果排泄合一）：设计稿 §10.1，增量 4。
- 军速等场景参数标定：`ARMY_SPEED=3`（MP/小时）保持占位，待移动/经济模型落地。
