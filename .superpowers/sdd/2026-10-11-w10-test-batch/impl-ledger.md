# W10d 测试批 —— 实现/证据账本

**批次**：`2026-10-11-w10-test-batch`（经济线**最后一轮**测试批）
**执行者**：W10d 测试 Agent（子代理 `session-…` 的 delegated child）
**范围**：只改 `simos-app/src/test/**`、`simos-economy/src/test/**`；**未改任何 `src/main/**`**（临时变异体见 §5，全部 md5 逐字节还原）。
**世界**：`/home/cna/SimulatorMosire`，工作树（未 `git commit`，未跑 `clean verify`）。

---

## 0. 一句话结论

四条红**全部是测试侧基线陈旧**，**没有 main 侧缺口**（判据见 §1.1）；基线已按实跑真值**逐条具名重钉**，牙（精确集合相等）**一条未松**；
W10 具名缺失的 `economyPolicies` 往返测试 **3 处已补**（Codec / ChangeSet / withPolicy）；
政策写口的端到端仓内测试 **已做**（`EconomySettlement` 逐日重建 + `LegacyHouseholdMigration.migrateMeta` 两处 W10-fix 都覆盖）；
`EconomyCycleHealthTest` 指纹**与基线逐值相同**（未重基线）。

---

## 1. ① 四条红的性质判定 + 逐条重钉

### 1.1 性质判定：**四条全是测试侧基线陈旧（不是 main 缺口）**

判据（★ 先判 `McpCoverageTest` 那条，再逐条回代码核）：

| 判据 | 实测 | 结论 |
|---|---|---|
| **①`economy.SetEconomyPolicy` 在不在 catalog 的 type 表里？** | **在**：`simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java:470` 有 `"economy.SetEconomyPolicy"` 词条 | ⇒ **不是"新命令没进 catalog"** |
| **②catalog 的 type 集合 vs Shell 注册的 handler 集合** | 从旧 surefire 报告里**逐 token 提取两侧集合**再求差：`catalog 集合 = 旧 EXPECTED_COMMAND_TYPES(143) ∪ {economy.SetEconomyPolicy}`，`handler 扫描集合 = 同一个 144 集合`；**两侧互为子集**（差集恰为空） | ⇒ **两集合仍同源**，差异只是"测试表少抄了 1 条" ⇒ **基线陈旧** |
| **③四个红类的"多出来的那 1 个元素"是同一个吗？** | 是：`*Tool.java` 磁盘扫描 `176 = 旧 175 + {simos.economy.setEconomyPolicy}`；GM 桶 `166 = 旧 165 + 同名`；handler `144 = 旧 143 + {economy.SetEconomyPolicy}`；窄写类 `70 = 旧 69 + 同名`；写闸集 `129 = 旧 128 + 同名`。**没有第二个漂移项** | ⇒ **纯 W10 新增的 1 工具 + 1 命令**，无其它漂移 |

**判定方法（可复核）**：不猜、不读注释——直接从上一轮 `clean verify` 留下的 surefire 报告 `simos-app/target/surefire-reports/io.mosire.simos.app.tools.SimosToolsTest.txt` 里
用脚本抽出 AssertJ 打印的**实际集合**（176/166/144/144/129/70 个 token），与测试源码里的常量表求双向差集。
结果：**每个差集都恰好是那一个新名字**，反向差集全空。⇒ 一字不多、一字不少。

★ 因此按任务书 §0 的 ★ 判据：**属"在"分支 ⇒ 测试侧基线陈旧 ⇒ 按 ① 处理**，不触发"停手上报 main 缺口"。
★ 但**另有一条 main 侧观察**（W10 自己账本已明示的 backlog，非本批新发现）见 §1.4。

### 1.2 逐条重钉（旧 → 新 + 依据）

依据统一写法：**W10（2026-10-11）新增窄工具 `simos.economy.setEconomyPolicy` + 命令类型 `economy.SetEconomyPolicy`**
（`simos-app/src/main/java/io/mosire/simos/app/tools/write/EconomySetEconomyPolicyTool.java`、
`simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySetEconomyPolicyHandler.java`，
注册点 `SimosToolSource.java:815` / `Shell.java:710`，catalog 词条 `CatalogTool.java:470`）。

