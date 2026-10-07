# 税制 / 财政闭环 + 饥饿-逃亡反馈（Z7 约束设计书）

> 状态：用户 2026-10-23 已选下一批 = 税制/财政闭环，并逐条裁定 D1/上缴/逃亡/饥饿归属。本文是 Z7a~Z7e 的
> 唯一契约来源；与本文或用户原话冲突 ⇒ 上报 BLOCKED（AGENTS §一.8.1）。
> 前置材料：`docs/superpowers/reports/2026-10-23-gov-batch-run6-vs-run5.md`（D1~D4 证据）、
> `docs/superpowers/reports/2026-10-23-fiscal-loop-investigation.md`（根因与选项）、
> `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md`（Z0，全部既有架构）。

## 0. 用户原话（逐字）

> 中央辖区本身也是一个省，本来搞什么独立的直辖区就是错误的，直辖市、直辖区就是独立的省份

> 省政府上交中央可以搞定期，但是省政府一定要有能力改，也就是抗税

> 部分付，不给政府人员吃饭，那不就应该减行政效率，然后设置相关家户跑路吗？政府家户里的成员允许跑路吗

> 1、我想了一下，原本经济家户满足不了要求的代偿方法是借债/借东西，但是在GOV家户借东西是得走批准的，所以我准备设定如果GOV不满足GOV家户的每日运行需求，直接快速增加这个家户跑路到其他任意当前格经济家户的速度；如果满足，则缓慢减少

> 首先是，政府家户的财产是公家的，转移的家户内人员不带走东西；其次是，转移顺序肯定优先往理论上加入后赚钱多的走啊；其他按照你说的，俸禄/工资是啥？理论上来说在政府家户内，俸禄不就是口粮吗？走的肯定是成员啊，饥饿系数不是家户维护的吗？肯定要加啊；以及这个饥饿系数到底是怎么做的，给我介绍一下

> 我觉得Social层面饥饿系数应当直接决定单tick家户能提供的劳动力……这样一来，不管是经济还是GOV等模块，维护家户的需求都不需要额外处理饥饿

> AAA，肯定按户啊，按照你说的去向做

> AB同时应用吧

## 1. 范围与责任区

| 区 | 目标 | 依赖 |
|---|---|---|
| **Z7a** | D1：首都座位拆成**与省平级的 `capital-province`**（中央直辖、税率中央自定），省辖区只留其余 18 格 | — |
| **Z7b** | 国库户退出商品市场（买卖都不生成卖单）+ 预算桥逐腿账本/`PARTIAL` + 俸禄 sink→官吏户 transfer | Z7a 可并行，但 app 文件串行 |
| **Z7c** | remittance：省按周期把实收的 0..1000‰ 上缴 superior（省可改 0 = 抗税；中央不能强制） | Z7b（同一账户会话） |
| **Z7d** | Social 饥饿系数（按户 satiety → 劳动力）+ GOV 有效供给 min(承诺, 实际劳动) + 逃亡（成员级、不带走财产、按赚钱机会排序） | Z7b/Z7c |
| **Z7e** | run7 360 tick 验证 + 统一测试（含 D1/D2/D4/闭环/饥饿/逃亡断言） | 全部 |

顺序：Z7a → Z7b → Z7c → Z7d → Z7e（一次一个写代码代理；`PopulationEconomyTimeParticipant`/`GovBudgetExecutionBridge`
被多区触碰，必须串行）。

## 2. D1：首都直辖省（用户口径）

- `SmallWorld` 新增 region **`capital-province`**（与 `small-world` 平级，含中央座位格 `(0,0)`；是否含首都城邻格由 Z7a 按城市落点定并台账）；
- `gov-province` 的 `unit.SetJurisdiction`/税只覆盖 `small-world`（其余 18 格）；
- **中央 GOV 直辖 `capital-province`**：bootstrap 给 `gov-central` 加 jurisdiction（`capital-province`，初始 `rate=0`，
  要不要征税由中央自定/后续决策人工具改）；
- 效果：省不再抽中央国库；首都民户归中央自己的省级辖区（不再"没人管"）；无"特殊直辖区"第三形态；
- 旧档/旧世界不自动迁移（只对空库首启生效），旧 run 仍按旧 region。

## 3. 市场排除 + 预算逐腿账本 + 俸禄转移

1. **国库户退出商品市场**（缺陷修复，无需裁定）：`MarketSettlement.ordersFor` 不再为政府/单位家户（`hh-gov-*`、
   `hh-unit:*` 等单位户）生成买单/卖单；0 人口持仓户不得被当卖家清仓。
