# Task 5 评审报告：外挂时态属性（`info` 包）

- 评审对象：`f7785cb..2630e3f` 中的 **`0f14a95` / `baeeb95` / `2630e3f`** 三个 commit
  （`f7ac329` 是控制器的 spec 文档提交，按指令**不在评审范围**）
- 评审方式：**不采信报告自述**——自建变异 harness 独立复跑；产物旁证只读不重跑全量 `verify`
- 结论：**A. 规格符合性 ✅ ｜ B. 任务质量 通过**

---

## 0. 独立复核的方法与纪律

- harness：`/tmp/t5review/mutate.py`（本轮现写）。流程 = 精确串锚点替换（锚点必须唯一命中，否则中止）
  → **`javac` 预检**（避免把"编译不过"记成"转红"）→ 单跑
  `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`（**每次先删 `surefire-reports/`**，
  避开陈报陷阱）→ 从 `/tmp/t5review/snap/` 整文件恢复 → 逐字节 `sha256`（4 文件）+ `git diff --stat` 复核。
- 共独立跑了 **17 条变异**（指令要求 ≥ 7 条），覆盖指令点名的全部 8 条。
- **17 条全部恢复干净**：每次恢复后 `git diff --stat` 均为空，4 个源文件 `sha256` 与快照一致，
  `main/java` 下无游离测试类（我加了这个硬断言——实现者报告的"事故 1"正是这个坑）。
- 所有**转红都是真·用例红**：每条红都伴随 `Tests run: 13` 的 surefire 报告（Failures/Errors 计数），
  无一条是构建中断（我的 F1 首版曾因"参数与字段同名遮蔽"编译失败，被 `javac` 预检拦下并修正——
  这本身就是报告里"构建红 ≠ 用例红"的第 3 个真实样本）。
- 快照 sha256（== HEAD 提交内容，我独立算过）：
  `InfoEntry 876ed3c4…` / `InfoSystem 226aaed3…` / `InMemoryInfoSystem c8d9af59…` /
  `InMemoryInfoSystemTest c5ddfd6c…` —— 与报告 fix-2 表头所列**逐字节一致**。

## 1. 门禁旁证（不重复全量 `verify`）

| 证据 | 结果 |
|---|---|
| `simos-util/target/surefire-reports/`（9 个类） | **95 tests / 0 failures / 0 errors**；`InMemoryInfoSystemTest` 13 条 |
| `simos-util/target/spotbugsXml.xml` | `<BugInstance>` **0 命中**（`effort=More` / `threshold=Low`） |
| `simos-util/target/checkstyle-result.xml` | `<error>` **0 命中** |
| 产物时间戳 vs 提交时间 | 产物 20:22–20:23，HEAD 提交 20:24:09；且提交内容 sha256 == 变异前快照 → 产物对应的是**提交的那份源码** |
| **我在 HEAD 上活跃复跑**（`spotless:check` + `checkstyle:check` + `test` + `spotbugs:check`，仅 `simos-util`） | **BUILD SUCCESS**；单测 13/13；`spotbugsXml.xml` 被重新生成（mtime 20:41:48）仍为 0 BugInstance |
| 依赖 / pom | `git diff f7785cb..2630e3f -- '**/pom.xml'` **为空**（零新增依赖）；变更文件恰为 4 个任务文件 + 1 个越界的 spec 文档提交 |

> 我的变异运行会删/覆盖 `target/surefire-reports/`，评审结束时已把**实现者 verify 时的产物副本**（评审开始前
> 先拷到 `/tmp/t5review/artifacts/`）原样复原，`target/` 与工作树均回到干净状态。

---

## 2. 独立复核的变异表（17 条）

「报告自述」列 = `task-5-report.md` fix-2 表的原文；标 ✅ 表示**逐字一致**（含行号）。

