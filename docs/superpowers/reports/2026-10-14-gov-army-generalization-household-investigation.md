# GOV / ARMY 通用隶属关系与家户管理：进一步调查

> 2026-10-14；只读调查与设计建议，未修改生产代码。补充 `2026-10-14-gov-army-capability-audit.md`，不取代已有用户裁定。以下“建议”均未实施。
> 工作区基线 HEAD `b82535e3`；DSH 同时修改 GA / app 决策装配文件，未接手其修改。本文新增探针所直接编译的源码与依赖 SHA256 留在证据目录。

## 1. 用户原话附录

本轮用户原话，2026-10-14：

> 这些设计是否可以更加通用化？例如GOV模块和Army模块可以有一个通用的隶属关系，方便GOV决策人自由创建任意形式的武装部队；GOV的家户管理一直是一坨屎，你可以进一步深入调查，然后告诉我你的进一步调查结果

上一轮任务原话，本会话 2026-10-14：

> 是的，隔壁DSH正在对ga模块进行修改，而你需要同步调查一下当前项目的GOV模块、ARMY模块，这两个模块的设计目的是和GA模块配合，为单个决策人提供完善的政府/军队操作，你需要调查其中的功能性/代码缺陷，先告诉我缺陷是什么、哪些和设计目的相悖

## 2. 结论与既有裁定溯源

可以通用化，而且已有 Unit 通用身份、编制树与命令链可用。问题主要在于：同一类归属存在多处权威；创建流程未把组织、真实家户与 Army 身份闭合；岗位与家户绑定过紧；承诺劳动与实际供给未按家户统一分配；可见范围又被当作写权限。

不建议先增加一个“大一统 GOV/ARMY 基类”，或让 GA 维护 GOV/ARMY 归属枚举。需要先明确每一种关系的含义、唯一权威和变更协议。

| 已有要求 | 来源与回核 | 本轮设计含义 |
|---|---|---|
| Unit 可挂多个家户，经济按家户分别结算；劳动从 Social 成员逐人求和 | `docs/superpowers/plans/2026-10-09-household-structure-repair-plan.md:19-24` | 不能把所有官吏、军人压进一个标准命名的大户，或按 Unit 平均分配劳动 |
| 政府可从自己的家户获得劳动，也可让外来家户承担行政任务 | `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md:25-31` 用户原话 | 外部任职不能强迫搬户；内部任职也不能限制为唯一固定 ID |
| `hh-gov` 为零人口财政锚点 | 同文 `:38`；`simos-app/src/main/java/io/mosire/simos/app/time/GovSalaryRuleBridge.java:115-170` 回代码核过 | 人员、岗位、国库应分别表达，保留当前 HOUSEHOLD 账户口径 |
| DM 不能凭空造钱造人建军；Army DM 提交自然文本战术和移动，战役细节归 GM | `docs/superpowers/plans/2026-10-12-sd-abolition-ga-decisionmaker-hex-attrs-plan.md:14-15` 用户原话 | 本轮 GOV 自由组建部队应使用真实人口/资产来源，不能直接开放 GM 的凭空生成入口；本轮未要求更改战斗裁定权限 |
| 决策人归属归各模块，新增模块不能要求 GA 重写归属种类 | 同文 `:127`；`simos-ga/src/main/java/io/mosire/simos/ga/model/DecisionMaker.java:69` 回代码核过 | 通用的是组织关系与接入协议；GA 仍维护一般决策人运行时 |
| 中央不设全视野 | `docs/superpowers/specs/2026-10-02-architecture-design-source.md:22-27` | 组织树不能自动向上汇总所有地方、军队的视野或控制权 |
| 人员流失不缩/释放 GOV_SERVICE 承诺；户空不摘岗位、不摘 Unit.households、不迁壳户 | `docs/superpowers/plans/2026-10-13-emergency-stop-protocol.md:508-511` 最新裁定 | 不能以“修家户管理”为名自动删空户岗位、停止工资；实际劳动分配与职位承诺分开 |