| # | 文件 | 位置（锚） | 旧 → 新 | 依据 |
|---|---|---|---|---|
| 1 | `SimosToolsTest.java` | `everyToolClassOnDiskIsRegisteredInSomeBucket` | `hasSize(175)` → **176** | 磁盘 `tools/{read,write}` 扫到的 `*Tool.java` NAME 实增 1 |
| 2 | `SimosToolsTest.java` | `everyNarrowWriteToolClassIsWiredIntoTheGmBucket` | `hasSize(69)` → **70** | 新工具 `extends AbstractNarrowWriteTool` |
| 3 | `SimosToolsTest.java` | `roleBucketsNeverCarryGenericWrite` | `hasSize(165)` → **166** | GM 桶 = 37 读 + 129 写 |
| 4 | `SimosToolsTest.java` | `catalogCoversEveryCommandHandlerImplementation` | `hasSize(143)` → **144** | 扫描面 handler `type()` 实增 1 |
| 5 | `SimosToolsTest.java` | `EXPECTED_COMMAND_TYPES` | 143 → **144**（加 `economy.SetEconomyPolicy`） | 同源（catalog 早已列出） |
| 6 | `SimosToolsTest.java` | `ECONOMY_ZONE_WRITE_NAMES` | 7 → **8**（加 `simos.economy.setEconomyPolicy`） | 新窄写工具名 |
| 7 | `SimosToolsTest.java` | `ECONOMY_ZONE_WRITE_COMMAND_TYPES` | 6 → **7**（加 `economy.SetEconomyPolicy`） | 工具名 ≠ 命令类型，命令类型另列 |
| 8 | `SimosToolsTest.java` | `WRITE_TOOL_NAMES` / `GM_TOOL_NAMES` 文档注释 | 128/165 → **129/166**（61 非窄写 + 68 窄写） | 同上 |
| 9 | `McpServerTest.java` | `EXTERNAL_UNION_GM_TOOL_NAMES` | 165 → **166**（加 `simos.economy.setEconomyPolicy`） | 真 MCP `tools/list` 实测 166 |
| 10 | `McpPortTopologyTest.java` | `ECONOMY_ZONE_WRITES` | 8 → **9**；`GM_NARROW_WRITES` 68 → **69**；J1 断言 165 → **166** | 同上 |
| 11 | `McpCoverageTest.java` | `EXPECTED_COMMAND_TYPES` | 143 → **144**（加 `economy.SetEconomyPolicy`） | 与 `SimosToolsTest` 同源，两处计数必须逐字相同 |
| 12 | `McpCoverageTest.java` | `MINIMAL_PAYLOADS` | **+1 条载荷** `economy.SetEconomyPolicy` | ★ 见下 |

**★ 第 12 条是本条红里唯一的"真工作量"**：`McpCoverageTest` 那条断言有**两段**——
① `catalog types == EXPECTED_COMMAND_TYPES`；② `MINIMAL_PAYLOADS.keySet() == catalog ∖ PRECONDITION_REJECT_TYPES`。
上一轮只红在第①段，**第②段从未跑到**；只改计数会让第②段接着红。⇒ 已补载荷
`{"key":"SLIDE_ROUND_DAYS","valueMilli":7}`（该键缺省 5 ⇒ 非缺省 ⇒ 走**正常提交**路径而不是被具名拒 `unchanged`；
宿主 `economy.meta` 由同一夹具的 `economy.Seed` 播下 ⇒ 不是 `not-activated`）。
**并加了一条"真的生效"断言**（同 W1g 给 `SetMarketPrice` 加的那条同形）：读回
`economySlice(head).meta().economyPolicies()` 必须**含** `SLIDE_ROUND_DAYS → 7`，读口 `policyOf` 必须 = 7
（缺省 5 ⇒ 一个"照单收下却什么都没写"的 handler 会让它红）。

### 1.3 牙没有放松（逐条自检）

- 全部计数断言仍是 **`hasSize(精确值)`** / **`containsExactlyInAnyOrderElementsOf`**——**没有**改成 `contains`、`≥`、`isSubsetOf` 之外的新语义、**没有删条目**；
- `SimosToolsTest` 的 **"磁盘上每个 `*Tool.java` 都必须进某个桶"语义一字未动**：
  `assertThat(onDisk).isSubsetOf(union)` 与反向"GM 桶不得有幽灵条目"两条原样保留（只改了它前面的**扫描面条数** 175 → 176，那是"扫描器静默"陷阱的**自检**，不是牙本身）；
