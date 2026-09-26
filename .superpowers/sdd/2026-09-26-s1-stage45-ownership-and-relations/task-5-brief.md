### Task 5: ★ 协调器（app）：产权落账 + 两个参与者的接线

**Files:** Create `simos-app/src/main/.../time/OwnershipBooks.java`、`.../time/EconomyOwnershipTimeParticipant.java`；Modify `.../time/PopulationEconomyTimeParticipant.java`；Modify 夹具：`EconomyTestWorld.genesis()`（加 `actor` 快照）、`PopulationEconomyFixture`（加 `actor`）、`WorldgenInitializeToolTest`/`EconomySettlementEndToEndTest` 的参与者注册；Test `OwnershipBooksTest`（新建）、`ActorEconomyOwnershipTest`（新建，端到端）

**Interfaces:**
- Produces: `OwnershipBooks.apply(ActorData base, HexCoord fallbackLocation, List<ActorEntry> entries) → ActorData`；
  `EconomyOwnershipTimeParticipant(String mapId)`（namespace = `"ownership"`，`moduleChanges` = `{economy, actor}`）

- [ ] **Step 1: `OwnershipBooks`（纯函数）**
  - 逐条：`balances[commodity] += delta`；账户键 = `new GoodsAccountKey(entry.actor(), entry.location())`（**键从值派生**，走 `ActorData.withAccount`，**不自己拼键**）；
  - ★ **余额不得为负**（`GoodsAccount` 的构造期守卫兜底）⇒ 协调器**再判一层**并抛（消息含 actor/商品/余额）；
  - ★ 余额**只在被写的商品上覆盖**（其余商品原样带过）⇒ 两张表不互相抹。
- [ ] **Step 2: `EconomyOwnershipTimeParticipant`**：日循环 = `EconomyDayStepper.step(d)` ⇒ `books = OwnershipBooks.apply(books, hex, ledger.actorEntries())`；`finish()` 后交 `{economy: EconomyChangeSet.between(...), actor: ActorChangeSet.between(...)}`；读写集含 `actor:<mapId>:account.<key>`（照 `ActorResolver` 的地址形制）
- [ ] **Step 3: `PopulationEconomyTimeParticipant` 加第三片**：`actorOf(state)`（**缺席 ⇒ 抛**，同 `economyOf` 的既有口径）；日循环里同一处 apply；提案的 `moduleChanges` 加 `ACTOR`
- [ ] **Step 4: 接线与夹具**：① 两处注册点换成新参与者（★ 两者**从不同时注册**）；② `EconomyTestWorld.genesis()` 与 `PopulationEconomyFixture` 的 `modules` 各加一条 `actor` 快照（`ActorData.empty()`）；③ `Shell` 不变（已注册 `PopulationEconomyTimeParticipant`）
- [ ] **Step 5: 跑红→跑绿**（端到端）：先写 `ActorEconomyOwnershipTest` 的断言（一个真播种器世界推满一个周期）：
  ```java
    assertThat(afterActor.accounts().get(new GoodsAccountKey(ESTATE, HEX)).balances())
        .as("★★ I4.1：净产落 operator 的账；付出去的那笔已扣（算式：净产 − 实付）")
        .containsEntry(GRAIN, netMinusPaid);
    assertThat(rows.income()).as("★ 行侧的实得 = cohort 入账（不是毛产分成）").containsEntry(GRAIN, intake);
  ```
  ⇒ **红**（还没接线）⇒ 接线 ⇒ **绿**
- [ ] **Step 6: 变异自证（三条）**：① `OwnershipBooks` 不落"产出入 operator"那条 ⇒ 断言红；② 参与者的 `moduleChanges` 去掉 `actor` ⇒ 断言红（★ **这一条必须试**：它证明"产权账真的过线"）；③ `ActorEntry` 的 `location` 换成 `(0,0)` 常量 ⇒ 非零格的世界红
- [ ] **Step 7: 提交**；★★ **关账点 B**：`./mvnw clean verify` 全绿（T4+T5 同批；既有 e2e 期望值已按新口径重算）

---

