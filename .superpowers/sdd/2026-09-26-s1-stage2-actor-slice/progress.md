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
