# SDD ledger — plan: docs/superpowers/plans/2026-09-26-s1-stage45-ownership-and-relations.md

Spec: docs/superpowers/specs/2026-09-26-s1-actor-property-design.md（含 2026-09-26 追加-1..4）
Breakdown: docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md §三 阶段 4 / 阶段 5
★ 执行方式（用户 2026-09-26 裁定）：**不派独立评审 Agent、不跑修复环**；
验收 = 计划里那套判据（I4.1/I4.2/I4.3 + I5.1–I5.4），在**阶段边界**由控制方自己核。
★ 用户总指令：「一口气干完跑模拟，模拟结果出来你自己先检查，有问题自己改，有问题需要裁决再找我」

## 控制方裁定（派活前已写进计划）

E1 守恒式必带 `ΔΣRowGoods`（spec §五 那条是终态特例）· E2 `household` 档改「实物分成给劳动者 + 自留」
（spec §六 与 §七 V3 内部矛盾，取 V3）· E3 契约落 `economy-api`（不落 actor-api，否则循环）
· E4 单列表 + `priority`，租显式给 `(hex, landlord)` cohort · E5 `Basis` 补第 6 档 `FIXED_AMOUNT`
· **E6 `ClassKey → CohortKey` 本阶段不做（★ 第二次推迟，V9/I1.2 记为未达成项，不许静默）**
· E7 完整账路径唯一化为 app 协调器（`EconomySettlement.settle` fail-closed、删 `EconomyTimeParticipant`）

## 任务清单

- [x] T1 契约：`CohortKey` + `relation` 包（economy-api）
- [ ] T2 `EconomyData.relations`（第 8 组件）+ `RegimeRelations` 四档推导 + 载荷 `relation?`
- [ ] T3 `ProductionSettlement` 纯函数（公式表 + priority 序）★关账点 A（行为未变）
- [ ] T4 `harvest` 切换：产出不进 `ClassRow`，改「入 ledger + cohort 入账」；`settle` 多日入口 fail-closed；删 `EconomyTimeParticipant`
- [ ] T5 app 协调器（`OwnershipBooks` + 新 `EconomyOwnershipTimeParticipant`）★关账点 B（T4+T5 必须同批）
- [ ] T6 `weave@hex|*` 四行的布逐行 0 + operator 有布 + farm 行拿到布（I4.3）
- [ ] T7 同格同产能佃制 ≠ 庄园制；GROSS 30% vs NET 30% 差 = 30%×损耗（I5.1/I5.2）
- [ ] T8 逐条读数 + `clean verify` + 未达成项如实记 ★关账点 C

## 执行记录

- **T1: 完成**（`f3ff906` 6 主文件 + 1 测试 / +981；`a020f79` 台账）—— `CohortKeyTest` **14/14**；
  `-pl simos-economy-api -am verify` BUILD SUCCESS（checkstyle/spotless ✓、SpotBugs 0、模块 23/23）。
  RED **当场捕获**（`testCompile` 编译错、surefire 未跑）；**6 条变异体**逐条当场红、
  逐个 `md5sum -c` 证明还原到最终字节。
- **★★ Ruling（E8 —— brief 的变异体是"等价变异体"，第五次，且这次源头是我）**：
  brief 的变异体①（`parse` 按**最后**一个 `|` 切）在**合法串**上是**等价变异体** ——
  两个分量都不含 `|` ⇒ `indexOf ≡ lastIndexOf` ⇒ **往返不红**。**照抄会得到"绿得骗人"的第五次。**
  实现者补了「**接缝之后整段交给阶层词表**」的断言才让它红；"逆写错"那一层由 M3（两段接反）覆盖。
  ⇒ **裁定：实现者的更正是对的。** ★ 教训已**五次**复现（红在 POM 校验 / 红在 checkstyle /
  打在不经 codec 的入口 / **空转变异体** / **等价变异体**）⇒ **升级为通行准则**：
  **变异体必须与被测规则"可观测地不同"——先证明它红了，再算数。**
- **Ruling（E9 —— 疑虑 2）**：`SELF_RETENTION` 属**实物档** ⇒ 按二选一守卫**必须带 `commodity`** ⇒ **保持原样**。
  依据：fail-closed；且"不写这条规则 ⇒ 余额归 `residualOwner`"是一条**干净的等价路径**，T2 有路可走。
  ★ **现在改契约最便宜**，所以这条要在 T2 派活前定 —— **定了：不改。**
- **Ruling（E10 —— 疑虑 3）**：`api/package-info.java` 的"只放稳定 ID"口径需放宽一格 ⇒ **T2 (或 T8) 回填**。
  ★ `docs/.../stage45` 计划文档与 brief 目录**已由控制方入库**；stage2 `progress.md` 那处未提交改动
  **是控制方自己错放的**（stage-3 台账目录当时还没建），照留痕不动、一并提交。
- **Ruling（E11 —— 疑虑 4）**：`type × basis` 的组合守卫**有意不加**（T3 的公式表才是落点）；
  `ASSET_QUANTITY` 只有夹具覆盖（真档未种入 `AssetHolding`）⇒ **接受，记为已知边界**。
