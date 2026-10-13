# AgentLib Docs 系统 —— Simos 侧对接需求书（2026-10-13）

> **本文用途**：交给隔壁 AgentLib 项目组，作为他们设计/实现通用 **Docs 系统**时的**需求输入**。
> **本文不是设计**：不规定 AgentLib 内部怎么实现，只写 **Simos 这边会调用的地方、必须满足什么、不得破坏什么、以及必须由用户裁定的开放问题**。
> **本文自足**：每条需求都带 Simos 侧的证据（`file:line` + 真实命令/输出），读者不需要读 Simos 源码即可判断该不该做、做成什么样。
> **口径优先级**（Simos 仓规矩）：用户原话 > 用户设计原稿 > 最新落盘文档 > `AGENTS.md`。本文中**用户裁定**各条以用户 2026-10-13 会话原话为准。
> **基线**：Simos `main` @ `9c841e06`（工作树干净）。本文只读调查 + 一次真机冒烟，**未跑 Maven**。

---

## 0. 需求是怎么来的（用户裁定逐条）

| # | 用户原话（2026-10-13） | 本文里的落点 |
|---|---|---|
| U1 | 「现有项目的 Skill 等直接取缔，Skills/Docs 系统我想了想其实是一个，Skill 无非是有更多触发规则的 Docs」 | §1.2、§2（R-Skill0） |
| U2 | 「我们暂时不准备做 Skill，只维护一个通用文档系统，最多加个自动注入的简单 Agents.md」 | §2 R-B1、R-B2、R-B3 |
| U3 | 「GM 肯定啥都能看」 | §2 R-V4 |
| U4 | 「单个世界内的决策人也许需要一个在世界内一同储存的文档，注意应当走文件夹而不是 db」 | §1.1、§2 R-S1、R-S4 |
| U5 | 「Docs 系统必须维护一个自定义目录，这个自定义程度甚至要允许 Simos 这边自动注入 worldid 字段，具体工具封装这边都可以谈」 | §2 R-C1、R-C2、R-C3 |

★ **U1 撤销一条更早的用户原话**，必须显式记录，否则后续子 Agent 会拿旧原话把派单打回来：
`SkillLibrary` 类注（`simos-app/src/main/java/io/mosire/simos/app/skill/SkillLibrary.java:31-32`）逐字收着
「最好搞成存在外部方便读取、写入、修改，还要兼容其他 外部 Agent 方便进行 GM Agent 的通用修改」。
⇒ **用户 2026-10-13 裁定取代该条**：不再维护独立的 Skill 文件库，改由通用 Docs 系统承担。

---

## 1. 现状事实（回代码核过；这是需求的地基）

### 1.1 ★ 现在有两层 "info"，其中一层是死的

| 层 | 类型 | 生产路径现状 |
|---|---|---|
| **世界级** `SimulationState.info` | `InfoSystem` 接口（`get`/`put` 两个方法），住在 `simos-util` | ★ **生产代码零写入点**：`git grep -n "info()\.put" -- 'simos-*/src/main/java/**'` 只命中一条无关的 `view.put(...)`；唯一实现在**测试**里用（`InMemoryInfoSystem` 仅出现在 `simos-actor/src/test/**`）。仅被 `CheckpointEncoder.java:68` 序列化成一个 `info` 段写进 checkpoint 信封 |
| **模块级** `GaState.info` | `Map<String, List<SdInfoEntry>>`（地址字符串 → 条目列表） | **文档真正住在这里**：写 `simos-ga/.../ga/spi/PutInfoHandler.java:128-181`；读 `simos-app/.../app/query/RedactingQueryService.java:317/386` |

⇒ **"文档住在世界状态里"这句只对模块级那一层成立。** 世界级 `InfoSystem` 是一个**没接线的空壳**。

### 1.2 现有两条机制的对比（这是要合并的两半）

