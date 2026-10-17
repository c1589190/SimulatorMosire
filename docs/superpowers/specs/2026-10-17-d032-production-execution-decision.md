# D-032 补充裁决：D3 删除与 D4 生产执行职责（2026-10-17）

> **后续工程裁决/局部收口（2026-10-17）**：`docs/superpowers/specs/2026-10-17-d032-production-runtime-boundary-decision.md` 已明确 §3 的实例/进度归属、三处工程待定项、计算契约与账户提交边界。用户业务原话保持有效；对应工程问题不再待用户选择，本文旧正文保留。
> **状态：用户裁决记录，尚未实现；供相邻会话接续设计，不是实现派单书。** 用户原话见 §5；§2 是本次评估提出的工程边界整理，具体接口及状态拆分尚未冻结。
> **权威入口**：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 D-032 / U8。
> **局部取代**：调查报告 `docs/superpowers/reports/2026-10-17-d032-production-mode-survey.md` §6 中 D3/D4“待答复”状态，以及 §6.5 仅收归模板定义的建议范围；D4 现扩展为生产计算与生产状态变化的职责调整。旧报告正文保留为历史现场。
> **承接**：`docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`；文化模块保留占位及标签面，当前不推进 D-033 宗教实现。

## §1 用户裁决

1. **D3 直接删除**：退役旧文化倍率合成专用的 ProductionLaborLookup、UnitProductionFacts、HouseholdLaborShare、EconomyProductionLaborLookup 及相关装配/引用。新经验执行若需劳动事实，按新生产模型提供，不因相似用途而保留旧文化专用通路。
2. **D4 归生产方式模块**：production 统一维护方案，并提供单个家户采用指定方案时的预计/实际产出与生产导致的家户状态变化能力；职责超过保存、返回模板。
3. **经济模块调用 production**，负责家户间经济关系。不能继续由 economy 维护另一份投入产出数值，或通过 templateFor 现场拼出竞争定义。
4. 这是领域职责调整，不是只搬几个文件；按需进入生产/创建产业实例的既有能力须在新分工下承接，不能通过删除候选数据把能力一并删掉。

## §2 工程边界整理（控制方评估，非用户逐字原话）

以下用于落实 §1，并遵守既有数据所有权与命令提交纪律；不冒称用户逐项裁定了接口、字段或全部运行状态归属。

| 责任 | 建议归属 |
|---|---|
| 方案投入产出、适用条件、经验解锁、可配置熟练度公式 | production |
| 给定家户条件与投入，计算可生产规模、预计消耗及产出 | production |
| 按实际投入推进生产，计算实际消耗、产出、经验及进度变化 | production |
| 依据需求、价格、利润及资源约束选择方案、统一分配劳动与资源 | economy |
| 买卖、雇佣、租赁、工资、地租、债务及产出分配 | economy |
| 真实库存、货币、人口的权威落账 | 各数据拥有者；production 返回所需事实/变化请求，不直接改外模块对象 |

建议的调用关系：

```text
economy 提供可用资源及经济条件
  → production 试算方案
  → economy 按需求/利润选择并分配投入
  → production 按最终投入计算实际生产及状态变化
  → economy 处理交易与收益分配
  → 各模块 ChangeSet 汇入同一 Revision 提交
```

“随后处理经济关系”不应实施成所有经济约束都等生产结束才出现：租用的资产、雇入的劳动、购入的原料等，需要在实际生产前明确可用性。app/Core 只装配与调度，不承接新生产公式。

## §3 后续设计必须覆盖的约束

- 预计和实际共用方案定义及生产公式；预测无副作用，不扣库存、不增经验、不推进时间。实际结果依据最终投入，不能保证等于先前假设下的预测值。
- 统一预算：多个方案、多个生产单元不得重复占用同一份劳动、土地、工具或原料。只按实际生产贡献累计经验，衰减按统一 tick 规则执行。
- 生产总产出与家户最终所得分开。雇佣、租赁、分成仍按经济关系处理；协作生产只算一次总产出，并保留各家户实际劳动贡献供经验计算使用。
- 经验、解锁与生产进度等生产状态的具体归属/拆分须在约束设计书明确；库存和人口仍由各自拥有者落账，不能把“家户状态更新”解释成 production 绕过领域写入口。
- 选用已有方案通常创建引用该方案稳定 ID 的生产实例，不为每次选用复制一份方案定义。按需新建定义与按需新建实例须区分。
- D4 的候选持久组件、codec、ChangeSet、GUI、入口/转业路径都需完整收口；不留第二份数值权威、不做旧档兼容双写。
- 当前只读定义契约需按新职责设计计算契约；不让 production 反向依赖 economy 实现，也不让 economy 直接 import production 内部实现。

## §4 源码现场与范围说明

以下为 2026-10-17 在本工作树回代码核实的旧现场，不代表新裁决已实现：

- `simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:28`：旧说明仍要求运行实例、劳动、账户、债务、进度不搬入；新职责设计须更新对应口径，不能把旧注释当作否决裁决的依据。
- `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionCandidate.java:57`：候选自带定义数值。
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyEntrySettlement.java:1014`：templateFor 合成定义；调用位于 :871。
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java:34`：现有按关系分配产出的纯计算，说明“怎么生产”与“产出怎么分”需要区分；不要求整类按名字搬入 production。

工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。本次仅记录裁决与评估边界，未修改生产/测试代码、未启动 dsh、未运行构建/测试、未提交或推送。完整接口、载荷、状态拆分、时序、文件 owner 与验收由后续约束设计书冻结。

## §5 用户原话附录（逐字，2026-10-17）

用户在本会话引用自己的 D3/D4 答复并询问决定是否合理：

> D3直接删没毛病
> D4我建议归生产方式模块，生产方式模块干脆提供单个家户选用某个生产方式，预计/实际的产出和家户状态更新，而不是只提供一个模板，经济模块调用生产方式模块之后，负责计算随后的家户间经济关系，

用户随后要求：

> 记录一下这个裁决，告诉我其文档地址，我还给隔壁