文件名带 2026-10-23 的设计文档作为既有内容引用，不用文件名推断用户说话日期。

## 3. 新增已复现缺陷

### H1：合法自定义官吏家户，被招募工具认成坏数据

回代码核过：`simos-gov/src/main/java/io/mosire/simos/gov/tools/write/GovRecruitPlan.java:125-137` 固定寻找 `hh-unit:<govUnitId>`。只要没有这个固定 ID、但已经存在岗位，就拒绝并声称 posts 不可能是 Unit.households 的子集。

这个判断不成立。当前模型允许任意 HouseholdId：`simos-unit/src/main/java/io/mosire/simos/unit/GovernmentFormation.java:57-66` 岗位表以 HouseholdId 为键，`:90-105` 只校验键值身份；`UnitState.java:32-63` 校验家户唯一容纳。

探针把中央政府内部官吏户换成真实的另一户，保留财政户、岗位配置；UnitState 接受，Social.location 同步设为该 GOV。招募预览却拒绝：

```text
CUSTOM_OFFICIAL_HOUSEHOLD success=false code=BAD_REQUEST message=GOV 单位 gov-central 的 householdPosts 非空但没有岗位户 hh-unit:gov-central：岗位数据坏（posts 的键不可能 ⊆ Unit.households）；先补齐岗位户（Z3 simos.gov.expandHousehold / unit.SetUnitHouseholds）再招募（不猜、不静默降级到财政户）
```

影响：模型支持多个自定义家户，操作层却把默认创建名字变成身份约束。已有组织只要不是按工具的固定模板创建，就无法正常招募。与多家户、自定义政府组织目的相悖。

### H2：家户从 Unit A 调到 Unit B，合法最终状态被中间状态检查阻断

回代码核过：`simos-social/src/main/java/io/mosire/simos/social/tools/write/UnitAssignHouseholdTool.java:151-187` 先校验“目标单位加入家户”，后构造“来源单位移除家户”。第一步校验仍基于旧状态，家户还在来源单位，立即触发 Unit 的唯一容纳守卫。

```text
CROSS_UNIT_MOVE success=false code=BAD_REQUEST message=同一个家户不得同时属于多个 unit: household=hh--2_0-rural-poor_peasant 同时在 audit-a 与 audit-b
```

影响：编制间整户调动失败，政府与军队之间转移整户同样受这个基础设施缺陷影响。该工具本身是 GM 工具（同文件 `:240-243`），故不能把这个结果说成“GOV DM 当前已有调户工具”；缺陷在通用调户机制。

建议：在 Unit 域内一次生成同时修改两个单位的变更集，批中再协调 Social.location。只改变最终一致状态，不放宽一家户至多属于一个 Unit 的守卫。单纯交换旧工具两条命令的顺序，也不能解决岗位引用、Social 位置等全部跨切片约束。

### H3：退休的角色参数不能保证实际退出该角色

回代码核过：`simos-gov/src/main/java/io/mosire/simos/gov/tools/write/GovRetireStaffPlan.java:121` 解析 role；`:147-162` 固定选择标准岗位户；`:270-277` 从该户分配人口，未按实际岗位 role 验证或筛选。

```text
RETIRE_ROLE actualSourcePostRole=POST requestedRole=YAMEN success=true
```

影响：请求“退休一名衙役”，实际可从驿卒岗位户抽人；角色参数成为不能兑现的业务承诺。前一轮另已回核显式退休缺少相应岗位/劳动承诺释放，不在本轮冒充新发现。

建议：明确“人口离户”“岗位任免”“服务承诺变更”是不同操作，并提供一个组合离职流程。部分退休应只变更对应任职/合同份额，不能粗暴删除全户岗位与全部承诺。外部服务终止通常也不要求搬户。自然死亡、逃亡、户空则继续遵守最新保留职位承诺的裁定。

### H4：同一家户对多个政府服务，实际劳动被重复计入

回代码核过：`simos-app/src/main/java/io/mosire/simos/app/household/GovernmentServiceLaborBridge.java:247-248` 源码已经承认这个边界；`:283-289` 每个 GOV 独立执行 `min(该 GOV 承诺, 家户全部实际劳动)`。

