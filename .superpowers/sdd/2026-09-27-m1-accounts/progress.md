# M1 施工台账 —— M1.0 + M1.1（钱这一层）

> **性质**：施工台账（本批**唯一**新建的文档）；代码 + 用例的落点与**实跑**证据都在这里。
> **日期**：2026-09-27 · **分支**：`ts/m1` · **开工 HEAD**：`2d0f244c`（working tree clean）
> **口径来源**：用户 2026-09-27 任务书（M1.0 + M1.1）+ `docs/superpowers/plans/2026-09-27-master-development-plan.md` §四
> + `.superpowers/sdd/2026-09-27-m1-accounts/survey.md`（只读勘察）。
> **本批结束时**：**未 `git commit`**（用户要求：审后由用户提交）。改动集（含未跟踪新文件）的 md5 = `255313ffbda7ae00d49cca4cafeee478`。
> **改后全仓改动**：10 改 + 6 新（5 生产 + 1 测试类），见 §3。

---

## 1. M1.0 幻影判别力收口（前置）

### 1.1 做了什么

| # | 改动 | 文件 |
|---|---|---|
| ① | 新增**真的调用 `money()`** 的多币种往返用例 | `simos-actor/.../codec/ActorCodecTest.java` → `moneyRoundTripsThroughTheWireWithZeroKeptAndAbsentDistinct` |
| ② | 给 `CurrencyId` 补 key deserializer（照 `CommodityId` 先例）+ 类注"三个键"改"四个键" | `simos-actor/.../codec/ActorCodec.java` |
| ③ | 改正类注里那句**不成立**的断言（点名真实存在的用例） | `simos-actor/.../model/GoodsAccount.java`（两参构造器的类注） |

用例夹具的形状 = 判别力（三个币种各代表一种"钱在不在"）：
`silver = 1,200`（有余额）/ `copper = 0`（**余额为 0 但币种存在**，不许归一掉）/ `gold = 缺席`（**根本没有这种钱**，读回来不许补成 0）。
逐值断言货币表 + 商品表各一次，另钉线格式（`"money":{"silver":1200,"copper":0}`）与"解码→再编码逐字节相同"。

### 1.2 判据实跑结果（★ 如实：**它本来就绿**）

| 跑 | 命令（`tools/mvn-lock.sh` 代 `./mvnw`） | 结果 |
|---|---|---|
| **跑 #1：只加用例、生产代码仍是 HEAD** | `-q -Dtest='ActorCodecTest' -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-actor -am` | **EXIT=0**；`ActorCodecTest tests="26" failures="0" errors="0"` ⇒ **本来就绿，说明 deserializer 不是功能缺陷**（Jackson 对单 `String` 构件 record 的键会退回到"按规范构造器建键"，其构造器与 `parse` 校验一字不差） |
| 补注册 `CurrencyId`（①行代码）后再跑 | 同上（先 `rm -rf simos-actor/target/classes simos-actor/target/test-classes`） | **EXIT=0**；`tests="26" failures="0" errors="0"` |

⇒ **补注册不是"红→绿"的修复，而是"规矩一致性"**：类注自己写着"注册今日与'不注册'行为等价 —— **仍然显式注册**，
不靠 Jackson 的构造器推断去碰巧满足"（裁定 R-48-f / R-aa）。原状是**只登记了商品键、漏了货币键**，
两者同住 `GoodsAccount` 这一层 ⇒ "登记一半"本身就是会漂的规矩。**如实记：没有任何用例能区分这一行**（行为等价），
它由"规矩要写出来"这条纪律背书，不由判别力背书。

### 1.3 判别力自证（变异，已还原）

**变异**：把 `GoodsAccount` 紧凑构造器末尾的 `money = Collections.unmodifiableMap(moneyCopy);` 改成 `money = Map.of();`（= 钱被丢）。

```
[ERROR] Tests run: 26, Failures: 1, Errors: 0, Skipped: 0
[ERROR] io.mosire.simos.actor.codec.ActorCodecTest.moneyRoundTripsThroughTheWireWithZeroKeptAndAbsentDistinct:463 <<< FAILURE!
```

⇒ **新用例真的抓得住"钱被丢"**（不是"两个空对象也相等"式假货）。
**已还原**：还原后 `md5sum GoodsAccount.java = 71f39167ac72fdf15bd4878217ac1313`（与变异前逐字节相同），
并重跑 `test -pl simos-actor -am` 至**绿**。§4 的全仓门禁跑在**还原后**的树上。

