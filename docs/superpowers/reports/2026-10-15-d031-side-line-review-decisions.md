# D-031 侧线矛盾清单复核与技术决定（2026-10-15）

> 状态：控制方复核决定，供侧线复制进入分节设计/spec/计划；没有实现新模块，没有替用户增加新业务裁定。
> 用户本次原话：「隔壁出实现报告了，你核对，真和设定有矛盾、需要裁定的你再问我，如果你能自己做裁定，直接给我你的决定我复制过去」。
> 复核对象是侧线三份 proposed 笔记，尚非代码实现验收。主检出当前有另一批测试在途，未触碰。
> 侧线记载“只需要把接口立好”的范围原话，来源为侧线笔记转引；本文按该笔记的接口先行范围审查，不将其伪装为本主会话直接收到的逐字原话。

## 1. 证据与复核范围

- 侧线原件：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.agents/notes/proposed/architecture/2026-10-15-contradiction-to-ruling-list.md`，以及同目录 `2026-10-15-d031-interface-first-scope-trace.md`、`2026-10-15-new-module-mechanical-checklist.md`。
- 用户方向：`docs/superpowers/specs/2026-10-02-architecture-design-source.md:350`（D-022）、`:487`（D-031）、`:509`、`:522`（两次补充）。以逐字原话和最新补充为准，不把旧代理自定条款升格成用户原话。
- 本轮通过 `dsh headless - < /tmp/simos-d031-side-review-task.txt` 委托 DeepSeek 只读复核；返回码 0，结果在 `/tmp/simos-d031-side-review-result.md`。控制方另读关键原文/代码后作以下决定；临时结果不是长期交付物。
- 发现的实际材料是规划清单，未见新 `simos-culture`/`simos-production` 实现；未运行 Maven/测试/服务，不声称实现通过。

## 2. 可直接给侧线的决定

### M2：信用/利息与生产加成不互斥

本轮聚焦生产熟练度接口；复杂宗教信用/利息规则延期，旧需求保留登记，不宣布作废。
不要求为了未来效果预建一套万能数值修正框架，也不承诺“增加枚举就能表达全部利息合同规则”。生产加成采用有明确对象、来源和量纲的契约，未来领域能力按需要扩展。
依据：旧文化规划 `docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md:3`、`:7`；D-031 原话说“首先”和经济作用于生产熟练度（设计原稿 `:505`）。这是范围分期，不是两个效果必须二选一。

### M4/R1：最终定义归前置模块已定，接口与迁移分阶段

不再请求用户在“定义永留 economy”与“归前置模块”间重选。
本轮按接口先行范围立生产定义契约、查询/供给边界及迁移计划；允许旧生产路径暂时仍使用 economy 中的旧定义实现，但必须标为尚未迁移，不能称生产前置化已完成。
后续接线完成标准：生产前置模块成为定义及注册的唯一权威写者，经济通过契约消费；没有新旧两套可独立编辑的定义，没有第二份权威目录。
迁移定义不等于把生产运行实例、劳动分配、账户和债务一并搬入生产模块。
依据：设计原稿 `:513`、`:519`；`simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java:126` 混有定义与实例字段。

### M15/R2、M16：先采用两个模块，允许经济消费前置生产

本阶段采用 `simos-culture` / `io.mosire.simos.culture` / namespace `culture`，以及 `simos-production` / `io.mosire.simos.production` / namespace `production`。
不先为两个领域各增 api+impl；独立 api 模块只有在实际依赖/消费者需要时再拆，不能以“改 POM 多”当架构理由。
生产对外定义/lookup 契约优先组织在生产模块的公开契约包；现有底层 ID 可暂复用 `economy-api`，不为改包重复造身份。经济允许声明生产模块为前置依赖。
建议依赖方向：`production → util/map/economy-api（按实际用到的契约）`；`economy → production + 现有允许面`；文化按需要引用 Social/生产契约，向经济输出沿用经济接收契约；app 组合接线。
禁止 `production → economy` 实现和领域模块 → app；新允许面须同步约束设计、POM/enforcer、边界表及测试清单。
“经济永不依赖前置生产实现”是侧线自行加的约束，不是用户铁律；现有 `economy → social → economy-api` 已有无环先例。
如果设计阶段发现该建议依赖形成实环，提出最小契约抽取方案再由控制方技术裁决；不把 Maven 模块个数作为业务问题反复询问用户。

### M6：本阶段保留零人口身份与经济壳，不擅自清账删户

保留 Social 零成员家户及 Economy 零人口投影行，已有的迁移清点、残留财债拒绝和引用校验维持；零人口不等于可继续生产或作为普通迁移源。
不因“清零删户”的早期整理措辞删掉合同端点、身份或财货；后续若设计通用注销能力，需证明人口、财货、合同和引用全部处理完，另列生命周期工作。
D-022 的逐字原话（`:353`）只要求转户/新建表达生产方式变化；`:366` 的删除在代理整理的“设计裁定”段。后续同主题成文已经细化：
- `docs/superpowers/specs/2026-10-06-seven-hex-full-chain-merchant-migration.md:205`：残留时保壳；
- `docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md:262`：0 人口壳/清理债务与质押；
- `docs/superpowers/plans/2026-10-10-p0-mode-migration-social-outbox.md:100`、`:122`：同批 delta 回写需要源行存在，始终保壳且 Social 留空户。
实现与此相符：`simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:684`；Social 桥只建目标/转成员（`simos-app/src/main/java/io/mosire/simos/app/household/MigrationSocialBridge.java:117`）。
因此不要求用户重新追认一次实现；在新设计追加后续细化说明，不篡改历史原话。

### M12/M13：接口可以先立，实际收益与预期口径必须共同接线

本轮允许先完成契约与默认中性路径；不得以空占位字段或始终返回 1000‰声称文化效果已经生效。
若本轮选择启用生产熟练度的真实非中性修正，预期利润评估须同时消费同一生产定义、同一已知修正语义；不能实际生产吃加成而迁移决策仍按中性配方，亦不要求预期利润预知未来未知天气等因素。
允许累计实际修正与预测输入在时间窗口上有所区别，需明确时间口径，不能复制两套定义与倍率规则。
文化可以成为首个实际生产者；“第一生产者”是接线阶段目标，不是本轮接口骨架已经完成的事实。
依据：`docs/superpowers/specs/2026-10-23-production-efficiency-framework.md:362`、`:371` 只记录旧批边界；当前 `ExpectedProfitBook` 尚无修正消费，不能据旧边界永久推迟一致性。

### M5 及其他项

M5 按最新原话：流动在市场结算周期执行；逐 tick 的候选/修正评估与周期执行在 spec 分开描述。本阶段只设计协议也可，但必须标明尚未替换旧双机制。统一周期和追加轮规则由设计给出技术方案，不重复问已经明确的“是否同步”。
M1/M3/M7/M8/M9/M10/M11/M14 维持已定结论；“以代码为准”仅描述当前结构，不能作为以后代码高于用户原话的通则。
M13 的确定性要求不能被解释为所有派生倍率都必须单独存一张表：可编辑文化状态和规则须有可回放来源，纯派生结果可以即时计算，不存第二份权威真源。

## 3. 机械清单需纠正的通则

- 纯持久/声明模块不必强行添加 `TimeParticipant`。`TimeAdvance.java:167` 起 codec 与 participant 各自注册并检查，不要求一一对应；Gov 有持久状态而没有自己的 participant。
- 模块清单、enforcer、往返守卫及扫描根按实际新增能力同步；不为满足计数造空推进器/空工具。测试阶段统一执行，不能把未跑写成通过。
- 两个无冲突的领域可以共享底层契约；契约包所在 Maven artifact 不自动等于状态所有者。方案必须列出定义对象、状态、写者、读取接口及实例引用的实际流向，不能只画模块名字。

## 4. 本轮仍需用户新裁决的事项

就已提供的清单而言，没有必须追加的业务裁决。M4 的最终所有权已由用户明确，M6 可由逐字原话与后续文档溯源解决，其余为范围/接口/技术分期决定。
若后续 spec 发现确实必须改变用户原话（如改成经济继续永久拥有定义、文化改写 Social 人口、或删除负债/库存三步经济因素），应明确冲突原文后再上报，不把这些偏离当作技术便利直接实施。

## 5. 未做/未验证

未修改侧线三份笔记、未改生产代码、未提交、未运行 Maven/测试。依赖方向是待 spec 展开并静态/构建验证的技术方案，不声称新模块已编译或功能已接通。日期项沿用侧线与先前调查证据，本轮未再复跑全部历史日期。

## 6. 侧线第 1–9 节分节设计复核（2026-10-15）

> 审查对象为用户在本会话粘贴的侧线分节设计，尚非已落盘 spec。结论：允许直接进入 spec/实施计划编写，以下技术修正同步纳入，无需另向用户征求同意。本项不是实现或提交授权。
> 本次 DeepSeek 只读复核命令：`dsh headless - < /tmp/simos-d031-sectional-review-task.txt > /tmp/simos-d031-sectional-review-run.out 2> /tmp/simos-d031-sectional-review-run.err`，退出码 0；临时结果 `/tmp/simos-d031-sectional-review-result.md`。以下为控制方核定，未照抄代理全部建议。

1. **生产视图补齐现有定义能力与身份关系。** Industry 定义、ProductionMode、Recipe 可以拆成关联视图，分别明确稳定 ID、制度/模式映射、已有版本和阶层结构引用。`Industry.java:127` 起有 id/name/regime，`:133` 的 dailyLaborPerUnit 与 `:144` 的 moneyOutputPerUnit 在侧线视图遗漏；`ProductionRecipe.java:54` 也含货币产出。必须保留这些定义信息，不能丢铸币；Mode 的 version 是真实既有字段（`ProductionMode.java:22`），不要伪称 Industry 已有 version，也不要伪造两个 ID 的一一映射。运行实例字段继续排除。
2. **公开契约的字段类型必须无环。** `ClassSlot`、`AllocationRule`、`MoneyOutput` 等当前在 `simos-economy.model`；production 公开视图不能直接引用这些经济实现类型，否则 production→economy 造成环。应定义/抽取允许依赖内的不可变契约值并由经济适配器转换，不能为了省字段类型破边界。仅转换视图不意味着新建第二份权威状态。
3. **文化身份必须能连到加成规则。** 当前拟议 `HouseholdCulture(householdId, tag)` 与 `CultureTag(地域认可,宗教,习俗)` 没有 CultureId，但规则按 CultureId 索引；补显式 CultureId 关联。生产键须明确为 production 公共契约中的稳定类型，不能含糊自由字符串，也不要直接把有 hex 语义的 IndustryId 当跨地区生产技能键；实际键与查找路径在 spec 中给出。
4. **“规则可编辑”必须有命令写口。** 除设置家户文化外，补生产熟练度规则的增改/删除语义及命令；所有写仍走 Command→ChangeSet→Revision。若本轮只立契约，写清哪个命令/状态在本轮实现，不得写一张只有硬编码能填的所谓可编辑表。
5. **补家户规则到生产单元输入的转换契约。** 既有接收口按 ProductionUnitId 收一个 0..2000‰ 的值、重复 unit 拒绝。spec 须说明家户文化/生产规则如何按劳动贡献关联到 unit，由哪个领域服务计算组合结果；不能默认只看生产经营者文化，也不能让 app 自己实现文化公式。app 负责调用与传参。本轮可保持不启用，未知复杂文化/科技组合算法不预建成规则引擎。
6. **边界禁令写准适用面。** 将“任何模块不能依赖 core”改为“新增 culture/production 不依赖 core/app”；已有组合根对 core 的依赖保持既有允许面。economy-api 不能反向引用 production 中新定义类型，否则 production→economy-api→production 成环。
7. **实施顺序与测试归属写清。** production 公开契约先达到可编译状态，culture/economy/app 再消费。按责任区派单；生产代理只过编译，所有 Java 测试清单及断言修改、测试和变异归 Z6 测试代理统一完成，控制方负责调度验收。writing-plans 可以列验证命令，但不得据此恢复“每个小任务配一轮测试”。

范围仍沿用三处未完成标记：定义尚未迁移、真实效果未启用、旧双流动机制未替换。技术掌握范围的 typed 契约可先定义，不因接口先行自动生成传播/升级行为；没有必须新增的用户业务裁决。
