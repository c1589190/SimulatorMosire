# GOV / ARMY 与单个决策人操作能力调查（2026-10-14）

> 调查快照：`HEAD=b82535e3`，同时存在 AgentLib Docs/Workspace 的未提交改动；用户告知 DSH 正在修改 GA。
> 本文是调查结果，不是修复设计书。没有修改生产代码、测试、既有文档或现有世界，没有运行 Maven。
> 证据分为：当前源码回核、独立端口探针、尚未实测的流程推断。GA 接口结论需在 DSH 改动落定后复核。

## 1. 用户原话附录

2026-10-14，本次调查指令，逐字照录：

> 是的，隔壁DSH正在对ga模块进行修改，而你需要同步调查一下当前项目的GOV模块、ARMY模块，这两个模块的设计目的是和GA模块配合，为单个决策人提供完善的政府/军队操作，你需要调查其中的功能性/代码缺陷，先告诉我缺陷是什么、哪些和设计目的相悖

## 2. 判据来源与调查边界

| 判据 | 已有出处 | 本次如何使用 |
|---|---|---|
| 单个决策人应能操作自己的政府/军队 | 本次用户原话 | 检查读口、操作工具、提议入口及最终执行是否闭环 |
| 中央不设全视野；政府默认只读直辖 | `specs/2026-10-02-architecture-design-source.md` D-002；`plans/2026-09-30-gov-army-modules-plan.md` 用户原话与裁定 4 | 检查自己的政府身份是否被错误地当成读取/修改任意家户的许可 |
| 编制、预算、岗位和两路招募由政府决策人调整 | `specs/2026-10-23-gov-service-mode-design.md:27-31`、`:133-148` | 这些是已有设计能力，不能把“工具存在”当成决策流程已经可用 |
| 退休/解职应释放承诺 | 同设计书 `:143` | 区别显式结束岗位与自然逃亡导致户空；后者另有新裁定要求保留职位与发薪依据 |
| 户空保留职位/承诺 | `plans/2026-10-13-emergency-stop-protocol.md:508-509` | 本文不把“逃亡后空户仍发薪”记成缺陷 |
| Army 处理交战记录、概率与随机化，Unit 承载状态链接 | 用户设计原稿 D-009 / D-010 / D-012 | 检查开战、追加阶段、判定、结束之间是否能走通 |
| 合并决策先规划全部操作，再原子提交 | `plans/2026-10-22-d3-merged-plan.md:47-62` | `planOnly` 必须返回命令批且零提交 |

日期说明：带 `2026-10-22/23` 的文件名按仓内日期纪律只作为文件标签；本文通过正文与取代关系判定口径，不以文件名推断实际先后。

## 3. GOV 已确认的代码缺陷

### G1：四个可提议工具在规划阶段提交世界修改（最高优先级）

现场：

- `simos-gov/src/main/java/io/mosire/simos/gov/tools/write/GovRecruitTool.java:236-248`
- 同目录 `GovRetireStaffTool.java:227-239`、`GovSelectExamineesTool.java:232-244`、`GovDispatchTeamTool.java:234-246`

四条 `apply(..., boolean planOnly)` 接收参数，却没有任何 `if (planOnly)` 提前返回，直接调用 `submitBatchWithReceipt`。

实际消费者：`simos-app/src/main/java/io/mosire/simos/app/decision/DecisionEffectExecutor.java:347-377` 强制 `preview=false, planOnly=true`，随后要求结果包含 `plannedCommands`；`:208-235` 把规划失败作为 `PLANNING_FAILED` 返回。

独立探针四条实际输出均为：`success=true submitCalls=1 returnedPlannedCommands=false`。

因此在实际 Core 写口能成功提交的条件下，第一条 GOV 操作已落地，执行器随后因没有 `plannedCommands` 报规划失败；后续操作不会按整批执行。**决策包失败却已有世界修改**，与合并决策原子性直接冲突。

归属：缺陷在 GOV 工具；GA/app 是契约消费者。探针使用记录写口，未向真实 Core 提交 revision；上述执行器后果由当前调用链回核推导。

### G2：外部岗位绕过家户可见范围，预览也能泄露数据（最高优先级）

