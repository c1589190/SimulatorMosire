# S1（经济产权改造）外部评审指南

> **给评审者的用法**：先读本文 §1–§3 建立地图，再按 §4 挑着你认为最可疑的设计决定深入。
> ★ **本文写明了"哪些证据是薄的"**（§5）——那是你最该挑战的地方，不是我们藏起来的地方。

**范围**：S1 阶段 1–4+5（已完成）。阶段 6/7 未做。
**分支**：`ts/m1`。**本阶段为止的净改动**：129 文件 / +10,762 / −419；新增主代码 20 文件。

---

## §1 要读的设计文档（按序，共约 2,300 行）

| # | 文件 | 是什么 |
|---|---|---|
| 1 | `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md` | **S1 设计稿**（主体 418 行 + 2026-09-26 追加的四条裁定） |
| 2 | `docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md` | 七阶段划分与**判据 I1–I7** |
| 3 | `docs/superpowers/plans/2026-09-26-s1-stage2-actor-slice.md` | 阶段 2 计划（**契约与切片**；含裁定 R1–R7） |
| 4 | `docs/superpowers/plans/2026-09-26-s1-stage3-operator-binding.md` | 阶段 3 计划（**绑定 operator**；含裁定 D1–D8） |
| 5 | `docs/superpowers/plans/2026-09-26-s1-stage45-ownership-and-relations.md` | 阶段 4+5 计划（**产出归属 + 生产关系结算**；含裁定 E1–E7） |
| 6 | `docs/superpowers/specs/2026-09-26-population-economy-v3-design.md` | 经济系统 v3 设计（**背景**：为什么会有这次改造） |
| 7 | `docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md` | 聚合式经济改造（**背景**：旧口径） |

★ **要读的是"怎么设计的"**，不是"怎么做测试的"。测试报告可以跳过。

## §2 新代码的设计载体（**这些才是设计落地的地方**）

### 2.1 新模块 `simos-actor-api`（契约层，6 文件 / 301 行）

| 文件 | 行 | 承载什么设计 |
|---|---|---|
| `.../actor/api/actor/ActorRef.java` | 71 | **主体身份键**（`kind + id`）；★ `parseCanonical` 是"裸 `toString()` + 单参 `parse`"配对的实例 |
| `.../actor/api/actor/ActorKind.java` | 60 | 主体种类**七档词表** |
| `.../actor/api/asset/AssetKind.java` | 15 | 资产**粗类型**六档 |
| `.../actor/api/asset/AssetClassKey.java` | 127 | ★ **资产同质性键**（粗类型 + qualities，规范串键有序） |
| `.../actor/api/package-info.java` | 19 | **模块边界声明**（谁可以依赖它） |

### 2.2 新模块 `simos-actor`（切片，15 文件 / 1,604 行）

| 文件 | 行 | 承载什么设计 |
|---|---|---|
| `.../actor/ActorData.java` | 190 | ★ 状态树：**四张互不嵌套的表**（meta / actors / holdings / accounts）+ 三个 wither（键从值派生） |
| `.../actor/model/Actor.java` | 29 | ★★ **身份本体只有 `ref` + `label`**（资产与库存**都不在**它里面） |
| `.../actor/model/AssetHolding{Key}.java` | 36 + 91 | ★★ **产权**：聚合键 `(owner, hex, assetClass)` |
| `.../actor/model/GoodsAccount{Key}.java` | 69 + 86 | ★★ **库存余额**：键 `(owner, hex)`；0 余额保留 |
| `.../actor/change/ActorChangeSet.java` | 121 | 变更集（四组件逐条 `FieldDelta`） |
| `.../actor/codec/ActorCodec.java` | 188 | ★ **四路 Map 键的 Jackson（反）序列化走 `.toString()`/`.parse` 配对** |
| `.../actor/spi/ActorPayloads.java` | 363 | 载荷校验（fail-closed） |
| `.../actor/spi/ActorSeedHandler.java` | 125 | 命令面 `actor.Seed` |
| `.../actor/resolve/ActorResolver.java` | 169 | 地址解析 |

### 2.3 既有模块里的关键新增（阶段 3 / 4+5）

| 文件 | 行 | 承载什么设计 |
|---|---|---|
| `simos-economy-api/.../cohort/CohortKey.java` | 92 | ★★ **人口身份**（`residence × SocialClassId`，**不含产业**） |
| `simos-economy-api/.../relation/*.java` | 4 文件 269 | ★★ **生产关系契约**：`RuleType` · `Basis`（**六档**，含新增 `FIXED_AMOUNT`）· `Recipient` · `CompensationRule` · `ProductionRelation` |
| `simos-economy-api/.../id/SocialClassId.java` | 76 | 阶层**全局词表**（阶段 1） |
| `simos-economy/.../model/Industry.java` | 314 | ★ `operator: ActorRef`（第 16 组件） |
| `simos-economy/.../model/RegimeOperators.java` | — | ★★ **`regime → 默认 operator` 的唯一拼写点**（四档） |
| `simos-economy/.../model/RegimeRelations.java` | — | ★★ **`regime → 默认生产关系`的唯一拼写点**（四档） |
| `simos-economy/.../time/ProductionSettlement.java` | — | ★★★ **公式表 + `priority` 序 + E14 守卫**（"产出怎么分"的算式全在这里） |
| `simos-economy/.../time/EconomySettlement.java` | 大 | ★★ **harvest 的切换点**（产出离开 `ClassRow`）+ `classRowsOfCohort`（受方定池） |
| `simos-app/.../time/OwnershipBooks.java` | 83 | ★ **完整账的路径唯一化**（协调器） |
| `simos-app/.../time/EconomyOwnershipTimeParticipant.java` | 201 | 第三片 actor 的时间参与者 |

