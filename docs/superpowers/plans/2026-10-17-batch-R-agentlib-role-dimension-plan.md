# 2026-10-17 批 R（rev2）：角色维对接 —— 计划书（按审核报告修订）

> **分支**：`agentlib/dock-0.2.1` · **工作树**：`.claude/worktrees/agentlib-dock` · **基线**：`e26d49a4`（A+B 批已入账）
> **修订依据**：`docs/superpowers/reports/2026-10-17-batch-R-role-dimension-design-review.md`（五项必改 + §6 三裁定结论）。
> **状态**：rev2 计划书；**未动 R 的业务代码**。审核结论 = 无需再向用户追加裁定（见 §9），可直接开工。
> **另有一项与本批无关的独立硬约束**（单空格 system）：见 `docs/superpowers/specs/2026-10-17-single-space-system-message.md`，按批 S 单独执行（§8）。

---

## 0. 用户原话附录（逐字照录）

1. （A+B 驱动）「我只说一点，agentlib更新了许多可以复用的标准化Agent结构，你应该把目前ga模块的相关Agent实现，秉持着lib里能复用就复用的原则来进行重写和对接，懂我意思吗？可以开一个新分支方便保存开发进度和日后合并」
2. （本批）「先写个计划书出来」
3. （审核轮）「审核，Agentlib所在的项目就在~/ProjectMosire」
4. （审核结论落地轮）「阅读，重设计，看看有没有需要再裁定的，没有就开始」
5. （单空格 system，批 S 的依据）「我这边的要求是，系统上下文应该直接传一个空格，因为多种证据表面过长的系统上下文会导致模型性能衰减，加上现在正在测试，所以应当硬要求只传一个空格」

---

## 1. 目标 / 非目标

**目标**：把 Simos 角色面（`ToolRole{GM,DM}` + 两桶 + 白名单 + 装订期审计）与 AgentLib 0.2.1 角色维接成同一件事——**声明、调用者身份、库侧判定、装订期审计四处同源**，并在**包装后的真实注册表**上验证。

**非目标**：不改 GM/DM 语义、两桶归属、审批链；不删既有 `ToolRoleAudit`；不把内部预览/规划路径改走 authorizer（审核 §3 明确否决）；不碰批 D、不碰 A+B 循环改造；不改上游 AgentLib。

---

## 2. 库侧事实（0.2.1，审核已核 + 本工作树复核）

- `AgentTool.roles()`：声明侧，**空集 = 本维不表态（⊤）**；库里默认空集 ⇒ 未声明者行为逐字不变。
- 调用者 `AgentPermissionSet.roles()`：**空集 = ⊥（谁都不是，fail-closed）**；构造器 `.roles(RoleSet)`（`AgentPermissionSet.java:217`）。
- 判定：`PermissionChecker.denialReason(..., toolRoles)`——工具声明非空且与调用者 `roles` 不相交 ⇒ `PERMISSION_DENIED`，排在白名单/级别之后、敏感破坏之前（`:97-113`）。**判定器不用改**。
- 判定入口 `ToolExecutionGuard.denial/execute` 已把 `tool.roles()` 传进判定（`:45-52`）——**声明一侧接通即自动生效**。
- `RoleCatalogAudit.of(registry, knownRoles).withDerivation(fn).withHandwrittenCatalog(map).run()`：未知角色名/派生⇔手抄漂移 ⇒ `hasFailures()`；**覆盖缺口只报告不失败**；它**不接收调用者权限集** ⇒ 调用者接线必须另由真实路径判据守住（审核 §2 能力边界）。

## 3. Simos 侧事实（本工作树复核；★ = 审核指出的关键点）

- 角色声明唯一拼写点：`SimosTool.toolRoles(): Set<ToolRole>`（185 个实现类）；`ToolRole` 枚举 = Simos 领域件。
- 两桶派生 + `ToolRoleAudit.requireFaces`（`ToolRoleAudit.java:90-108`）双向钉死 **桶 ⇔ 声明**——但它靠 `((SimosTool) tool)` **强转**，只能在**包装前**跑。
- ★ **`RecordingToolSource.RecordingTool` 实现的是 `AgentTool`**（`RecordingToolSource.java:68`），委托 `spec/gate/resources/ledgerArgs/execute`，**不委托 `roles()`**；`Shell.java:1417` 实际注册/暴露给 MCP 的是这层记录壳（`:1429`）⇒ **包装后角色丢失、库判定跳过（空声明 = ⊤）**。只扫描 `SimosTool` 实现、只在包装前审计，全都抓不到（审核 §1 的探针实测：`raw_roles=[GM]; wrapped_roles=[]`）。
- 调用者权限集构造点（审核 §3 已逐个核实际消费方式）：

