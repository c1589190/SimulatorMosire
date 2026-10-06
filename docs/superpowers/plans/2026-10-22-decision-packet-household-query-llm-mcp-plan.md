# 2026-10-22 开发规划：家户聚合查询 + 决策包完整版 + GM 工具 + 真 LLM/MCP 验收

> 本文件是后续批次在**上下文可能中断**时的唯一恢复入口。每批开工先读本文件，再读对应施工子文档。
> 用户 2026-10-21 最终裁定：
> 1. **中央决策人也只能看自己直辖区内的 hex 相关数据**；辖区外信息必须走**上报**，不能直接看。
> 2. GM 与决策人**共用工具**；GM 拥有**全图权限**（scope=ALL）。
> 3. 其余按 `docs/superpowers/specs/2026-10-21-decision-packet-and-household-query-usability-design.md`
>    的默认选择执行（同工具、支持多维 groupBy、rateMode=BOTH、按 tick 合批、DecisionProposable、
>    初始可 propose 工具清单）。
> 4. 实现完自己改测试；测试改完跑**真实 LLM 决策人 + 真实 MCP**端到端；没问题才算完成。
> 基线：`HEAD 4fb5b301`（权限跨命名空间 + SpawnArmy/AbsorbUnit 已推送）。

## 0. 当前事实（防止记忆漂移）

- 人口权威：Social；Unit 只持 `Unit.households` + 编制；Economy 行是投影。
- 位置：`HouseholdLocation.UNIT(unitId)` 为规范关联；`HouseholdPositionResolver` 把有效格现算；
  `SocialHouseholdMoveTool` 已禁止在编家户改 HEX（必须 detach）。
- 权限：`CommandTarget(namespace,path)` 跨命名空间；`CommandTargets.targetResources(...)`；
  家户 `HEX→social(q_r)`、`UNIT→unit(u)`；`from/to` 双边校验。
- **D0 已修正**：`GovScope` 只保留“自己 + 直辖区 hex 上的单位”（**不含后代、不含下辖 GOV**）；
  `ScopeUnitExpansion` 仅供 Army/Nation 展开各自可见单位的后代。下辖 GOV/辖区外单位不可见，
  跨区信息必须走上报（D4）。
- 决策人现状：D2 已落 `DecisionPacket/FormattedCall` 持久组件 + propose/submit/intent/my；旧 `sd.IssueDirective` + `sd.AdjudicateTick` 保留为旧口。
- 读口现状：`simos.social.population` 单 hex；`unit.get/list`；D1 已补 `simos.social.households` 聚合读口（GM/决策人共用，GM `scope=ALL`）。
- 测试现状：`test-compile` 红（历史遗留 + 本轮改动），`clean verify` 未跑。

## 1. 用户已确认的默认口径

| 项 | 决定 |
|---|---|
| 家户查询工具 | GM/决策人共用 `simos.social.households`；GM 桶可 `scope=ALL` |
| groupBy | 支持多维交叉（年龄 × 阶层等），组合键输出 |
| 出生/死亡率 | 默认 `BOTH`（配置加权 + 窗口观测） |
| 决策包执行 | 一个 tick 的所有已批准 call / merged plan 合批一条 revision |
| FormattedCall 目标 | 可 propose 工具实现 `DecisionProposable`（目标 + 预览） |
| 初始 propose 清单 | `simos.unit.raiseUnit`、`simos.gov.recruit`、`simos.gov.retireStaff`、`simos.unit.setArmyPayPolicy`、`simos.social.household.members`（受限 action）、`simos.unit.levyRegion`、`simos.gov.selectExaminees`、`simos.gov.dispatchTeam` |
| 跨区情报 | 决策人不得直接读；必须走上报（见 D4 上报工具） |
| GM | 全图；共用工具；审批/合并可改参数 |

## 2. 批次总览

