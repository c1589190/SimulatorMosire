### Task 2: `EconomyData.relations`（第 8 组件）+ 四档推导

**Files:** Modify `EconomyData.java`（组件/守卫/`withRelations`/跨表守卫）、`EconomyPayloads.java`（`industries[].relation?` + 缺省）；Create `model/RegimeRelations.java`；Test `model/RegimeRelationsTest.java`、`EconomyRoundTripTest`（加一条）、`EconomyInvariantsTest`（加一条）

**Interfaces:**
- Produces: `EconomyData.relations() → Map<IndustryId, ProductionRelation>`；
  `EconomyData.withRelations(Map<IndustryId, ProductionRelation>)`；
  `RegimeRelations.defaultRelation(RegimeId regime, IndustryId industry, ActorRef operator) → ProductionRelation`；
  `RegimeRelations.registered() → Map<String, RuleType>`（**保序**，四档）

- [ ] **Step 1: 改 `EconomyData`**
  - 第 8 组件追加在**末尾**（`allocations` 之后）；`null ⇒ Map.of()`（照包内既有口径）；
  - 跨表守卫（R3）：`relations` 的每个键**必须**在 `industries` 里（`"关系指名的产业不存在"`），且 `relations[k].operator()` 必须 `equals(industries[k].operator())`（**两处拼写必须一致**）；
  - ★ 守卫**不**检查 cohort 侧的行是否存在（同 `ActorData`「表与表之间没有引用完整性约束」的口径：逐组件增量落盘 ⇒ 关系先到、行后到是合法写序）。
- [ ] **Step 2: `RegimeRelations`**（形制**逐字照** `RegimeOperators`：`final class` + 私有构造 + 静态块 + `LinkedHashMap` + `Collections.unmodifiableMap`，★ **绝不用 `Map.of`**——错误消息要列档位）
  - 四档的规则见表 R8；`registered()` 返回档位表；
  - **每条规则的 `recipient`**：劳动者 cohort 用 `ToCohort(new CohortKey(hexOf(industry), stratum))`，其中 `hexOf` 走 `IndustryHexKeys.hexKeyOf`（**唯一拼写点**；拿不到 hex ⇒ 抛，理由：关系必须有地点）；
    ★ 于是"劳动者"在 S1 = **该格的四个阶层 cohort 各一条规则**（与 R7 的解析口径一致：人口为 0 的那些自然解析不到、留在 operator）。
  - ★ 参数（率/固定额）取**出厂值**并注释写明 R8 的约束算式。
- [ ] **Step 3: `EconomyPayloads`**：`industries[]` 可选键 `relation`；缺 ⇒ `RegimeRelations.defaultRelation(regime, id, RegimeOperators.defaultOperator(regime, id))`（**推导只在载荷边缘**，同阶段 3 的 D1）；给了 `relation` 但 `operator` 与产业的不一致 ⇒ **抛**（R3）。
- [ ] **Step 4: 跑受影响的类**（一次一个）：`RegimeRelationsTest`·`EconomyRoundTripTest`·`EconomyInvariantsTest`·`EconomyCodecTest`·`EconomySeedHandlerTest`·`EconomySettlementTest`
- [ ] **Step 5: 变异自证（两条）**：① 跨表守卫放宽成"只查键在不在" ⇒ `EconomyInvariantsTest` 的新用例红；② 缺省推导从"按 regime"改成"取第一条已登记档" ⇒ `EconomySeedHandlerTest` 的逐值断言红
- [ ] **Step 6: 提交**；★ **关账点 A 的前半**（本任务行为不变：harvest 还没改）

---