### 1.4 M1.0 没做到 / 没跑

- ★ **没有做"真文件落盘"（写 SQLite 存档 → 关进程 → 重开再读）**：`simos-actor` 模块**不依赖任何 store**
  （store 在 `simos-core`，依赖方向相反）⇒ 模块内能做到的"落盘往返"就是**经真 `ActorCodec` 的 JSON 往返**
  （这正是该模块既有的"往返用例"形制，`ActorCodecTest` 全类如此）。**真档规模的文件级路径由 app 侧
  `EconomyMoneyInvariantTest` 等守**（它们读真存档），本批**没有**给它们加东西。
  若任务书的"落盘"特指文件 I/O ⇒ 本条**与任务书有落差**，如实记在这里。
- 没有做变异测试运动（AGENT.md §三.0：测试与变异自证留到最后统一做）—— 本批只做了上面**一条**针对新用例的变异自证。

---

## 2. M1.1 币种与货币工具身份

### 2.1 做了什么（逐条照任务书语义）

**新增类型（`simos-economy-api`）**

| 类型 | 位置 | 语义 |
|---|---|---|
| `InstrumentId` | `.../api/id/InstrumentId.java`（★ 走 `id/` 包，与既有 22 个稳定 ID 同族；survey §3 的落点建议） | `record InstrumentId(String value)` + 裸 `toString()` + `parse` 逆 + 空白即抛，与 `CurrencyId`/`CommodityId` **逐字同形** |
| `CurrencyDef` | `.../api/money/CurrencyDef.java` | `record CurrencyDef(String id, int scale)`：计价单位 + 最小单位精度；守卫 `id` 非空白、`scale ≥ 0`；`currencyId()` 是"词表条目 → 运行时身份"的**唯一转换点** |
| `InstrumentKind` | `.../api/money/InstrumentKind.java` | `enum { SPECIE 金属币, STATE_NOTE 国币, BANK_DEPOSIT 银行存款 }`；`requiresIssuer()` 是**无 `default` 的 `switch` 表达式**（加一档 ⇒ **编译不过**，必须先回答"它要不要发行人"）；`label()` 只服务报错/读口 |
| `MoneyInstrument` | `.../api/money/MoneyInstrument.java` | `record (InstrumentId id, CurrencyId currency, InstrumentKind kind, Optional<ActorRef> issuer, Optional<ActorRef> redeemer)`（★ issuer 的类型见 §6.1） |
| `MoneyVocabulary` | `.../api/money/MoneyVocabulary.java` | **世界级货币词表的唯一拼写点**（照 `EconomyVocabulary.allCommodityIds()` 的先例）：`SILVER_CURRENCY_ID/SILVER_SCALE/SILVER/SILVER_CURRENCY/SILVER_SPECIE` + `allCurrencyDefs()` / `allInstruments()` |

**构造期守卫（五条，逐条有理由，写在类注里）**：① 五个组件都非 null（含两个 `Optional` **容器本身** —— "缺值"用
`Optional.empty()`，"没填"是坏数据当场抛）；② `scale ≥ 0`；③ `STATE_NOTE`/`BANK_DEPOSIT` **必须有** `issuer`；
④ `SPECIE` 的 `issuer` **必须为空**（★ 见 §6.6 的裁定与理由）；⑤ `kind.requiresIssuer()` 与 `issuer` 的空/非空
**互为充要**（③④合起来是一条全覆盖规则，没有"看情况"的第三档）。

**判据落点**

- `silver` 迁成"**一个 `CurrencyDef` + 一个 `SPECIE` 工具**"：`MoneyVocabulary.SILVER`（`scale = 3` = 毫银）+
  `MoneyVocabulary.SILVER_SPECIE`（`kind = SPECIE`、`issuer = empty`、`redeemer = empty`）。
- **旧 `CurrencyId` 读口保留（兼容）**：`CurrencyId` 一字未动，仍是 `GoodsAccount.money` / `Transfer.money` /
  `Market.numeraire` 的类型；`RegimeRelations.DEFAULT_CURRENCY` 由 `new CurrencyId("silver")` 改成**引用**
  `MoneyVocabulary.SILVER_CURRENCY`（值逐字不变，测试全绿）⇒ `"silver"` 字面量全仓 `src/main` **恰一处**。
