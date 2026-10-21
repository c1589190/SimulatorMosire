# D-032 C5b 责任区任务书：运输生产方式、角色与配方骨架（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §4.3/§5/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §5.2/§6 C5
> **上游：** C5a 已完成 compile；`transport_horse` 已在中心词表并登记为 durable commodity capital；
> `ProductionRecipe`/`IndustryDefinitionView`/probe/roster/execute 已有 durableCommodityCapacity/occupancy。
> **本任务只做 C5b 生产方式/角色/配方骨架；C6 做每 tick 批次/非库存产出路由，C9 做实际 seed/roster/读口接线。**

---

## 0. 用户原话与冻结口径

```text
商队的生产资料暂时和耕地在市场中的表述持平，可以设置为运输马匹，目前不计算其生产，作为可交易商品
```

```text
商队生产方式也放两档，第一档是组织运输生产的家户……第二档是雇佣制家户，允许……无地农民家户受雇参与
```

```text
运输能力产出全归 operator OWNER，hired_driver 只领工资
```

冻结：

- `caravan_transport` 概念模式；稳定 mode id 复用现有 `MERCHANT`（`"merchant"`），不新增第二个商人身份；
- 两个位置：
  - `caravan_operator`：OWNER，PRODUCT_SHARE，sharePerMille=1000；
  - `hired_driver`：OWNER/PROLETARIAN，MONEY_WAGE，sharePerMille=0；
- 配方：`cycleDays=1`、`durableCommodityCapacityPerUnit={transport_horse:H}`、
  `inputPerUnit` 无消耗、`laborPerUnit=L`、内部服务产出键 `transport_capacity_service=C`；
- 马匹本批不扣、不折旧；服务产出**不得进普通库存/普通订单簿**，C6 负责 non-inventory 路由；
- 不为运输马匹建 `AssetKind.HORSE`/`OwnershipStake` 第二本账；
- C5b 不 seed transport unit/roster，也不填实际 transport_horse occupancy（C9/C6）。

---

## 1. 目标运行序列

```text
S3
  家户看到 mode=merchant 的两个位置：
    caravan_operator（PRODUCT_SHARE，OWNER）
    hired_driver（MONEY_WAGE，OWNER/PROLETARIAN）
  role/payment 访问门由 C1 的 ProductionPlanBridge 推导

S4/S5
  配方骨架：
    durable capital capacity = transport_horse/H（C5a 契约）
    labor = L
    service output = transport_capacity_service/C
  本任务只建立 recipe/roles/mode；C6 才把 recipe 产物转成 batch

C5b 不实际 seed，不产生 runtime 行为。
```

---

## 2. 责任区六要素

### 2.1 问题现场（C5a 后）

- `transport_horse` 已入中心商品词表、OwnershipCapitalVocabulary、durable capacity/occupancy 契约；
- `DefaultProductionModes.MERCHANT` 仍是旧三位置（principal/porter/self_employed），
  不是二元运输方式；
- 没有 `caravan_transport` 配方模板；
- 没有两个新角色的稳定 id 与 `ProductionRoleDefinition`；
- C6 还没有 service output/batch 路由，C9 还没有 seeder/roster。

### 2.2 责任归属

C5b writer 负责 mode=merchant 的两个运输角色、`CaravanTransportTemplate` 的配方骨架、
以及所有 production/economy main 编译修整。C5b 不 seed、不做 batch、不做市场锚价。

### 2.3 最终能力

1. mode `MERCHANT` 的正式位置改为 `caravan_operator` + `hired_driver`；
2. 配方只锚 `transport_horse` 耐久商品资本，不消耗马匹；
3. 配方产出内部 `transport_capacity_service` 服务键；
4. 角色支付形态正确：operator 1000 ProductShare，driver 0 MoneyWage；
5. C6 能只读这个模板/服务键并接 non-inventory 路由，不重写配方；
6. C9 能只读同一模板 seed unit/roster；
7. compile 绿。

