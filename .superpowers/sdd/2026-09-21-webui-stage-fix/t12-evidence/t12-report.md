# T12 关账报告 —— 富世界文档 `.md` 产出（绝不录 Info）

> 分支 `wsf/t12`，worktree `.claude/worktrees/wsf-t12`，基线 `68e3eeb`（含 T11 合并提交）。
> 证据目录 `.superpowers/sdd/2026-09-21-webui-stage-fix/t12-evidence/`。
> 判据依据：spec §五.3 / §七 `C27`；计划 §T12。

## 〇 一句话

把 `v17levant` 存档 `checkpoints[].elements[]` 的 **615 条 / 319,630 字**文本，按 checkpoint 类别
离线导出成 `docs/worlds/v17levant/` 下的 **7 个 `.md`**（6 类 + 一份索引 `README.md`），供另一个 Agent 阅读。
**全部走离线文件产出**：不 `import` 任何 simos 类型、不碰 `simos.db`、不 `submit` 命令、**不进任何 Info**
（红线，两层判据 + 各自变异体）。

## 一 交付物

| 文件 | 变更 |
|---|---|
| `tools/v17levant_docs.py` | 新：文档产出器（离线、标准库 only、不入 reactor）。按 6 类分文件 + 索引；`map.md` 明确标出 16 有名 / 82 噪声；**先 `purge_stale` 清旧 md**（产物 = 源档纯函数） |
| `tools/check_v17levant_docs.py` | 新：产物校验器（逐值计数 + 产物覆盖 + 16/82 + **Info 红线** + 目录/路径）。期望值**全部本地冻结**，不继承生成器 |
| `docs/worlds/v17levant/README.md` | 新：总览 / 红线声明 / 规模表 / **“98 区域里只有 16 个有资料”** 醒目提示 / 文件导航 / 阅读顺序 |
| `docs/worlds/v17levant/{worldview,characters,factions,narrative,internal,map}.md` | 新：6 类正文（逐字节签入） |
| `simos-app/.../app/demo/T12InfoRedlineTest.java` | 新（2 条）：C26 runtime 不变量 + 结构性「无 Info 写路径」fail-closed 护栏（含 Python 符号 AST 检查） |
| 台账 `.superpowers/sdd/2026-09-21-webui-stage-fix/progress.md` | 追加 T12 段 |

★ **`docs/worlds/v17levant/` 下只有 `.md`**（`find` 断言：非 md 条目为空，证据 `logs/content-spotcheck.txt`）。

## 二 判据逐条实测（spec §七 `C27` + 计划 §T12）

| 判据 | 实测值 | 证据 |
|---|---|---|
| **C27 条数逐值** | **615** = `narrative 170 / map 318 / internal 67 / factions 52 / worldview 6 / characters 2` | `logs/checker.txt` `counts.*` 全 PASS |
| **C27 字数逐值** | **319,630** = `narrative 193,165 / internal 59,558 / factions 38,224 / map 25,561 / worldview 1,651 / characters 1,471` | `logs/checker.txt` `chars.*` 全 PASS |
| 产出目录只有 `.md` | 非 md 条目 **0** | `logs/content-spotcheck.txt` |
| ★ **16/82 标注** | 有名 **16**（`factions` key ∩ 98 province 名）、噪声 **82**；`map.md` 有独立「程序化噪声区域」一节 | `logs/checker.txt` `regions.named-16` / `regions.noise-82` / `map.noise-section-exists` |
| ★ **16 个有名势力确在 `factions.md` 有长篇设定** | 16 个 key 全部出现；如 `法蒂玛哈里发国` 首行即 `名称: … / 首都: 科尔多瓦 / 体制: …` | `logs/content-spotcheck.txt` `factions.md:19` |
| ★ **内容抽查（关键片段）** | `factions.md:19`「首都: 科尔多瓦」；`narrative.md:13`「哈里发穆罕默德·奥斯曼奥卢登基已历七载…」；`internal.md:50`「帕拉丁帝国 · 军事编制」（约3.4万战斗人员）；`worldview.md:31`「稻教的核心叙事…」 | `logs/content-spotcheck.txt` |
| ★★ **红线：没有录进 Info** | `info == InMemoryInfoSystem.empty()`（`bySubject` 空）、`SdState.info()` 空、`sdInfoEntryCount == 0`；**且探针确证状态非空**（`hexCount=59223`） | `logs/info-probe.json`（`T12InfoRedlineTest` runtime 实测落盘） |
| ★★ 红线（结构性） | 产出文档与两个离线脚本里**无 Info 写路径符号**；富世界资源信封 `info` 段为 `{"bySubject":{}}` | `T12InfoRedlineTest.docsAndRichWorldHaveNoInfoWritePath` |