| | **Skills**（本次取缔） | **Docs**（要保留并扩展） |
|---|---|---|
| 存储 | 外部 Markdown 文件：仓种子 `config/skills/` + store 覆盖 `<storeDir>/skills/` | `GaState.info` → 落 `simos.db` 的 revision |
| 定位 | **运行环境**，类注逐字：「**不在 revision 内**……改 skill 会改变模型行为，但**不留下世界版本锚**」 | **世界事实**，走 `Command → ChangeSet → Revision` |
| 索引 | 无（按 id 取一篇） | 地址 + `tick`（可按时段查） |
| 写 | **零写方法**（`SkillLibrary` 只有 `open`/`list`/`find`） | `ga.PutInfo`（GM 侧 UNRESTRICTED） |
| 可见性 | "能调到工具就能看"（全局库，不按决策人过滤） | `tags ∪ affiliations`，单一判据 |
| 读工具 | `simos.skill`（`roles()={DM,GM}`） | `sd.DecisionDocs`（{DM}）· `sd.DecisionResults`（{DM}）· `simos.sd.reports`（{DM,GM}） |
| 热更 | 按 mtime，改文件不用重启；坏文件保留上一版 | 随 revision 落盘、可回放 |

### 1.3 一条文档现在的完整字段（`SdInfoEntry`）

`simos-ga/src/main/java/io/mosire/simos/ga/model/SdInfoEntry.java`：

```java
public record SdInfoEntry(
    SdInfoId id,                              // 身份
    long tick,                                // 时刻
    Set<DecisionMakerId> tags,                // 可见性轴 1：显式指派
    Set<String> affiliations,                 // 可见性轴 2：按归属自动
    String key,                               // 条目键
    Object value,                             // 正文（裸 Object，不解析不改写）
    Optional<String> note,                    // 备注
    RevisionId at,                            // ★ 它是哪条 revision 落的
    Optional<DirectiveId> sourceDirective,    // 来源令（现在恒空）
    Optional<AdjudicationStatus> adjudicationStatus)  // EFFECTIVE / VOIDED（现在恒空）
```

### 1.4 身份规则（`SdInfoIds.synthesize`）

```java
public static SdInfoId synthesize(String canonicalAddress, int ordinal) {
  return SdInfoId.parse(canonicalAddress + "#" + ordinal);
}
```
- **id = canonical 地址 + `#` + 追加序号**；`ordinal` 是"该地址下第几条"，靠 `GaState.info` 那个 `List` 现算。
- 类注逐字：「**不自增、不用随机 UUID**：id 是 (canonical 地址, 追加序号) 或载荷给定的**纯函数**，同一 base + 同一载荷恒得同一 id ⇒ 可重放（铁律 2）」。

### 1.5 读口形状（唯一判据所在）

```java
// simos-app/.../app/query/RedactingQueryService.java:374
public List<Map<String, Object>> docs(
    DecisionMakerId actor, QueryTarget target, DecisionResultWindow window)
```
- `QueryTarget` = `record QueryTarget(BranchId branch, RevisionId revision)`（`QueryService.java:189`）。
- `DecisionResultWindow(Long tick, Long fromTick, Long toTick, int limit)`（`RedactingQueryService.java:266`）；构造期具名拒：`limit` 必填 > 0、`tick` 与 `fromTick/toTick` **互斥**、`fromTick ≤ toTick`。
- **排序**：`tick` 降序，同 tick 按 `entry.id()` 升序，**先排后截**（`:397-400`）。
- **可见性判据**（类注 `:355` 附近逐字）：
  - 支 1：`entry.tags()` 含该 actor；
  - 支 2：`entry.affiliations()` 含该 actor 的归属（同 nation/army 的决策人自动可见）；
  - ★「**两支都不命中 ⇒ 不可见**（fail-closed）。actor 在自己的 id 在世界里**解不出**（被删 / 身份与状态不同源）⇒ **只走 tags**（**不**把"不知道你是谁"读成"按归属放行"）」；
  - ★「判据**只此一处**：GUI 的文档子页与决策人读工具都走本方法，各写一份时两边都不报错、只会漂移」。
- **返回**：`{docId, id, tick, tags(升序), affiliations(按 kind:id 升序), key, value(原样 Object), note?, at{branch, revision}}`；★「`value` **不解析/不改写**」；★「空结果返回空列表，**不抛**」。

