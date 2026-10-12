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
