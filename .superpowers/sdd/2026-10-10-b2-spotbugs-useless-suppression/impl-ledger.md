# B2 SpotBugs 无用抑制清除 —— 实现架构账本（写码 Agent，极小修责任区）

> **依据**：派单（责任区 = 极小修：删 `simos-economy` 里的无用抑制，门禁恢复全绿）；现场红点 = EconomySimos 的
> `US_USELESS_SUPPRESSION_ON_CLASS`（`WorkingDayView`，2 条）。**基线**：工作树 `1f7d656c`（干净）。
> **范围**：允许写 `simos-economy/src/main/**`（实际只动 1 文件）。**不做**：不改测试 / `pom.xml` / `docs/**` / `AGENTS.md`；
> 不改 `WorkingDayView` / `EconomyDayView` 对外语义；不 `git commit`。
> **纪律**：一次一个 Maven；三条命令（spotless:apply → compile -am → verify -pl simos-economy -am）；不跑 `test`。

---

## 0. 一句话

`WorkingDayView` 上的类级 `@SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})` 是**无用抑制**（该类本来就 0 条这两种缺陷）
⇒ **删注解 + 删随之无用的 import，共 4 行**；门禁 `BugInstance size is 0` 恢复，且未靠抑制掩盖任何真问题。

---

## 1. 关键调查结论（`file:line` 为**改动前**行号，逐条核过）

| # | 事实（改动前） | 结论 | 影响 |
|---|---|---|---|
| C1 | `WorkingDayView.java:56-58` = 类级 `@SuppressFBWarnings(value={"EI_EXPOSE_REP","EI_EXPOSE_REP2"}, justification="…只读门面…")`；`:59` `final class WorkingDayView implements EconomyDayView` | 两个值**都**被 SpotBugs 判为无用（派单引文两条） | 整条注解无可留（逐值报，不是"删一个留一个"） |
| C2 | 上一轮 `simos-economy/target/spotbugsXml.xml`（mtime 20:20，修前产物）实测 `BugInstance total = 2`，两条都是 `US_USELESS_SUPPRESSION_ON_CLASS` / priority 2 / class `…time.WorkingDayView` / SourceLine `63-148` | 与派单引文**逐字一致**（含行号区间）⇒ 红点不是转述失真 | 修前基线可信；本条即"修前"证据 |
| C3 | 同文件 `:3` = `import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;`，全文件**唯一**使用点就是 `:56` 的注解 | 删注解后 import 成死引用 | 一并删（否则留死 import，Checkstyle/整洁面留尾） |
| C4 | 本类只持一个 `private final EconomyStateBuilder sheet`（`:61`），构造器一次 `Objects.requireNonNull`；15 个 accessor 全部**现场委托** `sheet.xxxOrBase()` 或 `sheet.base().xxx()`（`:68-143`），无任何字段被直接交出去 | 无"交出自有可变字段"（EI_EXPOSE_REP）、构造器入参也非 SpotBugs 认定的可变类型（EI_EXPOSE_REP2）⇒ 两条抑制**本来就无物可抑** | 与派单判断一致："惰性解析、不持有可变字段暴露" |
| C5 | economy 其余 EI_EXPOSE_REP 抑制共 8 处（`EconomyData:279`、`AccountDelta:32`、`EconomyStateBuilder:67`、`ProductionEnterprise:71`、`EconomySession:79`、`AccountSnapshot:34`、`MarketRegulation:42`、`LegacyHouseholdMigration:76`），本轮 `total_bugs=0` **未**报无用 | 它们是**承重**抑制（真压着缺陷，删了会当场红） | **不动它们**；反过来若删了它们就必须做真修，而那超出本责任区 |

---

## 2. 实现架构（本批实际改动）

**唯一改动文件**：`simos-economy/src/main/java/io/mosire/simos/economy/time/WorkingDayView.java`，`1 file changed, 4 deletions(-)`：

