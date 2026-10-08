# 2026-10-09 选项 A：债务引用拆表（debt-ref-table）—— 实现账本

> 责任区：把「家户 → 其债引用 id 列表」从 `HouseholdEconomy` 行内拆成独立表，让每天只落盘**真正变化的引用对**。
> 用户 2026-10-09 批准原话：「先用上选项A，验证效率提升」。
> 本账本由办事（写代码）子 Agent 写；**不含测试改动**（测试留给后续测试 Agent，见 §9）。

---

## 0. 一句话结论

- **功能全部落地**：引用表独立成组件、合同表仍是唯一权威、对账/守卫逐条保持、旧档可读、往返不变式成立、数值逐值不变。
- **硬指标 1（`economy.classes` ≤150KB/天）达成**：控制方那份 240 天真实世界上 **757 → 55 KB/天（−92.7%）**。
- **硬指标 2（总变更集 ≤400KB/天）未达成**：同一世界上 **1290 → 589 KB/天（−54.3%）**，距目标还差 **189KB/天**，
  且差额**全部落在本批范围之外**（social 255 + actor 49 + economy 其它组件 228）⇒ 按 §一.8 三级处置：
  **本批不判 DONE，按 BLOCKED 交账**（见 §8）。
- 耗时：四组 ABBA 配对（同世界、同时长、单步）**改后每组都更快**（−4.7% / −6.1% / −5.8% / −32.9%），
  分段口径 −3.8% ~ −13.7%。绝对值的离散度很大（同字节重跑的离散度可达 40%），故只报**配对**结论。

