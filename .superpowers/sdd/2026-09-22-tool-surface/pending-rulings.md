# 待你裁定（控制器只备料，不落笔）

> 依据你 2026-09-22 的指令「**尽可能把不用判断的做完，提交并推送，然后等我回来判断**」。
> 以下**五项**已被识别为**需要你判断**的，**控制器一律没动**。
> 每一项都给了 **可选项 + 代价**，你可以只回一句（例：`D-5 选 3；M4 只要最高那两条；D-4 选 1；creed 改；A/B/C 不动`）。
> 事实出处一律带 `文件:行`；**凡属推断的都标了「推断」**。

---

## 一、D-5：导入器 tag 改 `nation:` —— 四个选项，选哪个？

**要做的事**（机械、一处）：`tools/gsimap_import.py:506` 现在是**直通** `"tag": province.get("tag")`
⇒ 改为产出带前缀的 `nation:<…>`。（`:501` 同时把 `name` 设成 `province_id`。）

**为什么它现在被卡住**（三条实测，不是推导）：

1. **本机没有导入器输入侧的真档**。`find / -xdev` 对 `n0000_map.json` / `*_map_diff.json` /
   `-type d -name nodes` / `*test_integration*` **命中均为 0**；`ls ~/DevMosire`、`ls /home/cna` 都是
   `No such file or directory`。而 `check_v17levant_import.py` 的 `main` 要求 `len(argv) in (2,3)`
   且**源档必填** ⇒ **这张黄金校验脚本在本机连启动都不能**（recon §7.4）。
2. ⇒ 改完之后**无法为新的期望值取证**。而本仓纪律明文禁止「**不写没实测过的期望输出**」
   （纪律形态 5）——改了导入器就必然留下**一个没跑过的期望**。
3. 已入库的 `v17levant.json`（**4,219,540 B**，md5 `1d817eee4d238c5255953e274a8f00f0`）**本机无法重新生成**
   （它的两级输入同样缺席）。

### 四个选项

| # | 选项 | 具体做什么 | 代价 / 风险 |
|---|---|---|---|
| **1** | **合成夹具复验** | 造一份**最小合成**旧档（几个 province、tag 为 `Nation`），跑导入器，断言产出 tag == `nation:<id>`；真档复验仍挂空 | 合成档**证不了**真档的 252/252；`check_v17levant_import.py:35` 的 `EXPECTED_TAGS = {"Nation": 252}` **仍会陈旧**，换机器一跑就红 |
| **2** | **只改导入器，不动已入库资源** | 改 `:506` + 更新两张黄金期望的字面（`check_v17levant_import.py:35`、`RichWorldTest.java:86-94`） | ★ 两张期望**都改成没跑过的值** ⇒ 直接违反纪律形态 5；**要等换机器才发现写错**（本仓最贵的教训类型） |
| **3** | **拆分：改导入器，真档复验如实记为「未兑现」** | 改 `:506`；**不碰**两张黄金期望（保持今天的事实：真档今天确实是 `Nation`）；把「真档复验」记成带裁定的遗留项 | ★ 换机器后 `RichWorldTest` 与 `check_v17levant_import.py` **会红**（因为期望还是旧的）——**红是诚实的**，但需要你在那台机器上当场重算期望值 |
| **4** | **整件挂起** | 一行不改，等换到有真档的机器上一起做 | 本阶段**少一件交付**；但零虚假期望 |

★ 四个选项的**共同代价栏是真的**：**本机做不到"改完即验"**，区别只在于把不确定性放在哪里。

### 无论选哪个，有两件事实你必须一并裁（我实测出来的，可能与 D-5 原文的预期不同）

**事实 ①：`nation:` 的后缀应该是 NationId，不是显示名。**
D-5 的文本写的是 `nation:<名>`，但代码契约是 `NationTag.tagFor(NationId)`
（`NationTag.java:6,17-19` 产出 `"nation:" + nationId`；`webui/map.js:2373-2375` 与 `panels.js:1227`
按**字面相等**对应）。本次**252/252 恰好 `name == id`**（recon 实测）⇒ 两种写法结果相同，
**但契约是 id**。★ 若按"名"写，哪天名与 id 分叉就会**静默失配**：
`nationRegionIds` 返回 `[]`、**高亮空白、不报错**（`decision-mode.test.cjs:430` 把 `[]` 钉成了判据）。