### ★ 装置自证（防“扫描为空恒真”）

- 校验器**先断言**`teeth.source-non-empty`（源 615 条）与 `teeth.docs-non-empty`（产物 ≥1）再下结论。
- 校验器**先断言** `redline.probe-saw-real-state`（`hexCount==59223`）——证明 Info 的“空”是**真读到了非空世界之后的空**，不是“没读到”。

## 三 生成器与校验器的关系（★ 本任务最关键的一处修正）

**发现（实测，非推断）**：m1（生成器删掉 `narrative` 一行）**首轮存活**。根因是**判据与变异体串通**：

1. 校验器 `import v17levant_docs` 并迭代 `vd.CATEGORY_FILES` —— 生成器的类别清单被改坏，校验器**跟着它一起少迭代一类**；
2. 校验器只从**源档**计数，从未核对**产物**；且生成器不删旧 md ⇒ 上一轮的 `narrative.md` 仍在，“漏写”被陈旧产物遮蔽。

**修法（三条，全部已落）**：
- 校验器的**期望值全部本地冻结**（`EXPECTED_FILES` / `EXPECTED_BY_CATEGORY` / `EXPECTED_CHARS_BY_CATEGORY` / `EXPECTED_TOTAL`），**不继承生成器**；
- 新增**产物覆盖判据** `coverage.all-elements-present-in-docs`：每条源元素取 `value` 首行前 60 字作指纹，必须在对应类别 md 里找得到；
- 生成器 `purge_stale()`：写前清掉输出目录旧 `.md` ⇒ **产物是源档的纯函数**。

⇒ 修后 m1 **KILLED**（红在 `coverage.*`）。同族地，m2 的杀点也从“仅计数”扩到 16/82 三条断言。

## 四 门禁

- `./mvnw clean verify` **rc=0**、**第 3 次尝试**：
  - attempt1（`logs/clean-verify.attempt1.log`）**FAILURE**——SpotBots 之外的真问题：`spotless:check` 判 `T12InfoRedlineTest` 的 Javadoc 折行不合 google-java-format 格式（**这是留档的失败轮，记之**）；
  - attempt2（`logs/clean-verify.attempt2.log`，md5 `8b8e768b…`）**GREEN**——`spotless:apply` 之后、**变异轮之前**；
  - ★ **最终绿轮 = `logs/clean-verify.after-mutants-GREEN.log`**（md5 `b8e20ccf…`，rc=0，**变异轮之后的复跑**）。
- **8/8 `SUCCESS [`**（`SimulatorMosire` / `UtilSimos` / `MapSimos` / `SocialSimos` / `UnitSimos` / `CoreSimos` / `SDSimos` / `SimosApp`）。
- 用例总数**现场重算**（只取模块汇总行，`paste -sd+ | bc`）：**1358** = `170 / 368 / 45 / 259 / 178 / 129 / 209`。
- **增量干净**：T11 终态 = `170/368/45/259/178/129/207`（1356）⇒ **app 207→209 = +2**（= `T12InfoRedlineTest` 2 条），其余六个模块**逐字不变**。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**；前端 `[frontend-gate] OK tests=164 pass=164 fail=0`（**未改前端 ⇒ 与 T11 同值**）。
- `tools/**` **不入 reactor** ⇒ 导出器/校验器的判据由 `check_v17levant_docs.py` 自带（rc=0，36 PASS / 0 FAIL，`logs/checker.txt`）。

