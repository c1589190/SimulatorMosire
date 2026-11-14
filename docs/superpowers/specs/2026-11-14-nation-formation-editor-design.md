# 国家与完整军队编制编辑：目标运行设计

> **状态**：有效（2026-11-14）
> **本文取代**：无。增补现有国家、Unit 编制与 Army 工具；不取代世界时间序列。
> **父序列**：[节点编辑运行设计](2026-11-13-node-docs-runtime-target-design.md)的节点编辑、读回与工件链；[地图自由工具](2026-11-14-map-reference-tools-design.md)的自由工具调用。

## 1. 目标运行序列

1. **定位与冻结目标**：GM 工具或网页编辑器读指定 branch/revision 的稳定 NationId、ArmyId、UnitId；写入始终带 expectedRevision。网页一次提交时冻结目标，切换节点不得把旧结果应用到新节点。
2. **领域计划**：gov 将国家名、领土并集、颜色及可选中央 GOV 推导为 map/unit/gov/social 命令；army 将一份递归 JSON 编制树推导为 social/unit/army 命令。计划只读同一 SimulationState，不写存档、不调用模型、不写经济/actor/ga。preview 可选，正常一次 apply 即完成。
3. **完整校验与原子提交**：稳定 ID、存在性、重复节点、树深度/规模、坐标、跟随位置、人员与装备整数、GOV 类型及领土冲突先校验。完整有序命令批交 ToolWritePort.submitBatchWithReceipt，经 Command → 各域 ChangeSet → Revision。任一后续命令拒绝时整批回滚，禁止部分国家/半棵树/孤立人口。无效及 stale revision 返回具体原因。
4. **真实世界状态**：国家实体归 gov；区域几何与颜色归 map；编制树、位置、装备、附属关系与 ArmyFormation 归 unit；人员归 social；Army 身份归 army。Army 仍只认 GOV，不新增 NationId 字段。单位省略人员不凭空复制父级人数；统计分别显示本级与子树合计。
5. **共同移动**：新下级默认 attached=true；坐标缺省时在创建时写实为父级位置，不能留空。既有 UnitTimeParticipant 只移动 formation root 并同步 attached 后代；detach 的旅仍有隶属关系，带其附属营独立移动。编辑位置或重编时必须维护整支位置一致，禁止制造 attached 跨格。
6. **统一读回与显示**：国家读视图按精确 nation:<id> 标签合并区域，使用 Nation.name，返回并集格数、区域与 GOV；网页国界、填色和文字使用同一视图。军队读视图从 Unit/Social 派生完整树、直接人数/合计人数、装备、GOV、坐标与 attached。PNG 编制图经既有 ArtifactStore 出站为真正 MCP ImageContent；网页提供可缩放、可滚动编制图和 JSON 编辑入口。
7. **网页一体化操作**：现有地图编辑/单位编辑中提供国家与编制入口，能载入现有对象、编辑一份 JSON、一次保存、读回图形；可从当前选格取得落点/领土。HTTP 仅桥接固定的上述领域工具，不能任意执行任意 GM 工具。共同冻结/刷新/错误处理住宿主，不复制写入状态机。最终验证 editor 模式无经济运行也能建国家、GOV 与完整师旅营。

日志锚点：国家创建/更新、人口工单、单位创建/重编、Army 更新沿既有领域日志记录 ID 与数量；拒绝 INFO 具名，不记录整份大 JSON。验收锚点见 §6。

## 2. 来源与裁定影响

用户原话（2026-11-14）：

> 注意其中要确保易用性，包括一站式、一体化、一个工具调用就能解决的单单位军队编制等（例如，你要创建一个师，旗下有好几个旅、营，你肯定不想一个一个单独创建，这时候传一整个json进去安排明白肯定是非常好的选择，最好还能有图渲染出来看它们的隶属关系？）；此外还有模块边界问题，这个我不多说了，你先把功能写出来，后面再统一检查即可

此前要求：国家创建和领土修改、军队创建和编制修改；营默认随旅、旅默认随师共同移动；将来重建也门、埃塞俄比亚、厄立特里亚、索马里四国。

| 裁定 | 影响阶段 / 运行后果 | 不涉及 / 取代情况 |
|---|---|---|
| 一次传完整 JSON | 2、3、7：一次领域工具调用、一个原子批；无需逐营调用 | 不强制 preview/确认流程；新增入口不删除旧原语 |
| 默认一同移动 | 4、5：附属与隶属分开，默认 attached；落点必须真实存在 | 不新造移动引擎；修复省略子位置的缺陷 |
| 模块边界后续统一检查 | 2、4、6：实现归 gov/army/unit，app 只桥接装配，最终门禁统一核验 | 未授权复制领域状态/逻辑到 app；铁律未被取代 |
| 人口家户权威、Army 只认 GOV | 4、6：复用 Social 工单与现有 GOV 关联 | 不增加旁路人数/国家归属表；原裁定继续有效 |

