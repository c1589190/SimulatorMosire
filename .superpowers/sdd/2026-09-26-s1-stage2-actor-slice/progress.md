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

- **Task 2: complete**（commits `4b3e3e5..7613599`，review clean —— Spec ✅ / Approved / 无 Critical / 无 Important）
  ★★ **评审独立验实了两个"不许采信自述"的靶子**：
  - **逐字节声明成立**：三个搬走的文件各自**只有一个 hunk**，且 `-`/`+` 对只有 `package` 行；
    评审还**自己读了被 hunk 截断的文件尾**（`AssetKind:8-15` 六档原样、`ActorRef:28` 两参签名原样、
    `ActorKind:29-59` 七档原序）—— 这正是"给风险命名再定点核查"的正确用法
  - **误删的 `}` 不在提交态里**：删除块恰是**四条完整方法 + 一个空行**，类尾 `}` 是**未改动的 context 行**，
    文件里恰剩一条 `@Test`
  - 另：`R5/I2.3` 是**结构性证明**（`actor/` 目录**不再存在**），不是靠 grep 推断

- **★★ R7（A 段关账条件）：控制方独立实跑 `clean verify` —— BUILD SUCCESS，12/12 模块**（新增 `ActorApiSimos`）
  —— 评审明确标记"这是实现者声明、我看不到运行输出"，故**由我解**（放行不能建立在自述上）：
  | 量 | 实现者自报 | 控制方实跑 |
  |---|---|---|
  | 测试总数 | 2306 → 2307 | **2307 / 0 失败 / 0 错误** ✓ |
  | `actor-api` | 0 → 4 | **4** ✓ |
  | `economy-api` | 13 → 9 | **9** ✓ |
  | `economy` | 127 → 128 | **128** ✓ |
  | surefire mtime | 全落本轮 | **0 份陈旧 / 268 份** ✓ |
  | SpotBugs | 全 0 | `BugInstance size is 0` ✓ |
  ⇒ **三个数字逐个吻合，R7 成立。**

### A 段关账（Task 1 + Task 2）—— 五条 Minor 的裁定

- **Ruling（R-m）**：`simos-actor-api/pom.xml:59` 与 `package-info.java:12-13` 的措辞
  「S1 阶段 2 起 util 与 map 都不再声明」**读起来像"以前声明过 map"**（其实 R-e 一直是"不声明"）。
  ⇒ 改成「**本模块只声明 jackson（util/map 都不声明）**」，**随 Task 3 一起改**（Task 3 本来就要为 R-j 动这个 pom）。
  —— **错了的代价**：低（措辞）。
- **Ruling（R-n，改判 R-i）**：`ActorRef.java:7` / `ActorKind.java:9` 那句"设计稿 §2 明确 api 只依赖 util/map"
  现在**与两个文件之外的 pom 自相矛盾**（评审指出：等价的老文本在别处被修了、这两处没修，**不一致**）。
  ⇒ **改**，但**推到 Task 9 的文档回填**（与 `AGENT.md` 模块表同一批）——
  理由：B 段的纪律是"**不得回头修改迁移契约**"，而这两个文件**就是**迁移契约；文档任务里改才不与关账纪律冲突。
  —— **错了的代价**：一句过时注释多留几个任务（低）。
- **Ruling（R-o）**：**陈旧的归属声明**（`simos-economy-api/pom.xml:14` 的 `<description>` 仍写 economy-api 提供
  "ActorRef…"；根 `pom.xml:30` 仍说 social/ledger "共用它的稳定 ID 与 ActorRef"）⇒ **也归 Task 9 文档回填**。
  —— **为什么**：与 R-n 同批，且它们都是**同一个模块图的描述**，一起改才不会又出现"改一半"的不一致。
  —— **错了的代价**：低（但会误导下一个人找是谁拥有 ActorRef）。
- **Ruling（R-p）**：评审列出的"**超出四类允许改动**"的三处（`actor` 的 `package-info` 随迁移搬走并重写首句、
  `economy-api` 的 `package-info` 删掉**已悬空**的 `{@link …economy.api.actor.ActorRef}` 并补一段、
  根 `pom.xml:27` 顺手把 `util` 从 actor-api 描述里去掉）⇒ **判定可接受**。
  —— **为什么**：留着 `{@link}` 会变**悬空链接**（本仓在阶段 1 刚因同类问题抓过一次真缺陷），
  且"虚假归属声明"正是本任务要消除的那类东西；三处**都是文档、都已披露、都与迁移事实一致**。
  —— **代价**：A 段的"允许改动类别"实际是**五类**（多一类"修正随迁移产生的**悬空/虚假文档引用**"）——
  记在这里，免得收尾评审判它越界。