| # | 变异（我独立实施的写法） | 我实测的红（用例:行） | 报告自述 | 一致 | 恢复 |
|---|---|---|---|---|---|
| 1 | **构造期退回浅拷贝**（整块 → `Map.copyOf(bySubject)`） | `mutatingTheBackingListAfterConstruction…:180` | `:180` | ✅ | 干净 |
| 2 | **去掉最后那层 `Map.copyOf(copy)`** → `copy` | `theExposedMapIsImmutable:190` | `:190` | ✅ | 干净 |
| 3 | **record → `final class`**（保留 `bySubject()`，只剩身份相等） | 4 红：`instancesWithSameContentAreEqual:128`、`emptyInstancesAreEqual:137`、`mutatingTheBackingList…:182`、`mutatingTheBackingMap…:166`；**`instancesWithDifferentContentAreNotEqual` 不红** | 同 4 红（同一集合） | ✅ | 干净 |
| 4 | **F2 互补：给 record 塞恒真 `equals`**（+ 恒真 `hashCode`） | **仅** `instancesWithDifferentContentAreNotEqual:145` | `:145` | ✅ | 干净 |
| 5 | **M4 复合（可变结构 + 就地改 + `return this`）** | 5 红：`putReturnsANewInstanceAndLeavesTheOriginalUntouched:70`、`mutatingTheBackingList…:180`、`mutatingTheBackingMap…:164`、`theExposedMapIsImmutable:190`、`instancesWithDifferentContentAreNotEqual:145` | 同 5 红 | ✅ | 干净 |
| 6 | `get` 去掉 key 匹配 | `readsByKeyAndMoment:31` | `:31` | ✅ | 干净 |
| 7 | `get` 去掉有效期判定 | `validityWindowIsHalfOpen:50` | `:50` | ✅ | 干净 |
| 8 | 后写胜 → **先写胜**（条件加 `found == null &&`） | `theLastInsertedOverlappingEntryWins:62` | `:62` | ✅ | 干净 |
| 9 | 删 `put` 的 subject 守卫 | `putRejectsNullSubjectAndEntry:110` | `:110` | ✅ | 干净 |
| 10 | 删 `put` 的 entry 守卫 | `putRejectsNullSubjectAndEntry:111` | `:111` | ✅ | 干净 |
| 11 | 删 `InfoEntry` 的 `requireNonNull(value)` | `nullPartsAreRejected:91` | `:91` | ✅ | 干净 |
| 12 | key 校验去掉 `isBlank` 臂 | `blankOrNullKeyIsRejected:79` | `:79` | ✅ | 干净 |
| 13 | 去掉 `put` 自身的 `List.copyOf`（= 报告 M16 对照行） | **未转红：13/13 全绿** | 未转红 | ✅ | 干净 |
| 14 | **（我新增）删紧凑构造器的 `requireNonNull(bySubject, "bySubject")`** | **未转红：13/13 全绿** ← 见 §3(c) | — | — | 干净 |
| 15 | （我新增）逐值拷贝退化 `copy.put(key, entries)`（= 报告 F6 同型） | `mutatingTheBackingList…:180`（`theExposedMapIsImmutable:192` **仍绿**） | F6 → `:180` | ✅ | 干净 |
| 16 | （我新增）整块构造期拷贝删除（= 报告 F7） | 3 红：`theExposedMapIsImmutable:190`、`mutatingTheBackingMap…:164`、`mutatingTheBackingList…:180` | F7 → 同 3 红 | ✅ | 干净 |
| 17 | （我新增）复合 list 泄漏：`put` 不拷 + 构造期不逐值拷 | `theExposedMapIsImmutable:192`、`mutatingTheBackingList…:180` ← 见 §3(d) | — | — | 干净 |

**结论**：报告的 20 行变异表我抽验了 11 行（含被点名的全部 8 条），**用例名与行号逐字吻合，无一处夸大或错记**；
两条"有意不红"的对照行（M16）以及"F1 对负向断言无判别力"的自曝，均被独立复现并确认为**准确**。

**额外旁证（M12/M13 原始报文）**：删掉 `put` 的守卫后，用例仍转红**只是因为钉了消息**——
抛出的 NPE 来自下游（`Map.copyOf` @ `InMemoryInfoSystem.java:28` / `List.copyOf` @ `:52`），消息为 `null`。
这独立证实了报告 §4-B 的判断：**只钉异常类型的断言在这两条上会空转**。

