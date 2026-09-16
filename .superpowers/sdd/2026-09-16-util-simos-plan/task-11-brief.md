### Task 11: M1 关账

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-util-simos-design.md`（回填计划期新增细则）
- Modify: `docs/superpowers/plans/2026-09-16-simos-master-plan.md`（§二 M1 状态）
- Modify: `CLAUDE.md`（"当前状态"表）

- [ ] **Step 1: 全量门禁**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS（Spotless check + Checkstyle validate + SpotBugs verify + Surefire 全绿）。
**`mvn test` 不跑 SpotBugs**——关账必须以 `verify` 为准。

- [ ] **Step 2: 逐条核对关账判据（spec §1.2）**

| 判据 | 核对方式 |
|---|---|
| 八大件各有单测 | `Address*` / `SubjectIdTest` + `ResolvedSubjectTest` + `QueryResultTest` / `SimosTimestampTest` / `StateRefTest` / `SnapshotProtocolTest` / `SimulationStateTest` / `RoundTripAssertionsTest` / `InMemoryInfoSystemTest` / `TemporalSeriesTest` / `ResolverRegistryTest` / `FacetRegistryTest` 全绿 |
| 往返框架有故意漂移的失败用例 | `RoundTripAssertionsDriftTest` 绿（它断言的是"必须抛 AssertionError"） |
| 冻结样例逐条往返 | `AddressParseTest.frozenSamplesRoundTrip` 的 15 条参数全绿 |
| `./mvnw verify` 绿 | Step 1 |

- [ ] **Step 3: 把计划期新增的三条细则回填 spec**

1. §五 补：`TimeRange.to` 必须严格晚于 `from`，否则构造期抛 `IllegalArgumentException`。
2. §五 补：`SimosTimestamp` 的 `equals` 含 `calendarLabel` 而 `compareTo` 只看 `tick`——判"同刻"一律用 `compareTo == 0`。
3. §十二 测试清单补 `TimeRangeTest`（spec §十二 是下限不是上限）。

- [ ] **Step 4: 更新状态表**

- `docs/superpowers/plans/2026-09-16-simos-master-plan.md` §二：M1 由 ⬜ 改 ✅（列出 11 个任务的产出与 `verify` 结论）
- `CLAUDE.md` "当前状态"表：M1 行改 ✅，M2 行标注"待裁决 MapSimos 待决项（spec §十三）"

- [ ] **Step 5: 提交（不推送）**

```bash
git add docs/ CLAUDE.md
git diff --cached --stat
git commit -m "docs: M1 UtilSimos 关账——判据核对、计划期细则回填 spec、状态表同步"
```

---

## 自审记录（writing-plans 的三项自查）

**1. spec 覆盖**：spec §二 包结构的 8 个包 ↔ Task 1–10 逐个落地；§三 Address ↔ Task 1/2；§四 身份 ↔ Task 3；§五 时间与版本 ↔ Task 4/6；§六 三协议与容器 ↔ Task 6；§七 TemporalSeries ↔ Task 7；§八 Facet ↔ Task 9；§九 往返框架 ↔ Task 10；§十二 测试清单逐类落到任务里（`AddressQuoteTest` 由 Task 1 建、Task 2 补归一用例；额外的 `TimeRangeTest` 由 Task 11 Step 3 回填进清单）；§十 偏离 D1–D7 在 Task 5/6 落地并在接口处注明。**§十三 不做清单**（各领域 Resolver、Jackson 序列化、两阶段时间推进、命令信封、世界时钟、单位编制链地址、`agent:` 嵌套语义）在计划中**无对应步骤**——这是有意的。

**2. 占位符扫描**：每个代码步骤都给了可编译的完整代码与确切路径；无 TBD/TODO/"类似 Task N"。唯一引用他处代码的措辞是 Task 1 的 `AddressText`（同任务 Step 3 内给出）与 Task 2 复用 Task 1 的段类型（同文件、已给全）。

**3. 类型一致性**：逐项核对过——`Entity.of(String)` / `Entity.of(String, String)`、`Address.canonical()`、`Index(List<Integer>)`、`SegmentedSeries.of(segments, events, addition)`、`TimeRange.since(...)`、`InfoSystem.put(...)`、`SimulationState.module(String)`、`RoundTripAssertions.assertRoundTrip/assertSnapshotRoundTrip` 的参数顺序（`diff` 为 `(S,S)→C`、`apply` 为 `(C,S)→S`）在各任务间一致；Task 10 的玩具类型用**静态** `apply(C, S)` 方法以匹配该签名（写成实例方法会让方法引用顺序颠倒、编译不过）。
