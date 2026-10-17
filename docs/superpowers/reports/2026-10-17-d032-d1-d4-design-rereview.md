# D-032 D1–D4 第二次独立设计复核（R-01..R-18 修正后，2026-10-17）

> **性质**：只读复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **复核基线**：`HEAD 713b2048`；复核开始时 `git status --porcelain=v1` 为空。
>
> **修正提交**：`713b2048 docs: 修正 D1-D4 独立复核 R-01..R-18`（已推送）。
>
> **复核范围**：D1/D2/D3/D4、R2 最小设计、U23 裁决、D3 裁决文件、重规划、状态文件；并回核 `EconomyData` 31 组件、`OwnershipStake`/`OwnershipStakeBook`、`AccountSession`、`MarketTable`、`DebtContract`/`DebtContractBook` 及 D2/D3/D4 新增类型的当前零实现事实。

## 结论

**不通过。**

没有 P0，但仍有 **5 项 P1、4 项 P2**。上一轮 R-01..R-18 中多数已实质关闭；但修正后仍有几处会在实现时产生真实错误：

1. 户级余额与地点级 `InventoryReservation` 仍可能在“单个 W3 地点 + 其他地点预留”的形态下被错用；
2. `W1/W2b` 也读同一个可支付库存读口，但 payer 级地点预检只写在 W3；
3. `OwnershipStakeBook` 的合并键 `ShareTuple` 仍未包含 `location`，不同地点份额可能被合并；
4. `SurvivalOutlook` 的预计收入没有“下一 tick / 周期”时间窗口，可能把整季产品份额当作下一 tick 口粮；
5. `DebtSource` 的普通贷款/迁移/seed 写入点没有完整列入 owner 与 source 映射。

当前不建议按“可派 D4-I/W3”冻结；建议先把 N-01..N-05 交回对应设计 owner 做最小修正后复评。

## 一、R-01..R-18 逐条核对

> 判定只依据当前正文与源码；“关闭”不等于实现/测试已通过。

