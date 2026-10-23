# 2026-10-17 批 R 角色维对接计划审核

> 审核对象：`docs/superpowers/plans/2026-10-17-batch-R-agentlib-role-dimension-plan.md`（未提交稿）。
> 基线：消费方工作树 `agentlib-dock` / `e26d49a4`；库源码 `/home/cna/ProjectMosire` / `ef241ae`；探针直接加载消费方缓存中的发布版 `agentlib-mosire-0.2.1.jar`。
> 用户本轮原话：「审核，Agentlib所在的项目就在~/ProjectMosire」。
> 结论：**复用方向成立，当前计划需修改后再实施**。以下为回代码核实的设计问题；本轮未改业务代码、未改原计划、未提交。

## 1. [P1] 漏掉 GM 记录包装层，接完声明后实际执行面仍丢失角色

- 计划 §5.1 / §7 只安排 `SimosTool` 声明派生和调用者挂角色，未覆盖 `RecordingToolSource`。
- 事实：`simos-ga/src/main/java/io/mosire/simos/ga/tools/RecordingToolSource.java:68` 的 `RecordingTool` 实现的是 `AgentTool`，委托 `spec/gate/resources/ledgerArgs`，没有委托 `roles()`；`simos-app/src/main/java/io/mosire/simos/app/Shell.java:1417` 实际注册的是这层记录壳，GM MCP 调用也使用这份注册表（`:1429`）。库 `AgentTool.java:59` 默认角色为空，`PermissionChecker.java:87` 遇到空声明跳过角色检查。
- 后果：原工具派生 `{GM}`，进入真实 GM 注册表后却成了空声明；仅在包装前做审计、仅扫描 `SimosTool` 实现，都抓不到。正常 GM 正向用例仍可能全绿，角色维实际没有接通这一侧。
- 必改：将记录壳的角色委托纳入同一责任区；在包装后的真实注册表验证角色与原工具一致。增加「非相交调用者经包装后仍被拒」「取消角色委托即红」判据。记录壳不是 `SimosTool`，其透传 `roles()` 不违反禁止领域工具重写该方法的要求。

## 2. [P1] 审计两项输入来自同一桶名单，不能检出库声明与桶的漂移

- 计划 `:112` 的伪代码有语法误写（静态工厂前不应加 `new`）；更关键的是 `:113` 让 `withDerivation` 从桶派生，`:114` 又把同一桶名单传给 `withHandwrittenCatalog`。
- 事实：库 `AgentLibMosire/src/main/java/io/mosire/agentlib/permission/RoleCatalogAudit.java:156` 在提供派生函数后只使用该函数的返回值；`:169` 起仅比较这个返回值和另一份目录，不额外核对 `AgentTool.roles()` 与派生目录的关系。未知角色扫描不能识别“合法名字 GM/DM 之间写反”。
- 后果：工具声明 `{DM}`、桶名单却列在 GM 下，两项输入都取桶时依旧不报失败；包装丢失声明也可能如此漏过。
- 必改：一项输入读取**实际注册实例的 `AgentTool.roles()`**，另一项读取实际 GM/DM 桶成员，形成两个独立观察面。不要为调用 `withHandwrittenCatalog` 新建手抄清单；该参数可以承接已有桶投影。明确两桶同名工具实例的处理，不能只在合并时任选一侧而漏验另一侧；GM 包装后的实例也必须覆盖。
- 判据：将某工具库声明改成另一个**已知**角色、或改为空，同时保持桶不变，装配必须失败。原来的“未知角色”变异不能替代这个判据。
- 能力边界：`RoleCatalogAudit` 不接收真实调用者权限集。传入 `knownRoles={GM,DM}` 不能证明 `gmCaller` / DM 工厂真的挂了角色；`hasFailures()` 也不将覆盖提示计为失败（库 `:250`）。调用者接线必须另由真实路径判据守住。

## 3. [P2] C3/C4/C5 被误归为需要全角色的工具调用者

计划 `:101` 的“保持空角色会调不动工具”不适用于这些现有路径：

| 路径 | 已核实的实际消费方式 | 本批建议 |
|---|---|---|
| C1 DM | `DecisionCallerFactory.java:299` 构建权限，经 `:472` 的 authorizer 执行 | 在原工厂挂 DM |
| C2 GM | `Shell.java:1738` 构建 GM 权限，MCP authorizer 使用 | 在原工厂挂 GM，同时修记录壳 |
| C3 提议预览 | `ProposalCatalog.java:198` 构建系统资源上下文，`:205` 直接调用 `tool.execute` | 保持现有内部预览机制，无需新增全角色 |
| C4 效果规划 | `DecisionEffectExecutor.java:358` 构建系统资源上下文，`:364` 直接调用 `tool.execute`（`planOnly=true`） | 保持现有规划/整批提交机制，无需新增全角色 |
| C5 裁决围栏 | `AdjudicateTickTool.java:677` 只供 `ResourceAuthorizer` 求资源交集，`:705` 构建该资源权限载体 | 不挂 GM/DM；它不是系统工具调用身份 |
| C6 装配挂载 | `SimosToolSource.java:489` 调 `registry.mount`，`:538` 的权限仅供挂载期资源授权 | 不挂角色 |
| C7 LLM 配置 | `AgentLibLlmConfig.java:327` 等直接调用配置存储 | 不挂角色 |

另两处构造点被“穷举”漏掉：`simos-social/.../GmAdjustPopulationTool.java:109`（资源上下文、`:118` 裸转发委托）；`simos-ga/.../ToolClassAudit.java:62`（只读 gate 探针）。两者也不经过工具角色判定。