- **唯一拼写点有牙**：`simos-util` 的源扫描护栏 `EconomyVocabularyGuardTest` 加两条 ——
  `silverCurrencyIdLiteralIsWrittenExactlyOnce`（`SILVER_CURRENCY_ID = "silver"` 恰一处）+
  `noModuleSpellsTheSilverCurrencyInline`（全仓 `src/main` 不许有 `CurrencyId("silver")`）。
- **发出来（否则"没人读得到"）**：`ApiViews.economyHex` 新增两栏 —— `currencyDefs`（`{id, scale}`）与
  `moneyInstruments`（`{id, currency, kind, issuer, redeemer}`，`issuer/redeemer` 为 `null` = **没有**）。
  形状照读口既有口径 `{kind,id}`（顺手把 `industryView` 的 operator 也收成同一 helper `actorRefView`，不允许两套写法）。
- **顺手改正过期的话**：`grainDiagnosis.unavailable.paymentInstrumentGap` 的老话"货币工具与接受规则属 M1"已过期一半
  ⇒ 改成"工具的**身份**已给出（见那两栏），**接受规则**属 M2、兑现属 M4+ ⇒ 今天仍不判「付得出去吗」"
  （`ApiViews` 的类注表 ⑥ 行同步改；`EconomyGrainDiagnosisTest` 只钉"三项具名"，不受影响）。

### 2.2 判据实跑结果

| 判据 | 证据 |
|---|---|
| 守卫用例（null / 负 scale / 缺 issuer / 多余 issuer） | 新增 `simos-economy-api/src/test/.../money/MoneyIdentityTest.java`：**12 条**，`tests="12" failures="0" errors="0"`；逐条覆盖：五个组件各一条 null、`scale = -1` 抛而 `scale = 0` **必须过**（边界）、国币/存款**逐档各一条**缺 issuer、`SPECIE` 带 issuer 抛（"多余 issuer"）+ 空 issuer 对照、`redeemer` 空/非空两向、词表自洽（id 不重复、工具币种必须在币种表里、词表逐条过守卫） |
| `InstrumentId` 也进同族护栏 | `EconomyIdsTest` 的 `IDS` 登记表 22 → **23**（含 `hasSize` 与类注数字，历史句加"追加"标注）；`tests="1" failures="0"` |
| 旧 `CurrencyId` 读口**未回归** | 全仓 `clean verify`：`simos-economy-api 38/38` 绿、`simos-economy 171/171` 绿（§4） |
| 新类型真的读得到 | 新增 `GuiApiTest#moneyVocabularyIsPublishedThroughTheEconomyReadout`（**经真 HTTP 端点**逐值断言 `currencyDefs` / `moneyInstruments` / 键序 / 与旧 `actorMoneyTotal`、`market.numeraire` 并排）；`GuiApiTest tests="34" failures="0"` |
| 唯一拼写点有牙 | `EconomyVocabularyGuardTest` 8/8 绿（原 6 + 新 2） |

### 2.3 M1.1 没做到 / 没跑 / 边界（如实）

- **不动账、不加写入点**（照任务书）：没有命令、没有新的账、没有算式；`MoneyInstrument` **不是**余额容器。
- **没做**：铸熔、成色、兑现（M4+）；接受规则（谁收哪种工具）属 M2 的订单/参与者面；汇率仍不存在（币种之间不许求和折算）。
- **没有实现 `MoneyAuthority` / 没有登记发行人**（本批零注册不变）⇒ 世界词表里**故意没有国币/银行存款工具**：
  那两档必须有发行人，而"谁是发行人"今天**说不出来**，硬造一张会被守卫拦下 —— 守卫在这里的作用正是"不许为了让词表好看而编造制度"。
- ★ `redeemer` 是**具名留位**：本批唯一读它的是 `ApiViews` 的读口，**没有任何算式读它**；本批**不给它加规则**（兑现设计不存在，拍守卫就是编造设计）。
- ★ `CurrencyDef.scale` 今天只被**读口 + 用例**读，**没有任何算式消费它**（M1.1 只加身份）。这是刻意的，但也如实记：
  它是"写下来的既有事实"（毫银），不是被消费的参数。
