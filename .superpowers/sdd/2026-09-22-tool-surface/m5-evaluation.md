# M5 评估：通用写是否收窄（D-4）

> 依据：计划 §二 **M5**（「标准化覆盖够之后，**通用写是否收窄**？⇒ 列为开口项，出一份评估（受益 vs 破坏 D2 取舍）」）
> 与 **D-4 = 只评估**。**本文件不含任何代码改动。**
>
> 所有事实都在**今天的字节**上现场量过（2026-09-22），出处带 `文件:行`；**凡属推断的一律标注**。
> 与 `pending-rulings.md` §二（M4 读口）无关，是**两个独立**的待裁项。

---

## 〇. 三行摘要

1. 「通用写」在**工具面**上**只有一条**：`simos.command.submit`（`simos.advance` / `simos.fork` 的**命令类型是固定的**，结构上属窄工具，只是名字没跟命令类型同名）。
2. **覆盖已闭**：**43 个生产命令类型 ↔ 43 条同名窄写工具**（现场 `comm` 实测是**双射**）⇒ 去掉通用写，**没有任何命令类型变得不可达**。
3. ★★ **但收窄工具面 ≠ 收窄写权限**：`POST /api/command`（`GuiServer.java:498` → `:782`）接受**任意 `type`**、**不过审批门链** ⇒ 能到达 GUI 端口的人**仍握着一条完整的通用写**。

★ **权威挂钩（逐字核过）**：spec §八.3 原文（`docs/superpowers/specs/2026-09-20-sd-simos-design.md:478`）已经把这件事**列为挂起**：

> **外部 MCP 桶**（**保留现状或另行收敛，列为挂起**）

⇒ **D-4 就是兑现这句「另行收敛」**；同节 `:479` 另写「★ **GM 绝不握通用写**（N11）」——**那条今天已经满足**。

---

## 一. 「通用写」到底是哪几条（实测）

| 工具名 | 命令类型由谁定 | 依据 | 属「通用写」吗 |
|---|---|---|---|
| `simos.command.submit` | **调用方自选**（schema 里有 `type` 字段） | `CommandSubmitTool.java:35`（NAME）、`:63`（`props.put("type", …)`） | ★ **是，且是唯一一条** |
| `simos.advance` | 固定 `AdvanceTime` | `AdvanceTool.java:108-109`（`new AdvanceTime(...)`）；schema 只有 `branch`/`expectedRevision`/`from`/`to`（`:62-65`） | 否（固定类型工具） |
| `simos.fork` | 固定 `ForkBranch` | `ForkTool.java:85-86`（`new ForkBranch(...)`）；schema 只有 `source`/`expectedRevision`/`newBranch`（`:55-57`） | 否（固定类型工具） |

★ 这三条在 `SimosToolSource.addExternalWrites`（`:155-161`）里被合称「通用写」，但**逐条读 schema 后只有第一条名副其实**。
⇒ 「收窄通用写」= **是否去掉 `simos.command.submit` 这一条**。

---

## 二. 它进哪个桶 / 谁拿到（实测）

- **唯一构造点**：`SimosToolSource.java:158`，在 `addExternalWrites` 体内（该方法体共 3 行 `:158-160`）⇒ **只进 `EXTERNAL` 桶**。
- `GM` 桶**无**通用写：`SimosToolSource.java:90` javadoc 原文「**无通用写**（N11）」。
- `DECISION_AGENT` 桶**无**通用写：`SimosToolSource.java:94` 原文「**无通用写**（N9）」。
- **生产里的两个活口**（各一处构造，`Shell.java:439` / `:455`）：
  - `EXTERNAL_WITH_GM` = `EXTERNAL ∪ GM`（`SimosToolSource.java:147-150`，两个 `add*Writes` 依次调用）→ **含** `simos.command.submit`；
  - `DECISION_AGENT` → 不含。
- ★ **`Role.EXTERNAL` 在生产代码里没有构造点**：`git grep 'new SimosToolSource('` 命中 3 处、**全在 `Shell.java`**（`:439` / `:455` / `:610`），前两处都带 role 实参；5 参构造器（默认 `EXTERNAL`，`:125`）**生产零调用**。
- ⇒ **「收窄」的实际落点 = 现有 MCP 口少一条工具**（那个口的持有者按 `Shell.java:467` 的类注是「外部 MCP 客户端 + GM」混合，**权限边界在端口、不在身份**）。

**四桶规模**（★ **从源码逐条数出来的**，不是抄任何文档）：
读 9（`:254-263`）+ 外部写 3（`:158-160`）+ GM 窄写 43（`:174-218`）+ 决策窄写 22（`:227-249`）

| 桶 | 算式 | 规模 |
|---|---|---|
| `EXTERNAL` | 9 + 3 | **12** |
| `GM` | 9 + 43 | **52** |
| `DECISION_AGENT` | 9 + 22 | **31** |
| `EXTERNAL_WITH_GM` | 9 + 3 + 43 | **55** |