- `everyNarrowWriteToolClassIsWiredIntoTheGmBucket` 的 `gmNarrow containsExactlyInAnyOrderElementsOf(NARROW_WRITE_NAMES)` 原样保留 ⇒ §5 的 M5 变异体证明它仍有牙。

### 1.4 ★ 顺带回代码核出的一条 main 侧观察（**W10 账本已明示的 backlog，不是本批新缺口**）

**事实（本批实测 grep，`file:line`）**：`EconomyPolicyKey` 有 **9** 个键，但 `src/main` 里**只有 4 个有运行期读取点**：

| 键 | 读取点 | 有/无 |
|---|---|---|
| `SLIDE_SPEED_PER_MILLE_PER_RATIO` / `SLIDE_COST_PER_LEVEL` / `SLIDE_ROUND_DAYS` | `ClassSlideSettlement.java:118-120`（每个市场轮读一次） | **有** |
| `MINT_MILLI_PER_FURNACE_CYCLE` | `MintSettlement.java:238-240` | **有** |
| `FAMINE_MAX_PPM_PER_TICK` | 运行期读的是**常量** `FamineMortality.FAMINE_MAX_PPM_PER_TICK`（`simos-social/.../HouseholdBook.java:1137,1156`） | 无 |
| `MINT_LABOR_SHARE_PER_MILLE` | 只在**创世播种**消费（`EconomySeeder.java:4142`） | 无（设计如此） |
| `LOAN_BASE_RATE_PER_MILLE` | 无 `economyPolicyOf` 调用点 | 无 |
| `MIGRATION_SPEED_CAP_PER_MILLE` / `PROFIT_GAP_SPEED_SLOPE_PER_MILLE` | 运行期读常量（`ModeMigrationPolicy.java:162/134,2100-2114`） | 无 |

**⇒ 这 5 个键"写口可用、写了不生效"。**
**但这不是新缺口**：`W10` 实现账本已**逐条明示**——`.superpowers/sdd/2026-10-11-w10-scale-unlimited/impl-ledger.md`
§④.2/④.3 与 `:78-80 / :142 / :218 / :319`（原话：「这 5 键**写口可用、读取点仍读常量（写了不生效）**…（W10 backlog）」）。
本批只是**回代码核实了台账那句话为真**（§四"机制性描述回代码核"）。
**⇒ 对本批的实际影响（已落进测试口径）**：新测试**只挑有读取点的键**，否则"读点按新值走"会对着一个没有读取点的键**空转**。

---

## 2. ② `economyPolicies` 往返测试（W10 具名缺失 → 已补 3 处）

**落点**：全部落在**既有**往返测试类里，照既有形制（不另起炉灶）：

| # | 类（既有） | 新增用例 | 覆盖 |
|---|---|---|---|
| 1 | `simos-economy/.../codec/EconomyCodecTest.java` | `economyPolicyTableSurvivesTheCodecInOrderAndKeepsMissingKeysMissing` | **Codec 往返**：带非空政策表的 meta 写出→读回 |
| 2 | `simos-economy/.../change/EconomyRoundTripTest.java` | `theEconomyPolicyTableSurvivesTheChangeSetInBothDirections` | **ChangeSet 往返**：`apply(between(base,target), base)` 逐字段重建 target（base 空表→target 非空，**以及反向**） |
| 3 | `simos-economy/.../change/EconomyRoundTripTest.java` | `writingTheDefaultValueDeletesTheRowAndEmptiesTheTable` | **"写缺省 ⇒ 删行 ⇒ 空表"**（W10 `withPolicy` 语义）+ 越域负向 |

**判据（不是"跑绿了"，是钉住了什么）**：

- **①Codec**：
  - 逐键逐值相等 + `keySet()` **顺序 = 枚举声明序**（★ 关键：`Map.equals` **与迭代序无关** ⇒ 只断言"相等"抓不住
    "解码把保序 `LinkedHashMap` 换成 `HashMap`/`Map.copyOf`" 这种退化，那条**顺序断言**才抓得住）；
  - **缺键语义**：没配过的键**不在表里**（`doesNotContainKey`），读口 `policyOf` 给**具名缺省**（不是 0）；
  - **字节级**：编码 → 解码 → **再编码逐字节相等**（序漂移 ⇒ 字节必变）。
