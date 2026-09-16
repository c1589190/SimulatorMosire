# Task 5 fix round 3 复审（re-review）：`2630e3f..6e4c826`

- 复审对象：`6e4c826`（范围内恰 1 commit，2 文件，+14/−2）；对照 `task-5-review.md` 的三条 Minor
- 复审方式：**只读 diff + 独立变异复跑**（3 条变异，全部就地恢复并逐字节校验）；门禁只取
  `target/surefire-reports` 计数旁证（**96 tests / 0 failures / 0 errors**），**未跑全量 `verify`**
- 结论：**三条 Minor 全部已解决；范围干净；无新发现**（一条原评审已标注的"可选臂"仍留白，不阻塞）

---

## 0. 前置核对（评审包 vs 仓库）

| 项 | 结果 |
|---|---|
| `git log --oneline 2630e3f..6e4c826` | 恰 1 条，即 `6e4c826` |
| 评审包 diff vs `git diff 2630e3f..6e4c826` | 去掉上下文后，`+` 行（14）与 `−` 行（2）**逐行一致**（仅 hunk 上下文宽度不同） |
| `git show --stat 6e4c826` | 2 文件 / +14 / −2，与包一致 |
| 复审开始前工作树 | `git status --short` 与 `git diff --stat` 均为空 |

---

## 1. Minor 1（`bySubject` 守卫空转）→ **已解决**

**判据强度**：新用例 `nullBySubjectIsRejected`（`InMemoryInfoSystemTest.java:201-206`）用
`assertThatNullPointerException().isThrownBy(() -> new InMemoryInfoSystem(null)).withMessage("bySubject")`
——AssertJ 的 `withMessage` 是**全等**匹配（非 contains），钉的是字段级消息，不是异常类型。

**独立变异（M-a）**：删掉紧凑构造器里的 `Objects.requireNonNull(bySubject, "bySubject")`
（`InMemoryInfoSystem.java:26`；精确锚点唯一命中，1 行删除）→ 单跑
`./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`：

- `Tests run: 14, Failures: 1, Errors: 0` —— **唯一转红的就是新用例**
  `InMemoryInfoSystemTest.nullBySubjectIsRejected`，其余 13 条全绿（旧 13 条无一动，证明新用例承载了全部判别力）。
- **失败行号**：`InMemoryInfoSystemTest.java:205`（失败断言处），traceback 中 lambda 位于 `:204`；
  报告自述的 ":204/:205" 与实测一致，无夸大。
- 失败报文：`Expecting message to be: "bySubject" but was: "Cannot invoke "java.util.Map.forEach(...)" because "bySubject" is null"`；
  抛出点 `InMemoryInfoSystem.<init>(InMemoryInfoSystem.java:27)`——**正是下游 `forEach` 顶包**。
  这独立证实：只钉 NPE 类型确实会空转，钉字段级消息后才有判别力（M12 同型第 3 次）。

**恢复**：`InMemoryInfoSystem.java` sha256 = `36067f365582…` 与变异前**逐字节一致**；`git diff --stat` 为空；
随后干净复跑 14/14 全绿。

## 2. Minor 3（Javadoc 失实）→ **已解决**

**措辞核对**（`InMemoryInfoSystem.java:18-21`，全中文）：构造期"逐值 `List.copyOf` + 整体 `Map.copyOf`"深拷贝
被表述为"**这是唯一可观测的防线（删掉它有用例转红）**"；`#put` 里的拷贝被表述为"**不可观测的纵深防御**：
构造期已深拷贝，删掉它没有用例会转红——留着只为写路径自成一体，别当成承重墙"。原先把 `put` 拷贝与构造期
拷贝并列为防线的句子（"本类自身的写路径（{@link #put}）对各主体列表同样 {@code List.copyOf}，外部无从经它改写内部状态"）
**已整句删除**，无失实残留。

**两半断言我都独立复核过**（不只是采信报告）：

- "`put` 拷贝不可观测"：变异 M-b（`next.put(subject, List.copyOf(entries))` → `next.put(subject, entries)`）
  → `Tests run: 14, Failures: 0`，**全绿**（构建 SUCCESS）——纵深防御定性属实。