### 1.6 地址约定（唯一拼写点，`simos-app/.../app/docs/DecisionDoc.java`）

- 文档地址 = `ga:doc.<docId>`（`ADDRESS_PREFIX`，`:35`），条目 `key` 固定为 `"doc"`（`:38`）。
- 报告地址 = `sd:doc.report-<sender>-<tick>-<seq>`（`SimosSdReportTool.java:46`）。
- ★ **docId 必须是裸词**（不含 `.` / `:` / 空白）——类注逐字：「docId 里若带这些字符会把 canonical 撕成别的段形状，于是**写进去的地址与读出来的"是不是文档"判据不再一致**」，类注称之为「文档静默消失」。写入侧 `addressOf` 即拒（`:47-58`）。
- ★ 读侧判据用**解析出的 `kind`**、不用字符串前缀（规避 `ga:docx.foo` 这类前缀假阳性，`:26-28`）。

### 1.7 两个"活的"依赖（搬出 revision 后会断，必须显式处理）

1. **`at(RevisionId)`**：`PutInfoHandler` 写入时注入"这是哪条 revision 落的"；`VoidAdjudicationTool.java:242-262` 读它做留痕。
2. **作废语义是"再追加一条"，不是删除**：`VoidAdjudicationTool.voided(entry)` 同一条记录只换 `adjudicationStatus=VOIDED` 后**追加**到同一地址（类注逐字：「**显式改写**：那条裁决记录翻成 VOIDED（**不删**——留痕）」）。

### 1.8 GM 与决策人的工具面现状

- 决策人可见工具 **43 条**（权威清单住在测试 `simos-app/src/test/.../access/DecisionFaceGolden.java:34`；生产零清单，装配期由 `ToolRoleAudit.requireFaces` 双向钉死）。
- GM 拥有全部 161 条，其中写任意文本的口是 `ga.PutInfo`（`SdPutInfoTool.java:24`，`roles()` = `{GM}`）。
- 决策人能写"世界内文本"的只有两条，**都不是通用文档写口**：`simos.sd.packet.intent`（写决策意图）、`simos.sd.report`（写 `sd:doc.report-*`）。

---

## 2. 需求

> 记号：**R-xxx** = 必须满足；**N-xxx** = 不得破坏；**Q-xxx** = 开放问题（需用户/双方裁定）。
> 「用户裁定」= 用户 2026-10-13 原话；「实测纪律」= Simos 仓踩过坑后写死的规矩。

### 2.A 存储与归属

| # | 需求 | 依据 |
|---|---|---|
| **R-S1** | 文档库**跟着世界走**：与世界的 store 同处一地（同一运行目录内），且**走文件夹而不是 db** | 用户裁定 U4 |
| **R-S2** | **根目录可配置**：调用方（Simos）能声明文档库放在哪 | 用户裁定 U5（"自定义目录"） |
| **R-S3** | **目录布局可自定义**：分片方式、文件名/子目录结构由调用方声明，Docs 系统按之落盘与读回 | 用户裁定 U5 |
| **R-S4** | **不在世界 revision 内**（由 U4"不走 db"推得）：文档不参与 `Command → ChangeSet → Revision`，不随 checkpoint 落盘 | 用户裁定 U4 + 推论 |
| **R-S5** | 但**世界坐标必须能注入**：写入时调用方可带上 `branch` / `revision` / `tick`，且读回时原样返回 | §1.7 的 `at(RevisionId)` 是活的依赖 |
| **R-S6** | **追加序号由存储维护**：调用方不传序号也能拿到 `id`；且"同一 base + 同一载荷 ⇒ 同一 id" | §1.4 实测（铁律 2 可重放） |

### 2.B Documents 与"自动注入的 Agents.md"

| # | 需求 | 依据 |
|---|---|---|
| **R-B1** | 本期**不做 Skill 系统**：不存在"带触发规则的特殊文档" | 用户裁定 U2（U1 的"Skill 是有更多触发规则的 Docs"是**将来**的方向，本期不实现） |
| **R-B2** | 维护**一个通用文档系统**即可，不需要技能库那套 `id/title/version` 头 | 用户裁定 U2 |
| **R-B3** | **支持"自动注入的简单 Agents.md"**：能在开跑前把一份约定文件的内容**自动注入**给 agent（≥ 注入给决策人；是否也注入 GM 由 Q-B1 定） | 用户裁定 U2 |