---

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| 1 | `simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java:158-166` | `diff` 是**行级**的：`!entry.getValue().equals(base.get(key))` ⇒ 整行值进 Upsert | 行内任一字段变 ⇒ 整行（含 3KB 引用）重写 |
| 2 | 控制方 240 天世界实测（本账本 §7 复现）：每 tick `classes` upsert **203 行**，首行 3298 字节中 **3020 字节是 `debts`**（≈33 条合同 id） | 每天约 750KB 是引用陪跑 | 这就是本批要拆的东西 |
| 3 | `simos-economy/.../model/HouseholdEconomy.java`（改前）`List<DebtContractId> debts` 是 record 组件 | 引用是**行内状态** | 拆表必须动 `EconomyData`/`EconomyChangeSet`/`EconomyCodec` 三处（铁律 5） |
| 4 | `simos-economy/.../migrate/DebtReferenceReconciler.java`（改前）：`EconomyData` 构造期按 `debtContracts` 重建每行 `debts` | 权威方向 = 合同表 → 引用（单向）；引用是**派生索引** | 拆表后同一段对账改成重建"引用表"，语义可逐条保持 |
| 5 | `simos-economy/.../EconomyData.java`（改前 :947 附近）`for (DebtContractId contractId : row.debts()) if (!debtContractsCopy.containsKey(...)) throw` | 悬空引用守卫 | 拆表后判据要改成扫引用表（本批收成 `requireReferencesResolvable`，见 §5） |
| 6 | `simos-economy/.../time/DebtContractBook.java:549`（改前）`withDebtReference(row, debtId)` | 会话内维护行内引用 | 全仓引用读点逐条核过：**会话内没有任何读者**（§4）⇒ 不再需要这份会话内维护 |
| 7 | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:115-122`：`CHANGESET_MAPPER`（Core 的**第四台** mapper）+ `Replay.java:196` 用它读变更集 | **重放不经过 `EconomyCodec` 的整形层** | ★ 只把退役字段的处理放在 codec 里 ⇒ 读自己的旧档会在重放路径当场死（实测复现，见 §6.4） |
| 8 | `simos-util/.../json/SimosObjectMapper.java`：「**不关闭** `FAIL_ON_UNKNOWN_PROPERTIES`」 | 未知字段 = 漂移信号，默认严格 | 拆掉 `debts` 组件后，旧档行里的 `debts` 键必须**具名**处理 |
| 9 | `simos-app/.../gui/ApiViews.java:1309/2122/3747/3748/3760/3761` 五个读点 | App 侧读"某户的债" | 需要一个状态读口 → 新增 `EconomyData.debtsOf(HouseholdId)`（唯一读法） |
| 10 | `simos-economy/.../codec/EconomyCodec.java:1025`（改前）`rewriteLegacyHouseholdEconomyDebtReferences` | 旧档行内引用改写 | 拆表后该改写**无事可做**（引用由对账从（已迁移的）合同表重建，逐值相同）⇒ 删除 |

---

## 2. 行形态选择与理由

**选：行 = `(HouseholdId, DebtContractId)` 复合键 × 标记位 `Boolean.TRUE`**，落在
`EconomyData.householdDebtRefs`（第 37 个组件，追加在末尾）。

- 复合键类型：`simos-economy/src/main/java/io/mosire/simos/economy/model/HouseholdDebtReference.java`（新文件）。
  规范串 `<household>@<contract>`（`toString`/`parse` 互逆）：家户 id 实际拼写只含 `[A-Za-z0-9_|.\-]`（`hh-…`/`legacy-…`），
  合同 id 只含 `[0-9a-f\-]`（`debtc-<hex>-<hex>-<hex>-<hex>`）⇒ `@` 不会出现在任一侧；`parse` 对"没有/多于一个分隔符"fail-closed 具名抛。
- 值 = `Boolean.TRUE` 标记位：一个引用对"要么在表里、要么不在"。**`false` 被构造期具名拒**（否则同一条引用有两种拼法、
  变更集 diff 会把它当两次变化）。
- 粒度理由：复合键 ⇒ 新增/删除**一条引用只写一行（~100 字节）**；若按家户聚合（值 = 每户一条 `List`），
  一次引用变化仍要重写该户整份列表（实测 ~3KB）—— 本批要的就是"只写真正变化的那一对"。
- 保序：`LinkedHashMap` + `Collections.unmodifiableMap`，键序 = `classes` 键序 × 户内合同 id 升序（对账产出）⇒
  同一份状态恒得同一份表、字节可重放。

**为什么不保留"行内非状态派生视图"这条路**：视图必须与表同步，而表按构造期对账全量重建 ⇒ 视图只能靠"构造期再回填行"
维持，等于在**内存里保留两份同一事实**（拆表要消灭的正是这种陪跑），且任一回填漏点都会静默漂开。故**整字段删除**；
所有读点改走状态读口 `EconomyData.debtsOf(...)`（§4）。

---

## 3. 落点清单（改了哪些文件）

| 文件 | 改了什么 |
|---|---|
| `model/HouseholdDebtReference.java` | **新增**：复合键 record（规范串 + `parse`） |
| `model/HouseholdEconomy.java` | 删 `debts` 组件与相关守卫/`with*` 参数；加**具名退役注解** `@JsonIgnoreProperties("debts")`（理由见 §6.4） |
| `EconomyData.java` | 第 37 个组件 + 空表归一 + 冻结 + 形状守卫（值必须 TRUE）+ 对账改成重建引用表 + 守卫改查新表 + 新增读口 `debtsOf` + 4 个便捷构造器各补一个 `Map.of()` |
| `change/EconomyChangeSet.java` | 第 35 个组件 `FieldDelta<Boolean> householdDebtRefs`（缺键 ⇒ Unchanged、`between`/`apply`/`isEmpty` 同步） |
| `codec/EconomyCodec.java` | 注册 `HouseholdDebtReference` 键反序列化器；删除已无事的旧档行内引用改写；注释标注"类型级注解"这一例外 |
| `migrate/DebtReferenceReconciler.java` | `reconcile(contracts, classes, refs) → refs`（重建引用表，幂等返回同一实例）+ `requireReferencesResolvable(...)` 具名守卫 + 一条 INFO 对账日志 |
| `time/DebtContractBook.java` | 删 `withDebtReference`（会话内维护，无读者）；类注改口径 |
| `time/EconomyStateBuilder.java` | `build()` 原样带过 `base.householdDebtRefs()`（对账在构造期重算，不需要会话工作副本） |
| `time/{MarketSettlement,EconomySettlement,GovernmentDebtIssuance,LotMigrationBook}.java` | 删 5 处会话内"补引用"点（`addDebtReference` 等） |
| `spi/{EconomyRegisterHouseholdHandler,EconomyRegisterGovernmentHandler,EconomyMigrateHouseholdHandler,EconomyPayloads}.java` | 行构造去掉 `debts` 参数；播种载荷的 `debts` 键**保留形状校验、不再进状态**（注释写明理由） |
| `spi/{EconomyClearRegionHandler,EconomySeedHandler}.java` | 两处"手工全量构造 `EconomyData`"补第 37 个组件（原样带过） |
| `EconomyLogSource.java` | 新增 `ECONOMY_DEBT_REFERENCE`（system 面来源 id） |
| `simos-app/.../gui/ApiViews.java` | **5 个读点**改走 `data.debtsOf(key)`（`householdEconomyView` 多一个 `debtRefs` 参数） |

---

## 4. 22 处 `.debts()` 读点的逐条处置

**A. 是"行内引用"语义（本批要改的）**

| 位置 | 处置 |
|---|---|
| `ApiViews.java:1309` | → `data.debtsOf(key)` |
| `ApiViews.java:2122` | → `new LinkedHashSet<>(data.debtsOf(key))` |
| `ApiViews.java:3747/3748` | → 新参数 `debtRefs`（调用点传 `data.debtsOf(key)`） |
| `ApiViews.java:3760/3761` | → 同上 `debtRefs` |
| `EconomyData.java:947`（守卫） | → `DebtReferenceReconciler.requireReferencesResolvable(debtContracts, classes, householdDebtRefs)`（具名抛，判据不变） |
| `DebtReferenceReconciler.java:88`（重建） | → 改为重建**引用表**（`reconcile` 返回值换了类型） |
| `DebtContractBook.java:553/556`（`withDebtReference`） | → **删除**：会话内无读者；终态由构造期对账重算（数值逐值不变已证，§6.3） |
| `EconomySettlement.java:8956`（饿死回写透传） | → 去参数（该字段已不在行里） |
| `EconomySettlement.java:8977`（周期累加重置透传） | → 去参数 |
| `EconomyRegisterHouseholdHandler.java:191`（透传） | → 去参数 |
| `EconomyRegisterGovernmentHandler.java:240`（透传） | → 去参数 |
| `EconomyMigrateHouseholdHandler.java:142`（透传） | → 去参数 |
| `MarketSettlement.addDebtReference`（内部调用 `round.householdEconomies.put(...withDebtReference...)`） | → 删方法 + 2 处调用（会话内无读者）；★ 该方法里那句 **fail-closed 具名断言逐字保留**（提成 `requireHouseholdRowInRound`：债务人家户行必须在本轮工作副本里），拆表不许放松守卫 |
| `GovernmentDebtIssuance.java:159` | → 删 put（同上） |
| `LotMigrationBook.java:307` | → 删 put（同上；保存存在的行存在性校验） |
| `EconomySettlement.java:5738`（借粮补引用） | → 删 put（同上） |
| `EconomySettlement.java:6354`（资本化补引用） | → 删 put（端点存在性校验保留） |

**B. 是"债合同表"语义（**不是**本字段，未改）**：`EconomyLiquidationSettlement.java:356/1067/1104/1107/1557`
（`context.debts()` = 合同工作表）、`EconomySettlement.java:3675`（`partition.debts()`）、
`MarketSettlement.java:5981`（`round.debts()`）—— 全部原样。

**C. 测试文件（本批禁止碰，交给测试 Agent；见 §9）**：`EconomyInvariantsTest:481/499/519`、
`EconomySeedHandlerTest:417/428/563`、`EconomyFixtures:318`、`LotMigrationBookTest:101/102`（这些会**编译失败**）；
`TwoRoundMarketProbeTest:415/438` 是 `RoundSnapshot.debts()`（另一个类型）⇒ 不受影响。

---

## 5. 实现要点（数据流与次序）

```
① 命令/推进：会话只写 debtContracts 工作表（DebtContractBook.upsert/reduce/...）
② 构造 EconomyData（唯一入口）：
   缺键 ⇒ householdDebtRefs = Map.of()
   → 形状守卫（键/值非 null、值必须 TRUE）→ 冻结副本（LinkedHashMap + unmodifiableMap）
   → DebtReferenceReconciler.reconcile(debtContracts, classes, refs)：
        按 debtor 分组、组内按 DebtContractId 规范串升序；按 classes 键序逐户重建
        （陈旧/多余/重复引用一律丢弃 —— 与改前逐行重建同一套规则）
        一致 ⇒ 返回**入参同一实例**（幂等 no-op，不产生无谓增量）；不一致 ⇒ INFO 一条具名计数
   → 跨表守卫（合同的两端家户行必须存在）
   → requireReferencesResolvable（引用 ⊆ 合同表 + 挂对债务人 + 家户行存在）★ 与改前那条行内守卫同一条判据
