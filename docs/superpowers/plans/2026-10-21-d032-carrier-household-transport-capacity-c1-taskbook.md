# D-032 C1 责任区任务书：OWNER/PROLETARIAN 阶级契约与位置访问（2026-10-21）

> **状态：可派单的实现任务书；本轮只写生产代码到编译绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md`
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C1
> **目标运行序列锚点：** 父设计 §3 S0–S8；C1 嵌入 S1/S2（阶级载入与家户结构）、S7-M2（债偿 class 中间态）、S7-B/C（阶级流动）和 S8 收口。
>
> 本任务书是约束设计书，不是逐文件施工图。实现方必须自己写
> `.superpowers/sdd/2026-10-21-d032-carrier-c1/impl-ledger.md`，记录实际架构、调查结论、偏离和未完成项。

---

## 0. 用户原话与授权（逐字，相关段落）

### 0.1 阶级二元化

```text
注意到，这里事实上直接以资本主义逻辑区分了有产者和无产者，单个有产者允许购买多种生产资料，包括土地、运输马匹，并同时根据利润高低组织多种社会生产，例如有地农业、运力提供，但是无产者只能是无产者，其只能作为被雇佣的一方，在买得生产资料时，自动成为对应阶层的有产者，这套机制毫无疑问是合理的；
```

```text
现在，对于商业运输，我们已经规定了二元制的生产方式、对应家户的阶级跃迁通道和方式、运力作为商品的特殊性，值得一提的是，应当先把有地农民和无地农民规范成有产者和无产者——家户不绑定生产方式，生产方式对家户的区分从此只有有产者和无产者，细化阶层划分以后再做
```

### 0.2 生产方式不赚钱不触发阶级流动

```text
我需要再确认一下阶层流动，事实上，由于生产方式不赚钱而导致的有产者阶层流动已经可以废掉了，因为一个有产者家户可以自由选择什么作为主生产方式，设计中是这样吗？
```

用户随后确认了控制方的解释：生产方式/主副业变化 ≠ 阶级流动；有产者可自由更换活动、可受雇；
某活动不赚钱只影响下一 tick 的活动选择。

### 0.3 向下人口流动

```text
阶级向下流动不需要有产者家户失去所有生产资料，只需要随着负债增加，给一个家户迁移数值就行，只迁移人口的那种——我记得家户人员流动表有搞吧？
```

```text
是的，HouseholdFlowBook不记库存也不记债，只记人净身出户，也就是方向B
```

### 0.4 债务清偿介质与中间态

```text
额，所以说，如果有产者欠无产者工资，是不能用土地/货运马匹抵债的？
```

```text
市场轮还债就是该还土地还土地，该还其他东西还其他东西，有一个通用还债顺序的，也就是A；如果无产者家户在期间拥有土地等生产资料了怎么办？没有关系，后续统一转化/合并为有产者家户，中间态流程处理应该是可行的吧？
```

### 0.5 本轮授权

```text
其他要不直接根据我之前的思路，你代替我做裁定？根据我的补充内容，完善设计和开发计划，有裁定再告诉我
```

```text
可以开始派单了，记得遵循skill中的责任区派单规则
```

---

## 1. 目标运行序列（C1 只负责其中的 class 部分）

```text
S0 Social
  人口/温饱/劳动权威不变。

S1 经济载入
  载入 Map LAND、actor 商品库存（未来含 transport_horse）、economy assetShares；
  装配只读 HouseholdOwnershipFacts；
  ownershipClass 由资产当刻派生，不持久另存。

S2 进入/迁移/组织
  家户按 (homeHex, cultureKey, ownershipClass) 合并；
  位置访问按 ownershipClass + RolePayment；
  有产者可经营/自雇/受雇；无产者只能工资位置。

S3 生产位置选择
  无产者只进入 MONEY_WAGE；
  有产者可进入任何允许位置；
  生产方式本身不写阶级。

S4/S5/S6
  生产、产出、工资仍按既有阶段；C1 不改运输生产配方/产出通道。