探针给同一真实家户两个合法 GOV_SERVICE 承诺，分别配置为两个 GOV 的外部岗位；EconomyData 接受承诺状态。模拟当日实际劳动下降到 10,000，然后调用现行供给桥，扣除各政府原有官吏供给：

```text
CROSS_GOV_SUPPLY gov=gov-central externalHouseholdEffectiveLabor=10000
CROSS_GOV_SUPPLY gov=gov-province externalHouseholdEffectiveLabor=10000
CROSS_GOV_TOTAL actualHouseholdLabor=10000 suppliedToGovernments=20000
```

影响：同一批实际劳力重复改善两个政府的行政能力。外部家户、多级政府、自定义多重任职越丰富，越容易触发。和 Social 劳动唯一权威、按家户守恒相悖。

建议：先对一家户全部有效服务统一分配当日实际劳动，再投影到各岗位/GOV；跨 GOV 的实际使用总量不得超过该户实际供给。保留承诺合同及发薪依据，不能通过截断持久承诺“修复”这个问题。具体分配优先级需在实施约束设计中明确，本报告没有替用户裁定比例或先后顺序。

### A3：可见其他军队的范围，被用来放行对它的移动写入

回代码核过：`simos-app/src/main/java/io/mosire/simos/app/access/ArmyScope.java:95-110` 收集视野内所有单位及其后代，没有己方组织过滤；`simos-app/src/main/java/io/mosire/simos/app/tools/write/AdjudicateTickTool.java:676-685` 把这个范围用于 `Operation.WRITE`。`simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanRouteHandler.java:34-49` 是 DM 可用命令，目标只声明被移动的 Unit，没有 Army 隶属校验。

探针创建两个属于不同 GOV 的独立 Army 身份，根单位同格，决策人只归属 Army A。使用真实 ArmyScope、PlanRouteHandler 的目标解析，再调用现行裁定工具的写权限围栏：

```text
OTHER_ARMY_MOVE_BOUNDARY ownArmy=audit-army-a targetArmy=audit-army-b violations=[]
```

影响：移动另一个军队的目标能通过当前范围校验。这个结果证明目标授权缺少己方指挥关系检查，**没有证明已绕过 GM 审批实际移动敌军**；也没有测试 DSH 正在修改的新版 packet 全链路。

建议：视野检查回答“能否知道这个实体”；指挥授权回答“能否向这个实体下令”。两项分别判定。层级关系不能把所有看见的单位变成下属，也不能把名义上级变成全视野操作者。

## 4. 为什么仅合并 GOV / Army 的归属字段仍不够

### 4.1 已有通用关系，但特化关系独立存储

回代码核过：

- `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java:105-131`：Unit.parent 是编制归属，attached 决定是否随父一同移动。
- `simos-unit/src/main/java/io/mosire/simos/unit/CommandChain.java:9-18`：另有通用命令链，允许多属，是具名星形集合，不是唯一父树。
- `simos-unit/src/main/java/io/mosire/simos/unit/GovernmentFormation.java:63`：另存 superiorGov；`unit/ops/UnitOperations.java:1025-1040` 只认 GOV 上级。
- `simos-unit/src/main/java/io/mosire/simos/unit/ArmyFormation.java:41` 与 `simos-army/src/main/java/io/mosire/simos/army/Army.java:50`：分别存 masterGov / masterGovUnitId。
- `simos-army/src/main/java/io/mosire/simos/army/spi/SetArmyMasterGovHandler.java:41-43`：双边同步归组合工具，单命令可只改 Army 一侧。前轮已核对组合工具默认同步、但允许关掉同步。

这些关系没有形成同一个权威系统。不能仅新增一个“通用 relation”后让旧四处关系继续独立写，否则只是增加第五处真相。

### 4.2 现有 parent 创建入口会自动附随，不能直接当作行政隶属工具

