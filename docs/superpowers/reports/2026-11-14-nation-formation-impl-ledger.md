# 国家与完整军队编制编辑 — 生产实现台账

> **状态**：生产实现完成，待测试 Owner 统一验收（2026-11-14）。
> **父阶段**：`docs/superpowers/specs/2026-11-14-nation-formation-editor-design.md` §1 阶段 1–7、§3.1/§3.2 工具形状、§4 子设计影响；含 2026-11-14 修正（次区域解除 tag、非空领土、transfer 不划空）与控制方三轮审阅修复。
> **边界**：本台账只记录生产实现、实际接口、影响、偏离和未验证；不声称测试/浏览器/MCP 验收通过。

## 1. 关键形状（逐字复述）

- `simos.gov.editNation`（GM-only）：target + `mode:create|update`、`nationId`、`name`、`color:#RRGGBB`、`hexes:[{q,r}]`、可选 `territoryMode:replace|add|remove`（默认 replace）、`overlapPolicy:reject|transfer`（默认 reject）、`government:{unitId,name,position:{q,r}}`、`dryRun`、`reason`。一次 `ToolWritePort` 原子 batch，落 map/unit/gov/social（social 仅新 GOV 的 0 人政府家户 CREATE_HOUSEHOLD）；国家图形读视图 `simos.gov.nations`。
- `simos.army.editFormation`（GM-only）：target + `mode:create|update`、`armyId`、可选 `name`、`masterGov`、`root` 递归 `{id,name,role,position:{q,r},personnel,equipment:{type:count},speed,mobilityPerMille,attached,children:[...]}`、`dryRun`、`reason`。节点 stable id 必给；role 复用 `ArmyFormation.role`；HQ 可 0 人；新子默认 `attached=true` 且位置实际写为父位置；root 明坐标；update 只改给定字段、保留未提及节点、不自动删除。`personnel` 是本级直接人数，Social 工单权威；不发 economy/actor/ga 命令；新节点整树一次 batch，任何失败全回滚。
- `simos.army.formation`（GM-only）：无 armyId 列表；有 armyId 返回真实 tree + PNG assetIds（MCP ImageContent）；直接人数/子树合计分别给出；attached/detached 线区分。
- 上限 512 node / depth 16；unknown/duplicate/越其他 Army/非整数/attached 不同格等具名 fail-closed；更新保留政策、家户、Unit record 其他字段、他国/非国家 region 元数据。
- Army 不加 `NationId`；国家归 gov，编制/移动归 unit，人员归 social，几何归 map；app 只统一装配、固定工具白名单 HTTP 桥和宿主。

## 2. 实际接口示例

### 2.1 一次建国家 + 中央 GOV

```json
{
  "mode": "create",
  "nationId": "demo-nation",
  "name": "演示国家",
  "color": "#3366CC",
  "hexes": [{"q": 10, "r": 5}, {"q": 11, "r": 5}],
  "territoryMode": "replace",
  "overlapPolicy": "reject",
  "government": {
    "unitId": "demo-government",
    "name": "演示中央政府",
    "position": {"q": 10, "r": 5}
  },
  "dryRun": false,
  "reason": "editor 建国家与中央 GOV",
  "branch": "main",
  "expectedRevision": 42
}
```

生成命令（固定顺序）：`map.CreateRegion`（或 home 已存在时 `map.UpdateRegion`/`map.RenameRegion`）→ 其他同 tag 区域 `map.UpdateRegion`（只解 tag）→ transfer 源 `map.UpdateRegion` → `gov.CreateNation` → `unit.CreateUnit` → `unit.SetGovFormation` → `unit.SetJurisdiction`。返回 `commands`、`homeRegionId`、`releasedRegionIds`、`transferredFromRegionIds`、`submission.ref`。

### 2.2 一次建师旅营