## 五 变异（装置 `mutants/mut-run.sh`，九道门禁）

| 轮 | 靶子 | 结局 | 红点（被保护断言） |
|---|---|---|---|
| m1 | 生成器`CATEGORY_FILES` 删掉 `narrative` 一行 | **KILLED** | checker `coverage.all-elements-present-in-docs — 缺失: {'narrative': 1}` |
| m2 | `named_vs_noise` 把 98 个全当“有名” | **KILLED** | checker `map.all-mapped-noise-regions-in-noise-section` + `map.noise-not-marked-named` + `readme.states-16-82` |
| m3 | `T12InfoRedlineTest` 把一条文档**录进 Info**（`info.put(...)` 后再创世） | **KILLED** | `richWorldCarriesNoInfoAndTheWorldIsNotEmpty:82` `[C26：全局 Info 段（InfoSystem）一条都没有]` |
| m4 | 生成器**真引入 Info 符号**（`from io.mosire.simos.util.info import InfoSystem`） | **KILLED** | `docsAndRichWorldHaveNoInfoWritePath:159` `[★ 红线：…不得出现 Info 的符号名]` |
| m5 | 生成器往 docs 目录多写一个 **非 md** 文件（`leak.txt`） | **KILLED** | checker `docs.only-md — 非 md 条目: ['leak.txt']` |
| m6 | `_elem_body` **只写标题不写正文**（值缺失） | **KILLED** | checker `coverage.all-elements-present-in-docs — 缺失: 6 类全部` |

**★ 6 变异体 / 6 KILLED / 0 SURVIVED / 0 VOID**，九道门禁逐轮满足：`orig_md5 != mutant_md5`、`COMPILATION ERROR=0`、`restored_md5 == orig_md5`、`round_done=OK`。

### ★ 装置的两处如实记录（都是本题的教训）

1. **m4 首轮 VOID/存活（等价）**：m4 最初写成 `_INFO_WRITER = "InfoSystem"`（**字符串字面量**）。AST 判据（正确的）把它当**文本**、不当符号 ⇒ 存活。**这不是护栏失效，是变异体选错了形态**——它没有引用任何 Info 符号。**改写为真 `import ... import InfoSystem` 后 KILLED**。⇒ 记：**结构性判据的变异体必须落在“符号”而非“字符串”上**。
2. **m5→m6 的跨轮污染（已修）**：m5 写的 `leak.txt` 在轮末**未被还原**（还原只覆盖 `.md`）⇒ m6 首轮**假红**在 `docs.only-md`。已在 `mut-run.sh` 的 `--regen` 段补 `find … ! -name '*.md' -delete`，并**重跑 m5/m6 于干净世界**（m6 现仅红在 `coverage.*`）。⇒ 与 CLAUDE.md「装置产物自己带状态」同族。
3. **md5 是判据、记忆不是**：m3 重跑后我一度以为 `restored_md5` 与“pristine”不符——实为**我记的是 spotless 之前的旧 md5**；日志里 `orig_md5 == restored_md5 == 59772b76…` 逐字节相同。当场判清靠的是**回文件比 md5**，不是记忆。

## 六 我未能核实的

