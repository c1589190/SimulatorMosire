# D-032 农村经济目标运行设计（标准文档试写，2026-10-19）

> **状态：已按用户 2026-10-19 确认落地。** 本文是 D-032 的标准文档试写；写作规则已写入根 `AGENTS.md` §五.12、`docs/agents/15-十四-目标运行设计与子设计嵌入.md` 和 `.agents/skills/target-runtime-design/SKILL.md`。
> **适用范围：** D-032 农村土地/劳动/债务/工资/市场重设计的 P0.5–P6。
> **权威顺序：** 用户原话 > 用户设计原稿 D-032 U1–U23 及 2026-10-18 后续逐字修订 > 本文目标运行序列 > 各子设计 > 当前代码事实。
> **本文不是什么：** 不是对当前实现的验收，不是新的玩法裁定，不是把所有历史文档重抄一遍。当前实现差异见 §7。

## 0. 阅读方式

本文先定义“目标世界里，代码应当按什么顺序运行”，再把已有裁定和子设计嵌入到具体阶段。

- 目标运行序列的每个阶段有稳定阶段号（M0、M1、M1b……M9）；
- 后续派单/约束设计必须引用阶段号，说明“嵌入哪里、上下游是谁、改变了什么”；
- 裁定原话只作为来源与影响映射，不再脱离运行链单独施工；
- 当前实现与目标的差异单列 §7，不拿现状反推目标；
- 子设计不重抄父序列，只写“本段增量 + 对父序列的影响”。

## 1. 目标运行序列（唯一主链）

### 1.1 每日公共前缀

| 阶段 | 触发 | 做什么 | 读 | 写 | 权威/失败语义 |
|---|---|---|---|---|---|
| **M0** | 每 tick | Social 先用上一 tick 的 `PendingFamineFacts` 结算出生、死亡、年龄、劳动能力、自然需求 | Social 权威人口/成员/年龄/性别/劳动能力；上一 tick 单槽事实 | Social 切片；经济侧只读投影 | Social 失败 ⇒ 整次 advance 失败，不落 revision；经济不得自行判死/缩劳动 |
| **M1** | 每 tick | 经济载入 M0 后活着的家户，刷新 composition、labor budget、naturalNeeds 投影 | Social 投影；Economy 家户行 | economy 会话投影/必要人口 delta | 缺行/负人口/投影不一致 ⇒ 契约故障 |
| **M1b** | 每 tick | 进入、迁移、组织变更（可能新建 unit/roster/relations） | 需求、定义、资产、劳动、家户 | economy 工作副本；production 实例/名册经契约口 | 业务拒绝具名；契约故障整批失败；不得留下第二套定义/实例权威 |

> M1b 是“家户/组织事实的日级准备”，不是第二个市场阶段。M2 的债务清算仍然在市场日的所有市场动作之前。

### 1.2 市场日分支（`day % 5 == 0` 且市场表非空）

```text
M0 Social
→ M1 经济载入
→ M1b 进入/迁移/组织
→ M2 债务清算
→ M3 第一遍预期生产编排
→ M4 统一市场
→ M5 当日基础消费
→ M6 第二遍实际生产编排
→ M7 生产执行/入库
→ M8 工资结算
→ M8b 市场日工资锚观测/调价
→ M9 保存 PendingFamineFacts
```