## 3. 工具形状

### 3.1 国家

`simos.gov.editNation`（GM-only）：target + `mode:create|update`、`nationId`、`name`、`color:#RRGGBB`、`hexes:[{q,r}]`、可选 `territoryMode:replace|add|remove`（默认 replace）、`overlapPolicy:reject|transfer`（默认 reject）、`government:{unitId,name,position:{q,r}}`、`dryRun`、`reason`。create 必须给完整名称/颜色/非空领土；update 未给字段保持原值，明确给空集合的语义不可偷换。中央 GOV 为无经济依赖的空编制 Unit + GovernmentFormation + jurisdiction；SetGovFormation 自动挂入的 hh-gov-<unitId> 必须同批通过 Social 工单建立为空家户（0 人），不得留下悬空 household 引用，标明不是完整财政/决策人系统初始化。更新若省略 government 保留既有 GOV，不重建。

国家领土权威仍为精确 nation:<id> 区域的并集。工具创建确定的 homeRegionId；编辑已有国家时将目标并集放 homeRegion；其他同标签区域保留 ID、几何及其他元数据，仅解除其 nation 标签，避免双重国家领土表示（结果列出解除标签的区域）。不得误改不同标签区域。replace/add/remove 均按并集计算，重复格/未知格/非法色值拒绝。区域模型禁止空几何，因此目标国家不得清空；transfer 仅允许其他国家区域扣格后仍非空，否则具名拒绝整批，提示先调整对方区域结构。允许的 transfer 保留其实体、元数据和非国家区域；reject 返回冲突国家/格。不得暗中转移领土或删除被引用的区域。

新 `gov.UpdateNation` 原语修改既有 Nation 的名称/homeRegion/budget 等指定字段，保留其他 gov 组件并要求 homeRegion 的 tag 精确匹配该 NationId。旧 create 的精确 tag 验证同步修复。

`simos.gov.nations`（GM-only）：target、可选 nationId、`render`。返回国家并集、颜色、GOV 关系、真实名称及地图 PNG（render 时）。读取/渲染与网页复用 gov 视图；map 模块不引用 gov。

### 3.2 单个 Army 的完整编制

`simos.army.editFormation`（GM-only）：target + `mode:create|update`、`armyId`、可选 name、masterGov、`root:{id,name,role,position:{q,r},personnel,equipment:{type:count},speed,mobilityPerMille,attached,children:[...]}`、`dryRun`、reason。

示例（已有中央 GOV）：

```json
{"mode":"create","armyId":"demo-division","name":"演示师","masterGov":"demo-government","root":{"id":"demo-div","name":"第1师","role":"DIVISION","position":{"q":10,"r":5},"children":[{"id":"demo-bde-1","name":"第1旅","role":"BRIGADE","children":[{"id":"demo-bn-1","name":"第1营","role":"BATTALION","personnel":500,"equipment":{"步枪":450,"卡车":20}},{"id":"demo-bn-2","name":"第2营","role":"BATTALION","personnel":500}]}]}}
```

关键约束：

- 节点 id 明确且稳定；不按数组下标派生身份。role 复用现有 ArmyFormation.role，允许 DIVISION/BRIGADE/BATTALION 及自由兵种职责文本，不新增平行编制权威。
- create 新节点默认人员 0、装备空、speed=1、mobilityPerMille=1000、子节点 attached=true、位置从父节点写实；root 必须明确坐标。HQ 可无人员。每个节点均带 ArmyFormation，masterGov 与 Army 保持一致。
- update 保留所有未给字段；树中未提及的节点保留、不自动删除。提供 children 表示把这些节点编到该父节点之下，非整表删除。新节点允许加入；重复/循环/跨其他 Army 掠取节点拒绝。更改 Army.root 的语义不在本次 update 中，必须返回具名错误。
- attached=false 可独立落点，仍属于组织树；attached=true 不允许不同于父节点的显式坐标。涉及重编和位置变更时复用 unit 原语并同步附属整支，不能复制单位对象漏字段。
- personnel 是本级直接人员目标，不含下属；GM 编辑会通过 Social 工单增减真实成员批次（成年男性默认值须在工具描述和结果显式说明，不冒称真实人口结构）。省略即保持；负数、小数、溢出拒绝。设置人数是编制校准，不是假装从地方完成招募或经济开户。
- household 指向 UNIT(id)，Unit.households 与 Social 一致；装备只落 unit。已有非本工具创建家户必须用真实成员读数，不得覆盖/丢失其他 Social 状态；无法安全调整时具名拒绝，不能偷偷另挂一份重复人员。
- 上限：最多 512 个节点、深度 16；人员与装备整数计数在既有域范围内，解析前后均防溢出。预览与实际提交复用同一计划。
- 现有 Army.name 若需修改，新增归 army 的窄更新 handler；现有 Unit 基本参数无法用原语编辑时新增 unit 窄 handler。都保留完整 record 其他字段。