回代码核过：`simos-unit/src/main/java/io/mosire/simos/unit/spi/CreateUnitHandler.java:93-107` 有父时默认 attached=true，且要求同格；`unit/ops/UnitOperations.java:136-151` 改 parent 不改变 attached。

因此“把远方驻军的 parent 改成某政府”不能直接用现有默认创建模板完成。通用组织操作需要显式表达是否独立移动，并守住行政隶属与同格合编的差别。需要临时多头指挥时，评估已有 CommandChain，不要把唯一父关系随意扩成任意多父。

### 4.3 创建武装部队的入口现在不闭合

回代码核过：

- `simos-unit/src/main/java/io/mosire/simos/unit/tools/write/RaiseUnitPlan.java:70-87`、`:486`：从真实家户转人、创建普通 Unit；没有 Army 注册命令。
- `simos-gov/src/main/java/io/mosire/simos/gov/tools/write/GovDispatchTeamPlan.java:43`、`:450`：armed 分支增加 ArmyFormation；没有 Army 身份注册。前轮已核对导致 Army 归属、视野、相遇监控入口认不到它。
- `simos-unit/src/main/java/io/mosire/simos/unit/tools/write/SpawnArmyPlan.java:39-55`：同时注册 Army，但这是 GM 凭空增加成年男丁的生成路径，不适合直接开放给 GOV DM。
- `simos-unit/src/main/java/io/mosire/simos/unit/ArmyFormation.java:41-42`：role 已是字符串，职责名称本身并没有被写死成固定兵种枚举。

所以问题并非“再加几个兵种名称”即可解决，而是需要一个有真实来源、组织关系、身份注册和后续管理能力的组建协议。

## 5. 通用化建议（未实施）

### 5.1 统一组织基础，分开业务含义

优先以 Unit 作为稳定组织身份，复用其 parent / CommandChain 基础能力；GOV 与 Army 提供领域配置。组织父关系只保留一个权威写入口，当前 superiorGov / masterGov 中表示同一隶属的部分改为派生查询。财政出资方、临时指挥方若本来不同，分别用有明确意义的关系表达，不强行等同为一个“主子”。

这是对现有独立关系存储的设计调整建议，尚不是批准后的字段删除方案。哪些旧字段表示行政隶属、哪些表示资金责任，实施前必须形成约束设计。

可支持的组织例子：

```text
省政府
├─ 治安机构
│  ├─ 城防队
│  └─ 乡勇队
├─ 水上武装
│  ├─ 巡江队
│  └─ 运粮护卫队
└─ 战时军团
   ├─ 常备部队
   └─ 临时征募部队
```

这里的名称/职责由用户配置；各节点能各自拥有多个家户，按需要注册军队身份、绑定决策人。常驻行政隶属不强制共同移动；有独立决策人的下级，不应自动变成上级可直接代决的对象。

### 5.2 家户、任职、服务与财政分别表达

| 概念 | 建议职责与约束 |
|---|---|
| 家户 | Social 维护稳定 ID、成员、位置及真实人口/劳动；Unit 维护唯一容纳结构；一次操作协调两者，不能分别手工修列表 |
| 岗位/军职 | 提供稳定岗位或任职身份、职责、档位、任期；家户通过任职关系提供服务，不能把 HouseholdId 当成唯一职位 ID |
| 劳动承诺 | 保留现有 Economy 承诺机制；GOV/Army 业务声明所需服务，Economy 统一分配实际劳动，Social 提供真实供给 |
| 财政 | 财政户继续作为当前账户锚点；人员户分别生活、持有资产、领工资，不能因同属一政府而合并经济账 |
| 决策人 | GA 保持一般身份/运行时；GOV、Army 保持各自决策人归属，通过现有接入协议提供上下文和授权 |

岗位建议引入稳定 PostId / 任职记录，是本报告提出的新形状，不是声称仓库已实现。先消除固定命名依赖和错误 role 行为，也能在尚未引入新模型时修复部分工具缺陷。