| 阶段 | 目标内容 | 读 | 写 | 失败/边界 |
|---|---|---|---|---|
| **M2** | 市场轮最前面清算全部债务；同一债务人的债权人按纯库存财富降序 → 债权人 id → 债务 id；货币 → 库存 → 土地；土地整块直接抵债、不找零；债权人全灭则债务消灭、土地留债务人 | 债务合同、账户、库存、土地、所属市场区当前地价、纯库存财富快照 | 债务本金/状态；货币/库存；土地 owner/operator；`landAcquiredThisRound` | 无资产/不足 ⇒ 余债继续；无 Pledge/抵押/担保；不得强制找买家；不得先卖地再抵债 |
| **M3** | 第一遍预期生产编排：家户按纯库存财富降序 → id；在劳动/土地/资产/经验/价格/工资锚约束下按单位劳动净收益贪心选位置；锁雇佣；生成生产投入需求、5 tick 基础消费需求、商品意向、**土地意向** | M2 后的实际库存/土地；Social 劳动预算；生产定义/经验；市场区地价/工资锚；现有 unit/roster | **仅瞬态** `ExpectedProductionPlan`、雇佣锁、`WageDemand`、`MarketIntent` | 只选正收益位置；零劳动 organizer/产品份额位置可入选且不占本户劳动预算；缺锚不得按 0；新土地本轮不可用 |
| **M4** | 唯一的统一市场：商品订单与土地订单进**同一订单簿/同一撮合器**、同轮同结算；基础消费/生产投入 priority 0，其他商品 priority 1，土地投资 priority 2；同一买方共用可花货币/冻结预算 | M3 的商品/土地意向；实际库存/货币；土地块/市场区价；纯库存财富序 | 商品库存/货币；土地所有权/经营者；普通货币债（有成交但现金不足时）；市场统计 | 无供给只是未满足需求，不记债；未定价 ≠ 0；土地整块、不进普通库存；土地成交后本轮不可用 |
| **M5** | 每日基础消费，先扣当日口粮/自然需求；市场日先市场后消费 | 实际库存；Social 注入需求 | 家户库存；`unmetToday`/缺口读数 | 吃不饱合法；不得凭空补货/借粮 |
| **M6** | 第二遍实际生产编排：雇佣锁原样只读；按市场/消费后的实际库存、土地、劳动重排非雇佣小生产；失败位置劳动转无业；本轮新土地排除；**不产生任何市场买卖** | 实际库存/货币/土地；劳动预算；M3 雇佣锁；生产定义/工资锚 | **仅瞬态** `ActualProductionPlan`；最终 roster/劳动承诺/实例编排（同 revision） | 不得改雇主/雇工/份额/工资；不得换新位置；不得下单；实际不可行的锁不得 materialize |
| **M7** | 生产执行：实际投入现扣 → production 执行 → 产出/入库同一环节；经验、进度、效率余数、解锁由 production 候选态推进 | 实际计划；production 候选态；账户会话 | production 实例/经验/进度；账户库存；实际劳动回执 | 业务拒绝逐项具名；契约/公式故障整批失败、零半提交 |
| **M8** | 工资结算：只付货币；同一雇主按稳定义务序付到现金用完；未付余额形成普通 `WAGE_ARREARS` 货币债（0‰）；不拿库存抵工资 | M7 实际劳动回执；工资锚；雇主账户快照；稳定义务序 | 雇主/雇工货币；工资债；工资读数 | 同日 M8 在 M2 后，结构上不会被同 tick M2 再还；不得库存抵工资 |
| **M8b** | 只在市场日、M8 后：按本市场轮 M3 的 target 与 M8 实际 matched 聚合观测，调整下一 tick 工资锚 | M3 `WageDemand`；M8 实际劳动；锚表；调价 policy | `productionWageAnchors`/streak/观测字段 | 本 tick due 不追改；非市场日不动锚；缺锚/币种不一致/溢出 fail-closed |
| **M9** | 生成并保存本 tick 的 `PendingFamineFacts` | 本 tick 缺口、实际人口 | app 侧单槽内存 | 不落盘/不进 Codec/ChangeSet；失败不覆盖上一份有效事实；成功后替换 |

### 1.3 非市场 tick

非市场 tick **不开统一市场、不处理债务本金、不改价格/工资锚**，但**第二次生产方式选择（M6）、家户生产、入库（M7）每 tick 都要做**：