- **②ChangeSet**：`cs.meta()` 必须是 `Upsert` 且 `changed()` 为真（不得退化成 `Unchanged`）；重建后整份状态
  `isEqualTo(target)` + 政策表逐键逐值 + **顺序 = 声明序** + 缺键仍走缺省；反向重建 `isEqualTo(base)` 且表回到空。
  ★ 夹具**刻意乱序插入**（PROFIT_GAP → SLIDE_ROUND → FAMINE）⇒ 插入序 ≠ 规范序，"按插入序"的实现当场红（见 §5 M4）。
- **③withPolicy**：非缺省→缺省 = **删该行且其它键保留**；删掉最后一个键 = **表空**；对**本来没有**该键的 meta 写缺省 = **仍空**（不凭空补行）；
  非缺省值仍进表（反向对照：证明"删除"不是对着恒空实现变绿）；**越域上界+1 / 下界−1 当场抛**且原实例不动（fail-closed）。

**实跑结果**：`EconomyCodecTest` **29 条全绿**（含新增 1）、`EconomyRoundTripTest` **8 条全绿**（含新增 2）。

---

## 3. ③ 政策写口的端到端 repo 测试（**做了**）

### 3.1 端到端：命令写入 → 推进 ≥1 天 → 读回仍在 → 读点按新值走

**落点**：**新类** `simos-app/src/test/java/io/mosire/simos/app/time/EconomyPolicyEndToEndTest.java`
（单用例，**2.3 s** —— 用最轻的真世界 `GovZ6WorldFixture.smallWorldGenesis`，不是 three-powers）。

**装置**：真 `Shell` + 真 `small-world` 创世（经 `economy.Seed`，`EconomyPayloads:523` 建 meta）+ 真命令面
（`CommandEnvelope` → `coreSimos().submit`，type = `EconomySetEconomyPolicyHandler.TYPE`）+ 真逐日推进
（`shell.advanceAndDrain(AdvanceTime)`），每一步都**真 replay** 读状态。

**步骤与断言**：
1. **前置（防空转）**：创世 `economyPolicies()` **为空**；`economyPolicyConfigured` 为 false；读口 = **具名缺省**；
2. **①写入**：`{key: SLIDE_ROUND_DAYS, valueMilli: 9}`（缺省 5）→ `CommandResult.Committed`；
3. **②写后即读回**：读口 = 9、`configured` = true、政策表 `containsExactly(entry)`（只写一个键，别处不动）；
   再写第二个键（`SLIDE_COST_PER_LEVEL = 4000`）⇒ 两键都在；
4. **③推进 6 天**（★ 覆盖 **`EconomySettlement` 逐日逐字段重建 meta** 那一处 W10-fix；6 天同时跨过**一个市场轮**
   `day % 5 == 0`），断言 tick 真的 +6（没推进则后面全是空转）⇒ **政策表两键原样仍在**；
5. **④读点按新值走**：`economyPolicyOf(WIRED_KEY)` = 9 且 **≠ 缺省 5**（★ 该方法是 `EconomyDayView` 类注明写的
   "结算类读政策值的**唯一拼写点**"，`ClassSlideSettlement:118-120` 读这三个滑落键就是调它 ⇒ 读的是政策表而不是编译期常量）；
6. **⑤"写缺省 ⇒ 删行"经真命令面**：把第二个键写回缺省 ⇒ 该行**消失**、第一个键**仍在**；**再推进一天** ⇒ 删掉的行
   **不得被重建回来**，被删键的读口回到具名缺省。

**实跑结果**：**1 条全绿**（2.298 s）。
**★ 口径（如实写）**：断言到"唯一拼写点"这一层；**没有**构造"某个家户真的滑落一级"的夹具 ⇒
「改了政策值之后**滑落天数**真的按新值算」这条**下游数值后果未验**（见 §6）。

### 3.2 迁移路径：`LegacyHouseholdMigration.migrateMeta` **不丢政策表**（★ 构造期自动迁移路径，夹具**造出来了**）

**落点**：**新类** `simos-economy/src/test/java/io/mosire/simos/economy/migrate/EconomyPolicyMigrationTest.java`（3 用例，**全绿**）。

**关键：怎么造出"构造期自动迁移"的夹具**（这是任务书点名"能造夹具就造"的那一处）：