- **Ruling（R-q）**：`EconomySeedHandlerTest:169-171` 与既有 `:149` 部分重叠 ⇒ **保留**（brief Step 1 要求，
  且该测试的 ② 是**别的测试没覆盖的方向**：再编码回去）。**不删**。
- **Ruling（R-k 扩展）**：⚠️ 评审指出 `simos-core` 的 test classpath 现在**经 `social → economy-api → actor-api`
  传递到了 actor-api**，而 `simos-core/pom.xml` 里**没有** actor-api 的排除项 ⇒ 今天不炸，
  但**边界处于未强制状态**。⇒ **Task 9 的回填范围加上 `simos-core` 的 `enforce-core-boundaries` excludes**
  （宽 exclude + 需要时窄 include），与 `util`/`map`/`social`/`unit` 的 ban 回填同一批。
  —— **错了的代价**：将来 core 悄悄依赖 actor-api 而无人拦（与 `AGENT.md:55-58` 记的是同一类欠账）。

### 计划维护项（**派任务前必须先做**）

- ★ Task 3 的测试草稿原有 `...` 占位 ⇒ **已补全**（含三条 fail-closed 用例：词表外、无 `=`、**键重复**）
- ★ **Task 5 / Task 6 / Task 7 的测试草稿里仍有 `...` 占位** —— 按 writing-plans 的 "No Placeholders"，
  **各自的 dispatch 之前必须先补成可执行代码**（与 Task 3 同样处理）。

- **A 段关账 + Task 3 dispatched**（BASE=`7613599` 后的台账提交）：B 段开始 —— `AssetClassKey`（新建，TDD）

### Task 3 实现报告（`0a2f95c`，DONE，5 文件 / +205−11）

**数字**：`AssetClassKeyTest` **5/5**；全仓 `clean verify` **12/12 SUCCESS，2312 测试 / 0 失败**
（A 段基线 2307 + 5）；SpotBugs 全 0；**269 份 surefire 报告 mtime 全落本轮**。
★ 提交里**没有** `ActorRef`/`ActorKind`/`AssetKind`/`ActorTypesTest` ⇒ **B 段纪律（不得回头改 A 段契约）成立**。

★ **变异自证做得比要求更细**：brief 的变异体（TreeMap→LinkedHashMap）**确实打到被测那一层** ——
红在 `hasToString`（2 failures），而 `isEqualTo`/`hasSameHashCodeAs` **过了**。
实现 Agent 还**先验了判别力**：`arable`/`quality` 的 `hashCode % 4` 都是 3，在 `Map.of` 的 4 槽表里**恒撞位**、
两序恒相反（10 次 JVM 启动实测）—— 这是"先证明夹具真的能分辨，再拿它当判据"。
另补三条 fail-closed 的变异（删重复键/删缺 `=`/删词表）各自红在对应断言；四个变异全部还原、grep 复查无残留。

- **Ruling（R-s，疑虑 1 —— 我的示范句是错的）**：我在 R-m 里写的示范句「本模块只声明 jackson」
  与 **R-j（删 jackson）当场冲突** ⇒ 照抄会写出**当场为假**的话。实现 Agent 按 R-j 之后的事实措辞
  （"一条都不声明"）**是正确的**。
  —— **为什么**：**裁定之间会互相覆盖，后一条生效**；实现者按事实写、并把冲突报上来，是对的做法。
  —— **错了的代价**：无（措辞）。
- **Ruling（R-t，疑虑 2）**：根 `pom.xml:27`「只依赖 jackson」被 R-j 弄假 ⇒ 一并改成"不声明主依赖"。
  **判定可接受**（R-p 口径：随事实修正的文档引用），**可回退**。代价：低。
- **Ruling（R-u，疑虑 4）**：SpotBugs `EI_EXPOSE_REP` 按本仓「只读包装写在赋值处」消掉，
  **未**用 `@SuppressFBWarnings` 压制 —— ★ **这是对的**：压制注解会把一类真缺陷永久静音，
  而本仓的 `DEFENSIVE_COPY` 口径就是要**真的**做防御性拷贝。
- **★ 疑虑 3 交给评审判，我不预判**：实现 Agent **自陈**加了一条 brief 未列的第 7 条校验
  （qualities 的键/值不得为空白），并说"5 条用例不依赖它" ⇒ **若真无测试覆盖，它就是未被验证的分支**。
  —— 按 SDD 纪律**不许给评审预判**，所以我把这条**留白**，让评审自己报，再在修复环里裁。

### 计划维护项已完成（Task 5/6 的测试占位补全）+ 一处真计划缺陷

