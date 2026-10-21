# D-032 C10 blocker E hotfix：SpotBugs 6 项（含 Optional/HouseholdId 类型错误）（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：verify 在 simos-economy SpotBugs 6 bugs。只修这些 main 缺陷，不写/跑测试，不 commit/push，不碰主树。

## 修复项

1. `TransportRoundPlan.java:38`：`demands()/commitments()/observationsByShippingHex()` EI_EXPOSE_REP ×3
   - 在 accessor/构造赋值处返回 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（SpotBugs 能看见的最外层包装）；
   - 不改键序/内容/语义。
2. `TransportRoundPlan.java:231` `sameDemandSupply` WMI_WRONG_MAP_ITERATOR
   - 用 `entrySet()` 遍历；不改相等/哈希语义。
3. `MarketTradeExecutor.java:228` BX_UNBOXING_IMMEDIATELY_REBOXED
   - 去掉多余拆箱再装箱；不改数值。
4. `ProductionRunSettlement.java:916` **High** GC_UNRELATED_TYPES：
   `Optional<HouseholdId>` 与 `HouseholdId` 比较；代码 `householdEconomies.containsKey(economic)` 类型错误/潜在运营户回退 bug。
   - 修为 `economic.filter(householdEconomies::containsKey).orElse(null)` 或等价类型安全写法；保持“无户 ⇒ 不静默回退”的 fail-closed 语义；
   - 必须回代码核这段的真实意图并写账本；若确实影响 360 运输供给 0，记录但不能声称已证明。

## 纪律

- 只改上述 main 文件（必要时同责任区最小修整）；禁止 test/pom/tools/test-world/主树/docs（除 `.superpowers` 账本）。
- 不改 D/S/定价/批次/seed 语义；不新增第二本账。
- 编译：`tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am`；不跑 test/verify/gate/360。
- 账本 `.superpowers/sdd/2026-10-21-d032-carrier-c10-blocker-e-hotfix/impl-ledger.md`，报告每项 actual fix、编译 rc、Optional 类型错误调查结论、残留/blocker。
