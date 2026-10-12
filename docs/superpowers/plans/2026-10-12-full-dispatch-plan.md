# 派单规划（约束设计书）：sd 弃用 / ga 持标准决策人 / 权限统一 / hex 属性表

> **基线**：`main` @ `f2de4429`。**权威**：与代码冲突以代码为准（§四）。
> **本文件用途**：控制方派实现子 Agent 的**约束设计书**（§一.8）。每批的派单书引用本文件对应小节并原样复述关键形状。
> **不含**：逐文件施工图、类/方法拆分、实现顺序（那些是**实现方**的「实现架构账本」，落 `.superpowers/sdd/<topic>/impl-ledger.md`）。

---

## §0 用户原话附录（逐字照录，§一.8.1；不改写、不缩写、不意译）

**2026-10-12 第一轮**
> 「首先，地图肯定是交给Map模块维护逻辑的，存储交给Core就行；然后是城市，城市是单个hex的比例状态，也就是说单个hex可以1%是城市、99%是非城市等，这种状态叫比例状态；然后是去掉作为标签的城市后，所有hex都可以被额外命名，GUI方面后面在做，改名需要用GM工具；行政区划本质是区域，实际上，行政区划、国家区划本质上都应该交给GOV模块来维护，GUI只负责显示——因此应该先修改GOV模块和Region模块，Region模块只提供通用的区域创建/修改/替换等工具操作和通用接口，GOV是实际负责提供国家、省份等语义的」

**2026-10-12 第二轮**
> 「首先，一切以我最新说明为准」
> 「其次，城市方面目前暂时只交给Map维护一个固定数值，变动搞一个标准的程序内工单，就是提交谁干的、为了干什么就能改一个hex的城市化率」
> 「根据前者，这种程序内工单在项目里似乎不少吧？也许可以做个通用化？原本要求提交谁干的为了干什么是为了日志方便，现在看来很有必要标准化啊」
> 「simos-sd不是取缔掉了吗？GOV模块随便引用新的ga模块；国家语义直接般GOV模块得了呗，GOV就是维护国家/政府等的！」
> 「继续思考和考察」

**2026-10-12 第三轮**
> 「工单我想了一下，原本我以为没有其他的，现在看来额外的自然语言reason没啥必要，保持现有；
> 国家模块到底啥问题，命令改名自动放权，理论上不应是GM命令优先实现，然后额外套DMOnly标签管理吗？神了
> 战斗模块，包括阶段之类的，归Army模块管，原则和GOV差不多，但是Army很显然可以依赖GOV，对GOV的相关修改也要走工单系统；
> 这个能实现吗？」

**2026-10-12 第四轮**
> 「那肯定b啊」
> 「城市化率用面积比来显示」
> 「另外，给单个hex加一个可以编辑的可用土地数值，根据地形初始化，可以不填（给一个各个地形的默认值，反正大体大差不差，如果没有额外的赋值，就用地形默认值，编辑原则和城市化同理）」
> 「map层级的命令、凭空造钱造人建军肯定不该给DM用啊，你的理解是对的」
> 「目前的战役细节不都是GM推演的，单个Army决策人只有权提交自然文本战术和移动到哪，其他工具都是GM专有，我建议把1、2、3、5给废除掉，4和6都是很有用的工具，给GM开放，其中能详细和我讲讲4是什么吗？」
> 「ga为什么依赖sd？？？？ga应当维护标准的一般决策人，因此事实上是要提供决策人Agent上下文状态描述，和决策人的归属等的，储存可以交给Core；单个决策人的具体归属，交给调用ga的具体模块不行吗？同样搞工单；和Unit一样，不但允许创建没有更多附加状态的单个决策人，也可以创建/为已有决策人赋予、修改状态！」
> 「GOV模块」（答 `NationId` 落点）
> 「sd整个弃用！」
> 「直接扔」（答 `sd:nation.<id>` 地址族）
> 「继续调查，按照我的思路规划」

**2026-10-12 第五轮**
> 「哦牛逼，那不能直接让ga统一鉴权来解决吗？这本来就是规范问题」
> 「不是，那不能让各个模块自己决定自己工具的权限范围吗？为啥还有APP额外批准？」
> 「就这么办，来到B1，土地权威是啥？我只是给Map中的hex设定一个比例，后面有需要还会再加的，我甚至没说这个城市化率、总土地量有啥效果
> 想了想确实可以改改，把城市化率取消掉，额外搞一个Map，目前只加两项，后面可以加自定义字段，已有默认字段分别是城市面积和乡村面积，根据地形在初始化世界的时候先给乡村面积一个固定值，然后再根据需求自己调整城市化面积，目前两者只给一个加减赋值，不和其他任何模块联动，这下你看懂了吧？」
> 「形状按A」
> 「工具两者都要，走工单，Map额外实现工具」
> 「自定义字段必须是一个String，应当明确标注为"自定义字段"，已有的城市/乡村走enum标签声明」
> 「这个可用地面积如果没有，那么读的时候就走默认，也就是单个地形的默认可用面积，以乡村类型显示」
> 「那让ga不依赖core就行，按你说的做；剩下的A2、A3、B2、B3、B4还需要我决策吗？看看你的新想法」
> 「归属肯定归模块啊，后面新加模块ga还得显式地重新添加？不可能的，因此是乙」
> 「现在写完整的派单规划，再次确认一遍决策都过完了，然后自己开工，不需要我做完一个确认一下，除非遇到需要决策的，否则一步一步继续」

**既有裁定（被本次引用，非本轮）**：D-002（不给中央开天眼）· D-012（交战记录移 Army）· D-025（城市化率派生量）· R6（外交边表落 sd，**已被本次取代**）· C21（initiator 形态）· §一.11（Alpha 不兼容旧档）· §一.11+（存档废掉就废掉）· §一.5（一个责任区一个代理）· §一.10（一次一个写代码代理）。

---

## §1 决策结清确认（**12 组，全部已定，无待裁**）

| 组 | 决策 | 状态 |
|---|---|---|
| 工单 | **保持现有**（不新增信封字段、不统一 payload、不建通用 WorkOrder） | ✅ 用户 |
| 权限方向 | **反默认**：默认仅 GM，DM 需显式声明 | ✅ 用户 |
| 权限归属 | **模块自主声明 + 模块自交 handler 清单 + ga 统一默认规则与装配期审计 + app 只注册** | ✅ 用户 |
| 权限实现 | `DmUsableCommand` 命名（甲1）· `GmOnlyCommand` 同批退役（甲2）· `sd.` 前缀**一条不留**（甲3）· 切片 A（等价迁移）与 B（收紧 35 条）**分批**（甲4） | ✅ 用户 |
| 收紧范围 | map 命令 · 造钱 · 造人 · 建军 → 不给 DM；**"其他工具都是 GM 专有"** | ✅ 用户 |
| 战斗 | 归 **army**；废 1（触发器状态机）2（改表）3（`LossClass`）5（交战级 `finalOutcome`）；**4（`atRevision`）6（三段地址）给 GM** | ✅ 用户 |
| army 依赖 | **`army` 可依赖 `gov`** | ✅ 用户 |
| sd | **整个弃用**；`NationId` 归 **gov**；`sd:*` 地址族**直接扔** | ✅ 用户 |
| ga | 持**标准一般决策人**（身份 + 上下文状态描述）；**存储交 Core**；**像 Unit 一样裸创建 + 再赋状态**；**不依赖 core** | ✅ 用户 |
| 归属 | **乙：归属归模块**（ga 不得枚举归属种类） | ✅ 用户 |
| hex | 落点 **(A) 塞 `HexCell`**；**取消城市化率**；改为**可扩展属性表**（默认两项：城市面积/乡村面积，enum 标签 + 自定义 String）；乡村面积创世按地形给定值；**增量与全量赋值都要**、走工单、**Map 自实现工具**；缺省 ⇒ 读地形默认、以乡村显示；**暂不与任何模块联动** | ✅ 用户 |
| 地图/存储 | 地图逻辑归 map、存储归 core；随仓世界**暂不动**（用测试世界验） | ✅ 用户 |

**控制方技术裁定（可被用户推翻，已在文档标注）**：地形默认表落 `TerrainCatalog` 旁表（B2）· `SdInfoEntry.affiliations` 降为不透明受众标签集、可见性判定搬 app（乙第 5 步）· `ga` 的 9 个工具迁移取 `ga-spi.ToolWritePort` 而非新增不变量（A1）。

---

## §2 批次与派单单位

**纪律**（§一.5/§一.10）：**一次只有一个写代码代理**；写代码代理**只写生产代码、只过编译、不写/不跑测试、不 commit**；测试由**测试代理**在批次末统一做；控制方审后提交。

| 批 | 责任区 | 派单单位 | 类型 |
|---|---|---|---|
| **0** | 准备与去风险 | 0b（pom）· 0a+0c（护栏测试） | 生产 1 + 测试 1 |
| **1** | 权限统一到 ga 规范层（等价迁移） | 1a（`ga-spi` 声明 + ga 规则与审计）· 1b（模块自交清单 + 91 条声明）· 1c（app 只剩注册） | 生产 1（分 3 步同批） |
| **2** | hex 属性表 | 2a（`HexCell` + 地形默认表 + `TerrainOperations` 必修点）· 2b（命令 + Map 工具 + 读面） | 生产 1（分 2 步） |
| **3** | sd 弃用 | 3a（`ga` 持 `decisionPackets`+`mergedEffectPlans` 最小切片）· 3b（决策族其余 + 乙六步）· 3c（外交 → gov）· 3d（战斗 → army，先重挂判决面）· 3e（sd 空壳退役） | 生产 1（**串行 5 步，每步一门禁**） |
| **4** | 权限收紧 35 条 | 4a（按 B4 细化后的清单收紧） | 生产 1 |
| **5** | gov 命令面 / 收尾 | 5a | 生产 1 |

**次序硬约束**：**批次 1 必须先于批次 3**（否则 25 条 `sd.*` 改名后静默进令白名单，`AdjudicateTickTickToolTest` 66 → 91）。批次 2 与 1/3 无文件交集，但**同守"一次一个代理"**。

---

## §3 批次 0（**现在就开工**）