| 编号 | 判定 | 依据与说明 |
|---|---|---|
| R-01 | **关闭** | D4 已统一 `SMALL_TOOL`/`LARGE_TOOL`；两 horizon 见 `asset-occupancy-design.md:286-287`，拒绝 `TOOL` 见 `:588,618`，往返/拒绝判据见 `:737-750`；D1 词表见 `production-definition-v2-freeze.md:118-122,184-190`。 |
| R-02 | **关闭** | D2-I2 已改为只要求 `PRODUCT_SHARE` 角色有 participant，`MONEY_WAGE` 可空（`cooperative-production-design.md:669`；正文 `:464`；验收 P20 `:719`）。 |
| R-03 | **关闭** | D2/D3 已改为“D2/D3 只新增 32–35，D4 后 36/37”（`cooperative-production-design.md:56`；`wage-chain-design.md:135,505,889`）；D4 的 36/37 在 `asset-occupancy-design.md:653-654`；status 已更正 `:162`。未再发现“无第 36”作为全库绝对禁令。 |
| R-04 | **部分关闭** | D3 已新增逐需求行覆盖算法、`SurvivalCoverageLine` 来源拆分（`wage-chain-design.md:440-446,473-484`）；但预计收入仍无下一 tick/周期时间窗口，见 **N-04**。 |
| R-05 | **部分关闭** | W3 已增加 payer 级地点预检（`wage-chain-design.md:1008-1010,1294,1316`）；但同一户级余额与其他地点存在的 `InventoryReservation` 仍未扣净，且 W1/W2b 使用同一读口却没有同等预检，见 **N-01/N-02**。 |
| R-06 | **基本关闭** | D1/D2 已逐字补 U22/U23（D1 `:212-216`；D2 `:83-92`）；但 D1 U14/U15 的 blockquote 仍夹控制方括注，见 **N-07**（P2）。 |
| R-07 | **关闭** | D1 的 `laborPerUnit`/`standardLaborMilliPerCycle` 已改为毫小时（`production-definition-v2-freeze.md:30,47,187`）；D2/D3 量纲表一致（`cooperative-production-design.md:255`；`wage-chain-design.md:226`）；活动正文不再把“千分劳动”作为有效单位。 |
| R-08 | **部分关闭** | D4 已列出 `OwnershipStake` 构造/seed 入口及 location 来源（`asset-occupancy-design.md:186-198`；owner 表 `:848`）；但 `OwnershipStakeBook` 的 `ShareTuple` 合并键仍未包含 location，见 **N-03**。 |
| R-09 | **关闭** | D2 已新增 C0-A owner（`cooperative-production-design.md:763-764,772`），覆盖 `AssetKind.java` 与全部 main 旧引用扫描；D1 同步 `:121`。 |
| R-10 | **关闭** | D4 已删除“无请求默认 1”的通用规则，默认 1 只留“已存在单实例且无请求”的兼容路径（`asset-occupancy-design.md:386-394`；验收 P23 `:753`）。 |
| R-11 | **部分关闭** | D3 已增加 `DebtSource` 与 `capitalizeArrears` 的 WAGE/RENT 映射（`wage-chain-design.md:619-645,1080-1090`）；但普通贷款、迁移、seed/codec 的写入点没有完整列入 owner/source 清单，见 **N-05**。 |
| R-12 | **关闭** | 裁决文件头部已标 U23 取代（`d3-precheck-adjudication.md:7`），旧正文标为历史/关闭（`:20,44-52`）；有效实现口径不再使用 `allowIdle`。 |
| R-13 | **关闭** | D1 §2.1 已改为 `simos-economy-api`（`production-definition-v2-freeze.md:37`），顶部历史注保留但明确旧包名作废。 |
| R-14 | **关闭** | D1 `UNALLOCATED` 已改为归 organizer residual（`production-definition-v2-freeze.md:24,110,187-188`；D2 `:536`）。 |
| R-15 | **关闭** | D4 已定义规范 `<q>_<r>`、非法示例与 `asset-location-missing`（`asset-occupancy-design.md:162-164,409,750-753`）。 |
| R-16 | **关闭** | D4-I 唯一新增/实现 `WagePayableInventoryLookup`（`asset-occupancy-design.md:553,848`）；W3 只消费（`wage-chain-design.md:1481,1498`）。 |
| R-17 | **关闭** | D1 末尾已补未实现/未编译/未跑测试声明（`production-definition-v2-freeze.md:227-229`）。 |
| R-18 | **基本关闭** | replan 已改为 W2a→W1→W2b→W3→W4→W5（`production-line-replan.md:80-84`）；status 组件号已更正（`:162,185`）；但 D3 仍留“本次不修改 replan 文件”的旧句，见 **N-06**（P2）。 |

