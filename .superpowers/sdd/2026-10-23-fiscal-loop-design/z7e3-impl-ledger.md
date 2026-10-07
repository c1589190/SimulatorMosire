# Z7e-3 实现台账：`gov.SetBudgetPolicy` 双模（PATCH 缺省 + REPLACE 开关）

- 责任区：**Z7e-3**（`simos.gov.setBudgetPolicy` 载荷语义修复；用户裁定 A+B）
- 契约来源：`docs/superpowers/specs/2026-10-23-fiscal-loop-design.md` §10（用户原话："AB同时应用吧"）+ 本报告
  `docs/superpowers/reports/2026-10-23-fiscal-loop-run7-vs-run6.md` §4（run7 首跑污染证据）
- 基线：`a968ad85`（Z7e-2 run7 报告，HEAD）
- 纪律：模块圈内改动（`simos-gov` 主/测 + `simos-app` 主/测 + 目录提示）；`GovBudgetPolicy` 值对象**零改动**
  （`mode` 不落状态）；新增 WARN = 0；一次一个 Maven（`tools/mvn-lock.sh`）

---

## 0. 结论先行

| 目标 | 结果 |
|---|---|
| A：payload 级 patch 语义（缺省字段保留现值） | ✅ `GovPayloads.budgetPolicy(payload, current)`；`mode` 缺省 = `PATCH` |
| B：显式 `mode:"PATCH"|"REPLACE"` 开关 | ✅ `GovBudgetPolicyEditMode`（词表大小写不敏感、词表外具名拒）；`REPLACE` = 旧整表替换 |
| 显式清空 | ✅ `orderedCategories:[]` 清空类别表；工资规则逐内层字段给出才覆盖 |
| 三面同源（命令/工具/目录） | ✅ 载荷 `mode` + 工具顶层 `mode`（覆盖 payloadJson）+ catalog `payloadHints`；preview 带 `editMode` |
| run7 污染场景回归 | ✅ 只传 `remittancePerMilleToSuperior`（500→0）不再清空 5 类预算 + 10/1 工资规则 |
| 旧语义兼容 | ✅ `mode:"REPLACE"` 逐值复现"缺省=空表/0/0/0" |

---

## 1. 实现点

| # | 文件 | 改动 |
|---|---|---|
| 1 | `simos-gov/.../gov/GovBudgetPolicyEditMode.java`（新） | `PATCH|REPLACE` 枚举 + `parse(String)`（`trim` + `toUpperCase(Locale.ROOT)`，词表外 `IllegalArgumentException("字段 mode 只支持 PATCH\|REPLACE，收到: …")`）；类注写明"不落状态、只影响本次解析" |
| 2 | `simos-gov/.../gov/spi/GovPayloads.java` | `budgetPolicy(JsonNode)` → **`budgetPolicy(JsonNode, GovBudgetPolicy current)`**（旧单参入口删除，编译器强制调用方给现值）；`editMode(JsonNode)`（缺省 PATCH）；`categoriesOf(JsonNode)`（整表解析保序）；`salaryRule(JsonNode, GovOfficialSalaryRule fallback)`（内层缺省 = fallback，PATCH 逐项保留 / REPLACE = 0）；`remittancePerMilleToSuperior` 缺省 = `base.remittancePerMilleToSuperior()`（REPLACE 的 base = 中性 ⇒ 0）；类注"缺省口径"同步 |
| 3 | `simos-gov/.../gov/spi/SetBudgetPolicyHandler.java` | `current = snapshot.state().budgetPolicy(unitId).orElse(null)`；`requestedMode = GovPayloads.editMode(payload)`；`policy = GovPayloads.budgetPolicy(payload, current)`；日志 `mode` 三值扩为 `created|patched|replaced|noop` + 新增 `requestMode=PATCH|REPLACE`；类注载荷语义改为双模（附 `"mode":"PATCH"` 示例） |
| 4 | `simos-gov/.../gov/spi/GovAdministrationProjections.java` | `BudgetProjection` 增第 6 组件 `requestedMode`；投影用 `keyExisted ? previous : null` 作合并基准（与 handler 同源）；`withMode(payloadJson, mode)` + 私有 `withField(...)`（`withUnitId`/`withMode` 共用唯一重写点）；类注同步 |
| 5 | `simos-app/.../tools/write/GovSetBudgetPolicyTool.java` | 新顶层参数 `mode`（`optionalText(args,"mode",null)` → `GovBudgetPolicyEditMode.parse`，给出即 `withMode` 覆盖 payloadJson 的 mode）；description/jsonSchema 增双模说明（保留 `remittancePerMilleToSuperior` + `0..1000` 子串）；`projectionView` 增 `editMode`；审批 gate 消息带 `mode=` |
| 6 | `simos-app/.../tools/read/CatalogTool.java` | `gov.SetBudgetPolicy` 提示改为双模：`mode?(PATCH|REPLACE，缺省 PATCH…)`，保留 remittance 说明 |

**语义表（冻结）**：

| 载荷 | PATCH（缺省） | REPLACE |
|---|---|---|
| `orderedCategories` 缺失/null | 保留现值（键不存在 = 空表） | 空表 |
| `orderedCategories` 给出（含 `[]`） | 整表替换（`[]` = 显式清空） | 同左 |
| `officialSalaryRule` 缺失/null | 保留现值（键不存在 = 0/0） | 0/0 |
| `officialSalaryRule` 给出 | 内层缺省字段逐项保留现值 | 内层缺省字段 = 0 |
| `remittancePerMilleToSuperior` 缺失/null | 保留现值（键不存在 = 0） | 0 |
| `remittancePerMilleToSuperior` 给出 | 覆盖（0..1000‰ 构造期判） | 同左 |