| # | 位置 | 是谁 | 本批动作 |
|---|---|---|---|
| C1 | `DecisionCallerFactory.java:299` `permissionSetOf`（`:472` 处经 authorizer 执行） | 决策人 | ★ 挂 `{DM}` |
| C2 | `Shell.java:1738` `gmPermissionSet()`（MCP authorizer 使用） | GM / MCP | ★ 挂 `{GM}` + **修记录壳** |
| C3 | `ProposalCatalog.java:198` + `:205` 裸 `tool.execute` | 提议预览 | **不动**（内部预览，不经角色判定） |
| C4 | `DecisionEffectExecutor.java:358` + `:364` 裸 `execute(planOnly)` | 效果规划 | **不动** |
| C5 | `AdjudicateTickTool.java:677/:705` 仅供 `ResourceAuthorizer` | 裁决围栏 | **不动** |
| C6 | `SimosToolSource.java:489/:538` 装配期挂载 | 挂载授权 | **不动** |
| C7 | `AgentLibLlmConfig.java:103/:327` 配置 | LLM 配置 | **不动** |
| C8 | 测试夹具 | 测试 | 按被测路径补角色 |
| C9 | `GmActAsTool.java:267` → `DecisionCallerFactory:505` `callerFor` | GM 代打 | **外层 actAs 仍是 GM；内层自然取得 C1 的 DM 角色** ⇒ 无需新造权限集、不改领域声明（审核 §6.3，已有裁定 `plans/2026-10-14-gm-dm-interaction.md:205` §3.7） |
| — | `GmAdjustPopulationTool.java:109`、`ToolClassAudit.java:62` | 资源上下文 / 只读 gate 探针 | **不动**（不经工具角色判定） |

★ 给内部上下文加 `RoleSet` **不会**让裸 `execute` 经过 `ToolExecutionGuard`（审核 §3）⇒ 不做。

---

## 4. 设计（rev2）

### 4.1 声明侧：双声明、单拼写点，角色字符串由 `ToolRole.name()` 派生

```java
// SimosTool.java
@Override
default Set<String> roles() {                       // 覆写库的角色维（声明面）
  Set<String> names = new TreeSet<>();
  for (ToolRole role : toolRoles()) names.add(role.name());   // 唯一拼写点仍是枚举
  return Set.copyOf(names);
}
```

- 185 个实现类**一行不改**；`ToolRole{GM,DM}` 仍只住 Simos；
- **禁止任何 `SimosTool` 实现直接覆写 `roles()`**（扫描器判据：覆写数 = 0；变异：手写一条 ⇒ 红）；
- 调用者/审计里的角色名一律 `ToolRole.GM.name()` / `.DM.name()` 派生，**不在第二处手写 "GM"/"DM" 字符串**（审核 §7）。

### 4.2 包装侧（★ 审核 P1-1 必改）：记录壳转发角色

- `RecordingToolSource.RecordingTool` 增加 `@Override public Set<String> roles() { return delegate.roles(); }`（记录壳不是 `SimosTool`，透传不违反"禁止领域工具重写该方法"）。
- **验证在包装后的真实注册表上**：`Shell` 用 `toolRegistry`（record 后的 GM 面）跑装配期审计；决策人侧注册表同样在记录壳之后验证。
- 判据：① 非相交调用者经**包装后的实例**仍被拒；② **取消角色委托即红**（变异：删掉 `roles()` 转发 ⇒ 目标判据必须红）。

### 4.3 调用者侧（只动 C1/C2）

| 调用者 | 改动 | 判据 |
|---|---|---|
| C1 决策人 | `permissionSetOf(...)` 末尾 `.roles(RoleSet.of(ToolRole.DM.name()))` | 删掉这一行 ⇒ 决策人端到端用例**必须红**（证明判定真的生效，不是"看起来接了"） |
| C2 GM/MCP | `gmPermissionSet()` 末尾 `.roles(RoleSet.of(ToolRole.GM.name()))` | 同上（GM 面路径） |
| C3–C7 | **不改**（裸 `execute`/资源上下文/配置，不经角色判定） | 既有测试维持绿 |
| C8 | 逐夹具补 | 与被测路径同角色 |
| C9 GM 代打 | **不改**（内层走 C1 的 DM 角色） | 代打既有用例维持绿 |