---

## 3. 空转 / 冗余护栏（单列；含我新发现的两条）

### (a) F1（record→final class）对 `instancesWithDifferentContentAreNotEqual` **无判别力** —— 确认，且报告自曝准确
我的 F1 变体下，红的是 `instancesWithSameContentAreEqual:128`、`emptyInstancesAreEqual:137` 与两条防御拷贝用例的
`equals` 臂（`:182` / `:166`），**负向断言用例一条都不红**。身份相等天然满足"不相等"，这条变异抓不到它。

### (b) 其判别力由互补变异 F2（恒真 `equals`）证明 —— 确认
F2 下**只有** `instancesWithDifferentContentAreNotEqual:145` 转红（我实测 `Failures: 1`）。
两条变异合起来 = 相等语义的"下界 + 上界"都有人守。**F1 的那条用例不是空转，是"过宽相等"的镜像守卫。**

### (c) 【我新发现】**紧凑构造器的 `Objects.requireNonNull(bySubject, "bySubject")` 无任何判据**（空转护栏）
删掉它（变异 14）→ **13/13 全绿**。没有任何用例构造 `new InMemoryInfoSystem(null)`。
它是 fix-2 新写的代码，且是本任务里**唯一一条没有自证用例的实参守卫**——与实现者自己在报告 §7 给 `put`
两条守卫补测时立的标准（"本任务既然在补守卫，就不该留下一半"）**自相矛盾**。
同族的另一个未覆盖面：`Map` 里某 subject → `null` 列表时 `List.copyOf(entries)` 抛**无消息** NPE，同样无用例。

### (d) 【我新发现】`theExposedMapIsImmutable` 的 `:192` / `:194` 两条断言**单点变异不可达**（复合才响）
`:190`（`bySubject().put(...)`）已由 F5 单点证明有判别力；但 `:192`（内层列表 `add`）在我跑过的**全部单点变异**
下都不先红（`get` 到的列表在 `put` 与构造期**各有一道** `List.copyOf`），只有把两道同时撤掉（变异 17）才红。
属"**复合才可判**"的次要断言：不是空转（它确实守着一个独立契约），但**不能当单点护栏记功**；`:194`
（`hasSize(1)`）在我所有变异下从未成为首条红。建议：记档即可，不必删。

### (e) `put` 里的 `List.copyOf` 在 fix-2 后**不可观测** —— 确认（与报告口径一致）
变异 13 → 13/13 全绿。构造期深拷贝已使 `put` 那次拷贝行为冗余。**非缺陷**（纵深防御 + `put` 自身语义完整），
但连带一个措辞问题：`InMemoryInfoSystem` 的 Javadoc 仍把"`put` 对各主体列表 `List.copyOf`"与构造期拷贝
并列为防线（见 Minor 清单第 3 条）。

---

## 4. 规格符合性逐条

| 权威条款 | 核对结果 |
|---|---|
| **§十-D4**：`get(Address, String key, SimosTimestamp)` 增 key；`put` 返回新实例 | ✅ 接口签名一致；`put` 返回 `InMemoryInfoSystem`（协变收窄），原实例不变（brief 用例 + 变异 5 双向钉住） |
| **§十一**：`equals`/`hashCode`/`toString` 由 record 提供，**禁手写** | ✅ `grep "public boolean equals\|public int hashCode\|public String toString"` 在 `info/` **零命中**；值语义由 F1/F2 双向自证 |
| **§十一**：集合防御性拷贝一律 `List.copyOf` | ✅ 构造期**逐值** `List.copyOf` + 整体 `Map.copyOf`；`put` 路径同样 `List.copyOf` |
| **§十一（第 355 行）/不可变** | ✅ 无可变字段暴露；`bySubject()` 拿到的 map 与 list 都不可改（`:190`/`:192` 两臂 + 变异 2/17） |
| **§二 包结构** | ✅ 落在 `io.mosire.simos.util.info`，恰好 `InfoEntry` / `InfoSystem` / `InMemoryInfoSystem`，未多出类型、未加 `package-info` |
| **§十二 测试清单**（"按 key + 时刻取值；有效区间左闭右开；`put` 返回新实例且原实例不变"） | ✅ 逐条有对应用例 |
| brief 的类型/签名/构造器守卫（key 空白 IAE + 4 条 NPE 字段级消息） | ✅ 与 brief 逐字一致（仅 google-java-format 折行差异） |
| 注释/文档中文、无新增依赖 | ✅ |