## 二、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| N-01 | P1 | `InventoryReservation` 的 overcommit 校验和 `WagePayableInventoryLookup` 的扣减都只按当前 `(payer, location, commodity)`；账户本身是户级账，其他地点的 ACTIVE 预留不会从当前地点 lookup 中扣除，且不同地点预留可各自分别通过余额检查。 | `asset-occupancy-design.md:247-262`（`InventoryReservation` 含 location；只校验同地点 + frozen ≤ 户级余额）；`:269-272` 重复同地点校验；`:571-577` lookup 只聚合 `location == settlementLocation` 的 reserved；`:584` 明确 `AccountSession` 是户级余额、没有地点级库存；D3 `wage-chain-design.md:1151-1155` 只要求返回“已扣完”的数量 | 同一 payer 在 W3 只有一个 location A、但在 location B 有 ACTIVE 生产预留时：`lookup(A)` 会返回 `balance−frozen−reserved_A`，把 B 已预留的粮算成可支付；同一户在 A/B 各有预留也可能各自通过 `≤ balance`，最终超过户级余额。W3 可能支付掉其他地点生产投入，违反 U16/U21 与 D4-I11。 | 二选一并写死：① 在户级账户上按 payer×commodity 汇总**所有** ACTIVE 预留（不按 settlement location 过滤），lookup 减去全局预留；overcommit 也按 payer×commodity 汇总校验；或② 明确引入地点级物理库存账（但会与“不建第二库存账”冲突，需用户/设计裁定）。同时增加 P18c：`grain=1000`、B 预留 300、A 只有工资义务 ⇒ lookup(A) 必须返回 700，而非 1000；支付/债务不得超过 700。 | D4/D3 |
| N-02 | P1 | payer 级地点预检只写在 W3；W1/W2b 的“预计/排队”也使用同一 `WagePayableInventoryLookup`，没有相同的 payer 级预检，可能针对同一 payer 的多个地点分别读同一户级余额。 | `wage-chain-design.md:1008-1010`（W3 预检）；`:1154-1157`（同一读口，预计/排队/执行使用；但未要求 W1/W2b 预检）；`asset-occupancy-design.md:549-553`（明确“payer 级地点预检由 W3 负责；D4 lookup 不负责”）；D4 `:583-586`（W3 消费顺序） | W1/W2b 可先按 location 各调用一次 lookup，得到同一份户级库存的两份“预计可支付”读数；虽然最终 W3 会在 >1 location 时拒绝，但候选估价、排队、预计利润已经错误，且与“预计/实际同源”冲突。 | 把预检提升为读口/预算契约的一部分：新增 payer 级 `PayableInventoryBudget`（或 `WagePayableInventoryLookup.payableInventoryForPayer(payer)`）供 W1/W2b/W3 共用；或在 W1/W2b 候选入口同样整组拒绝 `>1` location。增加 P39b：同一 payer 两地点在 W1/W2b 也只能得到整组拒绝/零库存预算。 | D3/D4 |
| N-03 | P1 | `OwnershipStakeBook` 的目标份额合并键 `ShareTuple` 仍不含 `location`；即使 `OwnershipStake` 加入了 location，不同地点的两份份额仍可能被当作同一行合并。 | D4 要求 location 是份额身份/地点闭合的一部分（`asset-occupancy-design.md:160-167`；构造/迁移入口 `:186-195`）；当前源码 `OwnershipStakeBook.java:247-252` 的 `ShareTuple` 只有 industry/asset/owner/operator/kind；`:371-374` 与 `:486-489` 用它建键/查重 | 同 industry/asset/owner/operator/kind 但 location=A/B 的两份份额，在 Move/split/rebuild 合并路径中可能被合并成一行；location 被覆盖，R2 跨格隔离失效，`OwnershipStake.quantity` 的守恒也可能表现为错误地点。 | 把 `ProductionLocation` 加入 `ShareTuple`、`buildTupleIndex`、Move/Rebuild 的目标键；Move 目标 location 固定取 `source.location()`（D4 已禁止搬迁）；补 P24b：A/B 两份同 tuple 份额在 split/merge/rebuild 后仍保持两行，location 逐值不变。 | D4 |
| N-04 | P1 | `SurvivalOutlook` 的预计货币/实物/产品份额收入没有“下一 tick 还是整个周期”的时间字段；D2 的产品份额预计是周期窗口，而 D3 的生存判定是下一 tick，可能把整季份额当作下一 tick 口粮。 | D3 `wage-chain-design.md:363,367` 明确“下一 tick 生存所需”；`:414-420` 的 `LivelihoodAlternative` 只有 `laborMilli`、没有 income horizon/forTick；`:479-481` 直接把 `expected*` 当本次覆盖池；D2 `cooperative-production-design.md:526-527` 明确产品份额/工资预计是 `× cycleDays` 的周期累计；U23 决定文档 `wage-idle-barter-decision.md:35` 明确“整季收获不能直接当成下一 tick 已可吃到的粮” | 实现者可能把 D2 的周期产品份额/周期工资直接填入 D3 的 `expected*`，导致 `idleEligible=false`（误判可维生）或 `IDLE_CHOSEN` 错误；也可能反过来低估/高估下一 tick 覆盖。该问题正是 U23-b 的时间尺度核心。 | 给 `SurvivalOutlookRequest` 或 `LivelihoodAlternative` 增加 `forTick`/`horizonTicks`（或要求输入全部为“下一 tick 可达量”）；D2/W2b adapter 必须把周期产物/工资换算到下一 tick，不能原样复用 `RoleDistribution` 的周期净池。增加 P49：cycle=100、产品份额 1000/tick 为 0；下一 tick 覆盖只能用 0（或明确排产后的 10），把 1000 当下一 tick ⇒ 红。 | D3/D2 |
| N-05 | P1 | `DebtSource` 新增了字段，但普通贷款/迁移/seed/codec 的写入点未完整列出 owner 与 source 映射；当前 `DebtContractBook.upsert` / `new DebtContract` 的调用点远多于 W3 owner 表里的三个示例。 | `wage-chain-design.md:619-645`（新增 `DebtSource` 与 WAGE/RENT 映射）；`:1473`（W3 owner 仅列“`UnitBorrowHandler`/`MarketSettlement`/`MintSettlement` 等”，未列 `MoneyMarketSettlement`/`ModeMigrationSettlement`/`LotMigrationBook`/`EconomyPayloads`）；当前调用点：`UnitBorrowHandler.java:170`、`MoneyMarketSettlement.java:640`、`MarketSettlement.java:7827`、`MintSettlement.java:607`、`EconomySettlement.java:7255`、`ModeMigrationSettlement.java:1700`、`LotMigrationBook.java:279`、`EconomyPayloads.java:1795` | 增加必填 `source` 后，遗漏调用点会编译失败；若用默认值掩盖，迁移/seed 可能被错误标成 `ORDINARY_LOAN`，普通贷款或工资债来源漂移；W3 的 `source` 字段无法在 `EconomyPayloads` seed/JSON 往返中闭合。 | 在 W3 owner 表逐一列出这些文件，并规定：普通借入/市场延期/铸币 = `ORDINARY_LOAN`；迁移/合并必须保留原 `source`；`EconomyPayloads`/codec 必须显式读写 source；不得提供默认 `LEGACY_UNKNOWN` 的写路径。补 P29b：所有 `DebtContractBook.upsert`/`new DebtContract` 调用点 source 非空，工资/普通/迁移三类逐值正确。 | D3 |
| N-06 | P2 | D3 仍写“D4 正文仍未完成”和“本次不修改 replan 文件”，与当前 D4 已完成设计、replan 已在同一修正提交更新的现实不符。 | `wage-chain-design.md:1137`（“D4 正文仍未完成”）；`:1464`（“本次不修改 replan 文件”）；D4 正文 `asset-occupancy-design.md:1-198` 已完整落盘；replan 已在 `713b2048` 修改（`production-line-replan.md:80-84`） | 控制方/实现代理可能把 D4 读成未完成、或误以为 replan 仍是旧顺序，造成错误的派单门槛/顺序。 | 把 D3 `:1137` 改为“D4 设计已落盘；`WagePayableInventoryLookup` 仍未实现”；把 `:1464` 改为“本册局部更正已同步到 replan（见 commit）”或删除旧括注。 | D3/控制方 |
| N-07 | P2 | D1 的 U14/U15 blockquote 内仍夹控制方括注，和 D2/D3 已采用的“引文块外另列控制方说明”不一致；引文正文本身逐字，但附录形态未完全满足 §一.8.1。 | D1 `production-definition-v2-freeze.md:196`（U14 后 `（★ U15 更正...）`）、`:198`（U15 后 `（★ 更正...）`）；D2 已把控制方说明独立到 `cooperative-production-design.md:87-92`；D3 独立到 `wage-chain-design.md:42` | 控制方总结仍出现在“用户原话附录”的 blockquote 内，严格审核会认为原话与控制方解释未完全分离，降低派单文档可信度。 | 把 D1 两处括注移到 blockquote 外的“控制方说明（非用户原话）”小节；引文只保留 `U14`/`U15` 原文。 | D1 |
| N-08 | P2 | D4-I 的 owner 表仍与 C2/W2a/W2b 的 owner 表重叠（`EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`ExpectedProfitBook`/`LaborQueueBook`/`EconomySettlement` 等），D4 §11 只写了 D4-I→D4-T→W3，没有明确 D4-I 必须在 C2/W2b 之后、以及逐文件的交接顺序。 | D4 `asset-occupancy-design.md:848`；D2 C2 `cooperative-production-design.md:766`；D3 W2a/W2b `wage-chain-design.md:1470,1472`；D4 文档只写 `:853` 的 D4-D0→D4-I→D4-T→W3；全局 replan 有 C/W 顺序（`production-line-replan.md:69-88`），但 D4 owner 表没有引用 | 两个批次若按各自设计同时开工会争同一文件；尤其在 D4-I 改 `EconomyData`/`ExpectedProfitBook`/`LaborQueueBook` 时，W2b 的匹配/估值写面可能尚未交账，违反一文件一 owner。 | 在 D4 §11 增加明确交接：C2→W2a→W1→W2b 全部交账停手后，D4-I 才开工；或把 D4-I 涉及的这些共享文件拆给同一 owner/独立顺序；把 owner 表与 replan 的 Phase 顺序互引。 | 控制方/D4 |
| N-09 | P2 | `SurvivalOutlook` 要求枚举所有满足劳动/资源约束的替代子集，但没有候选数上限、分支定界或性能预算；替代数增长时 2^n 不可执行。 | `wage-chain-design.md:477`（枚举所有替代子集）、`:491-492`（每个替代只能选一次、所有满足条件的子集、空子集也评估）；没有 `maxAlternatives`/pruning/近似口径 | U23-b 的判定在真实世界中可能因候选组合爆炸无法完成；实现者会自行加入未落盘的剪枝/近似，改变“所有可行组合”的语义。 | 明确候选上限与超限 fail-closed/具名降级（不得静默近似），或给出确定性分支定界/支配剪枝并保持“所有组合”语义；补性能/组合数判据（例如 >N 返回 `survival-search-overflow`）。 | D3 |

## 三、跨文档矛盾重点

### 3.1 组件号

R-03 已实质关闭：D2/D3 的 32–35、D4 的 36/37 均有效正文一致；旧“无第 36”只保留为范围/历史。`EconomyData` 当前源码仍为 31 组件（`EconomyData.java:310-397`；测试断言 `EconomyRoundTripTest.java:549`），尚未实现新增组件，符合“设计已落盘、未实现”。

### 3.2 时间尺度

A1 的候选窗口 `cycleDays` 与实际 W3 due 不乘 `cycleDays` 仍一致。但 **N-04** 是新的跨文档时间尺度缺陷：D2 的周期产品份额/工资预计没有在 D3 的下一 tick 生存预测中被强制换算；U23 原话明确反对把整季收获直接当下一 tick 口粮。

### 3.3 idle / allowIdle

D1–D4 有效正文没有 `allowIdle`/`householdLaborChoices` 状态/命令/seed；D3 的 `idleEligible`/`idleChosen` 分离、默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 与 `BEST_EFFORT_WORK` 仍一致。裁决文件已加 U23 取代标记（R-12 关闭）；旧 `allowIdle=true` 行仍留在历史正文，但明确标为已关闭。未发现新的有效 `allowIdle` 判据。

### 3.4 D4 / R2 / W3

R2 五项退役门、原 `LAND=100` 两实例判据、D4 未关闭声明仍在（`asset-occupancy-design.md:473-502,861`），没有把 D4 写成 R2 已关闭。W3 lookup 形状、`Optional.empty()`/空 `Map`、W3 不二次扣减、读口 owner 已对齐（R-16 关闭）。

但 **N-01/N-02** 表明“户级余额 + 地点级预留/查询”仍不闭合：W3 的 payer 预检不能替代 `WagePayableInventoryLookup` 对其他地点预留的扣除，也不能覆盖 W1/W2b 的预计/排队；**N-03** 表明 `OwnershipStakeBook` 的地点合并键仍缺失。D4/R2/W3 接口方向可保留，但不能按当前 owner/地点语义派实现。

### 3.5 抵物折价与模块边界

D1/D3 仍只允许 `LOCAL_BID`/`LOCAL_ASK`，未发现 `LOCAL_LAST_TRADE`/`referencePriceOf`；缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`、production 定义/economy 执行边界保持一致。未发现新的折价规则冲突。