**事实 ②：改完之后，富世界的 252 个区域会全部变成「不可删」。**
`RegionDeleteGuard.java:56-57` 对**带国家 tag** 的区域拒删（文案原文：
`Region <id> 带国家 tag（nation:<名>），不可删（spec §九）`）。今天这 252 个区域**一个都不带**该前缀
（`nation:` 前缀计数 **0**）⇒ 今天**可删**。改完后它们**全部不可删**，
而**没有任何既有断言会红**（recon §7.6——这是"行为变了但没有护栏看着"的一处）。

### 改完之后还有三个连带效果（**已逐条实测**，不再是推断——你可能想要、也可能没想到）

**连带 ①：`sd.CreateNation` 的前置会被打开。**
`CreateNationHandler.java:53-55` 要求 `homeRegionId` 指向的区域**已带 `nation:` 前缀的 tag**，
否则拒绝（原文 `Region <id> 无国家 tag（R13：需以 nation: 开头的 tag）`）。
今天这 252 个区域**一个都不带** ⇒ **今天在富世界里建不出任何国家**（先有鸡还是先有蛋）。改完后这条前置**能被满足**。

**连带 ②：但那条前置**只判前缀**，不判对应关系。**
`NationTag.isNationTag`（`NationTag.java:21-23`）是**纯前缀检查**：
`return tag != null && tag.startsWith("nation:")`。⇒ 拿一个**任意** nationId 去建国家，
只要 `homeRegionId` 的区域带 `nation:` 前缀就**会被接受**，**即使 tag 里的 id 与要建的国家不是同一个**。
「区域属于哪个国家」这层对应**只在读侧用**（WebUI 按**字面相等**高亮），**写侧从不校验**。
★ 换句话说：D-5 之后那个 tag 是**提示**，不是**绑定**。

**连带 ③：这些 tag 在 sd 的语义里是悬空的。**
导入器**不建任何 `Nation` 对象**（`git grep CreateNation -- tools/` **零命中**）⇒
区域会宣称自己属于某个 nation，而 sd 里并不存在对应的 `Nation`（`base.nations()` 不含它）。

---

## 二、M4 读口：清单已出（你裁的 D-3 = 先列清单再定），请圈范围

现状：GUI 有 **17** 个 JSON 读端点，MCP 侧只有 **9** 条读工具 ⇒ **缺口 9 条**
（8 条完全没有 + 1 条部分）。完整清单与逐条证据在 `m4-inventory.md`，下面按**风险**排：

### ★★ 圈范围前你必须知道的一条结构性事实：**读工具没有"只给 GM"这个选项**

`SimosToolSource.java:117` 的类内 javadoc 原文：**「读工具四桶共享；写面各自不同」**
（装配式实测：`built = new ArrayList<>(readTools(...))` 是**四个桶的公共前缀**，
再按角色追加各自的写面）。⇒ **读工具一旦加进去，`EXTERNAL` 桶也拿到**——
而 `EXTERNAL` 是**对外的 MCP 口**（给外部 agent 的）。

**这条把下表的风险整体抬了一档**：圈中一条读端点 ≠「给 GM 开个便利」，
而是 **「凡持有任何一个口的人都拿到」**。特别是：

- 圈 `/api/sd/decision-makers` ⇒ **外部口也能读到别人的 `rootUnit`/`viewScope`/`allowedTools`/`providerId`**；
- 圈 `/api/llm/providers` ⇒ **外部口也能读到 `baseUrl`/`model`/key 名**；
- 圈 `/api/gm/tool-usage` ⇒ **外部口也能读到 GM 的内部观测面**；
- 圈 `/api/map/path` ⇒ **外部口也拿到那条不受审批的逐格测地形通道**。

★ 若你要的是"**只给 GM**"，那 M4 的形态就**不是"补读工具"**，而是**给 `Role` 加一条只挂读工具的子集**
（或把这几条做成 GM 专属桶）——**那是另一个设计问题，代价大得多**，请明说。
★ 控制器**没有替你选**：这条只是把"圈范围"的真实含义摆出来。