- `:3` 删 `import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;`
- `:56-58` 删类级 `@SuppressFBWarnings(...)`（含 `justification` 三行）

**零行为变更**：`implements EconomyDayView` 的实现体、方法集、每个 accessor 的返回来源、`toString`、
类 javadoc 全部逐字未动；抑制注解在字节码里是 `RuntimeInvisibleAnnotations`（`javap` 实测删后 0 命中），
不进任何运行时语义 ⇒ "只清注解"满足派单的"不改对外语义"。

---

## 3. 关键判断

1. **为什么直接删而不是"改小 value / 改 justification"**：`US_USELESS_SUPPRESSION_ON_CLASS` 是**逐值**上报的
   （修前 2 条 BugInstance 对应 2 个值），两个值都无用 ⇒ 保留任一值仍是红，整条注解没有可留的部分。
2. **为什么不加防御性拷贝"以图合规"**：本类的设计语义就是**活的解析器而非快照**（类注 `:50-51`：
   构造时拍引用会在惰性物化那一刻变成过期快照）；拷贝既**改变行为/破坏设计**，又超出"极小修"。
   而 SpotBugs 本来就没报该类暴露缺陷 ⇒ **无需真修**，也不存在"靠抑制掩盖真问题"。
3. **删注解不会新暴露缺陷（方向性 + 实测双重）**：抑制只可能**减少**报告、不可能产生报告；
   实测同一次运行 `total_classes=504 / total_bugs=0`，且报告内 `ClassStats … class='io.mosire.simos.economy.time.WorkingDayView' bugs='0'`
   ⇒ 该类**确实在本轮分析集里**（回答 §三"见绿先问它分析了几个类"：不是"没分析所以绿"）。

---

## 4. 偏离记录

**无。** 未碰测试 / `pom.xml` / `docs/**` / `.superpowers/**`（除本账本）；未改 `EconomyDayView`；未 `git commit`。

---

## 5. 验证证据（真命令 + 真结果，按派单顺序）

| # | 命令 | rc | 真结果 |
|---|---|---|---|
| 1 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（`-q`）；`WorkingDayView.java` md5 `2b6f90a8424e246ca53c70f78a8359d5` 前后一致（本就合格式，未被改写）；`git status --porcelain` 仅本文件 |
| 2 | `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`；16 模块全 `SUCCESS`（含 EconomySimos 2.054s）；`WorkingDayView.class` **4725 → 4384 字节**（真重编，非增量空转） |
| 3 | `tools/mvn-lock.sh -DskipTests verify -pl simos-economy -am` | **0** | `BUILD SUCCESS`；8 模块（economy + 7 上游）**逐个 `BugInstance size is 0`**（util/map/calendar/social-api/actor-api/economy-api/social/**economy**）；economy `spotbugsXml.xml`：`total_classes=504 total_bugs=0`，`timestamp Sat, 10 Oct 2026 20:21:56`（本轮）；`Tests are skipped.` ×8（未跑 test）|

**前后对照（同一路径）**：修前 `BugInstance total = 2`（两条 `US_USELESS_SUPPRESSION_ON_CLASS`）→ 修后 **0**。
修前的 2 条是从上一轮遗留报告读出的（mtime 20:20），随后我为跑干净轮**删掉了**该 `target/spotbugsXml.xml`（§三：`target/` 不还原）。

---

## 6. 未完成 / 未验证（如实记）

- **未跑 `test`**（派单明令不跑）⇒ 行为回归不在本批证据内；本批改动为"纯删注解 + 删 import"，无行为面。
- **未跑全仓 `verify`**：第三条命令只到 `-pl simos-economy -am` ⇒ `actor / sd / core / gov / army / app` 6 个模块
  本轮**未分析**，其 SpotBugs 结果不在本次证据内（派单指定的红点只在 EconomySimos，故按派单范围收口）。
- **未 `git commit`**（留给控制方）；本账本自身未编入任何提交。