S7-M2 债务清偿
  工资第一次只付货币；未付成普通 0‰ 工资债；
  后续通用还债顺序：货币 → 可清偿库存（含未来的 transport_horse）→ LAND；
  债权人直接取得抵债资产；
  允许 working copy 内“清偿前 PROLETARIAN、清偿后持有生产资料”的中间态；
  债务转移后的最终状态必须按最终资产重新派生 ownershipClass；
  不得持久化“PROLETARIAN + 正生产资料”的矛盾 class。

S7-B 市场买入向上跃迁
  使用 S7-M2 完成、S7-A 交易开始前的 ownershipClassAtMarketTradeStart 快照；
  市场买入路径与债偿路径分开，不能互相冒充。

S7-C 向下人口流
  仍只迁人口；source 保留生产资料则仍是 OWNER；债务清空全部生产资料时自然派生 PROLETARIAN。

S8
  回传事实；C1 不改变 Social 人口权威。
```

---

## 2. 责任区六要素

### 2.1 问题现场（已回代码核）

- `ClassNature` 目前只有 `LANDED/LANDLESS`，位置级；
  `ProductionRole` / `ProductionModeDefinitionView` / payload / reference validation 都依赖它。
- `ClassNatureBook` 从生产位置派生家户 class；同户多 position 声明冲突会抛。
- `ProductionPlanBridge` 现有门控：
  - LANDLESS 且 organizer/非 MoneyWage ⇒ 拒绝；
  - LANDED 且非 ProductShare ⇒ `CLASS_NATURE_LANDED_CANNOT_WAGE`。
  这与“有产者可受雇”冲突。
- `HouseholdMergeBook` 当前 key 含 `(productionMode, classNature)`；
  本责任区只做 class/位置访问契约，不负责把 merge key 全改完，但必须让其后继代码有可用的 ownershipClass 读口。
- `HouseholdFlowBook` 当前靠 `ClassNature.LANDED/LANDLESS` 选 source/target，仍搬钱货债；
  C4 才改 population-only；C1 只移除/替换其 class 依赖，不能提前宣称 C4 完成。
- `DebtFirstSettlement` 当前按 `ClassNature.LANDLESS` 跳过 LAND 腿；
  新口径必须删除该守卫，并允许债偿中间态，在最终状态重派生 class。
- C1 需要提供跨切片 ownership lookup；后续 C2/C4/C7/C8 会复用它。

### 2.2 责任归属

C1 writer 负责**整个阶级契约与位置访问责任区**：
从 `simos-economy-api` 的公开 class 词表，到 production contract/payload、economy 派生读口、
位置门控、旧 ClassNature 清理、债务清偿 class 移除与最终派生，全部一次做到 `compile` 绿。

C2/C3/C4/C5 的运输/流动/配方本体会在其任务书里继续改；C1 不抢跑。

### 2.3 最终能力

完成后系统必须能表达：

1. `OWNER`：拥有任一正数量 LAND、`transport_horse`（未来接入）、非 LAND 耐久资产；
2. `PROLETARIAN`：以上全部为 0；
3. `ownershipClass` 不持久另存；任意时刻由跨切片只读事实派生；
4. 有产者可经营/自雇/受雇；无产者只能进入 `MONEY_WAGE` 位置；
5. `PRODUCT_SHARE` 只允许 OWNER；`MONEY_WAGE` 允许 OWNER 和 PROLETARIAN；
6. 生产方式/主副业变化不改变 `ownershipClass`；
7. 债务清偿可把 LAND/运输马匹直接给债权人；债权人资产从零变正后最终派生 OWNER；
8. S7-M2 working copy 可短暂出现“PROLETARIAN 有资产”，但最终 ChangeSet 不得持久化该矛盾状态；
9. C1 编译通过，且后续 C2/C4/C5 能在该读口上继续工作。

### 2.4 不可破坏机制

- 五条铁律；尤其 `Command → ChangeSet → Revision`、完整状态往返、跨 revision 原子提交。
- 领域所有权：Map LAND、actor 商品库存、economy assetShares；C1 不得建第二份资产权威。
- Social 是人口唯一权威；C1 不写人口。
- `MONEY_WAGE` 不取得产品份额；`PRODUCT_SHARE` 不以实际劳动为条件。
- 不新增抵押/担保/特定资产优先受偿。
- 不引入第二个市场/第二套 matcher。
- 不写旧档兼容；Alpha 直接重建。

### 2.5 可动范围

只允许改本任务书 §7 列出的 main 文件范围。不得改测试、pom、tools、world、docs（账本除外）。

### 2.6 交账方式

- 只写生产代码，只跑到 `compile` 绿；
- 写 `.superpowers/sdd/2026-10-21-d032-carrier-c1/impl-ledger.md`；
- 报告：改动文件、编译命令 + 真实 rc/输出、数值行为影响、受影响硬编码字面量、
  会让既有测试失效的清单、未完成/blocker、与任务书偏离。
- 不 commit/push。

---

## 3. 冻结契约形状

### 3.1 公开词表

**类型：** `OwnershipClass = OWNER | PROLETARIAN`

- 放在 `simos-economy-api` 的公开包（建议 `io.mosire.simos.economy.api.classes.OwnershipClass`；
  最终包名写进实现账本）；
- production 依赖 economy-api，可引用；
- economy 实现可引用。

删除/退役 `ClassNature` 作为家户阶级：
- 不再有 `LANDED/LANDLESS` 家户 class；
- 不再有 mode 级 `supportedClassNatures`；
- 不再有 role/position 的单一 `classNature` 字段；
- 位置访问改由 `RolePayment` 推导：
  - `PRODUCT_SHARE` ⇒ 只接受 OWNER；
  - `MONEY_WAGE` ⇒ 接受 OWNER 或 PROLETARIAN。
- 若发现现有 `ProductionRole`/`ProductionRoleDefinition` 需要显式 access 集合才能表达，
  writer 可在本责任区内引入 `acceptedOwnershipClasses`，但必须保持上述语义并写进账本。
  不允许留下第二个家户 class 字段。

### 3.2 跨切片派生输入

C1 必须提供一个 economy 内部只读派生口（名称由实现定，语义冻结）：

```text
ownershipClassOf(household, liveWorkingState) =
  OWNER if
    Σ LAND(household, anyHex).quantityMilliMu > 0
    OR HouseholdInventory.balances[transport_horse] > 0     // C5 接入该商品 id
    OR any non-LAND OwnershipStake owned by household has quantity > 0
  else PROLETARIAN
