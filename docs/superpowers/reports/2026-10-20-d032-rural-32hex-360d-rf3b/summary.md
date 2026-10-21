# D-032 32hex / 360 tick：RF3b 土地取得者占用回退结果

> 状态：中间修复跑，不是最终验收。360/360 `status=OK`，无 `ADVANCE_EXCEPTION`。
> 代码态：RF1–RF3b（需求价格接受者、生产输入/劳动、work-first、周期中途补种、自转移跳过、大信封、
> 土地取得者 D4 LAND 占用回退）。
> 未跑单测/`clean verify`/`tools/gate.sh`；未 commit。

## 1. 运行

- 命令：`SIMOS_MULTITICK_SKIP_BUILD=1 JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' tools/multitick-sim.sh rural-32hex-360d`
- `MULTITICK_SIM_RESULT=PASS cases=1 failed=0`；360/360 `OK`；139.1s。
- CSV sha256：`c225e0c912e5e37ff3a00d13ab9b8810558cbca15ca6f8b106abc1ae93cab710`
- MD sha256：`aa60562017eff0d8bec167b14d5b4c83264cfc1de83b02a85a94a3de08e6e861`

## 2. RF3b 的目的

修复 landless/新地主已有 `landHoldings`、但 D4 的 `requestedScaleFromNonAssetFacts` 因 roster organizer
`plannedLaborMilliPerDay=0` 返回 0 ⇒ 不分配 LAND ⇒ RF3 劳动回退不触发的循环。

实现：

- `SettlementIndex` 从标量 `landHoldings` + roster 计算瞬态 `fallbackLandScaleByUnit`；
- `ProductionProcessBook.assetOccupancyBudget` 只在“roster organizer planned=0、operator 有 LAND、户 labor>0”
  时使用该 fallback scale；
- LAND 仍只由 `LandProductionProjection` 合成瞬态份额，不进入 `assetShares`、不新增持久组件；
- roster 原值不写回；
- 新 DEBUG：`LAND_FALLBACK_OCCUPANCY_REQUEST`、`LAND_OWNER_LABOR_FALLBACK`。

## 3. 关键结果

| 指标 | RF2e 前 | RF3b 后 |
|---|---:|---:|
| 360 tick status | 360 OK | **360 OK** |
| `productionGrainMilli` | 2,271,648,000 | **4,096,320,000** |
| 粮食产出 tick | 120/240/360 | **120/240/241/360** |
| tick120 grain | 526,224,000 | **770,352,000** |
| tick240 grain | 822,864,000 | **1,508,064,000** |
| tick360 grain | 922,560,000 | **1,798,080,000** |
| 人口 | 7,101 → 6,414 | **7,101 → 6,341** |
| 出生 / 死亡 | 435 / 1,121 | **437 / 1,196** |
| 结束粮食库存 | 1,477,644,187 | **2,727,500,425** |
| 市场成交（逐轮相加） | 6,408 | **6,276** |
| 结束债务本金 | 3,845,382 | **34,804,688** |
| 结束合同数 | 3,625 | **3,250** |
| `LAND_FALLBACK_OCCUPANCY_REQUEST` | 0 | **6,759** |
| `LAND_OWNER_LABOR_FALLBACK` | 0 | **6,727** |
| 土地成交 | 0 | **仍 0** |
| 地价 | 0 | **仍 0** |

RF3b 验证了土地取得者的 farm unit 确实开始进入 D4/生产：两次 fallback 日志都出现数千条，农业产出翻倍。
但债务从 3.8M 涨到 34.8M，说明“全量价格接受者需求 + 无现金/无运力环境”下普通市场赊购债务成为新的主要风险；
这不是工资算法回归。

## 4. 仍未解决

- 人口仍净减（1,196 死亡），但比修复前 2,151 明显下降；
- 土地成交仍 0、地价仍 0；土地买盘路径/卖地语义未修；
- 跨 hex 运力仍为 0；
- `PRODUCTION_REJECTED` 仍约 21–22/tick；
- 普通市场赊购债务 34.8M，远高于工资债；F5 必须按 DebtSource 拆分；
- 未做 F4 实际盈余卖盘/土地买盘初始化/运力；
- 未跑测试、`clean verify`、`tools/gate.sh`。

## 5. 结论

RF3b 完成了“土地取得者从有地无劳动计划 → 进入 D4 LAND 占用 → 获得劳动回退 → 产出”的闭环：
`LAND_FALLBACK_OCCUPANCY_REQUEST` 6,759 次、`LAND_OWNER_LABOR_FALLBACK` 6,727 次，粮食产出 4.096B。
下一责任区是 F4：土地买盘初始化、实际盈余卖盘、跨 hex 运力和大额市场赊购债的边界。