- ★★ **写测试时发现计划缺陷（已就地修）**：Task 5/6 原只把 `holdings`/`accounts` 暴露成 `Map`，
  那样"聚合键是 `(owner, hex, assetClass)`"就**退化成 `java.util.Map` 自己的语义** ——
  **测试恒真、判别力为零**。⇒ 补 **`ActorData.withHolding` / `withAccount` / `withActor`** 三个 wither，
  让"**键从值派生**"只有一个拼写点。
  —— **为什么重要**：这正是本仓反复踩的"判别力假货"（`AGENT.md` §9.1）——测试全绿但什么都没验。
  —— **错了的代价**：中（若不加，Task 5 的 I2.2 判据是**空的**，而它正是本阶段的验收条件之一）。
- Task 5 测试占位 **5 条补全**（含"只差一段 ⇒ 各自成条"、"同键后写覆盖前写"、
  **I2.2 后半句的形状断言**：把 HOUSEHOLD 建成 actor **不会**给它新增任何产权）
- Task 6 测试占位 **4 条补全**（含 R6 的落点：0 余额**保留**不归一成空表、余额表是**防御性拷贝**）
- ★ **Task 7 的 Step 1 仍是散文**（"照 `EconomyCodecTest`：正例 + 空态 + 非法态"）
  —— 它指了一个**具体的范本类**，且 Task 7 的真判据是 Review Focus ⑤（空 `ActorData` 往返**逐字段**相同），
  故**判定可接受**，但 dispatch 时要明写这条判据。

### Task 3 评审（Spec ❌ / Task quality Approved / 1 Important · 5 Minor）

★ 评审**确认**了那条跨文件否定性声明（B 段纪律）：commit 清单恰 1 条、stat 恰 5 个路径，
**没有任何 hunk 指向** `ActorRef`/`ActorKind`/`AssetKind`/`ActorTypesTest` ⇒ **R7 成立**。
★ 评审还**独立核实了门禁绑定**（`spotless:check` 在 verify、checkstyle 在 validate、spotbugs 在 verify）
⇒ 印证了 A 段那条"逐任务命令必须含 spotless"的教训。

**★ Important #1（进修复环）**：`AssetClassKey` 的 **7 条校验里有 3 条零测试、零变异自证**
（`checkText` 的空白分支、`checkText` 的分隔符分支、`kind == null`）——**删掉任何一条，5/5 照样全绿**。
其中**含分隔符那条是"可逆性"的承重墙**：没有它，`new AssetClassKey(LAND, Map.of("a|b","1"))`
会产出规范串 `"LAND|a|b=1"`，而 `parse` **自己拒绝**它 ⇒ **构造函数造得出"自己的规范串解析不回来"的键**。

- **Ruling（R-v，对 Minor #1 的处置）**：那条**拒空白**的第 7 条校验 **保留**，按"一条规则一条断言"补测试。
  —— **为什么**：它符合本仓成例（`SocialClassId` / `ActorRef.id` 都拒空白），拒的是**退化输入**，
  属 fail-closed 方向；**删它才是放宽**。评审指出的"零保护"是**覆盖率**问题，由本轮修复解决。
  —— **错了的代价**：低（一条校验分支）。
- **Ruling（R-w，评审"部分是虚的"那条不修）**：评审指出该分支**只拒全空白、不拒前后有空白**
  ⇒ `"B"` 与 `"B "` 仍是两个身份、两条规范串。**判定：不做归一化**（trim/规范化是 brief 之外的扩张）。
  它是一个**数据纪律**问题，**不是可逆性 bug**（两种写法各自往返都成立）。**记在案**。
  —— **错了的代价**：低但真实 —— 上游若真塞进带空格的值，会产生"看起来一样的两份产权"。
- **Minor 2–5 不入环**（记账，留给收尾全支评审分级）：②测试判别力耦合 `Map.of` 内部实现
  （换 JDK 可能**静默失效**）③`parseKind` 丢 cause ④`split` 的正则转义写法可读性 ⑤测试 3 验的是
  string→object→string 而 javadoc 的断言是 object→string→object

- **Task 3: fix round 1/5**（`0a2f95c..5323e54`，1 addressed / 0 open；1 文件 / +47，**实现文件零改动**）
  ★ 实现 Agent 的证据链很干净：三条新用例**当场就绿**（无需改实现）⇒ 印证评审说的是**覆盖率缺口、不是行为缺陷**；
  三个变异 M-A/M-B/M-C **各自只红对应那一条**（每次 `Tests run: 8, Failures: 1`）⇒ 规则↔断言一一对应；
  还原证据是**空的 `git diff`**。
  ★ scoped 复评 dispatched（fix base `0a2f95c` → head `5323e54`）。