```

约束：

- 输入必须读**同一份当刻活工作副本**；S7-M2 内不使用 S7 前旧快照；
- `transport_horse` id 由中心词表/常量提供；C5 之前可先留可配置 durable commodity 集合，
  但不得用“所有商品库存 >0”当有产者；
- `assetShares` 只看 owner 归属与正数量；operator 但非 owner 不自动算家户有产；
- LAND 只从 Map transient ledger/owner 视图读，不得读回已删除的持久 `landHoldings`；
- 不把 `ownershipClass` 写进 `EconomyData`/`Codec`/`ChangeSet`。

### 3.3 位置访问门

C1 必须把以下旧门替换为 ownershipClass + RolePayment：

| household | role payment | 结果 |
|---|---|---|
| OWNER | PRODUCT_SHARE | 允许 |
| OWNER | MONEY_WAGE | 允许 |
| PROLETARIAN | PRODUCT_SHARE | 拒绝（具名） |
| PROLETARIAN | MONEY_WAGE | 允许 |

- 不得用“位置 classNature = 整个家户 class”推导；
- 同一 OWNER 可同时拥有多个 production organization/roster，不得因多位置 class 冲突而抛；
- 旧 `CLASS_NATURE_LANDED_CANNOT_WAGE` 必须消失；
- `PROLETARIAN` 经营/组织位置必须 fail-closed，带具名原因。

### 3.4 债务清偿

- 删除 `DebtFirstSettlement` 的 LANDLESS 债权人跳过 LAND 腿守卫；
- 债偿顺序仍是既有 `货币 → 可清偿库存 → LAND`；
- `transport_horse` 作为库存商品可由 C5 接入价格/库存后自动进入该腿；
- 本批不新增非 LAND `OwnershipStake` 的债务转移腿；
- 债偿资产直接归债权人户，不触发市场买入的目标户吸收/人口迁移；
- S7-M2 working copy 内允许 class 中间态；最终 build 前对受影响户重新派生 ownershipClass；
- S7-M2 完成后、S7-A 交易开始前提供 `ownershipClassAtMarketTradeStart` 快照，
  供后续 S7-B 市场买入跃迁使用；
- 债偿实物转移沿用 ledger 直转，不是市场成交，不生成运输需求/运费。

### 3.5 旧流动链

- `ClassSlideSettlement` 不再作为向下流动写口；删除调用/路径（或让其不可达并记录）。
- `ModeMigrationPolicy`/`ModeMigrationSettlement` 不再承担阶级流动；
  若还有普通活动/迁移逻辑，必须不写 `ownershipClass`、不按 classNature 换位置。
- `HouseholdFlowBook` 的完整 population-only 改造属 C4；C1 只把其 class 依赖切换到 ownershipClass，
  不得把“C4 已完成”写进账本。
- `HouseholdFlowTable` 的持久形状、lastObservedDebt 基线保留；C4 再改执行器。

---

## 4. 不变量与失败语义

### 4.1 不变量

1. `ownershipClass` 只由资产派生，不持久、不由 productionMode 派生。
2. 有产者可受雇；无产者只能进入 `MONEY_WAGE` 位置。
3. 产品份额角色只允许有产者；工资角色不取得产品份额。
4. 生产方式切换、主副业变化、亏损、债务增加本身不改变 `ownershipClass`；
   只有实际资产获得/失去导致资产从 0→正/正→0。
5. 同一 OWNER 可同时有多个生产位置；不得因 classNature 多值冲突拒绝。
6. 债务清偿前 PROLETARIAN、清偿后拥有 LAND/马匹的中间态只能存在于 working copy；
   最终状态按最终资产派生 OWNER。
7. 债偿/市场买入/合并全部在同一 revision 原子提交；失败不半提交。
8. 所有排序确定性；不得用 Map 迭代序、墙钟、随机 UUID。
9. Social 仍是人口唯一权威；经济不直接写 `HouseholdEconomy.population/laborMilli`。

### 4.2 失败语义

- 未知/缺失 class 派生输入：不得静默当 PROLETARIAN；装配缺口应 fail-closed 或明确 empty 语义。
- 无产者占 PRODUCT_SHARE/经营者位置 ⇒ 具名拒绝。
- 有产者因 MONEY_WAGE 位置被拒 ⇒ 契约故障，必须修。
- 最终状态持久化“PROLETARIAN + 正生产资料” ⇒ 契约故障。
- 债偿转移资产但未减本金 ⇒ 契约故障。
- 跨切片 lookup 读旧快照导致债偿后 class 未更新 ⇒ 契约故障。
- 删除 `ClassNature` 后仍留 main 裸引用 ⇒ 编译失败，不得用兼容别名掩盖。

---

## 5. 验收锚点（供后续测试 owner，不在本任务运行）

### 正例

- T-C1-01：LAND>0 ⇒ OWNER；LAND=0、马匹=0、耐久资产=0 ⇒ PROLETARIAN。
- T-C1-02：生产 mode 切换后 ownershipClass 不变。
- T-C1-03：OWNER 进入 MONEY_WAGE 成功；PROLETARIAN 进入 MONEY_WAGE 成功。
- T-C1-04：OWNER 同时拥有多个 production org/position 不因 classNature 冲突失败。
- T-C1-05：PROLETARIAN 债权人在 S7-M2 收到 LAND/运输马匹后最终派生 OWNER。
- T-C1-06：债偿后的 `ownershipClassAtMarketTradeStart` 使用最终资产，不把新 OWNER 当本轮 PROLETARIAN 买方。
- T-C1-07：旧 `ClassSlideSettlement`/mode-profit 路径不再改变 ownershipClass。
- T-C1-08：S7-M2 中间态不进入最终 ChangeSet；同一 revision 可回放。

### 负例

- PROLETARIAN + LAND>0/马匹>0/正耐久资产 ⇒ 最终仍 PROLETARIAN：红。
- PROLETARIAN 进入 PRODUCT_SHARE/owner operator：不拒绝或错误接受：红。
- OWNER 进入 MONEY_WAGE 被拒：红。
- 债偿转了资产但未减本金：红。
- 债偿后仍用 S7-M2 前 class 快照做 S7-B 买方判断：红。
- 把 `ownershipClass` 写进 Codec/ChangeSet 持久字段：红。
- 仍以 ClassNature 决定位置访问：红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-production/src/main/java/io/mosire/simos/production/contract/**
simos-production/src/main/java/io/mosire/simos/production/spi/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire
docs/**（除本任务指定的 .superpowers 账本外不改）
```

