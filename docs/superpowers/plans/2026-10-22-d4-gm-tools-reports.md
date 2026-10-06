# D4 施工契约：GM 参数工具 + 跨区上报

> 批次：D4（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：D3 已推送（`570f555a`）。用户口径：GM 与决策人共用读工具；GM 全图；
> **中央只能看直辖区，跨区原始数据必须走上报**；GM/决策人工具端强化后续继续补。

## 1. 目标

1. 把 P4a/P4b 已落盘的参数面补成 GM 窄工具（preview/apply/read，拒绝具名）；
2. 实现跨区**上报**：决策人发给 GOV/NATION/GM 的报告写 sd INFO 覆盖层；决策人只读自己收到/隶属收到的报告，GM 读全部；
3. 新命令补 `CatalogTool.PAYLOAD_HINTS`；GUI 面板留 D4.1（本批只保证工具/Catalog；用户已说工具端强化后续专门搞）。

## 2. GM 工具

### 2.1 `simos.gm.periodicAdjustment`（P4a 规则）
- 动作 `action = list | due | upsert | remove`（缺省 `list`）。
- `list`：按 id 升序读 `EconomyData.periodicAdjustments()`；过滤 `payer?`/`payee?`/`reason?`。
- `due`：按当前 tick 读**将到期**规则（口径与 `PeriodicHouseholdAdjustmentExecutor` 逐字一致：
  `day >= startsOnDay && (expires 空 || day <= expiresOnDay) && ((day-startsOnDay) % periodDays == phaseDay)`）。
- `upsert` / `remove`：载荷字段与 `economy.UpsertHouseholdPeriodicAdjustment` /
  `economy.RemoveHouseholdPeriodicAdjustment` 一致；`preview`（缺省 true）只做形状/引用/到期窗口校验并返回
  `{before, after, commandsPreview}`；`preview=false` 提交命令（一条 revision）。
- `upsert` 构造 `HouseholdPeriodicAdjustment` 在 preview 阶段纯构造校验；`remove` 不存在 ⇒ 具名拒。
- 只在 GM 桶；资源声明 `economy` + `sd`？实际只写 economy 命名空间，声明 `economy` UNRESTRICTED。

### 2.2 `simos.gm.armyPayPolicy`（P4b）
- 动作 `view | apply`（缺省 view）。
- `view`：`unitId` 必须存在且带 `ArmyFormation`；返回当前 `militaryPayPolicy`（排期 + 三张逐户表）。
- `apply`：载荷字段与 `unit.SetArmyPayPolicy` 一致（`periodDays/phaseDay/startsOnDay/expiresOnDay?/
  grainPerHouseholdPerCycle?/clothPerHouseholdPerCycle?/moneyPerHouseholdPerCycle?/enabled?`）；
  preview 纯构造 `MilitaryPayPolicy` + 校验家户键 ⊆ `Unit.households`，返回前后对比；apply 提交命令（一条 revision）。
- 只在 GM 桶；资源声明 `unit` UNRESTRICTED。

### 2.3 `simos.gm.vitalRates`（Social 生死率）
- 动作 `view | setGlobal | setHousehold`（缺省 view）。
- `view`：`householdId?`；不带 ⇒ 读全局默认 `SocialData.vitalRates().globalDefaults()`；
  带 ⇒ 读该家户覆盖 + 生效值（覆盖 ?? 全局）。
- `setGlobal`：新增命令 `social.SetGlobalVitalRates`（payload `{rates:[{bracketId,sex,birthRatePerMillionPerTick?,
  deathRatePerMillionPerTick?}], reason}`），整体替换 `SocialVitalRates`（`SocialData.withVitalRates`），
  handler 放 `simos-social/spi`，在 `Shell` 注册；preview 纯构造；apply 提交命令。
- `setHousehold`：复用既有 `social.SetHouseholdVitalRates` 语义（整体替换家户覆盖），preview/apply 同制。
- 只在 GM 桶；资源声明 `social` UNRESTRICTED。

### 2.4 `simos.gm.adjustPopulation`
- 动作 `add | remove | transfer`（与既有 `simos.social.household.members` 逐字同语义）。
- 实现：薄适配器复用 `SocialHouseholdMembersTool` 的纯推导/批组装（不另写第二份人口口径）；
  可以直接持有它的一个实例并转发 `execute`（系统上下文 + 合法 GM 参数），确保只写 social 命名空间。
- 只在 GM 桶；资源声明 `social` UNRESTRICTED；preview/apply 同既有工具。

## 3. 上报机制

