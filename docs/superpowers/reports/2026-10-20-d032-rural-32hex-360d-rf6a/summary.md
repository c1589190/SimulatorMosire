# D-032 32hex / 360 tick：RF6a/RF6b 收口结果

> 状态：本轮修复责任区的最终 360 tick 证据 + 完整门禁证据；**不是整个 v2 S0–S8 的最终经济验收**。
> 代码态：RF1–RF4b（需求价格接受者、生产输入/劳动、土地组织、市场/运力、迁移守卫）+ RF5 测试迁移 + RF6a null 守卫 + RF6b SpotBugs 注解清理。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；未 commit/push，未碰主树。

## 1. 最终运行

当前最终字节（RF6b 后）使用门禁刚构建的 classes，跳过编译重跑：

```bash
SIMOS_MULTITICK_SKIP_BUILD=1 \
JAVA_OPTS='-Dsimos.economy.logLevel=INFO' \
tools/multitick-sim.sh rural-32hex-360d
```

结果：

- `MULTITICK_SIM_RESULT=PASS cases=1 failed=0`
- `[PASS] case=rural-32hex-360d 用时=126.6s`
- 360 行全部 `status=OK`
- 无 `ADVANCE_EXCEPTION`、无运行期异常退出、无 OOM
- 退出码 `0`

修复批次中另有一轮同代码态（RF6a 后、RF6b 前，DEBUG 日志，387MB）：
`[PASS] ... 用时=138.7s`。两轮 CSV/MD 逐字节相同。

## 2. 产物与哈希

目录：`docs/superpowers/reports/2026-10-20-d032-rural-32hex-360d-rf6a/`

```text
e7af3a652ba10b018d0a97d592850af5900460f15811633a7f2d0605949ce7e2  rural-32hex-360d.csv
b1a1ff224cc5b4a47a901a3ef36db86040589f4d6f08acbb32658532f83480c1  rural-32hex-360d.md
```

两个哈希与 `docs/superpowers/reports/2026-10-20-d032-rural-32hex-360d-rf4b/` **完全相同**，
说明 RF6a（仅 5 参无账视图 null 守卫）与 RF6b（仅删除无效 SpotBugs 注解/import）没有改变 32hex/360 tick 的任何数值。

完整指标见同目录 `metrics.txt`。

## 3. 360 tick 关键读数

| 指标 | 结果 |
|---|---:|
| status | **360 / 360 OK** |
| 人口 | 7,101 → 6,308（出生 431，死亡 1,223） |
| 户数 | 290 → 290 |
| 粮食产出（逐 tick 求和） | **3,928,032,000 milli** |
| 有产出的 tick | 120、121、124、125、126、128、240、360 |
| 结束粮食库存 | 2,608,157,928 milli |
| 结束纤维库存 | 1,056,403,879 milli |
| 结束货币总量 | 91,000 milli |
| 市场成交（逐轮笔数求和） | 5,896 |
| 未成交槽位（逐轮槽位求和，不是人数/数量） | 44,825 |
| 结束债务本金 | **32,599,505 milli** |
| 结束工资债本金 | 967,037 milli |
| 结束债务合同版本数 | 2,048（不是义务笔数） |
| 土地成交 | 81,000 milliMu（10 个非零成交轮） |
| 结束地价 | **0** |
| 土地抵债转移量 | 46,515,182 milliMu（数量，不是钱） |

`seedInputMilli=3,365,292,000` 是 `ProductionReceipt.consumedInputs` 的周期累计口径，不是当日库存增量。

## 4. 完整门禁

命令：

```bash
tools/gate.sh full
```

结果：**PASS**，`clean verify rc=0 + validate rc=0`，耗时 315s。

