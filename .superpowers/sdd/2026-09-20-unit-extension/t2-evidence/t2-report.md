# T2 执行报告 —— 三态速度（Unit 扩容计划 §三 T2 / spec §三.2 E3 / P5 / P6 / P13）

日期：2026-09-20。worktree：`.claude/worktrees/uet12`，分支 `ue/t12`。依赖 T1（`4e18549`）。

## 一、落地内容

修改 main：
- `UnitStatus.factorPerMille()`：`MOVING=1000` / `RESTING=500` / `ENGAGED=250`（P5 v1 值，可调非永久）
- `Unit.effectiveSpeed()` = `max(1, floorDiv(speed × factorPerMille + 500, 1000))`（**缺口 U1 的 clamp 裁定**：与 `TerrainMovementCost.scale` 同款四舍五入后 clamp 到 1，满足 `Movement.speedAtDeparture ≥ 1`）
- `UnitOperations.planRoute`：`Movement.speedAtDeparture` 由 `unit.speed()` 改为 `unit.effectiveSpeed()`（**只改这一处**）
- `UnitOperations.setStatus` + 私有 `withStatus`（第 13 参 status 之外全部原样带过）
- `UnitPayloads.requireStatus` / `optionalStatus`（未知串 ⇒ 抛，handler 折拒绝）
- `CreateUnitHandler`：可选 `status`，缺省 `MOVING`

新增 main：
- `simos-unit/.../spi/SetStatusHandler.java`（`unit.SetStatus`：`id, status`）

## 二、判据实测值

| 判据 | 实测值 |
|---|---|
| `factorPerMille` 逐值 | `1000 / 500 / 250`（`UnitStatusTest`） |
| 三值互不相等且 RESTING/ENGAGED < MOVING | 绿 |
| `speed=8` 的 `effectiveSpeed` | `8 / 4 / 2`（`UnitTest.effectiveSpeedScalesByStatusFactor`） |
| clamp：`speed=2` ENGAGED | `1`（`effectiveSpeedNeverDropsBelowOne`） |
| 同路线三次 `planRoute` 的 `speedAtDeparture` | `8 / 4 / 2`（`planRouteFreezesTheStatusScaledSpeed`，m1/m2 靶子） |
| 在途 `SetStatus` 后 | `speedAtDeparture` 与整条 `Movement` 一字不变（m3 靶子） |
| `unit.SetStatus` 未知 status ⇒ Rejected | `SetStatusHandlerTest.unknownStatusStringIsRejected` + `UnitCommandHandlersTest.setStatusRejectsAnUnknownStatus` 绿 |
| `unit.SetStatus` 未知 id ⇒ Rejected | 绿 |
| `CreateUnit` 缺省 `status == MOVING` | 绿（既有 create 断言补 `status==MOVING`） |
| `CreateUnit` 显式 `"RESTING"` ⇒ RESTING | 绿 |
| `CreateUnit` `"SLEEPING"` ⇒ 拒 | 绿 |
| `MovementStatus` 与 `UnitStatus` 正交 | `UnitMovesTest.unitStatusIsOrthogonalToMovementStatus` 绿；`UnitMoves.java` 源码零改动 |
| **simos-unit 计数** | **167** = T1 的 148 + 19（UnitOperationsTest +3 / UnitTest +2 / UnitMovesTest +1 / UnitStatusTest +2 / UnitCommandHandlersTest +5 / SetStatusHandlerTest +6） |

`util/map/social` 未动；`simos-app` 未动（T2 不注册 handler，注册归 T9）。

## 三、缺口 U1 的裁定（执行期落地）

`effectiveSpeed = max(1, floorDiv(speed × factor + 500, 1000))`。理由：`Movement.speedAtDeparture ≥ 1` 是硬约束，而 `speed × factor / 1000` 在 `speed` 小、状态慢时可为 0。判据夹具用 `speed=8` 保证三档可区分（8/4/2），`speed≤2` 的用例专门钉 clamp。

## 四、变异轮（九道门禁，同 T1 装置）

| # | 变异体 | 靶子 | 实测 | 红点 |
|---|---|---|---|---|
| m1 | `factorPerMille` 三档都返回 `1000` | 三档速度可区分 | **KILLED** rc=1 / Failures 5 / compile_errors 0 | `UnitOperationsTest:264`、`UnitTest:69:76`、`UnitStatusTest:19:25` |
| m2 | `planRoute` 用 `unit.speed()`（恒 MOVING 口径） | RESTING/ENGAGED 出发速度 | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitOperationsTest:264` |
| m3 | `withStatus` 顺带按新状态改写 `Movement.speedAtDeparture` | 在途不回溯 | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitOperationsTest:283 [在途不回溯]` |

**存活：0。** 三轮 `restored_md5 == orig_md5`。日志：`logs/m1.log`~`logs/m3.log`。

## 五、我未能核实的

1. **`factorPerMille` 数值尚无外部依据**：1000/500/250 是 P5 的 v1 取值（可调），未经真实平衡检验。
2. **`effectiveSpeed` 的舍入口径只与 `TerrainMovementCost.scale` 形似**：`moveCost` 恒为 1000 整倍时 `+500` 永进位；这里 `speed × factor` 不保证是 1000 整倍（`factor=250`、`speed` 奇时），故 `+500` 是**真四舍五入**——**未与任何既有口径对拍**（无既有可比对象）。
3. **真档对旧 `Movement` 的影响未测**：旧档里的 `speedAtDeparture` 是历史冻结值，本次改动只影响**新下达**的路线；真档冒烟归 T10。
4. **`unit.SetStatus` 未注册进 `Shell`**（归 T9），故端到端冒烟未在本任务做。
5. **SpotBugs 未单独跑**（归关账轮）。

## 六、执行期取代说明

无。计划 §三 T2 七步、文件与判据逐条落地；U1 按计划 §六建议表裁定（clamp 到 1）并记入台账。
