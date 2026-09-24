# M4 落地记录（读口补齐）· 2026-09-24

> 前置：侦察报告 `m4-inventory.md`（本目录）、`pending-rulings.md` §2.5（"要不要补、补哪几条"）。
> 用户 2026-09-24 裁定：**补**（"补 MCP 读口（工具面 M4）"）。本文件记**这一批实际落地的范围与未落地的**。

## 一 本批落地（5 条新读工具，GM 口 62 → 67）

| 新工具 | 补的缺口 | 桶 | 关键口径 |
|---|---|---|---|
| `simos.timeline.revisions` | `/api/timeline` 的 `nodes`（原有 `branches` 只给分支与 head） | 四桶共享 | 视图 = `ApiViews.timeline`（GUI 同源）；分支不存在 ⇒ `NOT_FOUND` |
| `simos.map.region` | `/api/map/region/{id}` | 四桶共享 | 视图 = `ApiViews.regionDetail`；不可见/不存在同款 `NOT_FOUND` |
| `simos.map.path` | `/api/map/path`（A\* 试算） | **GM-only** | 起点由服务端取 `effectivePosition`；报告 §二-4：它是**地形探测**，不给决策人 |
| `simos.sd.decision-makers` | `/api/sd/decision-makers` | **GM-only** | 视图 = `ApiViews.decisionMakers`；报告 §二-2：含 `allowedTools`/`providerId`/`rootUnit` = 别人的底牌 |
| `simos.sd.decision-maker` | `/api/sd/decision-makers/{id}` | **GM-only** | 同一缺口的两半；未知 id ⇒ `NOT_FOUND` |

## 二 结构改动（两处，都不是"顺手"）

1. **`ApiViews` 提升为 GUI 与读工具共用的视图层**（类 + 5 个方法 `public`）。理由：侦察报告 §二-6 实测
   "同一资源的两个形状"（GUI 与 MCP 各写一份视图 ⇒ 发散且无判据）。共用一份 ⇒ 形状发散在**结构上**不可能。
2. **读工具的桶归属显式化**（`app.tools.GmOnlyRead` 标记 + `SimosToolSource.readToolsFor(...)`）。
   此前"读工具四桶共享"是**结构默认**、无处可写（creed 五要求写出来）；现在标了标记的只进 GM 桶。
   连带：决策人白名单（`DecisionCallerFactory.WHITELIST`）跟着只放**共享**读工具。

## 三 判据（新 2 条，都在 `SimosToolsTest`）

1. `everyReadToolIsReachableAndAnswersWithItsMinimalShape`：**逐条真调**每个读工具（按名字，不是索引切片），
   断言"不报错 + 最小形状键在场"；末尾自证"表格逐条覆盖读工具全集"（新增读工具没进表 ⇒ 红）。
   由来：侦察报告 §四-1 —— 此前 15 条守卫全在名字集合层，**没有一条真的调过读工具**。
2. `everyToolClassOnDiskIsRegisteredInSomeBucket`：扫 `tools/read` + `tools/write` 的 `NAME = "…"`，
   断言 ⊆ 两桶并集（防"写了工具类却忘了注册"）、且 GM 桶无幽灵条目。
   由来：报告 §四-2 —— catalog 那条同源强判据**只覆盖命令类型**，工具面无等价物。

★ 另一处**精确化**（既有护栏）：`AppWritePathGuardTest` 的禁止符号从**子串**改成**标识符**（`\bTimeline\b`）。
实测：新类 `TimelineRevisionsTool` 撞了子串匹配（它引用的是 `CoreSimos.revisions`，不是存储写面 `Timeline`）。
边界由新增的 `forbiddenSymbolsMatchIdentifiersNotSubstrings` 自证（真引用仍命中）。

## 四 ★ 本批**未**落地（下一次接着做）

| 缺口 | 为什么这批没做 |
|---|---|
| `simos.sd.verdicts`（`/api/sd/verdicts`） | 视图在 `RedactingQueryService.verdicts(target)`，而该类需注入 `DecisionScopeFunctions` ⇒ 要给 `SimosToolSource` 加依赖；报告 §二-1 还要求"必填 actor（不给缺省 = FULL）"，得先定 actor 从哪来 |
| `simos.llm.providers`（`/api/llm/providers`） | 需要注入 `AgentLibLlmConfig`（同为加依赖）；报告 §二-5：值不泄露但 `baseUrl`/`model`/键名会露 ⇒ 若做也是 GM-only |
| **形状对齐**（报告 §二-6 / §四-3） | `simos.map.hex` 缺 `regions`/`edges`/`terrainType`（M8-U1 的多从属语义看不到）；`simos.map.overview` 的 MCP 版走**逐格**（真档 ≈1MB，M9 的省流没吃到）⇒ 这两条是"同源"的**载荷层**缺口，需要跨面判据，单独一批 |
| `/api/state` 的 `meta` 面 | 报告 §一："若只要 branches ⇒ 已覆盖，不新增"——本批按此**有意不新增** |

## 五 ★ 负面清单（有意**不**开口）

- `simos.gm.tool-usage`（`/api/gm/tool-usage`）：报告 §二-3 —— **GM 内部观测面**（谁在什么时候调了什么工具），
  四桶共享会把"GM 在看什么"暴露给被观测者。**明确不开口**，理由记在此以备后人再问。
