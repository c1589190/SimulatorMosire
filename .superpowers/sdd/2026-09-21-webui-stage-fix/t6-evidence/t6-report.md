# T6 redaction 洞收口 —— 实现报告

> 分支 `wsf/t6`，worktree `.claude/worktrees/wsf-t6`，基线 = 当时 HEAD `c221736`（T5 合并后）。
> 计划：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` §T6；spec：`...-design.md` §六.6 / §七.6（`C28`/`C29`/`C30`）/ D9；research：`...-research.md` §C.7。
> ★ 只做 T6；未改 spec/计划正文；未越界做 T7+。

---

## §一 目标与结论

两个洞（research §C.7 实测）：

1. **`adjudicationDisclosure` 与 `redactedFields` 被解析、被存、却从未被应用**（只经 `ChannelAdmission.redactedBrief:61-81` 回显）。
2. **除 `/api/map/overview?as=` 与 `/api/units?as=` 外，所有读端点都不过 redaction**。

**结论**：两个洞**均已修好**，`C28`/`C29`/`C30` 逐条实测；未覆盖端点**逐一列出并 fail-closed**（§二.3）。

---

## §二 修法

### 二.1 洞 1：disclosure / redactedFields 真正生效

`RedactingQueryService`（`app/query/`）新增：

- **`applyRedactedFields(Object body, ViewScope scope)`**：按 `scope.redactedFields()` **递归按字段名剔除**（`Map` 键命中即整条去掉、`List` 逐项下钻、标量原样）；空 `redactedFields` ⇒ 原对象原样返回（不做无谓深拷贝）。`mapOverview` / `units` 出口也接上它。
- **`verdicts(DecisionMakerId, QueryTarget)`**：按 `scope.adjudicationDisclosure()` **三档**裁剪判决内容——
  - `FULL` ⇒ `{id, breakpoint, subject, atRevision, payload, meta}`；
  - `PERCEPTION_ONLY` ⇒ 只留**可观察项** `{id, breakpoint, subject, atRevision}`（去掉模型输出 `payload` 与 `meta`，二者是**不可感知的内部量**）；
  - `WITHHELD` ⇒ **空列表**（判决整条不出现，**不是空串**）。
  - `verdicts(QueryTarget)`（无 actor = GM/调试）⇒ `FULL`。判决按 `VerdictId` **字典序**（响应字节可复现）。
- **可见性谓词**：`seesHex` / `seesRegion` / `seesUnit`（`visibleUnits ∪ (seeOwnUnits ? 己方子树)`），给"按地址取单个实体"的读端点提供 **fail-closed 判据**（不可见 ⇒ 调用方折成 404，与"不存在"同形，不给存在性侧信道）。

### 二.2 洞 2：端点覆盖

`GuiServer.handleGet` 现在**先解析 `as=`**（一次），再逐端点分派：

| # | 读端点 | 带 `as=` 的处理 |
|---|---|---|
| 1 | `/api/state` | 递归剔除 `redactedFields` |
| 2 | `/api/resolve` | 递归剔除 `redactedFields` |
| 3 | `/api/facets` | 递归剔除 `redactedFields` |
| 4 | `/api/map/overview` | 可见性（hex/region/city）+ `redactedFields` |
| 5 | `/api/map/hex` | 可见性（hex 不可见 ⇒ **404**）+ `redactedFields` |
| 6 | `/api/map/region/{id}` | 可见性（region 不可见 ⇒ **404**）+ `redactedFields` ★ **计划 §T6 清单未列，T6 补接**（否则"hex 关了、region 还开着"是明显漏洞） |
| 7 | `/api/timeline` | 递归剔除 `redactedFields` |
| 8 | `/api/units` | 可见性（unit）+ `redactedFields` |
| 9 | `/api/unit/{id}` | 可见性（unit 不可见 ⇒ **404**）+ `redactedFields` |
| 10 | `/api/social/population` | 可见性（hex 不可见 ⇒ **404**）+ `redactedFields` |
| 11 | `/api/sd/verdicts` ★ **新增** | `adjudicationDisclosure` 三档 + `redactedFields` |

### 二.3 ★ 未覆盖端点清单（逐一列出 + fail-closed）

**A. fail-closed（带 `as=` ⇒ `400` 拒绝，`rejectAs`）——不是静默返回全量**：

| 端点 | 为什么未接 |
|---|---|
| `/api/map/path` | 寻路会泄露**不可见区域的可达性**（起点由服务端取、路径穿图）；接它需要一套"路径级可见性"语义，**本阶段无设计依据** ⇒ 拒绝 |
| `/api/sd/decision-makers` | 决策人清单/过滤是 T5 的**管理面**（T7 决策模式消费）；对"决策人视角"的可见语义未裁决 ⇒ 拒绝 |
| `/api/sd/decision-makers/{id}` | 同上（详情含 `viewScope` 配置） |

**B. N/A（不是 sim 状态读）**：

| 端点 | 说明 |
|---|---|
| `/api/approvals`（GET）与 `/api/approvals/{id}`（POST） | AgentLib `ApprovalHttpEndpoint` 的**透明代理**，不读 sim 状态；审批请求字段由 AgentLib 持有，`as=` 对本代理无语义。★ 如实列出（未加 `as=` 拒绝——它不是 sim 状态读，不存在"全开"）。 |

**C. 写端点（非读，不在 redaction 范围）**：`/api/command`、`/api/advance`、`/api/fork` —— 一律经 `CoreSimos.submit`，`initiator="player:gui"`；`as=` 不适用。

> ★ **为什么新增 `/api/sd/verdicts`**：`adjudicationDisclosure` 的语义是"**判决**对某角色可不可见"，但**既有任何读端点都不携带判决内容**（`/api/resolve` 只回候选 id/type；`/api/facets` 只有 unitsHere/population）⇒ 不新增读面就**无法让该字段"真正生效"**。该端点复用 `RedactingQueryService` 已有的 `sdState()` 读取，不新增 sd 数据（D13 精神）。

---

## §三 门禁

**`./mvnw clean verify`（实现后第 1 次尝试）rc=0**（`logs/clean-verify.attempt1.log`，`md5=7f18fde4e5136954e1bcc20ee47c1158`）：

| 模块（显示名） | 结果 | Tests |
|---|---|---|
| SimulatorMosire | `SUCCESS [` | — |
| UtilSimos | `SUCCESS [` | 170 |
| MapSimos | `SUCCESS [` | 368 |
| SocialSimos | `SUCCESS [` | 45 |
| UnitSimos | `SUCCESS [` | 259 |
| CoreSimos | `SUCCESS [` | 178 |
| SDSimos | `SUCCESS [` | 124 |
| SimosApp | `SUCCESS [` | 180 |

- **8/8 `SUCCESS [`**；**总数 1324**（现场重算：`170/368/45/259/178/124/180`，`logs/recomputed.txt`）；
- `[ERROR]` **0 行**；`BugInstance size is 0` **×7**；前端 `[frontend-gate] OK tests=114 pass=114 fail=0`。
- **基线**（本树改动前实测，`logs/baseline-verify.log`）：**1308** = `170/368/45/259/178/124/164`，rc=0、8/8、BugInstance ×7、ERROR 0、前端 114/114。
- **delta 干净**：**只有 app 164→180 = +16**（`RedactingQueryServiceTest` 3→9 = **+6**；新 `RedactionApiTest` **10**）；其余模块**逐字不变**。
- **变异轮后复跑**（最终字节，`logs/clean-verify.after-mutants.log`）：rc=0、**1324**、8/8、ERROR 0、BugInstance ×7、前端 114/114。

---

## §四 变异（7 轮 7 KILLED / 0 存活）

装置：`mutants/java-round.sh` + `mutants/make-mutant.py`（照 T5 九道门禁：干净世界 / 字节不同 / 推规范名 / 清陈旧 `.class`+`surefire-reports` / `COMPILATION ERROR=0` 且 `Tests run>=1` / 报告 mtime 落轮内 / 红点落被保护断言 / `cp` 逐字节还原 / 日志自指 md5）。每轮 `orig_md5` 与 `restored_md5` **逐字节相等**。

| 轮 | 变异（语义） | 靶文件 | 红点（被保护断言） | 判 |
|---|---|---|---|---|
| t6m1 | `WITHHELD` 早退去掉（不隐藏判决） | `RedactingQueryService` | `withheldDisclosureHidesEveryVerdict`、`verdictDisclosureGatesTheVisibleContent` | **KILLED**（2 击） |
| t6m2 | `redactedFields` 不生效（原样返回） | `RedactingQueryService` | `redactedFieldsRemovesTheNamedFieldFromUnitViews`、`redactedFieldsRecursesIntoNestedLists`、`positionIsRedactedForOneActorButPresentForAnother`、`unitsListRedactsTheNamedFieldForEveryItem`、`stateRedactionRemovesTheNamedTopLevelField` | **KILLED**（4 击） |
| t6m3 | 未接端点不再拒绝 `as=`（fail-closed 失效） | `GuiServer` | `endpointsWithoutRedactionRejectTheAsParameter` | **KILLED**（1 击） |
| t6m4 | `PERCEPTION_ONLY` 也带内部量（恒 FULL 字段集） | `RedactingQueryService` | `perceptionOnlyDropsTheNonObservableVerdictFields`、`verdictDisclosureGatesTheVisibleContent` | **KILLED**（2 击） |
| t6m5 | 单格可见性 fail-closed 失效 | `GuiServer` | `invisibleHexIsNotFoundUnderAs` | **KILLED**（1 击） |
| t6r-t5m5 | （裁定 42 重派生）决策人列表路由整段去掉 | `GuiServer` | `emptyLibraryGivesEmptyListNotAnError` 等 **9** 条 T5 断言 | **KILLED**（3 击） |
| t6r-d4m1 | （裁定 42 重跑）`mapOverview` hex 过滤去掉 | `RedactingQueryService` | `twoScopesSeeDifferentHexesOnTheSameEndpoint`、`unknownActorIsFailClosedToEmptyScope` | **KILLED**（1 击） |

★ **t6r-t5m5 是重派生**：T5 的旧靶串（`return decisionMakersReply(exchange);`）已被 T6 重写 ⇒ 按**新字节**重新派生同一语义（删掉该路由块）。t6r-d4m1 的靶串（`full.put("hexes", …)`）**逐字未变**，直接重放。

---

## §五 裁定 42：改动既有文件的连带重跑

| 被 T6 改动的文件 | 改它的既有任务 | 处置 |
|---|---|---|
| `simos-app/.../gui/GuiServer.java` | T5（决策人路由） | **重跑 t5m5**（重派生为 `t6r-t5m5`）⇒ KILLED |
| `simos-app/.../query/RedactingQueryService.java` | SDSimos D4（redaction 初版） | **重跑 d4m1**（`t6r-d4m1`）⇒ KILLED |
| `simos-app/.../query/RedactingQueryServiceTest.java` | （T6 自身扩展，无前置变异轮） | — |

其余 T5 变异轮（t5m1~t5m4）靶文件 `SdQueryService.java`/`ApiViews.java` **字节未动** ⇒ 证据不作废，不重跑。

---

## §六 我未能核实的

1. **浏览器/前端未验**：T6 是**后端**改动（`webui/**` 零改动），无 `as=` 的前端消费者（research §C.8 实测 `webui/**` 搜 `as=` 无命中）⇒ 全部判据在 Java 端到端（真 `Shell` + JDK `HttpClient`）与单测层。
2. **`/api/sd/verdicts` 未经真实 LLM 判决产生**：夹具直接种入 `Verdict`（不经 `sd.SubmitVerdict` 命令路径）。判决**内容**（`payload`/`meta`）的可见性口径已逐值断言，但"真 adjudicator 产出的判决"未接（`agentlib-mosire` 外部依赖盲区，沿 D 阶段）。
3. **`redactedFields` 的字段名语义是"按名"不是"按路径"**：递归剔除同名键（任意层级），未实现 JSONPath 式定位；`position`/`heads` 这类唯一名不受影响，但若两处不同层级有同名字段会**一并剔除**（保守方向，不泄露）。
4. **`resolve` / `facets` 的可见性未做实体级过滤**：只做了 `redactedFields`。`/api/resolve?address=map:Map1:[1,1]&as=dm-none` 仍会返回该 hex 的候选（`typeName`/id），因为"地址解析"是否算"可见性"**无设计依据**；已列入遗留（§七）。
5. **`/api/approvals` 未加 `as=` 拒绝**：它是 AgentLib 透明代理（非 sim 状态读），`as=` 会被原样转发；未验证 AgentLib 端对未知查询参数的行为（沿"外部依赖盲区"）。
6. **跨 JVM 逐字节决定论**：`verdicts` 按 `VerdictId` 字典序、`redactedFields` 剔除后仍是 `LinkedHashMap`（保序）；未跨 JVM 独立复现（同 T5 §八）。
7. **真档未验**：夹具是合成小图（3 hex / 1 unit / 1 verdict）；真档上的 redaction 未跑（与 T5 同一开口项）。

---

## §七 遗留（带裁定）

- **L1（范围声明）**：`/api/resolve`、`/api/facets` 只做**字段级** redaction，**不做实体级可见性**。理由：地址解析/扩展面是否受 `visibleHexes` 约束**无 spec 依据**；做了就是发明语义。**未覆盖的实体级可见性**以本报告 §六.4 显式列出，不伪装成"已实现"。
- **L2**：`/api/map/path`、`/api/sd/decision-makers{,/{id}}` 带 `as=` ⇒ 400。若 T7/T9/T10 需要它们支持 `as=`，须**先补设计**（各自一套可见性语义），不得就地放开。
- **L3**：`/api/approvals` 的 `as=` 透传（§六.5）。
- **L4**：`verdicts` 的 `PERCEPTION_ONLY` 字段划分（保留 `id/breakpoint/subject/atRevision`，去掉 `payload/meta`）是**实现期裁定**——`DisclosurePolicy` 的 spec 未定义字段粒度（A2-c 已记"取值 spec 未定义、实现期定档"）。若日后 spec 明确，需回填。

---

## §八 证据清单

- `logs/baseline-verify.log` + `baseline-rc.txt`：改动前基线（1308）。
- `logs/clean-verify.attempt1.log` + `.rc`：实现后第 1 次尝试（1324，rc=0）。
- `logs/clean-verify.after-mutants.log` + `.rc`：变异轮后复跑（1324，rc=0）。
- `logs/recomputed.txt`：门禁**现场重算**（只取模块汇总行）+ delta。
- `mutants/java-round.sh` / `make-mutant.py` / `orig/` / `logs/t6m{1..5}.log` / `logs/t6r-t5m5.log` / `logs/t6r-d4m1.log`：七轮变异（含九道门禁自证）。