```json
{
  "mode": "create",
  "armyId": "demo-division",
  "name": "演示师",
  "masterGov": "demo-government",
  "root": {
    "id": "demo-div", "name": "第1师", "role": "DIVISION",
    "position": {"q": 10, "r": 5},
    "children": [
      {"id": "demo-bde-1", "name": "第1旅", "role": "BRIGADE",
       "children": [
         {"id": "demo-bn-1", "name": "第1营", "role": "BATTALION", "personnel": 500,
          "equipment": {"步枪": 450, "卡车": 20}},
         {"id": "demo-bn-2", "name": "第2营", "role": "BATTALION", "personnel": 500}
       ]}
    ]
  },
  "reason": "editor 建完整师旅营",
  "branch": "main",
  "expectedRevision": 42
}
```

新下级坐标缺省时 `unit.CreateUnit` 在创建期写实为父位置；`personnel>0` 时每户一个 `social.SubmitHouseholdWorkOrder`（`source.module=army`，plan = `CREATE_HOUSEHOLD` + `ADD_MEMBERS`），`unit.SetUnitHouseholds` 与 Social 工单同批；最后 `army.CreateArmy`。

### 2.3 读回

- `GET /api/editor/nation?branch=main&revision=42&nationId=demo-nation&render=true` → `result.nations[0]`（hexes/hexCount/name/color/regionIds/govUnitIds）+ `images[0].base64`。
- `GET /api/editor/formation?branch=main&revision=42&armyId=demo-division&render=true` → `result.armies[0].nodes[]`（directPersonnel/subtreePersonnel/attached/parentId/depth/equipment/position）+ `images[0].base64`。

## 3. 对父序列的影响

| 维度 | 影响 |
|---|---|
| 顺序 | editNation：先 map 区域/标签/转移，再 gov.CreateNation，再 unit GOV 立编制/管辖，最后同批 social 0 人政府家户工单（在 GOV 单位存在之后）；editFormation：DFS 先父后子创建/更新，Social 工单与 unit.SetUnitHouseholds 同批，最后 army.CreateArmy/UpdateArmy。全部命令共享 branch/expectedRevision，一条 revision。 |
| 预算/冻结 | 无经济/actor/ga/social 预算消费；编辑器前端冻结 `{branch,revision,writeEpoch}`，JSON 不能改目标节点；成功后只按刷新返回的新 epoch 推进游标。 |
| 身份/持久化 | 不新增顶层状态组件；Nation 仍居 gov、Region/颜色居 map、Unit/编制居 unit、人数居 Social、Army 身份居 army；国家 GOV 关系是 jurisdiction 派生视图，不是持久字段。 |
| 失败/回滚 | 两工具都组完整命令批后一次 `submitBatchWithReceipt`；后序命令拒/冲突 ⇒ 整批零 revision。apply 前先 `writes.head(branch)` 预检，stale 直接 CONFLICT，不把旧 revision 的 plan 错误归 BAD_REQUEST。 |
| 日志 | 沿用 `GovLog.nation()`、`UnitLog.command()`、`ArmyLog.armies()`；只记稳定 id/数量/角色，不记大 JSON。 |
| 测试 | 本 Agent 未写/未跑测试；边界/负向用例留给测试 Owner。编译成功 ≠ 验收通过。 |

## 4. 实现文件清单

### gov（`simos-gov/src/main/**`）
- `gov/tools/GovNationViews.java`：精确 `nation:<id>` tag 并集、颜色/冲突、GOV 派生、payload。
- `gov/tools/NationRenderPort.java`、`gov/render/NationImageRenderer.java`：国家 PNG 绘制在 gov；app 只注入 `PngPublisher`。
- `gov/spi/UpdateNationHandler.java`：`gov.UpdateNation`；`gov/spi/CreateNationHandler.java` 精确 tag 修复。
- `gov/tools/write/GovEditNationPlan.java`、`GovEditNationTool.java`：国家计划/工具，map/unit/gov 原子批。
- `gov/tools/read/NationsTool.java`：国家读视图 + render。
- `src/main/resources/webui/modules/gov/manifest.json`、`nation-editor.js`。