- **Task 3: scoped 复评 = All findings addressed，无新破损** ⇒ **修复环闭环**
  ★ 复评**逐条走了"删掉该分支后会发生什么"**来独立判断判别力（变异运行是瞬态的），并**独立在磁盘上核到**
  surefire 报告 `AssetClassKeyTest.txt` = `Tests run: 8, Failures: 0`、XML `tests="8"`
  —— 这正是"报告是未验证声明、要对着证据核"的正确用法。
  ★ **"实现文件零改动"独立确认**：fix diff 里只有 3 个 `diff --git` 头（两条 .md + 测试），
  全文 grep 主实现路径**命中 0**；测试那份 hunk 是 **+47 / −0** ⇒ 原 5 条用例一字未动（没有"顺手改断言"）。
  ★ 复评还核了：新断言**不反向依赖** `Map.of` 的哈希迭代序；null 值夹具用 `Collections.singletonMap`
  （`Map.of` 不许 null 值，选对了）；两条**文档提交只改 `.md`**、无代码夹带。

- **★★ B 段关账：控制方独立实跑 `clean verify` —— BUILD SUCCESS，12/12 模块，SpotBugs 0**

- **Task 3: minor (deferred)** —— 复评的两条越界观察：
  1. ★ **`parse` 顶部平凡守卫（`:69-71`）与 `parseKind` 的空白守卫（`:105-107`）仍无断言**
     （后者可达：`parse("|a=b")`）。本轮 finding 只点了三条，故不阻塞。
     ★★ **并记一笔报告口径问题**：实现报告的"7 条校验 / 7 组断言 / 7 组变异证据"**略微夸大**
     —— 规则 3 的"空白面"仍无断言。**这是报告准确性，不是代码缺陷**，但记在案：
     **"都覆盖了"这种总括句必须能被逐条点开。**
  2. ★★ **计划里的代码片段本身不是合法 Java**（Task 5 的 `.as()` 里嵌了 ASCII 双引号）⇒ **已就地修**
     （改成 `「」`，同时把 `empty()` 的省略号伪代码改成"写成当下真能编译的那一个"）。
     —— **归类**：与 A 段那条"变异体走不到被测那一层"**同属一类**：
     **派活件的缺陷会直接变成实现者的缺陷。**⇒ 派活前自查"逐字照抄能不能编译"。

- **C 段开始 —— Task 4 dispatched**（BASE=`5e9096f`）：建 `simos-actor` 切片 + `Actor`/`ActorMeta`/
  `ActorData`（**只两张表**）/`ActorSnapshot`/`ActorChangeSet`/往返测试。实现 Agent = sonnet 档。
  ★ 随 dispatch 带过去的裁定：**`namespace()` 恒 `"actor"`**（三处同字面）、**按"增量表"只建两张表**、
  **`withActor` 必须有**（键从值派生只许一个拼写点）、**`Actor` 只许 `ref`+`label` 两个组件**（spec §三 禁令）、
  **R2 两条 enforcer 护栏 + 变异自证**、**依赖只声明真 import 的**（`simos-economy-api` 留到 Task 6）、
  **验证用 `verify` 不是 `package`**、**往返测试必须逐字段**
  ★ 明确告知：需要改 A/B 段产物 ⇒ 报 `BLOCKED`，不许就地改

- **Ruling（R-x，`ActorMeta` 的组件 —— 计划漏写，实现者提问后裁定）**：
  `record ActorMeta(String mapId, long activatedDay, String rulesVersion)` ——
  照 `EconomyMeta` 的**形制**，但**去掉 `lastClosedCycle`**（actor 切片没有周期）
  **也去掉 `migrationSource`**。
  —— **为什么去掉 `migrationSource`**：`EconomyMeta` 有它是因为**旧的经济载荷格式真的存在过**；
  而 spec §十.4 明令「旧档重建也没关系、**不做迁移工具**」⇒ 本切片的它**永远只会是空**。
  **不加一个永远为空的字段**（与 R-e 不声明 `simos-map`、R-h 删零 import 的 `simos-util`、
  Task 4 不提前加两张表同一条口径）。
  —— **错了的代价**：低（真要迁移时加一个字段 + 改 codec；而 spec 已明说不会做迁移）。
  ★ **实现者把"照形制"与"照抄字段集"分开看，这个判断本身是对的** —— 它只错在把 `migrationSource`
  也当成了"形制"的一部分。**计划已就地补上这段定义**（含"为什么去掉这两样"的表）。
