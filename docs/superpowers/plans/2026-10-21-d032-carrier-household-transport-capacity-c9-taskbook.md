# D-032 C9 责任区任务书：Seeder、读口与 32hex 接线（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §5/§10/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C9
> **上游：** C1–C8 已完成且 compile 绿；C5b `CaravanTransportTemplate`，C6/C7 批次/锚价/观察，C8 马匹取得。
> **本任务只做 main 的 Seeder/读口/32hex 合同；`simos-app/src/test/**` 的 32hex fixture 由测试 owner 改。**

---

## 0. 用户原话与冻结口径

```text
运输马匹的测试世界存量必须要大
```

```text
每个 hex 的既有 OWNER 家户持有大量运输马匹，并提供可出售余量；不另造“马商”特殊阶级。
```

```text
32hex 仍单市场区；读口显示 ownershipClass、马匹库存、本市场轮运力批次（按 homeHex）、
市场区锚价、区内总 D/S、各 hex 的 TransportHexObservation 与本地未服务/未售原因。
```

冻结：

- 马匹是普通商品库存，唯一数量权威 = actor HouseholdInventory.balances[transport_horse]；
- 不另造马商阶级/AssetKind.HORSE/OwnershipStake；
- 运输生产方式唯一 `CaravanTransportTemplate`/MERCHANT 两位置；
- 每 hex 既有 OWNER 家户获得大库存马匹，并提供可出售余量；
- 32hex 仍是单市场区；
- 测试 fixture 由测试 owner 改；实现 writer 只改 main 合同；
- 不写/跑测试、不 commit/push、不碰主树。

---

## 1. 目标运行序列

```text
S0/S1 创世
  WorldBalanceSeeder / EconomySeeder
    - transport_horse 已进商品词表/价格表；
    - 每 hex 既有 OWNER 家户获得大库存 transport_horse；
    - 每 hex 有 caravan_transport 生产组织/可形成 carrier 家户；
    - 32hex 单市场区由现有 world 参数决定，C9 不另建第二区。
  ProductionRuntimeSeeder
    - 用 CaravanTransportTemplate 建运输 unit/roster；
    - operator = 本 hex OWNER 家户；hired_driver = 工资位置；
    - 不 seed 旧 trade/haul 活动路径。
  ProductionRoleSeeder
    - 运输 mode 位置映射到 caravan_operator/hired_driver；
    - 不再映射旧 merchant principal/porter/self_employed。

S3/S4/S5
  运输单位按配方/roster 组织；马匹 durable capital 从 operator 户库存读；
  产出走 C6 batch sink。

读口（API）
  - 家户：ownershipClass、transport_horse 库存；
  - 市场：transportPriceAnchors、transportCapacityBatches、MarketRoundStats.lastTransportByHex；
  - 区内总 D/S 与逐 hex observation；
  - 缺观察/缺 anchor 如实报缺失，不伪造 0 来源。
```

### 对父序列影响表

| 维度 | C9 影响 |
|---|---|
| 顺序 | 不新增日阶段；只接创世 seed 与读口。 |
| 预算/冻结 | 初始马匹库存与市场价格进入既有商品/账户路径；不改撮合预算。 |
| 身份/持久化 | 不新增 EconomyData 组件；不新增 AssetKind/OwnershipStake；seed 数据走现有组件。 |
| 失败/回滚 | seed 缺 hex/owner/位置/价格 ⇒ 具名 fail-closed，不静默少播。 |
| 日志 | `CARAVAN_TRANSPORT_SEEDED`/`TRANSPORT_HORSE_SEEDED`/`TRANSPORT_READOUT_MISSING`。 |
| 测试 | 32hex fixture/端到端由 C10 test owner；C9 只 main。 |

---

## 2. 责任区六要素

### 2.1 问题现场（C8 后）

- `transport_horse` 已在词表/durable capital，但 WorldBalanceSeeder 没有牌价/大库存/可售余量；
- `IndustrySeeder.trade` 仍是旧 haul/工具配方；
- `ProductionRuntimeSeeder` 仍为城市格加旧 trade；
- `ProductionRoleSeeder` 仍映射旧 merchant principal/self_employed；
- `ApiViews` 还没有 ownershipClass/马匹/批次/锚价/observation 的读口；
- 32hex fixture 仍是旧贸易/无马匹形状（测试 owner 后续迁移）。

### 2.2 责任归属

C9 writer 负责 main Seeder 与 API 读口、32hex 单区合同；
不负责测试 fixture、不跑测试、不改 test-world、不做 C10 测试/门禁。

### 2.3 最终能力

1. 每 hex 既有 OWNER 户获得大库存 transport_horse，并有可售余量；
2. 每 hex 有 caravan_transport unit/roster/operator/工资位置；
3. 旧 merchant trade haul 活动 seed 退出；
4. 32hex 仍是单市场区；
5. 读口可看 ownershipClass、马匹库存、批次、锚价、D/S、observation；
6. 缺失读口数据如实报缺，不伪造；
7. compile 绿。

### 2.4 不可破坏机制