### 文件所有权说明

- 当前工作树有大量前序未提交改动；不得回滚、格式化、批量重写无关文件。
- `git status` 基线见派遣时控制方核对；只改本责任区需要的主代码。
- 如果发现必须改测试才能编译：**停手报告**，不要改测试；因为本任务只要求 `compile`，不要求 `test-compile`。
- 如果发现必须改 pom/enforcer 才能编译：**停手报告**，不要改 pom。
- 本任务不写运输批次/价格锚/耐久商品配方；这些属 C5–C8。

---

## 7. 编译与交账

### 实现步骤

1. 先读父设计/计划 §6 C1 与本任务书；
2. 只读调查现有 class/role/payload/flow 引用；
3. 实现并持续跑主代码编译，直到：
   ```bash
   tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
   ```
   `rc=0`；
4. 不跑 `test` / `test-compile` / `verify` / gate / 360；
5. 写 ledger；
6. 报告。

### 必须报告

- 改动文件清单（不含 `.superpowers` 本机留痕）；
- 编译命令原文 + 真实 rc + 输出摘要；
- 会改变数值行为的清单（本责任区理论上应为纯 class/访问语义，须逐项说明）；
- 受影响硬编码字面量；
- 会让既有测试失效的类型/构造器/JSON 字段清单；
- 与任务书不一致处 + 原因；
- 未做/未验证项。

