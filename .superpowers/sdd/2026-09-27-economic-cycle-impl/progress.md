# SDD 台账 —— 经济循环实现（H0–H5 + 关账）

Plan: `docs/superpowers/plans/2026-09-27-economic-cycle-implementation-plan.md`
Goal: `goal-19ff41be-ca9f-40d8-be89-8b9149ea69bf`（用户 2026-09-27：「一口气跑完」）

## 执行方式（用户裁定，优先于任何 Skill）

- ★ **开发期不写新测试、不做变异自证**（`AGENT.md` §三.0）。唯一硬底线 = **编译必须绿**。
- ★ 既有测试仍在跑；既有用例变红 ⇒ 按新口径**重算期望值**，**不许删或放宽断言**（照 S1 先例）。
- ★ **一次只能跑一个 Maven** ⇒ 所有 Maven 经 `tools/mvn-lock.sh`（本轮新建，已自证互斥）。
- ★ 一个文件一个 owner；派单写明文件清单。

## 控制方裁定（执行前已定，见 plan §三）

K1 家户账 = actor 持久真源 + economy 会话工作副本（不进 `EconomyData`）
K2 行键 = `CohortKey`（`(格, 居住类型, 阶层)`）= 家户键；`ClassKey` 删除
K3 产能从行搬到 `Industry`；`ClassRow.meansOfProduction` 删除（代价：真档数值会变）
K4 转移原语只认 `ActorRef`（住 `economy-api`，已依赖 `actor-api`，无循环）
K5 货币与市场**同批**落地
K6 ★ 用户裁定：作坊投入改 `{FIBER, TOOL}`、`IRON` **留作留位但必须有读者**（`allCommodityIds()` + 读口）
K7 既有用例变红优先判"夹具/口径错"，重算不放宽
K8 台账纠错：`ClassKey` 实测 **298 处**（main 174 / test 124），旧台账的 259 把 `AssetClassKey` 算进去了

## 进度

### H0 身份与观测收口

- [x] **契约（控制方亲做）**：`ResidenceKind` 新建 · `CohortKey` 加居住维（三参 + 三段规范串）·
      `PopulationLots` 的前缀改为转发到 `ResidenceKind.lotPrefix()`（**消除第二处拼写点**）
      —— 编译实测：`-pl simos-economy-api,simos-social -am` **exit 0**
- [ ] H0.1/H0.3/H0.4 `simos-economy` 主源（**Agent B 在跑**）
- [ ] H0.1/H0.7 `simos-app` 播种器与读口（**Agent C 在跑**）
- [ ] H0.5 `AssetHolding`/`AssetClassKey`/`ASSET_QUANTITY` 退役（**待 B 落地后再动**：
      `Basis` 与 `ProductionSettlement` 在 B 的文件里，避免互相覆盖）
- [ ] H0.6 产权读口（GUI + MCP）+ 600 天聚合脚本纳入 actor 账
- [ ] H0 批次编译（`-DskipTests package`，**含测试编译**）⇒ 提交

### H0 的判据（阶段边界由控制方跑）

- I1.1：迁移前后逐格人口/劳动/需求**逐值相同**（H0 不应改变经济行为）★ H0.4 例外（K3 已认代价）
- 结构：`ClassKey` 零残留（`grep -v AssetClassKey` = 0）；行键全部 `(格,居住,阶层)` 形状
- 全部 `*Test` 编译通过（不许删文件来"通过"）