### 2.C 自定义字段（用户点名的 `worldid`）

| # | 需求 | 依据 |
|---|---|---|
| **R-C1** | 文档条目必须有**开放的自定义字段区**：调用方在写入时塞任意字段，读回时原样取到 | 用户裁定 U5 |
| **R-C2** | 注入点必须在**调用方（写入侧）**，不能是"存储内部知道这个值" —— 因为只有 Simos 知道 `worldId` | 实测：`worldId` 是 `ShellConfig.java:48` 的字段，**只在空库创世时决定用哪个生成器**；`StateMeta` 只有 `{ref, timestamp}`，**世界状态里没有 worldId** |
| **R-C3** | 自定义字段的类型能力要够用：Simos 需要塞**标量**（`worldid` 字符串）与**结构化小对象**（如 `{branch, revision}`） | 用户裁定 U5 + §1.7 |
| **R-C4** | 自定义字段**不得参与**身份、可见性、排序这三件核心语义（避免"第二处拼写点"） | 见 §5 风险 |

### 2.D 可见性

| # | 需求 | 依据 |
|---|---|---|
| **R-V1** | **三支判据，全 fail-closed**：① `tags` 显式指派 ② `affiliations` 按归属 ③ **owner-only（单决策人私有）** | ①② 已在 §1.5；③ 来自用户 2026-10-13 早前那条需求（「单个决策人也应该有能力通过给定接口维护一个其他决策人不可读的文档系统」） |
| **R-V2** | ★ **身份解不出时，不许把"不知道你是谁"读成"按归属放行"**（只能走 tags） | §1.5 实测纪律 |
| **R-V3** | **可见性判据只许有一处**（GUI 文档子页与决策人读工具走同一个判据） | §1.5 实测纪律（"各写一份时两边都不报错、只会漂移"） |
| **R-V4** | ★ **GM 无条件可见全部文档**，且**这一支不进可见性判据**（不是"给 GM 挂一个匹配所有文档的标签"） | 用户裁定 U3 |
| **R-V5** | **无主 ≠ 大家都能看**（判据不命中就是不可见） | `DecisionDocsTool` 描述逐字 |

### 2.E 查询语义

| # | 需求 | 依据 |
|---|---|---|
| **R-Q1** | **按时刻读**：给 `tick`（精确）或 `[fromTick, toTick]`（闭区间）；两者**互斥** | §1.5 |
| **R-Q2** | **限流**：`limit` 必填且 > 0；**先排后截** | §1.5 |
| **R-Q3** | **顺序确定**：`tick` 降序，同 `tick` 按**稳定有序列**升序 | §1.5（同 tick 现按 `id` 升序，而 `id` 嵌着追加序号 ⇒ 有序列不能丢） |
| **R-Q4** | **查询不抛异常**：空结果给空集/空清单 | §1.5 逐字 |
| **R-Q5** | 能**按 docId 精确取一篇**；"取不到"与"不可见"返回**同一个回答**（不泄露存在性） | `DecisionDocsTool` 描述逐字 |
| **R-Q6** | 正文 `value` **原样进出、不解析不改写** | §1.5 逐字 |

### 2.F 写入语义

| # | 需求 | 依据 |
|---|---|---|
| **R-W1** | **append-only**：同 (docId, key) 再写是**追加一条**，不是覆盖；**永不删除** | §1.7（作废是"再追加一条 VOIDED"） |
| **R-W2** | **作废是通用需求**：核心应内建"这条被作废了"的标记能力，不要每家用自定义字段各造一份 | §1.7 + §3 字段分档 |
| **R-W3** | **id 唯一性检查**：写一条与既有条目撞 id 要具名拒 | `PutInfoHandler` 类注逐字（"`id` 空白或与既有条目**撞 id**"） |
| **R-W4** | **身份与地址的合法性在写入侧就拒**（fail-closed），不要留到读侧表现为"文档静默消失" | §1.6 实测 |
| **R-W5** | **并发安全**：同一条文档的并发追加不许撕裂（Simos 侧一次可能并发多个决策人写文档） | 与 `ConversationStore` 同口径（AgentLib 既有约定："每次调用自身原子"） |