- 没有跑 `./mvnw`（全部经 `tools/mvn-lock.sh`）；没有 `git commit`。

---

## 3. 改动文件清单（10 改 + 6 新）

**改**
1. `simos-actor/src/main/java/io/mosire/simos/actor/codec/ActorCodec.java`（+`CurrencyId` key deserializer；类注"三个键"→"四个键"）
2. `simos-actor/src/main/java/io/mosire/simos/actor/model/GoodsAccount.java`（改正不成立的断言，点名真实用例）
3. `simos-actor/src/test/java/io/mosire/simos/actor/codec/ActorCodecTest.java`（+1 用例）
4. `simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java`（+两栏读口 + `actorRefView` helper + ⑥ 行/占位文案更新）
5. `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（`MARKET_NUMERAIRE` 类注追加 M1.1 补记）
6. `simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java`（+1 用例）
7. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/package-info.java`（补"工具身份"那一层）
8. `simos-economy-api/src/test/java/io/mosire/simos/economy/api/EconomyIdsTest.java`（登记 `InstrumentId`，22→23）
9. `simos-economy/src/main/java/io/mosire/simos/economy/model/RegimeRelations.java`（`DEFAULT_CURRENCY` 引用词表）
10. `simos-util/src/test/java/io/mosire/simos/util/verify/EconomyVocabularyGuardTest.java`（+2 条源扫描护栏）

**新**
11. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/InstrumentId.java`
12. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/CurrencyDef.java`
13. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/InstrumentKind.java`
14. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyInstrument.java`
15. `simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyVocabulary.java`
16. `simos-economy-api/src/test/java/io/mosire/simos/economy/api/money/MoneyIdentityTest.java`

---

## 4. 全仓门禁（判据 4）—— 实跑

```
tools/mvn-lock.sh -q spotless:apply     # EXIT=0（只改到本批自己动的文件，说明其余树本来就是干净的）
rm -rf */target/surefire-reports        # 判据 5 的纪律：报数前先清，避免读两轮并集
tools/mvn-lock.sh clean verify          # EXIT=0 · Total time: 04:29 min
```

```
[INFO] SimulatorMosire .................................... SUCCESS [  1.003 s]
[INFO] UtilSimos .......................................... SUCCESS [  5.793 s]
[INFO] MapSimos ........................................... SUCCESS [  8.355 s]
[INFO] ActorApiSimos ...................................... SUCCESS [  1.584 s]
[INFO] EconomyApiSimos .................................... SUCCESS [  2.293 s]
[INFO] SocialSimos ........................................ SUCCESS [  5.097 s]
[INFO] UnitSimos .......................................... SUCCESS [  3.769 s]
[INFO] CoreSimos .......................................... SUCCESS [ 12.432 s]
[INFO] SDSimos ............................................ SUCCESS [  4.082 s]
[INFO] ActorSimos ......................................... SUCCESS [  3.858 s]
[INFO] EconomySimos ....................................... SUCCESS [  4.419 s]
[INFO] SimosApp ........................................... SUCCESS [03:36 min]
[INFO] BUILD SUCCESS
[INFO] Total time:  04:29 min
```

前端门禁（`verify` 内含）：`[frontend-gate] OK tests=297 pass=297 fail=0`。
★ 全仓门禁是**三项**（Spotless + Checkstyle + SpotBugs），本跑逐模块 `BugInstance size is 0 / No errors/warnings found`。
★ `EconomyChangeSet`（9 组件）/ `ActorChangeSet`（3 组件）的反射守卫**本批一字未动**（没有加状态组件）⇒ 铁律 5 无牵动。

---

## 5. 测试数（判据 5）—— 基线 vs 改后

**基线（改前实测，直接清点工作区里既有的 `*/target/surefire-reports`，未跑 Maven）**：
11 模块 / **2467** / 0 失败 —— app **684**、map 379、unit 305、core 215、util 199、sd 188、social 188、economy 171、
actor 104、economy-api 26、actor-api 8。
★ 与 `AGENT.md` §七 记的"app 683 / 2464"差 1/3（那份逐模块清单自身加起来是 2466）—— **以实测 2467 为准**，见 §6.3。

**改后（`clean verify` 之后逐模块清点）**：

