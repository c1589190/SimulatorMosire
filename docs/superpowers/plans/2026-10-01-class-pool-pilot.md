# 阶层池 + 聚合资产状态：pilot 重构计划

> 分支：`refactor/class-first-economy`
> 目标：把 pilot 从“家户级阶层”改成“阶层池 + 聚合资产状态”；阶层本身固定，人口通过上下溢流在池间交换。

## 1. 数据模型

```text
ClassPool(modeId, classPositionId)
  population
  labor
  assetVector: Map<AssetKind, qty>
  debt: Map<unit, qty>
  assetStateIndex A_C          // 无量纲
  x_C = clamp((A_C − L_C)/(U_C − L_C), 0, 1)

HouseholdAccount(pool, household, sharePerMille)
  // 只做人口/消费/劳动/分配的子账户；资产状态与流动由 ClassPool 决定
```

## 2. AssetStateSchema（佃农制默认）

资产维度与再生产需求（按人/劳动归一）：

```text
dimensions:
  ownedLand, operatedLand, tools, grainReserve, money, debt, leaseSecurity

requirements per class:
  LABORER:  grainBuffer=1期, moneyStart=租佃启动, toolsStart
  TENANT:   seed+tools+rentReserve+foodBuffer+leaseSecurity
  MIDDLE:   seed+tools+foodBuffer+selfFarmLand
  LANDLORD: rentalLand + extractionIncome + maintenanceReserve
```

无量纲状态：

```text
r_k = asset_k / max(1, req_k)
A_C = (Π r_k^{weight_k}) × (min_k r_k)^{bottleneckWeight}
```

- 不硬折不同 unit；无价格时该项记 unpriced 并降低权重；
- debt 是负维；钱多但没地不会撑满状态。

## 3. ClassBounds（佃农制默认）

```text
L_C = 该阶层最低再生产条件的 assetState 值
U_C = 该阶层生产关系容纳上限的 assetState 值

LABORER: L=0,                 U=leaseStartThreshold
TENANT:  L=leaseMaintenance,  U=selfFarmThreshold
MIDDLE:  L=selfFarmMinimum,   U=selfOperableCapacity
LANDLORD:L=landlordMinimum,   U=softReference
```

全部进 `MobilityPolicy`，GM 可逐 mode/class 调整。

## 4. 速率

```text
r_up   = upMin   + (upMax   − upMin)   × x^γ
r_down = downMin + (downMax − downMin) × (1 − x)^γ

默认：
  γ = 2
  upMin=0.01, upMax=0.08   // 年化
  downMin=0.02, downMax=0.15
```

中间 `x∈[0.4,0.6]` 设计成稳定区。

## 5. 人口流与吸收

```text
F_{C→C+1} = P_C × r_up   × O_{C+1}
F_{C→C-1} = P_C × r_down × O_{C-1}
```

吸收系数来自真实机会：

```text
O_tenant   = 可租地 / 求租需求
O_middle   = LandForSale / 求购需求
O_landlord = 可出租地 + 可组织他人劳动的容量
```

`O=0` ⇒ 有压力也流不动；可跳过一级或进入后续定义的待业池，但人口不能消失。

## 6. TransitionBundle（守恒接口）

每条边 `C→D` 必须定义：

```text
Bundle(C→D) = (
  peopleShare, laborShare,
  liquidAssets, landOwnership, leaseRights, tools, claims,
  debtShare
)
```

佃农制默认：

| 边 | 人口/劳动 | 带走 | 留下/获得 | 债务 |
|---|---|---|---|---|
| 雇农→佃农 | 人口+劳动 | 启动储备、工具 | 获得租约使用权 | 按人头带走 |
| 佃农→中农 | 人口+劳动 | 积累基金、工具 | 从 LandForSale 买地 | 按人头带走 |
| 中农→地主 | 人口+劳动 | 超过自营能力的土地 | 保留自营部分，多余地变出租资产 | 留在原地池 |
| 中农→佃农 | 人口+劳动 | 流动财产 | 土地进 LandForSale，租约重签 | 按人头带走 |
| 佃农→雇农 | 人口+劳动 | 流动财产 | 退还租约 | 按人头带走 |

守恒：

```text
Σ P_C = 总人口
Σ assets_C + LandForSale = 总资产
Σ debt_C = 总债务
Σ claim_C = Σ debt_C
```

## 7. GM 政策

`MobilityPolicy(modeId, classPosition, direction/edge)`：

```text
AssetStateSchema：dimensions / requirements / weights / bottleneckWeight
ClassBounds：L_C / U_C
rates：upMin/upMax/downMin/downMax/γ
caps：UpCap_C / DownCap_C
opportunity：leaseAvailability / landForSale / absorptionPolicy
bundle：TransitionBundle
    
GM 只改这些源参数；不得直接改人口、阶层、资产、债务读数。
```

## 8. 试点验收

跑 360 tick：

1. `x∈[0.4,0.6]` 的阶层净流动接近 0（稳定区）；
2. 至少一次同时向上和向下迁移；
3. 中农→佃农（向下）与佃农→中农（向上）由同一批 LandForSale 驱动；
4. GM 调 `UpCap` / `leaseAvailability` 后迁移数量发生可复现变化；
5. 人口/资产/债务守恒全部通过；
6. 正净额 claim、负净额 debt，不混。

## 9. 边界

- 只改 `simos-economy/src/main/java/io/mosire/simos/economy/pilot/**` 与对应 test；
- 不改 `EconomyData`/旧结算/三国主路径；
- 不做 GOV 税、商业 mode、跨格市场；
- 不写大测试文件；读数直接打印。
