# Task 1 报告 —— `FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制 + R1 守卫

**状态：DONE**（1/1 变异轮红在声明靶子上；`./mvnw clean verify` rc=0，util 156 / map 246 / core 15，`BugInstance size is 0` ×5）

## ① 交付物

| 文件 | 形态 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java` | **git mv**（132 → 215 行）：package 改 `util.state`；import 加 `java.util.function.Function`；类 Javadoc 新增 C7 段（为什么在 Util）+ 三处模块专指改写；`diff` / `rebuild` 从 `MapChangeSet` **逐字搬入**为接口静态方法（只加 `static`，插在 `changed()` 之前） |
| `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | 178 → 100 行：两个私有方法 `diff` / `rebuild` 整体删除；`between` / `apply` 调用点改为 `FieldDelta.diff(...)` / `FieldDelta.rebuild(...)`；**删 4 个变无用的 import**（`LinkedHashMap`/`LinkedHashSet`/`Map`/`Set`），**保留** `Function`/`Objects`；加 `import io.mosire.simos.util.state.FieldDelta;`；`STRING_KEY` 原样保留 |
| `simos-map/.../generate/RegionRandomizer.java`、`RiverBuilder.java` | 仅 import 替换（`map.change.FieldDelta` → `util.state.FieldDelta`） |
| `simos-map/src/test/.../RegressionGuardsTest.java` | import 替换 + **补 `import static org.assertj.core.api.Assertions.entry;`**（R-1-d）+ 追加 `R1_thereIsExactlyOneFieldDelta`（**9 个用例** = 原 8 + R1） |
| `simos-map/src/test/.../change/MapChangeSetTest.java`、`RoundTripComponentsTest.java` | **新增** import（同包引用原无 import，R-1-b 漏列的 2 个文件） |
| `simos-map/src/test/.../generate/RegionRandomizerTest.java`、`RiverBuilderTest.java` | 仅 import 替换 |

终局判据：`git grep -n "map.change.FieldDelta" -- 'simos-map/src'` **为空**（实测）。`CityId` / `PathwayId` / `RegionId` / `EdgeRefTest` / `PathwayGroupTest` 的 `{@code FieldDelta}` Javadoc 文本**未动**。

证据：`task-1-evidence/{run.sh,mutate.py,rounds-driver.out,gate-clean-verify.txt}` + `task-1-evidence/rounds/m3t1v-1.kept`。

## ② 控制器裁决 R-1-a~f 逐条落点

- **R-1-a**：变异体 = 新增 `simos-map/.../map/change/LegacyFieldDelta.java`，**文件名 LegacyFieldDelta、声明名 `FieldDelta`**（包级私有，不写 `public`），**取代**计划 Step 8 第 3 条"类名与文件名一致（LegacyFieldDelta）"的写法——按该原写法 `interface LegacyFieldDelta` 不含守卫的逐行子串 `interface FieldDelta`，变异将假绿。实测该形态 `COMPILATION ERROR count = 0`（同包其余文件的单类型导入按 JLS 6.4.1 遮蔽同包同名类型）。
- **R-1-b**：替换 5 处 import（RegionRandomizer / RiverBuilder / RegressionGuardsTest / RegionRandomizerTest / RiverBuilderTest）；**新增 2 处**（MapChangeSetTest、RoundTripComponentsTest）；终局 grep 为空；Javadoc 文本引用未动。
- **R-1-c**：`MapChangeSet` 删私有方法后同步删掉 `LinkedHashMap`/`LinkedHashSet`/`Map`/`Set` 四行 import——若不清，checkstyle（`UnusedImports`，绑在 validate）会让构建挂而不是断言红。`Function` / `Objects` 保留。
- **R-1-d**：`RegressionGuardsTest` 补 `entry` 静态导入（原先确实没有）。
- **R-1-e**：装置复用 M2 Task 14 的 `{run.sh,mutate.py}`，三处必改全落：`ROOT=/home/cna/SimulatorMosire`（原件写 /root）、`ROUNDS_DIR` 指向本任务证据目录、变异体换成 R-1-a 形态的单条；**保留 Task 14 两条升级**：干净世界断言"清单之外的 .java = 0"、并集自证（修改 ∪ 新增 == 声明）。
- **R-1-f**：期望数字逐项命中——改前 util **156** / map **246** 全绿；变异后**仅** `R1_thereIsExactlyOneFieldDelta` 红（消息带出多余的键）；终局 verify util 156 / map **246** / core 15、`BugInstance size is 0` ×5、`BUILD SUCCESS`。