```text
M0 Social
→ M1 经济载入
→ M1b 进入/迁移/组织
→ M5 每日基础消费
→ M6 第二次实际生产编排（锁定雇佣；非雇佣小生产按实际库存/劳动重排）
→ M7 生产执行/入库
→ M8 工资结算
→ M9 保存 PendingFamineFacts
```

> **M3 的边界（待用户审阅确认）：** 本文目标按“M3 只在市场日做第一遍预期编排/雇佣锁/市场意向；非市场日 M6 使用上一市场日锁定的 roster/劳动承诺”写。当前代码 M3 每个 tick 都在跑；如果用户希望雇佣关系可每天重选，则应把 M3 明确列为每日阶段；如果雇佣关系必须跨到下一市场轮才重选，则保留市场日 M3，并补持久锁/roster 读取。两种口径不能靠代码现状默认。

### 1.4 两遍生产决策的共同公式

M3 与 M6 共用同一套经济目标，只换信息集：

```text
positionNet = expectedIncome − directInputCost − wageCost
positionYieldPerLabor = positionNet / laborMilli
```

- 仅 `positionNet > 0` 的位置进入贪心；
- 两遍不得各写一套利润公式；
- 第二轮只能换实际库存/土地/劳动信息集，必须遵守第一轮锁定的雇佣关系；
- 第二轮不得用“更优”改锁，也不得新增市场订单。

## 2. 数据所有权矩阵

| 事实 | 唯一权威 | 在运行链中的读写 | 禁止 |
|---|---|---|---|
| 人口/出生/死亡/年龄/劳动能力/基础需求 | Social | M0 写；M1/M3/M6/M8 只读投影 | economy 自行判死/缩劳动；第二本人口账 |
| 生产方式定义/配方/角色/经验规则/实例/进度/预计实际物理产出 | production | M3 只读 probe；M7 执行写本域候选态 | economy 复制配方/效率公式；production 直接结算市场/债务/工资 |
| 生产计划/雇佣锁/roster/劳动匹配 | economy | M3 写计划/锁；M6 读锁、写实际计划/最终 roster；同 revision 提交 | production 维护“选中的计划”作为第二权威 |
| 商品/货币账户 | actor | M2/M4/M5/M7/M8 经经济会话工作副本写 | 第二本库存/货币账；绕过 ChangeSet/Revision |
| 土地所有权/经营者/LAND 份额 | economy | M2/M4 改 owner/operator；M3/M6/M7 只读使用 | 把 LAND 塞进普通 `HouseholdGoods`；独立租约/Pledge |
| 债务合同 | economy | M2 本金清算；M8 写工资债；M2 同池处理工资债 | 抵押/担保/特定资产优先受偿；商品本金债 |
| 工资锚 | economy | M3/M6/M8 只读；M8b 市场日调价 | 每个雇主自定第二套报价；缺锚按 0 |
| 市场订单/统一市场/地价 | economy | M3 意向；M4 统一撮合/结算 | 商品与土地两套独立市场阶段；hex 级独立地价 |
| `PendingFamineFacts` | app 单槽内存 | M9 写；下一 tick M0 读 | 落盘/ChangeSet/Codec/历史表 |
| 阶级/地主/富农/贫农标签 | economy 投影 | M6/M9 后读数 | 进入 M3/M6 的位置/角色/候选过滤 |

## 3. 关键跨模块接口（语义级）

```text
Social
  人口/出生死亡/年龄/劳动能力/自然需求
    ↓ app 只读投影 + PendingFamineFacts
economy
  日序编排 / 家户决策 / 统一市场 / 劳动匹配 / 工资 / 债务 / 土地
    ↓ production.contract：probeRoster / executeDay / 定义 / 经验
production
  生产方式/配方/角色/经验/实例物理状态/预计实际产出
    ↓ 同 revision 的领域 ChangeSet
actor / Social / 其他持账方
```

硬边界：