③ 变更集：FieldDelta.diff(base.householdDebtRefs(), target.householdDebtRefs())
④ 读口：EconomyData.debtsOf(household)（唯一读法；逐值等于改前的 row.debts()）
```

**日志（§一.9）**：唯一新增事件 `DEBT_REFERENCES_REBUILT`（logger `.debt`，来源 `economy-debt-reference`，
`households/added/removed/total` 四个具名计数），**INFO 且只在引用真的变了时发射**（每个 revision 最多一条，
不是逐笔噪声；逐笔明细仍由 `DebtContractBook` 的 DEBT_UPSERT/DEBT_REDUCE 记）。

---

## 6. 自证证据（探针在 `/tmp/debtref`，源码已存 `./probes/`；**不进仓库**）

探针分两个类：`PerfProbe`（**版本无关**：用反射兼容"引用住在行里"与"引用独立成表"两种形状，故同一份字节能在
改前/改后两套 classpath 上跑）+ `SelfProbe`（只在改后 classpath 上跑，打新 API 与守卫）。

### 6.1 往返不变式（`SelfProbe rt`，失败 0 项）

```
✓ toString / parse(toString) == 原键
✓ classes 非 Unchanged（日变动那一维在变更集里）      ✓ householdDebtRefs 变了（新增一对）
✓ 引用差恰一对                                      ✓ 往返：apply(between,base).equals(target)
✓ target 的引用表 = 合同表派生（1 对）
✓ 引用组件 Unchanged（0 字节引用陪跑）  ← 只有日变动时，引用组件是 0 字节
✓ 快照 decode(encode(x)) 逐值相等；二次编码字节相同（len=4111）
✓ 变更集 decode(encode(x)) 可施加且相等；二次编码字节相同（len=4314）
✓ 键序 = classes 键序 × 合同 id 升序                   ✓ 同一份状态两次构造逐值相等（可重放）
```

### 6.2 守卫（`SelfProbe guard`，失败 0 项）

```
✓ 悬空引用 ⇒ 具名抛：householdDebtRefs 引用了不存在的债务合同（v2 spec §八.2）：hh-… → debtc-deadbeef
  （fail-closed：不静默核销、不放行悬空引用）