| 模块 | 改后 | 基线 | 差 | 说明 |
|---|---|---|---|---|
| simos-util | **201** | 199 | **+2** | 货币字面量唯一性 2 条源扫描护栏 |
| simos-map | 379 | 379 | 0 | |
| simos-actor-api | 8 | 8 | 0 | |
| simos-economy-api | **38** | 26 | **+12** | `MoneyIdentityTest` 12 条（`InstrumentId` 在既有 `EconomyIdsTest` 里登记，不新增条数） |
| simos-social | 188 | 188 | 0 | |
| simos-unit | 305 | 305 | 0 | |
| simos-core | 215 | 215 | 0 | |
| simos-sd | 188 | 188 | 0 | |
| simos-actor | **105** | 104 | **+1** | 多币种 `money()` 往返用例 |
| simos-economy | 171 | 171 | 0 | |
| simos-app | **685** | 684 | **+1** | 货币词表读口用例 |
| **合计** | **2483** | **2467** | **+16** | 差额 = 新增用例数 16，逐模块 0 失败 / 0 错误 |

---

## 6. 与任务书不符 / 拿不准（如实，不美化）

1. ★★ **`MoneyInstrument.issuer` 的类型**：任务书（与 master plan §M1.1）写 `ActorRef issuer`，**实现为
   `Optional<ActorRef> issuer`**。理由：同一条任务书又要求"`SPECIE` 可无 issuer"，而"可无"在**非空** `ActorRef` 上
   根本无法表达（除非允许 `null`；本仓的"可无"一律用 `Optional`，见 `Debt.commodity` / `ActorData.meta` /
   `Transfer.settles`）。守卫语义逐条照任务书，差异只在**容器类型**，已在类注里具名写清。
2. **`InstrumentId` 的包**：任务书写"在 `.../money/` 新增"，实现放在 **`.../api/id/`**（`CurrencyId`/`CommodityId`
   同族 + `EconomyIdsTest` 现成的登记护栏 —— survey §3 的落点建议正是"走 `id/` 包"）。`CurrencyDef`/`InstrumentKind`/
   `MoneyInstrument`/`MoneyVocabulary` 四个都按任务书落在 `.../money/`。
3. **基线的数字**：任务书说"11 模块 / 2467 / 0 失败"（**与实测相符**）；但 `AGENT.md` §七 的逐模块清单
   （app 683、共 2464）与实测（app **684**、共 **2467**）差 1/3 ⇒ 我按**实测**报数，没有改 `AGENT.md`（不属本批范围）。
4. **"落盘"的口径**：M1.0 的往返用例是**经真 `ActorCodec` 的 JSON 往返**，不是"真文件 I/O"（`simos-actor` 无 store 依赖，
   物理上做不到）。若要求"关掉进程重开真档"级别的往返 ⇒ 那是 app 侧用例的落点，**本批没做**（见 §1.4）。
5. **`CurrencyDef.id` 保持 `String`**（照任务书原签名）：它因此与 `CurrencyId` 是"同一身份的两种类型" ⇒ 我加了
   `currencyId()` 作为**唯一转换点**，并用用例钉住两者逐值一致。若判"应该直接用 `CurrencyId`"，那是签名级的改动，**请裁**。
6. ★★ **`SPECIE` 的 issuer 裁定 = "必须为空"**（任务书点名要"想清楚再定"）。理由（写进类注）：① `issuer` 的语义是
   "这张工具的价值是**对谁**的索取"（债权人槽位），金属币的价值来自金属本身 ⇒ 没有这个对象；把"铸主"填进来会被读成
   "某人欠我一张银币"（本仓最反对的"看起来在记、其实误读"）；② "谁铸的"是**另一个事实**（铸熔/成色，M4+），同一个字段
   不许承载两个语义 —— M4 要记铸主就**加字段**，而不是复用；③ 与"缺 issuer 就抛"合起来，`kind ⇒ issuer` 是一条
   **全覆盖规则**（没有"看情况"的第三档），可逐档钉死。**反向代价如实记**：M1.1 因此记不下"这枚银币是哪家铸的"
   （刻意；放松守卫 = 静默接受以前判为非法的状态，加字段才是机械改动）。
7. **没跑/没做**：没有变异测试运动（只做了 §1.3 一条针对新用例的自证）；没有真档规模的经济推进（本批零 Java 行为改动，
   真档只是"跑一遍既有回归"）；没有碰 M1.2–M1.8 的任何一行。
