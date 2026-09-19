# CLAUDE.md — SimulatorMosire 工作须知

> 本文件是**项目的常驻上下文**：任何会话、任何机器打开这个仓库，先读这里。
> 设计细节不在这里重复，只写"必须先知道的约束"和"该去哪读"。

## 这是什么

SimulatorMosire（简称 **simos**）是 GSimulator 的重构：把原本单一功能的地图推演工具，
解放为**可分模块生长**的模拟引擎。参考项目 `~/DevMosire/GSimulator`（**只作参考，不作依赖**）
与 `~/ProjectMosire/AgentLibMosire`（CoreSimos 整包依赖）。

## 五条铁律（不可协商）

1. **所有查询最终解析为稳定实体。** 地址是定位方式，ID 是身份。单位调动、区域改名，
   历史与 Info 都不断。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
4. **Core 只负责组合与调度**，不重新实现领域逻辑。
5. **变更集从完整状态类型派生，且有往返不变式测试守卫。**
   `apply(changeSet, base)` 必须逐字段重建出 target。

> 铁律 5 的由来：GSimulator 的 `MapDiff` 是**手工对着 `MapData` 维护**的，`MapData` 加字段时
> 没人提醒要跟着加。四个字段漂移出去（`terrainBlocks`/`terrainTypes`/`pathwayGroups`/`edges`），
> 既无编译期也无测试期护栏，导致**对非 root 节点写连通性会静默丢失**。
> 这是本项目最贵的教训。

## 模块结构与依赖硬约束

```
UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos  →  ShellSimos(app)
```

| 模块 | artifactId | 允许依赖 |
|---|---|---|
| UtilSimos | `simos-util` | **仅** Jackson（databind + datatype-jdk8）+ SLF4J。不依赖 AgentLibMosire，不依赖任何 simos 模块，**不碰文件系统**。★ jdk8 模块是 2026-09-18 M4 Task 3 裁定的：`Optional` 在快照树里且处于**嵌套泛型位置**（`SegmentedSeries<Optional<…>>`、`SimosTimestamp.calendarLabel`），裸 databind 会把它写成 `{"present":…}` 并丢值。⇒ 本条描述的目标是 **spec §二 / §〇.3**；`simos-util/pom.xml` 只有 `bannedDependencies` **黑名单**（无 `includes`），加 Jackson 家族构件**不会**触发 enforcer |
| MapSimos | `simos-map` | `simos-util`。**永不** import social/unit/agentlib。**不做任何存储** |
| SocialSimos | `simos-social` | `simos-util` + `simos-map`。**不依赖 UnitSimos** |
| UnitSimos | `simos-unit` | `simos-util` + `simos-map`。**不依赖 SocialSimos** |
| CoreSimos | `simos-core` | **main scope**：`simos-util` + `agentlib-mosire` + `jackson-databind` + `sqlite-jdbc`（M4 关账终态；日志实现只进 test scope、**MCP SDK 归 `simos-app`**——M5 已结账，见 spec §〇.3.1）。map/social/unit **退到 test scope** |
| **ShellSimos（app 层）** | `simos-app` | **M5 新增（第七模块）**。main scope：`simos-util`+`map`+`social`+`unit`+`core`+`agentlib-mosire`+`mcp-core`+`mcp-json-jackson2`+`jackson-databind`+日志实现。**不设 enforcer**——它是**组合根**，按 `/map` `/social` `/unit` 路由 ⇒ 天然认识各模块（ADR-1 §九 记账的"M5 的账"在此结清）：`Shell`/`ShellConfig`/`ShellMain`、`gui/`(5711)、`query/`、`tools/`(3 写+9 读)、`binding/`、`demo/` |

> ★ **CoreSimos 的 main scope 不依赖领域模块**（ADR-1，2026-09-18）。这是**铁律 4 的结构化**——
> Core 编译期看不见任何领域类型，**想重新实现领域逻辑也无从下手**。由 `simos-core` 自己的
> `bannedDependencies` 在构建期强制。具体模块的装配归 **app 层**（M5 的 GUI / MCP）。
> 连带后果：**命令跨边界是不透明载荷**（Core 只认信封的 `type` 字符串，不 `instanceof`、不 switch 类型），
> 见 ADR-1 §七。新增契约放 `io.mosire.simos.util.spi`，**既有契约原地不动**。
>
> 这些边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**——越界 = 构建失败，
> 不是 code review 的事。

### 跨模块可见性走 Facet，不走反向依赖

"某个 hex 上有哪些单位"**不能**写成 `MapManager.getUnitsAt(hex)`。Util 提供 Facet 协议，
各领域模块自己注册提供者，`MapSimos` 对这些扩展完全不知情。

