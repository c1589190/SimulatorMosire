# 开发进度存档 —— GM⇄DM 轮次协议 + 工具三分类（2026-10-15 04:0x）

> ★ 本文是**存档与交接**：写到哪、什么已验证、什么没验证、下一步是什么。
> **权威顺序**：用户原话 > 用户设计原稿 > 最新落盘文档 > `AGENTS.md` > 旧文档。
> 相关设计书：`docs/superpowers/plans/2026-10-14-gm-dm-interaction.md`（GM⇄DM 约束）
> · `docs/superpowers/plans/2026-10-15-tool-three-categories.md`（工具三分类 + 3b + planOnly，§8–§12）。

---

## 一、这一段做了什么（一句话一主题）

| 主题 | 内容 |
|---|---|
| **GM⇄DM 轮次协议** | 信件双向化 · 决策自由化（七件套：多条/改/删/覆盖/确认/撤回）· 推演确认 · 信件自动注入 · 提交语义（PENDING 不是锁）· GM 代打 `simos.gm.actAs` · 权限域名 `sd → ga` |
| **工具三分类（批 3a）** | 用户裁定「GMDM都能做那肯定是DM的需审批命令」⇒ 四格收敛成**三格**：类1 `GM_ONLY` 140 · 类2 `DM_APPROVAL` 17 · 类3 `DM_FREE` 37；**装配期 fail-closed 分区审计**（判据读**真 `gate()`**，探针上下文） |
| **批 3b** | 9 条 gov 类2 工具进 `ProposalCatalog` ⇒ DM「先记录、GM 统一批」**零阻塞**（纯加法：`roles()`/`gate()`/审批链/超时全不动） |
| **两个同源缺陷** | `gov.createOffice` 与 `province.apply` **整体 0 落盘**（`affiliation` 被具名拒 ⇒ 整批复原）—— 成因是 `description`/javadoc **撒谎**，写代码的人照着谎写 |
| **planOnly 双写** | ★★ catalog 现有 7 条里 **4 条**忽略内部参数 `planOnly` ⇒ `gm.packet.execute` 规划阶段**真落盘** ⇒ 报失败 ⇒ GM 重试**再落一次**（数据损坏级，已修） |

---

## 二、已提交（HEAD = `f5c483ef`）

### 2.1 今天（2026-10-15）

```
f5c483ef docs 设计书 §12：真 LLM 验证跑不起来（两个门控用例都坏，但都在测试侧）+ 三路径实测矩阵
932602aa docs GM⇄DM §12：真 LLM 前置阻塞 —— 门控 e2e 引用 10 处已改名的工具（死角第 4 次）
faae1c59 feat(app) ★ 批 3b：9 条 gov 类2 工具进 ProposalCatalog（DM 先记录、GM 统一批、零阻塞）
7337813c docs 设计书 §11：3b 落地（planOnly 复核 9/9 · B1–B6 全过 · B3 判别对照）+ 更正流程多一步 confirm
c27febc7 fix(gov)  ★★ planOnly：修 4 条既有缺陷 + 补 9 条（**双写**，数据损坏级）
78ef6616 docs 设计书 §10：planOnly 全扫 23 条 · 双写实证 · 裁定两条潜伏清单
0caeb7d7 fix(gov)  ★ simos.province.apply 整批被拒（同源缺陷）
ab1f466f docs 设计书 §9：catalog 的 planOnly 前置条件有 4/7 条违反 + 控制方两处自我更正
d69d0212 docs 三·验证：悬空 {@link} 可用 javac -Xdoclint:all 机械检出（实测 2→0）
faf553a8 docs 设计书 §8：批 3b 设计（纯加法）
41578c8a fix(gov,ga) ★ simos.gov.createOffice 整批被拒 + 5 处撒谎文案 + 假绿
e0d5748a test(app,ga) 批 3a 测试面：A1–A8 全落地 + 4 条变异自证（各杀各的）
f47ffc68 feat(ga-spi,ga,app) ★ 批 3a：工具三分类装配期分区审计 + D1/D2/D3
ea7d18bb docs 批 3 设计书：审计判据必须读真 gate()，不许引入第二处声明；D1 降级为纯外观
47142aea docs 批 3 设计书：由 (roles,gate) 纯派生 + 装配期分区审计
b88dc240 docs 七·门禁：门禁项有先后 + 门禁与变异必须串行
a7b9aec8 test(app) 批 J：提交语义变更的测试面（I13/I14/I15）
76a16ab6 feat(ga)! ★ 提交语义变更 —— PENDING 不是修改锁（批 I）
08793d30 docs 设计书 §3.9 提交语义变更
0b4976f9 test(app) 收口测试面：actAs 判据 G1–G10 + 桶计数锚点
2b9ac061 feat(ga,app) ★ simos.gm.actAs —— GM 以某个决策人的环境调用工具（批 G）
```