✓ 孤儿合同（合同在、债务人行不在）⇒ 具名抛：债务引用对账失败：合同 … 的债务人 hh-ghost 在 classes 里不存在
✓ 标记位守卫（构造期可达）：householdDebtRefs 的值只允许标记位 TRUE（存在即引用）: … = false
✓ 对照：合法引用放行（1 对保留）—— 排除"一律拒"
✓ 陈旧引用被对账清掉、不抛（与改前逐条相同）
```
★ 说明：`requireReferencesResolvable` 被单独提成具名方法，**就是为了让"悬空 ⇒ 具名抛"这条判据可被单独验证**；
它在生产路径上仍由 `EconomyData` 紧凑构造器在每次构造时调用（守卫没有放宽，只是可测了）。

### 6.3 旧档 / 逐值不变（同一 store 三种读法，摘要逐字节相同）

控制方那份 **240 天真实世界**推进到 tick 270（`/tmp/w240-*`），全经济状态规范摘要（跨版本可比：行内 `debts`
与独立 `householdDebtRefs` 都归一成一行 `household-refs`）：

```
BEFORE 自己写、自己读（tick 270, head=245）        家户=342 合同=942 引用对=942
  摘要 953515f4b03f9e39bf7682e616a1da508c80fada8ffa508259c33e2b4c9f6c7e