## 设计文档在哪

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-16-simos-master-design.md` | **总纲**：五模块边界、八大件原语、两层地址、两阶段时间推进、存储分层、里程碑。已获用户批准 |
| `docs/superpowers/plans/2026-09-16-simos-master-plan.md` | **实现计划**：M0 可执行分解（5 任务）+ M1~M6 路线图；每阶段的推进机制见其 **§六** |
| `docs/superpowers/specs/2026-09-16-util-simos-design.md` | **M1 spec（已执行）**：UtilSimos 八大件、Address 语法、四条时间语义、往返框架。五项待决见其 §〇 |
| `docs/superpowers/plans/2026-09-16-util-simos-plan.md` | **M1 计划（已执行完毕）**：11 个任务的 bite-sized 步骤。⚠️ 其代码草图是**计划期产物**，执行期已就地校正，**spec 与 `simos-util/src` 才是权威**（分歧处均有"取代说明"） |
| `docs/superpowers/specs/2026-09-18-spi-layering-design.md` | **ADR-1（架构决策）**：修订总纲 §三 的最后一条。记「为什么**不**拆 `simos-spi`」与「Core 的 main scope 为什么要收窄」。含插件假设的评估、否掉的方案及其理由、以及尚未自证的清单 |

**注意粒度**：总纲是**总纲**，不是五份 spec 的合集。各模块的**内部设计**（M1/M2/M3 已裁决完毕；
**M4 的三项已于 2026-09-18 裁决**——时间线 DAG 存储 schema / Checkpoint 周期 / Command 类型清单，
见 `2026-09-18-spi-layering-design.md` 与即将落地的 M4 spec）——总纲 §十三 有意把它们留给各模块自己的 spec。
给 **M5/M6** 写 bite-sized 步骤前，先确认对应模块的待决项已裁决，否则等于编造设计。

**⚠️ 不要用 `@` 导入上面这些文档。** 官方语义是导入文件**在启动时展开进上下文**——导入**不省上下文**，
只会让每个会话白白载入 **5000+ 行**。上面用反引号书写路径
（反引号 = 字面量，不触发导入），需要时按需读取。只有**必须每会话都生效**的短内容才该进本文件。

## 构建与门禁

```bash
./mvnw verify          # Spotless + Checkstyle + SpotBugs + Surefire（M0 起即为硬门禁）
./mvnw -q -Dtest=<类名> test    # 迭代时只跑相关单条用例
```

- 只想跑**某一个模块**的用例时，`-pl <模块> -am` 会把 `-Dtest=` 带到 reactor 里每个模块，
  没有该用例的模块会让 surefire 先报 `No tests matching pattern`——加
  `-Dsurefire.failIfNoSpecifiedTests=false`
- **`mvn test` 不跑 SpotBugs**，关账前须单独跑 `spotbugs:check`（或直接 `verify`）
- Java **21**；Maven `[3.8,)`
- 父 POM `io.mosire:simos-parent`，**不继承** `io.mosire:mosire-parent`
- **中文 Javadoc 的折行由 google-java-format 决定**（它按字符数折，100 汉字即换行，
  手工断行处会留下接缝空格）。写注释不要手工调行宽，改完跑
  `./mvnw -q spotless:apply`；`~/ProjectMosire` 同为该形态
- 模块边界不是靠约定：`simos-util`/`map`/`social`/`unit` 各自带
  `maven-enforcer-plugin` 的 `bannedDependencies`，越界即构建失败
  ★ **`simos-core` 也设了限，原写"是集成点，不设限"是错的**（2026-09-18 核对 `pom.xml` 时改正）：
  它的 **main scope 禁 `simos-map`/`social`/`unit`**（铁律 4 的结构化，见上面 ADR-1 一段），
  领域模块**只在 test scope**（`bannedDependencies` 带 `includes`，按 scope 放行）

## 纪律

- ★★ **子代理一律用 `deepseek-flash-go`（DeepSeek **V4.1** Flash）；禁用 `deepseek-flash`（V4 Flash），也不要用 `category=` 派单**
  （用户 2026-09-19 裁定，原话「**不许用 V4Flash！**」）。派单时写 `subagent_type="deepseek-flash-go"`，**不要**写 `category="…"`
  ——后者会落到 `Sisyphus-Junior` 的默认模型（= V4 Flash）。
  **由来**：控制器在 M7 期间误用 `category=` 连派 7 个任务（T1~T7），直到 M7b 被用户当场发现；**此前 M5/M6 用的都是 `deepseek-flash-go`**。
- **绝不 `git add -A`**；提交前先扫 `git diff --cached`（本仓有 `target/`、证据日志、
  `.serena/project.local.yml`，一把梭会误扫）。**该推就推**——私有仓库，用户 2026-09-17 原话
  「你爱推就推反正是私有仓库」。
  ★ **本条原写作"不擅自推送"，那是控制器自己加的规则、用户从未说过**，却被冠以"用户裁定"写进了
  6 个文件（CLAUDE.md、总纲计划 G11、M1/M2 计划、M1 台账），并因此**一路挡着推送**。已撤。
- **迭代只跑相关单条用例**，别动辄全量测试；出 bug 再找
- **单个模块开发任务的评审不超过 3 轮**（用户裁定，2026-09-16）。数的是**该任务上以"发现问题 /
  判是否可关账"为目的的独立派发**——任务级评审、限域重审、修复轮里的复核**都算**。第 3 轮仍不收敛
  就**不许再加轮**：由控制器当场裁定，未决项记成**带裁定的遗留条目**往下走。
  代价的由来：M1 的 `ResolverRegistryTest`（188 行）在一个 11 行加的修复上跑了 6 轮，根因是控制器
  把**已确证**的发现"park 到终审"而非当场修，之后又推翻自己的裁定。
  **推论：已确证的发现，若修复比它的描述还短，在发现的那一刻修掉，不 park。**
  ★★ **2026-09-17 用户重申（这是本条的要害）**：「别他妈一个模块跑几轮十几轮评审，**这个代码没多少，
  评审用的上下文比项目大了**」。⇒ **评审的体量不得压过代码本身**：
  - **不许为评审自建重型装置**——评审包/限域重审/md5 清单/多轮取证表格，这些是 M2 Task 1 上失控的形态
    （一个 hex 包，留痕比它的代码长一个数量级）。
  - **代码量小时，控制器自己读 diff 就是评审**，不必派评审者。
  - 实现者**自带的变异自证**已经是"测试"，不要在外面再套一层评审去复现它。
  - **台账记裁定与结论，不记取证过程。**
- **护栏必须自证**：任何 enforcer 规则、格式门禁、测试不变量，都要有一个**故意违规**的用例
  证明它真的会响。没有这个的护栏等于装饰。**怎么确认它真的有效**：M1 期间反复查出**十余次**
  判别力缺陷，下列五条是归纳出的**形态**——**形态清单才是权威，数字只是它的长度**：
  1. 把被保护的那行**删掉**、跑该用例、看它是否真的红；红不了就是装饰。**跑之前先让变异体自证**——
     编一份原件作参照、比 md5，证明落盘的确实是与原件**字节不同**的那份；否则 javac 编的可能还是原件，
     于是三向全绿。**按变异文件名（而非目标类名）拷入**则会让"红"变成**编译错误**，同样不算数——
     这两个坑在 M2 Task 1 上各踩过一次，第二次还连污染两轮（"失败清单"是空的，因为压根没跑到断言）。
     **装置该怎么写**：按**白名单**把变异体推成**目标类名**；每轮先清掉工作目录里规范名之外的 `.java`；
     并**强制断言 `grep -c "COMPILATION ERROR"` 为 0**，不为 0 就当场作废这一轮。
     **装置的产物自己也会带状态**：变异轮之间 `target/classes` 里的旧 `.class` 会**活到下一轮**——M2 Task 1
     的第 2 轮重审先探一手 `round`，读到的却是**上一轮留下的**陈旧 `HexCoord.class`，于是打出一份**完全虚假**的
     发现（"`round(0.5,0.5)=(1,1)`、超界 23.7%"）。**每一轮开跑前都要把工作目录恢复成干净世界**（重编原件、比
     md5）；"基线修正记录"这类**事后补记**同理，都不能代替干净世界。
     **红了还要问"为什么红"**——红的理由必须是被保护的那行本身（M1 里末尾的 `null` 被 varargs 吸收成
     **整个数组**，调用点没改却照样编译，javac 只给警告不报错；用例确实红了，红的却是"被测行为变了"）。
     **没红也要问"为什么没红"**——空输出可能只是**根本没跑到**（`junit-platform-console --details=none`
     全通过时不打汇总行）。
     **夹具规模决定判别力**——用**冻结字面量**钉 `Map.copyOf`/`Set.copyOf` 的保序时，键太少会**假绿**：
     3 键实测 7%~40% 恰好落回插入序（30 次独立 JVM 启动；"率"是**估值**不是常量），4~6 键 0/30（M2 Task 5 实测）。
     根因：`copyOf` 走 `ImmutableCollections`，迭代序 = **散列槽位序**，两键**撞槽**时线性探测的**相对次序随插入序**
     ⇒ **不是键集的纯函数**（record 键集同一次 JVM 内实测 100/100 两次不同；String 键集 0/100 只是该 JVM 的盐下
     没撞槽，**不等于**纯）。跨 JVM 的哈希盐是**第三个**独立来源。⇒ 夹具键数**要当场量**，别凭"看起来不像巧合"。
     **同一件装置在不同目录形态下的判别力可能不同**——自建仓源扫描器按**绝对路径**判隐藏段（`startsWith(".")`），
     主树里扫到 44 个文件，在 git worktree 里（绝对路径含 `.claude`）扫到 **0** 个 ⇒ 扫描为空、断言恒真、用例全绿、
     构建成功，**没有任何症状**。2026-09-18 M4 的 R15 与 R1 各中过一次（R1 先发现并修，R15 是后补的同款）。
     ⇒ **装了护栏，要在它真正会被用到的每一种环境形态下各自证一次**（主树 / worktree / 从模块目录起跑）；
     只在主树测过等于没测。它与 ugrep、`git grep --untracked` 属**同一族**——"把没搜到伪装成不存在"，
     只不过这次的"工具"是我们自己写的。
     ★★ **同族第五例，载体是 surefire 报告（2026-09-18 M4 Task 11 实测）**：变异轮跑完，`target/surefire-reports/`
     里的 `.txt` **留着变异体的失败**（本轮实测 `CommandBusLoggingTest.txt` 写着 `Tests run: 6, Failures: 1`，
     mtime 正是那一轮），而变异装置还原的是**源文件**——`target/` 下的一切（`.class`、`surefire-reports/*.txt`）
     **一律不还原**。⇒ **读 surefire 数字必须先跑干净轮，并核对报告 mtime 落在本轮内**；不许拿"上次留下的绿"
     或"上次留下的红"当本轮结论。★ 与 M2 Task 1 那次陈旧 `HexCoord.class` 是**同一族、方向相反**：
     那次造出的是**假发现**，这次险些造出**假失败**。
     **另有一个反向的坑（同日实测）**：`grep -cE 'spotless.*(SUCCESS|SKIPPED)'` 返回 **0**，看着像"Spotless 没跑"，
     实际它跑遍了 6 个模块（日志形态是 `Spotless.Java is keeping 66 files clean`）。
     ⇒ **命中 0 先怀疑自己的正则**，别先怀疑门禁。
     ★★ **同族第六例，载体是变异装置自己的日志（2026-09-18 M4 Task 12 实测）**：变异轮的门禁把自证项
     （`orig_md5` / `mutant_md5` / 推送后 md5）**只打到终端**，**日志里一条都没有**
     （`grep -c 'mutant=' <某轮日志>` = **0**）。于是"目标文件被改过之后，早先那几轮的旧证据还成不成立"
     就只能靠**推导**（"源文件没动 ⇒ 重新生成的变异体必然逐字节相同"）——而形态 5 要的正是把"验过"与
     "推出来"分开。★ 同一次还实测到**核对脚本自己造出假红**：`was=$(grep -oE 'mutant=[0-9a-f]{32}' 日志)`
     读到**空串**，与 `now` 一比即"不一致"，7 个变异体全被判"必须重跑"——**先怀疑自己的读取，别先怀疑被测物**
     （与上面那条 Spotless 正则同型）。⇒ **装置的每份产物都要自指**：把"这一轮跑的是哪份字节（md5）"
     **追加进日志本身**（Task 12 已给 `mut-round.sh` 补上，`task-12-evidence/mutants/mut-round.sh` 的
     "装置补记"段）；核对脚本要**先断言自己读到了非空**再下结论。
  2. `requireNonNull(x, "x")` 的失败消息**恰是字段名本身**，而删掉守卫后紧接着的解引用会抛 JDK 21 的
     热心 NPE，消息**同样含该字段名**——这类守卫只有**精确匹配**（`hasMessage`）才有判别力。
  3. 判"同刻/相等"口径的用例，输入必须落在两种实现会**分叉**的地方（如带 `calendarLabel` 的时间戳：
     `equals` 分叉而 `compareTo` 不分叉），否则两种实现下断言全等价。
  4. **纯转发型 SPI**（注册表、分发器）要有一条用例证明参数被**原样转交**；**返回处的加固**
     （`List.copyOf(...)`）也要逐处自证——M1 里同一个 `FacetRegistry` 的 `facetNames()` 钉住了、`queryAll()` 漏了。
  5. **（另有同源的另一族）"我验过了"与"我记得是这样"必须分开**：写给别人当依据的每个 **Expected / 事实 / 出处**
     都要有**当场跑过的痕迹**——不写没实测过的期望输出；不把**工具的静默假阴性**当"不存在"；不把
     **推导出来的风险**当既成事实；不引用**还只活在待写文件里**的条文；不把**推导出来的"护栏边界"**当结论。
  6. **（同族的第四个实例，2026-09-18 M4 实测）「分析器的判定不是被分析文件的纯函数」**：同一份**逐字节相同**的
     `UnitCodec.java`（`git diff` 无输出），在 `c76b2b6` 的类集下 SpotBugs 报 **0**、在 `684c757` 的类集下报 **2**
     ——触发点是模块内**首次出现对兄弟模块 `Snapshot` 实现的引用**（旧树 + 一段 14 行探针即复现；内部机理未证）。
     ⇒ **`BugInstance size is 0` 只在它跑过的那个类集下成立**，不能当"这个文件干净"的证据（`simos-map`/
     `simos-social` 当时的 0 同样是假阴性，故一并按同型预防修掉，**不是"门禁抓到 3 个"**）。
     **配套实测**：`./mvnw -pl <mod> spotbugs:check` **直调不跑生命周期、不编译**——在没编译过的树里
     rc=0 / 日志 0 行 / 无任何提示地通过。⇒ **要跑门禁就跑 `verify`，不直调单点 goal**；看到"绿"先问它**分析了几个类**。
  7. **（同族的第五个实例，2026-09-18 M4 Task 12 实测）「分析器的判定不是被分析语句的纯函数」**：`EI_EXPOSE_REP`
     只认**构造函数体内直接可见**的包装调用，**不做跨过程分析**。同一份语义：包装收进**辅助方法** ⇒ 该 record 的
     **两个 accessor 各报一条** `may expose internal representation`（都指到构造器那一行）；包装写进**构造器体** ⇒ 0。
     ⚠️ 探针**同时换了两个因素**（① `Collections.unmodifiableList` 在辅助方法里 ⇒ 2；② `List.copyOf` 在构造器体里 ⇒ 0），
     ⇒ **不能**据它断定"同一个 API 放进构造器体也判 0"，也**不能**断定"换个 API 放在辅助方法里就报"——那两格**没测过**（形态 5）。
     **⇒ 能用的推论只有一条：把公共辅助方法抽成"拷贝 + 逐元素校验"可以，包装那一层必须留在调用点。**
     ★ 同模块的 `WorldChangeSet` 一直是"包装写在构造器体里"，故它从未报过——**同形不必然同判，别照抄形状**。
  8. **（同族第七例，2026-09-19 M7 T7 实测）「工具的输入方式会静默改变被测对象」**：Playwright 的 `page.fill`
     **会把页面滚下去** ⇒ `#canvas` 的 `boundingBox().y` 变成**负值** ⇒ `page.mouse.click(x, y)` 落在**视口之外**、
     **点击静默无效**（M7 T7 的 b5 首次即中：点 (1,1) 没选中 u-3，而**没有任何报错**）。修法：点击前
     `scrollIntoViewIfNeeded()`；更一般的纪律是**点击后必须断言"它真的发生了"**（选中态/状态文案变了），
     而不是假定"发了 click 就等于点到了"。★ 与 `page.fill` 同族的还有 **`tree-check.cjs` 这类自建装置
     "不在 CI 里"**：装了护栏却不跑 = 装饰（M7 的系统性开口项）。**它仍属"把没发生伪装成没发生"这一族。**
- **密钥纪律**：值绝不进日志/异常/事件/argv/env/stdio；读配置只打印路径 + 长度
- 注释与文档用中文，与既有风格一致

## 当前状态（2026-09-19）

| 项 | 状态 |
|---|---|
| 总纲 spec | ✅ 已批准、已提交 |
| 实现计划 | ✅ 已落（`2610229` + 本机化修正）；阶段推进机制见其 **§六**。分支推送状态见该计划 §二 M1 的关账记录 |
| M0 | ✅ 已完成（5/5，2026-09-16 本会话内联执行；`./mvnw clean verify` 全绿） |
| M1 | ✅ 已完成（11/11，2026-09-16；spec 五项待决已裁决，`./mvnw clean verify` 全绿）——设计见 `docs/superpowers/specs/2026-09-16-util-simos-design.md`，计划见 `docs/superpowers/plans/2026-09-16-util-simos-plan.md` |
| M2 | ✅ 已完成（15/15，2026-09-17）——设计见 `docs/superpowers/specs/2026-09-16-map-simos-design.md`（其 §1.2 是关账判据），计划见 `docs/superpowers/plans/2026-09-16-map-simos-plan.md`，逐任务裁定与跨任务约束见 SDD 台账 `.superpowers/sdd/2026-09-16-map-simos-plan/progress.md`（★ **加不加 `-f` 是"按机器"的，别照抄任一侧结论**：本机 2026-09-18 实测 `.superpowers/sdd/` 下**没有** `.gitignore`，`git check-ignore` 返回"未忽略" ⇒ 普通 `git add` 即可。**但** git 历史里 M1 与 M3 的提交信息都写了"-f 越过 `.superpowers/sdd/.gitignore`"，而该文件**从未入库**（`git log --all` 查无记录）⇒ 它是**某些机器上才有的本地文件**。**换机器先跑 `git check-ignore -v <台账路径>` 再决定**）。关账四条判据逐条核过：① L1~L9 逐条守卫（`RegressionGuardsTest` + `region/RegionIndexGuardTest`，提交 `9152749`，9 轮变异逐条自证）；② 框选随机化与自动河流各有 ★ 验收；③ `./mvnw clean verify` 绿（rc=0、416 条用例、`BugInstance size is 0` ×5）；④ Task 1~14 每条护栏都有故意违规用例自证。关账报告 `task-15-report.md`（含"我未能核实的"清单）。挂起项：`GameMap` 无 id ⇒ `map:<mapId>` 的 mapId 只回显不可校验；重建河流会整份覆盖 `EdgeTags`（合并语义归 Command 层，编辑流实现者必须处理） |
| M3 | ✅ 已完成（13/13，2026-09-17）——设计见 `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`（其 §1.1 是四条判据），计划见 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md`（末节为执行期取代说明汇总），逐任务裁定与跨任务约束见 SDD 台账 `.superpowers/sdd/2026-09-17-social-unit-simos-plan/progress.md`（★ **加不加 `-f` 是"按机器"的，别照抄任一侧结论**：本机 2026-09-18 实测 `.superpowers/sdd/` 下**没有** `.gitignore`，`git check-ignore` 返回"未忽略" ⇒ 普通 `git add` 即可。**但** git 历史里 M1 与 M3 的提交信息都写了"-f 越过 `.superpowers/sdd/.gitignore`"，而该文件**从未入库**（`git log --all` 查无记录）⇒ 它是**某些机器上才有的本地文件**。**换机器先跑 `git check-ignore -v <台账路径>` 再决定**）。关账四条判据逐条核过：① 人口种子表逐值（`PopulationSeriesTest` 10/10，18036/6300/15000 字面断言在案）；② 移动逐值表（`UnitMovesTest` 10 + `TerrainMovementCostTest` 7；12500/32500 直证，中间值 27500/−5000/5000 由 R-13-b 补条 `criterionTwoArithmeticMatchesTheSpecTable` 直证并配变异轮 m13v-1 红）；③ `./mvnw clean verify` 绿（rc=0、517 条用例、`BugInstance size is 0` ×5、ERROR 0 / WARNING 1）；④ Task 1~12 计 46 轮变异 + 关账轮 m13v-1 逐条自证（存活项如实存档：Task 8 m1 等价、Task 9 m1/m3、Task 12 m3b、Task 4 m2 探针两轮存活后补序用例杀掉）。关账报告 `task-13-report.md`（含"我未能核实的"清单）。挂起项：`GameMap` 无 id ⇒ `mapId` 只回显不可校验；属性段地址不服务；materialize 写回归 M4；人口 cache 未做；A\* 规模与跨 JVM 决定论未测 |
| M4 | ✅ **已完成（17/17，2026-09-19）**——设计见 `docs/superpowers/specs/2026-09-18-core-simos-design.md`，计划见 `docs/superpowers/plans/2026-09-18-core-simos-plan.md`，逐任务裁定与**带裁定的遗留条目**见 SDD 台账 `.superpowers/sdd/2026-09-18-core-simos-plan/progress.md` 与各 `task-N-report.md`。**已完成**：Task 1~11（契约收敛 / `util.spi` 五类型 / JSON 地基 / `WorldChangeSet`+`Envelope`（C26）/ `SqliteStore` / **`Timeline`** / **`CheckpointStore`** / ★ **`Replay`** / **`CommandRegistry`+`CommandBus`** / ★ **C17 的 ③④ + R7（判据三）／**Task 11 可观测性**（八类事件冻结表 + `EventRow`/`EventStore` + `CommandBus` 事件链 + §7.3 四条日志 + R6；4 个新用例类各 6 条 = 24 条，12 个变异体全杀）**、⭐ **Task 12 两阶段推进**（六步 + ③ Resolve（C14/C15）+ ④ Validate 五项（§5.4）+ ⑤ 单事务落 revision 与全链事件 + ⑥ checkpoint（C19）；R9/R10/R14；2 个新用例类 24 条，**9 个变异体全杀**）、⭐ **Task 13 装配门面**（`CoreConfig`+`CoreSimos`：装配/封存 + Post-commit checkpoint（C19/C24）；1 个新用例类 5 条；**5 轮变异 0 存活**；主树合并门禁 **697** 条 170/255/37/93/**142**；★ **两条挂账当期消账**——`Replay` 真跑 / `StateLoader` 真装配 / `TimeAdvance` 档被读回）、⭐ **Task 14 判据一——分岔端到端**（真 `RenameUnit` 双侧推进 + 一字不变逐值断言 + 跨分支父链；test-only；**2 轮变异 0 存活**；主树合并门禁 **698** 条 170/255/37/93/**143**；★ **判据一 ① ② ③ 端到端闭合**）、⭐ **Task 15 判据三——真实并发加固**（**零改动关账**——裁定 54：Task 10 的 `OptimisticConcurrencyTest` 已全量满足 ①②③（逐条对表 `:136-141/:151-153/:171-173` + 当轮取证 `task-15-evidence/verify.log`）；m1/m3 产物自 `30a24eb` 未变、不重跑；★ **判据三闭合**）、⭐ **Task 16 unit 侧最小真实链路**（Step 1/2 `m4/b16`：`UnitTimeParticipant`+`RenameUnitHandler`；**Step 3 判据四端到端**（子代理 `m4/b16s3`）：真 `UnitTimeParticipant`+真 codec 经 `CoreSimos`，**2 轮变异 0 存活**、m2 读真事件自证；主树合并门禁 **700** 条 170/255/37/93/**145**；★ **判据四 ① ② 端到端闭合**）、⭐ **Task 17 M4 关账**（四条判据逐条实测值 + R1~R18 点验；主树全量门禁 **700** 条 170/255/37/93/**145**、BugInstance 0 ×5、ERROR 0；★ 关账抓到 spec 要求而**从未落盘**的 `jackson-databind` 显式声明并补上（pom，重跑门禁）；关账报告 **`task-17-final-report.md`**）**）——**Task 8 / 9 / 10 / 11 / 12 均为控制器内联执行**（本轮派发的 agent 先后死于 **429**（账号级五小时额度，换 agent 不解决）与 **503**（CPU 过载：`nproc=2`，**跑 Maven 会杀掉活着的 agent**——此条已实测两次））——**Task 13 起恢复子代理执行**（2026-09-19 用户重配子代理后首用 `deepseek-flash-go`，一次过），主树 `feat/adr1-core-scope`，`./mvnw clean verify` 绿（rc=0、**692** 条用例 170/255/37/93/**137**、6/6 模块、`BugInstance size is 0` ×5、`[ERROR]` 0 行；日志 `task-12-evidence/full-verify-final.log`——★ 与 Task 11 绿轮 668 **逐模块对差**：前四个模块一个都没动，core 113→137 恰 +24 ＝ Task 12 的 2 个新用例类（16 + 8））。★ **Task 13 合并后**全量绿轮 **697** 条 170/255/37/93/**142**（core 137→142 恰 +5 ＝ `CoreSimosTest`），日志 `task-13-evidence/logs/merged-full-verify.log`。★ **Task 14 合并后**全量绿轮 **698** 条 170/255/37/93/**143**（core 142→143 恰 +1 ＝ `BranchingEndToEndTest`），日志 `task-14-evidence/logs/merged-full-verify.log`。★ **Task 7 那道欠账的门禁补跑时抓到 2 个真 defect**（`UnitCodec` 两条 `BC_UNCONFIRMED_CAST`、`CheckpointStore.write` 一条 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`），均已修并各带自证过的守卫用例——**这正是"不跳过门禁"的价值**，详见台账 §癸（含 SpotBugs 判定随类集变化那条实测，已升为纪律形态 6）。★ Task 6 的 agent 只跑过 `-Dtest=TimelineTest` 且报告 §5.1 **自陈全量 verify 未跑**——合并后那次 clean verify 正是补上那道门禁（`TimelineTest` 首次在 SpotBugs+Checkstyle+Spotless+整个 reactor 下通过）。★ **Task 9 是 M4 里与 spec 分歧最多的一个**（六条真设计缺口，非笔误）：**裁定 32**（9↔12 闭环 ⇒ 注入 `AdvanceRoute`，**解环**）、**33**（Task 9 = 分派+①+②+④，③ 与锁纪律归 Task 10；`submit` **故意**非线程安全）、**34**（9→8 无环 ⇒ 注入 `StateLoader`，**不是**解环，是为可测性；别与 32 混记）、**35**（领域命令**继承父行时刻**，推定，Task 12 后复核）、**36**（`ForkBranch`/`AdvanceTime` **补身份三件套**，取代 spec §4.1 形状）、**37**（`type()` 必须 `<namespace>.<Command>`，**构造期**校验 ⇒ `CommandRegistry` **无可变 `register()`**，取代计划 Produces 行）、**38**（`info` 段的编解码**全仓缺口**——spec 没写、计划没写、`ModuleCodec` 表里也没有；实测**连空表都往返不了**且死在**类型解析期**（`Cannot find a (Map) Key deserializer for type … Address`）⇒ 在 util 的 `SimosObjectMapper` 注册 `Address` 的 Map 键绑定；★ **范围止于键，`InfoEntry.value` 是裸 `Object`、结构化值不满足 `equals` 往返，如实记下不在此解决**）、**39**（`changeset_json` 的线格式：**第四台 mapper（`Timeline` 那台）按 ADR-1 看不见领域类型、装不上模块级 mixin** ⇒ 三个模块的 `public boolean isEmpty()` 被 Jackson 内省成属性 `empty` **写进字节**，严格读侧随即抛 `UnrecognizedPropertyException`——**写出来的档自己读不回**；**与裁定 38 同形**：两处各自正确的东西之间**没有装配点**；修在**共享层**（`SimosObjectMapper.changesetsWithoutDerivedPredicates()`，**不能用 mixin**——同一 target 只有一份 mixin，会与 `Timeline` 的 `@JsonTypeInfo` mixin 碰撞）；★ **Task 6 的既有护栏用的是没有 `isEmpty()` 的替身，判别力差的就是那一个方法**；★ **带裁定的遗留条目**：spec §3.2 本把该列定义成「信封（C26），模块载荷是其中一段文本」，**Task 6 落成了 `WorldChangeSet` 整体 JSON ＋ `Id.CLASS`、Core 于是内省了模块类型（真偏离，不是措辞问题）**——改回去会动 `WorldChangeSet` 的类型、牵动 Task 4/6/9 三个已关账任务，故**不在此裁决**，归 Task 13 或 Task 16 收口）、**40**（C17 的锁**只罩 ③④**——`SqliteStore.inTransaction` 已把每个事务串行化，故缝在**两个事务之间**，① 与 ② 一条都不能挪进锁里；★ 加锁前实测到 `SQLITE_CONSTRAINT_PRIMARYKEY`，Task 9 报告 §5 那条硬接缝当场兑现）、**41**（m2 的预测被推翻：① 的「分支存在性」与「过期快失败」**两半都是护栏**、各有既有用例钉住——**m2 不是等价变体**；★ 我读漏了那个**炸药替身 handler**、差点把"已被钉死"写成"等价变体"，形态 5 的又一实例）、**42**（**新增护栏必须自带变异轮**：补的 `+1` 直证配了 m4，否则是装饰）。**未完成**：无（M4 17/17）。★ **M4 终态**：700 条 170/255/37/93/145；四条判据逐条核过；R1~R18 点验（**R3/R14 当场补证**、R1/R2/R13/R15/R18 记"报告在案、日志未入库"＝裁定 56）；关账报告 `task-17-final-report.md`；★ 下一里程碑 **M5**（GUI / MCP / AgentBinding，开工前先裁决 M5 待决项）。★ **裁定 49（Task 12）**：`AdvanceConflict.namespaces` **不排序**——C15 只管**地址**（spec §5.3 原文核过）；排了会把**方向**抹掉，两条独立风险退化成两条逐字节相同的事件（首轮用例当场抓住的真缺陷，m5 是它的复原体）。★ **裁定 50（Task 12）**：**包装调用必须留在构造器体**——SpotBugs 的 `EI_EXPOSE_REP` **只认构造函数体内直接可见**的包装调用、不做跨过程分析（两次探针实测；**已升为形态 7**，含"哪两格没测过"）。★ **裁定 45**：**不造** `core/observe/Digest.java`，**复用** agentlib 的 `Digest`（总纲 §8.1 / §10.5、spec §7.1 **三处同口径**；该类在旧的 49 类构件里整个缺席过，断法是**编译失败**，故 `DigestTest` 逐值钉住它）。★ **裁定 46**：**只有 `CommandEnvelope` 支的事件链归 `CommandBus`**；`AdvanceTime` 的**整条链由 route 写**——理由**不是分工好看，是事务边界**：只有 route 能开那个事务，`CommandBus` 替它写 `received` 会让那条事件落进**另一个事务**，Step ④ 的"revision 行 + 全部事件行同一事务"当场就破。★ **Task 11 给 Task 12 的硬接缝**（**已由 Task 12 兑现**）：`TimeAdvance` 必须**自己写全** `received → started → N×proposal → finished → committed` **并在同一个事务里**连同 revision 行落盘；★ Task 11 的 `advanceSequenceShapeIsWhatR6Requires` 用的是**替身 route**，**不证明真 `TimeAdvance` 会产出该序列**——Task 12 落地前**不许引用它当证据**。★ **Task 12 起**：真证据在 `TimeAdvanceTest.realRouteProducesTheFrozenR6Sequence`（真 route + 真 store）；Task 11 那条只改了注释、**仍是机制级**，引用端到端结论时**引前者**。★ `ForkBranch` 支**不发事件**（已知缺口，判据二现在覆盖不到分岔）。★★ **Task 10 与 Task 11 都改 `CommandBus.java`，是串行不是并行**（**两者均已关账**，此条留作通则）——计划把它们列作同批（C2）指的是同批**范围**，不是可并行；且本机 `nproc=2`，"Maven 与 agent 并存"已实测两次都会杀掉 agent ⇒ **一次只准有一个在跑**。★ 通则：**同一文件被两个任务改 ⇒ 串行**，后关账者必须**重跑前者的变异轮**（Task 11 就重跑了 Task 10 的 5 个变异体，全数复现被杀——旧证据的对象已被改掉，不重跑就等于拿旧证据给新字节背书）。**Task 12 ← 10、11**；**Task 13 ← 8、12**（Task 8 刚把它的一半满足了）；★ **Task 16 的 Step 3 必须等 Task 13**（裁定 20）——**已满足**。★ **Task 10 给 Task 11 的硬接缝**（**已由裁定 46 结**）：`commit` **只在 `commitLock` 内被调用**，计划 ④ 要求"revision 行 **+ 全部事件行**同一事务" ⇒ 事件写入落在 `commit` 里就**天然在锁内**，放到 ③④ 之外就破原子性。**Task 11 的兑现方式**：`CommandBus` 只写信封支的链（在 `commit` 内），`AdvanceTime` 的整条链交给 route（只有它能开那个事务）；另把**日志抬到锁外**（`dispatch` 拆出 `routeEnvelope`）——否则 appender 抛异常会在**提交成功之后**逃出 `submit`，调用方以为失败、实际已提交。★ **Task 10 给 Task 13/15 的硬接缝**：本任务的锁**只保证 `CommandBus` 自己的状态机**；`StateLoader`/`AdvanceRoute` 是**注入**的，R7 用的是返回常量的替身 loader ⇒ **"`Replay` 在并发下安全吗"没验**，Task 8 那条"`Replay` 从未在真状态上跑过"**依然成立**。★ **给 Task 15**：计划说"m1/m3 若已在 Task 10 跑过就不再重复跑"——**均已在 Task 10 跑过且都红**，日志在 `task-10-evidence/`。★ worktree `b8`/`b9` **派发前一律先 `reset --hard` 到当时 HEAD**（裁定 25 的陈旧基线陷阱；`b9` 上那个 Task 11 agent 死在动笔前、worktree 实测干净，`b8` 仍停在 `adfb871` 且带一个 untracked 残留文件）。★ 本机 `nproc=2` 且本地推理网关与 Maven 抢同样的核 ⇒ **并发上限 2**，**不要在 agent 活着的时候跑全量 verify**（已因此杀掉过一个 agent）。★ Task 6 给下游的坑（其报告 §4）：changeset 落盘用 mixin + `Id.CLASS`，**JSON 含全限定类名**（挪包即旧档不可读）；分岔行的 `changeset_json` 是 `WorldChangeSet` 的 JSON，**不是** checkpoint 信封。★ Task 9 给下游的硬接缝（`task-9-report.md` §5）：`commit` 用 `base.revision().value()+1`，**并发下两提交会算出同一个 revision 号**。★ **Task 12 已给推进支选了路**：不加锁、靠 `(branch, revision)` 主键挡，失败**折成 `Conflict`**（裁定 48）；**信封支仍靠 `CommandBus` 的锁**。★ Task 8 给下游的硬接缝（`task-8-report.md` §5）：① **`Replay` 从未在真由 `CommandBus` 写出来的 revision 上跑过**（`ReplayTest` 的行是夹具直接落盘的）⇒ Task 9 那条"`StateLoader` 的真实装配 `replay::replay` 从未在真状态上跑过"**依然成立，Task 13 装配时第一个要看这里**；② **R5 的步数上界是条件成立的**——它依赖「C19 说应当有的 checkpoint 确实都在」，劣化形态（文件缺失）**会超 N**（用例已把这条写成断言 `isEqualTo(8)` ＋ `isGreaterThan(N)`，别再把它读成无条件）；③ `readInfo` 落到具体类型 `InMemoryInfoSystem` 是**接缝不是终局**（`InfoSystem` 是接口，util 没给它的 SPI）；④ `changesetsWithoutDerivedPredicates()` **在 util 内没有自己的守卫**（有意：规则的意义是四台 mapper 一致，只有跨 mapper 的用例验得了它）**且它是"按名的规则"不是"按语义的规则"**（`"empty"` 是字符串常量，已核查当前无冲突，但是当时为真不是结构保证） |
| M5 | ✅ **已完成（12/12，2026-09-19）**——**Simos 外壳**：让 Agent / MCP / 玩家经**同一条** `Command → ChangeSet → Revision` 路径改同一个世界。设计见 `docs/superpowers/specs/2026-09-19-shell-simos-design.md`（判据见其 §〇.1），计划见 `docs/superpowers/plans/2026-09-19-shell-simos-plan.md`（§四 取代说明汇总），逐任务裁定见 SDD 台账 `.superpowers/sdd/2026-09-19-shell-simos-plan/progress.md` 与各 `tN-report.md`，关账报告 **`task-12-final-report.md`**。**已完成**：T1 `simos-app` 骨架与装配门面（reactor 七模块）/ T2 core 只读扩展（`branches`/`head`）/ T3 查询层 + **两个真 Facet**（`unitsHere`/`population`，关总纲 §3.2 遗留）/ T4 unit 7 handler + `UnitPayloads` / T5 工具集 **3 写 + 9 读** + `SimosToolSource` / T6 审批装配（`PendingApprovals`→`HttpApprovalChannel`→`ApprovalCoordinator`→authorizer；5711 `/api/approvals` 真代理）/ T7 ★ **5715 MCP 服务**（`startHttp` owned + **authorizer 必填**；**官方 SDK 客户端**走真 socket 端到端）/ T8 GUI 服务器 + `/api`（写经 Core）/ T9 前端三页（Canvas 地图，无 npm/无 CDN）/ T9b 可运行性收尾（★ `bootstrapGenesis` **唯一绕过 `submit` 的写路径、只在空库**；`--demo` 首启；地图单位标记）/ T10 AgentBinding（`AgentAttachPolicy` 入 `util.spi`）/ T11 ★ **判据①②端到端 + R9** / T12 关账。**门禁**：主树 `./mvnw clean verify` 绿（rc=0、**825** 条 = 170/255/45/131/153/**71**、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0）。**判据逐条实测值**：① 同一 Command 路径——GUI `(main,2)` / MCP `(main,3)` / MCP advance `(main,4)` **同表**、`initiator` `player:gui` vs `agent:t11-e2e` 逐字不同、链 **2/2/5** 完整、世界逐值；② MCP 可达任意合法状态——catalog **8 类型逐类 committed**（`main@2..9`）+ `advance` `main@10` + `fork` `mcp-branch@1`，反向坏载荷 ⇒ `REJECTED` 且 `revisions` 行数不变。**变异**：12 任务累计 **21 轮 0 存活**。**带裁定的遗留 11 条**（R2 存档 / `ShellMain` port 打印 / `BindingRegistry` 不持久化 / `PlanRoute` 稀疏 waypoints / 审批超时与会话键未验 / `fork` 不发事件 / R9 长连分支未覆盖 / 反向行数未打印 / 重放代价未测 / **运行形态=够用但不便、shade 归开口项** / §十三 1~3 已消），详见 `task-12-final-report.md` §四。★ **裁定 64**：spec §3.2 的 MCP caller 桶由 `GUEST` 校正为 **`DEFAULT`**——三条写工具是 `ToolSpec.level(DEFAULT, sensitive=true, …)`，`GUEST` 被 `PermissionChecker` **硬拒、不进审批** ⇒ 只能读不能写（与 S3/S4 矛盾）；spec 已回填。★ **裁定 65**："冻结 6 事件"是**控制器派单措辞错**（把 M4 文档里 N=2 的举例当常量）；R6/spec 原文是 `received → started → **N×proposal** → finished → committed`，本壳只注册 1 个 participant ⇒ 实测 **5 条**，spec 无需改。★ 下一里程碑 **M6**（开工前先裁决 M6 待决项）。 |
| M6 | ✅ **已完成（2026-09-19）**——**GSimap 导入器**：旧 `*_map.json` → simos 数据集。**★ 形态由用户当场改向**：不走 Java 五步流水线（无 spec、无模块、无 Java 测试），交付物 = **一个 Python 一次性迁移脚本** `tools/gsimap_import.py`（601 行、标准库 only、**零 Java/Maven 改动**）。用法：`tools/gsimap_import.py <旧 map.json> <输出目录>`。台账 `.superpowers/sdd/2026-09-19-gsimap-import/progress.md`（**用户裁定 M6-U1~U3 与全部映射硬规则在案**）。**门禁**：主树 `./mvnw clean verify` 绿（rc=0、**825** 条 = 170/255/45/131/153/71、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0）——`tools/` **不入 Maven reactor**，故与 M5 关账同值。**判据实测闭合**：`demo` 与 `test_integration` 两份真档逐字段对拍 **ALL PASS**（19441 hex、地形直方图 `plains 10719 = 5559+5042+118` ⇒ `swamp→plains` 的 lossy 合并算术对上、2 条 province 的环数与环长、pathwayGroups、信封与 `changeset_json` 真样本逐字节相同）；★ **对拍证明不了的那一环由 Java 真读路径证明**——`ShellMain --store <导入目录>`（**不给 `--demo`**）成功装配、未覆盖导入档、`/api/map/overview` 返回值经 `Replay((main,1))` 解码、**零异常** ⇒ **`Region` 的硬校验 `boundary.equals(RegionBoundary.of(hexes))` 接受了 py 算出的边界**（环算法移植成功，这是唯一"不是搬字段"的活）。**fail-closed 7 条**全部按预期拒绝（`rc=2`）：MapDiff 文件 / `forest`、`tundra`、未知 key 在用 / `cities` 非空 / `edgeTags` 非空 / `riverMask` 非 0 / `hexOrientation=true`。★ **地形映射表**（属性整套取 `TerrainCatalog`，因 U1 已把旧的 9 项表整个作废，**不是改名**）：`water→ocean`、`lowland→plains`、`plains→plains`、`desert→desert`、`hills→low_hills`、`mountain→mountains`、`swamp→plains`（★lossy，simos 词表是纯高度带的、没有湿地）；`forest`/`tundra` **故意不进表**（0 个真实世界用到，一旦用到就报错）。**丢弃**（各带理由）：`compressedRegions`（纯渲染缓存）/`rivers`+`roads`（旧仓 @Deprecated）/`terrainBlocks`（旧仓 @Deprecated）/`gridSize`。**带裁定的开口项**：① 连通性三份表示（`riverMask`/`edgeTags`/`edges`）的**融合优先级未裁决** ⇒ 有非空即报错（真实档里 `edges` 仅 `mcp_smoke_test` 有 1 条、其余为空）；② `simos.db` 的 **DDL 由脚本内联**，Core 改 DDL 时脚本会**静默失配**（无编译期护栏）；③ `GenerationSpec` 是**合成占位**；④ 带洞区域与 `edges` 非空**无真实样本**（只用合成夹具证过）；⑤ 只在**本机**跑过。 |
| M7 | ✅ **已完成（8/8，2026-09-19）**——**WebUI 可视化骨架**：把 M5 的三页只读前端改造成**单页工作台**（开场即地图 + 顶部五模式栏 + 三栏 + 底部线型时间轴 + 单位倒树 + 区域标签面板 + 单位移动编辑）。**★ 唯一碰 Core 的是 `Timeline.listRevisions`+`CoreSimos.revisions`（纯只读增量）**；**整个 WebUI 是"零 Java 改动"建起来的**。设计 `docs/superpowers/specs/2026-09-19-webui-design.md`（判据见 §〇.1），计划 `.../plans/2026-09-19-webui-plan.md`，逐任务裁定见台账 `.superpowers/sdd/2026-09-19-webui-plan/progress.md`（裁定 68~73），关账报告 **`task-8-final-report.md`**。**门禁**：主树 `./mvnw clean verify` 绿（rc=0、**833** 条 = 170/255/**45**/131/**154**/**78**、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0）。**判据逐条实测值**：① `GET /` 200/2545B 含 `id="mode-bar"`×1 与 `data-milestone="M8"`×2（旧三页仍 200）；② 时间轴节点==head、**中间节点两按钮置灰 + `head 3->3 rows 3->3`（只读预览不写盘）**、末端可分岔（`branches=["b2","main"] lines=2`）、**409 真复现**（提示+自动重取+未静默重试）；③ 点 hex ⇒ 左栏真读数且**请求 URL 带 `revision=2` 并显示 rev2 的值**；缩放/平移后点选准（scale 6.748 / 漂移 1.7px）；④ 区域按 tag 分组、点区域 `701==701`、点标签 **`union 701`**、**填充色 == `meta.color`（像素证明）**；⑤ 移动 `position {1,1}→{1,2}`+`head 1→2`+**节点 1→2**、编制 `parent/ member/ equipment` 逐值、422 显示 `reason` 原文、409 不写；⑥ 倒树 **`weight 700 vs 400` / `size 15 vs 12`**（"标大"数值证明）+ 展开 `visibleRows 0→4`。**变异**：7 任务 **14 轮 0 存活**；R1~R8 **每条都有变异自证**。★ **裁定 70**：**"拖动时间轴每次重放很贵"被实测推翻**——服务端 overview（含 `Replay`）**40–85ms**；真瓶颈是 **~1MB 响应体传输（浏览器内 ~2.6s，99.8% 在 transfer）** 与**首帧同步渲染 ~2.55s** ⇒ 行动项归 M8（视口分级 / gzip）。★ **裁定 71/72/73 通则为"变异要能被杀"**：T6/T7 各**独立**发现"护栏若无法被变异杀掉就是装饰"，并各自改造装置（T6 加页面内夹具、T7 让超时不抛）——**已两次命中，是设计护栏时的硬约束**。★★ **★ 系统性开口项：本项目没有 JS 测试器** ⇒ T3~T7 的前端护栏（时间轴纯函数、几何换算、`buildTree`、分组、写路径 allowlist）**全是"证据级"**（node 自检 + Playwright e2e + 截图），**不进 Maven 门禁** ⇒ 有静默腐烂风险（`AppWritePathGuardTest` 只扫 Java、**不扫 `webui/**`**）。**在那之前前端护栏强度低于后端**。★ **遗留**：**区域重叠归属未核**（T4/T6 两次挂起 ⇒ **M8 必先裁**）/ `PlanRoute` 稀疏路点缺口延续 / 词表外兜底色分支未实测 / 倒树方向（根在下）可推翻 / 旧三页**保留**为调试页 / e2e 装置真坑（`page.fill` 滚动 ⇒ 点击静默无效，需 `scrollIntoViewIfNeeded`）。★ 下一里程碑 **M8**（地图编辑写面：`map.*` 命令族 + 地图编辑/区域编辑两模式；开工前先裁决区域重叠与命令粒度）。 |
| M7b | ✅ **已完成（4/4，2026-09-19）**——**用户实测反馈的修复包**。缘起：M7 关账后**用户亲自试用**报了四条，控制器派**两路只读取证**后定修法。台账 `.superpowers/sdd/2026-09-19-webui-plan/progress.md` 的 **「# M7b」**（裁定 **U1~U3** / **S1~S6**），关账报告 **`task-4-final-report.md`**。**用户四条 → 实测值**：①「**没有可拖动的按钮**」⇒ 加**可见 knob**（真元素 + `setPointerCapture`）——`a-knob-on-cursor` **`delta=0`**、★ `b-drag-knob-offrow` **纵向偏 70px 仍能拖**（即"抓不住"的根因是旧的 `pointermove` 依赖 target 在行内）；②「**分岔的新节点回到最左、看不出从哪来**」⇒ **列坐标布局**（`x=(列−1)×列宽`，分支的列由 `parent` **递归**决定）+ 垂直连线——★ **`d-fork-aligned` `child.x=323 parent.x=323 dx=0`**、`e-main-first firstLine=main`（**旧实现是 CSS flex 流、`revision` 从未当列号用**；spec §五-4 写的"从分岔点长出"**从未实现**）；③「**不知道移动逻辑是什么**」⇒ `movement` 由**布尔改对象**（9 字段，四处同形）+ 左栏**摊开规则**（`每格成本 1500`/`预算 2000`/`总成本 3000`/`remaining 1500`/**`预计到达 tick 7`**）——★ 根因是**旧的"点一下就过去"其实是 `PlaceAt` 瞬移**，与移动是两码事，且**UI 从不显示 MP/成本/ETA**；④「**右键到目标格自动画路线，能做出来吗**」⇒ **能做**：`PathFinder.findPath`（A\*）**早已存在且有单测，却零生产调用者** ⇒ 新增**只读**端点 `GET /api/map/path`（起点由服务端 `effectivePosition` 权威取）+ 前端 `contextmenu` → `unit.PlanRoute`（**唯一写入口**）→ 折线自绘；实测 `PATH_B=(1,1)->(1,2)->(1,3)`、`c-polyline-3-points`、**右键=替换线路**、图外 `reachable:false` **无写**、`g-nonget-list` **打印了完整非 GET 清单**。**门禁**：`./mvnw clean verify` 绿（rc=0、**841** = 170/255/45/131/154/**86**、7/7、`BugInstance size is 0` ×6、`ERROR` 0）；**变异 7 轮 0 存活**（T1 2 / T2 3 / T3 2）。**★ 带裁定的遗留 6 条 + 1 条待裁**：★★ **`PlaceAt` 瞬移与右键移动目前共存**（U2 说瞬移不作为正常编辑手段，但 T7 的**左键**点格仍是 `PlaceAt`）⇒ **建议左键只选中、移动只由右键发起，瞬移归控制台**（待用户点头）；`Route` ≥2 格（右键自身格 ⇒ 不发写）；S3 的 `?from=` 被 `?unit=&q=&r=` 取代（**起点必须服务端取**）；折线**对比度低**（半透明黄沙漠上）；前端护栏仍不进 CI；大图 A\*/像素级/`ARRIVED`/`NEED_REPLAN`/触摸未测。★ **两条纪律新实例**：**T2 实现者修好了自己装置里的假绿**——`.class` md5 聚合模式写成 `"$CLASS_NAME".*.class`（**多一个点**）⇒ 只匹 `ApiViews.X.class`、**不匹 `ApiViews.class`** ⇒ 聚合读到**空串**（`d41d8cd9…`=`md5("")`）⇒ "还原相等"退化成 **`空==空` 恒真**（已改为 `"$CLASS_NAME"*.class` 并**先断言非空**）；**T2 实现者拒绝改历史证据**（`t7`/`t5` 的 e2e 仍按旧布尔断言 `movement`）——**理由：改历史证据 = 篡改留痕**，正确。★ 控制器在 M7b 又**两次**被实现者纠正（**口径/公式写错，非代码问题**）：T2 派单的**列公式**对非 main 分支不成立（应为 `(列−1)`，列由 parent 决定）——**这已是第三次**（前两次：裁定 65、72.1）。 |
| 远程仓库 | `https://github.com/c1589190/SimulatorMosire`（**PRIVATE**，默认分支 `main`） |
| 推送状态 | ✅ **已推送**（2026-09-19，**M4 关账** / **M5 关账** / **M6 关账** / **M7 关账**；M7 提交链 `2463451`（spec+台账）`→ 5294f85`（计划）`→ 5b8fb5a`（T1 合并）`→ 676d527`（T2）`→ 9d63243`（T3）`→ c6d9dca`（T4）`→ db5e22b`（T5）`→ bc7f434`（T6）`→ 7307f78`（T7）`→ M7 关账提交`；M6 提交 = `tools/gsimap_import.py` + 台账 + 本行；M5 提交链 `4a8b945 … 61f5002 → 4197048 → 297fd59`（Batch E-1/E-2）`→ 2748bb6`（T7 关账）`→ fe837c8`（T7 合并）`→ 559c6f2`（T11 合并）`→ M5 关账提交`；此前同日 Task 16 Step 3 `cbf7352`、Task 15 零改动关账、Task 14 合并 `a2d015a`、Task 13 `d7148b4`、2026-09-18 推到 `2d09336`）——`feat/adr1-core-scope` 与 `origin` 同步（Task 13~17 的实现/合并/关账提交均已到远程；`git rev-list --left-right --count origin/feat/adr1-core-scope...HEAD` 实测 **0 0**）。M4 的 Task 1~5 于 `147a777` **首次**到远程（此前 origin 该分支停在 `ce98212`）；`c76b2b6` ← `5e49816` ← `80677ec` ← `a455741` ← `acd7c52` ← `9a7f248` ← `147a777`（`git log` 实测，**该链至今仍是 HEAD 的祖先**，只是退到 Task 6 那一轮了）；此后 `ed60fd8`/`618f840`/`eaa8093`/`6e93cd5`/`a2029f2`/`684c757`/`adfb871`/`cfb3cc3`/`411f1bf`/`411a9b8`/`2d09336` 陆续推上。★ 本条原写"推到 `147a777`"**不是错的、只是没提后面的提交**；也曾据此怀疑台账与 CLAUDE.md 互相矛盾，实测两边都对。★ 原写作"不擅自推送"是控制器自加的规则、**用户从未说过**，已撤。用户原话「你爱推就推反正是私有仓库」 |