### 2.2 10-14（GM⇄DM 主体）

```
7cd98b5f docs §3.7.1 GM 代打三条裁定   7c5a3558 docs 批次 F 落定 + 取数教训
6f157693 refactor(ga,ga-spi,app,gov,army,unit)! 权限域名 sd → ga
816df952 test(app) GM⇄DM 轮次协议测试面（17 条红修 16 + 新增 14 + 4/4 变异）
4b3c574c docs §7.3 门禁暴露的第二个生产缺陷（SpotBugs）+「只到编译过」的漏检率
fba34b5c fix(ga) PacketDraftSupport 宽 catch 触发 SpotBugs
d909e7b9 fix(app) ★ 公用 sim-log 挂载用同一 holder 导致"已有决策人的 store"起不来（M9/W1）
2728be16 docs+fix 旧工具名/过时文案收口（批 E）
4187c51a feat(ga,app)! ★ 决策自由化 —— 七件套 + 权限转类3 + 信件序修正
ad91506e feat(ga,ga-spi,app)! ★ GM⇄DM 双向信件 + 推演确认 + 信件自动注入
1757985d docs 四条裁定落定（信件上限 N=128 · GM id=gm · reopen · 不加强制刹车）
26efd98d docs GM⇄DM 自主问答轮次约束设计书
2da0703d feat(ga,app,ga-spi)! AgentLib Docs/Workspace 接入 + 决策人互致消息
e2479fc0 docs AgentLib 接入设计书 + GOV·ARMY 调查报告
b82535e3 test 急停协议 + 拆 Skill + 分段等价 测试面
9290f539 feat(core,army,util)! 急停协议 + 取缔运行时 Skill + 修"户空即断供"
```

---

## 三、★★ 未提交（工作树里的两批，门禁跑完即可提交）

| 批 | 内容 | 文件 |
|---|---|---|
| **描述改准**（已交账） | 9 条 gov 工具的「决策人可省 `govUnitId`」文案改成**逐路径写清**（GM 面/propose 必填；**只有 DM 直写**可省） | `simos-gov/.../tools/write/Gov{AssignPosts,DefineCurrency,ExpandHousehold,IssueMoney,OpenPostsToMarket,RenameCurrency,SetBudgetPolicy,SetEstablishment,SetFxRate}Tool.java`（`161+/46−`，**只文案**：`roles(`/`gate(`/载荷键/校验/`planOnly` diff 命中 **0**） |
| **覆盖死角攻坚**（交账中，门禁在跑） | 5 个**非门控**守卫 + 门控夹具修复 | 新：`testing/GovE2eWorld.java` · `gov/GovCreateOfficeEndToEndTest.java` · `gov/ProvinceApplyEndToEndTest.java` · `decision/ProposalCatalogPlanOnlyTest.java` · `decision/ProposalCatalogPlanOnlyScanTest.java` · `decision/DecisionPacketEndToEndTest.java`；改：`decision/RealLlmGovScenarioTest.java`（`seedGenesis()` 补第 8 片 army + `ArmyCodec`） |

★ 覆盖批的**四条变异全部已还原**（含 `DecisionEffectExecutor` 那条"多提一条 PutInfo"的 MUTANT，md5 已核 `7c6f2a1479962c20925c633fcc32577f` = HEAD）。

---

## 四、★★ 未完成 / 未验证（诚实清单）

### 4.1 真 LLM 验证：**跑到真模型了，但守卫用例本身是坏的 ⇒ "通过"这句话不能说**

- ✅ **端点已验**：`mosire-flash`（远程中转站）与 `gemma4-local`（本机 llama.cpp）**tool calling 都正常**
  （`finish_reason=tool_calls` + 参数正确；0.7s / 1.1s）。
- ✅ **真跑过一次**（在独立工作树 `/tmp/simos-realllm`，打诊断补丁后）：`RealLlmUnitDecisionLoopTest`
  一轮走完 —— **14 次 LLM 调用 / 24 次工具调用 / head 1→7**；模型**如实报告**"可达面只有文档、决策包与 GM 信件"，
  **没有绕过、没有换名字重试**。