### 3.6 文件所有权

`WagePayableInventoryLookup` 的 D3/D4 归属于 R-16 已关闭。仍需处理：

- **N-03**：`OwnershipStakeBook` 的 `ShareTuple` 未纳入 D4 §4.2 的 location 规则；
- **N-05**：`DebtSource` 的普通贷款/迁移/seed 写入点没有完整 owner；
- **N-08**：D4-I 与 C2/W2a/W2b 的共享 economy 文件缺少明确交接顺序。

## 四、已核无问题项

以下项已核，未发现额外问题（仅静态复核，不是实现/验收通过）：

1. **R-01**：D4 的 `SMALL_TOOL`（TICK）与 `LARGE_TOOL`（CYCLE）horizon、P10/P11/P11b、往返及 `TOOL` 拒绝判据已对齐。
2. **R-02**：D2-I2 与 `MONEY_WAGE` 可无 participant 已对齐；P20 覆盖 `role-unfilled` 与空工资角色。
3. **R-03**：32–35 与 36/37 的当前有效正文一致；旧“无第 36”仅作为范围/历史说明。
4. **R-06**：D1/D2 的 U22/U23 引文文字与 architecture source/U23 decision 一致；D3/D4 也已逐字收录各自相关原话（N-07 只是 D1 括注形态）。
5. **R-07**：D1/D2/D3 的 labor/standard labor/quote 单位统一为毫小时/毫货币，未在活动正文发现把“千分劳动”当有效单位。
6. **R-09/R-10/R-13/R-14/R-15/R-16/R-17**：C0-A owner、`requestedScale` 默认边界、`ProductionRoleId` 包名、`UNALLOCATED` residual、location 规范、lookup owner、D1 未实现声明均已落地。
7. **R-12**：裁决文件已明确 U23 取代 U-D3-2/U-D3-3；`allowIdle` 不再作为有效实现口径。
8. **A1/U20**：D2/D3 的候选周期窗口、实际 tick due、`satisfiedStreak > decreaseDelayTicks` 文/式/例仍一致。
9. **折价**：仅 `LOCAL_BID`/`LOCAL_ASK`，无被禁止的市场读法；缺价/0 价/折后 0/缺币种 skip 留债。
10. **D4/R2**：D4 仍明确未关闭 R2，五项退役门与 `LAND=100` 原复现判据完整；D4 未新增玩法命令。
11. **固定事实**：`EconomyData` 当前 31 组件；`OwnershipStake` 当前无 location（七个组件）；`AccountSession` 为户级账户、无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；D2/D3/D4 新类型当前零实现。
12. **D3 DebtSource 的语义本身**：`WAGE_ARREARS` + 0‰、`RENT_ARREARS` 保旧条款、`ORDINARY_LOAN` 保原 terms、`LEGACY_UNKNOWN` 不自动 0‰ 的方向正确（N-05 是写入点/owner 不完整）。

## 五、复核限制

- **只读**：未修改任何设计/代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、W3 支付、SurvivalOutlook 行为、R2 多实例/跨周期世界或任何数值例。
- **未实现**：D4 occupancy/`WagePayableInventoryLookup`/`DebtSource`/角色/工资链均只有设计；当前源码仍是旧形状；未派实现单、未提交/合并/推送、未触碰主树。
- **未确认**：本报告中的 `file:line` 是当前 `713b2048` 静态行号；实现后必须重新核行号。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-rereview.md
```