- 马匹一份库存权威；不建 AssetKind.HORSE/OwnershipStake；
- 不新增第二市场/第二 matcher；
- 不 seed 旧 haul 生产活动；
- 不动 LAND/人口/债务既有权威；
- 不写测试/不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c9/impl-ledger.md`，先写目标运行段落+影响表；
报告编译、seed 常量/落点、unit/roster、读口字段、C10 测试接口、测试失效面、未完成/blocker。

---

## 3. 冻结契约

### 3.1 商品/价格/初始库存

- 在中心 seeder 增加 transport_horse 的市场价常量（唯一拼写点）；
- 每 hex 既有 OWNER 家户的 inventory 获得大库存马匹（具体数值写账本；必须远大于 test-world 需求）；
- 提供可出售余量：不得把全部库存冻结/全部自用；
- 不建第二本马匹账；
- 缺价格/缺 owner/缺 inventory 挂点 ⇒ 具名 fail-closed。

### 3.2 运输生产方式 seed

- `IndustrySeeder` 新增 `caravanTransport(hex, operatorActor)`（名可调，账本记录）：
  - 只读 `CaravanTransportTemplate.industry(hex)` 与 ROLES；
  - cycleDays=1、durable horse capacity、service output；
  - operator/roster 使用 `caravan_operator`/`hired_driver`；
  - 不复制配方/角色/H/L/C。
- `ProductionRuntimeSeeder` 每 hex 增加运输 unit：
  - operator = 本 hex 既有 OWNER 户（无则按现有 seed 规则不建，不能凭空造阶级）；
  - hired_driver 工资位置按现有工资锚/roster 规则；
  - 不建旧 `trade`/haul unit；
  - 同 hex 同户最多一个活跃运输组织（C5b 合同）；多种生产组织可并存。
- `ProductionRoleSeeder`：
  - 旧 merchant principal/porter/self_employed 映射退出；
  - 运输位置映射到 `caravan_operator`/`hired_driver`；
  - 缺位置 fail-closed。

### 3.3 32hex / 单市场区

- 32hex fixture 的 main 合同必须让每 hex 都有运输 seed 条件；
- 不新增第二市场区；现有 world 参数仍是单区权威；
- 测试 fixture 修改由 C10 owner 完成，C9 不写 `simos-app/src/test/**`；
- main seeder 与测试 fixture 的初始马匹数量必须由同一合同口径生成（常量/入口唯一）。

### 3.4 读口

在 `ApiViews` 现有家户/市场视图中增加（名可调，账本记录）：

- household `ownershipClass`（由 C1 lookup 派生，不持久读字段）；
- household `transportHorseMilli`（库存，缺失=0/empty 如实）；
- market `transportPriceAnchor`（zone/currency/price/streak/round）；
- market `transportCapacityBatches`（按 household/homeHex/marketRoundId/marketZone/capacity）；
- market `transportDso`（区总 D/S）与 `lastTransportByHex`（observation 逐 hex）；
- 缺 plan/observation/anchor 时返回空/缺失标记，不伪造零来源；
- 读口不改状态、不写 ChangeSet、不跑公式。

### 3.5 旧路径

- 新世界 seed 不再加旧 `trade`/haul 活动单位；
- 旧 `MerchantTradeTemplate`/HaulService 类可保留编译死代码，C7 已使其活动 matcher 不调用；
- C9 不新增旧 haul 库存/产出/价格。

---

## 4. 不变量与失败语义

1. 每 hex OWNER 大库存马匹，可售余量存在。
2. 马匹只一份库存权威。
3. 每 hex 运输 unit/roster 可形成 carrier。
4. 32hex 单市场区。
5. 旧 trade/haul 活动 seed 退出。
6. 读口数据来源唯一、缺失如实。
7. 不新增 EconomyData 组件。
8. seed 缺 owner/位置/价格 ⇒ fail-closed。
9. 32hex fixture 留给测试 owner。
10. compile 绿。

负例：

- 每 hex 无马/无运输 unit ⇒ 红；
- 马匹建第二本账 ⇒ 红；
- 新世界仍 seed 旧 trade/haul ⇒ 红；
- 32hex 多市场区 ⇒ 红；
- 读口伪造 0 来源 ⇒ 红；
- 实现 writer 改 test fixture ⇒ 越界。

---

## 5. 验收锚点（供 test owner/C10）

- A-C9-01：每 hex OWNER 有大量马匹库存/可售余量。
- A-C9-02：每 hex 有 caravan_transport unit/roster，operator/driver 正确。
- A-C9-03：新世界不再 seed 旧 trade/haul 活动单位。
- A-C9-04：32hex 单市场区。
- A-C9-05：读口含 ownershipClass/马匹/批次/锚价/D/S/observation。
- A-C9-06：读口缺失如实。
- A-C9-07：无 AssetKind.HORSE/OwnershipStake。
- A-C9-08：main/test fixture 同一马匹合同口径。
- A-C9-09：缺 owner/位置/价格 fail-closed。
- A-C9-10：C10 可只读同一入口做测试。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-production/src/main/java/io/mosire/simos/production/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-app/src/main/java/io/mosire/simos/app/**
simos-actor/src/main/java/io/mosire/simos/actor/**   // 仅商品库存读口所需
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire/.claude/worktrees 之外
docs/**（除 `.superpowers` 账本）
```

- 约 316 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- C9 不做 C10 测试/门禁；不新增测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、seed 常量/落点、unit/roster、读口字段、C10 接口、测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不做 C10 测试/fixture/门禁/360。
- 不做运输马匹折旧/损耗。
- 不改 C7 matcher/锚价/旧 haul 活动路径（已 C7b2 处理）。
- 不写测试/不跑测试/不 commit/push/不碰主树。