### 2.4 不可破坏机制

- 不新增第二个 merchant 身份：mode 稳定 id 仍是 `MERCHANT`/`"merchant"`；
- 不新增四位置或旧 principal/porter/self_employed 作为运输活动入口；
- 马匹只走 durableCommodityCapacity；不进 inputPerUnit；不扣；
- 服务键不作为普通商品库存/订单簿；不加入 `EconomyVocabulary.allCommodityIds()`；
- 不写测试/不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c5b/impl-ledger.md`，报告编译、改动文件、
mode/角色/配方形状、C6 消费接口、测试失效面、未完成/blocker。

---

## 3. 冻结契约

### 3.1 Mode / roles

```text
ProductionModeId = DefaultProductionModes.MERCHANT ("merchant")
ClassStructureId = structureIdOf(MERCHANT)  // 既有规则：merchant-structure

positions:
  caravan_operator:
    role id/position id = DefaultProductionModes positionId(MERCHANT, "caravan_operator")
    relationToMeans = OWNER
    laborRole = ORGANIZER
    surplusRole = SURPLUS_RECEIVER
    payment = PRODUCT_SHARE, sharePerMille = 1000

  hired_driver:
    role id/position id = positionId(MERCHANT, "hired_driver")
    relationToMeans = DIRECT_LABORER
    laborRole = PROVIDER
    surplusRole = WAGE_EARNER
    payment = MONEY_WAGE, sharePerMille = 0
```

- 新增稳定 role 名常量：`ROLE_CARAVAN_OPERATOR=""caravan_operator"`、`ROLE_HIRED_DRIVER="hired_driver"`；
- `DefaultProductionModes.MERCHANT` 的 mode spec 使用这两个位置；
- 旧 `ROLE_MERCHANT_PRINCIPAL/ROLE_PORTER/ROLE_SELF_EMPLOYED` 不再作为运输模式位置；
  可保留常量/旧模板引用以免 C5b 编译破坏，但不得进入新模式目录；
- `ProductionModeDefinitionView` 不声明 class nature（C1 已删）；
- 一个 owner 可拥有多个组织，仍由 C2 merge/roster 处理。

### 3.2 CaravanTransportTemplate（新类，唯一配方拼写点）

目标语义：

```text
mode = MERCHANT
kind/industry id = 每 hex 一个稳定 industry id（kind 可用 "caravan_transport"）
name = 商队运输
cycleDays = 1
capacityPerUnit = {}                                  // 不再用工具/AssetKind 作规模锚
durableCommodityCapacityPerUnit = { transport_horse: H }
inputPerUnit = {}                                     // 马匹不消耗
laborPerUnit = L
outputPerUnit = { transport_capacity_service: C }     // 内部服务键，C6 非库存路由
roles = [caravan_operator(ProductShare 1000), hired_driver(MoneyWage 0)]
mode = Optional.of(MERCHANT)
```

- H/L/C 为本任务工程常量，必须写在一处；C5b 不改标定数值，先取可审计的最小配置，
  并在账本写明确切值；
- `transport_capacity_service` 是内部服务键，不加入 `EconomyVocabulary.allCommodityIds()`、
  不进入普通 `MarketTable.prices`/HouseholdInventory；
- 必须有 `industry(hex)` 纯函数（模板）和稳定 `industryId(hex)`；
- 不在此类 seed、不创建 unit/roster、不填 occupancy；
- C6 的 non-inventory 路由必须只读同一个 `transport_capacity_service` 键与 recipe；
- C9 的 seeder 必须只读同一模板，不可复制配方/角色/值。

### 3.3 角色定义

`IndustryDefinitionView.roles` 必须与 mode 的两个位置一一对应：

