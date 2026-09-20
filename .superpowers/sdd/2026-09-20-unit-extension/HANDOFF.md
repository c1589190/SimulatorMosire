# unit-ext 交接（控制器，2026-09-21 07:56 停机前）

> 目的：让**下一个会话**不必重新推导就能接着干 T9/T10。所有数字均为**实测**，
> 凡与计划冲突处已标明"计划是立项旧数"。

## 一、当前状态（一句话）

主干 `feat/adr1-core-scope` 在 **`38616da`**，**T1~T8 全部关账**（实现 + 变异 + 模块门禁 + 合并 + 合并门禁 + 台账/CLAUDE.md 回填），工作树**干净**，`origin/feat/adr1-core-scope...HEAD` = **`0 0`**。
**未派 T9**：停机前跑道不足（T9 光实现就超过 T8 的 28 分钟，后面还压着模块门禁 + 变异 + 全量门禁）。**不存在进行中的子代理。**

## 二、已完成的 T1~T8（提交与计数）

| 任务 | 内容 | 计数 | 实现提交 |
|---|---|---|---|
| T1 | 模型地基：`UnitStatus`/`RelativeOffset`/`CommandChainId`/`CommandChain` + `Unit` 四字段 + `UnitState.commandChains` | unit 131→148 | `4e18549` |
| T2 | 三态速度 1000/500/250‰ + `planRoute` 冻结 + `SetStatus` | 148→167 | `8b8c0c0` |
| T3 | 编制命令 A：`attachSubtree` 级联 / `detachUnit` 只节点 / `setOffset` + 三 handler | 167→183 | `5adb574`（合并 `9747448`） |
| T4 | 编制命令 B：`reparentSubtree` 整树迁移 / `splitFormation` / `mergeFormation` + 三 handler | 183→201 | `234f5ce` |
| T5 | 命令链 `createChain`/`updateChain` + ★ **六处链清空修复** | 201→216 | `69d3c7d` |
| T6 | 稀疏路线 `unit.PlanSparseRoute`（A\* 逐段展开；不可达命令期拒；★ 裁定 U3 构造器注入） | 216→233 | `5e32402` |
| T7 | 回归路径 `unit.SetRejoinTarget` + 每 tick 重规划（裁定 U4/U5/U6） | 233→246 | `851e61b` |
| T8 | 战损 `unit.ApplyCasualties`（双轨 delta + 上界 + 未知键拒 + 时间线回退） | 246→257；**core 174→177** | `8cc872d` |

**合并后门禁**：T3 1112 / T4 1130 / T5 1145 / T6 1162 / T7 1175 / **T8 = 1191 = 170/362/45/257/177/62/116**（rc=0、8/8 模块、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 88/88）。每轮 **delta 都干净**（只有应涨的模块涨）。

## 三、★ 下一步 T9（SPI 装配）——前置与坑

**目标**：12 条新 handler 注册进 `Shell` + `PlanSparseRouteHandler` 的 `MovementCost` 注入 + catalog 含全部新 type + `unit` namespace 恰一个 participant + 端到端冒烟。

**★ 三个必须先知道的坑**（控制器已核实）：

1. **★ 计划里的 `simos-app` 计数 `110 + M` 是立项旧数，实测是 `116`。**
   （同理 `simos-core` 的 `169 + K` 也是旧数——T8 实测把它变成了 `177`。）
   ⇒ **一律以实测为口径**；计划的计数只作立项基线，**不改计划**（改了会抹掉偏差记录）。
2. **★ 计划 T9 步骤 1 写的 `new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)` 是"对的"，别误判成违反 U3。**
   U3 禁止的是**handler 内部写死** `INSTANCE`；在**装配点**注入 `INSTANCE` **正是注入的意义所在**。
   ⇒ 装配点传 `INSTANCE` 合法；`Shell` 里若出现任何"handler 内部自己取 cost"的形态才是错的。
3. **★ T9 的门禁不是"模块门禁"，是"近似全量"。** T9 改 `simos-app`，而 `-pl simos-app -am` 会把 7 个依赖全带上 ⇒ **实测耗时必然压在那条 600 s 线上**（见 §五）。⇒ **T9 的门禁必须按全量门禁的规矩跑**：前台、独占、记"第几次尝试"。

**12 条 handler 清单**（计划 T9 步骤 1，已与 T3~T8 实现对齐）：
`AttachUnit` / `DetachUnit` / `ReparentSubtree` / `SetFormationOffset` / `CreateCommandChain` / `UpdateCommandChain` / `SplitFormation` / `MergeFormation` / `PlanSparseRoute(cost)` / `SetRejoinTarget` / `SetStatus` / `ApplyCasualties`

**判据**：catalog type 集合 ⊇ 上述 12 个（**m1 靶子 = 删一条注册**）；每个 `type()` 形状 `<namespace>.<Command>`；`unit` namespace 恰一个 participant（装配 + 一次 `advance` 不抛）；端到端经 `Shell` 发一条新命令 ⇒ `committed`。**变异 ≥2 轮**；★ 十道门禁 + ★ **裁定 42**（新增/改动护栏必须自带变异轮）+ ★ 第 ⑩ 道**逐片段**自证。

**★ 装配改动 ⇒ 必须连带复核**：`Shell` 是既有装配点，改它 = **改动既有护栏** ⇒ 按裁定 42 需自带重跑轮；同时确认 `simos-app` 既有测试（实测 **116**）只增不减地绿。