---

## 2. 测试

### 2.1 `simos-gov/.../spi/GovCommandHandlersTest.java`（+6 用例，17 全绿）

| 用例 | 判据 |
|---|---|
| `setBudgetPolicyPatchKeepsOmittedFieldsRun7Regression` | 先落 3 类 + 10/5，再只传 `remittance=500`、再只传 `remittance=0`：类别表/工资规则逐值保留，rate 0，变更集非空 |
| `setBudgetPolicyPatchClearsCategoriesOnlyWhenExplicitlyEmpty` | `orderedCategories:[]` ⇒ 类别清空、同载荷里工资规则仍保留 |
| `setBudgetPolicyPatchMergesSalaryRuleInnerFields` | `officialSalaryRule:{silverMilliPerCommittedHour:7}` ⇒ 10/7（grain 保留） |
| `setBudgetPolicyReplaceModeKeepsLegacyWholePayloadSemantics` | `mode:"REPLACE"` + 只传 remittance ⇒ 空表 + 0/0 + rate 500 |
| `setBudgetPolicyPatchOnFirstWriteFillsNeutralDefaults` | 键不存在 + PATCH + 只传 rate ⇒ 中性默认 + rate 500 |
| `setBudgetPolicyModeIsCaseInsensitiveAndRejectsUnknownVocabulary` | `"patch"`/`"replace"` 可识别；`"MERGE"` ⇒ `Rejected` 理由含 `mode` + `PATCH\|REPLACE` |

既有 11 例不改判据仍绿：`setBudgetPolicyDefaultsOnlyUnitIdToNeutralPolicy`（键不存在 ⇒ 中性）、
`setBudgetPolicyIdempotentReplayIsNoopEmptyChangeSet`（全字段重放 ⇒ noop）、拒绝族（重复类别/min>cap/未知类别/负值）。

### 2.2 `simos-app/.../tools/write/GovToolsZ6Test.java`（+1 用例，22 全绿）

| 用例 | 判据 |
|---|---|
| `setBudgetPolicyPatchKeepsOmittedFieldsAndReplaceModeStillWipes` | 创世中央政策 5 类：preview（缺省 PATCH）`editMode=PATCH`、`budgetAfter` 5 类 + 10 保留 + rate 500；apply 后源状态同判；再以工具顶层 `mode:"REPLACE"` 传"只 remittance" ⇒ 类别清空 + 0/0 + rate 0 |
| `setBudgetPolicyToolAndCatalogHintExposeRemittanceRate`（扩） | description / payloadJson schema / catalog hint 三面均含 `PATCH`+`REPLACE`；schema `properties` 含顶层 `mode` |

---

## 3. 变异自证（改→红→还原，md5 校验）

| 变异 | 改动 | 红点 | 还原 |
|---|---|---|---|
| A：缺省模式退化 | `editMode` 缺省 `return PATCH` → `return REPLACE` | `GovCommandHandlersTest` 3/17：`PatchKeepsOmittedFieldsRun7Regression`、`PatchClearsCategoriesOnlyWhenExplicitlyEmpty`、`PatchMergesSalaryRuleInnerFields` | md5 一致 |
| B：内层合并退化 | `salaryRule` 内层缺省 `fallback.…` → `0L` | `GovCommandHandlersTest` 1/17：`PatchMergesSalaryRuleInnerFields` | md5 一致（`9b00afdbd245a43ac22b45d1a122464c`） |

---

## 4. 全仓门禁

`tools/mvn-lock.sh clean verify`（提交前最后一次，耗时 4:04）：**BUILD SUCCESS**

| 项 | 结果 |
|---|---|
| Surefire | **3359 tests / 0 failures / 0 errors / 5 skipped**（基线 3352 ⇒ +7 = 本批新增 6 gov + 1 app） |
| SpotBugs | **15/15 模块 `BugInstance size is 0`** |
| 前端单测 | `[frontend-gate] OK tests=412 pass=412 fail=0` |
| Spotless | `spotless:apply` 后通过（首跑红点全在本次改动文件的 javadoc 换行） |

首次 verify 曾因 Spotless 红：`GovAdministrationProjections` / `GovPayloads` / `SetBudgetPolicyHandler` /
`GovCommandHandlersTest` 的 javadoc 换行；`spotless:apply` 只动这 4 个本次改动文件（`git status` 核对无旁溢）。

---

## 5. 边界

- **没做**：F3 中央铸币/发债；F4 全覆盖校准（spec §5）；`GovInfoTool` 读口不加 `mode`（`mode` 不是状态，读口无此字段是正确形态）。
- **未验**：决策人桶 + GM 审批链下的 `mode` 参数端到端（工具层与 GM 桶共用同一执行体，审批门禁不读 `mode`；由 `GovToolsZ6Test` 的 GM 桶用例 + Z6 审批链既有用例间接覆盖）。
- run 现场脚本（`setup_tick0.py` / `run_refusal.py`）仍按"完整载荷"写法固化，作为对本缺陷的双保险。