---

## 3. 字段分档（核心 / 可注入 / 载荷）—— 这是给 AgentLib 的 schema 输入

| 字段 | 现在 | 迁移后归属 | 理由 |
|---|---|---|---|
| `id`（身份） | `SdInfoEntry.id` | ★ **核心** | 身份与可重放由存储负责 |
| `tick`（时刻） | `SdInfoEntry.tick` | ★ **核心** | 按 tick 读/排序就是查询语义 |
| `tags` / `affiliations` / owner | 前两者在条目上 | ★ **核心** | 可见性判据由 Docs 系统实现 |
| **有序列**（同 tick 内排序键） | 隐含在 `id` 里（`#ordinal`） | ★ **核心** | R-Q3 依赖它 |
| **作废标记** | `adjudicationStatus`（EFFECTIVE/VOIDED） | ★ **核心**（R-W2） | 通用需求，防各家自造 |
| `key` + `value`（正文） | `SdInfoEntry.key` / `.value` | **Simos 载荷**（Docs 当不透明内容存） | R-Q6 |
| `note`（备注） | `SdInfoEntry.note` | **Simos 载荷** | 同上 |
| `at`（哪条 revision 落的） | `SdInfoEntry.at` | ★ **可注入**（R-S5） | §1.7 活依赖 |
| `worldid` | 无 | ★ **可注入**（R-C1/C2，用户点名） | 用户裁定 U5 |
| `branch` | 无 | ★ **可注入** | 与 `at` 同族 |
| `sourceDirective` | Optional，现在恒空 | **可注入**（不必进核心） | 现在没有非空用例；真非空时 `directiveId` → `Directive` 表可 join 回来源 |

**⇒ 核心 schema 最小集**：`id` · `tick` · `正文(key,value)` · `可见性(tags,affiliations,owner)` · `有序列` · `作废标记` · `自定义字段区`。

---

## 4. 归属划分（谈"工具封装"之前先定这个）

| 归 **AgentLib（Docs 系统）** | 归 **Simos** |
|---|---|
| 存储原语：追加 / 读 / 按 tick 查 | **目录布局的声明**（放哪、怎么分片）—— R-S2/S3 |
| 身份规则：`id` 派生、可重放、追加序号 | **自定义字段的内容**（worldid / branch / revision / 业务字段）—— R-C2 |
| 可见性判据（含 GM 无条件通过、owner-only、fail-closed） | **谁在什么时候写**（GM 工具 / DM 工具 / 启动注入） |
| 顺序契约（tick 降序 + 有序列升序，先排后截） | **正文语义**（docId 命名、正文是否为 JSON 字符串） |
| 限流 + "查询不抛异常" | **工具面的角色声明**（Simos 侧 `SimosTool.roles()` 仍是唯一拼写点） |
| 并发/原子性 | **审批挂哪条链**（Simos 侧按装配决定，不进接口） |

★ Simos 侧的工具契约现状（供对接参考）：工具必须 `implements SimosTool`，其 `roles()` 返回非空不可变集合，取值 `{GM}` / `{DM}` / `{GM,DM}`；装配期与桶逐条比对，不一致**当场抛**。类注明确「审批链按**装配**决定……工具只声明"谁能用"……把它写进接口 = 第二处真相」（`simos-ga-spi/.../spi/SimosTool.java:22-23`）。

---

## 5. ★ 一条必须守住的边界（Simos 侧最贵的教训）

"自定义程度"开得越大，越容易在 Simos 侧出现**第二处拼写点**。具体：

- 文档身份现在只有一处定义：`DecisionDoc`（`ga:doc.<docId>`，docId 裸词，写入侧拒、读侧解析 `kind`）。
- **若 Docs 系统也自带一套"文档身份"的拼法**，那么"一条文档叫什么"就有了两个拼写点 —— 而本仓已经为"第二个拼写点"付过代价（`docs/agents/10-九-实测失败形态.md` 有专节）。