- economy 只读 production contract，不写 production 内部状态；
- production 不读 `EconomyData`，不决定家户选什么、工资多少、债务怎么还；
- app 只编排、桥接、账户会话、提案汇合，不实现领域公式；
- 同一 tick 的账户、生产、债务、土地写回同 revision；任何失败不得半提交。

## 4. 已有开发任务的设计嵌入表

> 这张表回答“每个已有任务嵌入目标运行序列的哪里、改变了什么、验收锚点是什么”。子设计文档只写本段增量，不再重抄整条序列。

| 任务 | 父序列锚点 | 嵌入/影响 | 数据所有权 | 主要验收锚点 | 状态 |
|---|---|---|---|---|---|
| **P0.5 大文件拆分** | 全阶段结构 | 不改运行顺序/公式；只拆职责类，所有 economy main Java ≤1000 行 | 不变 | 编译 + 既有回归 + 行数守卫 | 已完成（P3e 后需保持） |
| **P1 Social 先 + 单槽事实** | M0/M1/M9 | Social 先；上一 tick 事实单槽；失败不覆盖；经济只读新活家户 | Social/app | 分段等价、首 tick 空事实、失败不覆盖 | 已完成 |
| **P2 统一市场 + 债务最先 + 删 Pledge** | M2/M4 | M2 市场日最先；统一市场；土地是订单变体；无 Pledge；土地整块抵债；价格区级 `OptionalLong` | economy | 5 日开市、债务最先、财富序、货币→货→地、0/未定价、无 Pledge | 已完成后又由 P3d 收口撮合入口 |
| **P3a production roster probe** | M3/M7 | production 返回逐参与户预计产出/投入/效率；economy 不复制公式 | production/economy | A2/A3 probe 可行；同条件 expected/actual 一致 | 已完成 |
| **P3.0 MarketIntent / market round 装配** | M3/M4 | M3 意向进入同一 MarketRound；商品/土地同轮；M4 唯一入口 | economy | 商品订单不重复；意向替换同 actor 自动单 | 已完成（撮合入口待 P3d 收口） |
| **P3b 纯库存财富 + 新土地过滤** | M2/M3/M4/M6/M7 | 排序用钱+货+地；新土地本轮排除 | economy | 纯财富不扣债；新土地 M3/M6/M7 排除 | 已完成 |
| **P3c1 两遍计划类型** | M3/M6 | `ExpectedProductionPlan`/`ActualProductionPlan`/`EmploymentLock`/`PositionPlan`/`WageDemand` | economy 瞬态 | 两遍同公式；锁不可改；第二轮不下单 | 已完成 |
| **P3c2 生产阶段机械拆分** | M3–M7 | 拆分 prepare/draw/consume/execute；保持旧行为 | economy | 编译 + 既有回归 | 已完成 |
| **P3c3 两遍日序 + 雇佣锁** | M3/M4/M5/M6/M7 | 新链 M3→市场→M5→M6→M7；legacy fallback 仅旧世界 | economy | A2/A3/A4；锁只读；失败转无业 | 已完成（零劳动/锁过滤由 P3e 修） |
| **P3d 土地意向 + 统一撮合** | M3/M4 | 从生产位置产生 LAND BUY；未使用土地 LAND SELL；商品/土地统一订单序列/统一 matcher 入口；整块、区级价、新土地标记 | economy 轮内瞬态 | 有生产缺口⇒有土地需求；未使用土地⇒供给；同轮同预算；一个 matcher 入口 | 代码完成、编译过；测试待迁移 |
| **P3e 日常计划纠错** | M3/M5/M6/M8 | 5 tick 基础消费；零劳动位置入选；WageDemand 用 roster 实际雇工；roster-only fallback；不可行锁不 materialize；全链日志 | economy | 5 tick、A3/A4、锁过滤、D032_TICK_CHAIN | 已完成（编译过；测试待迁移） |
| **P4a 工资只现金 + 普通债** | M8/M2 | 只付货币；未付成 `WAGE_ARREARS` 0‰ 普通货币债；删工资抵物/库存 lookup | economy | 部分/零现金、库存零变化、债 source/terms、下一轮 M2 | 已完成 |
| **P4b1 工资锚状态/命令/seed** | M3/M8b | `productionWageAnchors` 状态/命令/seed；旧 posting/default 逐渐退役 | economy | 0 合法；缺锚≠0；跨雇主共享 | 已完成 |
| **P4c1 WageDemand + 锚消费** | M3/M6/M8 | M3 生成 WageDemand；M3/M6/M8 读同一 tick 锚快照 | economy | target 公式、同 tick 不追改、缺锚拒绝 | 已完成 |
| **P4c2 U20 观测/调价** | M8b | 市场日 M8 后聚合 target/matched；调下一 tick 锚；非市场日不变 | economy | 缺工加价、招满延迟降价、本 tick due 不变 | 已完成 |
| **P4c3 旧工资表/命令/fallback 退役** | 全状态/M3/M8 | 只留 `productionWageAnchors` + carry；删旧两表/两命令/seed/codec/fallback；组件 36→34 | economy | 旧标识符 0；锚唯一报价；编译绿 | 代码完成；测试待迁移 |
| **P4d production barter schema 清理** | M3/M7/M8 定义面 | 删 `RolePayment.MoneyWage.barterDiscount`、`BarterDiscountPolicy`、`BarterReferencePrice`、payload/tool schema | production | 旧 payload fail-closed；无 barter 字段 | 代码完成、编译过；测试待迁移 |
| **P5 grain_farm + Seeder** | M1b/M3/M7/初始状态 | 单一 `grain_farm`；operator + hired_laborer；删 tenancy/rent/阶级 mode 映射；初始土地/锚/demands/roster；跨 hex 本地雇农 | economy/app | 同 mode 自耕/雇工；无 rent；无地户买地；跨 hex；土地守恒 | P5a/P5b1/P5b2 完成；day1-9 真链跑通，day10 P2 抵地组织引用缺口另修 |
| **P6 独立测试/变异/小世界** | 全阶段 | T1 迁移测试；文件大小守卫；变异自证；`clean verify`；小世界全链日志模拟 | 测试/控制方 | 每条不变量正负用例；真实链路日志可诊断 | 未开始 |
| **T1 测试迁移** | P3e/P3d/P4d/P5 后 | 迁移旧类型/旧 seed/组件计数/命令清单；不动生产代码 | 测试 | testCompile/全量测试绿 | 待代码收口 |

