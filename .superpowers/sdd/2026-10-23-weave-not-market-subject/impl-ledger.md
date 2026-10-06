# 实现架构账本：weave（集体经营产业）不作为市场主体（裁定 B）

- **责任区**：让集体经营产业 `weave@hex`（家庭纺织）不再作为市场主体登记/警告。
- **约束设计书**：`docs/superpowers/specs/2026-10-23-weave-not-a-market-subject.md`（§3 目标行为 / §4 不变量 / §5 验收判据 / §6 文件所有权）。
- **分支/基线**：`main` @ `7625a503`；动手前 `git status --porcelain` 只有未跟踪的约束设计书。
- **实现者**：办事子 Agent（只写生产代码；不写/跑测试、不 `commit`）。
- **日期**：2026-10-23。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（改动前 file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:4623-4640`（改前行号） | 集体分支（`economicHouseholdOf` 为空、`householdsOf` 非空）发 `MARKET_SUBJECT_COLLECTIVE` **WARN** 后 `continue`；该 unit **从不进**参与者表 | 只需把 WARN 降成默认关的 TRACE，参与者列表天然逐值不变 |
| 2 | `MarketSettlement.java:4593-4623`（改后，原逻辑未动） | `collective.isEmpty()` 分支：无正产能 ⇒ `MARKET_SUBJECT_EMPTY_UNIT` **WARN** + `continue`；有正资产/劳动却解析不到家户 ⇒ 具名 `IllegalStateException` | §3.4 要求保持的两条，原样未触碰 |
| 3 | `MarketSettlement.java:4646-4674`（改后单一家户分支） | 只有 `economicHouseholdOf` 非空的 unit 会被挂到该家户 participant；集体分支是它的前置 `continue` 旁路 | 集体 unit 的产出经 harvest/`creditOutput` 按劳动落成员家户账，市场侧只在成员家户这一层 |
| 4 | `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java:3838-3862`（`householdWeaving`）、`:4292-4309`（`collectiveRelation`） | `weave@hex` 是按 `REGIME_HOUSEHOLD` 建的**产业**；`collectiveRelation` 用合成 `plan.operator()`（`HOUSEHOLD:weave@hex`）做 operator/payee，规则为空 | 清理合成 operator 涉结算 payee，明确不在本责任区；本责任区对该文件**只许改注释** |
| 5 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyLogSource.java:24` | `ECONOMY_MARKET`（`economy-market`，TICK）来源表项已存在 | 新 TRACE 直接复用来源表项，不动来源表 |
| 6 | `simos-app/src/main/resources/log4j2.xml:64-76`（只读核对，未改） | 默认 `io.mosire.simos.economy=INFO`；`-Dsimos.economy.logLevel=DEBUG` 只开到 DEBUG；`-Dsimos.economy.traceLevel=TRACE` 只作用于独立的 `.trace` 子 logger | MARKET logger 上的 TRACE 默认关；审计要看它需 `-Dsimos.economy.logLevel=TRACE`（见 §3.3、§6 注意） |
| 7 | `grep -rnE "MARKET_SUBJECT_(COLLECTIVE|EMPTY_UNIT)" --include='*.java' .`（改前） | 唯一发射点 = `MarketSettlement.java`；`simos-app/src/test`、`simos-economy/src/test` 零命中 | 无既有测试断言该 WARN，降级不会让既有测试红 |

## 2. 实现架构

- **唯一行为改动点**：`MarketSettlement.participantsFor` 的集体分支（改后 `:4624-4645`）。
  - **原**：`EventLog.channel(MARKET).warn(LogEvent.of("MARKET_SUBJECT_COLLECTIVE", EconomyLogSource.ECONOMY_MARKET, day, unit, operator, households, reason="aggregate-unit-not-a-single-household"))` + `continue;`
  - **新**：`if (MARKET.isTraceEnabled()) { EventLog.channel(MARKET).trace(LogEvent.of("MARKET_SUBJECT_COLLECTIVE_SKIPPED", EconomyLogSource.ECONOMY_MARKET, "day", …, "unit", …, "operator", …, "households", collective, "reason", "by-design-not-a-market-subject")); }` + `continue;`（`continue` 原样保留，控制流一字未动）。
- **TRACE 事件形状**（`LogEvent.of(name, origin, …)` 渲染）：
  `event=MARKET_SUBJECT_COLLECTIVE_SKIPPED origin=economy-market originKind=tick day=<n> unit=<unit-id> operator=<actor> households=[…] reason=by-design-not-a-market-subject`
- **为什么加 `isTraceEnabled()` 卫**：默认关闭时连 `LogEvent` 都不构造；与同文件 `MARKET_ROUND_START` 的 `if (MARKET.isDebugEnabled())`（`:797`）写法一致。
- **注释同步**：`MarketSettlement.java:4582-4587` 的三分支总说明把 ② 从"本批具名缺口"改成"**不是市场主体**（2026-10-23 裁定 B）、静默跳过（只留默认关 TRACE）"；`EconomySeeder.java:3858-3860`（`householdWeaving` javadoc）与 `:4304-4307`（`collectiveRelation` javadoc）各加 2~3 行，说明 weave 非市场主体、成员家户各自入市、合成 operator 仍继续作结算 payee。
- **未动**：`MARKET_SUBJECT_EMPTY_UNIT` WARN（`:4602-4615`）、具名抛（`:4617-4622`）、单一家户 `economicHouseholdOf` 分支、`unitsByOperator` 预索引、`DefaultProductionModes`、`ActorKind`、任何状态/Codec/ChangeSet/载荷/命令。

## 3. 关键判断（为什么这样改）

