# 实现架构账本 —— B4：落盘集合顺序 = 内容的纯函数（P-1/P-2 最后一道坎）

> 写码 Agent（责任区 = 让落盘集合顺序成为内容的纯函数）。日期 2026-10-10。现场：`.superpowers/sdd/2026-10-10-b2-verify-path-equality/impl-ledger.md` §②/§⑥⒝。
> 只改生产代码；只跑编译门禁（未跑 test / verify 的测试阶段 / world / 未 commit）。允许写：`simos-economy/src/main/**`、`simos-app/src/main/**`（后者**未改动**）。

## 1. 关键调查结论（file:line → 结论 → 影响）

| file:line | 结论 | 影响 |
|---|---|---|
| `DebtReferenceReconciler.java:106`（改前） | no-op 判据 = `rebuilt.equals(householdDebtRefs)` = `Map.equals`，**不比较迭代序** ⇒ "键集相同、顺序不同"的引用表被**原样返回**，规范序不会落地 | 唯一残余差异的直接机制（B2 §⑥-2） |
| `EconomyData.java:1055-1057`（调用点 `:1055`）+ `:301` 记录头/`:737` 缺键归一 + 紧凑构造器 | 引用表**只在** `EconomyData` 紧凑构造器里由 `reconcile` 重建（record canonical ctor，四条便捷 ctor 都汇到它）⇒ 所有路径（旧档 / 直接构造 / `EconomyChangeSet.apply`）都过这一段 | 纠正放这里 = 覆盖全部读入路径 |
| `EconomyStateBuilder.java:450`、`EconomyChangeSet.java:368`/`:430`、`EconomySeedHandler.java:230`、`EconomyClearRegionHandler.java:426` | 引用表的**全部**出现点：整表带过 base / diff / rebuild / 序列化 —— **运行期没有任何第二写口**（`grep HouseholdDebtReference` 亦只余这些） | 顺序只可能来自构造期对账 |
| `simos-util/.../FieldDelta.java:34-35`（该类注释）与 `:158-182` | **有意**语义："判等用 equals（与 map 迭代序无关：顺序变了而内容没变**不是**状态变更）"；`diff` 对只改顺序的两表产 `Unchanged`，`rebuild` 原样返回 base | ⇒ 顺序**不可能**被变更集携带；"规范序"只能在状态构造期落地（**util 不在允许写范围，未改，也不需要改**） |
| `EconomyData.java:2399` `debtsOf()` | 引用表**唯一读口**，按下标收集 `keySet()` 顺序；上游 `ApiViews.java:1324`/`:1353`/`:2174` 三处消费 | 引用表顺序 = dump 里 `classes[].debts[]` / `debtDetails[]` 的顺序 |

## 2. 实现架构（我改了什么）

- **`DebtReferenceReconciler.reconcile()` 的 no-op 判据改为含顺序**（`rebuilt` 的构造顺序本来就是规范序，不需要动）：
  - 落点：`DebtReferenceReconciler.java:125`（`if (sameEntriesInOrder(rebuilt, householdDebtRefs))`，注释 `:120-124`）+ 新私有方法 `sameEntriesInOrder` `:132-156`（签名 `:140`）；
  - 语义：**逐条（键 + 值）按 `entrySet()` 迭代序比对**，O(n) 单趟、无哈希、无分配；
  - 顺序也一致 ⇒ 仍 `return householdDebtRefs`（同一实例，幂等不变）；只有顺序不同 ⇒ `return rebuilt`（规范序）；
  - 值也比（`Objects.equals(value)`）：入参若带非 `TRUE` 值，仍走"重建"那条路把它归一（改前 `Map.equals` 会放行）。
- **规范序口径（一行）**：**外层按 `householdEconomies.keySet()`（= `classes` 键序）逐户，户内按 `DebtContractId::value()` 规范串升序** —— 与 `reconcile` 既有构造顺序、`DebtIndex`（`DebtIndex.java:53` 按 `id.value()` 排序）的既有惯例一致。
- 文档同步（无行为）：`EconomyData.debtsOf()` javadoc（`EconomyData.java:2389-2392`）记下"不需要再排序"的前提现在被构造期真正保证。

