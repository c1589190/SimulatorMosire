# SDD ledger — plan: docs/superpowers/plans/2026-09-26-s1-stage2-actor-slice.md

Spec: docs/superpowers/specs/2026-09-26-s1-actor-property-design.md
（含 2026-09-26 追加-1..4：R6 唯一真源 / R2 依赖护栏 / R7 三段硬隔离 / 两处文档事实修正）
Breakdown: docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md §三 阶段 2

## Setup

- 用户 2026-09-26 裁定：**R1–R5 全部通过**，并加三条硬要求：
  1. **R1 必须纯机械迁移**（`AssetKind` 原值/序列化名/解析行为一字不动；"移动词表"与"重新设计资产类型"
     绝不许塞进同一提交）
  2. **R2 加两条 enforcer 护栏**：`simos-actor` 禁依赖 `simos-economy` / `simos-ledger`
     （"只允许类型依赖，不允许运行时领域控制流反向流入"）
  3. **补充 R6**：`GoodsAccount` 是新产权模型商品余额的**唯一 authoritative state**；
     `ledger.Account` 保持 legacy/unwired —— **不读、不写、不同步**
- 用户明令任务切分：**三段硬隔离**（A 搬家 / B 新契约 / C 新模型），每段全仓恢复绿才进下一段
  ⇒ 记为 **R7**
- 执行方式：**Subagent-driven**（用户 2026-09-26："善用子Agent"；"按这个版本，我认为计划可以进入
  subagent-driven 实施"）—— 每个任务一个实现 Agent + 一个独立评审 Agent
- 分支：`ts/m1`（与阶段 1 / B1B2 同分支）

## 侦察结论（三份并行只读报告，2026-09-26）

| 结论 | 值 |
|---|---|
| `ActorRef` / `ActorKind` 定义 | **各 1 份**，同住 `simos-economy-api/.../economy/api/actor/` |
| `AssetClassKey` | **全仓 0 份**（只在 2 个 md 里被提及）⇒ 阶段 2 是**新建**不是上移 |
| 跨模块引用 | 只有 `simos-ledger`（34 次）+ `simos-app`（31 次）；**其余六模块零引用** |
| 上移的生产代码改动面 | **4 文件 / 6 行**（`Account` / `Claim` / `Transfer` / `EconomySeeder`） |
| `ActorRef`/`ActorKind` 上移的 JSON 线格式 | **不变**（7 条依据：零 Jackson 注解、无自定义序列化器、`toString()` 对 record 不生效、无 FQCN 字符串、无多态耦合…） |
| `ServiceLoader` / `META-INF/services` / `module-info` | **全仓 0 命中** ⇒ 装配 100% 靠 `Shell.java` 显式注册 |
| `bannedDependencies` | **每个模块自己 pom 里各写一份**，根 pom 里没有 |
| 新增模块的连带面 | `Shell.java` 5 个注册点 + `RichWorld` / `CorridorWorld` / `ApiViews` + **4 个 app 测试的硬编码 codec 清单** |

## 任务清单

- [ ] Task 1  建 `simos-actor-api` 模块骨架（含 enforcer 变异自证）  ← **A 段**
- [ ] Task 2  上移 `ActorRef` / `ActorKind` / `AssetKind`（纯机械）    ← **A 段关账点**
- [ ] Task 3  `AssetClassKey`（新建，TDD）                            ← **B 段**
- [ ] Task 4  `Actor` + `ActorData` + `Snapshot` + `ChangeSet` + 往返  ← **C 段**
- [ ] Task 5  `AssetHolding`（聚合键 + I2.2 前半句）
- [ ] Task 6  `GoodsAccount`（R6 的落点）
- [ ] Task 7  `ActorCodec`
- [ ] Task 8  SPI + `ActorResolver`
- [ ] Task 9  装配进组合根 + 回填文档 + 门禁 + 验收读数

（逐任务的执行记录追加在下面。）

---

## 预检冲突扫描（dispatch Task 1 之前，2026-09-26）