### army（`simos-army/src/main/**`）
- `army/spi/UpdateArmyHandler.java`。
- `army/tools/ArmyFormationViews.java`、`FormationRenderPort.java`、`army/render/FormationImageRenderer.java`。
- `army/tools/write/ArmyEditFormationPlan.java`、`ArmyEditFormationTool.java`。
- `army/tools/read/FormationTool.java`。
- `src/main/resources/webui/modules/army/manifest.json`、`formation-editor.js`。

### unit
- `unit/spi/CreateUnitHandler.java`：省略子坐标写实父位置、可选 attached。
- `unit/spi/UpdateFormationNodeHandler.java`、`unit/ops/UnitOperations.java`：编制节点窄更新；位置/parent/attached 变化清路线并同步 attached 后代；保留全字段。

### app
- `app/tools/SimosToolSource.java`：四工具装配。
- `app/Shell.java`：三 handler 注册、编辑器桥接线。
- `app/gui/GuiServer.java`：固定 `/api/editor/nation`、`/api/editor/formation` GET/POST、as= 拒、状态码映射。
- `app/editor/EditorToolBridge.java`、`RegistryEditorToolBridge.java`：四条工具白名单、系统权限上下文、assetId→base64。
- `app/tools/gov/AppNationRenderPort.java`、`app/tools/army/AppFormationRenderPort.java`：只注入 PngPublisher。
- `app/render/RenderService.java`：窄 `putPng`。
- `webui/app.js`：`writeEditorTool` 共享写收尾；`api.js`：`postEditor`；`index.html`/`map.html`/`styles.css`：入口、折叠、缩放/滚动。
- `webui/map.js`/`renderer.js`/`worldmodel.js`：地图国家层改用 `simos.gov.nations` 一次视图（一国一 label/union/name/color）。

### 中性契约
- `simos-ga-spi/.../ToolResources.java`：新增 `GOV_NAMESPACE` 常量（真实切片命名空间，不是领域逻辑）。

### 模块索引
- `simos-gov/AGENTS.md`、`simos-army/AGENTS.md`、`simos-unit/AGENTS.md`：新工具/handler 数量、模块 webui 资产、本轮专题入口。

## 5. 编译证据

```bash
cd /home/cna/SimulatorMosire
set -o pipefail
tools/mvn-lock.sh -DskipTests compile -pl simos-app -am 2>&1 \
  | tee /tmp/simos-nation-formation-compile-renderport.log >/dev/null
echo ${PIPESTATUS[0]}
```

- 结果：`MVN_RC=0`，`BUILD SUCCESS`，19/19 模块（2026-11-14 23:46:08 +08:00）。
- 原始日志：`/tmp/simos-nation-formation-compile-renderport.log`。
- SpotBugs 全 reactor：`/tmp/simos-nation-formation-spotbugs-renderport.log`（19/19 `BugInstance size 0`，BUILD SUCCESS）。
- JS 语法：`node --check` 通过 `app.js`、`api.js`、`map.js`、`renderer.js`、`worldmodel.js`、`nation-editor.js`、`formation-editor.js`。
- **未跑**：JUnit、`test`/`verify`/`package`、前端门禁、真浏览器、实际 MCP `tools/list`/`tools/call`、冷启动读回。

## 6. 偏离、风险与未验证