| 批次 | 交付 | 子文档（施工时写/更新） | 门禁 |
|---|---|---|---|
| **D0** ✅ | 撤销 GovScope 下辖 GOV/后代自动可见；跨区必须上报的口径固化 | `docs/superpowers/plans/2026-10-22-d0-scope-correction.md` | compile + smoke：中央看不到下辖 GOV/辖区外 unit/social/map，自己的直辖区可见 |
| **D1** ✅ | `HouseholdQueryService` + `simos.social.households` 聚合读工具 | `...2026-10-22-d1-household-query.md` | compile + smoke：GM 全图按年龄/阶层/生产方式一次汇总；真实 GOV 决策人 scope 裁剪；单 hex 与 population 对账；unit 面 hex-hidden |
| **D2** ✅ | `DecisionPacket`/`FormattedCall` 持久组件 + 决策人 propose/submit/my + GM packets/packet/decide | `...2026-10-22-d2-decision-packet.md` | compile + smoke：propose→PENDING→GM 预览→true/false；旧档空表兼容；重启重载 |
| **D3** ✅ | `MergedEffectPlan` + `gm.mergedPlan.*` + `gm.packet.execute` + outcome 回写 | `...2026-10-22-d3-merged-plan.md` | compile + smoke：两包冲突→GM 合并→一条 revision；执行者 GM、proposer 留痕 |
| **D4** | GM 工具：periodicAdjustment / armyPayPolicy / vitalRates / adjustPopulation + 上报工具（send/reports） | `...2026-10-22-d4-gm-tools-reports.md` | compile + smoke：每个工具有 preview/apply/拒绝；上报跨区可见性符合口径 |
| **D5** | 测试迁移 + `clean verify` + 真实 LLM 决策人 + 真实 MCP E2E | `...2026-10-22-d5-tests-llm-mcp.md` | `test-compile` 绿、`verify` 通过、E2E 报告落盘 |

每批固定动作：写施工子文档 → subagent 实现 → 控制方 compile/smoke 复核 → 更新本文件进度 →
commit + push。**不在批次里顺手扩无关功能。**

## 3. D0：口径修正（最先做）

- 改 `GovScope`：
  - 保留：自己 unit、自己直辖区 Region 的逐格 hex、位置落在直辖区 hex 内的单位；
  - **删除**：`superiorGov` 链上的下辖 GOV 单位、以及所有后代单位的自动加入
    （辖区外单位一律不可见，必须由上报工具送情报）；
  - actor：仍只自己 + `superiorGov` 的国库路径（显式上缴，不代表读权）；
  - javadoc 明确“中央看下辖政府编制 = 旧口径，已作废；跨区必须上报”。
- 加 smoke：建中央/下辖 GOV；断言下辖 GOV 的 unit/social/map 路径都不在中央 scope；
  中央直辖区 hex 可见；上报工具留 D4。
- 更新 `2026-10-20-cross-scope-command-targets.md` 中与此冲突的段落。

## 4. D1：家户聚合查询 ✅（已实现；施工记录见 `2026-10-22-d1-household-query.md`）
- `HouseholdQueryService`：scope/filters/groupBy/metrics/window/rateMode；`Visibility` 逐户/逐格判定；
  成员级 filters 部分命中时家户级指标记 `member-filter-partial`，不拿全家户账冒充命中成员。
- `SocialHouseholdsTool`：GM 与决策人共用；`scope=ALL` 仅当调用者 `social` 命名空间 unrestricted（GM）才允许；
  工具声明 map/social/unit/economy/actor 五读面。

- 新增 `simos-app/.../query/HouseholdQueryService.java`（纯只读，输入 `SimulationState` + 查询 spec）。
- 新增 `simos-app/.../tools/read/SocialHouseholdsTool.java`（`simos.social.households`），
  GM 桶与决策人桶都注册；`scope=ALL` 仅 GM 桶可用（用现有 autorizer/scope 判定，不靠工具名）。
- 维度：HEX/HOUSEHOLD/UNIT/AGE_BRACKET/SEX/STRATUM/PRODUCTION_MODE/RESIDENCE；
  指标按易用性设计 §2.4；`rateMode=BOTH` 默认；输出 `rows/totals/unavailable`。
- 可见性：
  - 决策人 hex 面 = `GovScope`/`ArmyScope`/`NationScope` 的 social 前缀；
  - 决策人 unit 面 = scope 函数给出的 unit 前缀（Gov = 直辖区内单位；Army/Nation 按其已定口径含各自可见单位的后代）；
    unit 面命中的家户可聚合，
    但其 HEX 维度输出 `null + hex-hidden-by-scope`；
  - 不得因为 unit 面可见就放出该 hex 的 social/map 数据。
- 复用 `Population` 的年龄/城乡派生（`SocialData.ageStructureAt` 等），但只读、不改那些方法。
- smoke：
  - GM `scope=ALL` 一次按年龄、阶层、生产方式聚合；结果与逐户手算一致；
  - 决策人 `scope=VISIBLE` 只含 scope 内家户；越界 hex 不出现；
  - 单 hex 查询的总人口 == `simos.social.population` 的 total；
  - rates：配置率加权 vs 窗口观测事件数对账。

## 5. D2：决策包完整版