### 0b —— `army → gov` pom 放行【生产】
- **问题现场**：`simos-army/pom.xml:92` 有 `<exclude>io.mosire:simos-gov</exclude>`；`simos-gov/pom.xml:107` 有 `<exclude>io.mosire:simos-army</exclude>`。
- **最终能力**：`army` 可声明并编译期使用 `gov`；`gov` 仍不许依赖 `army`（单向不对称是防环的必要条件）。
- **可动范围**：`simos-army/pom.xml`（删 gov 禁列 + 加 dependency + 改 `:19-24` 注释与 `:115` `<message>`）。**禁止碰** `simos-gov/pom.xml`。
- **不变量**：enforcer 禁列口径 = 全仓 − 自己 − 允许面，零例外（`<searchTransitive>false</searchTransitive>` 已在 `:81`）。
- **验收**：`tools/mvn-lock.sh -pl simos-army -am -DskipTests validate` rc=0、`BannedDependencies passed`。
- **负向**：给 armY 加回一条被禁的 `simos-sd` 依赖 ⇒ `validate` **rc=1**（证明禁列仍有牙）；还原后 md5 一致。

### 0a + 0c —— 两处铁律 5 / revision 级护栏【测试】
- **0a**：`simos-gov` 是全仓**唯一没有往返护栏**的切片（`find simos-gov/src/test -name '*RoundTrip*'` = 0）。新建 `GovRoundTripTest`：反射枚举 `GovState` 组件 + `hasSize(2)` + 排除集为空 + `between→apply` 逐字段重建。
  - **负向（判别力核心）**：往 `GovState` 加一个组件而不改 `GovChangeSet` ⇒ **当场红**（今天必绿）。
- **0c**：`army.RecordCombat` 的 **revision 级**判据（今天只有域级测试）：① 提交 ⇒ head revision +1 且 `combats` 恰 1 条；② 同 id 再提交 ⇒ `Rejected` 且 **head 不变**。
  - **负向**：改坏写口 ⇒ ①必红；删"阶段 id 重复具名拒" ⇒ ②必红。
- **禁止**：改任何 `src/main` 代码。

### 0d —— 文档更正【控制方自己做，非代码】
5 处：`freeze.md:69`（army 行漏 gov/sd/ga）· `freeze.md:68`+`AGENTS.md:62`（sd 的"推演框架层"已被 B2a/B3 推翻）· `2026-10-13-unit-integration-p2.md:40` 的 `unit.ResolveCombatStage` 笔误 · `freeze.md:78`/`AGENTS.md:72` 的"map 现用了 Nation"实测为假 · `HexCell` 类注的"P1 用户裁定"出处不明（§一.12）。**追加式更正，不改历史留痕行**（§五.4）。

---

## §4 后续批次的约束（派单时展开；此处只钉不变量与判据）

### 批次 1（权限统一）
- **声明落 `ga-spi`**（实测 16 个模块全部已声明它 ⇒ 零 pom 改动）；**规则与审计落 `ga`**；**app 只注册**。
- **默认 = 仅 GM**；`DmUsableCommand` 显式声明者才进 DM 面；`GmOnlyCommand` **退役**；`sd.` 前缀规则**删除**（同时删 `Shell.java:903-914` 的 5 条无条件 add）。
- **三面收一**：`directiveCommandTypes` / `drainableCommandTypes` / `ChannelAdmission.LANDING_POINTS` 全部派生自同一份声明。
- **正向判据**：统一前后三个面的集合**逐条相等**（对称差 = ∅）；`AdjudicateTickToolTest:549`(66) / `:931`(97) / `McpCoverageTest`(142) / `SimosToolsTest`(142) 全绿。
- **负向**：① 新加命令不打标签 ⇒ 三面都不含它，但 catalog 与 GM 提交面含它（今天必红）；② 同时声明"可 DM"与"仅 GM"⇒ **装配期抛**；③ 声明一个未注册类型 ⇒ **装配期抛**。
- **不可协商**：**读不到归属 ⇒ deny-all**。

### 批次 2（hex 属性表）
```java
sealed interface HexAreaKey {
  enum Builtin implements HexAreaKey { URBAN, RURAL }   // 内置：enum 标签声明
  record Custom(String name) implements HexAreaKey {}    // 自定义：String，类型上标注"自定义字段"
}
record HexCell(double height, Map<HexAreaKey, Long> areas)
```
- **必修语义点**：`TerrainOperations.java:115` 涂地形会**静默抹平**编辑值 ⇒ 改 `existing.withHeight(...)`。
- **地形默认表**：落 `TerrainCatalog` 旁表，按 `TerrainType.food` 派生；**不得写 `low_hills`/`mountains`/`plateau`/`plateau_mountains` 字面量**（`RegressionGuardsTest:371-383` 会红）。
- **缺省读法**：无值 ⇒ 读地形默认，**以 RURAL 显示**。
- **操作**：增量（±）与全量赋值都要；走既有命令路径；**Map 自实现工具**；新命令**标 `GmOnlyCommand`**（反默认落地前，不标 = 静默放权）。
- **负向**：① hex 不在图 ⇒ 具名拒 + **零 revision**；② **涂地形不抹编辑值**（先设值再 `map.SetTerrain` ⇒ 值仍在，**改前必红**）；③ 未赋值 ⇒ 读回地形默认（plains 3,100,000 毫亩 / desert 0）；④ 老 JSON `{"height":0.37}` 解码成功且新字段为默认；⑤ `MapChangeSet` 仍 8 组件、`GameMap` 仍 9 组件。
- **非目标**：不动随仓 `worlds/v17levant.json`；不与 economy 联动（记一条将来定权威的口子）。

### 批次 3（sd 弃用）
- **3a 最小切片**：`decisionPackets` + `mergedEffectPlans` 两组件 + 4 条命令 → `ga.*`（含 `DecisionMakerId` 搬入）；新建 `GaState`/`GaSnapshot`/`GaChangeSet`/`GaCodec`/`GaRoundTripTest`。
  - **负向**：① 提交 `sd.UpsertDecisionPacket` ⇒ `Rejected` 且 **head 不变**；② 加 `GaState` 组件不改 `GaChangeSet` ⇒ 往返必红；③ `ga.*` 未注册 codec ⇒ 装配期 `IllegalStateException`（不许静默）。
- **3b 乙六步**：`DecisionMaker` 去 `affiliation` → gov/army 各持归属表与赋予命令 → 端口落 `ga-spi`、模块实现、app 注册 → `DecisionScopeFunctions` 键改 kind 字符串 → `SdInfoEntry.affiliations` 降为不透明受众标签、可见性判定搬 app → 删 `Affiliation`/`SdPayloads.requireAffiliation`，`DecisionAgentRunner`/`DiplomaticWriteSignature` 改走端口。
- **3c 外交 → gov**（连 `NationId`）；**3d 战斗 → army**（**先重挂判决面 `sd:combat.*` → `army:combat.*`**，再退役；保留 4/6 给 GM）；**3e sd 空壳退役**（根 pom −1 模块）。
- **改名不可避免**（`namespaceOf` 用类型前缀）。
- **不可逆点**：`Verdict.subject`（持久字段，`Breakpoints:88` 硬编码 `"sd".equals(subject.namespace())`）· 地址族（扔之前必须切完 20 处 `nations` 读者）· 命令类型串（失败**静默**）。

### 批次 4（收紧）
- 按 B4 细化：移动三条（`PlanRoute`/`PlanSparseRoute`/`CancelRoute`）**留给 DM**；`PlaceAt`/`SetStatus`/`SetStateDescription`/`SetVisionRadius` 与"map 命令/造钱/造人/建军"**收紧**。
- **不得与批次 1 混提交**（红点无法归因）。

---

## §5 文件所有权（避免互覆）

| 批 | 独占写权 | 禁碰 |
|---|---|---|
| 0b | `simos-army/pom.xml` | `simos-gov/pom.xml`、任何 `src/**` |
| 0a/0c | `simos-gov/src/test/**`、`simos-army/src/test/**` | 任何 `src/main/**` |
| 1 | `simos-ga-spi/**`、`simos-ga/**`(权限部分)、各域 `*/spi/*Handler.java`、`simos-app/.../Shell.java` | 状态组件、codec、变更集 |
| 2 | `simos-map/**`、`simos-app/.../Shell.java`(注册)、`ApiViews`/`MapHexTool` | `simos-economy/**`（不联动）、`worlds/**` |
| 3 | `simos-ga/**`、`simos-gov/**`、`simos-army/**`、`simos-app/.../Shell.java`、`simos-sd/**`(退役) | `simos-map` 的状态组件 |

**共用装配面**（`Shell.java` / `SimosToolSource.java` / 测试计数表）**必须串行**。

---

## §6 风险（如实）
1. **未跑 Maven 的空转风险**：本规划所有"会红/会炸"均为**断言源码 + 代码路径**级证据，非实测；每批以**真实 `validate`/`compile`/`test` 输出**为准。
2. **乙的波及面最广**（`Affiliation` ≈150 处 / 12 文件），是批次 3 里最容易漏的；3b 单独一门禁。
3. **前端未逐文件核**（`webui/**` 对 `/api/*` 的消费点只数了量）。
4. **`.claude/worktrees/**` 下 30+ 平行工作树未核**。
5. **`EconomySeeder`（5474 行）只读入口与类注** ⇒ 若将来联动，成本可能偏乐观。

---

## §7 控制方工作方式（用户 2026-10-12 裁定，替换"盲睡轮询"）

> 用户原话：「那要不就这样，**你派单完就停下，子Agent做完应该会自己给你发消息**」
> 前一句：「能用主动检测的类似命令等待子Agent吗？Sleep很容易一睡睡过头」

**规矩**：**派单后控制方直接停轮**，等 harness 的落定通知（后台子 Agent 完成时会主动推消息）。
**不要**用固定 `sleep` 轮询 —— 盲睡 N 秒通常白等 N−3 秒。

**实测事实（2026-10-12）**：
- `list_agents` **看不到** `subagent` 起的子 Agent（只列 durable teammate）；
- `job_list` **看不到**（只列控制方自己的 bash 后台作业）；
⇒ **没有"查子 Agent 状态"的直接 API**，只能靠通知。

**兜底手段（仅在确实需要主动探测时用）**：**带谓词的轮询 + 提前退出**，而不是睡满：
```bash
for i in $(seq 1 40); do
  if <可观测谓词>; then echo "第 $((i*3)) 秒命中"; break; fi
  sleep 3
done
```
实测：探测 `simos-army/pom.xml` 是否已改 + Maven 进程数 ⇒ **第 3 秒命中**（vs 盲睡 120 秒 = 白等 117 秒）。

---

## §8 批次 0 收工记录与教训（2026-10-12 晚）