## 3. 关键判断

1. **为什么改判据而不是改 `rebuilt` 的构造顺序**：`rebuilt` 已是规范序（classes 键序 + 合同 id 升序）；病根是**判据把它丢掉了**。改判据 = 最小充分改动。
2. **为什么不用 `Map.equals` + 补一次 keySet 比较**：单趟 `entrySet` 迭代序比对同时覆盖"键序 + 值 + 长度"，比"两次 keySet 迭代 + equals"少一遍。
3. **为什么纠正不能放变更集层**：见 §1 第 4 行 —— `FieldDelta.diff` 的"顺序不是内容"是**有意设计**（util 类注释原文），改它需要动 `simos-util`（超出允许写范围）且会改变变更集语义。
4. **为什么不 `Map.copyOf`/`Set.copyOf`、不用 `HashMap` 迭代序**：全程 `LinkedHashMap` + `Collections.unmodifiableMap`（构造器 `:1049`/`:1057` 冻结），I7 未破；新增代码只**读**迭代序，不引入任何无序容器。
5. **性能**：`reconcile` 本来就**每次 `EconomyData` 构造都整表重建**（改前也一样），本次只把"怎么判是否 no-op"从哈希查表换成单趟顺序比对；返回 `rebuilt` 时下游 `FieldDelta.diff` 仍判 `Unchanged` ⇒ **不新增任何落盘 / 序列化 / revision**（§3.4 红线未触碰）。

## 4. 同族审计（逐点结论：改 / 留 + 理由）