- 触发判据取自 `LegacyHouseholdMigration.needed` 的**第二条**：`units` 空 + `relations` 非空；
- 夹具 = `EconomyData.empty().withMeta(政策表非空).withIndustries({farm}).withRelations({旧键})`
  —— 旧键的 `value` **刻意等于** `"farm"` ⇒ 迁移器走 `canonicalizeRelations` 的
  `legacyIndustryExists(industries, …)` 分支，把这条旧关系**丢弃**（既有口径：旧产业存在但没有 unit = 产能 0，行为等价）
  ⇒ **迁移本身不引入别的状态差异**，本用例量到的就只剩 meta（判据干净）；
- **防空转前置**：断言 `rulesVersion` 被改写成 `pre-modern-v1` **且 ≠ 原值**（"迁移真的跑了"的观察点）、
  `migrationSource` 被记上、`data.relations()` 为空（证明迁移确实处理过 relations）。

**三条用例**：① 构造期自动迁移后政策表**逐键逐值逐序**带过 + 读口按表里的值（≠缺省）+ 未配键仍缺省；
② 直调 `migrate(...)`，`Result.meta()` 逐字段带过（mapId/activatedDay/lastClosedCycle/政策表），未配键仍缺省；
③ **缺省中性**：旧档（空政策表）过一遍迁移**仍是空表** —— 不凭空补"等于缺省的冗余行"。

---

## 4. ④ 回归与硬牙读数

### 4.1 `EconomyCycleHealthTest`：**指纹与基线逐值相同 ⇒ 未重基线**

实跑（单类，`-pl simos-app -am -Dtest=EconomyCycleHealthTest`）：

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 65.76 s
=== 体检通过：8 段 / 240 天，5 组不变量全部成立（货币守恒 · 非负 · 人口不爆不灭 · 市场在运转 · 账目闭合）
    + W4b 加牙：96 个市场轮逐轮零成交 = 0 且分布指纹与实测基线逐值相同（单一形态；第三个值即红）===