- "构造期深拷贝是可观测防线、删掉必有红"：变异 M-c（整块深拷贝退化为 `bySubject = Map.copyOf(bySubject);`）
  → `Tests run: 14, Failures: 1`，红在
  `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180`——"删掉它有用例转红"属实。
  （唯一性在语义上有两层证据：M-b 证明 `put` 侧无判据，M-c 证明构造侧有判据，两者合起来即"唯一"。）

**恢复**：两次变异后均 `cp` 回原文件，sha256 与 `git diff --stat` 复核干净（见 §4）。

## 3. 范围清洁性 → **干净**

| 检查 | 结果 |
|---|---|
| 文件数 | 恰 2 个（1 生产 + 1 测试），无 pom、无其他文件 |
| 生产文件改动性质 | 改动行**全部落在 Javadoc 块内**（2 行注释删除 / 3 行注释新增；程序化扫描 `+/-` 行中无任何非 `*` 开头行——**零代码变更**） |
| 测试文件改动性质 | **11 insertions / 0 deletions**（纯新增；无既有断言被改写或删除） |
| 新增位置 | 新用例插在 `theExposedMapIsImmutable` 之后、`entry(...)` 私有助手之前，未触碰任何既有用例 |
| 依赖 / 越界 | 无新增依赖；`simos-util/src` 下无游离未跟踪文件 |
| 门禁旁证 | `simos-util/target/surefire-reports/`：9 个类 **96 / 0 / 0**（`InMemoryInfoSystemTest` 14 条，较 fix-2 的 13 条 +1，恰为新用例） |

## 4. 变异记录（3 条，全部恢复）

| # | 变异 | 实测红（用例:行） | 恢复校验 |
|---|---|---|---|
| M-a | 删紧凑构造器 `requireNonNull(bySubject, "bySubject")` | `nullBySubjectIsRejected:205`（lambda `:204`；14 run / 1 failure） | sha256 `36067f36…` 一致 + `git diff --stat` 空 + 复跑 14/14 绿 |
| M-b | `put` 去掉 `List.copyOf` | **不红**：14/14 绿（EXIT=0） | 同上（sha256 一致 + diff 空） |
| M-c | 构造期深拷贝 → 浅 `Map.copyOf(bySubject)` | `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180`（14 run / 1 failure） | 同上（sha256 一致 + diff 空） |

- 原始 sha256（变异前 = HEAD 提交内容）：`InMemoryInfoSystem.java` = `36067f365582d36742b1c2d573b76c9e5df4795b12d4a7a0deeefc0c784b8cbe`；
  `InMemoryInfoSystemTest.java` = `ab356e0f3214504991db3bbd7d94904431ae4f20209a68ab06d909cebaabf42c`（全程未动）。
- 复审结束时：工作树 `git status --short` / `git diff --stat` 均空；`target/surefire-reports` 已从备份复原为
  实现者 `verify` 的产物（96 / 0 / 0，18 个文件）。

## 5. 复审者自曝（harness 陷阱，供后续轮次记账）

恢复用 `cp -p` 保留了源文件的**旧 mtime**，Maven 增量编译据此判定 class 未过期——第一次"干净复跑"实际跑的是
`target/classes` 里的**残留变异体**，报出 1 红（`…BackingList…:180`），差点被误记为"恢复后仍红"。`touch` 源文件后
复跑即 14/14 绿。**内容自始至终逐字节正确（sha256 未变）**，但这条与评审包里"先删 `surefire-reports` 避开陈报"
同族：**恢复后必须强制重编译（或核对 class 与 source 的 mtime 序），否则变异体会继续冒充基线。**

## 6. 新发现

**无。** 一条残留（非新发现，原评审已标注为"可选"）：`Map` 的某 subject 值为 `null` 时，`List.copyOf(entries)`
抛**无消息** NPE，仍无用例；原评审 Minor 1 明说"map 值 null 那臂可选"，本轮未涉及，**不阻塞**。
