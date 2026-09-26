### Task 4: `harvest` 切换 + **过渡入账** + `ProductionLedger`（economy）

**Files:** Modify `EconomySettlement.java`（`harvest` + `settle` 的 fail-closed + 类注重画守恒式）、`EconomyDayStepper.java`（`step` 返回 ledger）；Create `time/ProductionLedger.java`；Delete `time/EconomyTimeParticipant.java`；Test `EconomySettlementTest`/`EconomyCycleBoundaryTest`/`EconomyFlowCycleTest`/`EconomySowingTest`/`EconomyDebtTest`/`EconomyLaborAllocationTest` 的**推进写法**（见 Step 5 配方）

**Interfaces:**
- Produces: `EconomyDayStepper.step(long day) → ProductionLedger`；
  `ProductionLedger(Map<IndustryId, Map<CommodityId, Long>> gross, Map<...> losses, Map<...> inputs, List<ActorEntry> actorEntries, Map<CohortKey, Map<CommodityId, Long>> cohortIntake, List<CompensationRule> deferredMoney)`

- [ ] **Step 1: 写失败的测试**（economy 侧，两个面）
  - **过渡面（R5）**：一个只有 agriculture + 一条显式 `relation`（给养给 `(hex, poor_peasant)`）的世界，推满一个周期 ⇒ 断言 **`farm@hex|poor_peasant` 的粮库存 = 期初 + 实付（手算）**，且 `ledger.actorEntries` 里恰好有一条 `+净产 → operator`（手算量）—— ★ 这一条**就是**"cohort 不断粮"的守门用例。
  - **fail-closed（R4）**：`EconomySettlement.settle(base, 0, cycleDays)` ⇒ **抛**（消息含"产权落账口"）。
- [ ] **Step 2: 跑红（当场捕获）**
- [ ] **Step 3: 改 `harvest`**：
  - 毛产/损耗/净产的算式**一字不改**；`netParts`/`lossParts` 两处分配**删除**（不再分给行）；
  - 每个商品：`+net → operator`（`ProducerEntry` 一条，`location` = `hexKeyOf(industry.id())`）；`loss` 只进 `ledger.losses`；
  - 调 `ProductionSettlement.settle(relation, facts)` ⇒ `actorEntries` 追加进 ledger、`cohortIntake` **就地落到消费行**（R7 的解析：`population > 0` + 按人口比例；解析不到 ⇒ 留在 operator）、`deferredMoney` 进 ledger；
  - ★ `FlowRow.income` 改成记**实物入账**（cohort 入账那一笔；★ **merge 不 put**，理由同 `consumedGoods`）；
  - ★ `productionLoss` 那一族累加器**删除或改挂 ledger**（判据：不许留下"分了损耗但没人收"的中间残留）。
- [ ] **Step 4: `settle` fail-closed**（R4）+ `EconomyDayStepper.step` 返回 ledger + 删 `EconomyTimeParticipant`（连带其用例改注册新协调器——**但本任务先到不了那一步**，见 Step 5 的顺序）
- [ ] **Step 5: 既有用例的**迁移配方**（机械，逐个 `Edit`）**
  ```
  EconomySettlement.settle(base, 0, N)   →   EconomyDayStepper stepper = new EconomyDayStepper(base);
                                             for (long d = 1; d <= N; d++) { stepper.step(d); }
                                             EconomyData after = stepper.finish();
  ```
  ★ 需要"产出侧事实"的用例：把 `stepper.step(d)` 的返回值攒起来（`ledger`），**不要**再算一遍公式。
- [ ] **Step 6: 跑绿**（一次一个类）⇒ 报告 mtime 落本轮；★ 期望值按 R1/R8 重算（算式写进注释）
- [ ] **Step 7: 变异自证（两条）**：① 删掉"cohort 入账"那一步（产出只进 ledger）⇒ Step 1 第一条红（**这就是"断粮"的可执行证据**）；② `settle` 的 fail-closed 去掉 ⇒ 第二条红
- [ ] **Step 8: 提交**（★ 与 T5 属**同一批**：单独提交也可，但**不许单独进关账点 B**）

---