任职操作由 GOV / Army 分别解释其领域规则。现有配置部分承载于 Unit；若进一步移动存储归属，必须另做模块边界与完整状态变更集设计，本报告不以调查结论擅自搬迁状态。

### 5.3 GOV 决策人的组建操作，应是一个完整业务流程

1. 指定新组织名称/职责、上级、真实人员来源（整户转入或从明确家户抽人）、所需装备与资金来源。
2. 核对操作人的直接管理或显式授权范围、人口/资产容量，生成只读计划。
3. 同一批命令闭合 Social 家户/成员、Unit 组织与配置、Army 身份，以及所需 Economy 登记；避免只挂武装标签的半成品。
4. 创建后有配套任免、调动、改隶属、解散、回流人员与资产的入口。绑定决策人应可选，并遵守当前归属授予权限；不能由“建了组织”自动获得任意创建/冒充决策人的权力。

“任意形式”指组织层次、职责、来源家户与运作配置自由组合，不代表获得凭空人口/资产或 GM 战斗裁定权。

模块边界仍遵守根 AGENTS：GOV 不允许直接依赖 Army。GOV 提供政府意图/资格判据；Army 提供登记与军事规则；app 组合根通过端口组装跨域命令计划，Core 只执行和调度。GA 不需要因此新增 GOV/Army 专属分支。

## 6. 优先级与实施验收形状

优先修复：移动目标的指挥授权与视野分离；前轮四个 planOnly 写入；跨 GOV 实际劳动守恒。随后闭合调户协议、去掉官吏户固定 ID 假设、补齐显式任职终止与退休角色校验，再统一组织关系与组建生命周期。

后续实现至少应接受以下反例验证（本轮没有编写生产测试）：

- 三个任意 ID 的官吏户可分别招募、任职、调动，不要求全体并入 `hh-unit:<gov>`。
- 整户跨 Unit 调动成功，旧/新容纳列表与 Social.location 同时一致；重复容纳仍拒绝。
- 同户多人不同职责，明确退休目标任职，部分退休不清空其他任职。
- 同户服务两个 GOV，饥饿后实际使用总劳动不超过 Social 供给；持久职位承诺按裁定保留。
- Army DM 能向自己的授权单位提交移动意图，看见别的军队时不能据此下令。
- 创建远方行政下属不被当成同格合编；合编仍要求同格，独立移动仍可用。
- GOV DM 组建自定义护卫/乡勇后，Army 身份与配置完整；失败则整批不落半套状态。
- 隶属调整不新增中央全视野；岗位、家户、Army 身份与历史以稳定 ID 连续。
- 所有新完整状态字段参与 ChangeSet 往返重建，遵守 `Command → ChangeSet → Revision`。

## 7. 证据、实际执行与未验证范围

证据目录：`.superpowers/sdd/2026-10-14-gov-army-generalization/evidence/`。

实际执行命令，退出码 0：

```bash
bash .superpowers/sdd/2026-10-14-gov-army-generalization/evidence/run-probe.sh
```

脚本直接编译本轮相关现行源码到新的 `/tmp` 目录，依赖/SmallWorld 夹具来自已有 shaded jar，源码类优先加载；不是全仓重新构建。实际输出见 `output.log`，关键输出原文已在 §3 各条引用。脚本与 `source-sha256.txt` 记录编译名单、依赖与源码指纹。探针包含拒绝任何写调用的 ToolWritePort；这里只读预览/纯函数，没有真实世界提交。

未运行 Maven、JUnit、门禁或真实决策人完整回合；没有修改运行中 jar，没有停止/启动服务，没有实际调动、退休、发薪或落 Revision。工具预览夹具协调 Unit/Social 的相关事实，没有声称已经验证该改动后的整世界 Economy 投影或下一日推进。跨 GOV 实验模拟已下降的当日劳动，不是完整饥饿推进实验。权限实验只验证当前目标授权围栏，没有执行 GM 审批、命令落库或 DSH 新版 packet 全链路。

生产代码和现有测试均未改，未提交。上述缺陷为当前快照调查结论；通用关系、稳定岗位身份与组建协议为后续设计建议。
