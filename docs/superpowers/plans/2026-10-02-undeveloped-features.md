# 未开发功能清单（2026-10-02）

> 记录用户或运行时明确提出、但当前领域模型/命令面/MCP 工具面尚未实现的功能。
> 这里只登记“没有开发”，不在这里设计细节；真正开工时另开批次与计划。

## 外交/附庸/朝贡
- [ ] **称臣纳贡 / 附庸与朝贡关系**
  - 现状：没有“附庸国 / 宗主国 / 朝贡”领域模型，也没有对应命令与 MCP 工具。
  - 今天能做的只有：
    - 军事占领：`map.UpdateRegion` + `province.apply` + `unit.SetJurisdiction` + GOV 直接管辖；
    - 部队移动、驻扎、战斗；
    - `sd` 层的 Nation / Army / DecisionMaker 只是身份与决策主体，不表达外交依附关系。
  - 缺：
    - 附庸关系实体（附庸 Nation → 宗主 Nation/GOV，自治度、义务、期限）；
    - 朝贡支付（粮/布/银/人力；周期、上限、违约、拒贡）；
    - 宗主权利（抽调兵、设税、驻军、否决外交、册封/废立）；
    - 决策人侧接口（提出称臣/纳贡/拒贡、宗主索贡）；
    - GM 工具（建立/解除附庸、调整贡额、强制履约）；
    - 读口/GUI（地图上区分宗主/附庸/占领）。
  - 结论：先记着，不在本轮实现；当前占领区按“军事占领 + 直接 GOV 管辖”处理。

## 大蜀国策（1445 秋）可行性调查（2026-10-02）

> 来源：用户给出的四条国策 + 外交交互机制（n tick 决策 → n+1 tick 中央政府紧急回应）。
> 调查方式：只读检查 `simos.command.catalog`、相关命令 handler/tool 源码、当前世界（head 126 / tick 120）。
> 结论：**四条国策当前均不能原样直接执行**；下面按“可行 / 部分可行 / 不可行”登记缺口。

### 1. 北谷残兵打散编入蜀军 + 留守备 + 休整 + 全军转攻铁门坎
- [ ] **缺少“部众/兵力转移”命令**
  - 现状：`unit.MergeFormation` 只挂子树、不合并 member；`unit.SplitFormation` 只 detach 既有子节点、不拆兵力；`simos.gov.absorbUnit` 只把**无编制人口单位**吸收进 GOV 编制，明确拒 ArmyFormation 源；没有 `unit.TransferMembers` / `unit.MergeStrength` / `unit.SplitStrength`。
  - 影响：北谷残兵无法“打散编入”现有蜀军，只能整队 attach/reparent，或由 GM 用 `unit.SetStrength` 手工加减（多 revision、非原子、无守恒校验）。
- [ ] **北谷残兵本身没有实体/数额**
  - 当前世界 83 个单位中没有北谷残兵/降卒单位；GSim 档案只写“残部受降/部分突围”，没有可导入的残兵人数。
  - 若要执行，需先由 GM 裁定人数并以 `unit.CreateUnit` 建单位，或直接写入 Info 作叙事记录。
- [ ] **没有“留少量守备”的拆兵命令**
  - `unit.SplitFormation` 不能按人数拆；守备队只能用 `unit.CreateUnit` 新建 + 主军 `unit.SetStrength`/`unit.ApplyCasualties` 手工减员，再 `unit.AttachUnit`/parent 挂到编制树；多命令、非原子。
- [ ] **“休整”只有状态位**
  - `unit.SetStatus(RESTING)` 可设；但 `RESTING` 只影响速度因子（500‰）与 merge 限制，没有恢复、士气、补给、训练、readiness 领域模型；没有“休整数日”效果。
- [ ] **“全军转攻铁门坎”只有 GM 裁决式交战**
  - 移动有 `unit.PlaceAt` / `unit.PlanRoute` / `unit.PlanSparseRoute`；交战有 `sd.CreateCombat` + `sd.AddCombatStage` + `sd.SetStageOutcomeTable` + `sd.CommitCombatOutcome` + `sd.RecordCasualties`，但没有“攻击/围攻”领域命令，也不会自动接战；当前 (34,-55) 没有守军/工事实体。