### 逐任务自洽性

| 任务 | 自身文本是否自洽 |
|---|---|
| Task 1 模块骨架 | ✅ |
| Task 2 上移 | ✅（"17+9=26 文件 / 131+37=168 次"两处表述同值） |
| Task 3 `AssetClassKey` | ❌ **F2**：要求给 `land`/`loom` 两个工厂，但 `AssetKind` 六档**没有 `LOOM`** |
| Task 4 `ActorData` | ❌ **F1**：`ActorData(…, …)` 里写了 **`…` 占位** |
| Task 5 `AssetHolding` | ✅（"不要 `id`、用聚合键代替"已在 Self-Review 声明） |
| Task 6 `GoodsAccount` | ✅ |
| Task 7 `ActorCodec` | ✅ |
| Task 8 SPI + Resolver | ✅ |
| Task 9 装配 | ❌ **F3**：写 `ActorToolSupport`，实际是 `ToolSupport.ACTOR_NAMESPACE` |

### 共享文件 / 接口的任务对

| 对 | 生产者 → 消费者 | 发现 |
|---|---|---|
| T1 → T2 | actor-api 模块 → 往模块里加类型 | ✅（T1 的 ban 表含 `economy-api`，T2 让 `economy-api` 反向依赖 `actor-api` —— **方向相反、无环**） |
| T1 → T3 | 模块 → 模块内的新类型 | ✅ |
| T2 → T3 | 产出 `AssetKind` → 消费为 `AssetClassKey.kind` | ❌ **F2** |
| T4 → T5 | 产出 `ActorData(meta, actors)` → 加 `holdings` 表 | ❌ **F1** |
| T4 → T6 | 同上 → 加 `accounts` 表 | ❌ **F1** |
| T4 → T7 | 产出 `simos-actor/pom.xml`（含 R2 enforcer） → 切片内加 codec | ✅ |
| T5/T6 → T7 | 键类型（`AssetHoldingKey`/`GoodsAccountKey`） → codec 消费 | ✅ |
| T7 → T8 | codec → SPI | ✅ |
| T8 → T9 | SPI handler → 注册进 `Shell` | ✅ |
| T2 → T9 | 都改 `simos-app`，但不同文件 | ✅ |
| T3 → T2 | B 段**不得回头改** A 段契约 | ✅（已在计划里显式写成纪律） |

### 裁定（**在被实现咬到之前就定**）

- **Ruling（F1）**：`ActorData` 的三张表**按任务顺序增量加** —— Task 4 建 `(meta, actors)`，
  Task 5 加 `holdings`，Task 6 加 `accounts`；**每加一张，同一提交里同步改 `ActorData` 组件 +
  `ActorChangeSet` 字段 delta + `ActorRoundTripTest` 往返断言**。
  —— **为什么**：让每个任务**自身可编译、可测、可评审**，不让 Task 4 引用尚不存在的类型。
  —— **错了的代价**：Task 4 的 `ActorData` 形状与最终形状不同，评审须对照计划的"增量表"而不是最终形状
  （计划已就地改成表格，不再有 `…` 占位）。
- **Ruling（F2）**：Task 3 **只给 `land(Map)` 一个工厂**，**删掉 `loom`** ——
  `AssetKind` 没有 `LOOM`，凭空发明映射正是 R1 明令禁止的"趁机改语义"。
  —— **错了的代价**：spec §2.3 的 `LOOM(handloom, tech=T1)` 举例暂时无法用本类型表达，
  要等 `AssetKind` 真的增档（那是另一个裁定）。
- **Ruling（F3）**：Task 9 的 `ActorToolSupport` 是笔误 ⇒ 改为 `ToolSupport.ACTOR_NAMESPACE`，
  且**三处同字面 `"actor"`**（`ActorSnapshot.namespace()` / `ActorCodec.namespace()` / `ToolSupport`）。
  —— **错了的代价**：`SimulationState` 构造期会当场抛（键名写歪拦得住），故代价低。