- `ProductionRoleDefinition` id = `positionId(MERCHANT, ROLE_CARAVAN_OPERATOR)`；
- payment = ProductShare，sharePerMille = 1000；
- `ProductionRoleDefinition` id = `positionId(MERCHANT, ROLE_HIRED_DRIVER)`；
- payment = MoneyWage，sharePerMille = 0；
- 不得新增第二个产品份额角色；
- 不得把 hired_driver 的 payment 写成 ProductShare。

### 3.4 旧路径边界

- C5b 不改 `haul`/`CARRIER_FEE`/`MerchantCapacityPool`/旧 merchant 模板退役；
  C7 负责全路径退役；
- 旧 `MerchantTradeTemplate` 可保留编译；但新运输配方只能由 `CaravanTransportTemplate` 定义；
- 旧 principal/porter/self-employed 不在新 mode 目录中，不再作为运输生产入口。

### 3.5 C6 消费接口

C6 需要仅凭以下信息完成 non-inventory 路由：

```text
CaravanTransportTemplate.SERVICE_OUTPUT = transport_capacity_service
CaravanTransportTemplate.industry(hex)
role/unit → operator household
recipe.durableCommodityCapacityPerUnit
```

C5b 必须在账本中列出实际常量/方法名，C6 不得复制配方或角色表。

---

## 4. 不变量与失败语义

1. Mode id 仍 `merchant`，不新增第二个商人身份。
2. mode 只有两个运输位置；支付形态正确。
3. 配方 durable 锚 = transport_horse；不扣马。
4. 服务产出键不进普通商品词表/库存/订单簿。
5. `capacityPerUnit` 可为空，但 durable capacity 必须非空。
6. 配方角色 id 与 mode 位置 id 一一对应；share 规则正确。
7. C5b 不 seed/不产 batch/不填 occupancy。
8. C6 唯一服务键/配方入口。
9. 旧 merchant 模板可编译但不能冒充新运输 recipe。
10. 所有排序/ID 确定性。

失败：

- 新 mode 仍用 principal/porter/self_employed ⇒ 红；
- hired_driver 有产品份额 ⇒ 红；
- 马匹进 inputPerUnit/被扣 ⇒ 红；
- 服务键进入普通商品词表/市场价表 ⇒ 红；
- 配方/角色由两处拼写 ⇒ 红；
- C5b 填 batch/seed ⇒ 越界。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C5b-01：mode merchant 只有 caravan_operator/hired_driver 两个位置，支付形态正确。
- A-C5b-02：operator share=1000 ProductShare；driver share=0 MoneyWage。
- A-C5b-03：CaravanTransportTemplate durable capacity 只有 transport_horse，inputPerUnit 为空，cycleDays=1。
- A-C5b-04：服务产出键不在普通商品词表/库存/订单簿。
- A-C5b-05：角色 id 与 mode 位置 id 一致。
- A-C5b-06：C6 只读同一模板可完成 non-inventory 路由。
- A-C5b-07：C9 只读同一模板可 seed unit/roster。
- A-C5b-08：旧 mode 入口不被误用。

负例：

- 仍有三/四旧位置 ⇒ 红；
- driver 拿产品份额 ⇒ 红；
- 马匹按趟消耗 ⇒ 红；
- service 键进普通市场 ⇒ 红；
- 复制配方/角色常量 ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-production/src/main/java/io/mosire/simos/production/**
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
docs/**（除 `.superpowers` 账本）
```

- 约 300 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- 不改 C5a durable capacity 契约、C6 batch/价格锚、C7 lane/需求、C8 马匹向上、C9 seed 行为。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、mode/角色/配方实际常量、C6 消费接口、既有测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不 seed transport unit/roster（C9）。
- 不填 transport_horse occupancy（C6/C9）。
- 不做每 tick 批次/non-inventory 路由（C6）。
- 不做市场区锚价/需求/lane（C7）。
- 不做马匹向上/混合拆分（C8）。
- 不写测试/不跑测试/不 commit/push/不碰主树。