### 2. 接管北谷 + 免税半年 + 北谷侯一家接到成都
- [x] **接管北谷**：已有 `大蜀北谷占领区-gov`（PROVINCE、辖 P01/P02、上级 `大蜀-gov-central`、已配编/拨款/DM）。这一项当前世界已具备。
- [ ] **免税半年没有“免税/期限”实体**
  - 可用的只有 `unit.SetTaxRate`（0..1000‰，region 必须在 GOV jurisdiction 内）；当前占领区两省税率已为 0。
  - 到期恢复需要 `sd.RegisterEffect`（SCHEDULED + `at_or_after_tick` + `enqueue_unit_command` `unit.SetTaxRate`），但效果不会随 `simos.advance` 自动执行，必须由 GM 在目标 tick 显式调 `sd.AdjudicateTick`；到期后的“恢复原税率”需要现算原值。
  - 缺：免税公告/期限实体、税率历史回滚、自动执行器。
- [ ] **北谷侯一家“接到成都”无人/家族/俘虏模型**
  - 没有 Person/Character/Noble/Family/Hostage 领域；`sd.CreateDecisionMaker` 是抽象决策主体，不是可移动人物；unit 是军队/GOV，不是人物容器。
  - 成都有 `c44_-68` 城市节点（(44,-68)，MajorCity，population 0，大蜀新都），但没有可移动的“北谷侯一家”。
  - 只能写 `sd.PutInfo` 记录叙事，或建替身单位/DM，不能忠实表达。

### 3. 对西陵“逆我者亡”
- [ ] **没有外交关系领域**
  - 命令面没有宣战/停战/和平/同盟/关系值/最后通牒/最后期限/屈服/附庸/朝贡；`sd.CreateNation` 只建国家实体，不建立外交关系。
  - 现有手段只有：`sd.PutInfo` 记立场；`sd.IssueDirective` 出军令；`sd.CreateCombat` 开一场 GM 裁决式交战。
  - 影响：“逆我者亡”无法变成可判定的外交 ultimatum/投降机制，只能作为 Info + 军事行动。

### 4. 对铁门坎第一次轰城
- [ ] **没有攻城/炮击/城防模型**
  - 没有 `sd.Bombard` / `unit.Siege` / 城防/城墙/工事/火炮领域命令；unit 的 `equipment` 只是自由 map，没有“炮/攻城器械”语义。
  - `sd.CreateCombat`/`sd.AddCombatStage` 可以在 (34,-55) 建一个抽象阶段，用 outcome table + casualties 表达“第一次轰城”，但完全是 GM 裁决，不会自动结算。
  - (34,-55) 当前无城市、无守军单位；西陵军队在 (34,-61) 附近。现存名为“铁门坎”的城市在 (31,-60)，是生成器产物，与档案坐标冲突。

### 外交交互机制（n tick 决策 → n+1 tick 紧急回应）
- [ ] **没有自动事件/紧急响应触发器**
  - `simos.advance` 只推进时间（可 from=n,to=n+1），不自动跑 DM、不自动裁决。
  - 需要 GM 手工：`sd.IssueDirective`（n）→ `sd.AdjudicateTick`（n）→ `simos.advance`（n→n+1）→ `sd.RunDecision` / `simos.sd.run-decision-makers`（涉及中央政府 DM，真 LLM 轮）→ 他们在 n+1 出令 → `sd.AdjudicateTick`（n+1）。
  - `sd.IssueDirective.tick` 不得记在未来（> 当前 tick 直接拒），所以“紧急回应”的令要等推进到 n+1 后才能落。
- [ ] **中央政府决策人看不见全国**
  - 现有 `大蜀-gov-central-dm` / `西陵-gov-central-dm` 是 **GovScope 直辖**：只看见首都 Region（各 7 格）。`大蜀-gov-central-dm` 看不到北谷占领区、北线军、铁门坎；`西陵-gov-central-dm` 看不到西陵军主力/铁门坎。
  - `sd.SetDecisionMakerAccess` 只能**收紧**，不能扩大范围；要全国视野需：
    - 新建 Nation 归属 DM（`sd.CreateDecisionMaker` affiliation `{kind:"nation", id:"大蜀"/"西陵"}`；sd 里已有 大蜀 / 西陵 Nation，region tag 分别为 `nation:大蜀` / `nation:西陵`）；或
    - 扩大中央 GOV jurisdiction（`unit.SetJurisdiction`）到全部省份（会改变配编/税基）。
  - 大蜀占领区地区 tag 是 `Nation`（不是 `nation:大蜀`），所以即使新建大蜀 Nation DM 也看不到占领区；北谷没有 sd Nation / 中央 DM；北谷侯没有 DM/人物实体。
  - ArmyScope 军队决策人只有“当前位置 + 视野半径”圈（默认 R=1 ⇒ 7 格），不适合跨战区外交回应。
- [ ] **DM 出令审批链**
  - 决策人 `sd.IssueDirective` 走审批时可能停在 pending；需要审批 watcher/GM 批准。批处理 `simos.sd.run-decision-makers` 只跑 LLM 轮，不代批。