## 5. 裁定 → 运行阶段映射

| 裁定/口径 | 落到阶段 | 运行后果 |
|---|---|---|
| Social 先；上一 tick 单槽事实 | M0/M1/M9 | Social 在 economy 前；经济只读活家户；事实不落盘 |
| 市场轮最前面先清算全部债务 | M2 | M2 先于 M3/M4；不得先卖地再抵债 |
| 商品+土地同一订单簿/同一撮合器 | M3/M4 | 土地需求来自生产位置；商品/土地同一轮、同一 matcher 入口、共享预算/冻结 |
| 两遍生产：预期→市场→实际 | M3/M4/M6 | 第一遍选位置/锁雇佣/出意向；第二遍按实际库存重排非雇佣小生产，不下单、不改锁 |
| 单家户净收益最大化；两轮同公式 | M3/M6 | 只选正收益；同一 `positionNet/positionYieldPerLabor`；零劳动位置不占预算 |
| 本轮新买/抵债土地本轮不用 | M2/M4/M6/M7 | `landAcquiredThisRound` 贯穿 M3/M6/M7；下一市场轮才可用 |
| 土地是 LAND 生产能力资产；价格按市场区；hex 决定面积/质量/供给 | M2/M3/M4 | 不进普通库存；区级 `OptionalLong` landPrice；0 合法、empty 未定价；整块转移 |
| 纯库存财富 = 钱+货+地，不扣债 | M2/M3/M4 | 债务债权人序、生产位置序、市场买方序共用同一快照口径 |
| 债务本金只货币；无 Pledge/抵押/担保；还债钱→货→地；债权人财富序 | M2 | 普通债务；无强制出售；债权人全灭债务消灭；工资债同池 |
| 工资第一次只现金；未付成普通 0‰ 货币债；还债阶段可用库存/土地 | M8/M2 | 工资不抵物；M8 后债；下一市场日 M2 同池 |
| 工资锚 = 市场区×生产方式×位置；缺工加价/招满慢降；只在市场日 M8 后调 | M3/M6/M8/M8b | 锚唯一报价；本 tick due 不追改；非市场日不动 |
| 阶级标签只做投影 | M3/M6/M9 | 生产选择不得读 `stratum()`/`SocialClassId`；P5 删除旧阶级 mode 映射 |
| 生产方式/配方/经验/预计实际/状态归 production；经济关系/市场/债务/工资归 economy | 全阶段 | production 不读 EconomyData；economy 不复制效率公式 |
| 文件 ≤1000 行 | 全阶段 | P0.5 拆分；新增/修改文件必须保持 ≤1000；P6 机械守卫 |
| 生产方式进步/升级本轮暂不做 | P5 范围外 | 不阻塞农村 tick；只保留单一 grain_farm |