| # | 落点 | 判定 | 理由 |
|---|---|---|---|
| 1 | `DebtReferenceReconciler.java:106`（改前） | **改** | 见 §1/§2：顺序影响落盘字节（引用表组件键序 + `debts[]`/`debtDetails[]`），且 `Map.equals` 放行非规范序 |
| 2 | `EconomyGovUnitUpserts.java:497` `if (!assetShares.equals(base.assetShares()))` | **留** | 该分支的 `assetShares` 是 `new LinkedHashMap<>(base.assetShares())` + `put`（`:388`）⇒ 顺序 = base 序 + 追加序，**是内容的纯函数**；顺序不同而内容相同的情形在这条调用链上不可能出现（有追加即内容就不同） |
| 3 | `GovApplyStaffingTool.java:268` `!formation.staff().equals(targetStaff)`（app） | **留** | `targetStaff` 是新造的 `LinkedHashMap`，键序 `YAMEN→SCRIBE` 固定（`:288-296`）；相等 ⇒ 不写（保持 base 序），不等 ⇒ 写同一固定序。**两个分支的顺序都是内容的纯函数**，不产生分段依赖 |
| 4 | `UnitOperations.java:831-832`（unit 模块，`!existingStaff.equals(formation.staff())`） | **留（范围外）** | 同 #3 同族；unit 模块不在允许写范围，且无顺序路径依赖 |
| 5 | `simos-util/.../FieldDelta.diff`（`:158`） | **留（有意语义，且范围外）** | 类注释明文"顺序变了而内容没变不是状态变更"；它就是"顺序不随变更集漂移"的地基。**不动** |
| 6 | `EconomyGmAdjustments.java:627`/`:633`/`:765`/`:771`（`Objects.equals(old, next)`） | **留** | 比对对象是**实体**（`ClassPosition`/`ClassStructure`），用途 = 是否登记一条 `Change`/是否 noOp；`projected` 仍由 `base` 拷贝 + `put` 构造（`LinkedHashMap`，base 序 + 追加序）⇒ 状态顺序仍是内容纯函数 |
| 7 | `OwnershipStakeBook.rebuild()`（`:213`） | **留** | 它是**批写口**（把 moves 落到工作副本），不是"先 equals 判 no-op 再决定写不写"；顺序 = base 序 + moves 序 |
| 8 | `LegacyHouseholdMigration.canonicalizeRelations/Conditions`（`:267`/`:317`）、`EconomyData` 旧档归一（`:850-939`） | **留** | 迁移器**保序**搬运（`for entry : raw.entrySet()` → `out.put`），触发判据是结构谓词（`:86` `needed`）而非值相等 ⇒ 不吞顺序、也不引入顺序 |
| 9 | `EconomyData` 全组件 `null → Map.of()` 归一（`:737`+ 同族） | **留** | 空表无顺序可言 |
| 10 | `Map.copyOf`/`Set.copyOf` 全扫（经济侧 **3 处真实调用 / 3 文件**，app 侧 **23 处 / 13 文件**） | **留（均不在持久状态路径；如实记风险）** | 经济侧逐点核过：`MarketSettlement.java:619`（每 tick 结算 ctx 的 `operatorConditions` **查表**，只 `.get(id)`）、`EconomyLiquidationSettlement.java:1901`（`AuditEntry.evidence` 瞬态审计载荷）、`EconomySettlement.java:9037`（`MoneyIssuanceJournal.governmentByTreasury` **查表索引**）——三者都**不进** `EconomyData` 任何组件。★ **不隐瞒**：它们仍属「用了 JDK 不保证迭代序的容器」这一族（仓内多处注释明文禁用 `copyOf`），彻底收口需另开批（改动面远超本责任区，且不在 P-1/P-2 现象链上）；app 侧 23 处全是配置/权限/读模型/瞬态请求对象 |
| 11 | 无序容器全扫（`new HashMap<>/HashSet<>`：economy **1 处**、app **17 处 / 6 文件** —— MapOverlapsTool 6、HouseholdQueryService 4、ProvinceDivider 3、ScopeUnitExpansion 2、RegionSeedPlan 1、HouseholdManpowerAllocator 1） | **留** | 逐点核过：`MarketDemandBook.java:372`（注释明写"迭代序不参与结果（只做查表）"）、`HouseholdManpowerAllocator.java:139`（辖区优先级由外层 List 定，Set 只判成员）、`RegionSeedPlan.java:438`（`regionHexKeys::contains`）、`ProvinceDivider.java:267`/`:535-536`（成员/visited）、`ScopeUnitExpansion.java:55`/`:63`（权限闭包）、`MapOverlapsTool.java`/`HouseholdQueryService.java`（读口）—— **无一处把无序容器的迭代序当作写回顺序** |
| 12 | `DecisionTurnFinalizer.java:210`/`:218`（app） | **留** | 比对的 `finalIntent` 是 `String`（`:163`），不是集合 |

## 5. 自证与证据

- **门禁**（本仓无并发 Maven；`pgrep` 仅命中自己的 bash）：
  1. `tools/mvn-lock.sh -q spotless:apply` → `exit=0`，`git status` 只有我这两个文件（无跨模块连带改动）；
  2. `tools/mvn-lock.sh -DskipTests verify -pl simos-economy -am` → **BUILD SUCCESS**（reactor 9 模块全 SUCCESS），`simos-economy` SpotBugs **`BugInstance size is 0` / `Error size is 0`**，Spotless `0 needs changes`，`Total time: 36.051 s`；
  3. 编译产物新鲜度：`DebtReferenceReconciler.class`(21:07:25) 新于源文件(21:07:01)。
- **运行级自证（/tmp 临时探针，非仓内测试、非 world）**：`/tmp/b4-canon-probe/B4CanonProbe.java` 直接调**已编译的生产类** `DebtReferenceReconciler.reconcile`（3 张真合同 + 3 户）：
  `[2] 旧判据 rebuiltMap.equals(input) = true`（病根：只有顺序不同，`Map.equals` 判相等）；
  `[3] reconcile(非规范序) 返回同一实例 = false`、`[5] 返回顺序 == 规范序 ? true`、`[6] 键集与值逐条相同（内容不变）? true`；
  `[7] reconcile(规范序) 返回同一实例 = true`（幂等保持）；`[8] 同序但值=FALSE ⇒ 重建，全部 TRUE`（归一仍生效）。
  ★ 探针的 `householdEconomies` 值是 `null`（`reconcile` 只 `containsKey`/`keySet`）—— 生产侧由 `EconomyData` 保证非 null；此处如实记。