| 级 | 端点 | 要害（会红什么 / 会漏什么） |
|---|---|---|
| **最高** | `/api/sd/verdicts` | ★ **唯一「支持 `as=` 又允许省略」**的 sd 读端点；**省略 ⇒ `RedactingQueryService:167` 写死 `DisclosurePolicy.FULL`，连模型输出的 `payload` 一起发**（★ 还发 `meta`：`model`/`promptVersion`/`inputBriefDigest`，见 `:203-209`）。同类端点（decision-makers / tool-usage / llm-providers）都是**显式 `rejectAs`** fail-closed（`GuiServer:450-466`）。★ 该口径被 `RedactionApiTest:184` 钉为**有意** ⇒ **照抄成读工具不会自己红** ★★ **本行经控制器三链逐字复核**：`GuiServer:443-449`（`asPresent` 三元）→ `RedactingQueryService:167-169`（硬编码 `FULL`）→ `:203-209`（`FULL` 才发 `payload`/`meta`，另两档不发） |
| **高** | `/api/sd/decision-makers`（+`/{id}`） | 含**别人的** `rootUnit` / `viewScope` / `allowedTools` / `providerId`；**不收 `as=`** ⇒ 无任何按视角裁剪机制。★★ **本行经控制器逐字段复核**（`ApiViews.java:575-605`）：实发 `id` · `affiliation{kind,id,displayName,nationId,`**`rootUnit`**`}` · `allowedTools[]` · `cadence` · `providerId`（不透明 id，null 不拿空串顶替）· `viewScope{}` · `due` · `lastDirectiveTick` · `ticksSinceLast`。★ **一处要读准**：`viewScope` 发的是**计数**（`visibleRegions/visibleHexes/visibleUnits` 的 `.size()` + `seeOwnUnits` + `adjudicationDisclosure`），**不是成员集合** ⇒ 泄的是「**他们能看多大**」，**不是「他们能看哪些」**；`adjudicationDisclosure`（判决披露档位）**本身是情报**（知道对手会不会看到你的模型输出） |
| **高** | `/api/gm/tool-usage` | GM 内部观测面；**四桶共享读工具** ⇒ 观测者被反向观测（该端点亦 `rejectAs`） |
| **高** | `/api/map/path` | 地形探测读，且**显式拒绝 `as=`**（今天无视角概念）⇒ 照抄 = 给决策人一条**绕过 `viewScope` 的逐格测地形通道**；同一能力在写面已有 `unit.PlanRoute`（**走审批**），读的那副**不受审批** |
| **高** | `/api/llm/providers` | ★ **密钥值不泄露**（逐字段读过：只发 `credentialsRef` 掩码 `:457-461` + `keyConfigured` 布尔 `:311-320`）；但暴露 `baseUrl`/`model`/**key 名**。★ **一处未核实**：`:226` 把坏条目的 `ConfigException.getMessage()` 原样发出，该消息是否可能带密钥值**未读到构造端** |
| 中/低 | `map/region/{id}` · `timeline` · `/api/state`（`meta` 面） | 可开，但需先定 `as=` 语义（`timeline`）/ 只差值形状（`meta`） |

**★ 另有一个判据缺口（不管你怎么圈，它都在）**：**读口没有 `McpCoverageTest` 的等价物**——
一条读工具可以有**正确的名字**、**正确的 `spec()`**，但**形状错 / 抛异常 / 压根不可达**，
而**全部现有断言照绿**。（`listTools()` 在全仓只被调过 **2** 次，**没有一次 `callTool`**；
`readToolsMatchQueryServicePerValue` 只逐值对拍 **3** 条。）

**★ 两处既有读工具的形状缺陷（不是缺口，是已存在的东西不对）——要不要一并修**
（★ 两条**控制器已逐字复核**，不是照抄侦察报告）：

1. **`simos.map.hex` 的 MCP 版比 GUI 版少三个键**（★ 侦察报告写的是"少两个"，**实测是三个**）：
   MCP `MapHexTool.java:76-79` 产出 `{q, r, terrain, height, facets}`（**5 键**）；
   GUI `ApiViews.java:352-363` 产出 `{q, r, terrain, height, regions[], terrainType, facets, edges[]}`（**8 键**）。
   少的是 **`regions[]`**、**`terrainType`**、**`edges[]`**。
   ⇒ **决策人经 MCP 看不到「一格同属多区域」**——而那是 **M8-U1 你自己裁的地基语义**；
   ⇒ 也**看不到 `terrainType`**（与 `terrain` 并列的那个字段）。
2. **`simos.map.overview` 的 MCP 版仍是逐格数组**（实测 `ToolSupport.java:306-314` 逐条建
   `hexes:[{q,r,terrain,height}…]`；GUI 侧 `ApiViews.java:176` 发的是 **`blocks`**）
   ⇒ **M9 的省流只落在 `ApiViews` 一侧**，真档 19441 格下 **≈1MB/次**
   （`ApiViews.java:165` 注释记着改造前 1,043,837 B）。

---

## 三、creed §五 的一处措辞（一句话的事）

`docs/superpowers/specs/2026-09-22-tool-surface-creed.md` §五 现在写：

> 每加一条**命令/工具** ⇒ 连带 `CatalogTool.PAYLOAD_HINTS` + `McpCoverageTest` 双向载荷

**逐字读会误导**：这条规矩的对象是**命令类型**
（`PAYLOAD_HINTS` 是「命令 type → 载荷提示」表，`CatalogTool` 构造期按**注册 type** 强制）。
**加读工具不产生新命令类型 ⇒ 两张表都不动**（M1 已实测为**空操作**）。

⇒ 建议改成「每加一条**命令**」。
★ **控制器没有擅自改**——creed 是**绑定权威、你的文档**。**要不要改，你点头我就改**。

---

## 四、三处「用户可见的域层静默缺口」（M1 侦察发现，**没修**）

| # | 缺口 | 具体表现 | 锚点 |
|---|---|---|---|
| **A** | 通路组 id **大小写变体** | 注册侧 `containsKey`（**敏感**），解析侧 `resolveKind` **不敏感且取首个** ⇒ 已有 `river` 时注册 `River` **成功**，此后 `kind:"River"` 落到先注册的 `river`；**两条路径都不报错** | `PathwayGroupOperations.java:38-39` vs `EdgeOperations.java:116-124` |
| **B** | `map.SetEdge` **无邻接校验** | 可以连**相隔半图**的两格 | `EdgeOperations.java:59-75` + `GameMap.java:72-89` |
| **C** | `map.UpdateRegion` 的 `meta` 是**整体替换** | 只给 `{"tag":"X"}` 会**静默清掉** color/description/annexedBy；`"meta":{}` 非 null 却**过**「至少给一个」检查并把 4 字段**全清空** | `RegionOperations.java:83,93,96` + `MapPayloads.java:136-150` |

**为什么没修**：三者都是**既有的**行为（今天经通用写 `simos.command.submit` 就可达，**不是本阶段新造的**），
而改域层会**牵动作废已关账的变异轮**（裁定 42：改动被测文件 ⇒ 旧证据作废、须重跑）。
★ 另有 5 处（D~H：`kind`/`mode` 大小写不敏感 / 未知字段静默忽略 / **空操作仍落 revision** /
`PropertyDef.type` 无白名单 / `CreateRegion` 无重名校验），**与 A/B/C 同族**，一并听你的裁决。

---

## 五、D-4：通用写要不要收窄（评估已出，**只评估、零改动**）

完整评估在 **`m5-evaluation.md`**（八节，每条事实带 `文件:行`，含"我未能核实的"一条 6 项）。
这里只给你**做决定需要的那几行**：

**① 「通用写」在工具面上只有一条。** `simos.command.submit`（`CommandSubmitTool.java:35`）——
`type` 由调用方自选。`simos.advance`/`simos.fork` **命令类型是写死的**（`AdvanceTime`/`ForkBranch`），
只是名字没跟类型同名，结构上属窄工具。

**② 覆盖已闭：43 个生产命令类型 ↔ 43 条同名窄写工具（现场 `comm` 实测是双射）。**
⇒ 删掉通用写，**没有任何命令类型变得不可达**。

**③ 它只进 `EXTERNAL` 桶**（`SimosToolSource.java:158` 是**唯一构造点**）；`GM` 与 `DECISION_AGENT`
两桶**都没有**它。★ 但 `Role.EXTERNAL` **在生产里没有构造点**——所以「收窄」的实际落点是
**现有那个 MCP 口（`EXTERNAL ∪ GM`）少一条工具**。

**④ ★★ 但收窄工具面 ≠ 收窄写权限。** `POST /api/command`（`GuiServer.java:498` → `:782`，
**14 行全文读过**）接受**请求体给的任意 `type`**，且**全程不过审批门链**，`initiator` 恒
`player:gui`。该端口 `--bind-address 0.0.0.0` 时**在局域网上可达**（M10 实测 200）。
⇒ **若你要的是"系统里不该存在任意 type 的写入口"，只删工具面不达成目的。**

**⑤ 顺带量出一条今天为真、但没有护栏的不变量**：「每个命令类型都有一条同名窄写工具」**没有任何判据钉住**
（现有判据只钉 类型↔catalog、工具类↔GM 桶、桶→类型 三个方向；**类型→工具**这个方向是空的）。
今天它只是便利性；**一旦选收窄，它就变成承重**——漏配**不会红**，只会让那条命令在那个口上**静默消失**。

### 三档，选哪个？

| # | 选项 | 做什么 | 代价 |
|---|---|---|---|
| **1** | **不动**（维持 D2） | 一行不改 | 该口持有者可绕过 43 条窄工具提交任意类型。★ **这本来就是 D2 的明文取舍**（`SimosToolSource.java:101-103` 自己记着账） |
| **2** | **只收窄工具面** | 删 `SimosToolSource.java:158` 一行 + 改该方法 javadoc/命名 + 更新三处测试常量 + **补 ⑤ 那条判据** | **零命令类型损失**；**但 HTTP 面仍在**（④）⇒ 只对"走 MCP 的 agent"生效。按裁定 42 需连带重跑受影响的变异轮 |
| **3** | **两面一起** | 选项 2 + 处理 `/api/command` | ★ **这是另一个设计问题，不是收窄**：M7c 实测「**非 GET 清单只剩 `/api/command` 一条**」⇒ 删它 = **工作台全部编辑功能失效**。现实的形态是「**是否给 GUI 写端点也加一道门**」= **新需求** |

★ **控制器没有替你选。** 三档的差别不是"严不严"，而是「**管哪一面**」：
**只收窄工具面**管的是"经 MCP 的 agent"；**要管住"任意 type 的写入口"必须碰 HTTP 面**。

★ **权威挂钩**：spec §八.3 原文（`docs/superpowers/specs/2026-09-20-sd-simos-design.md:478`）已经把
外部 MCP 桶写成「**保留现状或另行收敛，列为挂起**」⇒ **D-4 就是兑现这句「另行收敛」**；
同节 `:479`「★ **GM 绝不握通用写**（N11）」**今天已经满足**。

---

## 六、控制器当前状态（给你对账用）

| 项 | 状态 |
|---|---|
| **M1**（map 7 条工具） | ✅ **已关账**，已提交并推送（`40e19d2`） |
| **M2**（unit 20 条工具，GM + 决策人双桶） | ✅ **已关账**（任务评审 1 轮，判决 A 通过 / B 通过、无修复轮；1 条低危发现**当场修掉**）。提交 `981586e` + `8899457`（实现）+ `7ce2cc1`（关账文档）；**已推送**（`40e19d2..7ce2cc1`，纯快进） |
| **M3**（sd 12 条工具，只进 GM 桶） | 🔄 **实现已完成、任务评审运行中**（实现者判 `DONE_WITH_CONCERNS`，三条待评审独立判；生产侧 12 条 `NAME`/`description` 控制器已自行逐字核过）。实现提交 `aba3326` + `fab1fa4` + `2cffab4`；**尚未提交文档、尚未推送** |
| **M4**（读口补齐） | ⏸ **阻塞在你**（§二） |
| **M5**（通用写收窄评估） | ✅ **评估已出**（`m5-evaluation.md`）——**零代码改动**，**等你裁**（§五） |

★ **推送状态**：`origin/ts/m1` == **`7ce2cc1`**（= M2 关账点）。**本地 HEAD 已到 `2cffab4`（M3 实现），尚未推**——
控制器有意**压着不推**：`ts/m1` 是 M3 的**评审基线 `7ce2cc1`**，而推送会连带把 M3 的实现字节推出去；
更直接的约束是**三份控制器文档 + 评审包 + M5 评估**必须等 **M3 评审关账后**才能提交（否则会落进
M3 的评审区间 `7ce2cc1..implHEAD` 里）。评审一关就提交并推送。
★ **`origin/main` 仍是 `40e19d2`**（M1 关账点）——**没动**，等你裁定（口径见 `CLAUDE.md`：`main` 平时落后于 feature 分支，你说推就推）。

