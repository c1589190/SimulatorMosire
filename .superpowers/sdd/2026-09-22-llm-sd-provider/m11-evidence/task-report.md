# M11 / llm-sd-provider —— sd 侧「决策人绑定 LLM Provider」任务报告

> 分支 `llm/sd-provider`（worktree `.claude/worktrees/llmsd`），基线 `dfe74b9`。
> 本任务**只做 sd 领域这一半**：世界事实「哪个决策人绑定哪个 provider」。**不碰** LLM 客户端 / provider 配置格式 / 注册表（归 AgentLib）。

---

## §〇 边界与范围（守住的线）

- 交付物：`DecisionMaker.providerId`（`Optional<String>`，不透明 id）+ 命令 `sd.SetDecisionMakerProvider` + 回放语义 + 往返不变式 + catalog/载荷 + 变异。
- ★ **sd 里不出现** `baseUrl` / `model` / `key`（grep 可验；`providerId` 只是**字符串 id**）。provider 配置格式与生命周期归 AgentLib。
- ★ **没有**实现 `LlmProviderRegistry` / `HttpLlmClient` / provider 配置页（那是上一个 agent 的 WIP 里混进来的、归 AgentLib 的部分，**未带过来**）。
- 参考草稿：`git show bcae097:simos-sd/src/main/java/io/mosire/simos/sd/spi/SetDecisionMakerProviderHandler.java` 等——作为**参考**读，其 app 侧造轮子部分**丢弃**。

---

## §一 改了什么

| 文件 | 改动 |
|---|---|
| `simos-sd/.../model/DecisionMaker.java` | 加第 6 组件 `Optional<String> providerId`（+ 5 参重载 = 未绑定）+ 构造期校验（null / 空白拒）+ 类注写清**回放语义** |
| `simos-sd/.../spi/SetDecisionMakerProviderHandler.java` | **新**，命令 `sd.SetDecisionMakerProvider`：dm 存在 + providerId 非空白；**不校验 provider 存在性** |
| `simos-sd/.../spi/SetViewScopeHandler.java` | 重建 DecisionMaker 时**带回** `existing.providerId()`（否则配权静默丢绑定） |
| `simos-app/.../Shell.java` | 注册新 handler（进 late 列表，与 `SetViewScope` 同批） |
| `simos-app/.../tools/read/CatalogTool.java` | `PAYLOAD_HINTS` 补 `sd.SetDecisionMakerProvider`（缺项构造期抛 ⇒ 不补 app 起不来） |
| `simos-app/.../gui/ApiViews.java` | 决策人只读视图补 `providerId`（null = 未绑定，**不拿空串顶替**） |
| 测试 | `SetDecisionMakerProviderHandlerTest`（新，7 条）/ `SdProviderBindingEndToEndTest`（新，4 条）/ `SdRoundTripTest`(+1) / `SdCodecTest`(+1) / `SdDecisionMakerApiTest`(+1) / `SdFixtures.boundDecisionMaker` / `McpCoverageTest`、`SimosToolsTest`（catalog 42→43、载荷、head 43→44/44→45） |

★ 铁律 5 的联动：`DecisionMaker` 是 `SdState`/`SdChangeSet` 的**组件值**，不是 SdState 的新组件 ⇒ `SdState`/`SdChangeSet`/`SdCodec` **结构不变**（变更集仍是 10 组件、反射枚举仍绿），新字段由 record `equals` + `FieldDelta` 逐字段带过。

---

## §二 回放语义（写清 + 实现）

- `providerId` 是**世界事实**：随 revision 落盘、可回放、可回退分岔（`SdProviderBindingEndToEndTest.bindingLandsARevisionAndSurvivesReplay` 逐值证）。
- **provider 的存在性不是世界事实**：旧 revision 绑定的 provider 可能在新环境被删/改名。
  ⇒ sd **命令期不校验存在性**、**重放期不兜底**；存在性由**使用时刻**的 app 层解析 **fail-closed**（解析不到就明确报错/既定降级，**绝不静默换 provider、绝不当成"未绑定"**）。
- sd 侧的判据：`acceptsAProviderIdThatDoesNotExistAnywhereBecauseExistenceIsNotSdsConcern`（绑一个从未注册的 id 必须被接受）+ E2E `bindingAnUnregisteredProviderIsAllowedBecauseExistenceIsResolvedAtUseTime`。若有人把存在性校验塞回 sd，这两条红。
- ★ **本任务未实现那个 use-time 解析器**（`LlmProviderResolver`，属 AgentLib/app 对接层，见 `docs/superpowers/specs/2026-09-22-agentlib-llm-requirements.md` §三）。本任务只保证：**sd 侧不拦、不兜底、把事实如实落盘**。

---

## §三 往返不变式（带判据）