2. **逐腿账本 + `PARTIAL`**：执行器同时收"原始请求"和"逐腿授权"；状态扩展 `EXECUTED/SKIPPED/PARTIAL`；
   读口逐腿 `requested/authorized/paid/shortfall`；缺额只告警，不自动补。
3. **俸禄 = 发给官吏户的口粮/薪水（用户口径"俸禄不就是口粮吗"）**：`ADMIN_STIPEND` 从 sink 改为**转给官吏户**
   （按编制/承诺在官吏户间分），sink 只留非家户的衙门开销（若有）；与 `ADMIN_SALARY` 的关系由 Z7b 冻结
   （推荐：俸禄 = 实物口粮/布，工资 = 银/额外粮，均 transfer；不得同一份需求双付）。

## 4. remittance（定期、省可控、可抗税）

- 落点：扩 `GovBudgetPolicy.remittancePerMilleToSuperior`（0..1000，默认 0；旧档缺字段=0；复用现有
  `gov.SetBudgetPolicy` + GM/决策人工具 + 审批链，**形状不变**）。
- 执行：周期末（关账日）在**税后、预算前**，同一 `AccountSession`：`remit = 省当期实收 grain/silver × rate / 1000`
  从省国库转 superior 国库；不足 ⇒ 部分支付 + 具名缺口 + 告警（不自动注资/调率）。
- 省决策人可随时把 rate 改成 0（**抗税**）；中央不能自动强制；中央缺口只告警。
- 守恒：remit 是同一批资源的转移，不得凭空造物；读口记应缴/实缴/缺口。

## 5. Social 饥饿系数（按户，直接决定单 tick 劳动力）

- **状态**：Social 每户 `satietyPerMille`（0..1000，初始 1000），持久、按户（用户："肯定按户"）。
- **折算**：`SocialData.householdLaborMilli = 基础劳动 × satietyPerMille / 1000`；economy/GOV/其他模块只消费
  这个已折算的劳动，**任何模块不得再自算饥饿**。
- **更新（需要 economy→Social 回写）**：经济侧每日消费的逐户 `FlowRow.unmetNeed`（粮那一维）由 app 在
  同一/次日 revision 回写 Social：
  - 有粮 unmet ⇒ **快降** `−150‰/日 × 断顿比例`（断顿比例 = unmet/当日粮需求，封顶 1000‰）；
  - 无粮 unmet ⇒ **慢升** `+20‰/日`；
  - 布不够不降劳动（只告警）。
- 回写通道照既有 economy→Social 桥先例（outbox/typed 记录 + app 组合同一批），Z7d 冻结形状。

## 6. GOV 有效供给（冲突 1 = A）

- `GOV_SERVICE` 承诺行保留为**职位/诉求**（C7 不静默缩/不删）；
- 供给桥的**实际供给 = min(承诺劳动, 该户当月实际劳动)**（实际劳动已含饥饿折算）；
- 欠俸/断顿导致实际供给 < 职位 ⇒ 记录/告警"在编但供给不足"，效率随之下降；
- 只在**逃亡/解职**等显式动作里减承诺（具名、可审计）。

## 7. 逃亡（成员级、不带走财产、按赚钱机会排序）

- **状态**：Social 每官吏户 `fleeRatePerMille`（0..1000）+ 逃亡余数（跨日结转）。
- **驱动**：
  - GOV 未满足该户**每日运行需求**（俸禄/工资实付 < 应发）⇒ **快升**（默认 `+150‰/日`）；
  - 饥饿（satiety < 1000）⇒ 追加 `+150‰/日 × (1000−satiety)/1000`；
  - 满足（付足且吃饱）⇒ **慢降**（默认 `−20‰/日`），下限 0。
- **执行**（每日、确定性）：`逃亡量 = members × fleeRate/1000 + 余数`，满 1 人走 1 人；
  - **只转成员**（Social `TRANSFER_MEMBERS` 同款），政府户国库/库存/资产**原样保留**（公家财产）；
  - **去向**：当前格（GOV 有效位置格）的经济家户，按
    `可吸收劳动余量（产能−现有承诺）× 单位劳动预期利润`（`ProductionProcessBook` + `ExpectedProfitBook`）排序，
    无生产机会退化为 `人均（粮+银+资产）`，同分按家户 id；确定性、无随机；
  - **协同**：同一条 revision 内更新 Social 成员/位置、economy 两行人口/劳动/需求、按剩余劳动减 `GOV_SERVICE`
    承诺、户空 ⇒ 摘岗位 + 摘 `Unit.households` + 释放承诺；
  - **告警**：rate 跨档或真走人 ⇒ GM + 该 GOV 决策人具名 INFO；不自动补俸、不自动招人。