---

## 5. 行为回归（三轮改动后）

- **公开 API**：`empty()`、`get(Address,String,SimosTimestamp)`、`put(Address,InfoEntry)` 三个签名在
  `0f14a95` → `baeeb95` → `2630e3f` **逐次完全相同**（我逐 commit dump 签名核对）。
- **`get` 语义未变**：key 匹配 + 有效期判定 + "同 key 重叠取**插入序最后者**"三件在 fix-2 中**代码一字未动**
  （`git diff baeeb95 2630e3f` 只触及 Javadoc 与紧凑构造器）；变异 6/7/8 证明三条语义仍被用例钉住。
- **`InfoEntry` 守卫未被削弱**：该文件在三轮提交里 sha256 恒为 `876ed3c4…`（逐轮验证），
  变异 11/12 证明 key 的 IAE 与 NPE 字段级消息仍能转红。
- **fix-1 对 `put` 的唯一改动**是 `new InMemoryInfoSystem(Map.copyOf(next))` → `new InMemoryInfoSystem(next)`，
  拷贝搬进构造器，**语义等价**（变异 1/13/16 交叉验证）。
- **唯一的 API 扩大**（知情项，非缺陷）：首轮构造器是 `private`，record 化后**规范构造器变 public**
  （`new InMemoryInfoSystem(Map)`）。这是裁决 ① 的语言必然结果，且正是 fix-2 深拷贝要堵的入口；
  下游 T6 的 `apply` 可以经它直接拼 `Map` 构造（会被防御拷贝保护）。记档即可。

---

## 6. 对控制器裁决的异议

**无。** 三条裁决我都独立验证成立：

- **① record 化**：F1 证明不 record 就没有值语义（往返断言会以"两实例不相等"形态红）；F2 证明 record 化**没有**
  把相等语义放松到"恒真"。裁决依据（§十一 + §十-D4）与实测一致。
- **② 构造期逐值深拷贝**：F4（退回浅拷贝）→ `:180` 红；变异 15/16/17 三层次各自可判；
  浅拷贝下"调用方 `clear()` 改掉本实例值 + `hashCode()` 漂移"的穿透路径**真实存在**（能改到 record 的值语义）。
- **③ 保留两条自加用例**：两条**都成立**——
  `putRejectsNullSubjectAndEntry` 两臂都真转红，且**必须钉消息**才有判别力（我实测到"下游 NPE 顶包"的报文）；
  `theExposedMapIsImmutable` 的 `:190` 由变异 2 单点证明有判别力（`:192` 见 §3(d) 的限定，不影响该用例成立）。

---

## 7. 发现清单（无 Critical / Important）

- `[Minor]` `simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java:25` —
  紧凑构造器的 `requireNonNull(bySubject, "bySubject")` 无用例（删掉后 13/13 全绿），
  与本任务已给 `put` 两条守卫补测的标准不一致；建议补一条 `new InMemoryInfoSystem(null)` 的
  `.withMessage("bySubject")`（map 值 null 那臂可选）。
- `[Minor]` `simos-util/src/test/java/io/mosire/simos/util/info/InMemoryInfoSystemTest.java:192,194` —
  `theExposedMapIsImmutable` 的后两条断言在**单点**变异下不可达（仅复合变异 17 能红），记录在册免被当成单点护栏。
- `[Minor]` `simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java:19-20` —
  Javadoc 把"`put` 对各主体列表 `List.copyOf`"并列为防线，但它在 fix-2 后已不可观测（变异 13）；
  措辞宜标注为"纵深防御"而非"防线"，避免后人以为它仍在扛事。

以上三条都不阻塞：均无功能缺陷、无规格偏离、无回归。