> 用户 2026-10-19 补充：“第二次生产方式选择、家户生产、入库本身每一 tick 都要做。”→ 已写入 §1.3；M6/M7 每 tick 执行，不因非市场日停。

## 6. 日志与验证锚点

### 6.1 日志锚点

每个阶段必须有稳定事件：

| 阶段 | 事件 |
|---|---|
| M0/M1 | Social/Economy 载入与投影事件（既有 + 缺口事实） |
| M2 | `DEBT_*`、纯财富快照、债权人序、土地抵债 |
| M3 | `D032_P3_EXPECTED_PLAN`、`D032_P3_EMPLOYMENT_LOCKED`、`D032_P3_POSITION_FAILED`、`D032_P3_LAND_INTENT` |
| M4 | `D032_P3_MARKET_INTENT_REPLACED`、市场轮/成交/未成交、土地成交/拒因 |
| M6 | `D032_P3_ACTUAL_REPLAN`、`D032_P3_LOCK_REJECTED`、`D032_P3_LAND_FILTERED` |
| M7 | `D032_P3_EXECUTED`、生产回执/投入/产出 |
| M8 | `WAGE_SETTLEMENT_*`、`WageSettlementReadout` |
| M8b | `WAGE_ANCHOR_OBSERVED`、`WAGE_ANCHOR_ADJUSTED` |
| 每 tick | `D032_TICK_CHAIN` INFO 汇总 |
| 逐户链路 | `D032_HOUSEHOLD_CHAIN` DEBUG/TRACE（M3→M6→M7→M8 同一户/unit） |

INFO 只放阶段汇总/业务拒绝；DEBUG 放逐户原因；TRACE 放逐笔金额/劳动/库存。契约/跨切片一致性故障 ERROR，不降级。

### 6.2 验收锚点

- 每条运行不变量必须有一个正例 + 一个负例/拒绝用例；
- 测试引用阶段号，不从代码反推预期；
- P6 变异自证重点：守恒、身份不丢、静默付 0、断粮/缺口、两轮锁、统一市场；
- 多 tick 长跑不进 `clean verify`，用独立小世界模拟。

### 6.3 小世界模拟（P6 之后）

用户要求：测试迁移完成后，直接搞一个有完整新数据结构的农村小世界跑模拟，用日志看真实链路、找问题。

建议最小世界：

```text
1 个市场区，2–3 个 hex，4–8 个农村家户；
1 个 grain_farm 定义；
有地/无地/多劳/少劳家户各至少 1 户；
初始土地有差异、初始工资锚有差异；
有 1 个本地雇农、1 个跨 hex 地主、1 个可卖地块；
带粮食/种子/货币/债务；
关闭旧 mode/tenancy/rent 默认。
```

