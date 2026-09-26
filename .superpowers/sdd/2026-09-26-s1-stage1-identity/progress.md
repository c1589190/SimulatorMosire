# SDD ledger — plan: docs/superpowers/plans/2026-09-26-s1-stage1-identity.md

Spec: docs/superpowers/specs/2026-09-26-s1-actor-property-design.md §2.6（已通过）
Breakdown: docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md（含 2026-09-26 的阶段 1 收窄修正）

## Setup

- 用户 2026-09-26 裁定：**词表选 A**（`poor_peasant` / `middle_peasant` / `rich_peasant` / `landlord`），
  "按 A 直接开始" ⇒ **旧档失效**（规范串第二段换值），依据 spec §十.4"重建也没关系、不做迁移工具"
- 执行方式：**Native**（就地，分支 ts/m1 —— 与 B1/B2 那一轮同一方式与同一分支）
- 工作区：**就地**（本会话配置为 worktree 之外就地工作）

## 任务清单

- [ ] Task 1  新建 `SocialClassId`（全局阶层词表 + 词表外即抛）
- [ ] Task 2  换装（`ClassSlot.id` / `ClassKey.slot`）+ `ClassSlotId` 退役
- [ ] Task 3  验收（人口与劳动**逐值不变**）

### Task 1

- ★ **TDD 当场抓到一个真 bug（静态初始化顺序）**：首版把校验写成 `ALL.stream().noneMatch(...)`，
  而 `ALL = List.of(POOR_PEASANT, ...)` 依赖四个常量 ⇒ **构造器在 `ALL` 初始化之前跑** ⇒
  `ExceptionInInitializerError`（`ALL is null`）。测试 Step 4 直接红。
  **修法**：校验改用 `private static final List<String> VALUES`（字面量、声明在最前），
  四个常量从 `VALUES.get(i)` 派生 ⇒ 无循环依赖。★ 这一类错**编译期与 SpotBugs 都看不见**，
  只有真的构造一次才会炸 —— 值得记进 AGENT.md 的失败形态。

**Task 1: complete** (commits f971b66..75ea7c9, tests: `-Dtest=SocialClassIdTest` → 3/3 pass)
验收：词表恰四个且保序 · 词表外即抛 · 往返成立；变异自证（删词表校验 ⇒ 红 ⇒ 还原 ⇒ 复绿）
★ 副产物：TDD 抓到静态初始化顺序 bug（`ALL` 在常量之前被构造器引用 ⇒ ExceptionInInitializerError）

### Task 2

**Task 2: 进行中（换装已完成，测试期望值待改）**

#### 已完成
- **标识符替换**：`ClassSlotId` → `SocialClassId`，21 文件 / 65 处（`sed` 精确匹配 `\bClassSlotId\b`，
  排除两个 Id 类自身的 javadoc 引用）
- **`EconomySeeder.CLASS_IDS` 换成新词**：`{"poor_peasant","middle_peasant","rich_peasant","landlord"}`
  ★ **保持 `String[]` 而不换 `List<SocialClassId>`**（偏离计划原文）：消费点（`.length` / `CLASS_IDS[i]` /
  `slot.put("id", …)`）全按字符串用，换类型要动 15 处而**收益为零**（词表校验已在 `new SocialClassId(...)` 一处生效）。
- **删除 `ClassSlotId.java`**（调查确认 73 处全是经济侧自我引用，无第三方）
- **main 编译通过 · test 编译通过**
- **`ClassKeyTest` 3/3 绿**（本阶段的目标：跨产业同名阶层是同一个身份 / 往返 / 词表外即抛）
- `EconomyFlowCycleTest` 5/5 · `EconomySeederTest` 25/25 · `EconomySettlementTest` 10/10 绿
- 修了 `EconomyIdsTest` 的通用三件套契约测试：`SocialClassId` 是**唯一带词表**的 id ⇒
  它用自己的合法样例值（`poor_peasant`），其余 20 类仍用 `u-1`（**保留 ①② 两条覆盖，不是把条目删掉**）
- 替换了 JSON 载荷串里的旧词（`\"peasant\"` 形式，sed 漏掉的）—— 2 文件

#### 剩余（`clean verify`：8 Failures + 2 Errors，全部是"测试期望值含旧词"）

| 类 | 原因 |
|---|---|
| `EconomySowingTest` | 期望旧串 `debt-c1-farm@0_0\|peasant>farm@0_0\|landlord-grain`，实际 `…\|poor_peasant>…` |
| `EconomyDebtTest`（2F+1E） | debt id 含阶层名 ⇒ 换值后查找键失效（`NPE: first is null`） |
| `EconomySeedHandlerTest`（9F+8E 已降到剩几条） | 断言期望旧的**错误消息文本**（`ghost` 被拒时措辞变了） |
| 其余 | 同类：断言/夹具里带旧词或旧串 |

★ **修法（计划 Step 4 已授权）**：按新口径**手算重算**期望值，**不许抄实际值**；
★ 要注意**裸词**（`peasant` 不带引号，出现在 debt id 串里）与**变量名**（`PEASANT`）的区分 —— 只改前者。
