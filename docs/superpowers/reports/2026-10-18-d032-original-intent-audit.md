# D-032：按用户原始口径核查当前实现（2026-10-18）

> 审核对象：侧线 `parallel/side-module`，HEAD `de02aa3ff8fecf1181a3f5ddfc0ae251906462e0`。本报告是实现审核，不是新的玩法裁决或实现派单。
> 依据：本会话用户原话、[设计原稿 D-032/U1–U23](../specs/2026-10-02-architecture-design-source.md#L561)。旧提案中被 U15/U19/U21/U23 取代的解释不作为验收依据。
> 结论：主要数据结构和多项结算能力已经落地，但还不能验收为“原始设计已全部实现”。发现 **3 项可复现的运行断路、1 项尚未完成的选业接线、1 项抵物顺序偏差**。全量构建绿色与这些发现同时成立。

## 1. 对原话的取舍

| 来源 | 当前有效口径 | 本次核查重点 |
|---|---|---|
| U1/U2/U4/U5 | 技术以可选生产方案体现；经验按家户独立记录，可共用经验条；熟练度公式可配置；生产选择按本户需求和收益，主副业由投入形成 | 定义/实例、经验增长/解锁、预计与实际、选业消费者 |
| U3/U4 | 迁出部分保留原经验，合入后按人口成分平均；财产和债务按比例转移 | 经验缺行须算零，不能跳过稀释 |
| U5–U12 | culture 与生产效率解耦；production 掌握“怎么生产”及其状态，economy 掌握实际分配、经济关系和结算 | 所有权与调用方向 |
| U13/U14 | 同一实例可多户分角色；同一经验条、各户独立数值；不同角色经验增长量可配置 | 真实参与者经验、角色系数 |
| U15（取代旧劳动权重解释） | 直接产品份额；地主可以零劳动收租；角色池不能重新乘劳动比例 | 400/400/200、零劳动地主 |
| U16/U17/U18 | 地主自主报价，零/低价可存在；雇工工资须进入选业前费用；现金/库存付不起成债；再生产权益不作活动库存 | 预计劳动与工资成本、支付预算 |
| U19/U20 | 可按生存条件闲置；博弈跨 tick 发生；缺工快速累加加价，招满后缓慢加速降价 | 真实 tick 是否观测并更新报价 |
| U21 | 欠薪记录不折扣下一 tick 择业；禁止预付；先钱后数量最多的可支付库存，剩余成债 | 支付顺序、共享付款余额 |
| U22/U23 | 先修 R2；欠薪零息；任何家户在全部可行谋生方式均不足维持下一 tick 生存时可被裁决不从业；抵物折价可更低且可配置 | 共享资产、零息、Social/economy/production 边界 |

原话 U23 位于原稿 `:758–768`；它不等于“缺省一个 allowIdle=true 开关”，也不要求一旦有闲置资格就必须闲置。旧 ΔB×k 工资定价、劳动×系数产品分配均未重新提升为要求。

## 2. 发现（按处理优先级）

### A1 [P1，运行断路] U20 调价函数没有进入生产日循环

**已回代码核查**：`simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionWageBook.java:267` 定义 `adjustedPostings`，`:290` 调用内部算法。整个 `simos-*/src/main` 中没有调用它的外部位置，也没有构造真实 `WageObservation` 的调用点。`EconomySettlement.java:2070` 已接支付，但没有随后观测并写回下一 tick 报价的阶段。下文未重复目录的 economy 文件均位于上述 `time/` 包，production 引擎位于 `simos-production/src/main/java/io/mosire/simos/production/`。

**独立运行复现**：用仓内 `W4SurvivalTickIntegrationTest` 的真实 `EconomyDayStepper` 夹具，注入 Social 生存事实后实际 `step(1)`。初始报价 1、目标劳动 1000、实际劳动 0：

```text
PROBE_U20 before=1 afterTick=1 actualLabor=0 target=1000 streak=0 standaloneAdjustment=2
```

同一组缺工观测直接交给已实现的调价函数会得到报价 2；真实 tick 中报价仍为 1、缺工连续计数仍为 0。不是默认速度慢，而是调价入口没被调用。

**影响**：低工资→家户闲置→雇主缺工→下一 tick 加价这条反馈在最后一步断开；招满后的降价同样没有入口。

**修复归属/验收**：economy 读取角色级实际劳动和有效招工需求，结算完本 tick 义务后只更新下一 tick posting，经既有 ChangeSet 提交。测试须实际推进多个 tick，覆盖缺工累加、从零加价、招满后延迟/缓慢降价，以及当日工资不被新报价追改。只调用纯调价函数的测试不足以关闭此项。

### A2 [P1，运行断路] 合作生产的预计产出用组织者经验，实际产出用出工者经验

**已回代码核查**：

- `ExpectedProfitBook.java:2396–2418` 汇总名册劳动后，把全部劳动连同 **organizer 的 mastery/experience** 作为一次单户 `probe` 的输入。
- `ProductionRuntimeEngine.java:462–475` 的 probe 只有一个家户条件，按该户求效率。
- 同文件 `:894–899` 的 execute 则按每个实际贡献家户分别求效率，再累计贡献。

**独立运行复现**：同一方案、同样劳动 100、土地 100；组织者经验为 0，实际出工家户经验为 1000；配置总效率 `1+a`。按上述估算输入调用 probe，再用相同资源和实际劳动调用 execute：

```text
PROBE_COOP_MASTERY organizerExperience=0 workerExperience=1000 sameLabor=100 sameLand=100 expectedScale=100 actualScale=200 expectedOutput={grain=100000} actualOutput={grain=200000}
```

这是按当前 `rosterIncomes` 入参构造的 probe/execute 契约复现，未冒称跑过整个迁移周期。预计毛产 100 单位，实际毛产 200 单位；劳动和资产没有变。

**影响**：GUI 逐户预计所得、生存判定的产品份额投影，以及以后采用它的排序都会受组织者经验高低误导。高经验地主也可能反向高估低经验雇工的实际产出。

**修复归属/验收**：production 的合作试算须能接收预期参与者/角色/劳动及各自条件，并与 execute 使用同一套效率求值；economy 提供事实，不在估值层复制效率公式。应覆盖经验分布互换、同户多角色、多个家户共同劳动的预计/实际对照。

### A3 [P1，运行断路] 合法的“零劳动地主 + posting 招工”会被预计器判为没劳动

**已回代码核查**：`ExpectedProfitBook.java:2397–2405` 的物理劳动只求和 `roster.participants[].plannedLaborMilliPerDay`；工资 posting 的 target 在后面的工资费用段才使用。`MONEY_WAGE` 角色允许通过 posting 招工，不要求名册先塞一个假 worker。因此纯收租/组织者名册可以劳动为 0，但已存在合法招工需求。

另一个旧入口 `ExpectedProfitBook.java:557–571` 的 `productionProspect` 仍只把组织者家户自身劳动用于物理 probe，工资成本前置并未自动解决雇工产出估计。

**独立运行复现**：沿用 W4 的有效方案、实例、资产、报价和账户，名册保留零劳动 organizer，移除预先指定的 worker；posting 目标仍为 1000。直接调用真实 `ExpectedProfitBook.rosterIncomes`，再让生产引擎按雇工实际劳动 1000 执行：

```text
PROBE_WAGE_ROSTER organizerPlannedLabor=0 postingTarget=1000 estimatedRejection=Optional[PROBE:labor-short] actualScale=1 actualOutput={grain=5000}
```

这证明预计器把该合法招工形态直接判为 `labor-short`，而引擎在收到真实雇工投入后可产粮；此复现没有声称“posting 目标本身保证一定招满”。

**影响**：地主“先比较雇农方式的预计收益，再决定采用”仍可能在劳动入口就被否决。UI 或生存投影为了不报零而预置假 worker，也会掩盖这条断路。

**修复归属/验收**：economy 应为候选形成有依据的预期雇工劳动，并用 posting target 作需求边界，再传 production 试算；目标量不等于保证成交量。不得通过强制往 roster 塞 worker 修补。验收须从 organizer-only 名册出发，覆盖预期无人/部分招到/招满，以及相应产出和全部应付工资同时进入候选收益。

### A4 [P1，未完成接线] 逐户角色所得尚未成为产品分成角色的选业排序口径

**已回代码核查**：

- `LaborQueueSettlement.java:574–590` 对产品分成角色仍调用 `LaborQueueBook.offer`。
- `LaborQueueBook.java:361–405` 估值的是整个 probe 的产出/投入；`:411–423` 将该净收益放入 offer。role 在返回值中作为身份存在，但份额没有进入这段收益计算。
- `rosterIncomes` 在 main 的实际消费者是 app 只读展示 `AppProductionConsumerPort.java:400`，以及 `LaborQueueSettlement.java:2066` 的生存产品份额投影；尚未替换上述排序入口。
- `ModeMigrationPolicy.java:710` 等仍走原 `ExpectedProfitBook.prospect` 体系；不能把 GUI 已显示逐户所得当成迁移/选业已使用它。

**影响**：在单元净池为 1000、本户权益为 20% 的例子中，本户该比较的是所得 200 及自己的负担；目前这一产品分成 offer 的价值入口仍看整个单元。实际分粮可以正确，行为选择却仍按另一种收益口径发生。

**状态区分**：重规划 `2026-10-17-d032-production-line-replan.md:96–98` 本来就把此事列为 **Phase M**。因此本报告将它记为原始目标仍未完成，**不指控 DSH 已宣称 M 完成**；W5 构建通过不能替代 M 的实现。

**修复归属/验收**：economy 统一使用本户预计所得减本户负担；劳动分配、迁移比较与主副业读数对齐。用整池收益排序与本户收益排序会发生逆序的场景作判别用例，不能只验证 GUI 数字。

### A5 [P2，口径收窄] 抵物顺序按整组付款的初始库存排序，未按下一笔付款时的库存重排

**已回代码核查**：`WageSettlement.java:309–311` 在 payer 整组义务循环前建立一次 `barterOrder`，`:334` 开始逐笔支付，`:350` 后复用原序；`:710–716` 确实按数量降序，但后续 `payBarter` 只更新余额，不重排。

**独立运行复现**：使用真实 `barterOrder/payBarter`，无现金、同价同折扣；初始粮 100、纤维 90。第一笔付 40 粮后，余额粮 60、纤维 90。第二笔仍取 40 粮：

```text
PROBE_BARTER first={grain=40000} largestBeforeSecond=fiber second={grain=40000} remaining={grain=20000, fiber=90000}
```

这是支付算法复现，不是整 tick 仿真。共享余额没有超支，错误在“最多”的观察时点。

**依据/性质**：U21 原话是“没钱了再按库存最多的东西抵”；既有 [反馈提案 §11.3](../specs/2026-10-17-d032-tick-wage-feedback-proposal.md#L160) `:173` 已明确“付款时读取共享工作余额”“从当前数量最大的可支付商品开始”。现有 `W3WageSettlementContractTest.java:143` 的测试名反而冻结了 **HighestInitialQuantity**，且没有第二个工人导致数量次序翻转的场景。

**修复归属/验收**：economy 每笔义务开始抵物时，按共享可支付余额重排；数量并列按稳定 ID。无需改变 production 的折价条款，也不要求在同一笔转移的每一个最小数量单位后重排。补多工人先后支付的翻序场景。

## 3. 已核到落地的部分与边界

以下不是根据“类存在”推断已完成：动态部分均在本轮独立执行，具体输出见 §4。

| 原口径 | 当前证据 | 判定范围 |
|---|---|---|
| 地主不下地也能分产品，400/400/200 | 独立调用真实 `RoleDistribution.allocate`，地主认可劳动 0，结果 400/400/200 | 分配算术通过；不代替 A4 选业接线 |
| 同经验条、家户各自数值，角色系数可配 | 两户同为劳动 10、同 farming 条，系数 1000/500，execute 后经验 10/5 | 真实引擎角色增长通过；未穷举所有归一化/管理投入配置 |
| 逐 tick 达门槛解锁、经验小额余数累计 | 独立执行已有实际行为用例 `v5ExperienceGrows...`、`n5SmallPerTickContributions...` | 对应精确用例通过，不等于自动选业已完成 |
| 零经验迁入应稀释目标 | 独立执行 `ProductionRuntimeMergeUnionR4Test.targetOnlyTrackIsDilutedByTheZeroExperienceMovers` | 该迁移经验路径通过；本轮未重新全验财产/债务比例拆合 |
| R2 同池两实例不得复制整池 | 独立执行 D4 两实例显式请求分配用例和 `UnifiedBudgetNoDoubleBookingTest.r2SamePlanTwoInstances...` | 原共享资产分配反例已被封住；未据此宣称所有资产/跨季回填场景都完备 |
| 再生产占用不属于可支付库存 | 独立执行 `D4InventoryReservationTest.wageLookupDeductsFrozenAndActiveReservationsFromAllLocationsExactlyOnce` | 冻结/ACTIVE 占用扣除的指定用例通过 |
| 先现金、可配折价抵物、剩余零息工资债 | 独立执行 W3 现金优先、500‰ 折价与欠薪来源用例 | 支付/零息与来源保护通过；抵物多笔次序仍有 A5 |
| 任何家户按下一 tick 生存条件可闲置 | `W4SurvivalTickIntegrationTest` 的真实 Social 需求注入和 step 路径可运行；资格与选择分离 | 基础接线存在；A1/A2/A3 会影响完整反馈和投影，不能验收为市场博弈闭环 |
| 文化不再直接决定生产效率 | 被删 aptitude/composer/劳动口只剩历史说明；生产规则/经验在 production | 本轮未发现原倍率链复活；宗教/教育后续功能未计入本次生产线完成度 |
| 模块边界 | economy 的 production import 均为 contract；production 的 economy import 均为 economy-api；查过 `AppProductionLookups.java:94–100` 的真实只读装配 | 未见上述依赖方向越界；仍有下面列出的属性源未接 |

定义/实例分层、无地点方案与显式地点实例的形状已经存在；本轮没有把“能够建出该形状”扩大表述成“自动进入、升级与迁移全链已验收”。

另外，`AppProductionLookups.java:98,113–114` 仍提供 `UnavailableParticipantAttributeLookup`。因此识字率等外部属性条件的数据源还未接，声明此类条件会按既定规则拒绝；这是已公开的未完能力，不是文化倍率链重现。

## 4. 验证方法与原始输出

证据目录：`.superpowers/sdd/2026-10-18-d032-original-intent-audit/evidence/`，按仓规忽略、不入 git。里面有 `baseline.json`、两份 probe 源码、classpath、编译/运行日志、`exit-codes.json`、静态消费者检索与 `verification.json`。

### 4.1 本轮独立运行

在侧线根目录执行：

```bash
python3 .superpowers/sdd/2026-10-18-d032-original-intent-audit/evidence/run-probes.py --current
```

脚本只用 javac/java 在证据目录编译并运行独立探针，调用当前 build 的真实类；没有改 main/test、没有占用 Maven target。此前也用冻结的同 HEAD 字节码执行过。最后一次结果：

```text
compile 0
AuditEconomyProbe 0
AuditProductionProbe 0
```

退出码 0 表示探针正常完成，**不表示 A1/A2/A3/A5 的行为符合设计**；异常结果就是 §2 保留的 `PROBE_` 输出。另有 9 个具名既有行为用例被本轮独立调用并正常完成，以及独立的产品份额、角色经验算例。

### 4.2 DSH 的最终构建：本轮独立核验产物，未冒称本人重跑

命令：

```bash
tools/mvn-lock.sh -pl simos-app -am clean verify
```

原日志/元数据：`.superpowers/sdd/2026-10-17-d032-w5-6/clean-verify.log`、`clean-verify.meta`、`clean-verify.rc`。记录 HEAD 与本报告一致；`rc=0`，2026-10-18 04:02:23 +08:00 完成。

```text
[frontend-gate] OK tests=412 pass=412 fail=0
[shaded-guard] 结论：OK（8263 个条目；7 条运行期类 + Main-Class 全在）
[INFO] BUILD SUCCESS
[INFO] Total time:  05:54 min
```

本轮按 `simos-*/target/surefire-reports/TEST-*.xml` 的 tests/failures/errors/skipped 属性独立合计，得到 **3572/0/0/7**；production 84/0/0/0、economy 452/0/0/0、app 1017/0/0/7。原始逐模块计数和日志摘录在 `verification.json`。生成命令如下，逐文件 `xml.etree.ElementTree.parse` 后对四属性求和：

```bash
python3 .superpowers/sdd/2026-10-18-d032-original-intent-audit/evidence/collect-verification.py
```

现有绿色用例没有覆盖到 A1/A2/A3/A5 的组合情形。

审末再次核对 `git rev-parse HEAD` 和 5 个关键源码的 SHA-256，与 `baseline.json` 全部一致。两份探针最后使用的是本轮完成构建后的真实 classpath。

## 5. 工程处理建议与未验证项

工程上先补 **A1 的日循环观测/调价**，再将 **A2/A3 的合作试算事实统一**；A4 继续完成既定 Phase M，A5 收回到逐笔共享余额。均不需要用户重新裁定已经明确的玩法。实现后必须以实际 tick 行为和逆序选择场景验收，不能只证明辅助函数可调用。

本次没有修改业务代码/仓内测试，没有派实现代理、没有 commit/merge/push/stash，也没有修改其他控制方的交付记录。新增本报告与索引，独立证据留在上述忽略目录。

**未做**：没有本人另起全量 Maven/gate，没有对共享工作树做源码变异，没有运行长周期真实世界景气/萧条仿真；没有穷尽多需求组合、所有经验公式/归一化输入、跨季资产回填、全部迁移财债路径；宗教/教育后续实现不在本次生产线验收范围。不能据本报告宣称其余代码“无已知缺陷”或系统“已收敛”。