### 8.1 交付（已提交）
| 项 | 提交 | 内容 | 控制方自跑验收 |
|---|---|---|---|
| **0b** | `b96c6af3` | `simos-army/pom.xml` 删 gov 禁列 + 加 dependency + 4 处文案；**gov 侧禁 army 一字未动** | `validate` rc=0、`BannedDependencies passed` ×11、BUILD SUCCESS、`[ERROR]`=0 |
| **0d** | `e3cb2b02` | 5 处文档更正（freeze.md ×2、AGENTS.md ×3、unit-integration-p2.md ×1） | 逐处 grep 复核 |
| **0a** | 随下一提交 | `simos-gov/src/test/.../change/GovRoundTripTest.java`（9 个 @Test） | **9/9 绿**；变异：给 `GovState` 加第 3 组件而不改 `GovChangeSet` ⇒ **红在 4 处**（`:80` 组件数 / `:95` 变更集多元素 / `:115` 并集缺项 / 构造期 `IllegalStateException: 未登记的组件`），还原 md5 逐字节一致 |
| **0c** | 随下一提交 | `simos-app/src/test/.../ArmyRecordCombatRevisionTest.java`（4 个 @Test） | **4/4 绿**；变异①改坏写口 ⇒ 红在"combats 恰 1 条"；变异②删同 id 具名拒 ⇒ 红在"第二次提交变 Committed" |

### 8.2 ★ 三处对派单模板的更正（**后续派单一律照此**）
1. **`-DfailIfNoTests=false` 在 surefire 3.6.0 下无效**（会让 `-pl` 里没匹配测试的模块 0.8s 假红）。
   正确开关 = **`-Dsurefire.failIfNoSpecifiedTests=false`**。
2. **变异自证的"还原重跑"必须让 mtime 前进**：`cp -p` 保留源文件旧 mtime ⇒ maven-compiler 判定"没变"、
   继续跑旧 class ⇒ **会误判"还原失败"**。用不带 `-p` 的 `cp` 或 `touch`（内容 md5 不变）。
3. **"写口坏了 head 照样 +1"**（实测）：`CommandBus.commit` **不判变更集是否为空** ⇒
   **revision 级判据必须同时断状态**，只断 head 是装饰。这一条对后续所有"revision 级判据"通用。

### 8.3 0c 落点更正（代理上报，控制方采纳）
`simos-army` 的 enforcer **禁 `simos-core`，且连 test scope 也查**（证据：`simos-core/pom.xml` 自己把
test-scope 声明仍列入禁列 + 用 `io.mosire:simos-map:*:*:test` 窄 `<include>` 开口子）
⇒ **"经 core 提交"在 `simos-army` 里结构上做不到**。0c 因此落在**组合根** `simos-app/src/test/.../`，
形制照抄同包既有 [SdPutInfoEndToEndTest](simos-app/src/test/java/io/mosire/simos/app/SdPutInfoEndToEndTest.java)
（真 `CoreSimos` + `bootstrapGenesis` + 断 `CommandResult.Committed(StateRef(main, rev+1))` + `head` + `replay`）。
⇒ **§5 文件所有权表更正**：凡"经 core 提交并断言 revision"的测试，一律落 `simos-app/src/test/**`。

### 8.4 控制方自身失误（已修）
`git add -A` 把在飞的 `simos-army/pom.xml` 卷进了文档提交（`79f9bb0e`）⇒ 已 `reset --soft` **拆成两个提交**
（`b96c6af3` pom / `ac596d84` 文档）。**教训：派单在飞时提交自己那一份，必须显式点名文件，不许 `git add -A`。**

### 8.5 批次 1 拆分（据 §一.5"按可独立编译的层拆"）
- **批次 1**：**权限声明与规则统一**（`ga-spi` 标记 + `ga` 规则/审计 + 各域打标签 + `Shell` 改判 + 删 `sd.` 前缀 + 删 `:903-914` 无条件 add）—— **行为等价**（66 集对称差 = ∅）
- **批次 1.5**：**模块自交 handler 清单**（把 `Shell` 里 142 个 `new XxxHandler()` 下放到各模块，app 只 `concat`）—— 结构性，零行为变化；独立可验收

---

## §9 批次 1 中期裁定（2026-10-12 晚）：标记落 `simos-util/spi`（**控制方批准**）

### 9.1 派单书前提被推翻（**控制方实测错误**）
派单书 §③.1 写「★ `ga-spi` 已被**全部 16 个模块**显式声明（实测）⇒ 声明标记落 `ga-spi` 零 pom 改动」——**该前提为假**。

**错误成因（记进教训）**：控制方当时的探测命令是
```bash
for p in simos-*/pom.xml; do grep -q "simos-ga-spi" $p && printf "✓ %s\n" "$(basename $(dirname $p))"; done
```
—— **`grep` 一个字符串在 pom 里出现 ≠ 该模块声明了它**：禁列里的 `<exclude>io.mosire:simos-ga-spi</exclude>` 也被计成"✓"。
（同族教训：本仓已多次踩"grep 命中 ≠ 事实"。**探测 pom 依赖必须解析 `<dependency>` 块、并把 `<exclude>` 排除**。）