AFTER  推同一世界同样的 30 天（tick 270）           家户=342 合同=942 引用对=942
  摘要 953515f4b03f9e39bf7682e616a1da508c80fada8ffa508259c33e2b4c9f6c7e   ← 数值逐值不变
AFTER  读 BEFORE 写的同一份档（旧档 + 重放路径）    家户=342 合同=942 引用对=942
  摘要 953515f4b03f9e39bf7682e616a1da508c80fada8ffa508259c33e2b4c9f6c7e   ← 旧档兼容
```
另在 150 天世界（3 个 tick 点、342 户）同样三读同摘要：`a70caf84b2c36609a2049d4bcc91aa0be5c558db4faa37b8998b55bb5082413c`。

`SelfProbe legacy`（合成旧字节：把新组件键整段删掉）失败 0 项：
```
✓ 旧变更集确实不含该组件键（len=3493）      ✓ 缺键 ⇒ Unchanged（不是抛、也不是 NPE）
✓ apply 后引用表由合同表重建（1 对）        ✓ 旧快照确实不含该组件键（len=3320）
✓ 旧快照读回 ⇒ 引用表被重建（1 对）        ✓ 旧快照读回 ⇒ 逐值等于新档
✓ 旧档行内 classes[].debts 被摘掉（严格 mapper 不再炸）
```

### 6.4 ★ 本轮发现并修掉的真实回归（如实记）

第一版把"退役字段 `debts`"的处理放在 **codec 的节点整形**里（与既有 `useRights→assetShares` 同款）。
跨版本读档实测**当场失败**：

```
Exception in thread "main" java.lang.IllegalArgumentException: 变更集 JSON 非法或缺类型信息（@class）
  at io.mosire.simos.core.timeline.Timeline.readChangeSet(Timeline.java:389)
  at io.mosire.simos.core.store.Replay.replay(Replay.java:196)
Caused by: UnrecognizedPropertyException: Unrecognized field "debts"
  (class io.mosire.simos.economy.model.HouseholdEconomy) …
  （through reference chain: WorldChangeSet["modules"]->…["economy"]->EconomyChangeSet["classes"]
    ->FieldDelta$Upsert["entries"]->LinkedHashMap["hh--3_0-rural-poor_peasant"]->HouseholdEconomy["debts"]）