- 军队户欠饷的同类逃亡留后续（本批只做 GOV 官吏户）。

## 8. run7 验收要点

1. **D1**：中央座位/首都民户不被省征税；`capital-province` 与 `small-world` 平级；中央对 district 的税率可配（初始 0）。
2. **D2**：国库户市场日不被清仓（粮/银账户不被卖单抽空）。
3. **D4**：逐腿 `requested/authorized/paid/shortfall` + `PARTIAL` 可读；缺额只告警。
4. **闭环**：省按 rate 上缴（可改 0）；中央国库在 360 tick 内可持续发俸/工资（不靠一次性创世注资耗尽）。
5. **饥饿**：断顿 ⇒ 下一 tick 家户劳动下降（Social 折算）；供给/效率如实下降；补给后慢恢复。
6. **逃亡**：欠俸/断顿 ⇒ fleeRate 快升；真走人时**不带财产**、岗位/承诺/单位户一致更新、告警发出。
7. **回归**：Z0 的 gov/economy/unit 既有验收不破；全仓 `clean verify`（Z7e）。

## 9. 默认参数（用户未逐项给数，先按此冻结，跑完 run7 可校准）

| 参数 | 默认 |
|---|---|
| satiety 快降 / 慢升 | `−150‰/日 × 断顿比例` / `+20‰/日` |
| 逃亡快升（欠俸）/ 慢降 | `+150‰/日` / `−20‰/日` |
| 饥饿对逃亡追加 | `+150‰/日 × (1000−satiety)/1000` |
| 去向排序 | 产能余量×预期利润 → 人均财富 → id |
| remittance | 默认 0‰，周期末，省决策人可改（含 0） |
| 中央 district 税率 | 初始 0‰（中央自定/后续工具） |

## 10. Z7e-3：`gov.SetBudgetPolicy` 双模（PATCH 缺省 + REPLACE 开关）

**用户裁定（原文）**："AB同时应用吧"（2026-10-23）——A = payload 级 patch 语义（缺省字段保留现值），B = 显式
`mode:"PATCH"|"REPLACE"` 开关。**动因**：run7 首跑污染（`docs/superpowers/reports/2026-10-23-fiscal-loop-run7-vs-run6.md`
§4）——省决策人只传 `remittancePerMilleToSuperior` 改 0 抗税，整表替换把 `orderedCategories` + `officialSalaryRule`
清空 ⇒ 次日 `ADMIN_PLAN_MISSING` ⇒ 停俸 ⇒ 官吏逃亡。

**冻结语义（命令载荷 `gov.SetBudgetPolicy`）**：

| 载荷 | PATCH（`mode` 缺省） | REPLACE（`mode:"REPLACE"`，旧语义） |
|---|---|---|
| `orderedCategories` 缺失/null | 保留现值（键不存在 = 空表） | 空表（不自动付） |
| `orderedCategories` 给出（含 `[]`） | 整表替换（`[]` = 显式清空） | 同左 |
| `officialSalaryRule` 缺失/null | 保留现值（键不存在 = 0/0） | 0/0（不发薪） |
| `officialSalaryRule` 给出 | 对象内缺省字段逐项保留现值 | 对象内缺省字段 = 0 |
| `remittancePerMilleToSuperior` 缺失/null | 保留现值（键不存在 = 0） | 0（不上缴） |
| `remittancePerMilleToSuperior` 给出 | 覆盖（0..1000‰ 构造期判） | 同左 |

- `mode` **只影响本次解析、不落状态**（`GovBudgetPolicy` 值对象不加字段，幂等判定仍是逐值 `equals`）；词表大小写不敏感、词表外具名拒。
- 首次写入（键不存在）无"现值"可保留 ⇒ PATCH 等价中性默认（空表 + 0/0 + 0）。
- **三面同源**：命令载荷 `mode` 字段（`GovPayloads.editMode` / `GovBudgetPolicyEditMode`）、工具 `simos.gov.setBudgetPolicy`
  的顶层 `mode` 参数（给出即覆盖 payloadJson 的 mode）、catalog `payloadHints` 同一条词表；preview 视图带 `editMode`。
- 回归判据：`GovCommandHandlersTest`（PATCH 保留/显式清空/逐层合并/REPLACE 旧语义/首次写入/词表）、`GovToolsZ6Test`
  （工具面 + 目录 + preview 的 `editMode` + 只传 remittance 不清预算 + 顶层 mode 覆盖）。