`GovOpenPostsToMarketTool.java:72` 只声明 `unit` 资源；`:181` 只断言本 GOV 单位；随后 `:183-225` 按输入家户 ID 读取全状态里的 Social 家户、经济劳动和实际劳动。没有对家户所在 hex/unit 的可见性判定，也没有对应 Social/economy 资源断言。

独立探针构造：自己的 `unit:gov-central` 可达，Social/map 明确 `none()`，身份端口报告调用者确为本政府决策人。

```text
SOCIAL_READ=DENIED
OUT_OF_SCOPE_POST_PREVIEW success=true code=null
```

预览返回辖区访问未获准家户 `hh--2_0-rural-poor_peasant` 的 `socialLaborMilli=608000`、`economyLaborBefore=640000`，并生成更改其劳动预算及 GOV_SERVICE 承诺的命令载荷。

**“只能操作自己的政府”没有约束政府操作涉及的外部人群。** 这允许中央/地方通过写工具的预览越过读围栏；apply 的同一代码路径会将这些命令交给注入写口。GM 审批仍存在，但审批不能替代工具内部的资源范围检查。

边界：探针直接调用工具，身份事实由端口替身提供；没有走真实 GM 审批，也没有向真实库写入外部家户修改。

### G3：招募会覆盖整个既有岗位配置，并清掉档位

`GovRecruitPlan.java:125` 固定选择 `hh-unit:<govId>`；`:409-419` 生成的 `unit.AssignGovPost` 载荷只有 unitId、household、role。

`simos-unit/.../spi/AssignGovPostHandler.java:73-79` 对缺省 tierId 用空串、head 用 false、level 用本 GOV 层级；`.../ops/UnitOperations.java:1182-1187` 用一条新配置替换该家户原配置。

探针对小世界当前岗位执行招募生成的真实岗位命令：

```text
RECRUIT_POST beforeRole=POST beforeTier=tier-3 beforeHead=false afterRole=SCRIBE afterTier= afterHead=false
```

这不是“增加一个文员”的局部效果，而是把**整户既有人员**一起改成 SCRIBE，并清掉原档位。若原配置 head=true，当前缺省解析也会将它清为 false（后者只回核，未独立实测）。

`app/household/GovernmentServiceLaborBridge.java:410-426` 在 tierId 为空时按 role 固有权重回退，原先混合治安/公文/口岸权重会改变。**招人引发已有班子的行政结构与效率变化**，与“政府自主设置岗位、档位”的目的相悖。

### G4：显式退休没有结束岗位/劳动承诺；外部岗位缺少完整退出工具

`GovRetireStaffTool.java:260-280` 的新岗位户模式只组 Social 人口转移、可选退休待遇、GA Info；不会发岗位撤销或 `economy.SetGovServiceCommitment(laborMilli=0)`，也不修改原 GOV_SERVICE 承诺。

与此同时 `GovAssignPostsTool.java:212-225`、`GovOpenPostsToMarketTool.java:225-243` 要求人口劳动与承诺为正，不能用 `laborMilli=0` 结束岗位；`GovDismissPlan.java:96-101` 对岗位投影模式直接拒绝并建议退休/岗位工具。

后果：目前决策人工具无法完成“结束一份内部/外部岗位，并停止它对应承诺”的完整操作。显式退休把人移走后，原岗位承诺仍在，原俸禄遍历依据也仍在。

判定依据：政府服务设计 `:143` 明确解职/退休释放承诺。**自然逃亡导致户空仍保留职位**是另一个已裁定行为，本文不要求改它。

这是源码确认的流程缺口；未对退休后的下一日薪资金额做运行验证。如果用户后续对“显式退休仍保留职位”另有裁定，本条须按该裁定再核。

补充：`GovRecruitTool.java:269-288` 的岗位户模式也只加人口、改岗位，不同步经济劳动行或扩大 GOV_SERVICE 承诺。其探针批为 `[social.SubmitHouseholdWorkOrder, unit.AssignGovPost, ga.PutInfo]`。当旧承诺已达到有效供给上限时，新增人口不会自动提高行政供给；要另调承诺。本文将其记为操作流程缺口，不推定所有招募都必须自动扩大职位承诺。

## 4. ARMY 已确认的闭环缺陷

### A1：标准开战生成不可判定的初始阶段，正常结算无法结束交战

`army/tools/write/StartCombatPlan.java:37-40`、`:171-180`：`simos.army.startCombat` 不传 initialStage；`army/spi/RecordCombatHandler.java:167-178` 合成 `start`，其 outcomes 为空。