1. **次区域处理**：按 2026-11-14 修正，update 时其他同 tag 区域保留 ID/几何/其他 meta，只解除 `nation:<id>` tag；目标并集集中到 homeRegion。没有删除被引用区域，也没有改 map 非空不变量。
2. **transfer 空源**：只从其他国家同 tag 区域扣目标格；任一源扣后为空 ⇒ 整批具名拒绝，提示先调整对方区域结构。区域模型仍禁止空几何。
3. **中央 GOV 家户（已修正）**：`unit.SetGovFormation` 自动把 `hh-gov-<unitId>` 写进 `Unit.households`；editNation 现同批追加 `social.SubmitHouseholdWorkOrder`（仅 `CREATE_HOUSEHOLD`，location `UNIT(govId)`，0 人，`source.module=gov`，reason 带调用者理由与“0 人政府家户”），顺序在 GOV 单位存在之后，消除悬空引用。若该 id 家户已存在：location=UNIT(govId) 且成员为空 ⇒ 复用不重复建户；否则具名拒绝，不覆盖不匹配户。仍不初始化 economy/actor/ga。
4. **GOV 关系是派生**：`simos.gov.nations` 的 `govUnitIds` 由 jurisdiction 与本国区域相交推导，不是持久 Nation→GOV 字段；不替代决策人归属。
5. **PNG 是代表性视图**：国家图按轴向六角几何直接画领土并集/颜色/名称；编制图是树形布局（实线 attached、虚线 detached，本级/子树人数），不是地理投影或精确军标。大型 512 节点树整体缩放到高度受限 PNG（完整 JSON 仍在），可读性可能受限；未在真浏览器验证。
6. **人口校准**：`personnel` 新成员默认 `MALE` + `ageAtAnchorDays=18*365=6570`（成年男性锚点），工具描述/结果明说不是真实人口结构。减少时按家户现有 lot 逐批 `REMOVE_MEMBERS`；若单位有非本工具家户且目标人数变化，具名拒绝，避免覆盖其他 Social 状态。
7. **Social 工单**：使用 `social.SubmitHouseholdWorkOrder`，每户一组 `CREATE_HOUSEHOLD`/`ADD_MEMBERS`/`REMOVE_MEMBERS`，`source.module=army`，reason 含调用者原因与校准说明；未给 `orderId`（该字段可选；未引入第二套幂等）。`unit.SetUnitHouseholds` 与工单同批，批原子。
8. **update 的 masterGov 扩散**：显式改 `masterGov` 时，当前树里未在载荷出现的既有 Army 节点也会同批更新 `ArmyFormation.masterGov`（保留 role/duties/policy），避免 Army 与节点长期不一致；这是对“未提及节点保留”的唯一有意例外。
9. **HTML/解析入口最后修正**：`index.html` 三处误写 `</details>` 已仅按确切上下文改回 `</fieldset>`（单位创建、区域删除、区域元数据），`map.html` 只含新增编辑器 details，已检查平衡；`ArmyEditFormationTool.parseNode` 在递归入口先按 depth 16/节点 512 限制，超限先拒，不改业务契约。
10. **Shell 装配修复（真实启动失败）**：新增三命令注册后 `CatalogTool.PAYLOAD_HINTS` 缺项触发构造期完整性断言。已补 `gov.UpdateNation`（nationId + name/homeRegionId/adminBudgetPerTick 至少一个；homeRegion tag 精确匹配）、`army.UpdateArmy`（armyId + name/masterGovUnitId 至少一个；root/contactWatch 保留）、`unit.UpdateFormationNode`（id + name/position/attached/parent/speed/mobilityPerMille/equipment 可选；position/parent 清空语义、attached 同格、后代位置同步）。完整性检查未放宽，未加空 hint。
11. **真实 Chromium 修复**：① `simos.gov.nations` 空世界列表不再抛 BAD_REQUEST，返回 `{count:0,nations:[]}`；明确给不存在的 nationId 仍 BAD_REQUEST；无 id 且 render=true 时，空列表明确 `render.skipped`，非空列表具名要求 nationId，不 `get(0)` 越界。② `app.js` 写收尾返回 `cursorStillCurrent` + 同次提交后的 `completedTarget`（含 refresh 后新 writeEpoch）；nation/formation 编辑器只用该目标刷新列表与读回，提交前 frozen 只用于识别迟到响应；迟到成功/失败不改当前别的节点 DOM。列表/读回 generation token 仍保留。
12. **人数视图与即时拒绝修正**：`ArmyFormationViews.FormationView.directPersonnel` 改为 **root 本级直接人数**（师本部 60），`subtreePersonnel` 仍是 root 子树总数（全师 3340）；节点行本级数不变，PNG 根子树数不变。`app.writeEditorTool` 的同步 mode-denied/bad-target 返回补 `cursorStillCurrent=true` 与当前 `completedTarget`（bad-target 为 null）让模块显示提示；`runWriteSubmission` 的旧目标/epoch 判断保留，真迟到响应仍隔离。
13. **编制大图可用性补齐**：军队编制缩放工具栏新增“查看大图”，打开原生 `<dialog>` 宽窗浮层（约 90vw×85vh），复用编辑器已取得的同一张 PNG（不发请求、不重读节点、不改后端布局）；默认适应窗口，支持原图尺寸、±缩放、滚动、关闭按钮与 Esc；两宿主页 index.html/map.html 一致接线，未改 fieldset/details 闭合。未做真浏览器验证。
14. **可变集合封装 / 格式化 / 根 attached 修正**：
    - 删除测试 Owner 在 `UnitOperations.FormationNodePatch` 上加的 `@SuppressFBWarnings` 及 import；compact constructor 改为 `equipment == null ? null : List.copyOf(equipment)`，保留“null=未填”语义，copy-in/out 均由 record 访问器保证。
    - 本轮新 public records 做保序防御性复制：`GovNationViews.NationView`、`GovEditNationPlan.Plan`、`ArmyFormationViews.NodeView/FormationView`、`ArmyEditFormationPlan.NodeSpec/Plan`。List 用 `List.copyOf`；需要保序的 Map 用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`；`NodeView.equipment` 外层列表与内层 Map 都 copy-freeze，访问器再返回深冻结副本。
    - `ArmyEditFormationPlan` 新节点默认 attached 改为 `parent != null`：根默认 false（独立单位），子默认 true；update 省略仍保持现有值。PNG 根节点显示“根单位”。
    - 用单次 Maven 进程对 gov/army/unit/app/ga-spi 的 `src/main/java/**/*.java` 跑 spotless apply/check（`-DspotlessFiles='.*src/main/java/.*\.java'`），未格式化测试源码；SpotBugs unit/army/gov 通过。`GuiServer` 原有 EI_EXPOSE_REP2 抑制随构造拆分移到真正持有字段的 10 参构造，未新增掩盖。
15. **fresh full 两条 app EI 的窄接口修正**：删除 `RenderService.putPng(byte[])` public 方法，改为 `Function<byte[],String> pngPublisher()`（返回 `ArtifactStore.putPng` 的窄闭包），不新增 public put/set、不暴露 ArtifactStore 其余写面；`SimosToolSource` 两处改用 `renderService.pngPublisher()`。`Shell.renderService` 的 EI 由这个新增 public 写方法触发，移除后旧 getter 不再被分类为可变服务写口。恢复 `RenderService.mapReferences()` 的既有 `EI_EXPOSE_REP` 精确注解：GUI HTTP 与 MCP 必须共享同一参考图服务实例（同一缓存/工件身份），不是可复制世界状态；上轮 standalone 误判 useless 已纠正。新鲜单 Maven reactor `compile spotbugs:check -pl simos-app -am` 全部 19 模块 `BugInstance size 0`、BUILD SUCCESS。
16. **未验证**：没有真实 Chromium 操作、没有实际 HTTP 端点冒烟、没有 MCP ImageContent 调用、没有真实世界推进证明整支移动/detach、没有 512 节点性能与 PNG 尺寸实测、没有冷启动读回。

## 7. 交回状态

- 生产代码到此停写；不 commit/push；不修改/运行测试。
- 测试 Owner 可按父设计 §6 的正负用例统一验收；本台账不把“编译成功”写成“功能验收通过”。
