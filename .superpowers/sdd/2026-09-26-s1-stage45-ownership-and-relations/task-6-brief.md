### Task 6: I4.3 —— `weave` 不再作为产权主体持有布（+ 「过渡分配承重」的变异）

**Files:** Create `simos-app/src/test/java/io/mosire/simos/app/world/S1Stage45WeaveOwnershipTest.java`；Modify `EconomyRealScaleClothTest`（读口口径）

**Interfaces:** Consumes `EconomySeeder`（真载荷）+ `EconomySeedHandler` + `PopulationEconomyFixture` 的推进法（真协调器）

- [ ] **Step 1: 写测试**（真播种器世界，推满一个周期后逐值断言）
  ```java
    assertThat(clothOfAllRowsOf(after, WEAVE, hex))
        .as("★★ I4.3：weave 四行**一行都不持有布**（人口为 0 ⇒ 永不是 cohort 受方，R7）")
        .containsOnly(0L);
    assertThat(accountOf(afterActor, new ActorRef(ActorKind.HOUSEHOLD, "weave@0_0"), hex, CLOTH))
        .as("★ 产出落 operator 的账（布在这里，不在 weave 行里）")
        .isPositive();
    assertThat(clothOfRowsOf(after, FARM, hex))
        .as("★★ V3 的机制面：织布的人（farm 行的 cohort）拿到了布 —— 阶段 6 的 I6.3 读的就是这个")
        .anyMatch(v -> v > 0L);
  ```
- [ ] **Step 2: 跑红（当场捕获）**：在 T4/T5 已落地的树上，这三条**应当直接绿** ⇒ **RED 由 Step 3 的变异体当场补**，如实记「本用例的 RED 来自变异体、不是实现缺口」（**不许**把补拍写成"先红后绿"，同阶段 3 Task 5 Step 2）
- [ ] **Step 3: 变异自证（两条，各打一条判据）**：① 把 cohort 解析里的 `population > 0` 去掉 ⇒ 第一条红（布又落回 weave 行）；② 把 cohort 解析换成"活动自己的行" ⇒ 第一条**与**第三条同时红
- [ ] **Step 4: 「过渡分配承重」的证据**：临时把 cohort 入账改成"零入账"（产出只进 ledger）⇒ **既有 e2e 的缺口/饿死用例当场红**（点名哪几条）⇒ 还原 ⇒ 复绿；★ 这条证明"过渡分配"是承重的，不是装饰
- [ ] **Step 5: 提交**

---