- **Ruling（环境）**：superpowers 技能包的 `scripts/*` 是 **CRLF 行尾** ⇒
  `set -euo pipefail` 里的 `\r` 被当成选项名（`pipefail\r: invalid option name`），**在本机必然失败**。
  ⇒ 用**去 CR 的本地副本** `/home/cna/.claude/jobs/s1a-stage2/sdd-scripts/` 跑 `sdd-workspace` / `task-brief` /
  `review-package`。
  —— **错了的代价**：副本与插件版本可能漂移（插件升级后要重做副本）。★ 已记，不阻塞。

★ 三处计划缺陷**已就地修好**（不是只记不改）—— 修完复扫，无新增冲突。

### 两处工程纪律（沿用阶段 1，写在这里免得每轮重述）

1. **一次只能跑一个 Maven**；跑前 `pgrep -af "surefirebooter|classworlds.launcher"`
2. **不 `git add -A`**；按批次提交。**机械改动逐个 `Edit`**（R4 曾用脚本切片误删 10 个测试方法）
- **Ruling（R-e，预检补扫）**：Task 1 给 `simos-actor-api` 声明 `simos-map` 依赖是**多余**的 ——
  `AssetHolding`（要 `HexCoord`）住 **`simos-actor` 切片**，`AssetClassKey` 只用 `AssetKind` + `Map<String,String>`。
  ⇒ **不声明**，等真有 api 侧类型用到 `HexCoord` 再加。
  —— **错了的代价**：Task 3 若真需要 map 类型 ⇒ 编译立刻失败，加一行依赖即可（低）。
  ⇒ 已就地改计划（Task 1 Step 2）。

## 执行记录

- **Task 1 dispatched**（BASE=`f870c0c`）：实现 Agent = sonnet 档；brief `task-1-brief.md`，报告 `task-1-report.md`
  ★ 随 dispatch 带过去的裁定：**R-e**（actor-api 不声明 `simos-map`）+ "ban 列表只填本模块，回填别的模块是 Task 9 的事"

### Task 1 实现报告（`4872f44`，DONE_WITH_CONCERNS）

- **Ruling（计划缺陷 1 —— 变异写法）**：brief Step 5 写"加一条**不带 `<version>`** 的 `simos-app` 依赖"，
  但那种写法在 **POM 校验阶段**就红（根 POM 的 `dependencyManagement` 不管 `simos-app`），
  **根本走不到 enforcer** ⇒ 证明不了护栏。实现 Agent 补了 `<version>${project.version}</version>`
  （坐标与位置一字不动）才让 enforcer 真的开火。
  —— **为什么**：**变异必须打到被测的那一层**，否则"红"来自别处，等于没测。
  —— **错了的代价**：低（两段红证据都留在报告里，可复核）。
  ⇒ 后续任务若还要写变异体：**先确认它真的触发被测机制**。
- **Ruling（计划缺陷 2 —— 逐任务验证命令不全）**：brief 的 Step 4/5 只跑 `package`，
  而本仓 **`spotless-check` 绑在 `verify`** ⇒ 逐任务跑 `package` **看不到格式违规**，
  违规会一路积累到阶段收尾的 `clean verify` 才炸。实现 Agent 自己补跑 `spotless:check`
  并当场发现新文件的 javadoc 不合 google-java-format。
  —— **为什么**：**门禁要跑在最早能发现它的地方**，不能都堆到收尾。
  —— **错了的代价**：低（补一条命令即可）。
  ⇒ **从 Task 2 起，每个任务的验证命令加 `spotless:check`**（本仓自己的 `spotless:apply` 修）。
- **Task 1 评审 dispatched**：Review Package = `review-f870c0c..4872f44.diff`（1 commit / 8337 bytes），
  评审 Agent = sonnet 档，只读