```

⇒ **Σfills 3,337 / min·中位·max = 15·35.5·193 / 96 轮 / 零成交轮 = 0** 全部与收口世界基线**逐值相同**，
**没有出现"两个结果"**（因此也没触发 §三.5 的产物陈旧排查）。**未动任何期望值。**

### 4.2 全仓 `tools/mvn-lock.sh test -am`（**真数**）

**命令**：`tools/mvn-lock.sh test -am`（原始输出 `evidence/fullrun.log`；窗口 `23:32:44 → 23:37:09`，**BUILD SUCCESS / exit=0**）

| 模块 | run | fail | err | skip |
|---|---:|---:|---:|---:|
| UtilSimos | 217 | 0 | 0 | 0 |
| MapSimos | 379 | 0 | 0 | 0 |
| CalendarSimos | 33 | 0 | 0 | 0 |
| SocialApiSimos | 3 | 0 | 0 | 0 |
| ActorApiSimos | 8 | 0 | 0 | 0 |
| EconomyApiSimos | 50 | 0 | 0 | 0 |
| SocialSimos | 217 | 0 | 0 | 0 |
| UnitSimos | 518 | 0 | 0 | 0 |
| CoreSimos | 231 | 0 | 0 | 0 |
| SDSimos | 232 | 0 | 0 | 0 |
| ActorSimos | 130 | 0 | 0 | 0 |
| **EconomySimos** | **349** | 0 | 0 | 0 |
| GovSimos | 111 | 0 | 0 | 0 |
| ArmySimos | 91 | 0 | 0 | 0 |
| **SimosApp** | **910** | 0 | 0 | **5** |
| **合计** | **3,479** | **0** | **0** | **5** |

- ★ **`SimosApp` 909 → 910**：正是本批新增的 `EconomyPolicyEndToEndTest`（+1 条）；`EconomySimos` 的增量 = 本批新增 6 条
  （Codec +1 / RoundTrip +2 / Migration +3）。**上一轮的 10 红全部转绿**。
- ★ **5 条 skip 与本次改动无关**（既有、且是"真 LLM"类）：`RealLlmGovScenarioTest` 2 条 + `RealLlmUnitDecisionLoopTest` 3 条
  （`Time elapsed: 0 s` ⇒ 前置条件不满足直接 skip，非本批引入）。
- ★★ **surefire 报告 mtime 覆盖：416 / 416 份全部落在本轮窗口内，stale = 0**
  （最老 `simos-util 23:32:45`，最新 `simos-app 23:37:08`；15 个模块逐个数见 `evidence/` 的覆盖脚本输出）。
  ⇒ 不是"读了上一轮留下的绿"。
- ★ **运行时自证（真壳日志，非测试断言）**：本轮日志里 `SHELL_ASSEMBLED … handler=144 participant=2 resolver=7 facet=2 tool=166`
  —— **与重钉后的 144 / 166 逐值相同**，这是重钉数字的**运行时**来源（不是从测试常量反推的）。

### 4.3 B2 分段等价（three-powers `0→360` 一次 / 3 段 / 逐 tick md5 相同）与 Revision Δ=1

- **B2 分段等价**：**未跑**。理由：仓内**没有**既有测试（`grep 分段等价/segmentEquival` = 0 命中）；新写要造
  360 天 × 3 种推进形态的装置，且 three-powers **非分段推进实测 ~485 ms/天**（`EconomyCycleHealthTest` 类注 §12 实测）
  ⇒ 单是"一次 0→360"就 ~3 分钟，加"3 段"与"逐 tick"两次 ≈ 10 分钟以上运行 + 装置开发/调试 ⇒ **成本超出本轮余量**，如实标**未跑**。
- **Revision Δ=1**：**间接覆盖、未独立成条**。`EconomyPolicyEndToEndTest` 里 **5 次提交**
  （3 条命令 + 2 次 `advanceAndDrain`）**全部以 `expectedRevision = 当前 head` 提交并成功**
  —— 若任一提交不是恰好 +1，下一次提交会以 `Conflict` 失败 ⇒ 这 5 次提交**间接**证明 Δ=1。
  ★ 但**没有**独立的"Δ=1 硬牙"断言，也**没有**覆盖 360 天窗口 ⇒ 如实记为**未独立验证**。

---

## 5. ⑤ 变异自证（关键项，**6 个变异体全部被杀死**）

纪律：**一次一个变异体**；每个变异体**先确认能编译**（编译错误不算红）；跑完**立即还原并比 md5**；
变异窗口内不跑其它 Maven。三个被变异的 `src/main` 文件**全部还原为逐字节相同**：

```
md5sum -c evidence/main-md5-before.txt
  EconomySettlement.java        : OK   (cb2d61a7ff3a01a87efd61902e22aa7f)
  LegacyHouseholdMigration.java : OK   (9118d3881554d7097198806e7c7ab0ce)
  EconomyMeta.java              : OK   (e82a1b775ff221378ea2b9837a1e5537)
grep -rn MUTANT --include=*.java {simos-economy,simos-app,simos-social,simos-core}/src/main
  (no MUTANT markers — clean)