运行方式：

- 不进程序化测试，不塞多 tick 到 `clean verify`；
- 独立 sim（如 `tools/multitick-sim.sh` 新 case 或等价 main）；
- 打开 `D032_TICK_CHAIN` INFO + 逐户 `D032_HOUSEHOLD_CHAIN` DEBUG；
- 检查：M2 债→M3 需求→M4 土地/商品订单→M5 消费→M6 重排→M7 产出→M8 工资→M9 缺口；
- 任何“该有订单没有、该有劳动没有、该有产出没有、工资静默为 0”都回到阶段锚点定位。

## 7. 当前实现差异（不冒充目标已实现）

| 项 | 目标 | 当前事实 |
|---|---|---|
| 土地需求从生产位置产生 | M3 从 `assetRequired[LAND]`/scale 计算，正缺口挂买盘 | P3d 已写入 `landQuantityMilliMu` 并生成 LAND BUY/SELL；测试待迁移 |
| 未使用土地供给 | M3 自动生成 LAND SELL | P3d 已生成；测试待迁移 |
| 同一订单簿/同一撮合器 | 商品/土地一个 matcher 入口 | P3d 已将 `MarketLandMatcher` 并入 `MarketMatcher`；runner 只调 `matchAll` 一次 |
| 5 tick 基础消费 | 市场买盘只到下一市场轮 | P3e 已修（`MARKET_RESTOCK_INTERVAL_DAYS=5`）；测试待迁移 |
| 零劳动 organizer/产品份额 | 正收益可入选且不占劳动预算 | P3e 已修 M3/M6；测试待迁移 |
| WageDemand target | 先 roster 实际计划雇工，0 才回退工程默认 | P3e 已修；测试待迁移 |
| legacy fallback | 只有 roster 真正为空才回退 | P3e 已修；测试待迁移 |
| 不可行雇佣锁 | 不得 materialize/进 ActualProductionPlan | P3e 已修；测试待迁移 |
| M6/M7 每 tick | 非市场日也实排/生产/入库 | 当前已每天跑；合同已按用户纠正更新 |
| M3 跨 tick 边界 | 待审阅：市场日 M3 + 持久锁，或每日 M3 | 当前 M3 每天跑；见 §1.3 待确认项 |
| 多地点工资付款人 | 跨 hex 雇主可按 location/roster 支付 | 当前 `wage-payer-location-unsupported` 会拒绝多地点 payer；P5/P6 前需修 |
| 买地后自动建 unit | 无地户买地后下一轮可组织 | entry 依赖非空 `demands`；P5 需播种 demands 或显式 candidate unit |
| 文件 ≤1000 | 所有 economy main Java ≤1000 | P3e 后最大 997；P3d/P5 新文件必须继续守住 |
| 测试 | 全量迁移/绿 | 当前 testCompile 在 5 个 economy 测试红；app 未到 |
| P4d barter | production 无 barter schema | P4d 已删除；production/app 编译绿；测试待迁移 |
| P5 grain_farm/Seeder | 单一模式、无 rent/tenancy、初始土地/锚 | P5a/P5b1/P5b2 完成（mode、LAND/unit/roster、19 anchors、landPrice=10、19 hired participants）；day10 P2 抵地组织引用缺口另修 |

## 8. 待用户审阅/裁定的边界

1. **M3 跨 tick 边界**：本文按“市场日 M3、非市场日 M6 用上一市场日持久 roster/锁”写；当前代码 M3 每日跑。若用户要“每天都能重选雇佣关系”，则 M3 应改为每日阶段；若“雇佣锁到下一市场轮才重选”，则保留市场日 M3 并补持久锁读取。请用户二选一。
2. **土地质量**：当前实现把质量折进有效面积/数量；如果要独立 `qualityPerMille` 字段/读数/估值因子，需要另改状态/Codec；否则按有效面积系数实现。
3. **小世界初始数值**：土地面积、初始工资锚、地价、种子/口粮参数按用户已授权工程标定；不需要再问。
4. 其余 P3d/P4d/P5/P6 的都是已定口径的实现，不是新玩法裁定。