## ③ 变异轮自证（round `m3t1v-1`，`.kept` 原文在案）

- **干净世界**：从工作树 `rsync` 全新副本（排除 `target/`/`.git`/`.serena`/`.superpowers`），逐文件 md5 对照清单 **113 个 .java**、文件数不多不少、**清单之外的 .java = 0**；
- **改前绿**：`./mvnw -pl simos-map -am test` ⇒ `COMPILATION ERROR count = 0`，util `Tests run: 156, Failures: 0`、map `Tests run: 246, Failures: 0`，`BUILD SUCCESS`；
- **变异体字节自证**：新增文件声明集合 1 个，落盘非空，md5 `a1f1e4868736bb895758080fa1346922`；**并集自证**：实际（修改 ∪ 新增）== 声明集合，别无其它改动；
- **改后**：`COMPILATION ERROR count = 0`；simos-map **25 个测试类真的跑过**；map `Tests run: 246, Failures: 1`；
- **红点（恰 1 处，原文）**：
  `RegressionGuardsTest.R1_thereIsExactlyOneFieldDelta:402 [四个模块的 src/main 里必须恰有一份 FieldDelta 声明（第二份 = 语义必然漂移）]`
  surefire 断言消息：actual size 2 / expected size 1，actual = `{"simos-map/src/main/java/io/mosire/simos/map/change/LegacyFieldDelta.java"=2L, "simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java"=1L}`；
- **为什么红**：红因就是"多了一份 `interface FieldDelta` 声明"这件事本身——变异体落在扫描树内，`containsExactly` 把多余的键整个带出消息。**工作树全程未被变异触碰**（实验末尾 `git status --short` 与实验前一致）。

## ④ 门禁实测数字

- `./mvnw -q spotless:apply` ⇒ rc=0（在实验室**之前**跑，保证实验室 md5 清单 == 终局字节）；
- Step 6 回归网（`MapChangeSetTest,RoundTripComponentsTest,RegressionGuardsTest,RiverBuilderTest,RegionRandomizerTest`）：**58 条全绿**（20+5+9+11+13），`BUILD SUCCESS`——重构行为不变的判据，先于 R1 落地通过；
- `./mvnw clean verify`（完整日志 `task-1-evidence/gate-clean-verify.txt`，467 行）：**rc=0、`BUILD SUCCESS`**，Tests run：simos-util **156** / simos-map **246** / simos-core **15**，`Failures: 0, Errors: 0`；**`BugInstance size is 0` ×5**；
- 门禁后 md5 抽查：工作树 `simos-map/src` + `simos-util/src` 113 个 .java 与实验室清单**逐字节一致**（diff 为空）——实验室证据就是终局代码的证据。

## ⑤ Concerns 与未能核实清单

1. **变异体文件命中数是 2 不是 1**：R1 按计划逐字用 `rawLines`（**不剔注释**），变异体自己的中文 Javadoc 里也含有字面子串 `interface FieldDelta`，贡献了第 2 次命中。**决定性的那次命中是声明行本身**，红因不变；该性质意味着守卫对注释提及同样敏感（只会更严不会漏），按计划原文接受，未顺手改测试。
2. **JLS 6.4.1 遮蔽是实测自证、非条文核验**：同包同名类型被单类型导入遮蔽、不引发编译错误，靠变异轮 `COMPILATION ERROR count = 0`（25 类真跑）与控制器的 javac 探针双重实证；我没有独立核对 JLS 原文。
3. **实验室改前跑的是 `test`**（Surefire，不含 Spotless/SpotBugs），与 M2 Task 14 同口径；SpotBugs 门禁由工作树的完整 `clean verify` 单独覆盖。
4. `simos-social` / `simos-unit` 的 `src/main` 现在只有 `package-info.java`：R1 对这两个模块的扫描**结构性活着**（目录被扫、`javaFilesUnder` 的非空自证通过、0 命中），但尚无真实代码可护；M3 后续任务往里加代码即自动纳入扫描。
5. 计划 Step 9 的 `./mvnw -q -Dtest=FieldDeltaGuardTest test` 未执行——计划原话"无此类，跳过"。
6. 工作树里有一处先前就存在的未跟踪目录 `.omo/`（与本任务无关），未触碰、未入库。