## 四、T10（端到端判据 + 关账）——**已挂账的待裁项**（不许丢）

| 编号 | 挂账项 | 出处 |
|---|---|---|
| **T10-a** | ★ **T3-L1**：`dq`/`dr` 无上界 ⇒ `RelativeOffset.appliedTo` 裸 int 加法**静默溢出**（`1+MAX_VALUE=-2147483648`）；修点应落 T1 的 `RelativeOffset`（只堵 handler 层则 codec 路径仍开着），且 **P2 明文"无范围约束"** ⇒ **与 P2 一起裁** | T3 |
| **T10-b** | **同族判据清扫**：`everyHandlerRejectsMalformedPayload` 里 **13 处**只断言字段 token 的载荷断言（`UnitCommandHandlersTest:620`~`:631`）——凡"载荷层与域层都会提到同一字段名"的命令，token 断言**判不出是哪一层拒的** | T4 / T5-L6 |
| **T10-c** | **T5-L4 的 1 参兼容构造器 `new UnitState(units)` 是否删除**（它会**静默清空 `commandChains`**；T5/L4 立为通则、三次被变异体撞上既有护栏） | T5-L4 / T7-G4 |
| **T10-d** | **G1**：`disband` 之后可能留**悬空 `rejoinTarget`**——运行期口径安全（`effectivePosition` 对不存在 id 返空 ⇒ 不回归、不写任何东西）但**无判据**；**未在 T7 补**（会动 T3/T4 已关账 op 族） | T7-G1 |
| **T10-e** | **G5**：回归与**在途普通路线**同时存在时的交互**无 spec 依据**（现状：回归行程**替换**在途行程）；**凭空定策略就是发明需求** ⇒ 届时仍无上游依据就**记为"未定策略"、不记为"已实现"** | T7-G5 |
| **T10-f** | **G2**：`UnitPayloads` 类注仍写"unit **十六个** handler"（实为 **18**）——纯注释、零行为 | T7-G2 |
| **T10-g** | **spec §八 20 条逐条实测值**（不是"通过/不通过"，要**数字**）+ 变异轮汇总 + **"我未能核实的"清单** + 关账报告 | 计划 T10 |

## 五、★ 运行纪律与环境实测（**下一个会话必须原样继承**）

### 安全 / 纪律
- **密钥纪律**：配置**值**绝不进日志 / 异常 / 事件 / argv / env / stdio；读配置只打印**路径 + 长度**。
- **绝不 `git add -A`**；提交前先扫 `git diff --cached`；**不加 `Co-Authored-By`**、不加任何生成器 trailer；提交信息用中文。
- **`~/.m2` 里那份 simos 构件是探针 `install` 写进去的** ⇒ **`-am` 必带、禁 `install` simos、禁改任何 `pom.xml`**。
- **一次只跑一个 Maven**（本机 `nproc=2`）；**不许在 agent 活着时跑全量 verify**。
- **不许跳过门禁**；**单个模块的评审不超过 3 轮**，评审体量不得压过代码本身。
- 证据放 `.superpowers/sdd/<date>-<name>/tN-evidence/`，**不许放仓根**。
- 本机 `grep` 是 ugrep 7.8.4 ⇒ 全仓搜索一律 `git grep`；含未入库文件用 `git grep --untracked`。
- worktree 下**绝不裸用 `git stash` / `git stash pop`**。
- ★★ **子代理一律用默认类型**（用户明确要求）；**不许用 V4 Flash**。
- `CLAUDE.md` 两边各有一份同名文件（主检出与 worktree）⇒ **改哪份要认路径**。

### 环境实测（这台机器，1.8 GiB）
- ★★ **全量 `clean verify` 耗时压在 Bash 工具 600 s 上限附近**：一旦越过就被 harness **摘到后台**；**摘后台窗口内的内存压力**会招来内存守卫**杀进程**。
  **实测序列**：T5 1 杀 1 成 / T6 0 杀 1 成（587 s，恰在线下）/ **T7 3 杀 1 成** / **T8 0 杀 1 成（被摘后台、跑完 rc=0）**。
  ⇒ **"前台"是必要条件、不是充分条件**；**可操作的只有两条：①走前台 ②独占（门禁期间一条命令都别跑）**。
  ⇒ **"被杀"既不是红也不是绿**；被杀轮**留档不删**；记录门禁结果**必须一并记"第几次尝试"**。
- ★ **md5 才是"字节变了"的判据，mtime 不是**；**同一文件被改动 ⇒ 旧证据对应旧字节**。
- ★ **"派单"与"提交树外文档"不能同序**：T6/T7/T8 **连续三次**因为"派单后又提交了收口文档"导致分支分叉、需要 `rebase` 再 ff。⇒ 要么**先提交完再派单**，要么**接受一次 rebase**（并记得报告里引用的哈希会漂）。

## 六、T9/T10 之后

**sd-simos C1~C6 → D1~D7 → E1**，其中 **C 的硬前置 = unit-ext 全部完成**。

## 七、停机时的一件待决（**需用户一句话**）

`origin/main` 落后 **20** 个提交（`origin/main...HEAD` = `0 20`）。记录在案的口径是「默认分支 **deliberately unpushed**」，本会话**未推**，也**没有**在停机时擅自推。若要把 main 也推上去（用户此前有过一次「推 main」的先例），说一声即可。