8. **拿不准**：`MoneyVocabulary` 放在 `economy-api`（因为 `CurrencyDef`/`MoneyInstrument` 要用 `ActorRef` 与 `CurrencyId`，
   而 `simos-util` 看不见它们）与 `EconomyVocabulary` 放 util 的**不对称**是刻意保留的 —— 若将来 `simos-unit` 需要"按币种
   吃饷"，要的是更上一层的接口（M4+ 裁定），**不是**把新类型降级成 `String`。这条边界我写进了类注，但**没有裁过**，请复核。

---

# M1 剩余（M1.2–M1.8）施工台账（2026-09-27 由控制方追加，不改上文历史行）

> **口径**：用户 2026-09-27 追加裁定「**阶段门禁只做编译，所有测试统一移到最后**」⇒ 本节只记**代码落点、编译证据与如实边界**；
> **测试、变异自证、一年期读数全部留到最后统一做**（本目录不再有逐任务的判据实跑）。
> 勘察来源：`.superpowers/sdd/2026-09-27-m1-accounts/survey.md`；计划：MASTER §四（M1.3–M1.8）+ §八·补（三处裁定）。

## 1. 提交与改动集

| 任务 | 提交 | 改动集（生产代码） |
|---|---|---|
| **M1.2** 冻结机制 | **48cd6c62**（★ 当时**随 §一.5 文档一起提交**，未单独留台账；本轮补记） | `GoodsAccount` 加 `frozenBalances`/`frozenMoney`（守卫 `0 ≤ 冻结 ≤ 余额`、缺键=空表）；`AvailableStock`（唯一"余额 − 冻结"算法，商品/货币共用一行）；`OwnershipBooks.freeze*`/`release*`（纯函数、绝对值语义）+ 5 个落账点带过冻结；`ApiViews.economyOwnership` 发 `frozenGoods/frozenMoney/availableGoods/availableMoney` |
| **M1.3–M1.6** 账与结算 | **ff4d0813** | ① `landOperatorMoney` 账本缺席由"静默新建空商品账"改为 fail-closed 抛（与家户对称；load 口径仍不抛并写明分工）；`ActorPayloads` 显式解析可缺省的两张冻结表；`HouseholdSeeder` 两处创世改五参。② `applyTransfer` 改**两遍式**（`validateApplyTransfer` 只读全量校验 → 统一落账），`debitOperator` 摘判据成纯落账；仍唯一 applier；成功路径逐值不变、失败路径不留半笔。③ `DebtIndex.byDebtor/byCreditor` 纯派生双向索引；`ApiViews` 加 `creditCount/creditPrincipal` + `credits/debtDetails`（`dueCycle` 首次进读口）；不复活 `Claim`、不动 `FlowRow`。④ `ApiViews.moneyLayers`（私人流通/全部基础货币/银行存款/未归类）+ `MoneyIssuance` 类注明写"无总不变量" |
| **M1.7–M1.8** 关系与劳动 | **9521bd00** | ① `SubsistenceObligation`（具名、纯派生；`perLaborDue` 与实付同源、`retentionOf = min(请求, 承诺)` 且**不收人口参数**）；`RegimeRelations.defaultSubsistenceObligations`；`ApiViews.industryView` 发 `subsistenceObligations/subsistencePromised`；`EconomySettlement.laborOfCohort` 提为 public 供同一函数复用。② `ClassRow.participationAdjustedLaborMilli`（折算唯一算法）；`EconomySeeder.CLASS_WEIGHTED_LABOR_PER_MILLE = 860`（由份额×参与率派生）；`laborBudget` 与 `appendAllocation` 权重改用**折算后可用劳动**；`industryDailyLabor`/`laborOfCohort` 收口到同一算法；读口加折算劳动与两个人均量 |

## 2. M1.3–M1.8 判据的落点（★ 实跑验证留到最后）