```

根因：**重放走 Core 的第四台 mapper**（`Timeline.CHANGESET_MAPPER`），它不经过 `EconomyCodec` 的整形层
（`Replay.applyWorld` 只是把已解码的 `EconomyChangeSet` 路由回 codec）⇒ 只在 codec 里摘键 = 读自己的旧档必死。
修法：把退役字段做成**类型级的具名忽略** `@JsonIgnoreProperties("debts")`（`HouseholdEconomy`）——
对四台 mapper 一致生效，且**只忽略这一个名字**（`@JsonIgnoreProperties` ≠ `ignoreUnknown=true`，其余未知字段照旧 fail-closed）。
这是对"领域状态类型零 Jackson 注解"惯例的**第二处例外**（第一处是 `AllocationRule` 的多态注解），已在 `EconomyCodec` 类注里记明理由。
随后 codec 里那套整形函数**整体删除**（同一件事只有一处拼写）。

---

## 7. 效率对照（原始输出）

口径：**同一个世界、同一段时长、同一份探针字节**；改前 = git HEAD `359098e3` 现编的 class 树
（`git worktree add /tmp/debtref-before HEAD` + `compile`），改后 = 本工作树 `target/classes`。
单步 = 每天一条 `core.AdvanceTime`；分段 = 30 天一条（与 §12 验收纪律同口径）。测量窗口内**不做回读**（避免把重放算进推进）。

### 7.1 控制方那份 240 天真实世界（`/tmp/s2-probe-A-after18183786513952532793` 的两份副本，tick 240→270）

单步（30 天）：
```
BEFORE  墙钟=47819 ms，30 天 ⇒ 1594.0 ms/天
        变更集：新增 AdvanceTime revision=30，平均 1290 KB/天；economy.classes 平均 757 KB/天；householdDebtRefs 0 KB/天
AFTER   墙钟=63395 ms，30 天 ⇒ 2113.2 ms/天     ← ★ 这是"冷页缓存 + 顺序"造成的假慢，见下面的 ABBA 配对
        变更集：新增 AdvanceTime revision=30，平均  589 KB/天；economy.classes 平均  55 KB/天；householdDebtRefs 1 KB/天
```
ABBA 配对复测（同世界，15/20 天窗口，**同一份字节重复跑的离散度高达 40%**，故只看配对）：

| 配对 | BEFORE ms/天 | AFTER ms/天 | 差 |
|---|---|---|---|
| b1→a1（20 天） | 2160.1 | 2058.7 | **−4.7%** |
| b2→a2（20 天） | 1549.4 | 1454.2 | **−6.1%** |
| a3→b3（15 天） | 2181.5 | 2055.1 | **−5.8%** |
| a4→b4（15 天） | 2118.7 | 1422.5 | **−32.9%** |

分段（30 天一条）：
```
BEFORE  墙钟=4034 ms ⇒ 134.5 ms/天；变更集 1967 KB/天（classes 741）
AFTER   墙钟=3881 ms ⇒ 129.4 ms/天；变更集 1318 KB/天（classes 52、householdDebtRefs 40）
```

**变更集组成（240 天世界，30 天平均，KB/天）**：

| | BEFORE | AFTER | Δ |
|---|---|---|---|
| 总 | **1290** | **589** | **−54.3%** |
| `economy.classes` | **757** | **55** | **−92.7%** |
| `economy.householdDebtRefs` | —（行内） | 1.4 | 新增，≈0 |
| economy 小计 | 983 | 283 | −71% |
| social | 255 | 255 | 0（不在本批） |
| actor | 49 | 49 | 0（不在本批） |
| gov/sd/unit | 2 | 2 | 0 |

economy 其余组件（改后仍是本批未触碰的）：`units 86.3`、`flows 58.2`、`operatorConditions 41.1`、`debtContracts 30.4`、`allocations 4.5`、`classStandings 2.2`。

**单行实据（同一份变更集里的首行 `hh--3_0-rural-poor_peasant`）**：
```
BEFORE 长度=3298  字段：{'id':39,'view':25,'population':2,'laborMilli':6,'participationPerMille':3,
                       'money':1,'debts':3020,'naturalNeeds':29,'effectiveDemand':2,'cycleNaturalNeedMilli':6}
AFTER  长度= 267  字段：{'id':39,'view':25,'population':2,'laborMilli':6,'participationPerMille':3,
                       'money':1,'naturalNeeds':29,'effectiveDemand':2,'cycleNaturalNeedMilli':6}