1. **产出文档的“可读性”未做人工评审**：结构（索引 + 分节 + 导航）与内容完整性由脚本断言保证，但“另一个 Agent 读起来是否顺”**未由任何自动化判据覆盖**（无 LLM 读者回归）。
2. **`caches/` 决策原文未纳入**：spec §五.2 说 `~/DevMosire/testspace/caches/*.json`（决策原文）可作**交叉来源**；计划 §T12 的“文件清单”**只列 6 类 checkpoint**、**未列 `caches/`** ⇒ 按计划**不纳入**（不自扩大范围）。**若用户想要，是 T12 之外的一件新事。**
3. **`map.md` 的“7 个噪声区域无 map 条目”未与 spec 逐字对拍**：实测 82 个噪声区域里只有 **75 个**有 `map` 模板条目，另 7 个（`东境总督区`/`云松谷`/`灰角伯国`/`瓦伦狄乌斯本土`/`石桥自由市`/`苍崖领`/`铁峡伯国`）**连 map 条目都没有**。spec §五.4 只写“其余 82 个只有模板化短条目”，**未区分这 7 个**。已在文档与校验器**如实标注**（`map.noise-without-entry-count == 7`），但 **spec 正文未改**（不越界改 spec）。
4. **跨 JVM 字节稳定性未测**：同机 Python 下产物逐字节稳定（重跑与 pristine 逐值相同）已证；跨解释器/哈希盐未测。
5. **`info-probe.json` 是“测试落盘”而非“真进程观测”**：runtime 不变量由 `T12InfoRedlineTest`（真 `CoreSimos` + 真 codec）实测并写盘，**没有起 `ShellMain` 真进程**（T11 已用 `--store` 做过进程级 overview 读回；本任务的 Info 面在同一 `Replay` 路径上，未重复起进程）。
6. **`SdInfoEntry` 侧只证“未新增”**：`sdInfoEntryCount==0` 证明**没有任何一条**被写入；`PutInfo` 命令族本身存在（SDSimos A5），本任务**只是没有用它**——未构造“若用了会怎样”的反事实。

## 七 与既有约定的关系

- ★ **`docs/worlds/v17levant/` 直接复用主检出的 spec 名**：spec §〇.1 **D11 已写明**目录 `docs/worlds/v17levant/`（★ **`logdemo` 是控制器早期记错的旧名，spec 里已更正，不存在“需要更新 D11”的问题**）。
- **不越界**：未做 T13、未改 spec/计划正文、未动别的 worktree、未 `git add -A`、未 `mvn install`、未 kill 5818。
- **同文件串行**：`tools/**` 与 `docs/worlds/**` 是 T12 独占的新面；`simos-app` 只**新增**一个测试文件，未改既有生产/测试字节（门禁 delta 只 +2 可证）。
- **`tools/**` 不入 reactor**：导出器/校验器的判据自带（含行为自证：m1/m2/m5/m6 的 checker 红点即其判别力证明）。
- ★ **红线（用户硬要求）兑现**：**绝不进 `InfoSystem` / `SdInfoEntry`**——① runtime 不变量（m3 可杀）② 结构性无写路径（m4 可杀）③ 产出目录只有 `.md`（m5 可杀）④ 资源信封 `info` 空表。**四层，各有变异体。**

## 八 证据索引

| 文件 | 内容 |
|---|---|
| `logs/checker.txt` | 36 断言全 PASS（含 16/82、覆盖、红线） |
| `logs/info-probe.json` | runtime Info 不变量实测落盘（`infoEmpty`/`bySubject`/`sdInfoEntryCount`/`hexCount=59223`） |
| `logs/content-spotcheck.txt` | 产出清单 + md5 + 非 md 为空 + 关键内容片段 |
| `logs/clean-verify.attempt1.log` | 留档的 **FAILURE** 轮（spotless 格式） |
| `logs/clean-verify.attempt2.log` | 变异前 GREEN（md5 `8b8e768b…`） |
| **`logs/clean-verify.after-mutants-GREEN.log`** | ★ **最终绿轮**（md5 `b8e20ccf…`） |
| `logs/verify-rc-final.txt` / `logs/recomputed.txt` / `logs/module-*-lines.txt` | rc / 现场重算 1358 / 模块与逐模块数 |
| `mutants/m1..m6.*` + `mutants/pristine*` + `mutants/mut-run.sh` + `mutants/logs/*.log` | 6 变异体逐轮日志（九道门禁自记） |