1. **为什么不是"把 weave 改成单一家户"**：裁定 B 明确 weave 是**产业**、不是市场主体；`docs/…spec` §1 概念纪律（生产方式 ≠ 产业/单位）与 §4（不改产业定义、结算语义、身份）都禁止。改单一家户要重绑 operator/份额/payee，会直接改数值行为与结算关系，且正是被弃的路线。
2. **为什么不是"删 WARN 但保留登记（把聚合 unit 登进参与者表）"**：参与者列表会变，违反 §4"逐值一致"；聚合主体没有家户 `ClassRow`（单一家户分支要 `round.householdEconomies.get(household)`，会拿不到 ⇒ 要么现造主体、要么当场抛）；且产出/库存已按劳动落各成员家户账，再登聚合主体等于开第二本账。
3. **为什么 TRACE 走 MARKET logger 而不是 `EconomyLog.trace()`**：本事件属市场域，与兄弟事件 `MARKET_SUBJECT_EMPTY_UNIT` / `MARKET_ROUND_START` 同 logger；仓库已有"域 logger + TRACE"的先例（`DebtContractBook` 的 `DEBT_UPSERT` 等）。默认关成立：MARKET 继承 `io.mosire.simos.economy=INFO`，`-Dsimos.economy.logLevel=DEBUG` 仍不开 TRACE。
4. **为什么保留 `day/unit/operator/households` 字段**：§3.2 只强制事件名与 `reason`；保留原 WARN 的上下文便于审计，日志字段不参与任何状态/公式。

## 4. 偏离约束设计书之处及原因

**无契约性偏离**（事件名、级别、reason 值、行为、文件所有权均按 §3/§4/§6）。以下是设计书未指定、实现层自选的细节：

1. TRACE 挂在 `MARKET` logger（设计书只说 "TRACE（默认关）"，未指定 channel）；
2. 除 `reason` 外保留原上下文字段；
3. 增加 `isTraceEnabled()` 卫（默认路径零构造）；
4. 额外同步更新 `MarketSettlement` 顶部三分支注释 + `EconomySeeder` 两处 javadoc（§6 明文允许这两处注释）。

## 5. 真实命令与结果

```text
$ tools/mvn-lock.sh -q spotless:apply
rc=0
（运行后 git status --porcelain 仍只有本责任区的 2 个 M 文件 ⇒ 未格式化/改动他文件）

$ tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile
rc=0
```

静态负向核对（改后）：

```text
$ grep -n "\.warn(" simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java
4603:                  .warn(          # 全文件唯一 WARN = MARKET_SUBJECT_EMPTY_UNIT（集体 WARN 已删）

$ grep -n "MARKET_SUBJECT_COLLECTIVE" …/MarketSettlement.java
4631:                        "MARKET_SUBJECT_COLLECTIVE_SKIPPED"   # 只剩 TRACE 事件名，无旧事件字符串

$ grep -n "市场参与者无法解析到任何家户" …/MarketSettlement.java
4618:                "市场参与者无法解析到任何家户（账户主体只有家户；…）："   # 具名抛保留

$ javap -p -c -classpath simos-economy/target/classes io.mosire.simos.economy.time.MarketSettlement
  # 编译产物 participantsFor 内字符串常量只剩：
  #   MARKET_SUBJECT_EMPTY_UNIT           -> LogChannel.warn(LogEvent)
  #   MARKET_SUBJECT_COLLECTIVE_SKIPPED   -> LogChannel.trace(LogEvent)

$ grep -rn "MARKET_SUBJECT" --include='*.java' simos-app/src/test simos-economy/src/test
（零命中，rc=1）
```

一次性最小逻辑核对（`/tmp/WeaveSkipTraceCheck.java`，不入库）：

```text
$ java -cp simos-util/target/classes:simos-economy/target/classes /tmp/WeaveSkipTraceCheck.java
event=MARKET_SUBJECT_COLLECTIVE_SKIPPED origin=economy-market originKind=tick day=5 unit=unit-weave@0,0 operator=HOUSEHOLD:weave@0,0 households=[hh-1] reason=by-design-not-a-market-subject
CHECK-OK
rc=0
```

## 6. 未完成 / 未验证项

- **真实世界重跑**（≥30 tick 后 `grep -c 'MARKET_SUBJECT_COLLECTIVE' = 0`、day1-30 的 `MARKET`/`DAY_END` fills/creditFills/unfilled/unmet/transfers 逐值对拍）：按派单由控制方在真实世界复跑执行；本责任区未跑 `test`/`verify`/`package`、未跑世界。
- `MARKET_SUBJECT_EMPTY_UNIT` 只做了静态/字节码核对（WARN 发射点仍在），**未**跑运行时探针证明它仍能出现。
- **验证注意（重要）**：§3.2 强制的 TRACE 事件名 `MARKET_SUBJECT_COLLECTIVE_SKIPPED` **含**旧名子串 `MARKET_SUBJECT_COLLECTIVE`。§5 的 `grep -c 'MARKET_SUBJECT_COLLECTIVE' = 0` 只在 **TRACE 关闭**（默认/DEBUG）时成立——本实现正是默认关（§5 本身也写"默认关时零输出"）。若审计时开 `-Dsimos.economy.logLevel=TRACE`，该 grep 会命中新事件名；此时应改用精确 `event=MARKET_SUBJECT_COLLECTIVE `（带尾随空格）或排除 `_SKIPPED`。
- 合成 `HOUSEHOLD:weave@hex` operator 的清理/迁移（涉结算 payee）按派单不属本责任区，未做。
- 未 `git commit`（按派单由控制方提交）。

## 附：改动文件清单

- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java`（集体分支 WARN → 默认关 TRACE + 相关注释）
- `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（**仅** javadoc 注释，零代码行）
- 本账本：`.superpowers/sdd/2026-10-23-weave-not-market-subject/impl-ledger.md`