- **变更集往返**（内存）：`SdRoundTripTest.boundProviderSurvivesTheChangeSetRoundTrip`——`apply(between(base,target),base).equals(target)`，且目标态里**被改的那个 dm 本身带 providerId**（只测未绑定看不见新字段）。
- **JSON 往返**（线格式）：`SdCodecTest.boundProviderSurvivesTheJsonRoundTrip`——`decode(encode(snapshot)).equals(snapshot)` + 字节里含 `p-json`。
- 既有 `SdRoundTripTest.everyStateComponentParticipatesInTheChangeSet` / `fullFixtureRoundTripsThroughTheChangeSet` / `SdCodecTest.changeSetRoundTripsAllFourDeltaVariants` 未改，仍绿（新字段不破坏铁律 5）。

---

## §四 catalog / 载荷 / 覆盖率

- `CatalogTool.PAYLOAD_HINTS` 补 `sd.SetDecisionMakerProvider`（**构造期缺项即抛**的既有护栏，不补则 Shell 起不来——unit-ext T10 的教训）。
- `McpCoverageTest`：EXPECTED 42→43、新增最小载荷、`head 43→44`、advance 44/45。
- `SimosToolsTest`：EXPECTED 42→43、`catalogCoversEveryCommandHandlerImplementation` `hasSize(42→43)`（强判据：catalog 集合 == 全仓 43 个实现的 `type()` 集合）。

---

## §五 门禁（★ 最终绿轮 = `m11-evidence/logs/final-green-verify.log`，md5 `2696f48b8fd0f602a7993a6236b3db10`）