库的角色检查发生在 `ToolExecutionGuard.java:47`，给内部上下文加 `RoleSet` 不会让裸 `execute` 自动经过它。**本审核不建议为迁就计划统一改走 authorizer**：那会同时引入审批等行为，改变纯预览/纯规划的现有机制。如要改变这些边界，应另出设计与判据。

## 4. [P2] R-I3 的负向结果不能证明角色检查生效

- 计划 `:138` 要求 DM 调 GM-only 工具得到 `PERMISSION_DENIED`。生产 DM 注册表本来就没有 GM-only 工具，真实入口应维持 `TOOL_NOT_FOUND`；GM 代打同理。
- 即使测试把 GM 工具塞入临时注册表，生产 DM 白名单也不含它：库 `PermissionChecker.java:73` 会先以白名单拒绝，尚未执行 `:87` 的角色检查；此时删除角色检查，测试仍可能绿。
- 必改：分两层判据。① 生产注册表/代打路径维持已有找不到工具的结果；② 角色机制探针显式满足白名单、级别和其余前置条件，仅令角色不相交，经真实 `ToolCallAuthorizer` 调用，断言拒绝且不进入审批/工具体。将角色改成相交后，须能越过此拒绝点。包装层也要覆盖。

## 5. [P2] R-I5 不能把命令 catalog 条目都当成可直接调用的工具

- 计划 `:140` 将 catalog 举作“可见的每一项都可调用”的探针对象。
- 事实：`simos-app/src/main/java/io/mosire/simos/app/access/CatalogVisibility.java:60` 的可见性包含 `embeddedTypes`（`:66`），意为可以嵌入指令的命令类型；它们不必是 DM 可直接调用的工具。全工具白名单又在 `:63` 直接开放 catalog。
- 必改：将角色判定一致性探针限定到实际 LLM/MCP **工具面**，其余审批/资源条件按判据控制；命令 catalog 继续验证“直接调用或嵌入指令的合法触发途径”。不要为了让探针通过而拿掉原来允许展示的命令类型。

## 6. §8 三个待裁定点的核实结论

1. **跨角色收紧**：当前 `ToolRoleAudit.java:93` 校验 GM 桶含 GM、DM 桶含 DM，`:108` 起双向对照成员。因此正确接入 C1/C2 并保留桶边界时，正常生产调用已有相交角色；本次已核实路径未发现必须收紧的组合。不得把尚未发现的收紧当作用户已批准的行为变化。
2. **系统全角色**：按上表，这不是当前功能成立的必要条件，不需要先让用户选择“全角色或跳过检查”。现有裸预览/规划是事实，不能把保留现状描述为本批新增绕过。
3. **GM 代打**：语义已有裁定，无需再问。`docs/superpowers/plans/2026-10-14-gm-dm-interaction.md:205`（§3.7）要求建目标 DM 的环境并走同一条权限链；实际 `GmActAsTool.java:267` 调用目标 DM 的 `caller.execute`，工厂 `DecisionCallerFactory.java:505` 调 `callerFor`。**外层 actAs 工具仍是 GM；内层自然取得 C1 的 DM 角色**。无需给 actAs 新造全角色权限集，也无需改变其领域角色声明。

§7 调整建议：声明派生、调用者接线、包装委托、装配审计是同一完整生产责任区；R1/R2 可作为内部施工顺序，不分别派实现者、也不在中间状态宣称行为保持。测试和变异统一放最后。角色字符串由 `ToolRole.name()` 派生，避免在调用者和审计处再次手写 GM/DM。

## 7. 本轮验证与未验证

只读检索使用 `rg -n` / `nl -ba … | sed -n …`，逐项定位如上；库工作树为 `ef241ae`，本轮未改动上游。

临时探针原件 `/tmp/SimosRoleReviewProbe.java`（本机证据，不进 git；清理临时目录后需重建）。在消费方工作树运行：

```bash
python3 - <<'PY'
import pathlib, subprocess
b = pathlib.Path.cwd()
cp = [str(b / p) for p in (
    '.m2-repo/io/mosire/agentlib-mosire/0.2.1/agentlib-mosire-0.2.1.jar',
    '.m2-repo/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar',
    'simos-ga/target/classes', 'simos-util/target/classes')]
cp += [str(p) for p in (b / '.m2-repo/org/pf4j/pf4j').glob('*/*.jar')
       if 'sources' not in p.name]
subprocess.run(['java', '--class-path', ':'.join(cp),
                '/tmp/SimosRoleReviewProbe.java'], check=True)
PY
```

真实输出（rc=0；SLF4J 无 provider 提示略）：

```text
raw_roles=[GM]; wrapped_roles=[]
DM_raw_denied=true; DM_wrapped_denied=false
declared_DM_but_bucket_GM: catalogChecked=true; hasFailures=false; diffs=[]
```

探针没有调用工具体：包装用 `RecordingToolSource.record`，拒绝比较用 `ToolExecutionGuard.denial`；审计探针故意把工具库声明设为 DM、两项目录同时设为 GM。源码与发布 jar 在本次使用到的角色判定/审计行为上相符。

**未做/未验证**：没有实施 R，没有修改原计划，没有跑 Maven、全量测试、变异、真 LLM 或前端门禁；没有证明所有可能的动态调用组合均行为等价。上述探针是两处反例的实测，不代表整批验收通过。