- **"不改数值"的静态自证**：新旧两条路只在"`Map.equals` 为真但顺序不同"时分歧，此时入参与 `rebuilt` **键集相同、值逐条相同**（`rebuilt` 由合同表唯一决定）⇒ 返回值只可能**顺序**不同。引用表全部消费点已核：`EconomyData.debtsOf`（唯一读口）→ `ApiViews:1324`（`debtCount`/`debtPrincipal`/`debtPrincipalByUnit` 求和与 merge）、`:1353`（渲染 `debts`/`debtDetails`）、`:2174`（`LinkedHashSet` 去重后按 `TreeMap` 聚合）—— **全部是求和 / 按稳定键聚合**，无按位置取用；`requireReferencesResolvable` 是逐条校验。⇒ **未发现任何"顺序变化会改变业务结果"的下游**（故不触发"停手上报"）。

## 6. 会改变落盘字节的清单（本批）

1. **`EconomyData.householdDebtRefs` 组件的键顺序**（原始状态 JSON 的该组件内键序）：任何"内容相同、顺序非规范"的档/状态，构造期起被纠成规范序；
2. **视图面 `classes[].debts[]` / `debtDetails[]` 的元素顺序**（B2 账本实测的 82/97、83/97 非规范升序行的来源）——现恒为规范序；
3. **由 (2) 派生的顺序**（同一趟遍历写出的 `debtPrincipalByUnit` / `byUnit` 等 `LinkedHashMap` 的**键插入序**）：数值/键集不变，仅键序随之确定；
4. **不改**：任何数值、任何判定、revision 数、变更集内容（`FieldDelta.diff` 仍判 `Unchanged`）。
   ★ **不改**：t1（单段）视图 dump 的 `debts[]`/`debtDetails[]` 顺序按 B2 实测本来就是规范升序 ⇒ 预期不变；但其**原始状态**里引用表的全局键序若原本是插入序，会被纠正（这是预期行为）。

## 7. 偏离记录 / 未完成 / 未验证

- **未跑任何 test / verify 的测试阶段 / world**（按任务书纪律）；**未 `git commit`**。
- ★★ **P-1/P-2 的 dump md5 是否真的相同：未实测**（跑 world 被任务书禁止、也不在允许写范围内）。本批只证到"顺序 = 内容的纯函数"这条**机制**（静态全链路 + 真实类探针），**没有**证明 `0→60` vs `0→30→60` 的 dump md5 现已逐字节相同。
- ★ **"为什么单段路径恰好留下规范序"仍未追到行**（B2 §7-2 的遗留）：本批不依赖该解释 —— 改后两条路径都**必然**收敛到规范序。
- ★ **一个前提依赖（如实记）**：规范序的外层 = `classes` 键序 —— 本批**没有**独立证明 `EconomyData.classes` 的键序本身是内容的纯函数；只依据 B2 实测（三份 dump 的 `classes[]` 列表顺序相同、差异叶只在 `debts[]`/`debtDetails[]`）判它在两条路径上一致。若将来出现 `classes` 键序漂移，它**不在本批修复范围**内，需另开批（那会同时影响更多组件）。
- **未做**：`simos-app` 未改动也未重编（我的改动只在 `simos-economy`；`debtsOf` 只加了 javadoc，无 API 变化）；未跑前端门禁、未跑全仓 `clean verify`（按纪律只到编译门禁）。
- 探针留在 `/tmp/b4-canon-probe/`（**未进仓**，非交付物）；未在 `src/test/**` 添加任何东西。