**M0 已完成的东西**：五模块骨架（`simos-util/map/social/unit/core`）+ 父 POM；
模块边界 enforcer；门禁三件套（Spotless/Checkstyle/SpotBugs）；`simos-core` 接入
`agentlib-mosire` 并由 `AgentLibAvailabilityTest` 钉住（13 个类可加载 + JAR 类数 ≥ 118）。
每条护栏都有一个**故意违规用例**证明它会响，见实现计划 Task 3/4/5。

**AgentLibMosire 依赖现状**（2026-09-16 关账时复核）：**本机** `~/.m2` 里的
`0.1.0-SNAPSHOT` **已是完整构建**——`jar tf … | grep -c '\.class$'` 实测 **118**，
`simos-core` 的 testCompile 通过（`./mvnw clean verify` 全绿）。此前它曾是 49 类的陈旧构建
（缺 `permission` 包等），会导致**测试编译失败**（比"测试变红"更早一步）。**`~/.m2` 不跨机同步**——
换机器后若 `AgentLibAvailabilityTest` 红或 `simos-core` 编译失败，按测试里的提示在本机重建一次：
`cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install`。
⚠️ **判断重建成功与否看类数，不看时间戳**：`install` 会把源 jar 的 mtime 一并带过去，
`~/.m2` 里那个 jar 的时间戳与源 `target/` 下的**完全相同**，2026-09-16 当天被改写的只有同目录的
`maven-metadata-local.xml` 与 `_remote.repositories`。**用户已裁决：暂缓**升为固定版本
（ProjectMosire 有在途工作），本仓暂依赖 SNAPSHOT。

