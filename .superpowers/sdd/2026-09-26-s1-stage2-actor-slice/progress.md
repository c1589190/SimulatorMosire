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