**实测事实（解析 `<dependency>` 块）**：
| 面 | 结果 |
|---|---|
| **声明** `simos-ga-spi` | **仅 7 个**：army / economy / ga / gov / map / social / unit |
| **禁列** `simos-ga-spi` | 含 **`simos-actor`**（`simos-actor/pom.xml:106`，注释自陈"本模块不声明它（只可能经传递可见）"） |
| 五个需打标记的域（map/unit/social/economy/**actor**）是否都声明 `simos-util` | **全部 ✓** |

### 9.2 三条路的代价（子 Agent 给出，控制方复核）与裁定
| 路 | 后果 |
|---|---|
| (a) 标记落 `ga-spi`，actor 2 条不标 | DM 面 **66 → 64** ⇒ **等价性破、`hasSize(66)` 红** —— 违反本批核心判据 ❌ |
| (b) 标记落 `ga-spi`，actor 靠**传递依赖** import | compile/validate 会过，但 actor 的 pom 自陈"只可能经传递可见"，**等于引入"依赖传递不是契约"这一已被本仓定性为缺陷的形态** ❌ |
| **(c) 标记落 `simos-util/spi`** | util 是 map/unit/social/economy/actor **五个模块全部显式声明**的唯一下层 ⇒ **零 pom 改动、零 enforcer 例外、66 条一条不漏、等价性成立** ✅ |

**控制方裁定：批准 (c)。**
- **不与用户原话冲突**：用户「让 ga 统一鉴权」指的是**规则与审计**（那部分**仍在 `ga`**，未动）；标记只是"声明契约"的家。
- **且是自然落点**：`simos-util/spi` 里已经住着 `GmOnlyCommand`（它要取代的那个标记）、`CommandHandler`、`HandlerOutcome`、`ModuleCodec` —— 同包同形制。
- ★ 与上文 §1「甲组」的措辞对齐：批次 1 的**声明落 `util/spi`（不是 ga-spi）**，**规则与审计落 `ga`**。

---

## §10 批次 1.6a 收工：`GmOnlyCommand` 退役（2026-10-12 晚）

**结果**：95 文件（+239/−329）· 删接口 1 个 · 删 **51 处 `implements`**（actor 5 / army 3 / economy 28 / map 4 / sd 3 / social 7 / unit 1）+ 52 行 import
· `grep GmOnlyCommand src/main` **221 → 5**（残留 5 处全是自带"已退役"标注的历史留痕）。
**核心不变式**：四个面**逐条对称差仍全 = ∅**（142 / 66 / 66 / 2）；`DmUsableCommand` 声明面仍**恰 66**。
**门禁**：`clean compile` rc=0、18/18 模块、`[ERROR]`=0、`BannedDependencies` ×16（控制方自跑复核一致）。
`CommandPermissionRules` 审计 **3 → 2**（删"双标记互斥"—— 唯一标记后**类型上不可表达**），另两条 + 顺序守卫**原样保留**。

### 10.1 ★ 对派单书的事实更正：`sd.*` 是 **28** 条，不是 25 条
25（旧口径仅靠前缀排除）+ **3 条也标了 `GmOnlyCommand`**（`sd.DeleteNation` / `sd.SetArmyMasterGov` / `sd.DeleteDecisionMaker`）。
`51 − 3 = 48` 与 `GmOnlyCommand` 的 51 条精确吻合。⇒ **精确三分：142 = 66（DM 面）+ 28（`sd.*` 全在面外）+ 48（非 `sd.*` 且不打标记）**。
★ 后续所有涉及"25 条"的表述一律读作 **28 条**（判据更强，已实测满足）。

### 10.2 ★ 测量陷阱（新脚本已给出，后续派生一律用它）
上一批的 `derive_faces.py` 其 `dm_old` 表达式 = 「非 `sd.` 前缀 且 非 `GmOnlyCommand`」—— **依赖被删的标记** ⇒ 复用会把**测量伪影（66 → 114）误报成权限漂移**。
新脚本 `.superpowers/sdd/2026-10-12-gmonly-retire/derive_faces.py` 只派生"**当前生效的四个面**"，改动前后**同一套口径** ⇒ 可直接做对称差。
**教训：派生脚本不得依赖"正在被删的机制"作为基线表达；前后比较必须同一口径。**

### 10.3 控制方批准的三处越界（代理主动上报，均判**可接受**）
| # | 越界 | 裁定 |
|---|---|---|
| D1 | 除 `GmOnlyCommand` 外还改了 **36 处裸 `GmOnly` 简写注释** | ✅ 可接受 —— 「非 GmOnly ⇒ 可嵌令」是本批后**失效的推断**（28 条 `sd.*` 既非 `GmOnly` 标记、也不可 DM），留着会误导。**只改注释、零逻辑** |
| D2 | 顺手更正 `UnitAdjustCompositionTool.java:18` | ✅ 可接受 —— 该注释**先于本批就错**（其 handler 在 `ae8f9bd4` 就已标 `GmOnlyCommand`，2026-10-02 收紧时漏改） |
| D3 | `AdjustCompositionHandler.java:34` **保留**裸 `GmOnly` | ✅ **正确** —— 那是"四线调查 A 线 A7 实测…"的**逐字引文**，改它 = 篡改调查留痕（§五.4） |
| D4 | 跑了 `test-compile`（超出"只过 compile"的字面范围） | ✅ **值得鼓励** —— 它不执行任何测试，只为把"9 个文件编译不过"从**预测**升级为**实测**，给测试代理准确输入 |
| D5 | `derive` 公开签名 4 参 → 3 参 | ✅ 不可避免（标记退役的直接后果）；已证零测试读者、`Shell` 不受影响 |

### 10.4 ★ 新开一批：工具面 16 处旧术语**运行时字符串**（不在本批授权内）
`CatalogTool.java` **15 处** + `EconomyAdjustTool.java` **1 处**，合计 **16 处运行时字符串**仍是旧口径，例如
`:83/:89`「非 GmOnly，可嵌进决策令…」、`:411`「★ GmOnly 标记只挡住令/RegisterEffect/决策人 catalog 三条路径，不拦命令总线」、`:728/:851/…/:915`「…；GmOnly）」。
**未改**：它们是**工具面可见输出**（不是注释），改字 = 改行为。★ 其中 `:411` 与 `:83/:89` 的措辞在"一处声明"口径下已**不精确**（"非 GmOnly"不再蕴含"可嵌令"）⇒ **另开一批统一改口径**（属工具面文案变更，应走评审）。

---

## §11 ★ 控制方流程漏洞：格式门禁被错当成"测试阶段"（2026-10-12 晚）

**现象**：批次 1.7 的测试代理跑 `tools/mvn-lock.sh clean verify` ⇒ **rc=1**，红在
```
spotless-maven-plugin:3.10.2:check on project simos-util:
    src/main/java/io/mosire/simos/util/spi/DmUsableCommand.java
        @@ -1,18 +1,17 @@   （google-java-format 要重排该文件 javadoc 的折行）
Spotless.Java is keeping 89 files clean - 1 needs changes to be clean …
UtilSimos .......................................... FAILURE [  6.698 s]
其余 16 个模块 SKIPPED
```

**根因 = 控制方**：批次 1 与 1.6a 给实现方的**收工门禁只写了 `clean compile`**（§10 原文），**没有跑 `spotless:check`**
⇒ **基线 `ae068da8` 本身就是 `spotless:check` 红的**。三个批次（1 / 1.6a）新建或手写改了 **117+ 个文件**，
格式从未被检过。测试代理的判定正确：**不是文档错、不是它夹具错，是实现批遗留**。

**判决**：`spotless:apply` 全仓跑一次（**格式器，零逻辑**），然后 `spotless:check` **必须 rc=0**。

**★ 教训（写进后续派单模板）**：
1. **格式门禁属于"提交前必跑"，不属于"测试阶段"** —— §一.5 的"只写到编译过"是**实现方**的交付门槛，
   但**控制方提交前必须跑 `spotless:check`**（它不执行测试、不碰 jar、几秒钟）；
2. **凡批次新建了文件（`?`/`A` 状态），提交前一律先跑 `spotless:apply` 再 `spotless:check`** ——
   人手写的 javadoc 折行几乎必违规（本次一次就中）；
3. `spotless` 在第一处 FAILURE 后**其余模块全部 SKIPPED** ⇒ 只看到 1 个违规 ≠ 只有 1 个违规，
   **修完必须重跑**，直到全绿（可能要多轮）。

**★ 处置（控制方执行，等测试代理跑完 Maven 后，§一.4 不许并发改 Java）**：
① `tools/mvn-lock.sh spotless:apply`（全仓）→ ② 出文件清单 + md5 前后 + **证明零逻辑改动**
（过滤掉注释/空白/纯折行后的 `+/-` 行必须为空）→ ③ `spotless:check` rc=0 → ④ `spotbugs:check` → ⑤ 重跑全量测试取最终数。

---

## §12 ★★ 控制方工作口径修订（用户 2026-10-12 重申 §三.0）

> 用户原话：「**也许我们应该把测试环节放到最后，反正这是一轮总的架构调整**」

**这正是用户 2026-09-26 的既有裁定（AGENTS §三.0）**：「把变异自证也留到最后环节且砍到只有关键项，**先一口气把业务代码都做完**」
「**测试也不要写**，写完业务代码统一写」。前几轮控制方把**测试批次插在批次之间**（1.7），**偏离了该裁定** ⇒ 本节改回。

### 12.1 修订后的每批门禁（**"测试环节"与"静态门禁"要分开**）
| 类别 | 每批做不做 | 说明 |
|---|---|---|
| `compile`（main） | ✅ **必做** | §三.0 明写「**编译必须绿 = 唯一的硬底线**」 |
| **`spotless:check`** | ✅ **必做**（提交前） | ★ 见 §11 —— 它是**格式器**不是测试，属"提交前必跑"；不跑就会像批次 1 那样把红线攒进基线 |
| **`spotbugs:check`** | ✅ **必做**（提交前） | 同上：静态分析不是测试；本仓历史上它逮过真缺陷（`DLS_DEAD_LOCAL_STORE`） |
| `test` / `test-compile` / 新写测试 / 变异自证 / 全量 `verify` 总验收 | ❌ **全部留到最后统一做** | §三.0 的"收尾期"（守恒式 · 不丢失 · 静默付 0 · 断粮 四类关键项做变异自证） |

### 12.2 ★ 代价与对冲：**"既有测试仍在跑"这张网会在中段失效**
§三.0 把"既有测试仍在跑（现成的网）"列为开发期**该做**的事 —— 但架构大改会**成批**让既有测试编译断/断言红
（批次 1 已经：**9 个文件编译断 + 3 个用例红**）。
⇒ 本轮架构调整期内，该网**注定失效**，故必须对冲：
**维护一份「已知红点台账」**（落 `.superpowers/sdd/2026-10-12-arch-round-known-reds.md`），逐批追加：
> 批号 · 提交 · 红的 file:line · 红的**归类**（编译断 / 断言钉旧机制 / 夹具喂错面）· 预期在最后测试阶段**怎么处置**（删 / 迁移 / 改为新契约）

**没有这份台账，最后的统一测试阶段就无法归因**（分不清"老架构老测试该删"与"新代码弄坏了"）—— 这正是 §一.10 说的
"老架构老测试、新架构新测试"在**架构调整期**必须有的配套。

### 12.3 特殊例外（仍然每批做）
**`GovRoundTripTest` / `ArmyRecordCombatRevisionTest` 这类"铁律 5 / 铁律 2 的护栏"**（批次 0a/0c 已建）
是**新架构的护栏**，不是"老测试"：后续批次若给 `GovState`/`ArmyData` 加组件，**它们红 = 必须当场处理**（不是攒台账）。
⇒ 判据：**护栏（防静默漂移）当场修；旧测试（钉旧机制）攒台账**。

---

## §13 批次 3.0 收工：`ga` 摘掉 core 依赖（A1 落地，2026-10-12 晚）

**结果**（提交面 11 文件 / +219 −244）：`grep -rn "io.mosire.simos.core" simos-ga/src/main` **29 行 → 0 行**；
`simos-ga/pom.xml` 删 core dependency + **把 core 加进禁列** + `<message>`/`<description>`/头部依赖图同步；
9 个 ga 工具全部改吃 `ga-spi.ToolWritePort`（`BatchOutcome{Committed(ref,outcomes)|Rejected(outcomes)|Conflict(current,outcomes)}`
是 core 三结局的**逐字段镜像**；`Receipt`/`BatchReceipt` 回报 commandId）。
**四件门禁**（控制方自跑复核）：compile rc=0（18/18）· spotless rc=0 · spotbugs rc=0（**17/17 归零**）· `-pl simos-ga -am validate` rc=0（`BannedDependencies passed`）。
**负向验证**：加回 core dependency ⇒ validate **rc=1** + `<--- banned via the exclude/include list`；还原 **md5 逐字节一致**（`63444495c9b6854dfdd27a7c4db6a3b2`）并重跑全绿。

### 13.1 ★ 控制方派单书自相矛盾（代理越界，控制方**追认**）
任务书 ⑤ 把 `SimosToolSource.java` 列为禁碰，但把工具构造器从 `CoreSimos` 换成 `ToolWritePort` 后
**app 侧 8 处 `new XxxTool(core, …)` 必然编译不过** ⇒ **目标与文件所有权二选一**。
代理按 §一.8「纯技术分歧 ⇒ 控制方当场裁定」**自行裁定"必须改"**并做了最小改动（8 行 + 1 条注释），
**形制与同文件既有的 6 处（`toolWrites`/`gaWrites`/`mapWrites`/`unitWrites`）完全同款**。
**裁定：追认**，且它反而让「app 只负责组装」**更成立**（命令 id / `initiator` / `correlationId` 的生成进一步集中到 app 适配器）。
★ **§5 文件所有权表更正**：批次 3 系（含 3.0/3a…3e）的写权栏**必须包含 `simos-app/.../tools/SimosToolSource.java` 与 `Shell.java`**（共用装配面）。

### 13.2 ★ 它顺手修掉一个潜在错值 + 保住一处易碎契约
1. **`correlationId` 此前是"假定等于 commandId"**；改后由装配面生成并**经 `Receipt` 回报真实值** ⇒ 不再有错值可能。
2. **没有**把 `DecisionPacketPayloads.fold` 换成 `ToolResults.fold` —— 后者折的是"只有结局"的独立视图，会把 `submission`
   排到 JSON 最前、**改掉这 5 个工具返回的键序**。保留原折叠体 ⇒ 键序逐字不变。

---

## §14 批次 3a 中期裁定（2026-10-12 晚，控制方）

### 14.1 ★ 派单书自相矛盾（控制方错）：`test-compile rc=0` ⊥「不许改其他测试文件」
**代理实测**：移除 `SdState` 的两个组件 ⇒ **`simos-sd` 的测试必然编译断**（不是断言红），共 4 个文件：
`SdFixtures.java:100-103,216-231,234+,271+,308+`（`base.decisionPackets()` / `withDecisionPackets` / `withMergedEffectPlans` / `P2`/`MP1`/`draftPacket`/`mergedEffectPlan`）·
`SdRoundTripTest.java:127,130-131,136,157,170-171` + `:60 hasSize(14)` ·
`SdCodecTest.java:226-232,281-343,360-370` · `SdStateInvariantTest.java:119-131`。

**裁定：撤掉本批的 `test-compile rc=0` 要求。**依据：
- §12.1 明写 **`test`/`test-compile` 留到最后统一做**；§12.2 明写**编译断属台账类**（架构调整期既有测试网注定成批失效）；
- 本批若强行要求 test-compile 绿，等价于**每批都插一个测试迁移批** —— 正是用户 2026-10-12 重申 §三.0 时要避免的。
⇒ **本批门禁 = `compile` + `spotless:check` + `spotbugs:check` + 两处护栏自证**；sd 测试的编译断**逐条进台账**。

### 14.2 ★ 迁移面比派单书更窄：**零值类型迁入 ga**（代理判定，控制方批准）
派单书只想到"`DecisionMakerId` 要留在 sd"；代理实测**范围更大**：`DecisionPacket`/`MergedEffectPlan`/
`DecisionPacketId`/`MergedEffectPlanId`/`FormattedCall`/`MergedEffect`/`PacketStatus`/`CallStatus`
**全被 sd 的测试引用** ⇒ 把任一搬进 ga，sd 侧（含测试）就得声明 `sd → ga` ⇒ **成环，pom 禁列当场拒**。
⇒ **只搬两张表的"状态组件"（`Map` 字段）**，值类型全留 sd，`ga` 反向引用（**既有 `ga → sd` 方向，不新增边**）。
`GaState` 的两组件类型即 `Map<DecisionPacketId, DecisionPacket>` / `Map<MergedEffectPlanId, MergedEffectPlan>`。
**批准。** ★ 遗留（记入 3b/3e 的输入）：这批值类型与 `SdPayloads` 最终要随 sd 退役一并安置；
且 `sd:packet.<id>` 之类**地址族**是否改挂 `ga:`，属 3b 的待裁项。

### 14.3 `SdPayloads` 最小可见性放宽（代理判定，控制方批准）
4 个 handler 用 `SdPayloads`（729 行、包私有、**sd 其余 handler 仍在用** ⇒ 不能搬）解析载荷。
代理把**类 + 8 个方法**（`parse`/`requireText`/`optionalText`/`requireLong`/`optionalLongValue`/
`requirePacketStatus`/`optionalFormattedCalls`/`requireMergedEffectPlan`/`logRejected`）放宽到 `public`，
其余一字不动。**批准** —— 替代方案是在 ga 复制 ~150 行载荷解析 = **线格式的第二个拼写点**，违背本仓反复强调的"一处声明"。

---

## §15 `effects` 组件的模块边界（用户 2026-10-12 裁定）

> 用户原话：「**那让它依赖unit呗，这不是Army模块管理的吗？照道理来说Army和Nation都得依赖Unit作为地图实体**」

### 15.1 先讲清 `effects` 是什么（控制方调查结论）
**决策层的「未来／条件动作队列」** —— 此刻定下、**将来或条件满足时才发生**的动作（"令"与"时间"之间的桥）。
- **Trigger（何时发生）7 档**：`AtOrAfterTick` · `AfterTicks` · **`UnitAtHex(UnitId, HexCoord)`** · `ThresholdKills` · `OutcomeSelected` · `And` · `Or`
- **Action（发生做什么）4 档**：`PutInfo(Address,…)` · `SetStage` · **`RecordCasualties`（实测无生产者 + `SdTimeParticipant` 的 `default -> {}` 静默忽略 ⇒ 死路）** · `EnqueueUnitCommand(type, payloadJson)`（不透明）
- **Kind 5 档**（`SCHEDULED`/`ON_CALL`/`BE_PREPARED`/`BRANCH`/`SEQUEL`）· **Status 5 档**（`PLANNED`→`COMMITTED`→`FIRED`/`CANCELLED`/`EXPIRED`）
- **推进者** `SdTimeParticipant`（每世界日求值 Trigger，满足则执行 Action 并标 `FIRED`，日志 `SD_EFFECT_FIRED`）；**创建/取消** `sd.RegisterEffect`/`sd.CancelEffect`；**挂靠** `Directive.effects`
- ★ 耦合面很窄：**只有 `Trigger.UnitAtHex` 一处引用域类型**（unit + map）；`PutInfo` 用 util 的 `Address`，`EnqueueUnitCommand` 已是不透明载荷

### 15.2 裁定
1. **`ga` 可依赖 `unit`** —— 用户口径：`unit` 是**地图实体基础设施**（"Army 和 Nation 都得依赖 Unit 作为地图实体"）。
   同理 `map`（`UnitAtHex` 还要 `HexCoord`）⇒ **打开 `ga → unit` + `ga → map` 两条边**，**不算破例**。
   ★ 无环已核：`unit`/`map` **都已显式声明 `ga-spi`**，而**没有任何模块允许依赖 `ga` 本体** ⇒ 单向无环。
2. **战斗耦合的四档归 Army 的账上**（用户："这不是 Army 模块管理的吗"）：`ThresholdKills` / `OutcomeSelected` /
   `SetStage` / `RecordCasualties` 服务的是**丁组已废除的战斗阶段机**（且 `RecordCasualties` 已是死路）
   ⇒ 它们**随 3d 退役或归 army**，**不由 ga 承担**。
3. ⇒ **`effects` 搬 ga 可行**，且 **`Trigger.UnitAtHex` 不必改成不透明载荷**（依赖 unit 是正常的）。
   ⇒ 上一轮 §15.1 列的选项 (c1)/(c2) **作废**；采纳"选项 (b) 的 unit+map 子集"。

### 15.3 排期
`effects` 的执行顺位挪到 **3b-4**（它要动 `simos-ga/pom.xml`：允许面 +2、禁列 −2）；
正在跑的 **3b-1（`directives`+`verdicts`）零 pom 改动，不受影响**。

---

## §16 批次 3b-1 中期裁定：`IssueDirective` 的跨命名空间写（控制方选 (A)）

### 16.1 问题（代理实测，控制方复核成立）
`CommandBus` 一条命令**只写一个命名空间**（`:414 namespaceOf(type)` → `:506-512 applyToCandidate` 只交给该 namespace 的 codec）。
而 `IssueDirectiveHandler` 今天**一条命令写两个组件**（都在 sd 所以合法）：`directives` 表 + sd 侧 INFO 覆盖层的
**执行原文**（`sd:directive.<id>` / key=`intent` / `sourceDirective=<id>`，`:141-176`）。
改名 `ga.IssueDirective` 后 `namespaceOf` = `"ga"` ⇒ handler 只能回 `GaChangeSet`，而 `info` 本批不搬 ⇒ **朴素搬法编译过、提交时炸**。

### 16.2 ★ 口径溯源（§一.12）
「单条 Command 必须自带 INFO 作为执行原文」出自 **sd 自己的设计文档**
（`specs/2026-09-20-sd-simos-brainstorm.md:70`、`specs/2026-09-20-sd-simos-design.md:179/307/392`），
★ **不在用户设计原稿 `2026-10-02-architecture-design-source.md` 里（零命中）** ⇒ **是模块级设计约定，不是用户裁定**。
且「同批第二条命令补 `sd.PutInfo`」**是本仓既有成例**：`RejectDirectiveTool:291` 实证 `new ToolWritePort.Request("sd.PutInfo", …)`；
gov/army/app 另有十余个工具的文案写着"进 `sd.PutInfo` 行动记录"。

### 16.3 裁定：**选 (A)**（控制方技术裁定，非用户问题）
`ga.IssueDirective` 只写 `directives`；**执行原文改由同批第二条命令 `sd.PutInfo` 落**（增一个**可选**载荷字段 `sourceDirective`）。
三个生产写路径同批提交：`IssueDirectiveTool`（DM+GM）· 三条决策渠道 `AbstractCoreDecisionChannel` · GM 的 `simos.command.submit`。
**理由**：① 该约定的实质是"**一次决策 = 一条 revision = 执行原文与令不可分割**"，同批两条命令落同一条 revision、**原子生效** ⇒ 语义成立；
② (B) 对**所有**路径有损，(A) 只在一条窄路径有损；③ ★ **(A) 的缺口是过渡态**：`info` 搬进 ga（3b-3）后
`ga.IssueDirective` 即可**一条命令写两组件** ⇒ **缺口自动关闭**。
**目录面仍 143**（不新增命令类型）、`GaState` 仍 4 组件、地址族不变、权限四面不变。

### 16.4 ★ 记入台账的**暂时性缺口**（收口时点 = `info` 搬进 ga）
绕过工具/渠道、**直接 `CoreSimos.submit("ga.IssueDirective", …)`** ⇒ **只落令、不落执行原文**（今天这条路径两者都落）。
受影响面（代理实测）：`SdQueryService:156-168,192-201`（`/api/sd/directives` 的 intentInfo、决策人文档面、`ApiViews:5955-5956`）、
`VoidAdjudicationTool:237-244` 的 restore。

### 16.5 批准代理的另一条自行处置
`SdResolver:116-122`（`sd:directive.<id>` 存在性）与 `DeleteDecisionMakerHandler:112-118`（删决策人前的"仍被 Directive 引用"守卫）
走「**端口在 sd、实现在 ga、app 装配注入**」（`ga → sd` 是既有方向），**地址族串一字不改** ✓ 与 3a 的成环禁令一致。

---

## §17 批次 3b-2 早期裁定 + 跨模块 `sd.*` 字面量地图

### 17.1 ★ 控制方派单书又一处"比设计书更窄"（代理越界，控制方**追认**）
**问题**：`sd.PutInfo` → `ga.PutInfo` 改名，而该字面量在**三个被本批派单书禁碰的模块**里硬编码，且都进真批次提交：
- `simos-gov/**`：`GovRecruitPlan:92` · `GovDismissPlan:63` · `GovAbsorbUnitPlan:80` · `GovRetireStaffPlan:84` ·
  `GovCreateOfficePlan:97/100/103/106` · `RegionClearStructuresPlan:58`（被 7 个工具装进 `submitBatch`）
- `simos-army/**`：`AssignArmyGovPlan:71` · `FormatUnitPlan:57`
- `simos-unit/**`：`LevyRegionTool:106` · `SpawnArmyPlan:84` · `RaiseUnitPlan:96`
**不改的后果 = 运行时行为破坏**（批内一条未注册命令 ⇒ **整批原子回滚**），**不是编译错** ⇒ 这类比编译断更难发现。
**裁定：追认**（纯字面量改名，零逻辑/零 import/零 pom）。依据：
① **设计书 §5 的文件所有权表本来就`simos-gov/**`、`simos-army/**` 划进批次 3 的独占写权** —— **派单书 ⑤ 比设计书更窄**；
② §13.1 同款先例（目标 ⊥ 文件所有权 ⇒ 最小改动 + 追认）；③ 用户「sd整个弃用」要求 `sd.*` 字符串最终清零。
★ **§5 写权更正**：批次 3 系（3b/3c/3d/3e）的写权**必须包含 `simos-gov/**`、`simos-army/**`、`simos-unit/**`**
（用于命令类型串同步）—— 命令改名**从来不只是改名字**。

### 17.2 ★★ 跨模块 `sd.*` 硬编码字面量地图（控制方扫描，供 3c/3d/3e 当输入）
| 模块 | 硬编码的 `sd.*` 命令 | 归属批次 |
|---|---|---|
| **gov** | `sd.CreateDecisionMaker` · `sd.DeleteDecisionMaker` · `sd.SetDecisionMakerAccess` · `sd.SetDecisionMakerProvider` · **`sd.PutInfo`** | **3b-2（本批）** |
| **army** | **`sd.PutInfo`**（→3b-2）· `sd.SetArmyMasterGov`（→**3d**） | 3b-2 / 3d |
| **unit** | **`sd.PutInfo`**（→3b-2）· `sd.CreateArmy`（→**3d**） | 3b-2 / 3d |
| **ga** | `sd.AddCombatStage` `sd.CommitCombatOutcome` `sd.CreateArmy` `sd.CreateCombat` `sd.RecordCasualties` `sd.SetArmyMasterGov` `sd.SetStageOutcomeTable`（→**3d**）· `sd.CreateNation` `sd.SetDiplomaticRelation` `sd.RecordDiplomaticEvent`（→**3c**）· `sd.RegisterEffect` `sd.CancelEffect`（→**3b-4**）· `sd.CreateDecisionMaker` `sd.DeleteDecisionMaker` `sd.SetDecisionMakerAccess` `sd.SetDecisionMakerProvider` `sd.PutInfo` `sd.ResetDecisionMakerConversation` `sd.RunDecision` `sd.StartDecision`（→**3b-2**）· `sd.RejectDirective` `sd.DecisionDocs` `sd.DecisionResults`（工具名，随所属域） | 分批 |
| **app** | 23 个不同串（全谱）：`sd.AddCombatStage` `sd.AdjudicateTick` `sd.CancelEffect` `sd.CommitCombatOutcome` `sd.CreateArmy` `sd.CreateCombat` `sd.CreateDecisionMaker` `sd.CreateNation` `sd.DeleteDecisionMaker` `sd.DeleteNation` `sd.PutInfo` `sd.RecordCasualties` `sd.RecordDiplomaticEvent` `sd.RegisterEffect` `sd.ResetDecisionMakerConversation` `sd.RunDecision` `sd.SetArmyMasterGov` `sd.SetDecisionMakerAccess` `sd.SetDecisionMakerProvider` `sd.SetDiplomaticRelation` `sd.SetStageOutcomeTable` `sd.StartDecision` `sd.VoidAdjudication` | 分批 |

★ **系统性问题**：命令改名**必须同步全仓所有硬编码字面量**（含**其他模块**的），否则**运行时整批回滚且无症状**。
⇒ **每一批改名前，先跑这张表的扫描**（`grep -rhoE '"sd\.[A-Za-z]+"' --include=*.java <模块>/src/main | sort -u`）。
★ 次要债：这些是**各模块自己维护的常量**（一条命令在 N 个模块各有一份字面量）—— 与"一处声明"有张力；
但把它们收进 `ga-spi`/`util` 常量会让域模块引用框架常量，**本批不动**，记一条债（收尾期评估）。

---

## §18 ★★ 剩余批次的结构性纠缠（控制方发现）与解开次序

### 18.1 纠缠（三处互为前置）
| # | 想要做 | 被谁挡住 | 为什么 |
|---|---|---|---|
| **乙** 归属归模块（gov/army 持 `Map<DecisionMakerId, NationId/ArmyId>`） | ⇐ 需 `NationId` 在 gov（**3c**）、`ArmyId` 在 army（**3d**） | gov/army **禁 sd** ⇒ 拿不到 sd 的 id |
| **3c** 外交 → gov（`NationId` → gov） | ⇐ 需 sd 先不再用 `NationId` | `Affiliation.Nation(NationId)` **在 sd**；sd 若引用 gov 的 `NationId` ⇒ `sd → gov` = **pom 禁列当场拒** |
| **3d** 战斗/军队 → army（`ArmyId`/`CombatId` 等 → army） | ⇐ 需 sd 先不再用它们 | `Trigger.OutcomeSelected(CombatId,…)` / `Action.SetStage(CombatStateId,…)` / `Action.RecordCasualties(LossRecordId)` 在 sd 的 **`Effect`** 里；`Effect` 要到 **3b-4** 才搬 ga ⇒ sd 仍引 army 的 id |

**根因**：`Affiliation`（sd）与 `Effect`（sd）这两个"最后一个 sd 用户"把 sd 焊在了 `NationId`/`ArmyId`/战斗 id 上。

### 18.2 ★ 解开次序（控制方裁定；每步都只为下一步解锁，不做无关事）
1. **3c**：外交 3 组件（`nations`/`diplomaticRelations`/`diplomaticEvents`）+ 4 handler → **gov**；`NationId`/`Nation`/
   `DiplomaticRelation`/`DiplomaticEvent` 全搬 gov。
   ★ **必须同批处理 `Affiliation.Nation(NationId)` 这个冲突点** —— 方向 = 用户裁定的「**归属归模块**」：
   gov 持 nation 归属表（key = `DecisionMakerId`），`Affiliation` 的 nation 支随之收窄/由 gov 承担。
   ★ `DecisionMakerId` 落 **`simos-util/spi`**（依据 **ADR-1 原文：「新增契约一律放 `io.mosire.simos.util.spi`」**
   + `DmUsableCommand` 先例；util 是 gov/army/sd/ga **全部**允许依赖的唯一下层 ⇒ 零 pom 改动、零环）。
2. **3d**：战斗 4 组件 + 军队身份（`armies`）+ 7 handler → **army**；`ArmyId`/`CombatId`/`CombatStateId`/
   `CombatStageId`/`LossRecordId`/`CombatOutcomeId` 等 → army。
   ★ **先重挂判决面** `sd:combat.*` → `army:combat.*`（`Breakpoints`/`VerdictFreezer`；**不可逆点**，用户已批"肯定啊"）。
   ★ 战斗耦合的四档 `Trigger/Action`（`OutcomeSelected`/`SetStage`/`RecordCasualties`/`ThresholdKills`）
   按用户口径「**这不是 Army 模块管理的吗**」**随本批退役或归 army**（`RecordCasualties` 早已是死路）。
3. **3b-4**：`effects` → ga（开 `ga → unit` + `ga → map` 两条边 —— 用户已批「那让它依赖unit呗」）；
   此时战斗四档已退役 ⇒ `effects` 不再引 army 类型 ⇒ ga **不必**依赖 army。
4. **乙 收尾**：删 `Affiliation` 本体 + `DecisionMaker.affiliation` + `SdPayloads.requireAffiliation`；
   `SdInfoEntry.affiliations` → **不透明受众标签集**（可见性判定搬 app）；`DecisionScopeFunctions` 键改 kind 字符串；
   `DecisionAgentRunner`/`DiplomaticWriteSignature` 改走 `ga-spi` 端口。★ 三条都**不得**引入 `sd → ga`。
5. **3e**：sd 空壳退役（根 pom −1 模块、`ArchitectureGuardsTest.MODULES` −1、cmd 串清零）。

★ **每步的硬判据**：**不得新增任何被 enforcer 禁的依赖边**（`sd → gov`/`sd → army`/`sd → ga`/`ga → core`）；
若某步做不到，**停手上报**并给"哪条边必须开"的方案 —— 不擅自开边。

---

## §19 批次 3c 收工：外交整体搬 gov（控制方追认全部偏离）

**结果**：108 项（89 M / 14 D / 5 新增）、+973/−1620、**零 pom / 零 worlds**。
`GovState` 2→**5**、`SdState` 8→**5**、**4 条命令 `sd.*`→`gov.*`**（gov 的第一批命令）、
`NationId`/`Nation`/`NationTag`/`Diplomatic*` 全套类型 + `RegionDeleteGuard` 搬 gov。
`SdResolver` 的 `sd:nation.<id>` 支**整支删除**（用户已批的不可逆点）。
**`DecisionMakerId` → `simos-util/spi`**（57 个 main 文件改 import；ADR-1 原文 + `DmUsableCommand` 先例；**零 pom 改动、零环**）。
**`Affiliation.Nation(NationId)` → `Affiliation.Nation(String)`**：gov 新增 `NationAffiliations`（**裁定方，唯一形态与唯一裁点**）+
`NationAffiliationLookup`（端口在 gov、实现在 app）+ `AppNationAffiliations`（唯一拼装点：读 ga 的 `Affiliation.Nation` 自称 × gov 的
`nations` **裁定**，解析不了/国家不存在的引用**裁剪丢弃**）；`NationScope` 读不到归属 ⇒ **四命名空间 deny-all**（不抛、不回落、不给全量）；
★ **`DecisionScopeFunctions` 一行未改**（"键改 kind 字符串"是 §18.2 第 4 步，不在本批）。

**门禁**（控制方自跑复核）：compile rc=0（18/18）· spotless rc=0 · spotbugs rc=0（**17/17 归零**）·
`validate` rc=0（`BannedDependencies passed` ×16，★ **零新增被禁边**：三条互禁原样）· `GovRoundTripTest` **10/10**。
**变异自证**：给 `GovState` 加第 6 组件 ⇒ **红在 4 处**，还原 **md5 逐字节一致**并重跑绿。

### 19.1 ★ 代理第三次纠正控制方的事实错误
派单书写「`GovCodec` 注册 `NationId` 与 `DiplomaticEventId` **两个**键」——**漏了复合键 `DiplomaticRelationKey`**；
漏它则 `diplomaticRelations` **非空即解码炸**（空表测不到）。实为 **3 个**键。**追认 3 个。**

### 19.2 控制方追认的 8 条偏离
| # | 偏离 | 裁定 |
|---|---|---|
| **D1** | gov 是 nation 归属的**裁定方**而非**写入方**（写入仍在 `ga.CreateDecisionMaker` 的 affiliation 载荷） | ✅ **追认** —— 让 gov 自己写就要**新增 `gov.Assign*DecisionMaker` 命令 ⇒ 目录 143→144，撞本批红线**；且规划 §18.2 本就把"各模块持归属表 + 各自赋予命令"排在**乙第 4 步**。本批做到"能做的最大且 fail-closed" |
| **D2** | `Affiliation.Nation` 收窄为 `String`（不透明引用） | ✅ **追认** —— fail-closed（解析不了即裁剪 ⇒ deny-all）；且乙收尾会**整条删除 `Affiliation`** |
| **D3** | `ga.CreateDecisionMaker` 给不存在的国家 id：从"当场具名拒"变"建成 + 该决策人 nation 面 **deny-all**" | ✅ **追认** —— D1 的直接后果，方向是 fail-closed（**不是提权**） |
| **D4** | 多搬/删 4 件结构性必需物（`NationTag`、`RegionDeleteGuard`、`Army` 旧构造删除、gov 侧新建 `GovPayloads`/`GovSnapshots`） | ✅ **追认** |
| **D5** | 键读侧实为 **3** 个 | ✅ **追认**（见 19.1） |
| **D6** | `GaToolViews` 两个方法签名必须改（ga 结构性看不见 gov 类型）+ 连带 `SdDiplomacyTool`/`SdDiplomaticEventsTool`/`AppGaToolViews` | ✅ **追认** —— 同 §13.1/§17.1 的"目标 ⊥ 文件所有权 ⇒ 最小改动" |
| **D7** | 4 条命令的**工具面资源声明仍写 `sd` 命名空间**（新增 `GOV_NAMESPACE` 要动本批禁碰的 `simos-ga-spi`） | ✅ **追认** —— 运行时影响为零（DM 侧声明与迁移前逐字相同）；**留给批次 5 收口** |
| **D9** | `SdSnapshots.map(...)` 成死代码但保留 | ✅ **追认** —— 随 sd 退役一并删 |

---

## §20 批次 3d-1 收工：军队身份搬 army（控制方追认 4 点）

**结果**：54 项（43 M / 4 D / 7 新增）、+834/−677、**零 pom / 零 worlds / `src/test` 恰 1 个文件**。
`ArmyData` 1→**2 组件**（`armies` 放第一组件 ⇒ 线格式键序不变）；`SdState` 5→**4**；
2 条命令 `sd.*`→**`army.*`**（`army.CreateArmy`/`army.SetArmyMasterGov`，仍仅 GM）；
`Affiliation.Army(ArmyId)` → **`Affiliation.Army(String)`**（与 3c 的 `Nation` 支逐字同构）；
army 做裁定方（`ArmyAffiliations` + `ArmyAffiliationPort` 端口在 army + `AppArmyAffiliations` 实现在 app）；
`ArmyScope` 读不到归属 ⇒ **四命名空间 deny-all**（不抛、不回落、不给全量）。
**门禁**（控制方自跑）：compile rc=0 · spotless rc=0 · spotbugs rc=0（17/17）· `validate` rc=0（×16，**零新增被禁边**）·
`ArmyRoundTripTest` **12/12**。权限四面 **143/66/66/2** + 声明面 66 逐条不变。

### 20.1 控制方追认的 4 点
| # | 事项 | 裁定 |
|---|---|---|
| **D4** | 派单只写"删 `sd:army.<id>` 支"，代理**删了并在 army 侧补了 `army:army.<id>`**（+ `ToolSupport.subjectVisible` 新分支） | ✅ **追认** —— 理由成立：**gov 删 `sd:nation.<id>` 时自己有第二条读面，而 army 的身份读面只有 resolver 这一条** ⇒ 删而不补 = **功能净损失**（不是改名）；且 `army:army.<id>` 属 `army:` 命名空间，**不在用户"sd:* 直接扔"的范围内**。用户口径「单个 Army 单位的本质不是被 Army 模块打上自定义数据集的 Unit」也支持 army 拥有自己的身份地址 |
| **D6** | 2 条 ga 工具的资源声明仍写 `sd` 命名空间（留给批次 5） | ✅ **同意缓做** —— 代理的失效方向论证正确：同时改 `resources()`（审批面）与 `writeResources()`（权限判定）而不同步时，失败方向是"**该拒的放行**"，与本批"只改名不改数"的红线冲突；运行时影响为零。**批次 5 与 gov/战斗族资源面一起正名** |
| **点 3** | `ArmyAffiliationPort` 今天**无域内注入点**（army 无删除命令），代理**没造 `INSTANCE` 假接缝** | ✅ **接受** —— 造一个"看着能注入、其实无人注入"的常量才是坏形态 |
| **点 4** | `ArmyRoundTripTest` 的绿是在两个无权修改的兄弟测试文件（`ArmyDataTest` 8 处 + `ArmyFixtures:73`）的**临时补丁**下取得的，已 md5 逐字节还原；`ArmyData.java` 因后续 `spotless:apply` 覆写了备份，改用"无残留 grep + 行为等价 + 门禁重跑绿"三重核对 | ✅ **接受** —— 三重核对 + 两个兄弟文件逐字节 md5 已足够；**`simos-army` 的 test 源集在本批结束后仍编译断** ⇒ 收尾期先改那两个文件（已入台账） |

### 20.2 ★ 代理自我更正（记功）
它先前报的「资源面正名要动 `simos-ga-spi`」**是错的** —— 实际只需动 `simos-ga/src/main`（基类加一对 `ARMY_NAMESPACE_WRITE`
与 `armyNamespaceWriteResources()`，形制与 army 自己的 `AbstractNarrowWriteTool` 逐字相同）。它**主动在台账里更正了自己**。

---

## §21 批次 3d-2 收工：旧战斗族退役 + 判决面重挂 + 补回 4/6（控制方追认 7 条 + 1 项裁决）

**结果**：67 项（40 M / **27 D** / 0 新增）、+694/−3042、**零 pom / 零 worlds**。
**退役（不是搬迁）**：sd 旧战斗族 27 文件 —— 5 个战斗 id + 10 个战斗模型 + 5 个 handler + `SdCombats` + ga 侧 6 个工具类。
**`SdState` 4 → 1**（只剩 `effects`）。★ 这就是 **D-012 那句"清理另批"的落地**。
**判决面重挂**：`Breakpoints.acceptsVerdictSubject` 改为**按断点分别判命名空间**（D1/D3/D6 → `army:combat.*`；**D2 仍 `sd:decision-maker.*`**）
—— ★★ **代理逮到一个控制方也没预见到的陷阱**：旧代码是**同一个提前返回**，只把字面量 `"sd"` 换成 `"army"` 会**连 D2 一起打死**。
★ `Breakpoints` **零 army import**（只比对 `Address.namespace()` 与根段 `kind()` 两个字符串）⇒ **未新增依赖边**。
**补回 4 = `atRevision`**：`CombatRecord` 第 8 组件（记录级 —— 与 `ArmyChangeSet` 的"整条替换"粒度**逐字对齐**，避免"阶段戳 vs 记录戳谁说了算"的**第二处解释权**）；
3 个 handler 各取 `state.meta().ref().revision()`；**3 条命令的线格式一字未变**（戳不进载荷）。
**补回 6 = 三段地址**：`army:combat.<id>:stage.<sid>`（与 `combat(id)` **同源同 id 文本**）；可见性在**冒号处截断**按记录所在格判（否则 `CombatRecordId.parse("c-1:stage.s1")` 会失败 ⇒ 阶段恒不可见）；
★ **第四段 `:outcome.<oid>` 有意不补**（用户已废第 5 项 ⇒ 补它等于**复活被废的那一档**）。
**四档 Trigger/Action 退役**（逐档给了理由）；存活档语义一字未改。
**权限四面**：catalog **143 → 138**（−5）· 可嵌令 **66（∅）** · 可入队 **66（∅）** · 落点 **2（∅）** —— **与预期逐值一致，无意外变化**。
**门禁**：compile/spotless/spotbugs（17/17）/validate（×16）**全 rc=0**；`ArmyRoundTripTest` **12/12**；
变异自证两版：A（给 `ArmyData` 加组件不改变更集）⇒ **红 2 条**；B（只动 `CombatRecord` 组件 = 本批真实改动面）⇒ **仍 12/12 绿**（= 本批该绿且确实绿，**不是漏网**）。

### 21.1 控制方追认的 7 条偏离
| # | 偏离 | 裁定 |
|---|---|---|
| D1 | 同批删 `/api/sd/combats` 端点 + 改 5 个前端文件（派单书未列） | ✅ **追认** —— 数据源已退役，端点留着就是死路由 |
| D2 | 单位视图 `combat` 键删除（**键缺席，不是 null**） | ✅ **追认** |
| D3 | `DecisionAdjudicationService` 缺 army 切片 ⇒ **空表**（不抛，fail-closed） | ✅ **追认** —— 与 3c/3d-1 的 deny-all 同向 |
| D4 | `SdLogSource.SD_COMBAT` / `SdLog.combat()` **保留**（已无生产者） | ✅ **同意保留** —— 稳定 id 不悄悄改（本仓既定纪律）；**随 3e 收口** |
| D5 | 两处 ga javadoc 例句 `sd:combat.c1` → `army:combat.c1` | ✅ **追认** |
| D6 | 不补第四段 `:outcome.<oid>` | ✅ **追认** —— 见上文（复活被废档 = 违背用户裁定） |
| D7 | 未实现"按 revision 自拟 id"的 army 工具（id 形态属**工具层**另批） | ✅ **追认** —— 域类型不替调用方定 id 形态 |

---

## §22 批次 3b-4 收工：`effects` 搬 ga，**`SdState` 归零**（控制方追认 + 2 项裁决）

**结果**：38 项（27 M / 9 D / 3 新增目录 → 46 项）、tracked +714/−1445、新增 +1047、**零 worlds**、**1 处 pom**（= 用户批准的 `ga → map + unit`）。
`GaState` 6 → **7**（`effects` 放**末位** ⇒ 既有六键**线格式键序未动**）；**`SdState` → `record SdState()`（0 组件）**；
`sd.RegisterEffect`/`sd.CancelEffect` → **`ga.*`**；**`SdTimeParticipant` → `GaTimeParticipant`**（选①整类搬 ga，顺带把 3b-2 为跨切片写引入的注入口**整条删除**，参与者收成**单切片**）。
★ **行为等价的硬证据**：逐日主循环**归一化 diff = 72 行 → 72 行，仅 3 处有意差异**（`todayBegin` 两件都带 —— 触发器求值不读 info ⇒ 零影响；2 处日志来源 id `SD_TICK→GA_TICK`）⇒ 触发/落地逻辑**一行未改**。
★ **`ga → map+unit` 无环证据**：`unit`/`map` 各自**禁 `ga` 本体**且只声明 `ga-spi`；全仓**声明 `simos-ga` 的只有 `simos-app`** ⇒ `app → ga → {map,unit} → ga-spi → util` 单向。**负向验证**：给 ga 加回 gov ⇒ `validate` rc=1；还原 md5 逐字节一致。
**权限四面**：catalog **138→138**（对称差 = 2 条改名）· 可嵌令 **66（∅）** · 可入队 **66（∅）** · 落点 **2（∅）** · 声明面 **66（∅）**。**`sd.*` 命令类型数 2 → 0**。
**门禁**：compile/spotless/spotbugs（17/17）/validate（×16）全 rc=0；`GaRoundTripTest` **11/11**；变异自证：加第 8 组件 ⇒ **红 4 处**，还原 md5 `017f9da0…` 逐字节一致。
★ 补充冒烟（入仓外）：**空 `SdState` 的 codec 往返**通过（快照 `"state":{}` 解码相等、变更集 `{}` 解码相等、`apply` 正常）⇒ 挡住"0 组件 record 读不回来"的铁律 5 风险。

### 22.1 控制方裁决 2 项
| # | 事项 | 裁定 |
|---|---|---|
| 1 | **`sd:effect.<id>` 地址族现为空候选**，本批**不补 `ga:effect.<id>`** | ✅ **同意不补** —— 与 3d-1 给 army 补 `army:army.<id>` 的判据对照：**army 的身份读面只有 resolver 这一条**（删而不补 = 功能净损失），而 **effects 全仓零生产读者按地址取它**（唯一消费点是 GUI 在令视图里列 `Directive.effects` 的 id 串）⇒ 不满足"必须补"的条件。**留 3e 一并裁**（`sd` 退役时该支整体消失） |
| 2 | **`Directive.effects()` 外键有意不回迁构造期**（effects 已同树、技术上可以） | ✅ **同意** —— 本批红线是行为等价；回迁会新开"**搬迁前能构造、搬迁后当场抛**"的失败面（属行为变化）。留 3e/后续批 |

### 22.2 ★ 代理第三次纠正派单书事实
① **`SdSpec` 不存在**（我派单里写的；实际同步的是 `SdSnapshots`）；② 我写"`allowedCommandTypes = CommandFaces.drainableTypes()`"是语义描述，**实际注入点是 `declarations.dmUsableTypes()`（内容相同）且未改**；
③ **`GaRoundTripTest` 自 3d-1 起就编译不过**（3 处 import 指向已搬走的 id 类型 + `Affiliation.Army(String)`）⇒ 本批一并修正，否则护栏跑不起来。
★ 另：它发现**台账的最后一节是 3d-1，3d-2 从未追加**（3d-2 那批自称追加了）⇒ 它按"3d-1 + 规划 §21"为基准记账并注明。**控制方已知悉，3d-2 的台账增量以规划 §21 为准。**

---

## §23 乙收尾（§18.2 第 4 步）收工：归属彻底归模块 + 删 `Affiliation` 本体

**结果**：65 项（52 M / 4 D / 9 新增）、**零 pom / 零 worlds**、test 面**只有 3 个护栏文件**。
**`Affiliation` 本体 + `AffiliationTargets` + `SdPayloads.requireAffiliation/optionalAffiliationSet/readAffiliation` 全删**；
`DecisionMaker` → **6 参**（`affiliation` 去掉 ⇒ **裸决策人**成形；带 `affiliation` 键 ⇒ **具名拒并指路三条新命令**）。

### 23.1 ★★ N = 3 条赋权命令（catalog 138 → **141**）
`gov.AssignDecisionMakerNation` · `gov.AssignDecisionMakerGov` · `army.AssignDecisionMakerArmy`
—— **幂等 / 可改 / 可清**（缺席/null/空串 ⇒ 清除；同值再赋 ⇒ 空变更集），**逐条具名拒**（决策人不存在 / 国家不在 `nations` /
单位不存在或不带 `GovernmentFormation` / 军队不在 `armies`）。表 = **新状态组件放末位**（`GovState` 5→**7**、`ArmyData` 2→**3**）
⇒ 既有键线格式次序未动。★ 三条一律**不打 `DmUsableCommand`**。
**权限四面**：catalog **138 → 141**（授权）· 可嵌令 **66（∅）** · 可入队 **66（∅）** · 落点 **2（∅）** · 声明面 **66（∅）**。

### 23.2 ★★ 用户那句「新加模块 ga 还得显式地重新添加？不可能的」的**兑现证据**
`DecisionScopeFunctions` 键 = **kind 字符串**（各模块自报常量 `NationAffiliations.KIND="nation"` / `GovAffiliations.KIND="gov"` / `ArmyAffiliations.KIND="army"`）。
**加一个新模块的步骤**：① 模块内加归属表 + 赋权命令（`KIND`/`describe`/`affiliationText` 都在模块内）；② app 加一个 `DecisionScopeFunction` + 注册一行 + `AppDecisionMakerAffiliations.SOURCES` 加一行；**③ `ga` / `ga-spi` 一个字都不用改**。
**控制方复核**：`simos-ga/src/main` + `simos-ga-spi/src/main` 内 `Affiliation` 类型引用 = **0**（唯一命中是 `DiplomaticWriteSignature:24` 的 **javadoc 注释**，描述"本批删掉的最后一个归属种类 switch"）；两处 `switch (kind)` 与归属无关（`FrozenToolKind` / 工具自己的收件人词表）。

### 23.3 「读不到归属 ⇒ deny-all」证据
`NationScope:131` / `ArmyScope:118` / `GovScope:157` 三处 `denyAll()` **一律四命名空间 `ResourceScope.none()`**（不抛、不回落、不给全量）；
`DecisionScopeFunctions:98` 是**裸决策人**那条（同样四 none）；`:105` 是**未注册 kind ⇒ 抛**。
**等价性论证**：单轴 DM 时并集 = 该轴输出（其余轴给全 `none()`，`X ∪ none = X`）；三处范围算法**一行未改**，变的只是"归属从哪读"。

### 23.4 控制方追认的 6 条偏离 + 已知债
D1 删两个端口（`NationAffiliationLookup`/`ArmyAffiliationPort`，理由消失 ⇒ 拒造假接缝）· D2 新增 `DecisionMakerExistencePort`
（拆分"创建/赋归属"必然新增的判据）· **D3 多轴归属取并集**（跨模块互斥结构性做不到；**单轴时与迁移前逐字段相同**）·
D4 受众标签定为 `kind:ref` 串（代价 = 视图/载荷形状变化 O5/O6/O7）· D6 同批删 `SdSnapshots.units`。
**已知债**：`SimosSdReportTool:351` 仍按工具自己的收件人词表拼标签（**既有债**，第二个拼写点，失效方向 fail-closed）·
**删决策人不清理 gov/army 的归属行**（留下的是够不着的行；id 被重建会继承旧归属）—— 已入台账 §D。

---

## §24 批次 3e 收工：**`simos-sd` 整模块退役**（§18.2 第 5 步）

**结果**：188 项（107 M / **38 D** / **42 R**（git mv））、+905 / **−7340**、**零 worlds**。
`simos-sd` **目录已不存在**；42 个 `git mv` + 11 处删除 —— 残壳 44 个存活类型整体迁入 `simos-ga`
（`ga.model` / `ga.id` / `ga.adjudication` / `ga.channel` / `ga.spi` / `ga.resolve.GaResolver`）。
`ChannelAdmission` → `ga/channel/`（★ 给 `ga-spi` 会**成环**：它要读 `ga.model` 的 `DecisionMaker`/`AccessLimit`；`LANDING_POINTS` 一字未改）。
★ **`SdResolver` 迁 ga 后端口对整条删除**（旧形态因 `sd` 不得依赖 `ga` 才要注入 `DecisionTablesLookup`；同模块后直读 `GaState`，不留"只有一条线"的接缝）。
★ **D2 判决面改挂 `ga:decision-maker.*`**：`Breakpoints`（`"sd"` → `"ga"`，kind 判据一字未改，**零 ga import**）+ `DecisionAdjudicationService` 同串。

### 24.1 ★ 四个独立信号互相印证（控制方复核）
| 信号 | 值 |
|---|---|
| 根 `pom.xml` 的 `<module>`（逐条列出） | **16** |
| 实际 `simos-*/` 子目录 | **16** |
| `compile` 反应堆条目 | **17**（16 模块 + parent） |
| `validate` 的 `BannedDependencies passed` | **15**（16 模块 − `simos-app` 不设 enforcer） |
| `spotbugs:check` 归零 | **16/16**（上批 17/17） |

### 24.2 ★ 代理逮到的成环陷阱
`ChannelAdmission` **不能**放 `ga-spi`：它要读 `ga.model` 的 `DecisionMaker`/`AccessLimit` ⇒ 会造出 `ga-spi → ga`，
与既有的 `ga → ga-spi` **成环**。落 `simos-ga/ga/channel/` 正确。

### 24.3 控制方裁决与追认
| # | 事项 | 裁定 |
|---|---|---|
| **D1** | **资源声明面的标识符 `"sd"` 有意未改名**（`ToolResources.SD_NAMESPACE` / `ResourcePaths.sd` / `ToolSupport.SD_NAMESPACE`，**126 处**引用） | ✅ **同意缓做** —— 它与状态切片同名但属**权限声明面**（第三种东西）；正名要动 `simos-ga-spi` + `simos-util`（本批写权外），而**DM 侧无 `ga` 档** ⇒ 硬改的失效方向可能是"**该拒的放行**"（正是 3d-1 §D.1 拒绝承担的风险）。台账 3c §E.1 / 3d-1 §D.1 / 3b-4 §H.1 已**三处**排给**批次 5**，且要求**逐工具资源键 A/B 证据**（"计数不变 ≠ 每条键逐条相同"） |
| **D3** | 删了 `simos-sd/src/test/**` **27 文件**（派单"整目录删除" vs "不要改其他测试文件"两处授权冲突，取前者） | ✅ **追认** —— 模块已退役，那些测试测的是已删除的代码；`git show 3ee4aea5:<path>` 可取回 |
| D2 | `Sd*` 类名保留（`SdInfoEntry`/`SdInfoId`/`SdInfoIds`，模块已不存在） | ✅ **追认**（改名判别力为零；3e 后它们住 `ga.model`） |
| D4 | `simos-ga` 不为已退役的 `simos-sd` 留 enforcer exclude 留痕 | ✅ **追认** —— "防同名模块复活"由 `ArchitectureGuardsTest` 的清单一致性判据承担，比死条目**更有牙** |
| D5/D6 | 改了 `simos-app/src/main/resources/**`（`log4j2.xml` 删 sd 两行、`panels.js` 文案） | ✅ **追认** |
| D7/D8 | 根 pom 不留痕 · `GaLogSource.GA_RESOLVE` 新增表项 | ✅ **追认** |
| ★ | **`GaRoundTripTest` 本批未修**（它自 3d-1 起编译不过，import 断口 3 → **23** 条） | ✅ **同意不越权** —— 派单只授权 `ArchitectureGuardsTest` 一个测试文件。它**如实记"本批无铁律 5 变异证据"、不冒充通过**，处置正确。⇒ **列为收尾期最高优先项**（一行 sed） |