`CombatResolution.java:103-104` 对空概率表拒绝投骰；显式 outcome 同样要求属于该表。`AppendCombatStageHandler.java:89-96` 只追加新阶段，旧阶段 ID 重复则拒绝，没有给旧 start 补概率表的命令。

`ResolveCombatPlan.java:214-224` 要求其它所有阶段都已判定，才生成清交战链接命令。

独立探针：真实 RecordCombatHandler 产出缺省记录，追加一个已判定的后续阶段，得到：

```text
COMBAT_AFTER_RESOLVED_APPEND stages=2 initialOutcomes=0 allResolved=false
INITIAL_STAGE_RESOLVE=阶段没有结局概率表（outcomes 为空），无法投骰: start
```

**标准“开战→追加概率阶段→判定→结束”走不闭合**，Unit 的 combat 描述链接不会由结算工具清掉。GM 可手动清 Unit 链接，或绕开 startCombat 在创建记录时提供有表的 initialStage；这些不使标准工具流程成为完整能力。

### A2：政府创建武装调查组没有登记 Army 身份，不能直接接军队决策人

`GovDispatchTeamTool.java:268-294` 在 armed=true 时创建 Unit、人口/经济/账户并写 ArmyFormation，但整个批没有 `army.CreateArmy`。

`army/spi/AssignDecisionMakerArmyHandler.java:123` 绑定的目标必须在 ArmyData.armies 中；`app/access/ArmyScope.java:75-93` 也通过 Army 身份解根单位、计算视野；`army/spi/ArmyTimeParticipant.java:160` 的相遇监控遍历 Army 表。

所以“武装调查组”在 Unit/军俸侧已是军队编制，在 Army/GA 侧却还不是可绑定、可监控的 Army。需要 GM 额外登记。**GOV 创建军事单位到 ARMY/GA 接管之间缺一段编排**。

这是源码回核的能力缺口，未运行创建后绑定的端到端测试；不能将它扩大为“武装调查组没有人口/账户/军俸”，这些腿已有实现。

## 5. 与 GA 同时决策流程的接口缺口（当前快照，待 DSH 落定后复核）

### C1：GOV 配置写工具在同时决策中消失，提议目录没有等价入口

`app/tools/SimosToolSource.java:1527-1550` 装配了政府支付、币种/发行/汇率、编制/预算、岗位/招募两路的 DM 直调工具。

`ga/decision/DecisionAgentRunner.java:517-542` 按 schema 分类：有 expectedRevision 且不是 packet 工具的写工具，在 frozen 回合被移除。

`app/decision/ProposalCatalog.java:128-158` 的目录只包含 raiseUnit、recruit、retireStaff、household.members、levyRegion、selectExaminees、dispatchTeam。**没有政府预算/编制/岗位/外部岗位/货币操作的提议入口**。

因此旧单人直调有这些政府能力，同时决策回合只有自然语言表达而没有对应结构执行路径。冻结世界本身符合同时决策设计；缺陷是被移除的领域能力没有转接到决策包中。

### C2：ARMY 行军在旧出令路径存在，在决策包路径不可达

`unit/spi/PlanRouteHandler.java:34` 打有 DmUsableCommand；DM 面仍有 `ga.IssueDirective`（`app/tools/SimosToolSource.java:1504`）。所以不能说军队决策人完全不能行军：旧单人出令可以嵌 unit.PlanRoute。

但 frozen 回合移除了旧令路径；上述七项 ProposalCatalog 没有行军、取消路线、休整/状态调整，也没有军队专用提议工具。实际 ARMY DM 读口为 combat/combats；GM 战果/概率工具不是军队指挥入口。

**单个军队决策人在同时决策中不能把自己的行军意图转为可执行结构命令。** 与用户本次强调的“完善的军队操作”直接不符。

## 6. 另外两处结构/功能债（不混成已实测缺陷）

### D1：军队主子存在两个可独立改写的事实来源

`Army.masterGovUnitId` 与 `Unit.ArmyFormation.masterGov` 同时存在。

`army/spi/SetArmyMasterGovHandler.java:41-43` 只改前者；`army/tools/write/AssignArmyGovTool.java:201` 有显式 syncUnitSide 开关，false 时不改 Unit；`app/time/MilitaryPayRuleBridge.java:97-113` 从后者决定付款政府。

