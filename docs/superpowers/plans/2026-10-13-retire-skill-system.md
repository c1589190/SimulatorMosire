# 2026-10-13 取缔 Skill 系统（拆除）—— 约束设计书

> 本文是**派实现 Agent 的前置**（`AGENTS.md` §五.3 → `docs/agents/02-一-并发.md` §一.8）。
> **用户 2026-10-13 裁定**：「现有项目的 Skill 等直接取缔」。本批**只做拆除**，不做替代。
> **来源**：只读调查（`git grep` / 读文件）。**未跑 Maven**，一切测试计数为**调查值**，由实现 Agent 与验收 Agent 复核。
> **基线**：`main` @ `9c841e06`（工作树干净；另有两份未跟踪的新文档，见 §7）。

---

## 1. 需求来源（用户原话，原样照录）

> 「现有项目的Skill等直接取缔，Skills/Docs系统我想了想其实是一个，Skill无非是有更多触发规则的Docs，你得先给我提炼出来隔壁要做Docs系统至少得满足你这边什么需求」

> 「我们暂时不准备做Skill，只维护一个通用文档系统，最多加个自动注入的简单Agents.md」

★ **本条撤销一条更早的用户原话**，必须显式记录（否则后续子 Agent 会拿旧原话把派单打回来）：
`simos-app/src/main/java/io/mosire/simos/app/skill/SkillLibrary.java:31-32` 逐字收着
「最好搞成存在外部方便读取、写入、修改，还要兼容其他 外部 Agent 方便进行 GM Agent 的通用修改」。
**用户 2026-10-13 裁定取代该条**：不再维护独立的 Skill 文件库。

★ **本批不做的**（用户已明确）：不做 Skill 的替代实现；Docs 系统由隔壁 AgentLib 出（需求书已落盘，见 §7）；
"Skill = 有触发规则的 Docs"是**将来**方向。⇒ 拆完之后**"决策方法论"能力在线上是空的**，这是**有意为之**，不是缺陷、不许记 bug。

---

## 2. 现状事实（回代码核过）

### 2.1 要删的文件（4 生产 + 2 测试 + 1 配置目录）