### 强制停手条件

- 发现设计与用户原话/五条铁律/模块边界冲突 ⇒ 立即上报；
- 发现需要改测试/pom/tools 才能继续 ⇒ 上报，不自行越界；
- 发现 291 项未提交改动中的别人半成品被本任务踩到 ⇒ 停下报告，不代改；
- 无法在允许模块内做到 compile 绿 ⇒ 交 BLOCKED + 最小额外范围。

---

## 8. 非目标

- C1 **不实现**运输生产方式/马匹商品/耐久商品资本通道（C5）。
- C1 **不实现**每 tick 运输批次/价格锚/运输需求/lane 分配（C6/C7）。
- C1 **不实现** C4 的完整 population-only 执行器和 target shell 契约；只移除 class 依赖并为 C4 留口。
- C1 **不实现** C2 的最终 merge key 和 merge rebind 全链；只提供 ownershipClass 读口。
- C1 **不实现** C3/C8 的完整市场买入向上具体 primitive；只冻结 class 快照与访问语义。
- C1 **不改** LAND 表/actor 库存/economy assetShares 的存储形状。
- C1 **不写**测试、不跑测试、不 commit/push、不碰主树。

---

## 9. 交接入口

实现完成后，控制方将据此任务书验收；下一责任区是 C2，需先根据 C1 的实际类型/方法签名写 C2 任务书，不得直接把本任务书当 C2 施工图。