git diff --stat -- simos-economy/src/main   # 8 files, 190 insertions(+), 30 deletions(-) —— 与变异前逐字相同
```

| # | 变异体（改坏什么） | 目标守卫 | 实测结果 | 红点是否在被保护的判据上 |
|---|---|---|---|---|
| **M1** | `EconomySettlement` 逐日重建 meta 时用 `Map.of()` 替掉 `meta.economyPolicies()` | `EconomyPolicyEndToEndTest`（**W10-fix ①**） | **红**：`policyWrittenThroughTheCommandFaceSurvivesDailySettlement:137`，消息 = 「★★ W10-fix：逐日结算重建 meta 必须**原样带过**政策表…」 | ✅ 正是那条 |
| **M2** | `LegacyHouseholdMigration.migrateMeta` 用 `Map.of()` 替掉 `value.economyPolicies()` | `EconomyPolicyMigrationTest`（**W10-fix ②**） | **红 2/3**：`constructionTimeMigrationKeepsTheEconomyPolicyTable:101` **与** `migratePassesThePolicyTableThroughUnchanged`；第 3 条（旧档空表）**按预期保持绿** | ✅ 两条正题红；缺省中性那条不受影响（正确） |
| **M3** | `EconomyMeta.withPolicy` 的"写缺省"分支由 `remove(key)` 改成 `put(key,value)`（留冗余行） | `writingTheDefaultValueDeletesTheRowAndEmptiesTheTable` | **红 1/8**（其余 7 条仍绿）：`:511`「★ 写回缺省值 ⇒ 该行被**删掉**（不是留一行等于缺省值的冗余行）」 | ✅ 正是那条 |
| **M4** | `EconomyPolicyKey.normalize` 的声明序遍历改成"按输入插入序" | `economyPolicyTableSurvivesTheCodecInOrderAndKeepsMissingKeysMissing` | **红 1/29**：`:270`「★ 政策表的**顺序** = 枚举声明序（规范序）—— Map.equals 与序无关，这条才钉得住保序」 | ✅ 正是那条（**证明"只断言相等"不够、顺序断言才有牙**） |
| **M5** | `SimosToolsTest.ECONOMY_ZONE_WRITE_NAMES` 抽掉 `simos.economy.setEconomyPolicy`（计数仍写 176） | 重钉后的工具面清单 | **红 3/71**：`everyNarrowWriteToolClassIsWiredIntoTheGmBucket:1129`「GM 窄写必须逐条等于 NARROW_WRITE_NAMES（68 条…）」+ `registryContainsExactlyTheExternalUnionGmTools` + `writesAreSensitiveAndAskWithTheToolNameAsClassKey` | ✅ 精确集合相等仍有牙（不是"数字对了就行"） |
| **M6** | `McpCoverageTest.MINIMAL_PAYLOADS` 抽掉新加的 `economy.SetEconomyPolicy` 载荷 | §1.2 第 12 条那段"每个 catalog type 都备了载荷" | **红 1/1**：`:1058`「除 5 条需要前置状态 / 已退役的命令外，每个 catalog type 都备了载荷」 | ✅ 证明我补的那条载荷**是承重的**，不是装饰 |

★ **M5 的第一版变异体是编译错误**（抽掉末项留下悬空逗号）——按 AGENTS「红要红在被保护的那行上」，
编译错误**不算杀死**；已修正为可编译形态后重跑，才计入上表。

---

## 6. ★ 我没做 / 没验证的（如实清单）

1. **"下游数值后果"未验**：政策写口的 E2E 只断言到 `economyPolicyOf`（唯一拼写点）这一层；
   **没有**造出"家户滑落一级 / 铸币产出按新值算"的夹具 ⇒ 「改了政策值 ⇒ 那个算式真的用新值」**未验**。
2. **B2 分段等价未跑**（`0→360` 一次 / 3 段 / 逐 tick md5 相同）——理由见 §4.3。
3. **Revision Δ=1 未独立成条**（只有 5 次提交的间接证据，见 §4.3）。
4. **5 个"写了不生效"的键未验**（`FAMINE_MAX_PPM_PER_TICK` / `MINT_LABOR_SHARE_PER_MILLE` /
   `LOAN_BASE_RATE_PER_MILLE` / `MIGRATION_SPEED_CAP_PER_MILLE` / `PROFIT_GAP_SPEED_SLOPE_PER_MILLE`）：
   本批只**回代码核实**了"读取点仍读常量"这一事实（§1.4），**没有**写"写了不生效"的负向测试（那会把 W10 backlog 固化成断言）。
5. **没有跑 `clean verify`**（按硬纪律归控制方门禁）；**没有跑真世界 HTTP/受管后台作业**（本轮不需要：E2E 走进程内真 `Shell`，
   与"真 MCP"那条运行时证据是两套装置；W10-fix 的真 MCP 证据仍以 W10 账本为准，见 §7）。
6. **没有跑变异自证于**：`McpPortTopologyTest` / `McpServerTest` 的计数（与 M5 同源的同一批清单，未重复变异）。
7. `McpCoverageTest` 的**其余** catalog 面（逐类提交链）依赖既有夹具，本批只加了 1 条载荷 + 1 条读回断言，
   **没有**扩大该用例的覆盖面。

---

## 7. W10-fix 运行时证据索引（任务书要求给"在哪"）

W10-fix 的两处修复在**真 MCP + 独立 store** 上的运行时证据**不在本批**，在 W10 收口账本：

- 账本：`.superpowers/sdd/2026-10-11-w10-scale-unlimited/impl-ledger.md`（§④.2/④.3 是"未切读取点"的具名 backlog 清单）；
- 同目录 `report.md`；逐次运行读数 `results/*.result.json`（`w10fix-tppolicy.result.json`、
  `w10fix-tppolicy-prefixjar.result.json`、`w10fix-tp-restart.result.json`、`w10fix-tp240.result.json` 等）；
- 独立 store：`store-w10fix-tppolicy/`、`store-w10fix-tppolicy-prefix/`、`store-w10fix-tp240/`、`store-w10fix2-tp240/`；
- dump：`dumps/`（本批未复核其 md5 —— 如实记：**我没核 dump md5**，只核了"账本 + 结果 json + store 目录"存在）；
- 日志行：`results/*.log` / `*.log.stripped` 里的 `ECONOMY_POLICY_SET`（发射点
  `EconomySetEconomyPolicyHandler` 的 `EventLog…info(LogEvent.of("ECONOMY_POLICY_SET", …))`；
  另有拒绝路径 `ECONOMY_POLICY_REJECTED`、判据 DEBUG `ECONOMY_POLICY_CRITERIA`）。
  ★★ **本批另补了一层仓内运行时证据**（不是引用 W10 账本，是本轮全仓测试的真壳日志）：
  `evidence/fullrun.log` 里 **4 行 `ECONOMY_POLICY_SET`**，逐行与本批新测试一一对应，**逐字段可核**：

  | 来源 | 日志行（节选） |
  |---|---|
  | `EconomyPolicyEndToEndTest` 第 1 次写入 | `key=SLIDE_ROUND_DAYS from=5 to=9 default=5 min=1 max=1000 configuredKeys=1 effectiveFrom=same-revision` |
  | 同上 第 2 次写入 | `key=SLIDE_COST_PER_LEVEL from=1000 to=4000 default=1000 min=0 max=1000000000 configuredKeys=2 …` |
  | 同上 **写回缺省** | `key=SLIDE_COST_PER_LEVEL from=4000 to=1000 … configuredKeys=1 …` ← **`2 → 1` 是"写缺省 ⇒ 删行"的运行时读数** |
  | `McpCoverageTest` | `key=SLIDE_ROUND_DAYS from=5 to=7 default=5 min=1 max=1000 configuredKeys=1 …` |

  ★ 另注：**dump md5 我没核**（W10 账本目录 `dumps/` 未复核其内容与 md5）——如实记。

---

## 8. 口径与证据索引（本批）

- 统计窗口：**全部读数为本批（2026-10-11 23:20–23:5x）本工作树实跑**；上一轮 `clean verify` 的 10 红取自
  `simos-app/target/surefire-reports/*.txt`（mtime 23:15–23:19，读取于本批开工时）。
- **§三.5 产物陈旧**：开工先比 `target/classes` mtime（`1791731744`）**晚于**最新 `src/main` mtime（`1791731356`）⇒
  非陈旧；全仓 `test -am` 期间未出现"两个结果"。
- **一次一个 Maven**：本批所有 Maven 调用都经 `tools/mvn-lock.sh`，串行执行；变异窗口内不跑其它 Maven。
- 磁盘：跑前 `df -h /` = **30G avail (75%)**；跑后 **31G avail (75%)** —— 清理见下。
- 证据文件（本目录，gitignore；控制方 `git add -f`）：
  - `evidence/main-md5-before.txt`（三个被变异 `src/main` 文件的 md5，`md5sum -c` 复验 **OK**）
  - `evidence/fullrun-summary.log`（全仓 `test -am` 的 **Maven 骨架输出**：逐模块汇总行 + BUILD SUCCESS + `[exit=0]`；
    ★ 原始 819 MB 日志里 99.9% 是逐条 app 日志，已按纪律**剥离**，保留全部 Maven 汇总/错误/警告行）
  - `evidence/surefire-mtime-coverage.txt`（**416/416 报告 in-window、stale=0** 的逐模块读数，可复算）
  - `evidence/economy-policy-set-lines.txt`（§7 那 4 行 `ECONOMY_POLICY_SET` 运行时证据）
  - `evidence/shell-assembled-lines.txt`（`SHELL_ASSEMBLED … handler=144 … tool=166` 运行时读数）
- **清理记录**：删除 `evidence/fullrun.log`（819 MB）与全仓 `target/surefire-reports/TEST-*.xml`（824 MB，共 **~1.6 GB**）；
  保留各模块 `surefire-reports/*.txt` 摘要（mtime 覆盖证据因此仍可复算）。

## 9. 未完成 / BLOCKED

- **无 BLOCKED**。
- 未完成项 = §6 的 1–4、6–7（都不是阻塞，是明示的口径边界）。