因此合法现有入口能得到“Army 登记归 B，军俸仍由 A 支付”的分歧。**当前工具有意允许不同步**，这是结构债/设计目的冲突风险，不能伪称为同步工具默认分支漏了赋值（默认 true 的同步分支已有）。未做数值探针。

### D2：同 tick 同参数的第二次真实扩编被幂等键当成重试

`GovExpandHouseholdTool.java:209-210` 的 orderId 只有 govId、tick、role、count；`GovRecruitPlan.java:372-376` 同形；退休键再加目标家户。没有独立操作 ID，也不含 revision。

`social/spi/SubmitHouseholdWorkOrderHandler.java:45-46` 约定同 orderId 重复提交具名拒。

两次独立决策都“本日招 1 人”，第二次即使 expectedRevision 更新，也会撞同一工单身份。当前实现有意提供重试保护，但同时限制同一天重复真实操作。属功能限制；未独立跑重复提交。

## 7. 本次实测命令与原始输出

本机证据（不进 git）：`.superpowers/sdd/2026-10-14-gov-army-capability-audit/evidence/`，包含 AuditProbe.java、run-probe.sh、probe.log、reproduced.log、source-sha256.txt。

可复制命令：

```bash
bash .superpowers/sdd/2026-10-14-gov-army-capability-audit/evidence/run-probe.sh
```

实际 rc=0，输出：

```text
PLAN_ONLY success=true submitCalls=1 returnedPlannedCommands=false
RECRUIT_BATCH=[social.SubmitHouseholdWorkOrder, unit.AssignGovPost, ga.PutInfo]
RECRUIT_POST beforeRole=POST beforeTier=tier-3 beforeHead=false afterRole=SCRIBE afterTier= afterHead=false
PLAN_ONLY_TOOL name=simos.gov.dispatchTeam success=true submitCalls=1 returnedPlannedCommands=false
PLAN_ONLY_TOOL name=simos.gov.selectExaminees success=true submitCalls=1 returnedPlannedCommands=false
PLAN_ONLY_TOOL name=simos.gov.retireStaff success=true submitCalls=1 returnedPlannedCommands=false
SOCIAL_READ=DENIED
OUT_OF_SCOPE_POST_PREVIEW success=true code=null
COMBAT_AFTER_RESOLVED_APPEND stages=2 initialOutcomes=0 allResolved=false
INITIAL_STAGE_RESOLVE=阶段没有结局概率表（outcomes 为空），无法投骰: start
```

探针将涉及的当前工具、Plan、Army 类型/handler 和 Unit 岗位 handler 编译到独立 `/tmp` 目录；其余依赖与 SmallWorld 夹具使用现有 shaded jar。写口是记录调用的替身，身份查找使用限定本 GOV 的替身；其它 GOV 经济/选人等适配器使用既有实现。没有改 shared target，没有打开监听端口，没有写真实世界。

## 8. 未做 / 未验证

- 未跑 Maven、门禁、全套测试或变异验证；不能据本文宣称全仓回归通过。
- 未在生产审批链上批准操作；未验证真实 Core 中 G1 的 revision 增量与整个执行器的最终返回。探针直接证明工具调用写口，执行器后果由源码调用链确认。
- G4、A2、C1/C2、D1/D2 是源码与契约回核，未独立跑完整世界生命周期。
- 未把所有传递依赖重新编译；shaded jar 只用作探针依赖/夹具，不能当成正在修改的 GA 最新运行时。
- 未将“城防、士气、补给/训练、自动战力”一律列为缺陷：已有文档明确部分后置，Army 一期不算战力。GM 专用战果判定也不是权限缺陷。
- 未把“旧档不可读”列为缺陷，未把自然逃亡后户空继续发薪列为缺陷。
- 未调查概率分布统计正确性、大图相遇检测性能、全部 GUI 行为及所有财政结算公式。
- DSH 完成 GA 后，C1/C2 与 G1 的消费者调用链需要按新接口再次核实。

处置建议仅定优先次序：先止住 G1 的规划阶段提交、G2 的越界取数；再闭合 A1 交战生命周期与 C1/C2 决策能力入口；G3/G4 与 A2 一并按岗位/军事单位完整操作验收。本文未代替用户裁定新的数据形状或修复架构。