```
（每天 203 行被重写；`debts` 一门就占改前单行 92%。）

### 7.2 探针自建的新世界（THREE_POWERS 真创世；热身 30 / 150 天，测 30 天）

| 世界相位 | 口径 | BEFORE ms/天 | AFTER ms/天 | 变更集（总 KB/天） | `economy.classes` |
|---|---|---|---|---|---|
| tick 30→60 | 单步 | 371.9 | 329.4（−11.4%） | 918 → 549 | 425 → 55 |
| tick 150→180 | 单步 | 632.5 | 516.2（−18.4%） | 1034 → 566 | 524 → 55 |
| tick 30→60 | 分段 | 49.0 | 48.1（−1.8%） | 2297 → 1936 | 422 → 53 |
| tick 150→180 | 分段 | 46.7 | 40.3（−13.7%） | 1772 → 1306 | 520 → 53 |

★ 控制方原口径（490 ms/天 单步、52 ms/天 分段）在本机复现为：新世界 30 天段 **372 / 49**（同一量级）；
240 天世界的单步绝对值在 **1422–2182 ms/天** 之间大幅波动（§7.1）⇒ **绝对值不可跨运行比较，只报配对**。

---

## 8. 硬指标判定（如实交账）

| 指标 | 目标 | 实测（240 天真实世界，同一世界同时长） | 判定 |
|---|---|---|---|
| `economy.classes` | ~750 → **≤150 KB/天** | **757 → 55 KB/天（−92.7%）** | ✅ **达成**（余量 2.7 倍） |
| 总变更集 | ~1MB → **≤400 KB/天** | **1290 → 589 KB/天（−54.3%）** | ❌ **未达成**（差 189 KB/天） |
| 耗时 | 改善 | 四组 ABBA 配对全部更快（−4.7% ~ −32.9%）；分段 −3.8% ~ −13.7% | ✅ 改善（配对口径） |

**为什么总指标没达标（不是实现缺陷，是目标的组成假设）**：指标按"`economy.classes` ≈ 总变更集的 62~70%"推算
（1MB − 750KB + 小量 ≈ 370KB）。**实测那份世界里 `classes` 只占 58.7%，而"其它"合计 533 KB/天**：
`social 255` + `actor 49` + `economy` 其它组件 `228`（units 86 / flows 58 / operatorConditions 41 / debtContracts 30 …）+ gov/sd/unit 2。
拆表把 `classes` 那一份几乎清零（55），但**它从来没占过 62~70% 的"总"** ⇒ 即使把 `classes` 压到 0，
总变更集也只能到 ~534 KB/天。

**要达到 ≤400 KB/天，至少还需要在下面的范围里再省 ~190 KB/天**（都不在本批的文件所有权内）：
1. `simos-social/**` 255 KB/天（同样是自己行级 `FieldDelta` 的问题）；
2. `simos-economy` 其它组件 228 KB/天（`units` 86 + `flows` 58 + `operatorConditions` 41 + `debtContracts` 30 …）——
   **手法与选项 A 同族**（把大而不常变的子结构从行里拆出去），但那是另一批组件、需要各自的对账/守卫设计；
3. `simos-actor/**` 49 KB/天。

⇒ 按 §一.8 三级处置：**本批交付的代码全部落地且自证通过，但"总变更集 ≤400KB/天"这条硬指标未达成，
任务状态 = BLOCKED（不是 DONE）**；没有设计冲突、也没有需要用户裁定的开放点，纯粹是"再省 190KB/天需要更大的范围"。

---

## 9. 会让既有测试失效的清单（测试不在本批范围；交测试 Agent）

**会编译失败**（读已删除的行内 `debts`）：
- `simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java:481/499/519`
  （三条"对账重建行内引用"的断言 ⇒ 判据本身仍然成立，只是读点换成 `data.debtsOf(...)`/`data.householdDebtRefs()`）
- `simos-economy/src/test/java/io/mosire/simos/economy/spi/EconomySeedHandlerTest.java:417/428/563`
- `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyFixtures.java:318`
- `simos-economy/src/test/java/io/mosire/simos/economy/time/LotMigrationBookTest.java:101/102`

**会红但需要新夹具**：
- `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java`：
  ① `everyEconomyDataComponentParticipatesInTheChangeSet` 的 `mutate(...)` 是 `switch` + `default -> throw`
  （第 452 行）⇒ 新组件 `householdDebtRefs` 需要一条 case（自带合同表支撑）；
  ② `changeSetHasExactlyThirtyOneComponents`（第 296 行）断言 `hasSize(31)`，而**改前**实际已是 34 个组件、
  **改后** 35 个 ⇒ **这条在我改之前就是红的**（不是本批造成），名字/数字要一并更新。
- 未受影响（已核）：`TwoRoundMarketProbeTest.java:415/438` 读的是 `RoundSnapshot.debts()`（另一个类型）。

**本批没跑、也没法跑**（任务书硬边界）：`test` / `verify` / `package` / 任何 `src/test/**` 改动 / `git commit`。
⇒ "既有测试仍绿"这件事**本批没有验证**，只验证了生产代码 `compile` 与上面 §6 的探针。

---

## 10. 会改变数值行为的清单

**空**（逐值不变，已用两条独立证据钉住）：
1. 240 天真实世界 240→270 天：改前/改后**全经济状态规范摘要逐字节相同**（§6.3）；
2. 150 天世界同样三读同摘要。

不改变数值的原因：引用表是**派生索引**，唯一权威仍是 `debtContracts`；对账规则（按 debtor 分组、合同 id 升序、
陈旧引用一律丢弃、孤儿合同具名抛）逐条不变，只是产物从"每行一份 list"变成"一条一条的行"。

**受影响的硬编码字面量 / 常量**：无（没有改任何公式、阈值、标定值）。
唯一的"字面量"变化是新增日志来源 id `economy-debt-reference` 与事件名 `DEBT_REFERENCES_REBUILT`（只影响日志）。

---

## 11. 偏离与未完成项

**文件所有权偏离（主动记）**：任务书"允许写"只列了 `simos-economy/src/main/**`、`simos-economy-api/src/main/**` 与本账本，
但任务书第 5 条又要求"全仓 `.debts()` 的调用点（22 处）逐条改造"，其中 **5 处住在 `simos-app/.../gui/ApiViews.java`**
（1309/2122/3747/3748/3760/3761），而编译门禁是 `-pl simos-app -am` ⇒ 不改它就没有任何办法让 app 编译过。
故**只对这 5 个读点做了最小改动**（`data.debtsOf(key)` + 给 `householdEconomyView` 加一个 `debtRefs` 参数），
`simos-app` 的其它文件、其它模块、测试、pom、docs 一律未碰。`simos-economy-api` 无需改动（零修改）。

**偏离设计书（约束设计书未落盘，本任务书即口径）**：
1. 任务书建议"或保留为非状态派生视图"——**选了整字段删除**（理由见 §2）。
2. 任务书提到 `DebtContractBook.withDebtReference` 属"会话内维护"机制要"逐条保持"——**该机制被删除**，
   理由：全仓 22 处读点逐条核过，会话内**没有任何读者**，而终态由构造期对账重算；数值逐值不变已证（§6.3）。
   ★ 这是"少一处会漂开的第二份事实"，不是放松守卫（守卫反而被提成可独立调用的具名方法）。
3. 为修 §6.4 那个真实回归，给 `HouseholdEconomy` 加了**类型级 Jackson 注解**（惯例的第二处例外，已记入 codec 类注）。

**未完成 / 未验证**：
- ❌ 总变更集 ≤400KB/天（见 §8，需要更大范围）；
- 未跑：`test` / `verify` / `package`（本批禁止）⇒ Spotless 只跑了 `spotless:check/apply`（两模块绿），
  Checkstyle 由 `compile`（绑在 validate）带过（绿）；**SpotBugs 未跑**（绑在 verify）；
- 未做：`simos-app` 的 GUI 端到端验收（读口改动只做了静态核对 + 单测级探针，未起服务看页面）；
- 未做：更长时间跨度（>270 天）的旧档兼容（只验到 240→270 与 0→180）；
- 未做：`EconomyPayloads` 播种载荷里 `debts` 键的**端到端**验（只在探针里做了合成验证）。
