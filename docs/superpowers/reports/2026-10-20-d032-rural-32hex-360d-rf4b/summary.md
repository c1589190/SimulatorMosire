# D-032 32hex / 360 tick：RF4a/RF4b 市场与运力修复结果

> 状态：中间修复跑，不是最终验收。360/360 `status=OK`，无 `ADVANCE_EXCEPTION`。
> 代码态：RF1–RF4b。未跑单测/`clean verify`/`tools/gate.sh`；未 commit。

## 1. 运行

- 命令：`SIMOS_MULTITICK_SKIP_BUILD=1 JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' tools/multitick-sim.sh rural-32hex-360d`
- `MULTITICK_SIM_RESULT=PASS cases=1 failed=0`；360/360 `OK`；145.4s。
- CSV sha256：`e7af3a652ba10b018d0a97d592850af5900460f15811633a7f2d0605949ce7e2`
- MD sha256：`b1a1ff224cc5b4a47a901a3ef36db86040589f4d6f08acbb32658532f83480c1`

## 2. RF4a：土地买盘初始化 + 运力装配去死锁

- `SettlementDayProductionPipeline.prepareProductionBase` 在 M3 前设置
  `ctx.hexToIndustries = ctx.settlementIndex.industriesByHex()`，土地买盘不再恒 0。
- `MerchantCapacityCore.assemble` 的 servicePriced 格：
  `effective = max(haul 服务货, labor+tool productive)`，与决策层
  `MerchantCapacityMath.providerCapacityMilli` 同尺。
- 观察：
  - `LAND_TRADE_SETTLED=81`，土地成交从 0 变为 81,000 milliMu；
  - `MERCHANT_CAPACITY_FALLBACK=4,608`；
  - `MERCHANT_CAPACITY_LANE_TRUNCATED=86,386`（跨 hex 尝试增加，不再全池容量 0）。

## 3. RF4b：迁移 organizer 临时守卫

- `ModeMigrationSettlement.apply` 在任何 move 前过滤 organizer source 的全部 move；
- 记 INFO `MIGRATION_ORGANIZER_REBIND_UNSUPPORTED`（source/targets/units），
  `reason=production-rebind-not-implemented`；
- 目的：避免 `MigrationPopulationMove` 半迁移后抛
  `organizer-merge-requires-rebind`；不实现 `production.RebindInstanceOperator`，这是临时安全阀。
- 本跑 `MIGRATION_ORGANIZER_REBIND_UNSUPPORTED=752`，没有半迁移/异常。

## 4. 关键结果

| 指标 | RF3b | RF4b |
|---|---:|---:|
| 360 tick status | 360 OK | **360 OK** |
| 粮食产出 | 4,096,320,000 | **3,928,032,000** |
| 粮食产出 tick | 120/240/241/360 | **120/121/124/125/126/128/240/360** |
| 人口 | 7,101 → 6,341 | **7,101 → 6,308** |
| 出生 / 死亡 | 437 / 1,196 | **431 / 1,223** |
| 结束粮食库存 | 2,727,500,425 | **2,608,157,928** |
| 市场成交（逐轮相加） | 6,276 | **5,896** |
| 结束债务本金 | 34,804,688 | **32,599,505** |
| 结束合同数 | 3,250 | **2,048** |
| 土地成交 | 0 | **81,000 milliMu** |
| 地价 | 0 | **仍 0** |
| `PRODUCTION_REJECTED` | 21–22/tick | **仍约 22/tick** |
| `ADVANCE_EXCEPTION` | 0 | **0** |

## 5. 仍未解决

- 人口仍净减（1,223 死亡），市场/食物分配仍有缺口；
- 土地成交虽非 0，但地价仍 0、土地卖盘/买盘语义未完整实现；
- `PRODUCTION_REJECTED` 仍约 22/tick，land→unit 组织仍不完整；
- 普通市场赊购/货币市场债仍高（32.6M），需要 F5 按 DebtSource 拆分；
- `production.RebindInstanceOperator` 未实现，organizer 迁移被临时整源跳过；
- 未跑测试、`clean verify`、`tools/gate.sh`。

## 6. 结论

RF4a/RF4b 把土地买盘初始化、运力装配死锁和 organizer 迁移半状态卡死修掉：
**360 全 OK、土地成交非 0、无 ADVANCE_EXCEPTION**。
但这不是最终经济验收：债务、人口、land→unit 组织、测试与门禁仍需 F5/F6。