**机器与路径**：本项目**在多台机器上交替推进**，家目录不固定（已见 `/root` 与 `/home/cna`
两种），故文档里一律写 `~/`、不写死绝对家目录。工具可用性同样因机而异：数 JAR 类数一律用
`jar tf`（比 `unzip -l` 通用，`unzip` 并非每台都有）。

**换设备后的自检清单**（本机踩过的坑，按序做）：
1. `git status` 看 `core.autocrlf`——曾把整棵工作树 checkout 成 CRLF，`mvnw` 的 shebang 变
   `#!/bin/sh\r` 导致 Maven 完全起不来、Spotless 全红。仓库已用 `.gitattributes` 钉死 LF，
   新机器首次 clone 后若仍异常，先查这条。
2. `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 的重建不会跨机同步，新机器上若
   `AgentLibAvailabilityTest` 红（或 `simos-core` 测试编译失败），按上条命令在本机重建一次。
   **判"本机构件重建成功与否"一律看类数**（`jar tf … | grep -c '\.class$'` ≥ 118），**不看文件时间戳**
   ——理由见上面"AgentLibMosire 依赖现状"的 ⚠️（`install` 会把源 jar 的 mtime 一并带过去）。
3. **本机 `grep` 可能是 ugrep**（`grep --version` 可辨，本机实测 `ugrep 7.8.4`）：它**默认尊重 `.gitignore`
   且跳过隐藏目录**——于是 `grep -rn <串> .` 会**静默返回空**，把"没搜到"伪装成"不存在"。仓根下的
   `.superpowers/**` 正是被 ignore 的隐藏目录，属重灾区（M1 已因此得出过一次假阴性结论）。
   **要搜全仓一律用 `git grep <串>`**，或 `grep --hidden --no-ignore-files`。
   ★★ **但 `git grep` 本身带一个**同族**陷阱：它默认只看「已入库」的文件**（不含 untracked）。而
   **开发期刚写的文件正好全是 untracked**——于是 `git grep <串> -- <刚写的文件>` 会**静默返回空、rc=1**，
   把"明明有 77 处"报成"不存在"。2026-09-18 本会话当场实测（git 2.43.0，同一份 untracked 计划文件）：
   `git grep -c "Task" -- <路径>` → **无输出、rc=1**；`git grep --no-index -c "Task" -- <路径>` → **77、rc=0**。
   ⇒ **要搜含未入库文件的全仓，用 `git grep --untracked <串>`**（`--no-index` 本机实测等价）。
   **它与 ugrep 是同一个失效形态**：都把"没搜到"伪装成"不存在"，且都恰在人最需要搜到的时候发作。
4. **superpowers 插件的 `scripts/*` 可能是 CRLF**（本机实测 `task-brief` 是
   `with CRLF line terminators`，41 行带 `\r`）：直接执行会报
   `/usr/bin/env: 'bash\r': No such file or directory`，看起来像"脚本不存在"。**绕法**：
   `tr -d '\r' < 脚本 > /tmp/x.sh && bash /tmp/x.sh <参数>`。
   （与第 1 条同源——都是 `core.autocrlf` 在别的 checkout 上留下的 `\r`。）
