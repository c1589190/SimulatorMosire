# T7 报告：回归路径（rejoin path）

> 计划：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`（T7 段）；spec：`docs/superpowers/specs/2026-09-20-unit-extension-design.md`（§二.2 / §二.3 / §四 表 / P7 / P8）
> 工作树：`.claude/worktrees/uet7`（分支 `ue/t7`，基线 `103f51d`）；范围：**只碰 `simos-unit`**
> 证据：`t7-evidence/`（本文件 + `logs/` + `mutants/`）

## §〇 落地（改了哪几个字节）

| 文件 | 角色 | md5（最终字节） |
|---|---|---|
| `simos-unit/.../unit/spi/SetRejoinTargetHandler.java` | **新增**：`unit.SetRejoinTarget` 命令（`id, target?`；缺省/null ⇒ 清） | `773a39795973b2d1b504f5b285b03ed0` |
| `simos-unit/.../unit/ops/UnitOperations.java` | `setRejoinTarget`（三个命令期判据）+ `rejoinRoute`（"有能力回归"每 tick 现算） | `35b5d7cdc482298b4b6153c4572b0e1e` |
| `simos-unit/.../unit/spi/UnitTimeParticipant.java` | **第二趟**：回归重规划（每 tick 重新装载一条 `Movement`） | `8e391f2244747d2bc3abd1597dbbaeee` |
| `simos-unit/.../unit/spi/UnitTimeParticipantTest.java` | +6 条用例（判据 3/4/5 + T5-L4 站点） | `4c4d623884b745dcf48ffb0dcd369fb7` |
| `simos-unit/.../unit/spi/UnitCommandHandlersTest.java` | +7 条用例（命令层判据 + T5-L4 站点） | `c9dee55d3fbe2196dafe9cace6f1939f` |

`git diff --stat`：**4 文件、+586/−3**（新增文件未跟踪，提交时显式列出）；`simos-app` **0 条改动**（`git status --porcelain -- simos-app` = 空）。

三条裁定按单执行、**未改 `Unit.java`**（U4）：`Unit.rejoinTarget` 是**第 13 个组件、普通 `Optional<UnitId>` 字段**（核对结论：`.md5` 未出现在 diff 里 = 一个字节没碰）。U5/U6 见 §四。

## §一 判据逐条实测值

**① 目标不存在 / 指自己 ⇒ 拒绝**（`UnitCommandHandlersTest.setRejoinTargetRejectsUnknownSelfAndSelfReference:1443`）
- 断言形态：`reason()` 助手**先**断言 outcome 是 `Rejected`（不是 `Applied`；m6 轮就是在这条上红，Observed 打出 `Applied[…rejoinTarget=Optional[u-404]]`），**再**取 reason 文本判两段（域层措辞 + 出错的那个 id）。
- 三种拒绝的 reason 文本（`UnitOperations.setRejoinTarget` 抛出的原文）：未知 id ⇒ `单位不存在: u-404`；未知 target ⇒ `回归目标不存在: u-404`；自指 ⇒ `回归目标不得是自身: u-1`。
- 三种拒绝后**输入状态逐字段未变**（不是"拒了但写了一半"）。载荷坏形状 6 条见 `setRejoinTargetRejectsMalformedPayload:1471`：断言用**载荷层专有**的整句片段（`必须是 JSON 对象` / `不是合法 JSON` / `id 必须是字符串` / `target 必须是字符串或 null` / `target 不得为空白`），**不判裸 token**——`target` 这个 token 在域层消息（"回归目标不存在"）里也出现，只判 token 的断言分不出是哪一层拒的（T4/T5 记下的同族缺口）。

**② 设 / 清往返闭合**（`…ClearsOnMissingOrNullTargetAndRoundTrips:1419`）
- `target` 缺失、显式 `null` **两种都清**；清完后状态 `.isEqualTo(base)`（逐字段）；空白串 `"  "` ⇒ `Rejected("target 不得为空白…")`。

**③ "有能力回归" = 假 ⇒ 不动**（三条各有一个子判据）
- **不可达**（`unreachableTargetLeavesTheStateUntouched:378`）：走廊 H11-H12-H13、唯一那条 H12→H13 被成本替身封掉 ⇒ 变更集 `isEmpty()`、`reads()`/`writes()` 都空（连差分都没有）。
- **状态不允许移动**（`restingUnitKeepsTheReferenceButDoesNotRejoin:402`，裁定 U5）：RESTING ⇒ `movement` 空、**`rejoinTarget` 仍是 `Optional[u-2]`（引用不清）**；再 `setStatus(..., MOVING)` 推到第 2 刻 ⇒ 行程 `[H11,H12,H13]` 回来了 ⇒ "封边前后确有路"的正面样本（两半只差 `status` 一项）。
- **目标位置不可确定**（`undeterminableTargetPositionLeavesTheStateUntouched:427`）：`u-2` 未挂靠且无自身位置（`effectivePosition` 空） ⇒ 零变更、`reads()` 空。

**④ 大编制移动后终点随动**（`rejoinEndpointFollowsTheTargetsCurrentEffectivePosition:324`）
- 第 1 刻：`u-2` 物化在 H12（前提断言） ⇒ `u-1` 的行程 `containsExactly(H11, H12)`、`departedAt = T0+1`、`speedAtDeparture = 2`、`mobilityAtDeparture = 500`；`writes` 与 `reads` 都含 `unit:u-1` + `unit:u-2`。
- 第 2 刻：`u-2` 到 H13 ⇒ `u-1` **重新装载** `containsExactly(H12, H13)`，末格 `.isEqualTo(targetNow)`（目标**当前** `effectivePosition`，不是持久字段）、`departedAt = T0+2`。
- **不瞬移**：`effectivePosition(u-1, T0+1) = H11`、`effectivePosition(u-1, T0+2) = H12`（终点只落进 `movement`，`position` 从未被写）。
- ★ 冻结形态（终点取自第一趟之前）由 **m1** 打红；瞬移形态由 **m7** 打红。

**⑤ 状态里没有"旧格 / 终点"这类持久事实的位置**（结构判据 `stateHasNoPlaceToPersistAnEndpointHex:478`）
- `UnitState` 的 record 组件 `containsExactly("units", "commandChains")`；`Unit` 的 13 个组件 `containsExactly(..., "rejoinTarget")`（**没有任何 hex 字段**）。
- 行为半边由 ④ 钉住（每 tick 现算 ⇒ 随动）；两条合起来 = §二.3 不变量 3。

**T5-L4 通则**（本轮新增的两个站点 + 既有守卫）
- 生产侧三处重建状态一律 `withUnits(...)`（第一趟末、第二趟的 `materialized`、返回前的 `target`）；**没有** `new UnitState(units)`。
- 新守卫 `rejoinTickKeepsCommandChains:449`（回归趟不吞链）+ `setRejoinTargetKeepsCommandChains:1512`（命令层只动引用、链进不了差分）。
- **T5 的守卫 `advanceKeepsCommandChainsWhilePositionAndMovementChange` 逐字节未改**：从 `HEAD:` 与工作树各截方法体比对 `True`（946 = 946 字节），且**在 m4 轮真的响了**（红点见 §二）——"没改"与"还在响"两件事各有痕迹。

## §二 变异轮（7 轮，十道门禁，**7 杀 / 0 存活 / 0 作废**）

装置：`mutants/{make-mutants.py, mut-round.sh, run-all-rounds.sh}`；选择器 6 个用例类（`UnitTimeParticipantTest,UnitCommandHandlersTest,UnitRoundTripTest,UnitOperationsTest,UnitStateTest,UnitCodecTest`）⇒ 每轮 `Tests run: 163`。每轮日志尾部有 **SELF-REFERENTIAL RECORD**（`orig/baseline/mutant/pushed/restored` 四个 md5 + ⑩ 自证 + `mvn_rc` + `compile_errors` + 报告 mtime + 失败清单原文）。汇总：`mutants/rounds-summary.txt`（由七份日志的自记块拼出，不是手写）。

| 轮 | 靶文件 | 变异 | ⑩ 逐片段自证 | 红点（原文） |
|---|---|---|---|---|
| t7m1 | `UnitTimeParticipant` | 终点取自第一趟物化**之前**的快照（= 冻结旧 hex） | revert：orig 0 / pushed 1 | `rejoinEndpointFollowsTheTargetsCurrentEffectivePosition:336` |
| t7m2 | `UnitOperations` | 不判可达、恒建 `[起点, 终点]` | revert：orig 0 / pushed 1 | `unreachableTargetLeavesTheStateUntouched:388`（主）+ `rejoinTickKeepsCommandChains:458`、`restingUnitKeeps…:413`（附带，见诚实清单 1） |
| t7m3 | `UnitOperations` | 删掉"状态允许移动"判据（U5） | delete：orig 1 / pushed 0 | `restingUnitKeepsTheReferenceButDoesNotRejoin:409` |
| t7m4 | `UnitTimeParticipant` | 返回前改回 `new UnitState(units)` | revert：orig 0 / pushed 1 | **T5 既有守卫** `advanceKeepsCommandChainsWhilePositionAndMovementChange:205` + 新守卫 `rejoinTickKeepsCommandChains:462` |
| t7m5 | `UnitState` | 加第三组件 `rejoinAt`（另留两参构造器 ⇒ 既有调用点照旧编译） | revert：orig 0 / pushed 1 | `stateHasNoPlaceToPersistAnEndpointHex:481`（新结构判据）+ `UnitRoundTripTest.changeSetHasExactlyTwoComponents:83` + `…everyUnitStateComponentParticipatesInTheChangeSet:61->100`（`IllegalState 未登记的组件: rejoinAt`） |
| t7m6 | `UnitOperations` | 删掉"目标存在"检查 | delete：orig 1 / pushed 0 | `setRejoinTargetRejectsUnknownSelfAndSelfReference:1446`（Observed：`Applied[…rejoinTarget=Optional[u-404]]`，**悬空引用被接受**） |
| t7m7 | `UnitTimeParticipant` | 回归写回时把**终点**也写进 `position`（瞬移） | revert：orig 0 / pushed 1 | `rejoinEndpointFollowsTheTargetsCurrentEffectivePosition:341`（"回归不瞬移"那条） |

十道门禁逐轮全 `verdict=OK`：① 起点等于基线 md5；② 变异体字节不同；③ 目标在白名单（三条规范路径）；④ 源树无规范名之外的 `.java`（`find … | wc -l` = 0）；⑤ `compile_errors=0`（七轮皆 0）；⑥ surefire 报告 mtime 落在本轮内 + 汇总行非空；⑦ 失败清单原文留全供人读；⑧ **逐字节 `cp` 还原**并复测 md5（七轮 `restored_md5` 与基线逐字节相同）；⑨ 自记六个 md5 追加进日志本身；⑩ **逐片段**判方向（不是按整文件计数）。

## §三 门禁与计数

- **模块门禁**（前台、`timeout=600000`）：`./mvnw -o -pl simos-unit -am verify` ⇒ **rc=0**，`real 3m09s`，反应堆 **4/4 SUCCESS**（父 12.2s / util 44.2s / map 1:11 / unit 54.8s），`BugInstance size is 0` ×3、`[ERROR]` **0 行**、Checkstyle 0 violations ×4、Spotless clean。日志 `logs/module-gate-verify.final.log` + `.rc.txt`。
- **`simos-unit` 计数：233 → 246（+13）**＝ `UnitTimeParticipantTest` 6 条（16/16 绿）+ `UnitCommandHandlersTest` 7 条（71/71 绿）。另两模块 util **170** / map **362**（`-am` 带上的）。
- **前端**：`node simos-app/src/test/js/run-gate.cjs` ⇒ rc=0、`tests=88 pass=88 fail=0`（`logs/frontend-gate.final.log`）。★ 这是"**没被改**"的复核：`simos-app` 零改动（T7 范围外）。
- 两轮门禁（A = 断言集合的最终形态 / final = 最终字节）逐模块对差：三模块 SUCCESS 相同、计数相同（170/362/246）；差异只有墙钟时间。**生产字节在两轮之间逐字节相同**（§〇 的 md5，且当场 `md5sum -c mutants/baseline-md5.txt` 三个文件全 OK）⇒ **变异轮证据的对象没有被改掉**。

## §四 裁定在案（U4 / U5 / U6 的核对结论）

| 裁定 | 落实位置 | 判据 |
|---|---|---|
| **U4** 存储 = `Unit.rejoinTarget: Optional<UnitId>` 普通字段 | 已存在（T1 落的），T7 **未改 `Unit.java`** | `setRejoinTargetSetsOnlyTheReference:1395`：变更集非空、位置与行程逐字节等于 base、`commandChains().changed()` = false |
| **U5** "状态允许移动" = `status == MOVING` | `UnitOperations.rejoinRoute` 的 `if (unit.status() != UnitStatus.MOVING) return Optional.empty();`（注释标 `裁定 U5`） | `restingUnitKeeps…:402`（RESTING ⇒ 不回归、**引用不清**、回 MOVING 即恢复）+ `setStatusDoesNotClearTheRejoinTarget:1495` + m3 |
| **U6** 物化 = 每 tick 现算 + 写**新** `Movement`（`departedAt` = 本刻）；唯一持久事实是引用 | `UnitTimeParticipant` 第二趟（`withMovement` 只换 `movement`，13 组件其余原样） | ④ 的两刻逐值 + m1/m7 + ⑤ 的结构判据。"只走一格"的变体**未建**（按裁定不做） |

## §五 诚实清单

1. **m2 的杀点是 3 条，不只主杀点**：主杀点 `unreachableTargetLeavesTheStateUntouched:388`（期望零变更，实得一个凭空出现的 `Movement`）+ 两条**附带红**（`rejoinTickKeepsCommandChains` 与 `restingUnit…` 里 `IllegalArgument path 相邻格必须相邻：1_1 → 1_3`）。附带红是**被测行为真的坏了**（恒建 `[H11,H13]` 违反 `Route` 的相邻不变式），不是判据噪声 ⇒ 照报。附带红出现的用例恰是"起点与终点不相邻"的那些，而主杀点那条夹具是**相邻**两格（这正是设计它的原因：让"恒建"路线合法、把杀点落在断言而不是异常上）。
2. **m5 报两种形态**（"等价变异体"的实例）：形态① —— **被杀**，红在**结构判据 + T5/T1 的往返守卫**（`UnitState` 多一个组件 ⇒ `UnitRoundTripTest` 报 `未登记的组件: rejoinAt`）；形态② —— **回归场景的 6 条用例在 m5 下全绿**（那个字段谁都不读 ⇒ **行为层判不出来**）。⇒ 结论：**行为层判据对这一类变异体没有判别力**，但**另有能区分它的判据同时存在**（类型枚举 + 往返），故它不是存活项。**没有**为了造红而放松任何判据。
3. **我自己的一句注释被 m5 轮证伪，当场改了**：测试注释原写"本用例正是判据里唯一能区分它的那一条"，实测 `UnitRoundTripTest` 的两条也区分 ⇒ 改为实测口径（"本用例红，**并且**与 `UnitRoundTripTest` 的两条一起红；行为层判不出来"）。改后重跑了门禁（A/final 两轮，见 §三）。**这是本轮唯一一次"改被测文件 ⇒ 旧证据作废"**：作废的只有 A 轮那条门禁日志（留档不删，文件名里写明它是"断言最终形态"那一版），**变异轮证据不受影响**（变异轮的基线 md5 与两轮生产字节逐字节相同，且改后当场核过）。
4. `logs/targeted-first-run.log` 是**实现期的定向跑**（87 条 = 71 + 16），它跑在**加 anti-teleport 断言之前**的测试字节上；判据的最终计数由**门禁轮**给（71/16，同数）。引用判据一律以门禁轮为准。
5. **门禁耗时与环境**：本轮两轮模块门禁都在**前台**跑（`real 3m09s` / `3m29s`，`timeout=600000`），**没有被摘到后台、没有被杀轮**。`-am` 带上 util/map；`simos-app` 不在该门禁范围内（本轮它零改动）。
6. **装配注入口径**：`MovementCost` 与 `mapId` 仍是构造器注入（沿用 T6 的 U3 口径，`UnitTimeParticipant` 一行没改这两项）；`mapId` 只回显不可校验（M2/M3 挂起项，未变）。

## §六 缺口表（计划 / spec 与实现不一致处：**未改 spec、未改计划**）

| # | 缺口 | 现状与实测 | 建议归属 |
|---|---|---|---|
| G1 | spec §二.3 不变量 2 的**构造期**那一半：`disband` 之后不应留悬空 `rejoinTarget` | T7 的文件清单里没有 `disband`（`UnitOperations.disband:239`）⇒ **未加**这条前置（`disband` 现在的 op 里一处都不碰 `rejoinTarget`，这是读代码确认的，不是猜）。**运行期口径是安全的**（代码口径，非实测）：`rejoinRoute` 里 `state.effectivePosition(rejoinTarget, at)` 对**不存在的 id** 返回空 ⇒ 判为"位置不可确定" ⇒ `Optional.empty()` ⇒ 不回归、不写任何东西。★ 这条**没有被任何用例覆盖**（G5 同族） | 控制器裁（T8/T10 或挂账） |
| G2 | `UnitPayloads` 类注仍写"unit **十六个**命令 handler" | 现在 **18** 个（T6 +1、T7 +1）；该生产文件不在我的清单 ⇒ **未改** | T10 统一扫（与 T5-L6 / T4 的 token 断言清扫同批） |
| G3 | spec §9.1「不做 `REPLAN_EVERY_STEP`」 vs P8「每 tick 重规划」 | **两条轨道，不矛盾**：在途的**普通路线**照旧不重规划（§9.1 说的是它）；**回归轨道**每 tick 现算、每次产出一条**新** `Movement`（旧行程被替换）。已在 `UnitTimeParticipant` 类注写明两轨关系 | 若控制器认为 spec 需要回填一句话，请示下（我不改 spec） |
| G4 | T5-L4 的 1 参兼容构造器 `new UnitState(units)` 仍在 | T5 已把它归 **T10 裁**；T7 **未改 `UnitState.java`**（m5 轮改它的是**变异体**，原文件 md5 未变） | T10（本轮不改） |
| G5 | 回归与**在途普通路线**的交互**没有 spec 依据** | 现状：本刻既有在途 `Movement` 又有 `rejoinTarget` ⇒ 回归行程**替换**在途行程（`withMovement` 语义）。**没有**判据覆盖"是否应当等抵达/空闲再开始回归"这一策略问题 | 控制器裁（P8 只说"每 tick 重规划"，未说与在途路线的关系） |

## §七 我未能核实的

- 真档（19441 格）上的回归行为**未验**：判据全在合成小图（H11-H13 三格走廊 + 成本替身）上证的——与 T6 同一条开口项。
- G5 那条交互（在途路线被回归替换）**只有代码语义 + 无判据**；我**没有**构造"在途 + 回归同时存在"的用例。
- `Movement.speedAtDeparture` 用的是 `unit.effectiveSpeed()`（不是从 position 段派生当前速度）——**未与 M3 的口径逐值对拍**。
- 多单位 / 多链同时回归的规模未测（用例是两单位规模）。
- 前端 88/88 是**未改**的复核，不是"改过还绿"。
