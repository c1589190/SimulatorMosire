# 架构调整期「已知红点台账」（2026-10-12 起）

> **为什么要有这本**：用户 2026-10-12 重申 §三.0「**测试环节放到最后**，反正这是一轮总的架构调整」
> ⇒ 批次之间**不再插测试批**，每批只跑 `compile` + `spotless:check` + `spotbugs:check`（静态门禁）。
> 但架构大改会**成批**让既有测试编译断/断言红 ⇒ **必须逐批记账**，否则最后的统一测试阶段**无法归因**
> （分不清"老架构老测试该删"与"新代码弄坏了"）。
>
> **判据**：**护栏当场修**（`GovRoundTripTest` / `ArmyRecordCombatRevisionTest` 这类防静默漂移的）；
> **旧测试攒台账**（钉旧机制的、夹具喂错面的）。

## 已结清（不算红点）

| 批 | 提交 | 内容 | 处置 |
|---|---|---|---|
| 1.7 | `f3458cea` | 9 个测试文件因 `GmOnlyCommand` 删除而**编译断** + 3 个用例因 `sd.` 前缀规则删除而**红** | ✅ **已在同批结清**（2026-10-12）：9 处改 `isNotInstanceOf(DmUsableCommand.class)`；3 个用例迁移到新契约（夹具改喂派生面、自指保护改端到端验证），`DM_EXPECTED` 一字未放宽 |

## 待最后测试阶段处理

### 批次 2（hex 属性表，`HexCell` 加 `areas` 组件）—— 预期红（断言级，非编译级）
`test-compile` 实测 rc=0（0 ERROR）⇒ 下面全是**断言**红，不是编译断。

| # | file:line | 钉的旧形状 | 处置方向 |
|---|---|---|---|
| 1 | `simos-map/src/test/.../HexCellTest.java:49-52` | `hasSize(1)` + `containsExactly("height")` | 改成 `[height, areas]`（保留 `doesNotContain("terrain","edgeTags","riverMask")` 的判别力） |
| 2 | `simos-map/src/test/.../RegressionGuardsTest.java:168-172`（L2） | `containsExactly("height")` | 同上（该条本意"无 terrain/无连通性"仍成立） |
| 3 | `simos-app/src/test/.../tools/SimosToolsTest.java:985` | `hasSize(142)` | ⇒ **143**（新增 `map.SetHexArea`） |
| 4 | 同上 `:1026` | `hasSize(166)` | ⇒ **167**（GM 桶读+写） |
| 5 | 同上 `:1143` | `hasSize(70)` | ⇒ **71**（窄写类扫描） |
| 6 | 同上 `:1850` | `hasSize(176)` | ⇒ **177**（`*Tool.java` NAME 扫描） |
| 7 | 同上 `:1154` | `NARROW_WRITE_NAMES` 名单 | 补 `map.SetHexArea`（68⇒69） |
| 8 | `simos-app/src/test/.../McpCoverageTest.java:147` | `EXPECTED_COMMAND_TYPES`(142) | ⇒ **143** |
| 9 | 同上 `:577` | `MINIMAL_PAYLOADS` | 补 `map.SetHexArea` 的最小可成功载荷（该表要求每个 catalog type 都有能**真提交成功**的载荷） |

**本批新增能力尚无测试**（"涂地形不抹编辑值" / "未赋值读地形默认" / "增量基数是读出值" / "GM-only 不动 DM 面"）⇒ 收尾期按规划 §4 批次 2 的五条负向判据补。

### 未核项（如实）
`AdjudicateTickToolTest:931 hasSize(97)` 本批未核（97 = 实现 `CommandTargets` 的 handler 数；新命令实现了它 ⇒ **预期 98**，收尾期实测）。

## 格式门禁修复（2026-10-12，控制方执行）

批次 1 / 1.6a 的**收工门禁只有 `compile`**（未跑 spotless）⇒ 基线积累了 **65 个格式违规文件**。
控制方 `spotless:apply` 全仓一次 ⇒ `spotless:check` **17/17 rc=0**。
**零逻辑证明**：去掉注释后 **65 个文件的代码 token 逐字节相同**（Python 注释剥离器，处理字符串/字符字面量）；
差异全在 javadoc 折行位置与类声明折行。
★ 教训（已入规划 §11）：**格式门禁属"提交前必跑"**；`spotless` 第一处 FAILURE 后其余模块全 SKIPPED ⇒ 修完必须重跑直到全绿。
