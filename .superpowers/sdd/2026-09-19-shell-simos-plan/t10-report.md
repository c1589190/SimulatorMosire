# M5 T10 报告 —— AgentBinding 模型层（AgentAttachPolicy + map/unit 策略 + BindingRegistry）

> **任务**：M5 T10（`docs/superpowers/plans/2026-09-19-shell-simos-plan.md` T10；spec §6 / §〇.2 S6 / §十一 R8）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t10`，分支 `m5/t10`，基线 `61f5002`（PREFLIGHT 已自证）
> **日期**：2026-09-19
> **范围**：只有 `simos-util/**/spi/AgentAttachPolicy.java`、`simos-map/**/spi/**`、`simos-unit/**/spi/**`、
> `simos-app/**/binding/**` + 测试/证据。**未动** `Shell`/`ShellConfig`/`GuiServer`/`ApiViews`/`QueryService` 或任何其它模块/spec/计划/台账。

---

## 1 交付物

| 文件 | 内容 |
|---|---|
| `simos-util/.../util/spi/AgentAttachPolicy.java` | **新增 SPI**：`namespace()` + `canAttach(Address, ResolveContext)`。模块声明"哪些主体可绑决策人"，app/Core 只问、不 `instanceof` |
| `simos-map/.../map/spi/MapAgentAttachPolicy.java` | **任意存在的 `Region` 可绑**（取代说明见 §4.1）；判定委托 `MapResolver`，`typeName == "Region"` 才算 |
| `simos-unit/.../unit/spi/UnitAgentAttachPolicy.java` | **任意存在的 `Unit` 可绑**；判定委托 `UnitResolver`，`typeName == "Unit"`（`Equipment` 落选） |
| `simos-app/.../app/binding/BindingId.java` | `record BindingId(String)`，非空白；`generate()` 出随机 UUID |
| `simos-app/.../app/binding/AgentId.java` | `record AgentId(String)`，强制 `agent:<id>`（C21） |
| `simos-app/.../app/binding/DecisionScope.java` | `record DecisionScope(String)`，不透明标签、非空白 |
| `simos-app/.../app/binding/BindingMode.java` | `enum {SUGGEST_ONLY, AUTO_APPLY}` |
| `simos-app/.../app/binding/AgentBinding.java` | `record(BindingId, AgentId, SubjectId, DecisionScope, BindingMode, AgentPermissionSet)`（spec §6 形状，全字段非 null） |
| `simos-app/.../app/binding/BindingRegistry.java` | `bind/unbind/list/bySubject/byAgent`；bind = canonical 化 → 问策略 → 查重 → 落记录；**无执行语义** |

**测试**：`simos-app/.../app/binding/BindingRegistryTest.java`（**12 条**）。

**不做**（spec §6 边界）：绑定后的执行（决策人产出 → Command）归 Brain/MainMosire；本仓只到"记录 + 可绑性 + 查询"。

**未接线**（派单明令）：`BindingRegistry` **不接进 `Shell`**——保持与兄弟工作树文件不相交；它是独立可注入组件，装配归 T11/T12。

---

## 2 实测数字

### 定向（`logs/targeted-post-mutation.log`）

`./mvnw -pl simos-app,simos-map,simos-unit,simos-util -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BindingRegistryTest' test`
⇒ `rc=0`，`BindingRegistryTest` **12/12** 绿。

### 全量（`./mvnw clean verify`，`logs/full-verify.log`）

`rc=0`；**801 = 170 / 255 / 45 / 131 / 150 / 50**（util / map / social / unit / core / app）——**7/7 reactor entries SUCCESS**；
`BugInstance size is 0` **×6**；`^[ERROR]` **0 行**。

**对基线 789 = 170/255/45/131/150/38 的逐模块差**：

| 模块 | 基线 | 现在 | Δ | 来源 |
|---|---|---|---|---|
| util / map / social / unit / core | 170/255/45/131/150 | 同 | 0 | 未动（策略类无独立用例，只随 app 用例被驱动） |
| app | 38 | 50 | **+12** | `BindingRegistryTest` 12 条 |
| **合计** | **789** | **801** | **+12** | |

前五个模块一个不动，增长恰好来自唯一一个新用例类，**与预期逐条相符**。

---

## 3 变异自证（1 轮，九道门禁）

| m | 目标 | 变异 | 期望红 | 实测红 |
|---|---|---|---|---|
| m1 | `BindingRegistry.bind`（R8 策略门） | **删掉整段策略咨询**（`policies.get(...)` + 无策略抛 + `canAttach` 抛），bind 变成什么都收 | "策略拒绝 ⇒ 拒绝" / "无策略命名空间 ⇒ 拒绝" | ✅ `refusesANamespaceWithoutAPolicy:126`、`refusesWhenThePolicyRefuses:138`、`refusesWhenTheRealMapPolicyRejectsANonRegion:148`、`asksThePolicyWithTheCanonicalAddressNotTheHumanForm:116`（共 4 红） |

**九道门禁逐项（`logs/m1.log`）**：

| 门禁 | 实测 |
|---|---|
| 字节不同 | orig `89f32916075c3e1c799c4881a556e857` → mutant `487b7a6f60676a0850fa75a4e9ba39e9` |
| 干净世界 | `worktree_before == orig_md5`（`89f3…`） |
| 白名单推成**目标类名** | `cp` 到 `BindingRegistry.java`（非变异体文件名） |
| `COMPILATION ERROR`=0 且 `Tests run:`≥1 | 0 / 2 行 |
| surefire 报告 mtime 落本轮 | 1789769178 ≥ round_start 1789769173 |
| 红点落**被保护断言** | 是（4 条，含 R8 的两条判据路径） |
| `cp` 逐字节还原（非 `git checkout --`） | `worktree_restored == orig`（`89f3…`） |
| 轮内 md5 追加进日志**自身** | "装置补记"在案（orig/mutant/restored 三值） |

> ★ **m1 的红为何是"真"红**：删掉策略咨询后，`bind` 在"无 unit 策略"与"替身策略拒绝"两种输入下都**成功**落记录 ⇒ 断言 `assertThatThrownBy` 因"没抛"而红； `asksThePolicyWithTheCanonicalAddress...` 因替身策略**从未被调用**、记录表为空而红。四条都落在被保护行为上，不是因为编译/加载失败（`COMPILATION ERROR`=0、`Tests run: 12`）。

---

## 4 偏离 / 取代说明

1. ★★ **`Region 且 type=Nation` ⇒ "任意 Region"（必报项）**：spec §6（及总纲 §5.5 的例子）写 `MapAgentAttachPolicy`：**Region 且 `type=Nation`**。
   但 M2 落地的 `Region(RegionId, String, Set<HexCoord>, RegionBoundary, RegionMeta)` 与
   `RegionMeta(String color, String tag, String description, String annexedBy)` **没有 `type` 字段**，"Nation" 在领域类型里不存在
   （已当场 `cat` 两个源文件核实）。按控制器裁定，实现**老实版本**："可解析为已存在 `Region` 即可绑"，**不臆造字段**。
   这是"spec 的例子 ←→ 已落地领域类型"之间的真缺口，需 spec 回填（归控制器）。
2. **`AgentAttachPolicyTest` 未建**（派单允许）：接口无可独立测的行为；两条策略由 `BindingRegistryTest` 的真策略路径
   （`map:Map1:region.r1` 通过、`map:Map1:hex.1_1` 被拒、`unit:u-1` 通过）驱动。
3. **策略查表用 canonical 地址的首段命名空间，不是 `SubjectId.namespace()`**：`MapResolver` 给 Region 的 `SubjectId.namespace()` 是
   `"map.region"`，而 `AgentAttachPolicy.namespace()` 按 spec 是**地址首段** `"map"`。故 `bind` 先解析、再取 canonical 地址的
   `namespace()` 查表（R8 "canonical 化后再问"）。这一点若写错（用 `SubjectId.namespace()`）会让 map 策略永远查不到、全部被拒。
4. **多解/零候选的拒绝语义是就地补的**：spec §6 只说"先解析（canonical）→ 问 policy"，未定义多解。本实现**宽进严出**：
   0 候选 ⇒ 拒绝；>1 候选 ⇒ 拒绝（要求调用方给 canonical 地址）。理由：把决策人绑到"同名多解"的主体上不可接受。
   已由 `refusesAnUnresolvableSubjectAndAnUnregisteredNamespace` 钉住 0 候选；多解分支随 `UnitResolver` 链式多解才可达，**当前无用例**（见 §5）。
5. **拒绝一律 `IllegalArgumentException`**：spec §6 未给异常契约，不为它新造异常类型；消息可读且含命名空间/地址。
6. **`AgentId` 强制 `agent:` 前缀**：spec 写"形如 `agent:<id>`（C21 形态）"，故构造期校验，非该形态即抛。
7. **环境**：PREFLIGHT 指定的 `/home/cna/SimulatorMosire/.claude/worktrees/m5t10` 与分支 `m5/t10` **在派单时不存在**
   （`git worktree list` 无此项）。据"base commit `61f5002`、branch `m5/t10`"就地 `git worktree add -b m5/t10 … 61f5002` 补建，
   随后 PREFLIGHT 的 `log --oneline -1` 逐字命中。**不是**偏离设计，是环境补建，如实记。

---

## 5 我未能核实的

1. **生产装配未验**：`BindingRegistry` 未接 `Shell`（明令），故"生产上注册哪几条策略、bind 时的 `ResolveContext` 从哪来（哪条分支/哪个 head）"
   完全未验——归 T11/T12。
2. **多解拒绝分支**：`canonicalize` 的 >1 候选分支**无对应用例**。构造它需要 `UnitResolver` 链式同名多解（两个同名同层单位），
   本夹具只有一个单位。该分支的代码路径**从未被执行**（如实记）。
3. **绑定不持久化**：`BindingRegistry` 是**纯内存**；spec §6 未要求落盘，但它写"记录"，是否要跨进程存活**未裁决**。M5 未做。
4. **并发安全未验**：M5 单实例假设（spec §3.4），本类不加锁、无并发用例。
5. **`AgentPermissionSet` 的不可变性是假设**：`AgentBinding` 按引用存它；**本仓未验**它是否真的不可变。
   SpotBugs 未报 `EI_EXPOSE_REP`，但按本项目形态 6/7，**"未报"只在它跑过的那个类集下成立**，不当"它干净"的证据。
6. **策略对装配故障的传播未单独验**：`MapAgentAttachPolicy`/`UnitAgentAttachPolicy` 委托 resolver，缺对应切片时 resolver 抛装配故障；
   该传播路径随 `BindingRegistryTest` 的正常夹具不会触发，**未单测**。
7. **全量 verify 与变异轮的先后**：`full-verify.log` 生成于变异**之前**；变异轮以 `cp` 逐字节还原（md5 实测 `89f3…` 与原一致），
   故该绿轮对**还原后的字节**仍成立。变异后补跑定向轮（`targeted-post-mutation.log`）12/12 绿，清掉 `target/` 里的变异体 `.class`。

---

## 6 证据索引（`.superpowers/sdd/2026-09-19-shell-simos-plan/t10-evidence/`）

| 路径 | 内容 |
|---|---|
| `logs/full-verify.log` | `./mvnw clean verify` 全量绿：801 = 170/255/45/131/150/50，BugInstance 0 ×6，ERROR 0 |
| `logs/targeted-post-mutation.log` | 变异还原后定向轮：`BindingRegistryTest` 12/12 绿 |
| `logs/m1.log` | R8 策略门变异轮（含"装置补记"自指段：orig/mutant/restored 三处 md5） |
| `mutants/orig/BindingRegistry.java` | 原件备份（`89f32916075c3e1c799c4881a556e857`） |
| `mutants/m1.BindingRegistry.java` | 变异体（删除整段策略咨询；`487b7a6f60676a0850fa75a4e9ba39e9`） |
| `mutants/mut-round.sh` | 变异装置（九道门禁，适配 simos-app + BindingRegistryTest） |

---

## 7 变更文件 + 提交

见 §1 九个 main 类 + 一个测试类；证据/报告为 untracked 新增。提交在 `m5/t10`，**不推送**。