## 9. 文档规则应用（已落地）

本文按以下顺序组织，供审阅：

```text
1. 目标运行序列（阶段/顺序/读写/所有权/失败）
2. 状态所有权矩阵
3. 跨模块接口与原子性
4. 已有开发任务嵌入表（父阶段 + 影响 + 验收）
5. 裁定 → 阶段影响映射
6. 日志/验证锚点
7. 当前实现差异
8. 待裁定边界
```

该写作规则已于 2026-10-19 写入根 `AGENTS.md` §五.12、`docs/agents/15-十四-目标运行设计与子设计嵌入.md` 和 `.agents/skills/target-runtime-design/SKILL.md`。本文件作为 D-032 的标准文档保留；后续子设计按此模板写，不再另起一套。

## 10. 关键目标原则（模块/类型级）

> 用户 2026-10-19 追加：修改某模块/某类型代码时，必须先遵循该处的规范/细化设计；该原则已写入根 `AGENTS.md` §五.12、`docs/agents/15-十四-目标运行设计与子设计嵌入.md` 和 `.agents/skills/target-runtime-design/SKILL.md`。责任区子 Agent 写中期设定也必须遵循。

| 改动面 | 先看/先写 | 硬规范 |
|---|---|---|
| economy tick/结算阶段 | 目标运行序列 M0–M9 | 阶段顺序/触发/读写/失败清楚；不得第二套市场/债务/工资/生产权威；日志 INFO/DEBUG/TRACE；economy main Java ≤1000 |
| 统一市场/商品/土地 | M3/M4 | 同一订单簿/同一撮合入口；共享预算/冻结；土地整块、区级 `OptionalLong` 地价、`empty ≠ 0`；新土地本轮不用 |
| `EconomyData`/ChangeSet/Codec | 全阶段 | 一个事实一个写口；组件/字段同步；完整往返；Alpha 不兼容旧档，不写迁移/兼容位 |
| 债务/工资/账户 | M2/M8/M9 | 本金只货币；无 Pledge/抵押/担保；债权人纯库存财富序；工资只现金；未付普通 0‰ 货币债；账户唯一写口 |
| production.contract | M3/M6/M7 | production 拥有定义/配方/经验/实例物理状态/预计实际产出；economy 只读 contract，不复制公式；预计/实际同源 |
| `OwnershipStake`/LAND/土地市场 | M2/M3/M4/M6/M7 | LAND 不进普通库存；稳定块 id；整块转移；区级地价；新土地 marker 贯穿 M3/M6/M7 |
| 工资锚/WageDemand | M3/M6/M8/M8b | 锚 = 市场区×生产方式×位置；缺锚≠0；本 tick due 不追改；非市场日不动锚 |
| `simos-app` 时间参与者 | M0/M1/M9 | 只编排/桥接；Social 先；`PendingFamineFacts` 单槽且失败不覆盖；不实现领域公式 |
| Seeder/worldgen | M1b/M3/M7 | 只面向新世界；初始土地/锚/需求/roster 显式；不得保留旧阶级 mode/tenancy/rent 默认 |
| 测试/验证 | 全阶段 | 测试 owner 按阶段验收锚点写；写代码 Agent 不写/不跑测试；新架构新测试，旧测试删/迁移 |

## 11. 用户原话附录（2026-10-19，逐字）

```text
还可以加一个关键目标原则，例如在修改什么什么模块/什么什么类型代码的时候，必须遵循怎么样的规范，或者某些细化设计；其他没啥问题，把如此原则加进AGENTS.md和其引导的技能集，记得在其中提醒办事的责任区子agent写中期设定也要遵循
```