---

## 三. ★ 覆盖双射（现场实测，不是算术）

**方法**（两步都是现场跑的）：

```
# ① 命令类型：扫全部 *Handler.java 的 type() 返回字面量
git grep -h -A2 'public String type()' -- simos-map simos-unit simos-sd simos-core simos-app > /tmp/handlers-type.txt
grep -o 'return "[^"]*"' /tmp/handlers-type.txt | sed 's/return "//; s/"$//' | sort -u   → 44
# ② 工具名：扫 write/ 下全部 NAME 常量
git grep -h -o 'NAME = "[a-zA-Z.]*"' -- 'simos-app/src/main/java/io/mosire/simos/app/tools/write'  → 46
```

`comm` 结果：

- **46 − 43 = 3**，恰是 `simos.command.submit` / `simos.advance` / `simos.fork`（**即 §一 的三条"通用写"**）；
- **44 − 43 = 1**，只剩 **`map.RandomizeTest`** —— 它是 `simos-map` 里的**测试夹具**（按名字与所在路径判断，**未逐行读该类**），不是生产命令类型。

⇒ **生产命令类型 43 条，与 43 条同名窄写工具一一对应**。这条也与 `SimosToolsTest.java:476` 的 `.hasSize(43)`（数 handler 实现）**独立吻合**。

---

## 四. 收窄工具面的代价（逐条）

| 项 | 实测结论 |
|---|---|
| **命令类型可达性** | ★ **零损失**（§三 双射） |
| **审批强度** | ★ **完全不变**：`simos.command.submit` 与窄工具**都是** `ToolSpec.level(DEFAULT, true, false)`（`CommandSubmitTool.java:72`；`AbstractNarrowWriteTool` 同）+ `ToolGate.Ask(name(), …, AskKind.SENSITIVE)`（`:83-92`） |
| **资源面** | ★ **不变**：两者都是 `ToolSupport.ALL_WRITE`（`CommandSubmitTool.java:77`） |
| **`AdvanceTime` / `ForkBranch`** | **不受影响**（各有 `simos.advance` / `simos.fork`） |
| **代码改动量** | **一行**（删 `SimosToolSource.java:158`）+ 该方法的 javadoc 与命名（「通用写」不再准确） |
| **会红的既有判据** | `SimosToolsTest`（`:418` `registryContainsExactlyTheExternalUnionGmTools`、`:593` `roleBucketsNeverCarryGenericWrite` 等）、`McpServerTest`、`McpPortTopologyTest` —— ★ **这是护栏在起作用、不是缺陷**（本仓口径：改动被测文件 ⇒ 按裁定 42 连带重跑受影响的变异轮） |
| ★ **真实代价 ①** | **新命令的兜底没了**：今天"每加一条命令就配一条窄工具"是**惯例，不是被护栏钉住的**（见 §六）。收窄后若漏配，该命令在这个口上**静默不可达**（fail-closed，但**没有任何东西会红**） |
| ★ **真实代价 ②** | **文档连带**：`simos-app/src/main` 下 **9 个文件 / 12 行**提到 `simos.command.submit`（`git grep -c` 现场计数），其中多条窄工具/读工具的 javadoc 用它论证「**工具层有意不重复校验**（那份校验能被通用写绕过 ⇒ 是装饰）」。删掉工具后**这条论证仍然成立**（HTTP 面还在，见 §五）——但**引用点需要改写**，否则指向一个不存在的工具 |
| ★ **真实代价 ③** | **模型侧行为未实测**：通用写让模型「先读 catalog 再自选 type」；收窄后只能从 43 条工具里挑。**判断**：这是**收益**（N9 的原意）。★ 但**本机无 LLM 客户端 ⇒ 纯推断，零实测** |

---

## 五. ★★ 只收窄工具面 ≠ 收窄写权限

`POST /api/command`（`GuiServer.java:498` → `submitReply`，`:782-795`，**方法体 14 行全文读过**）：

```java
private Reply submitReply(HttpExchange exchange) throws IOException {
  JsonNode root = readBody(exchange);
  String id = UUID.randomUUID().toString();
  CommandEnvelope command =
      new CommandEnvelope(
          id, id, GUI_INITIATOR,
          new BranchId(textField(root, "branch")),
          new RevisionId(longField(root, "expectedRevision")),
          textField(root, "type"),        // ★ 任意字符串，由请求体给
          payloadField(root));
  return resultReply(core.submit(command));
}
```

- **`type` 由请求体给** ⇒ 这是一条**完整通用写**；
- 该方法体内**没有任何审批调用**（对照：MCP 工具面走 `ToolGate.Ask` + `AskKind.SENSITIVE`）；
- `GuiServer.java:78` 的类注把写端点列作「**写面唯一**：`/api/command`、`/api/advance`、`/api/fork` 与 T10 的窄写…」；
- ★ **端口可绑对外**：M10 已加 `--bind-address`（缺省 `127.0.0.1`）；显式传 `0.0.0.0` 时这条路径**在局域网上可达**（M10 实测：经 `192.168.71.21` GUI 返回 200）。