| 判据（MASTER） | 代码落点 | 状态 |
|---|---|---|
| M1.3 新旧两条读路逐值一致；写入只经一处 | `OwnershipBooks` 四条 `land*` 对称 fail-closed；写口仍 = `applyTransfer` + 四个 `land*`（+ freeze/release） | 代码就位（对拍用例**未写**，留到最后） |
| M1.4 ① 唯一 applier ② 注入失败 ⇒ 两边都不动 | `EconomySettlement.applyTransfer` 两遍式 | 代码就位；子代理 /tmp probe（三组失败后四副本 before==after）**不是仓库用例** |
| M1.5 一笔债两方向逐值相同；`dueCycle` 可读；应收不入 `FlowRow.income` | `DebtIndex` + `ApiViews`（`payable`/`receivable` 同源） | 代码就位（用例留到最后） |
| M1.6 逐工具守恒式；明写无总不变量 | `ApiViews.moneyLayers` + `MoneyIssuance` 类注 | 读口就位；`EconomyMoneyInvariantTest` 的**扩写留到最后** |
| M1.7 ① 给养义务可查 ② Σ经营者保留 ≤ 承诺 | `SubsistenceObligation` + `RegimeRelations` + `ApiViews` | 代码就位；冒烟（义务与 `RuleSettlement` 4/4 相等、`retentionOf` 封顶）**非仓库用例** |
| M1.8 四档人均劳动拉开；`Σ allocated ≤ available` 仍绿 | `ClassRow` 唯一算法 + `EconomySeeder` 预算/权重 + 读口 | 代码就位；数字见 §3 |

## 3. 改前 → 改后数字（本批唯一的数值行为改动 = M1.8）

| 量 | 改前 | 改后 |
|---|---|---|
| 夹具（1000 人平原纯农村，毛劳动 580,000）逐批次预算 | 275,000 / 15,000 | 236,500 / 12,900（=毛额×860‰） |
| 同一夹具默认 WEAVE=100‰ 的创世配额 | farm 448,920 + weave 49,880 = **498,800** | farm **428,968** + weave **39,904** = **468,872**（−6.00%） |
| 真档 tick360 四阶层 `labor/pop`（只读折算） | 全部 **562.0‰** | 贫 **534.0** / 中 **505.9** / 富 **421.5** / 地 **56.2‰**（贫/地 = **9.50×**） |
| 真档 Σ 劳动（只读折算） | 毛 6,387,680,800；改前口径 Σ配额 5,821,809,730（= 折算后的 106.0%） | 折算后可用 **5,491,860,227**（−14.03%） |

## 4. 如实边界（没做 / 没验证，★ 不美化）

1. **未跑任何测试/门禁套件**（用户裁定）：M1.3–M1.8 全程**只做编译门禁**（`rm -rf <改动模块>/target/classes` 后
   `tools/mvn-lock.sh -DskipTests compile` = BUILD SUCCESS，2026-09-27 由控制方复跑）；**无既有回归网的保护**。
2. **M1.8 的已知后果（★ 未扩范围、待后续裁）**：预算按逐批次折算后可用劳动封顶 + 性别权重偏斜 ⇒ **默认配置下"分不满"**
   （配额之和可小于折算后日劳动；夹具实测 −6.00%、weave −20%）。代码既有的"分不满=合法状态"是 R2 明文语义；
   **cap-aware 再分配**（把被 clamp 的份额转给仍有余额的批次、保持活动总量）会改变该语义、超出 MASTER M1.8 原文，
   **本批未做**，留作 M2/后续裁定项。
3. **M1.4 不校验"冻结够不够"**：会话副本只有两张**余额**表（冻结在 actor 侧 `GoodsAccount`），且本批无 freeze 生产调用方
   ⇒ M2 出现冻结写入后要把冻结带进副本（Javadoc 已写明）。
4. **M1.7 的 `retentionOf` 目前无生产调用方**：经营者还没入市；接缝 = M2.1/M2.2 的订单/保留算式（类注已写明）。
5. **M1.5 未累计"应收/利息收入"**：读口只读现有 `Debt`（principal 已含并入利息）；不新增落点（计划明文禁止入 `FlowRow.income`）。
6. **M1.6 未建"累计发行/注销"计数器**：本批恒 0；登记点将来挂在 `MoneyIssuance.REGISTERED` 旁（类注）。
7. **没有跑真档一年期**：M1.8 的真档效果（配额总量、产量、饥荒）**未实测** —— 按裁定留到最后统一做（tick 120/240/360）。
8. **M1.2 的测试**（冻结守卫/往返/可用算法）同样留到最后；**M1.2 当时随文档提交、没有单独台账**是流程缺口，本轮补记。