### 4.4 装订期审计（★ 审核 P1-2 必改：两个**独立观察面**）

拆成两个独立输入，不再拿同一份桶名单喂两边：

**输入 A（声明面，包装后实际实例）**：对 GM 面 / 决策人面**实际注册的** `AgentTool` 实例逐个读 `roles()`：

```
declared: Map<roleName, Set<toolName>> = for each face tool → for each role in tool.roles() → declared[role] += name
```

**输入 B（桶面，实际桶成员）**：`Map.of(ToolRole.GM.name(), gmBucketNames, ToolRole.DM.name(), decisionBucketNames)`（已有派生量，不新建手抄清单）。

**接线**：

1. **逐面逐实例的 face 校验（新增，post-wrap）**：`ToolRoleAudit.requireFacesOnAgentTools(gmFace, decisionFace)`——语义同 `requireFaces`（`toolRoles()` 含该面角色 ⇔ 在桶里），但读 `tool.roles()`（字符串）而非强转 `SimosTool` ⇒ **能在记录壳上跑**。这一层挡住"包装丢角色"和"声明⇔桶不一致"。
2. **`RoleCatalogAudit`（库侧）**：`of(actualRegistry, Set.of(ToolRole.GM.name(), ToolRole.DM.name()))` + `withDerivation(reg -> declared)`（输入 A）+ `withHandwrittenCatalog(bucketProjection)`（输入 B）+ `run()`；`hasFailures()` ⇒ **装配期当场抛**。
3. **两桶同名工具**：逐实例面校验（第 1 层）各自验一侧——GM 面的实例必须声明 GM、DM 面的实例必须声明 DM，**不合并任选**；声明面无冲突时两实例的 `roles()` 必须一致（不一致 ⇒ 第 2 层派生⇔桶对账会红）。
4. **能力边界如实写**：`RoleCatalogAudit` 收不到调用者权限集、覆盖缺口只报告 ⇒ 调用者接线由 §4.3 的真实路径判据守（不靠审计）。

**判据（审核 §2 指定）**：把某工具的**库声明改成另一个已知角色、或改成空**、同时保持桶不变 ⇒ 装配**必须失败**（面校验红）。原有"未知角色名"变异不能替代这一条。

### 4.5 明确不做

- 不把 C3/C4/C5 的裸预览/规划改走 `ToolCallAuthorizer`（会引入审批等行为变化，改变现有机制）；
- 不给内部 `ToolContext`/`AgentPermissionSet` 新增角色（加了也不会过判定链，只会制造"以为接上了"的假象）；
- 不收敛/删除 `ToolRole`、两桶、`ToolRoleAudit`。

---

## 5. 不变量与判据（rev2）

| # | 不变量 | 判据 / 负向用例 |
|---|---|---|
| R-I0 | 唯一拼写点 | `SimosTool` 实现直接覆写 `roles()` 数 = 0（扫描器；变异：手写一条 ⇒ 红）；调用者/审计里 `"GM"`/`"DM"` 字面量 = 0（全部由 `ToolRole.name()` 派生） |
| R-I1 | 声明 ⇔ 桶一致（**post-wrap**） | `requireFacesOnAgentTools` 在**记录壳之后的真实注册表**全绿；变异：删 `RecordingTool.roles()` 转发 ⇒ 红 |
| R-I2 | 装订期审计两输入独立 | ① 工具声明改成另一已知角色/空、桶不变 ⇒ 装配失败（面校验）；② 未知角色名 ⇒ `RoleCatalogAudit` 失败；③ 派生⇔桶漂移 ⇒ 失败 |
| R-I3 | 角色判定生效（**两层**，审核 P2-4） | ① 生产入口：DM 真实注册表里 GM-only 工具不存在 ⇒ 维持既有 `TOOL_NOT_FOUND`；② 机制探针：显式满足白名单/级别/敏感/destructive 全部前置，**只让角色不相交**，经**真 `ToolCallAuthorizer`** 调用 ⇒ `PERMISSION_DENIED` 且**不进入审批/工具体**；把角色改成相交 ⇒ 能越过**这一拒绝点**；③ 包装后的记录壳实例同样覆盖 |
| R-I4 | 漏挂调用者 = deny-all（fail-closed） | 删 C1 的 `.roles(...)` ⇒ 决策人真实路径用例红（不是"更严格地绿"）；恢复后绿 |
| R-I5 | 可见面 ⊆ 判定面（**限定范围**，审核 P2-5） | 只对**实际 LLM/MCP 工具面**（两个面注册表的工具名）做"可见的每项可调用"探针；命令 catalog 继续验证"直接调用**或嵌入指令**的合法触发途径"（`CatalogVisibility.embeddedTypes`），**不**把 catalog 条目当可直接调用工具、不拿掉原有合法命令类型 |
| R-I6 | 零行为变化（除有意收紧） | A+B 的 27 条 + 相关端到端族全绿；**审核 §6.1 结论：未发现必须收紧的组合** ⇒ 不得把"尚未发现的收紧"当成用户已批准的行为变化；若实现中发现，停下上报 |