`simos.army.formation`（GM-only）：target，可选 armyId（无则列表），`render`（默认 true）。有 id 返回完整组织树和 PNG；直接数/子树总数区分，附属线/独立机动线区分。大型树可分页或高度受限 PNG + 完整 JSON，不得静默漏节点冒称完整。

## 4. 子设计嵌入与责任范围

| 责任区 | 所属阶段 / 输入输出 | 持久化、失败与验证影响 |
|---|---|---|
| gov 国家计划、UpdateNation、国家读视图与渲染 | 2–4、6：State + JSON → 命令批 / 派生视图 / PNG | 不增 gov 顶层组件；复用 GovChangeSet；精确标签、合并去重与转移原子性测试 |
| army 编制计划、军队更新、树读视图与 PNG | 2–4、6：State + 递归树 → Social/Unit/Army 批 | 不存第二棵树或人数；失败回滚、图和直接/合计人数验证 |
| unit 创建位置修复及必要窄原语 | 4、5：已有命令 → 完整 UnitChangeSet | 全字段保留/往返；真实推进证明默认共同移动及 detach 行为 |
| 模块自有网页资产、app 宿主装配 | 1、6、7：固定工具 HTTP 桥 → 同一领域工具 | 不复制计划，固定工具白名单、GM-only、冻结目标、XSS 安全文本、错误不改变节点 |

文件范围：gov/army/unit 的 main 源码与模块自有 webui 资源；app Shell/ToolSource/GUI/render 的必要装配和共享网页宿主；模块索引。simos-map 仅必要通用区域能力，不准反向依赖。ga-spi 仅真正中性契约。不改经济公式或默认世界播种。

## 5. 现状依据与非目标

依据：[只读审计](../reports/2026-11-14-nation-army-capability-audit.md)及[探针](../reports/2026-11-14-nation-army-capability-evidence.json)。现有 CreateUnitHandler 接受省略子坐标但 UnitState.effectivePosition 只读自身；UnitTimeParticipant 已同步附属后代。旧 spawnArmy/GovCreateOffice 包含暂停经济命令，不能作为 editor 入口直接复用。

不在本次功能实现中：真实四国年份/实际控制区裁定、公开军力编制考据、经济与薪酬可靠性修复、自动战力值、军事 AI 多年推演、用粗演示边界冒称精确疆域。已有地图与参考图不得重建覆盖。前端应清晰注明自带样例为虚构编制。

## 6. 验收与负向用例

1. editor 空白世界已有地图上，一次 editNation 建国家/领土/颜色/GOV；一次 editFormation 建师→至少两旅→多营，部分下级不传坐标；无经济/actor/ga 命令。读回所有 stable IDs、Social 人数和位置正确。
2. 一次 update 改名/装备/直接人数/加入营/改隶属或 detached，并保留未提及节点、军俸政策、其他 households、区域元数据和无关模块。
3. 冲突领土默认拒；显式 transfer 才从他国扣格；多区域并集不双计数，国家名/颜色在网页和 PNG 一致。
4. 后序非法单位或拒绝工单不能留下前序对象；stale revision、无效 GOV、未知格、重复节点、循环、负数/小数/溢出、超限、attached 跨格、越其他 Army、非 GM 调用拒绝。真实 batch atomic rollback 用 Core 集成证明。
5. 独立真实世界推进（不放 JUnit 长跑）证明师/旅/营一起移动；detach 一旅后其营跟随它，师带其他旅移动。冷启动后树、人员、领土、图可读回。
6. 真 Chromium 操作完整 JSON 提交与读回图形、节点切换/冲突处理；MCP 实际 tools/list/call 获得 PNG ImageContent。模块边界/精确工具统计/完整 full gate 通过；测试 Owner 统一写正负测试，关键项做有意义变异自证。

## 7. 修订记录

- 2026-11-14：按用户新指令新增本设计；没有改动既有国家/编制权威，只补齐一体化编辑与显示入口。核对 RegionOperations 非空几何不变量后，原“清空次区域”改为解除其国家标签；消灭他国某一完整区域的 transfer 暂具名拒绝，防止删除仍被引用区域。四国数据仍等待年份与国界口径，功能实施不受该信息阻塞。
- 2026-11-14 实现审阅修正：原“国家只发 map/unit/gov”遗漏 SetGovFormation 自动挂入的空 GOV 家户。允许同批 Social 工单创建该 0 人家户，维持 Unit.households 引用真实存在；仍不初始化经济/Actor/GA 系统。