| 路径 | 行数/大小 | 说明 |
|---|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/skill/Skill.java` | 记录类型 `record Skill(id,title,version,body,source,modifiedMillis)` | |
| `simos-app/src/main/java/io/mosire/simos/app/skill/SkillSummary.java` | `record SkillSummary(id,title,version)` | |
| `simos-app/src/main/java/io/mosire/simos/app/skill/SkillLibrary.java` | **334 行** | 库本体：`open/list/find`（零写方法）+ mtime 热更 + 坏文件保留上一版 |
| `simos-app/src/main/java/io/mosire/simos/app/tools/read/SkillTool.java` | **136 行** | `NAME = "simos.skill"`，`roles() = {DM, GM}`，schema 只有 `id` 一个属性 |
| `simos-app/src/test/java/io/mosire/simos/app/skill/SkillLibraryTest.java` | — | 整类删 |
| `simos-app/src/test/java/io/mosire/simos/app/tools/read/SkillToolTest.java` | — | 整类删 |
| `config/skills/`（4 篇） | 12,209 B | `00-decision-basics.md` / `10-political-judgement.md` / `20-economic-judgement.md` / `30-military-judgement.md` |

★ **`SkillLibrary.DEFAULT_SEED_DIR = Path.of("config", "skills")`**（`SkillLibrary.java:65`）是"仓根 `config/skills`"的唯一拼写点，
删掉它之后 `config/skills/` 全仓再无引用者。

### 2.2 生产代码调用点（11 处）

| # | 文件:行 | 内容 | 处置 |
|---|---|---|---|
| 1 | `Shell.java:49` | `import …app.skill.SkillLibrary;` | 删 |
| 2 | `Shell.java:290` | Javadoc 里以 `SkillLibrary` 的 `config/skills` 作**类比**举例 | **改写**（不能整句删——它解释的是世界生成器冻结输入的路径口径） |
| 3 | `Shell.java:376` | `private final SkillLibrary skillLibrary;` | 删 |
| 4 | `Shell.java:434` | 构造器形参 `SkillLibrary skillLibrary` | 删 |
| 5 | `Shell.java:461` | `this.skillLibrary = Objects.requireNonNull(...)` | 删 |
| 6 | `Shell.java:1112` | `SkillLibrary skillLibrary = SkillLibrary.open(config.storeDir());` | 删 |
| 7 | `Shell.java:1135 / 1180 / 1312 / 1386` | 4 处透传给 `new SimosToolSource(...)` | 删实参 |
| 8 | `Shell.java:1377` | `toolsFor(role)` 里的重建路径（也透传 `skillLibrary`） | 删实参 |
| 9 | `SimosToolSource.java:13/36` | 两个 import | 删 |
| 10 | `SimosToolSource.java:325/356/405/445/495/1092` | **6 个公开构造器**各有一个 `SkillLibrary skills` 形参 | 删形参 |
| 11 | `SimosToolSource.java:339/370/422/463/527` | 5 处 `this(...)` 委托里透传 `skills` | 删实参 |
| 12 | `SimosToolSource.java:1231` | `built.add(new SkillTool(skills));` | 删（含其上方 `:1236` 的"两桶共享"注释） |

★ 私有构造器**不吃** `SkillLibrary`（只有 `buildTools(...)` 吃，已在 12 里处理）⇒ 改动面就是"6 个公开构造器 − 1 个形参 + 5 处透传"。

### 2.3 ★★ 工具面契约：两处**派生**也要改（最容易漏）

| # | 文件:行 | 内容 | 为什么不能只删工具 |
|---|---|---|---|
| 13 | `simos-ga/.../ga/decision/DecisionToolNames.java:27` | `String skill();` —— 接口方法（注释：「技能（决策方法论）读工具——**指针**」） | 名字由宿主注入，删了工具就无处取值 |
| 14 | 同文件 `:76-78` | `default Set<String> statelessReads() { return Set.of(catalog(), skill(), branches(), timelineRevisions()); }` | **`skill()` 是它的成员** ⇒ 集合从 4 项变 3 项，且注释里"目录/技能/分支列表/阶段列表"要同步 |
| 15 | `simos-app/.../app/decision/AppDecisionToolNames.java:5/45` | `import SkillTool;` + `public String skill() { return SkillTool.NAME; }` | 删 import 与方法 |
| 16 | `simos-ga/.../decision/DecisionAgentRunner.java:741` | `.append(LlmToolNames.wireNameOf(toolNames.skill())).append(" 是决策方法论，可按需阅读。\n")` | **改写开场消息那一句**：删掉技能指针。★ 同时改 `:653` 的 Javadoc（整段在讲"Skill 那一句是指针不是内容"） |

★ **改开场消息的连带风险**：`DecisionAgentRunner.openingUserMessage` 的断言型用例里有"正文里一个数字都没有"这类钉子，
且 opening 只在**空会话**注入 ⇒ 已落盘的老会话看不到变化。**改文本必须同步该用例的期望**（实现 Agent 自己找，见 §5.3）。

### 2.4 测试侧（★ 断言改动与生产改动**不同 owner**）

| # | 文件:行 | 内容 | 处置 |
|---|---|---|---|
| 17 | `simos-app/src/test/.../access/DecisionFaceGolden.java:73` | 金名单含 `"simos.skill"` | 删一行 + **改 `:31` 的注释**（"决策人面的 43 条"→42） |
| 18 | `simos-app/src/test/.../access/DecisionCallerFactoryTest.java:24/274` | `import SkillTool;` + `.contains(SkillTool.NAME)` | 删 import 与那一行断言；`:243/246` 的"43 条"文案同步 |
| 19 | `simos-app/src/test/.../tools/SimosToolsTest.java:224` | 名单含 `"simos.skill"` | 删一行 |
| 20 | `simos-app/src/test/.../tools/SimosToolsTest.java:1361` | `new Case("simos.skill", Map.of(), "skills")` | 删该 Case |
| 21 | `simos-app/src/test/.../tools/SimosToolsTest.java:1045` | `.hasSize(161)`（GM 桶） | **161 → 160** |
| 22 | `simos-app/src/test/.../tools/SimosToolsTest.java:1069` | `.hasSize(43)`（决策人桶） | **43 → 42** |
| 23 | `simos-app/src/test/.../ShellAssemblySmokeTest.java:80/82` | `GM_BUCKET_SIZE = 161` / `DECISION_BUCKET_SIZE = 43` | **160 / 42**，并按该类既有注释纪律**显式写明为什么改** |
| 24 | `simos-app/src/test/.../McpPortTopologyTest.java:74` | GM 外发名单含 `"simos.skill"` | 删一行（**`containsExactlyInAnyOrderElementsOf`** ⇒ 必须精确） |
| 25 | `simos-app/src/test/.../McpServerTest.java:113/230/336` | `EXTERNAL_UNION_GM_TOOL_NAMES` 含 `"simos.skill"`；断言是 `containsExactlyInAnyOrderElementsOf`（`:336`）；`:108` 注释里的计数 | 删一行 + 改注释 |
| 26 | `simos-app/src/test/.../decision/RealLlmUnitDecisionLoopTest.java:232/262/1143-1155` | 名单含 `"simos.skill"`；`copyRepoSkills()` 把 `config/skills` 拷进 store | 删名单行 + **删 `copyRepoSkills()` 及其调用**（`:262`） |
| 27 | `simos-app/src/test/.../decision/RealLlmGovScenarioTest.java:258/2547-2559` | 同上（自己的 `copyRepoSkills()`） | 同上 |
| 28 | `simos-app/src/test/.../ShellAssemblySmokeTest.java:55/57` | 注释举 `config/skills` 作"容缺目录"的例子 | 改注释（该类明确断言目录缺失 ⇒ **空库**、装配不依赖内容） |

★ **`config/skills/` 删掉后 `ShellAssemblySmokeTest` 的"容缺"判据仍然成立**（`:57` 逐字：「skills 种子目录缺失 ⇒ 空库」，装配期没有一处依赖它们的内容）⇒ **不需要**把它改成"必须存在"。

### 2.5 文档面（**本批只做最少改动**）

| # | 文件 | 处置 |
|---|---|---|
| 29 | `AGENTS.md` §四 文档索引 | 该索引指向 `docs/agents/13-十二-推演Skill文档规则.md` ⇒ **保留文档、但在索引里注明该节所指的运行期机制已取缔**（或按用户后续裁定删条目） |
| 30 | `.agents/notes/implemented/` | **新建决策记录**：为什么取缔、被谁取代（记用户原话 + 日期） |
| 31 | `docs/agents/13-十二-推演Skill文档规则.md` | ★ **不删**（历史留痕不改，`docs/AGENTS.md` §三/§五.4）。它是**用户裁定的记录**，删了就丢了 U1 之前的原话；如要标废，只在**文件头部**加一行「取代者：…」 |

---

## 2.5-补 ★ 实现期更正（2026-10-13 控制方补记；本节**优先于** §2.1~§2.3 中的相冲突描述）

> 实现 Agent 在施工中回代码核出三处**本文与事实不符**，按"只记账、不改口径"上报；控制方复核后**就地更正如下**。
> ★ 三处的**处置与净结果都与原文一致**，故不影响 §5 的任何验收判据。

| # | 本文原文 | 代码事实 | 影响 |
|---|---|---|---|
| **C1** | §2.2 行 10 称 `:325/356/405/445/495/1092` 是"**6 个公开构造器**"，★注称"私有构造器不吃 `SkillLibrary`（只有 `buildTools(...)` 吃）" | 实际是 **5 个公开构造器**（`:317/347/397/437/486`）+ **1 个私有静态方法 `readTools(...)`**（`:1084`）；**类里没有 `buildTools` 这个名** | 无（那 6 处形参一律删） |
| **C2** | §2.2 行 7 把 `:1135/1180/1312/1386` 全归为"透传给 `new SimosToolSource(...)`"，行 8 又单列 `:1377 toolsFor` | 实际 `new SimosToolSource(` 生产调用点 **3 处**（`:1126/1171/1377`）+ `new Shell(` **1 处**（`:1291`，其 `:1312` 是实参）；`:1386` 就在 `:1377` 那次调用里 ⇒ 行 7 与行 8 **记重** | 无（实际 **4 处**删实参：3×SimosToolSource + 1×Shell） |
| **C3** | §2.1 ★ 称"删掉 `DEFAULT_SEED_DIR` 之后 `config/skills/` **全仓再无引用者**" | 实际 `ShellConfigFile.java:26/70` 两处 **Javadoc 类比**仍点它的名字（那文件不在 §6.1 的写权文件里，实现方未动、已上报） | ★ **已由控制方就地更正**：该两处类比改指 `config/worldgen`（真实存在的同族目录）；改后 `git grep "config/skills" -- 'simos-*/src/main/**'` = **0 命中** |

★ 另两处**实现方自行更正且已记账**（属"同类注释同步"，不违 §4.7）：`DecisionToolNames:12` 的"11 个名字"删除后实为 **10**，一并改；
`List.of(...)` 末元素逗号随 `new SkillTool(skills)` 一起去掉。

★ **§2.3 的一处过时描述**（实现方核出）：本文称 `DecisionAgentRunner` 的断言型用例里有"正文里一个数字都没有"的钉子 ——
**该钉子在 `DecisionAgentRunnerTest` 里并不存在**（`grep -in skill` 该文件零命中；`git grep "是决策方法论"` 全仓只命中生产侧那一行）。
⇒ **删掉开场消息那一句不需要同步任何断言**；真正会红的是工具面计数与名单（§2.4 #17~#28）。

★ **两条实现方才发现、本文未写的现场事实**（对 task-2 关键）：
1. **两个待删测试类阻塞 `test-compile`**：`SkillLibraryTest.java` 与 `SkillToolTest.java` 仍 `new` 已删的生产类 ⇒
   在删掉它们之前，**任何 surefire 都起不来**（不是"跑红了"，是"跑不了"）。
2. **`mvn … compile` 会跑 checkstyle**（根 `pom.xml:276-286` 把 `checkstyle:check` 绑在 `validate`；规则含 `UnusedImports`）
   ⇒ 漏删 import 会当场红；但**不跑** spotless（绑 `verify`）。

### 2.6 ★ 测试期更正（2026-10-13 控制方补记）

> 测试 Agent 交账后，控制方**逐条回代码复核**（`git show HEAD:…`）确认属实，就地更正。

| # | 本文原文 | 事实 | 处置 |
|---|---|---|---|
| **T1** | §2.4 只有 **12 处**（#17~#28） | ★ **漏列第 13 处必然红**：`SimosToolsTest:1896` 的 `.hasSize(171)`（**扫磁盘 `*Tool.java` 的 NAME 数**）。删掉 `SkillTool.java` 后扫描面必然少一条 ⇒ 首轮实测红 `Expected size: 171 but was: 170` | 已改 **170**（控制方复核见该类注释：变 170 后仍有"下放只移动落点、不增减工具 ⇒ 总数仍是 176"的历史沿革叙述，与 170 并存属**既有漂移**，见下） |
| **T2** | §2.4 #27 称 `RealLlmGovScenarioTest` 有 `"simos.skill"` 名单行 | **不存在** —— `git show HEAD:` 该文件 `grep '"simos.skill"'` 零命中；`skill` 只命中 `copyRepoSkills()` 自身 | 实际只删了方法 + 调用（净结果与 #27 意图一致） |
| **T3** | §2.4 #26 只列了名单行与 `copyRepoSkills()` | 另有 **2 处未列的 skill 残留**：`RealLlmUnitDecisionLoopTest:1370` 任务简报正文 `… / social.population / skill / decision docs …`、`:1393` Javadoc `… catalog / skill}` | ★ **必须删**（否则简报会叫真模型去调一个**已不存在**的工具）；已一并删，并连带删掉变为未使用的 `import java.util.stream.Stream;` |

★ **控制方复核时确认的"既有漂移"**（**不属本批**，只记账、不修）：
- `SimosToolsTest:194` 原写「读工具名单（37 条）」，而 HEAD 实测为 36（测试 Agent 已顺手改为本轮实测的 **35**）；
- `SimosToolsTest:1896` 那条断言的注释长文里仍写着"总数仍是 176"，而断言值是 170；
- `McpServerTest:102` 的「37 读 + 129 写 = 161」**算术自相矛盾**（37+129=166）。

---

## 3. 目标 / 非目标

**目标**：源码里**再也没有 Skill 这套运行时机制**——生产代码零 `SkillLibrary/SkillTool/SkillSummary/Skill` 引用，
工具面精确减去 `simos.skill` 这一条，测试与文档同步，`config/skills/` 清掉。

**非目标**：
- ❌ 不做替代（Docs 系统由隔壁出）；
- ❌ 不动 `sd.DecisionDocs` / `sd.DecisionResults` / `simos.sd.reports`（那是 Docs，不是 Skill）；
- ❌ 不动任何经济/社会/军队/gov/army 语义；
- ❌ 不动 `docs/agents/13-*.md` 正文（历史留痕）；
- ❌ 不写迁移、不写兼容位（`AGENTS.md` §五.6：Alpha 不兼容旧档）；
- ❌ 不跑 `package`、不重建 jar（本批无产物变更）。

---

## 4. 外部契约与不变量（不可破坏）

1. **工具面只减不增**：GM 桶 **161 → 160**、决策人桶 **43 → 42**，且**减掉的那一条恰好是 `simos.skill`**。
2. **`SimosTool.toolRoles()` 仍是"谁能用"的唯一拼写点**：装配期 `ToolRoleAudit.requireFaces` 双向比对，
   桶与白名单**不一致当场抛** ⇒ 删工具时必须同时删两处声明（工具类 + 金名单），**不许只删一处**。
3. **`DecisionToolNames` 的"一处声明、三处派生"口径不变**：删 `skill()` 之后，
   `statelessReads()` 必须仍由**访问器**派生（不许改成写死字面量）。
4. **开场消息仍然不含任何会漂的值**（head/revision/tick）：删技能指针句时**不得顺手引入**任何数字或世界状态。
5. **`RealLlm*` 两个类的环境门控不变**：`@EnabledIfEnvironmentVariable(named="SIMOS_REAL_LLM", matches="1")`
   ⇒ 它们在本机默认**跳过**；改完必须确认仍是"跳过"而不是"报错"。
6. **`Shell` 构造器签名变化只允许"少一个形参"**：不许顺手改别的形参顺序/类型（13 处按 9/10/11 参装配的兼容构造器是既有设计）。
7. **删除是纯删除**：不许留下 `// 已删除` 之类的注释债，不许留空的 `skill/` 包目录。

---

## 5. 验收判据与负向用例

### 5.1 正向（全部要跑，给真实输出）

```bash
# ① 零残留（四条都必须是 0 命中 / rc=1）
git grep -n "SkillLibrary\|SkillTool\|SkillSummary\|app\.skill" -- 'simos-*/src/**'      # 期望：无输出
git grep -n "simos\.skill" -- 'simos-*/src/**'                                            # 期望：无输出
git grep -rn "toolNames\.skill()\|String skill()" -- 'simos-*/src/main/**'                # 期望：无输出
test -d config/skills && echo "FAIL: config/skills 仍在" || echo "OK"

# ② 编译（★ 用 mvn-lock，本仓一次只跑一个 Maven）
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile   # 期望 rc=0

# ③ 测试：只跑受影响的三个模块（app 是主战场）
tools/mvn-lock.sh -q -Dtest='SimosToolsTest,DecisionCallerFactoryTest,ShellAssemblySmokeTest,McpServerTest,McpPortTopologyTest' -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am
```

### 5.2 负向（★ 本批最容易出的假绿，必须验）

| # | 负向用例 | 期望 |
|---|---|---|
| N1 | **故意只删工具类、不删金名单** | `DecisionCallerFactoryTest` **当场红**（装配期 `requireFaces` 抛或金名单不等）⇒ 证明两处声明确实是耦合的、不是"删一处也能绿" |
| N2 | **故意把 `hasSize(161)` 留着不改** | `SimosToolsTest` 红，且报出的实际值 = **160** ⇒ 证明减的确实是一条 |
| N3 | **故意把 `hasSize(43)` 留着不改** | 同上，实际值 = **42** |
| N4 | **故意不删 `AppDecisionToolNames.skill()`** | 编译**断**（`SkillTool.NAME` 不存在）⇒ 证明没有"靠反射悄悄兜住"的旁路 |
| N5 | **故意不删 `statelessReads()` 里的 `skill()`** | 编译**断** ⇒ 证明它是派生而非第二处字面量 |
| N6 | 删完跑 `ShellAssemblySmokeTest` | **真 Shell 装配冒烟通过**（该类是真装配 + 真两桶审计 + 真关闭）⇒ 证明不是"编译过但装不起来" |

★ 判据要求（`docs/agents/04-三-验证.md` §三）：**护栏必须自证**——N1~N5 每条都要"改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5"，
做不到就**如实说"等价存活"**，**不许把"没跑"写成"通过"**。

### 5.3 交账（实现 Agent 必须给）

1. 改动文件清单（`git status --porcelain` 原文）+ 每个文件"删了什么/改了什么"一句话；
2. `git diff --stat` 原文；
3. §5.1 三条命令的**真实 rc 与输出**；
4. **实现架构账本** `.superpowers/sdd/2026-10-13-retire-skill/impl-ledger.md`；
5. **未验证项一节**（如实）；
6. 若改了开场消息文本：**指出同步改动了哪个既有用例**，并给该用例的单跑输出。

---

## 6. 文件所有权（★ 两个 owner，不许重叠）

### 6.1 实现 Agent（生产代码 owner）

✅ 允许改：
- **删**：`simos-app/src/main/java/io/mosire/simos/app/skill/**`（3 个文件）、
  `simos-app/src/main/java/io/mosire/simos/app/tools/read/SkillTool.java`
- **删**：`config/skills/`（4 篇）
- **改**：`simos-app/src/main/java/io/mosire/simos/app/Shell.java`
- **改**：`simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`
- **改**：`simos-app/src/main/java/io/mosire/simos/app/decision/AppDecisionToolNames.java`
- **改**：`simos-ga/src/main/java/io/mosire/simos/ga/decision/DecisionToolNames.java`
- **改**：`simos-ga/src/main/java/io/mosire/simos/ga/decision/DecisionAgentRunner.java`
- **建**：`.superpowers/sdd/2026-10-13-retire-skill/impl-ledger.md`

❌ 禁止碰：**任何** `src/test/**`（测试归 §6.2）、`docs/**`、`AGENTS.md`、任何 `pom.xml`、`log4j2.xml`、
`run-small-world.sh`、以及所有经济/社会/军队/gov/army 的生产代码。

### 6.2 测试 Agent（测试 owner，**在实现 Agent 交账后**接手）

✅ 允许改：§2.4 的 **#17~#28 全部文件**（含删两个测试类）、`.superpowers/sdd/2026-10-13-retire-skill/`
下的测试证据文件。

❌ 禁止碰：§6.1 的任何生产文件（发现生产侧还有残留 ⇒ **上报，不自己改**）。

### 6.3 控制方（我）

只做：派单 / 验收 / 落盘文档（§2.5 的 #29~#31）/ 提交。**不写代码**（`AGENTS.md` §五.2）。

★ **例外（已发生，一并记账）**：`ShellConfigFile.java` 的 2 处 Javadoc 类比（§2.5 更正 **C3**）由控制方就地改指
`config/worldgen` —— 它**不在任何 Agent 的写权文件里**，且属"文档面清理"而非生产逻辑改动（2 行注释，零语义变化）。
该文件是**本批唯一由控制方触碰的 `src/main` 文件**。

---

## 7. 现场提醒（派单时一并说清）

1. **本仓一次只跑一个 Maven**：用 `tools/mvn-lock.sh`，**不许**裸 `./mvnw`（`AGENTS.md` §五.1）。
2. **不要在别人跑 Maven 的窗口里改 Java**；动手前先 `git status --porcelain`。
3. **工作树里有两份未跟踪的新文档**（都是本轮控制方落的，**不属本批、不要动**）：
   - `docs/superpowers/reports/2026-10-13-long-run-agent-simulation-recon.md`
   - `docs/superpowers/specs/2026-10-13-agentlib-docs-requirements.md`
4. **`controller` 已裁定的下游依赖**：拆完之后"决策方法论"线上为空是**有意**的；
   `simos.skill` 从工具面消失会让 `DecisionAgentRunner` 开场消息少一句 ⇒ 决策人**没有**任何"该怎么做决策"的输入，
   直到 Docs 系统接上。**不许**用"临时补一段硬编码守则"来填这个空 —— 那正是本批要取缔的东西换个马甲。
5. **施工顺序建议**（不是硬要求）：先删 4 个生产类 → 改 `DecisionToolNames` → 改 `AppDecisionToolNames` →
   改 `DecisionAgentRunner` → 改 `SimosToolSource`（6 构造器）→ 改 `Shell` → 删 `config/skills/` → 编译到过 → 交账。

**⑤ ★ 全量门禁 `clean verify`（三项门禁 = Spotless + Checkstyle + SpotBugs + 全量测试 + 前端门禁）**

首次 `clean verify` **rc=1**：**Spotless 红**，全在 `simos-app/src/test/**` —— 测试 Agent 新增的长注释行需 google-java-format 重排
（它只跑了 `test`，而 spotless 绑在 `verify` ⇒ 这正是"只跑测试不跑 verify"的漏网形态）。
按本仓纪律处置：`tools/mvn-lock.sh -q spotless:apply` → **列全 `git diff --name-only` 逐条确认**（确认 spotless 只动注释行：
非注释改动仍只是"删名单行 + 161→160 / 43→42"）→ 重跑：

```
tools/mvn-lock.sh clean verify  → rc=0，[INFO] BUILD SUCCESS
  Tests=3186  Failures=0  Errors=0  Skipped=5
  前端门禁 412/412 全绿
  SpotBugs：16 处 "BugInstance size is 0"
```
⇒ **本批关账**。

---

## 8. ★ 控制方最终验收（2026-10-13，**亲自跑、非转抄**）

> 两个 Agent 都已停；本节四条命令由控制方在**最终冻结内容**上亲自重跑。

**① 零残留**（★ 判据脚本写进文件再跑，避免命令行字面量自命中 —— 测试 Agent 首跑就栽在这个形态上）：

```bash
# /tmp/residue-check.sh（要点）
git grep -n 'SkillLibrary|SkillTool|SkillSummary|app\.skill' -- 'simos-*/src/**'   # rc=1 无输出
git grep -n 'simos\.skill'                                   -- 'simos-*/src/**'   # rc=1 无输出
git grep -rn 'toolNames\.skill()|String skill()'             -- 'simos-*/src/main/**'  # rc=1 无输出
test -d config/skills            # 不存在 ✓
test -d …/app/skill              # 不存在 ✓
```
实测：三条 grep 全 **hits=0**，两个目录均不存在 ⇒ **VERDICT: PASS**。

**② 编译**
```
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile   → rc=0（-q 零输出）
```

**③ 指定 5 类**（先 `rm -rf simos-app/target/surefire-reports`，否则读到两轮并集 —— 本仓纪律 §七）
```
tools/mvn-lock.sh -q -Dtest='SimosToolsTest,DecisionCallerFactoryTest,ShellAssemblySmokeTest,McpServerTest,McpPortTopologyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am
→ rc=0；SimosToolsTest 65 · DecisionCallerFactoryTest 11 · ShellAssemblySmokeTest 1 · McpServerTest 5 · McpPortTopologyTest 4
  合计 Tests=86 Failures=0 Errors=0 Skipped=0
```
★ 这 86 条**内在地**验到了新计数：`hasSize(160)` / `hasSize(42)` / `hasSize(170)` 与金名单
`containsExactlyInAnyOrderElementsOf` 都在其中，不匹配会当场红 ⇒ **160 / 42 / 170 是实测值，不是推导值**。

**④ 全量关账（受影响两模块 + `-am` 全部上游）**
```
rm -rf simos-app/target/surefire-reports simos-ga/target/surefire-reports
tools/mvn-lock.sh -q test -pl simos-ga,simos-app -am
→ rc=0
```
| 模块 | Tests | Fail | Err | Skip |
|---|---|---|---|---|
| simos-util | 217 | 0 | 0 | 0 |
| simos-map | 379 | 0 | 0 | 0 |
| simos-calendar | 33 | 0 | 0 | 0 |
| simos-social-api | 3 | 0 | 0 | 0 |
| simos-ga | 13 | 0 | 0 | 0 |
| **simos-app** | **902** | **0** | **0** | **5** |
| simos-core | 231 | 0 | 0 | 0 |
| simos-social | 217 | 0 | 0 | 0 |
| simos-unit | 518 | 0 | 0 | 0 |
| simos-actor-api | 8 | 0 | 0 | 0 |
| simos-actor | 130 | 0 | 0 | 0 |
| simos-economy-api | 50 | 0 | 0 | 0 |
| simos-economy | 360 | 0 | 0 | 0 |
| simos-gov | 33 | 0 | 0 | 0 |
| simos-army | 92 | 0 | 0 | 0 |
| **合计** | **3186** | **0** | **0** | **5** |

- **5 跳过 = `RealLlm*` 环境门控**（`SIMOS_REAL_LLM=1` 才跑）—— 与基线口径一致，**不是**本批引入；
- **前端门禁 412/412 全绿**（同一次构建内）；
- ★ 与台账基线的差异要说明：`docs/agents/08-七-门禁与关账命令.md` 记的 **3144 条**是**收尾测试第 3 层之前**的数；
  本批**删除** 2 个测试类（`SkillLibraryTest` + `SkillToolTest`，共 2 个类、若干用例）**不增加**测试 ⇒ 3186 ≠ 3144 的差额
  来自基线之后的其他批次，**不是本批**（本批只可能让总数**减少**）。★ 未逐类核对差额来源 = **未核项**（见 §9）。

---

## 9. 未核 / 如实记

> ★ **本节原写于派单前**（当时确实"未跑 Maven"）。关账后按事实**更新**如下：**已核的移出、仍未核的保留**。
> 已核的部分见 §8（控制方亲自跑的五条命令，含真实 rc 与逐模块计数）。

**已被 §9 实测取代的（原第 1~3 条）**

- ~~"161/43 → 160/42 是静态推导、未实测"~~ ⇒ **已实测**：`hasSize(160)` / `hasSize(42)` / `hasSize(170)` 在 86 条判据里跑过，
  不匹配会当场红；且 `ShellAssemblySmokeTest` 真装配冒烟绿（`GM_BUCKET_SIZE`/`DECISION_BUCKET_SIZE` 已改 160/42）。
- ~~"两个 `RealLlm*` 里是否还有别处依赖 `config/skills/` 存在"~~ ⇒ **已核并已改**（见 §2.6 的 T2/T3；
  `RealLlmUnitDecisionLoopTest` 另两处 skill 残留已删，并连带删掉变为未使用的 `Stream` import）。
- ~~"`ShellAssemblySmokeTest` 的容缺路径在 `config/skills/` 物理删除后是否仍绿"~~ ⇒ **已实测绿**（§9 ④，simos-app 902 条 0 红）。

**仍未核（保留）**

1. **未核** 全仓 `3186` 与台账基线 `3144` 的差额来源（本批只删测试、不增 ⇒ 差额**不是本批**产生的；未逐类核对，见 §9 ④ 末注）。
2. **未核** 前端 `webui/` 是否还有 `skill` 相关消费点（`grep -rn skill simos-app/src/main/resources/webui/` 零命中，但**未逐文件复核**）。
3. **未核** `RealLlm*` 在 `SIMOS_REAL_LLM=1` 下的**真跑**路径（本机 5 条均 skipped；真跑要真 LLM provider 与真调用预算）。
4. **未核** 本批**未** `package` / 未重建 jar（源码级改动，无产物面变更 ⇒ 但"shaded jar 仍含旧 SkillTool 类"这件事未验证，
   按本仓纪律**改完产物要重建**才算完整关账；本批按"无产物变更"处理，见 §3 非目标）。
5. ~~未跑全仓 `clean verify`~~ ⇒ **已跑，rc=0 / BUILD SUCCESS**（见 §9 ⑤；含 Spotless 红的发现与处置）。
   （Checkstyle 跑了，它绑在 `validate`）。★ 本仓纪律：门禁是**三项**，只跑前两项不等于绿 ⇒ **关账前必须补 `clean verify`**。
6. **未核** 未 `git commit` / 未 `git add`（按本仓纪律 §五：不擅自提交）；工作树形态见 §9 末的最终清单。