- ❌ **但两个门控用例都坏，且都坏在测试侧**（生产无回归：非门控用例全绿）：
  1. `RealLlmUnitDecisionLoopTest` —— ① 创世 codec 表缺 `GovCodec`/`ArmyCodec`（连世界都建不起来）；
     ② ★★ **前提早已作废**：夹具造的是**光板决策人**，而 `DecisionScopeFunctions` 成文规定
     「**没有归属 ⇒ deny-all**」⇒ `:345` 那条断言**不可能成立** ⇒ **出生即红**。
  2. `RealLlmGovScenarioTest` —— **10 处引用已改名的工具**（`:159-162` 当 `allowedTools` ⇒ **白名单四条全空名**；
     `:945`–`:985` **念给模型的提示词**也是旧名 + 旧预算「4–6 次」+ 旧动作名 `submit`）。
- ⇒ ★ **修复规格已备好**：`/tmp/realllm-spec/gated-test-fix-spec.md`（含两夹具归属来源**不同**的确切修法 + 验收）。

### 4.2 那条"死角"（★ 本段最重要的教训）

| # | 缺陷 | 为什么长期没红 |
|---|---|---|
| 1 | `gov.createOffice` 整体 0 落盘 | 门控 `SIMOS_REAL_LLM` + `seedGenesis()` 缺 army 片 |
| 2 | `province.apply` 整体 0 落盘 | 同上 |
| 3 | 4 条 catalog 工具**双写** | 同上 + `simos-gov/src/test` 里 `planOnly` **零覆盖** |
| 4 | 门控用例引用**改名工具** | 门控 ⇒ 从不跑 ⇒ 没人被告知它陈旧 |
| 5 | 门控用例创世**缺两个 codec** | 同上 |
| 6 | 门控用例**前提作废**（光板决策人=deny-all） | 同上 |

⇒ **"门控 + 坏夹具 + 零覆盖 + 用例从不更新" = 同一个死角，六次。** 那两条"唯一的真 LLM 守卫"
在历次"测试全绿"里**一次都没守住过任何东西**，存在感是假的。
★ **已立纪律**：改工具名要**全仓扫 `src/test/**` 的字面量**（不只 `src/main`）；**门控用例当"会腐烂的资产"定期真跑**。

### 4.3 其他未做（逐条）

- **同类工具未逐条走完** `decide→execute`：3b 只对 1 条走了全链（另 8 条验到 propose 成功 + planOnly 交批）。
- **资源面漏的三维**：币种（`ResourcePaths` 无币种路径）· `issueMoney` 的 `actor:<q>_<r>` 国库格 ·
  `assignPosts`/`openPostsToMarket` 的 `activity` 单位。
- **白名单洞**：`validateCall` 在 `dm.allowedTools()` **为空时整条白名单检查被跳过** ⇒ DM 能 propose catalog 里**任何一条**。
  真封它要动 `DecisionCallerFactory.whitelistFor`（会红 `GmActAsToolTest:94`）⇒ **单独立项**。
- **两条 policy 工具的 `noop + planOnly` 空批分支**未实测。
- **潜伏清单**（进 catalog 前必修，见设计书 §10.4）：`simos-social` 7 条（`AbstractHouseholdGmTool` 子类）·
  gov 非 catalog 10 条（absorbUnit / applyStaffing / createOffice / dismiss / pay / remit / transferTreasury /
  provinceApply / provinceAssignCities / regionClearStructures）。
- **同族文档存量**：`GovToolSupport:33/159` · `GovToolPerspective:18/31` · `GovPayTool:45` ·
  `RegionClearStructuresPlan:37` · `GovInfoTool:65`（悬空/过时引用，可用 `javac -Xdoclint:all` 机检）。
- **描述里「需 GM 在审批面点头」**只对 DM 直写成立（同族第二处，未改）。
- **日志体量**：一次 `clean verify` 的日志 **869 MB / 2.6M 行**（`io.mosire.simos.economy.market` 一个 logger ≈43%），
  而本仓纪律是「逐笔明细 = TRACE」⇒ 待降档。
- ★ **15 条 `simos.sd.*` 工具名**、`Sd*` 类名、`/api/sd/…` 路由 —— **有意未动**（另一批的活）。

---

## 五、下一步（交接清单，按依赖排序）