- **Task 1: complete**（commits `f870c0c..4872f44`，review clean —— Spec ✅ / Task quality Approved / 无 Critical / 无 Important）
  - ★ 评审确认两处偏离**都是对的、不是图省事**：R-e（未声明 `simos-map`）与版本号变异体
    （评审**独立复核**了根因：根 pom 里 `simos-app` 只作 `<module>` 出现、`dependencyManagement` 里没有它）
  - ★ 评审指出变异证据比 brief 要求的更强：它证明了规则**拦得住传递依赖**，
    且顺带证明 8/9 条 exclude 字符串**真的能匹配到可解析坐标**（拼错的 exclude 是静默 no-op）
  - ⚠️ 三条 `Cannot verify from diff` 的处置：
    - ①「一次只能跑一个 Maven」→ 进程约束，控制方按 dispatch 纪律执行，**已解**
    - ② 本模块未过收尾 `clean verify` → **按计划如此**，Task 9 拥有
    - ③ `AGENT.md:41-52` 模块表还没有 `simos-actor-api` 行 → **Task 9 Step 5 拥有**（计划里已写改 `AGENT.md:28-63`），**已解**
- **Task 1: minor (deferred)** ×6（**不进修复环**，留给收尾的全支评审分级）：
  1. `pom.xml:25-27` 注释里「不被任何领域模块依赖」与**同句前半段**（"economy-api 反过来依赖本模块"）自相矛盾，
     且与 `package-info.java:7-9` 冲突 ⇒ 应改成"不被任何领域模块**反向**依赖"或删掉该分句
     ★ **该注释是本仓的边界文档，写错会传给后来人** —— 见下 Ruling R-f
  2. `simos-actor-api/pom.xml:61` 与 `package-info.java:12` 的 message 写了"util/map/jackson 可用"，
     但 R-e 已把 `simos-map` 排除 ⇒ 将来 grep 这句话的人会读成"这里能用 map" —— 见下 Ruling R-f
  3. **[plan-mandated]** `pom.xml:51-59` 是**枚举式黑名单**（今天 9 个模块）⇒ 将来新增的模块**默认放行**，
     正是 `AGENT.md:55-58` 记录的同一个失败 ⇒ 自维护写法（`io.mosire:*` + includes 白名单）更好 —— 见下 Ruling R-g
  4. `pom.xml:53` 的 `simos-ledger` 是变异**唯一没打过**的一条（它不在 app 的传递树里），
     正确性只靠根 pom 的 `<module>` 声明 ⇒ Task 9 补一次单行变异即可关闭 —— 见下 Ruling R-g
  5. **[plan-mandated]** `pom.xml:20-22` 的 `simos-util` **零 import**（模块还是空的）—— brief 要求，照留；
     等 Task 2/3 把类型搬进来后若仍无人 import 就删 —— 见下 Ruling R-h
  6. **[plan-mandated]** brief `:39-41` 的变异 XML 不带版本号、证明不了 enforcer ⇒ **已就地改进计划**
     （Global Constraints 加"变异必须打到被测的那一层"），实现 Agent 的偏离就是正确解

- **Ruling（R-f）**：上表 Minor 1、2 是**本仓边界文档里的事实性错误**，且它们所在的文件
  **正是 Task 2 本来就要动的**（`simos-actor-api/pom.xml` 要加依赖；根 pom 的注释紧邻 Task 1 那两行声明）。
  ⇒ **随 Task 2 的 dispatch 一起修**（两条各一行），**不**留到收尾评审。
  —— **为什么**：评审自己指出"写错的边界注释会传给后来人"，而 Task 2–9 每个实现 Agent 都要读这两处。
  —— **错了的代价**：近乎零（改两条注释）。
- **Ruling（R-g）**：Minor 3（枚举式黑名单不防将来）与 Minor 4（`simos-ledger` 那条 exclude 没被变异打过）
  ⇒ **都归 Task 9**，因为 Task 9 本来就拥有 ban 列表回填（`util`/`map`/`social`/`unit`），
  那时有全貌可以决定是否换成自维护写法（`io.mosire:*` + includes 白名单）。
  —— **为什么**：Task 1 已关账且 Approved，为一个设计改进重开它是流程要避免的churn。
  —— **错了的代价**：将来新增模块在 actor-api 里**默认放行** —— 正是 `AGENT.md:55-58` 已记的那类欠账。