### 3.1 `simos.sd.report`（决策人写）
- 载荷 `{to:{kind:GOV|NATION|GM, id?}, subject, body, evidence?, tick?}`。
- 身份从 `context.identity()` 派生（发送人 = 当前决策人，不由载荷自报）。
- 生成一条 `sd.PutInfo`：
  - 地址 `sd:doc.report-<sender>-<tick>-<seq>`，key `"report"`；
  - value = JSON 字符串 `{from, to, subject, body, evidence?, tick}`；
  - `tags`：`to.kind=GM` ⇒ 空；`to.kind=GOV|NATION` ⇒ 空（归属匹配由 affiliations 承担）；
  - `affiliations`：`GOV` ⇒ `[{"kind":"gov","id":unitId}]`；`NATION` ⇒ `[{"kind":"nation","id":nationId}]`；`GM` ⇒ 空；
  - `tick` 缺省 = 当前 tick（不得记未来）。
- `to.kind=GM` 的报告无归属；只有 GM 的 `simos.sd.reports` 能看到。
- 一条 `sd.PutInfo` 命令 = 一条 revision；决策人只能写自己的报告（`requireAll` 用 `sd:decision-maker/<自己>` 域，不采信载荷）。

### 3.2 `simos.sd.reports`（读）
- 共享读工具（决策人桶 + GM 桶；不标 `GmOnlyRead`）。
- 决策人身份：只返回 `key="report"` 且（`tags` 含自己 **或** `affiliations` 含自己归属 **或** 自己是 sender）的条目；
  GM 身份（`decisionMakerIdOf` 为空）：返回全部。
- 参数 `{from?, to?, subjectContains?, fromTick?, toTick?, limit?}`；`limit` 默认 20 上限 200，超限具名拒。
- 不因读报告获得对方 hex/家户原始读权；报告内容由发送方负责。
- 决策人工具面/白名单同步：`simos.sd.report` / `simos.sd.reports` 加 `DecisionCallerFactory.WHITELIST`；
  `simos.sd.reports` 也进 GM 读桶。

## 4. Catalog / 注册 / GUI

- 新命令 `social.SetGlobalVitalRates` 必须有 `CatalogTool.PAYLOAD_HINTS`（否则 Shell 装配炸）。
- 新 GM 工具加入 `SimosToolSource.addGmWrites`；`simos.sd.reports` 加入 `readTools`（决策人桶共享）。
- 新决策人工具加入 `SimosToolSource` 决策桶 + `WHITELIST`。
- GUI 决策→审批/上报面板留 D4.1（本批文档标注；不阻塞 D5）。

## 5. 门禁 smoke（控制方执行）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0。
- `/tmp/D4Smoke.java`（真实 small-world Shell，GM + 两个决策人）：
  1. `simos.gm.periodicAdjustment` upsert/preview/apply/remove/due；断言 preview 零 revision、apply body 变、remove 后消失；
  2. `simos.gm.armyPayPolicy` view/apply（建一个 ArmyFormation 单位）；
  3. `simos.gm.vitalRates` setGlobal/setHousehold/view；
  4. `simos.gm.adjustPopulation` add/remove/transfer preview/apply；
  5. 决策人 A `simos.sd.report` 给 GM、给 B 所属 GOV；GM 看到全部；B 只看到发给它归属的；A 看到自己发的；C 看不到；
  6. 负例：未知 plan/单位/家户、坏 rates、跨权限 ⇒ 具名拒且零 revision。
- 不跑 test/test-compile/verify；不 commit/push。

## 6. 实际 smoke 证据（2026-10-22）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → rc=0（`/tmp/d4-parent-gate.log`）。
- `/tmp/D4SmokeSelf.java`（临时，真实 small-world Shell）54 PASS / 0 FAIL / `D4_SMOKE=OK`
  （`/tmp/d4-parent-smoke2.log`）：
  - `simos.gm.periodicAdjustment` upsert preview 零 revision / apply / list / due / remove / 不存在具名拒；
  - `simos.gm.vitalRates` setGlobal + setHousehold 的 view/preview/apply；
  - `simos.gm.adjustPopulation` add/remove/transfer preview+apply 且人口读数对；
  - `simos.gm.armyPayPolicy` view/apply + 未知家户具名拒零 revision；
  - 决策人 A/B/C 上报：A 自己 2 条、B 按 GOV 归属 1 条、C 0 条、GM 2 条；from/subject 过滤、limit>200 具名拒；
  - 读报告不授予跨区原始数据读权（只读 sd INFO 报告条目）。
- 边界：`simos.gm.periodicAdjustment` upsert preview 比 handler 严——payer/payee 必须在 Social 存在；
  `SetGlobalVitalRatesHandler` 标 `GmOnlyCommand`（全局参数是 GM 的活）；GUI 面板留 D4.1。