- SdData 新组件：`decisionPackets`、`mergedEffectPlans` + Codec/ChangeSet；旧档缺键 ⇒ 空表。
- 实体/形状按易用性设计 §3.2；`FormattedCall.status` 逐 call。
- 工具：
  - 决策人桶：`simos.sd.propose`、`simos.sd.packet.intent`、`simos.sd.packet.submit`、`simos.sd.packet.my`；
  - GM 桶：`simos.gm.packets`、`simos.gm.packet`、`simos.gm.packet.decide`；
  - `DecisionProposable`：初始清单工具实现 `targets(dm,state)+preview(state,args)`；
    未实现该接口的工具不允许 propose。
  - `allowedTools` 强制为拟稿白名单。
- 拟稿校验：工具在 charter/注册表、args schema、目标 scope、preview 成功；坏 ⇒ 具名拒、不落 packet。
- 审批：`simos.gm.packet.decide` 支持 `APPROVE/DENY/MERGE`；DENY 写 INFO 理由；
  packet 状态与 call 状态原子落一条 revision。
- smoke：真实命令/工具提议 → GM 看到预览 → true/false；越 scope/坏参数拒；重启重载。

## 6. D3：合并效果集 + 执行

- `MergedEffectPlan` 持久 + GM 工具 `simos.gm.mergedPlan.upsert` / `.apply`；
- `simos.gm.packet.execute`：把某 tick APPROVED calls 按 packet/call 稳定序批执行；
- 执行者身份 GM；outcome（逐 call applied/rejected + 前后读数）写 INFO / packet；
- 一个 tick 一条 revision；冲突/执行失败退回 GM 重裁，不静默剔。
- smoke：两个决策人提议冲突 → GM MERGE → 排序 → apply；校验 revision 数、账户/人口最终值、proposer 留痕。

## 7. D4：GM 工具 + 上报

- `simos.gm.periodicAdjustment`：list/upsert/remove/preview/due 读口（复用 P4a 命令）。
- `simos.gm.armyPayPolicy`：preview/apply/read（复用 `unit.SetArmyPayPolicy`）。
- `simos.gm.vitalRates`：全局/家户率查看与调整（Social 接口）。
- `simos.gm.adjustPopulation`：指定家户加减人口（Social 工单 `ADJUST_POPULATION`/`ADD_MEMBERS`）。
- **上报机制**（跨区情报）：
  - 决策人工具 `simos.sd.report`：`{to: {kind: GOV|NATION|GM, id?}, subject, body, evidence?}`；
    写现有 `SdInfoEntry`/INFO 覆盖层（或新增 Report 记录），recipient 打 tags；
  - 读工具 `simos.sd.reports`：决策人只读收件人=自己的报告；GM 读全部；
  - 报表内容由发送方负责（可附数字摘要），不因上报而获得对方 hex/家户原始读权；
  - 上级若要原始数据，必须 GM 授权/合并审批。
- Catalog/PAYLOAD_HINTS/工具桶/GUI 同步。

## 8. D5：测试迁移 + 真 LLM/MCP 验收

- 先 `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests test-compile` 清红：
  - 已列出的旧断言：`EconomyRoundTripTest`（组件 28→29+）、`ResolveCombatToolTest`、`RaiseUnitPlanTest`、
    `LevyRegionPlanTest`、`GovScopeTest/ArmyScopeTest/NationScopeTest`（范围扩展新增）、
    `PopulationDynamicsTest` 等；逐个按新语义改，不删有效断言。
- `tools/mvn-lock.sh -q -pl simos-app -am clean verify` 全绿。
- 真实 LLM 决策人 + 真实 MCP：
  - 读现有真实 LLM 测试（`RealLlmGovScenarioTest` 等）与 MCP 启动方式；
  - 用真 provider（环境变量/现有配置）跑一轮：决策人 propose → packet → GM approve/execute → 真 MCP 读 packet/outcome；
  - 失败必须诊断到根因；无可用密钥/网络时如实记录阻塞，不伪造通过。
- 收口：更新主计划、本文件、handoff、AGENTS；全部 commit/push。

## 9. 恢复命令

```bash
cd /home/cna/SimulatorMosire
git status --short --branch
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile
# 本文件 + 对应批次的施工子文档
```

## 10. 进度

- [ ] D0 口径修正
- [ ] D1 家户聚合查询
- [ ] D2 决策包
- [ ] D3 MERGED/执行
- [ ] D4 GM 工具/上报
- [ ] D5 测试/真 LLM+MCP