⇒ **若 D-4 的意图是「系统里不该存在任意 type 的写入口」，只删工具面不达成目的**——GUI 端口上还剩一条，而且它连审批门链都没有。
**推断（未实测）**：这条路径今天**没有**任何按视角/身份的裁剪；`GUI_INITIATOR` 恒为 `player:gui`（`GuiServer` 常量，未逐字核值）。

---

## 六. ★ 一条今天为真、但没有护栏的不变量

「**每个命令类型都有一条同名窄写工具**」今天成立（§三 双射），但**没有任何判据钉住它**。现有三族判据各自只钉了一半：

| 判据 | 钉的是哪一段 |
|---|---|
| `SimosToolsTest:574` `catalogCoversEveryCommandHandlerImplementation` | **命令类型 ↔ catalog**（catalog ⊇ 全部 handler 类型） |
| `SimosToolsTest:710` `everyNarrowWriteToolClassIsWiredIntoTheGmBucket` | **工具类 ↔ GM 桶** |
| `SimosToolsTest:432 / :466 / :519` `{map,unit,sd}NarrowWriteToolsAreNamedAfterTheirFixedCommandType` | **桶 → 类型**（一个方向） |

⇒ **「类型 → 工具」这个方向没有判据。**

★ 今天它只是「便利性」的事实；**D-4 一旦选收窄，它就变成承重**：破了**不会红**，只会让那条命令在那个口上**静默消失**。

**若选收窄，这条判据要一起补**，形态建议（照本仓既有形制）：
扫 `*Handler.java` 的 `type()` 集合 **==** 扫 `write/*Tool.java` 的 `NAME` 集合 **−** 3 条通用写；
★ **并先断言扫到的集合非空** —— 否则扫描器写错时会退化成「**空 == 空 恒真**」（本仓踩过，见 `CLAUDE.md` 纪律形态清单）。

---

## 七. 三个可选项（D-4）

| # | 选项 | 做什么 | 代价 |
|---|---|---|---|
| **1** | **不动**（维持 D2） | 一行不改 | 该口持有者可绕过 43 条窄工具直接提交任意类型。★ **这本来就是 D2 的明文取舍**（`SimosToolSource.java:101-103`：「保留通用写是**用户裁定 D2 的取舍、不是缺陷**（其持有者可绕过窄工具直接提交任意命令）」） |
| **2** | **只收窄工具面** | 删 `SimosToolSource.java:158` 一行 + 改该方法 javadoc/命名 + 更新三处测试常量 + **补 §六 那条判据** | **零命令类型损失**（§三）；**但 HTTP 面仍在**（§五）⇒ 只对"走 MCP 的 agent"生效。★ 按裁定 42 需连带重跑受影响的变异轮 |
| **3** | **两面一起** | 选项 2 + 处理 `/api/command` | ★ **这是另一个设计问题，不是收窄**：GUI 工作台的编辑功能（M7/M8）依赖它——M7c 实测「**非 GET 清单只剩 `/api/command` 一条**」⇒ 删掉它，**工作台全部编辑功能失效**。⇒ 现实的形态是「**是否给 GUI 写端点也加一道门**」= **新需求** |

★ **控制器没有替你选**。三档的差别不是"严不严"，而是「**管哪一面**」：
**只收窄工具面**管的是"经 MCP 的 agent"；**要管住"任意 type 的写入口"必须碰 HTTP 面**。

---

## 八. 我未能核实的（必填）

1. **零 LLM/agent 行为实测**：收窄后模型是否更好用/更难用，本机无客户端 ⇒ §四「真实代价 ③」**全是推断**。
2. **`/api/command` 的调用者范围**：只核到「GUI 工作台发它」（M7c 既有证据）与它**不过审批**；**没有**核到除工作台外还有谁依赖它，也**没有**核到 `GUI_INITIATOR` 的字面值。
3. **`map.RandomizeTest` 是测试夹具**：按名字与所在路径判断，**没有逐行读该类**（⇒ §三 的"生产 43 条"依赖这一步）。
4. **§四「真实代价 ②」的 9 文件 / 12 行**是 `git grep -c` 的**命中数**，**没有**逐条判断每一处是否真的需要改写。
5. **spec §八.3 的全文**：只读了 `:475-479` 那一段（含 §〇 引的那句原文），**没有**通读该节其余部分，也**没有**核 spec 别处是否另有关于外部口的裁决。
6. **本评估未跑门禁、未跑变异**（有意：只评估、零改动）。§二 的四桶规模是**从源码数出来的**，不是跑出来的——**它与 M3 实现者断言里的期望值是否一致，由 M3 的评审回答**（`pending-rulings.md` §五）。