1. **提交工作树里的两批**（门禁 `clean verify` 绿后）：9 条文案 + 6 个测试文件。
2. **派"门控用例修复批"**（规格 `/tmp/realllm-spec/gated-test-fix-spec.md`）：
   - `RealLlmUnitDecisionLoopTest`：补 codec + **给夹具写真的 army 归属行** + 补"裸决策人 = deny-all"对照；
   - `RealLlmGovScenarioTest`：改 10 处旧名 + 更新简报语义（`confirm` / 预算 24 / `propose→confirm→decide→execute`）
     + 建议删掉"手写工具指引"让生产侧开场消息做**唯一来源**。
3. **真 LLM 复跑**：`SIMOS_REAL_LLM=1` 先 `SIMOS_GOV_SCENARIO_ROUNDS=1` 冒烟，再缺省 3 轮；
   拿 `Tests run:` + `[REAL-LLM-ROUNDS-JSON]` 的真实数字。
4. **真机复合**（视 3 的结果）：`./mvnw -q -pl simos-app -am -DskipTests package` →
   `./run-small-world.sh`（新鲜 store，端口 58xx）→ 经 MCP 建决策人 → 真跑一轮 →
   验**双向信件 / 多轮提交 / `actAs` / `planOnly` 恰好一条 revision**。
5. 设计书 §10.6 的测试面待办（`planOnly` 覆盖已由覆盖批做了；剩 §4.3 那些）。

---

## 六、怎么复跑（可复制原命令）

```bash
# 全量门禁（一次只跑一个 Maven；spotless 在 SpotBugs 之前 ⇒ spotless 红则该模块 SpotBugs 根本没跑）
tools/mvn-lock.sh clean verify

# 真 LLM（★ 建议在独立工作树跑，免被在飞的批污染）
git worktree add --detach /tmp/simos-realllm HEAD
cd /tmp/simos-realllm
SIMOS_REAL_LLM=1 tools/mvn-lock.sh -pl simos-app -am \
    -Dtest='RealLlmUnitDecisionLoopTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 真机
./mvnw -q -pl simos-app -am -DskipTests package   # ★ 先停服务，别覆盖在跑的 jar
./run-small-world.sh                              # 缺省 GUI 5811 / MCP 5815 / 审批 5813
```

★ **磁盘**：一次 `clean verify` 的完整日志约 **869 MB** ⇒ 取完数字就删，否则几次就堆 GB 级。

---

## 七、口径要点（免得下次重新推）

1. **三格口径**：类1 `roles()=={GM}`（**`gate` 不参与**，其 `Ask` 是用户裁定保留）· 类2 `roles()` 含 DM 且
   `gate()` = `Ask` · 类3 含 DM 且 `ALLOW`。★ `{DM}` 与 `{DM,GM}` 在这一维**等价**。
   **权威数字 = 真装配实测 `140 / 17 / 37`**（与两道桶锚点 `179`/`54` 自洽）；文件 grep 会系统性低估。
2. **决策人范围的唯一来源是各模块的归属表**（`ArmyAffiliations` / `GovAffiliations` / `NationAffiliations`）；
   `DecisionMaker` **没有**归属字段。★ **光板决策人 = deny-all**（用户裁定：「允许创建没有更多附加状态的单个决策人」）。
   ⇒ 任何"给决策人配范围"的夹具/文档，**都必须先写归属表**。
3. **真实决策流程**：`propose`(DRAFT) → **`confirm`**(PENDING) → `decide`(APPROVED) → `execute`（**恰好一条 revision**）。
   GM **不能裁决 DRAFT**。
4. **catalog 的硬前置条件**：每条成员**必须支持 `planOnly=true`**（"走与 `preview=false` 完全相同的纯推导与组批，
   但在提交前把命令批返回"）。不满足 ⇒ `gm.packet.execute` 规划阶段**真落盘** ⇒ **双写**。
5. **`description`/javadoc 是契约不是装饰** —— 本段两个"整体 0 落盘"的缺陷，成因都是**描述撒了谎而下游照着写**。
   ⇒ 改描述要**连它导致的下游一起改**；`javac -Xdoclint:all` 可机检悬空 `{@link}`。
6. **5 分钟审批上限不能"取消"**：传 `null` ⇒ AgentLib 当 `ZERO` ⇒ **立刻拒**；给巨大值 ⇒ **死锁**
   （GM 卡在 `run-decision-makers` 里等 DM 批次）。正确解 = **让阻塞路径根本不被走到**（3b 已做）。