## §3 证据与读数（**核对上面的设计是否真的成立**）

| 文件 | 内容 |
|---|---|
| `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/stage45-readings.md` | 机制筛查的逐条真数（I4.1–I5.4）+ 关账读数 |
| `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/sim600-curves.md` | ★ **600 天三国模拟的数据表 + 与第一次的对比 + 三问回答** |
| `.superpowers/sdd/2026-09-26-year-one-simulation/report-v3.md` | **第一次模拟**（改造前）——对比基线 |
| 各 `progress.md` | 逐任务的裁定台账（含每条"**错了的代价**"） |

---

## §4 ★★ 值得挑战的设计决定（**建议从这里下手**）

按"我认为最可能错"排序：

1. ★★★ **开发期不写测试、不做变异自证**（`AGENT.md` §三.0，用户裁定）。
   ⇒ **本阶段的护栏没有任何"判别力证明"**：测试 2518→2518，变异自证一条未做。
   §3 的读数是**真跑出来的数**，但**没有"改了会不会红"的证明**。**这是最该质疑的一条。**
2. ★★★ **`operator` 与"资产所有者"完全正交、无任何字段绑定**。
   可能错：真实世界里"谁经营"与"谁拥有"通常高度重合，这个正交是不是**过度抽象**？
3. ★★ **产出先进 operator 账、再按 relation 分出去（两步）**。
   可能错：多一次搬运 = 多一次不守恒的机会；operator 是"过路"的，为什么要先记它的账？
4. ★★ **cohort 的报酬是"流量"（落消费行），不是库存** ⇒ **cohort 不能储蓄**。
   ★ 数据已示后果：**债务在替粮食缺口买单**（本金 6.05 倍、DEBT 红灯 614 格）。
5. ★★ **守恒式在过渡期必须带 `ΔΣRowGoods` 项**（`E1`）。
   可能错：这说明**过渡期有两套账并存** —— 长期看是不是设计债？
6. ★★ **受方解析按"劳动侧批次"定池**（`E24` 的修法：谁出劳动谁收），
   而**租是按 `(hex, landlord)` cohort 给的** —— 两条路径**不对称**。为什么？
7. ★ **`feudal` 的给养吃掉 ~70% 净产** ⇒ 名义上的"地租 30%"**被截断**。
   ⇒ 这是**参数问题**还是**模型问题**？
8. ★ **布按劳动量分 ⇒ `landlord` 结构性吃不饱**（残余缺口 3.55% **全部**来自它）。
   ⇒ 模型是否该表达"地主有存粮/可买"？还是"不劳动者不得食"就是设计意图？
9. ★ **`CohortKey(residence, SocialClassId)` 不含产业**，但 `laborOfCohort` **仍把农村/城镇两池并进同一 cohort**（份额差 ≤0.04‰）。
   ⇒ 这个键**还不足以**表达真实身份 ⇒ 是键错，还是并池错？
10. ★ **`RegimeOperators` / `RegimeRelations` 用"出厂值常量表"**。
    可能错：把"制度"当成单一实体；真实世界里同制度下的参数差异很大。
11. ★ **两套账并存**：`GoodsAccount` 是"新产权模型唯一真源"，`simos-ledger.Account` 保持 legacy/unwired（去留未裁）。
    ⇒ 合并成本现在没人在付。

## §5 ★ 证据的薄处（**如实清单，别当成没发生**）

- **变异自证：一条未做**（开发期裁定）⇒ 每条护栏"**改坏了会不会红**"**未证**
- **测试：2518 → 2518**（本阶段没写新测试）⇒ 新代码的覆盖**只有既有测试**在守
- **V9 / I1.2 未达成**（"同一 cohort 兼两个 activity 只有一个身份"）—— ★ **第二次推迟**
- **V4/I6.1、V8/I6.2** 属阶段 6，未做
- **`AssetHolding` 未种入真档** ⇒ 产权表在真世界数据里**是空的**（只有夹具覆盖）
- **`EconomyRealScaleClothTest` 第 2/3 周期是弱断言**（降级为"产出 > 0"）
- **阶段 6/7 未做** ⇒ `ClassRow` **仍持有 `goods`/`money`/`debts`**，"一行同时是所有者+生产者+消费者"的病根**还没除**

## §6 别浪费时间的地方

- **不要读测试文件**（16 个测试文件 / 3,119 行）：它们是护栏，不是设计
- **不要逐行读 `EconomySettlement.java`**（1,800+ 行）：只看 `harvest` 的切换点与 `classRowsOfCohort`
- **不要评审 `AGENT.md` 的流程条目**（§三.0 是用户裁定，已生效）
- **不要评审阶段 6/7 的设计**（还没写）