- 全仓 surefire：**3585 tests / 0 failures / 0 errors**（472 个报告；app 侧 1017 条含 7 条既有 LLM 环境跳过）
- 前端门禁：**412 / 412**
- SpotBugs：**18 个模块均 `BugInstance size is 0`**
- 7 条关键护栏全部真实执行且通过
- Checkstyle 0 violations；Spotless 通过；shaded jar 已生成（28,845,597 字节）
- `-DskipTests validate` 模块边界 enforcer 通过

门禁证据（本机 SDD，不进 git）：

- `.superpowers/sdd/2026-10-20-d032-rf6a-null-account-guard/evidence/11-gate-full.log`
- `.superpowers/sdd/2026-10-20-d032-rf6a-null-account-guard/evidence/12-final-360-post-rf6b.log`

## 5. 本轮最后两处改动

### RF6a：无账视图 null 守卫

`LaborQueuePlanBook` 的 offer 构造从直接 `accounts.householdGoods()` 改为：

```java
accounts == null ? null : accounts.householdGoods()
```

`accounts == null` 时传给 `LaborQueueBook.offer` 的是 `null`（`awaiting-draw`），不是 `Map.of()`
（后者会被误读成“有账但库存为 0”并可能伪装成 `NO_INPUT`）。有账路径逐值不变。

### RF6b：清除无效 SpotBugs 抑制

`tools/gate.sh full` 在 SpotBugs 阶段曾报 3 条 `US_USELESS_SUPPRESSION_ON_*`；
实现 Agent 只删除了以下 3 处无效注解及失去用途的 import，未改冻结视图/ record 形状/调用方：

- `LandLedgerState.holdingsByKey()`
- `LandProductionProjection.Projection`
- `LandStateMerge.Merged`

随后 Spotless 机械重排了 `simos-economy-api/.../land/*` 的 Javadoc 折行；仍无业务逻辑变化。

## 6. 仍未解决 / 未做

| 项 | 状态 |
|---|---|
| F5 债务分源读数 | **未实现**：当前 CSV 只有债务总本金与工资债子集；市场赊购和货币市场贷款仍都写 `ORDINARY_LOAN`，尚未在报表中拆开。 |
| 无生产/无收入环境的信用边界 | **未定/未实现**：本轮只验证现状，没有改市场赊购或货币市场借款边界。 |
| `production.RebindInstanceOperator` | **未实现**；RF4b 仍是对 organizer source 迁移的临时整源跳过（本跑 `MIGRATION_ORGANIZER_REBIND_UNSUPPORTED=752`）。 |
| 完整 v2 S3/S4/S7 | 仍为部分实现：单一 `DailyDemandBill`、完整标准农业聚合、实际盈余卖盘等没有全部落地。 |
| 经济结果 | 人口仍净减；结束债务 32.6M；地价末值为 0；市场赊购/运力故障仍显著（DEBUG 跑 `MARKET_DEFERRED_PAYMENT=5,149`、`HAUL_SERVICE_DELIVERY_FAULT=10,575`、`MERCHANT_CAPACITY_LANE_TRUNCATED=86,386`）。 |

本报告不把“360 行 OK”写成“经济系统已调平”，也不把 `debtContractCount`、`marketUnfilled`、`seedInput`
的槽位/版本/累计口径当成业务人数或单日发生额。

## 7. 验证边界

- 已跑：当前最终代码 360 tick；完整 `tools/gate.sh full`；economy/app 全量测试。
- 未跑：F5 债务分源/信用边界；完整 v2 S0–S8 验收；GUI/MCP 手工验收；跨设备复跑。
- 未提交、未推送；`.superpowers/sdd/**` 是本机留痕，不在 git。
- 主树 `/home/cna/SimulatorMosire` 未触碰。

## 8. 结论

**RF6a/RF6b 责任区验收通过**：当前代码 360/360 OK，完整门禁全绿，RF6a/RF6b 的数值行为与 RF4b 基线逐字节一致。
但整体经济链路仍有 F5 债务分源、信用边界、organizer 重绑和完整 v2 语义未完成，不能宣称最终经济调参验收。