⇒ **要求**：**"文档身份"（docId / id）的定义权必须只有一方。** 要么 Docs 系统定义、Simos 引用；要么 Simos 定义、Docs 系统当不透明字符串。**不能两边各定义一遍。**（归哪一方由 Q-I1 裁）

---

## 6. 开放问题（14 问 —— 隔壁交回来的东西必须能逐条回答）

| # | 问题 |
|---|---|
| **Q-S1** | 存储根在哪？**在不在世界 revision 内**？（按 U4 应为"在 store 目录内、不在 revision 内"） |
| **Q-S2** | 目录布局怎么让调用方声明？（根目录参数？分片函数？文件名模板？） |
| **Q-I1** | ★ 文档**身份**（docId / 稳定 id）由哪一方定义？派生规则是什么？**可不可重放**？追加序号谁维护？ |
| **Q-I2** | ★ **有序列**（同 tick 内排序键）是什么、怎么保证确定？ |
| **Q-Q1** | **按 tick 读**怎么给？`tick` 与 `[from,to]` 互斥这条契约保留吗？ |
| **Q-V1** | 可见性有哪几维？**owner-only（单决策人私有）怎么表达**？ |
| **Q-V2** | **GM 无条件可见**怎么实现（且不污染 tags）？ |
| **Q-V3** | 身份解不出时怎么 fail？（必须 fail-closed，不许按归属放行） |
| **Q-Q2** | 顺序与限流的契约是什么？（先排后截？） |
| **Q-W1** | 同一 (docId, key) 重复写是**追加**还是覆盖？（必须追加） |
| **Q-W2** | **写入通道**是什么？谁能写？权限/审批挂哪一层？ |
| **Q-W3** | 写入时能不能带**当前世界坐标**（branch / revision / tick）并原样读回？（不做则 §1.7 的 `at` 静默退化） |
| **Q-C1** | **自定义字段区**怎么给？（写入侧怎么传、读侧怎么取、类型自由度） |
| **Q-B1** | **自动注入 Agents.md** 走哪个口、在什么时机（装配前？每轮开跑前？）、注入给谁（只 DM？还是 DM+GM？） |

---

## 7. 附：本期**不在**本文范围内（Simos 侧另开单）

1. **取缔现有 Skill 的拆除清单**：`SkillLibrary` + `SkillTool` + `config/skills/` 4 篇种子 + `AppDecisionToolNames.skill()` + `DecisionAgentRunner:741` 的指针句 + `Shell:1112` 装配点，共 6 处，各自需要的替换物与验收判据。
2. **"Skill = 有触发规则的 Docs"的将来形态**（用户 U1 的方向）：触发规则由谁求值（文本 vs 结构化）、以及它与"紧急 tick / 监控指标"的关系。用户已定：紧急 tick 在 `util` 声明、覆盖所有包；监控指标归 `APP` 管理。
3. **决策人对 GM 推演的异议/确认轮**（另一条独立需求）：涉及 `PacketStatus` 是否加"可申诉"态、或把裁决拆两段；与本文无关。

---

## 8. 未核 / 如实记

1. **未跑 Maven**；本文一切测试计数与运行期数字均**未复核**（只做只读调查 + 一次真机冒烟）。
2. **未核** `sd:adjudication.*` 那批 INFO 条目是否也走 `DecisionDoc.docIdOf` 的判据（只核了 `ga:doc.*` 与 `sd:doc.report-*` 两条地址约定）。
3. **未核** `simos.sd.report` 的收件可见性判据与 `docs()` 是否**同一处**（只读到类注声称"可见性由上面那条判据承担"）。
4. **未核** GUI 文档子页对 `at{branch,revision}` 的实际消费点（只核了 `DecisionDocsTool` 的返回形状）。
5. **未核** 一个世界内**同时**存在多份文档库（多世界共用一个 store 根）是否会发生；本文按"一个 store 一份"写。