- **Ruling（R-h）**：Minor 5（`simos-actor-api` 声明了 `simos-util` 却零 import）⇒ **随 Task 2 处置**：
  四个类型搬进来之后若仍无人 import `simos-util`，**就删掉这条依赖**（Task 2 正是让这个模块不再为空的提交）。
  —— **错了的代价**：低（真需要时编译立刻失败，加回一行）。

- **Task 2 dispatched**（BASE=`4b3e3e5`）：实现 Agent = sonnet 档；brief `task-2-brief.md`，报告 `task-2-report.md`
  ★ 随 dispatch 带过去的裁定：**R1**（纯机械迁移，AssetKind 六档一字不动）、**R4**（`ActorRef.parse`
  两参签名原封不动）、**R5**（不加转发壳，编译失败即迁移清单）、**R7**（本任务是 A 段关账点）、
  **R-e/R-f/R-h**（不声明 map / 修两条边界注释 / 搬完删空的 `simos-util` 依赖）、
  以及两条新 Global Constraints（验证命令含 `spotless:check`；**变异体必须打到被测的那一层**）
  ★ 明确告知：任务太大就报 `BLOCKED`，控制方会拆块 —— **烂活比不干更糟**

### Task 2 实现报告（`7613599`，DONE，48 文件 / +244−161）

**对照数字**：基线 `clean verify` **2306 绿** → 收尾 **2307 绿**
（+1 = 新加的 characterization test；`simos-actor-api` 0→4、`simos-economy-api` 13→9 —— 四条护栏**换模块住**，不是新增）。
23 个受影响测试类**逐个单独跑**全绿并以 surefire 报告核对；SpotBugs 全 0；**268 份报告 mtime 全落本轮**。
三份类型**逐字节只改 `package` 行**；`AssetKind` 六档一字未动。变异自证：`ActorRef.parse` 改单参逆
⇒ 编译红且**只**在搬过来的 3 处调用点报错（**全仓无别的调用者**——这本身就证明了调用面清点是全的）。

- **Ruling（R-i，疑虑 1）**：`ActorRef.java` 的 javadoc 仍引述设计稿"各领域只依赖 api/util/map"，
  **判定不改**。理由：那句话转述的是**设计稿对"领域模块"的约束**，而 `ActorRef` 现在正是"api"这一层的一员、
  零 map 用法，**不与之冲突** ⇒ 不是事实性错误，是**适用对象不同**。若 Task 9 做文档回填时想统一措辞，那时再说。
  —— **错了的代价**：一句措辞可能让读者多花一秒（低）。
- **Ruling（R-j，疑虑 2）**：`simos-actor-api` 仍声明 `jackson-databind` 而**零 import** —— 与 R-h 的
  `simos-util` **完全同型**。实现 Agent 因"R-h 未授权"而**没越权删**，这个判断是对的。
  ⇒ **把 R-h 扩展到 `jackson-databind`**，但**动作推到 Task 3 结束**：Task 3 是第一个真正往 api 加类型
  （`AssetClassKey`）的任务，那时才看得出要不要 jackson。若仍零 import ⇒ 删。
  —— **错了的代价**：低（真要时编译失败，加回一行）。
- **Ruling（R-k，疑虑 3）**：计划 Task 9 Step 2 预设"要给 `simos-core` 补 actor-api 的 test include"
  —— **实测不需要**（actor-api 不在 core 的 ban 列表里）。⇒ **删掉该步骤**，改成"实测不需要；
  若将来真有模块把 actor-api 传递进 core 的 test classpath，构建会红，那时再按第 110-118 行那套写法补"。
  —— **错了的代价**：低（构建红即发现）。
- **★ 疑虑 5 是评审的**重点靶子**：实现 Agent 如实记录了"删四条测试时一个 `Edit` 误删类尾 `}`，
  当场 `tail` 发现并修回"。**提交态结构是否正确，必须由评审独立验**（不许采信自述）。