---

## 6. 责任区与施工顺序（审核 §7）

**一个生产责任区**（不拆给多个实现者、不在中间状态宣称"行为保持"）：声明派生（4.1）+ 包装委托（4.2）+ 调用者接线（4.3）+ 装订审计（4.4）＝ 一笔完整的生产改动。
内部施工顺序建议：`4.1 → 4.2 → 4.4（先让装配能跑）→ 4.3`；编译绿后再统一补测试与变异（4.5 的判据）。

| 施工 | 文件 | 交账判据 |
|---|---|---|
| P1 | `simos-ga-spi/.../SimosTool.java`（roles() default）；`simos-ga/.../tools/RecordingToolSource.java`（转发）；`simos-ga/.../tools/ToolRoleAudit.java`（post-wrap 面校验）；`Shell.java`（审计接线）；`DecisionCallerFactory.java` + `gmPermissionSet()`（挂角色） | 全仓 `test-compile` 绿 |
| T1 | 新增/更新：面校验变红用例、wrap 丢角色变异、两输入独立审计用例、两层角色判据、可见面探针、调用者漏挂 fail-closed 用例；既有族回归 | 变异逐条红后还原 + md5 核对；既有测试族全绿 |
| C1 | 全量 `clean verify` + 真 LLM 门控 + 前端门禁（与 A+B 合并一次跑） | 全绿后交用户决定合并 |

---

## 7. 风险 / 未验证

1. 面校验读 `roles()` 字符串后，`ToolRole` 枚举与库字符串的映射只在 `SimosTool.roles()` 一处派生——若实现方绕开接口直接 `AgentTool` 且声明角色，面校验会红（fail-closed，符合预期）。
2. `RoleCatalogAudit` 的 `withHandwrittenCatalog` 语义按审核要求承接"桶投影"，不新建手抄清单；派生与手抄的键 = 角色名（`ToolRole.name()`）。
3. 未验证：GM 代打内层是否**所有**分支都走 C1（审核只核到 `:267 → :505` 一条主链）——T1 补一条代打端到端断言兜住。
4. 本计划书未跑任何 R 的实现/测试；A+B 的绿色证据不迁移到本批。

---

## 8. 并行批 S（单空格 system，独立于本批）

依据 `docs/superpowers/specs/2026-10-17-single-space-system-message.md`（用户硬约束）：**system 消息内容只能是一个 ASCII 空格**（U+0020），裁决器原 system 指令迁入 user；S1–S5 判据 + 负向自证；**不属本批 R、不赋予内部上下文角色**。执行顺序：S 先做（小、独立），再回头做 R 的 P1/T1。

---

## 9. 不需要再向用户裁定的点（审核 §6 已核）

1. **跨角色收紧**：审核已核 C1/C2 + 桶边界，正常生产调用都有相交角色；**未发现必须收紧的组合**。不得把未发现的收紧当作已批准变化（R-I6 第二句）。
2. **系统上下文全角色**：不需要——C3–C7 走裸 `execute`/资源上下文，不经角色判定；保持现状不是"本批新增绕过"。
3. **GM 代打**：语义已有裁定（`plans/2026-10-14-gm-dm-interaction.md:205` §3.7）；外层 actAs 仍是 GM，内层经目标 DM 的 caller 自然取得 DM 角色 ⇒ 无需新权限集、无需改领域声明。