- `./mvnw clean verify`：**rc=0**、**8/8 `SUCCESS [`**（`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` + parent）、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=188 pass=188 fail=0`。
- **模块判据（现场取汇总行）**：`170/369/45/259/179/138/216 = 1376`。
- **基线现场重算**（在 detached worktree `dfe74b9` 上真跑，不是抄文档）：`170/369/45/259/179/129/211 = 1362`（`logs/baseline-dfe74b9.txt`）。
  - **delta**：`sd +9`（handler 测试 7 + 往返 1 + codec 1）、`app +5`（E2E 4 + 决策人 API 1）；其余模块**逐值不变**。
- **尝试次数**：第 1 次即绿；变异轮**之后**在冻结字节上复跑（attempt 2）仍绿，本文件即这一轮。
- ★ **冻结字节自证**：5 个被改的生产文件 md5（记在 `logs/FINAL-GREEN.txt`）与变异装置每轮记录的 `orig_md5` **逐字节相同** ⇒ 最终绿轮跑的就是当前字节。

---

## §六 变异（全部 KILLED / 0 存活）

**本任务新增 7 轮**（`m11-evidence/mutants/`，每轮：字节确实不同 → `COMPILATION ERROR=0` → 红点落被保护断言 → `cp` 逐字节还原 + 复核 md5）：

| 轮 | 靶 | 红点（被保护断言） |
|---|---|---|
| `m1-binding-not-written` | handler 把 `updated` 换回 `existing`（绑定不落状态） | `...AsANonEmptyChangeSet:25 [绑定必须真的进变更集]` |
| `m1b-...-e2e` | 同上，app E2E | `bindingLandsARevisionAndSurvivesReplay:75` |
| `m2-viewscope-drops-provider` | `SetViewScopeHandler` 用 5 参重载（重建丢 providerId） | `settingViewScopeAfterBindingKeepsTheProvider:101 [配权不得丢 providerId]` |
| `m2b-...-e2e` | 同上，app E2E | `...KeepsTheProvider:117 [配权不得丢绑定]` |
| `m3-handler-not-registered` | Shell 不注册新 handler | `catalogCoversEveryCommandHandlerImplementation:242` + `catalogListsExactlyTheRegisteredCommandTypes:218` |
| `m4-view-drops-provider` | `ApiViews` 不吐 `providerId` | `detailMatchesReplayedSdStateFieldByField:267` + `unboundDecisionMakerReportsNullProviderIdNotAnEmptyString:279` |
| `m5-json-ignore-provider` | `DecisionMaker.providerId` 加 `@JsonIgnore`（**JSON 往返丢**） | `boundProviderSurvivesTheJsonRoundTrip:60 [绑定必须真的写进字节]` |

★ **m5 与 m2 各自独立**：m2 杀在**重建路径**，m5 杀在**线格式**；`SdRoundTripTest`（内存）在 m5 下**不红**——两层往返各自有靶子，未互相顶替。

**裁定 42 重跑（改既有文件 ⇒ 旧变异轮从新字节重派生，`m11-evidence/rederive/`，9 轮全 KILLED）**：

| 文件 | 重派生轮 | 见证与红点 |
|---|---|---|
| `Shell.java` | `r-t9m1` `r-t9m2` | `SimosToolsTest`：catalog == 实现 43（两条 catalog 断言） |
| `Shell.java` | `r-t9m3` | `McpCoverageTest`：`unit.PlanSparseRoute 必须经 MCP 可提交并生效`（REJECTED） |
| `CatalogTool.java` | `r-t10m2` | `SimosToolsTest`：构造期抛 `未登记载荷提示（PAYLOAD_HINTS）: [unit.ApplyCasualties]` |
| `ApiViews.java` | `r-t11-j1` `r-t11-j2` `r-t13-m2b` | `MapOverviewBlocksTest.overviewEmitsDeterministicBlockPolygonsAndNoPerHexTerrain` / `...StaysByteIdentical` |
| `ApiViews.java` | `r-t13-m2` | `MapOverviewBlocksTest.blockOrderIsCanonicalRegardlessOfStateInsertionOrder` |
| `ApiViews.java` | `r-t13-m1` | `MapOverviewBlocksTest...:61 [外圈块带一个洞环 ⇒ 2 条环]` |

★ **`r-t13-m1` 是 py 重派生（不是 patch）**：已归档的 m1 变异体基于旧的**对象顶点**视图（`List<List<Map>>`），而当前字节已换成**整数标签**（`List<List<Integer>>`）⇒ patch 的锚点已不在；按同语义（只取首环、丢洞环）在新字节上重派生。这是"旧变异体的对象已被改掉"的**恰好一例**。
★ **诚实披露**：`rederive/logs/r-t9m1|r-t9m2|r-t9m3.self` 的**首行**是一条 `VOID no-byte-change`——那是我第一次调用时把相对路径传给装置的**作废轮**（`diff` 找不到文件、未改字节）；有效轮记在其后一块。留档不删。

---

## §七 诚实披露 / 与派单文字的偏差

1. ★ **`r-t10m2` 的红是"构造期抛"而非断言**：删 `unit.ApplyCasualties` 提示后 `Shell.start` 当场抛 `IllegalArgumentException`（这正是 T10-j 的 fail-fast），`SimosToolsTest` 在其 `@BeforeEach` 里 error。红点不在 `assertThat` 上——如实记，不修饰。
2. ★ **`m4` 的红是缺字段导致的 NPE**（`body.get("providerId")` 为 null），不是数值不等——仍是"字段必须存在"的判据被触发。
3. ★ **`m3` 与 `r-t9m1/2` 的 sd 模块轮会先红、reactor 短接**：`m1`/`m2` 的 app E2E 因此另起 `m1b`/`m2b` 单独跑（"没跑到"≠"没红"）。
4. ★ **`ApiViews` 的 `providerId` 暴露**：派单未明列 GUI 暴露，我判定它属"sd 世界事实的可观测面"（纯只读、不涉 LLM 配置），故保留并配了 `SdDecisionMakerApiTest` 断言。**可推翻**。
5. ★ **`providerId` 无"解绑"命令**（命令要求非空白 id）：派单只给"绑定"，未要求解绑；5 参重载 = 创建期未绑定，绑定后无路径清空。如实记。
6. ★ **未改 `SimosToolSource` 工具集**：新命令经通用写 `simos.command.submit` 可达（EXTERNAL ∪ GM 桶）；未加 GM 窄工具（派单未要求）。

---

## §八 我未能核实的

1. ★ **use-time fail-closed 未端到端验**：`LlmProviderResolver`（providerId→client，解析不到即报错）**不在本任务范围**（归 AgentLib/app），故"provider 不存在时**使用时刻**确实 fail-closed"这句话**只有设计文档与 sd 侧判据背书，没有一条运行期证据**。
2. ★ **真档未验**：所有用例跑在**合成夹具**（`SdFixtures`/`SdWorlds` 小世界），未在 `v17levant` / `test_integration` 等真档上验绑定与回放。
3. ★ **跨 JVM 字节稳定未验**：`SdCodecTest` 的 JSON 往返在同一次 JVM 内验；`providerId` 的 `Optional` 序列化形态未做跨 JVM 逐字节比对。
4. ★ **AgentLib 侧 provider id 的真实约定未核**：`providerId` 的命名/取值约定由 AgentLib 定义（见需求清单 A4），本任务只把它当不透明串；若 AgentLib 最终用 `ModelRoute.name` 之外的键，sd 侧**无需改**（不透明），但"id 从哪来"未核。
5. ★ **`@JsonIgnore` 变异（m5）只测到"该 record 组件加注解会被线格式尊重"**：我只证了 m5 一例的判别力，未穷举 `Optional` 其他写法（如自定义序列化器）下的往返。
6. ★ **`McpCoverageTest` 的 revision 号是**按"新增载荷放最后"推出来的**（不移动前面命令的 revision）；未逐条重算所有命令的落点，只断言了 head 总数与末段 advance/fork。

---

## §九 证据索引（`m11-evidence/`）

- `logs/FINAL-GREEN.txt` + `logs/final-green-verify.log`（★ 权威最终绿轮，md5 `2696f48b…`）
- `logs/baseline-dfe74b9.txt`（基线现场重算）
- `mutants/mut-round.sh` + `mutants/logs/m*.log` + `*.self`（新增 7 轮）
- `rederive-round.sh` + `rederive/logs/r-*.log` + `*.self` + `*.patch`（裁定 42 的 9 轮）
- 本报告 `task-report.md`
