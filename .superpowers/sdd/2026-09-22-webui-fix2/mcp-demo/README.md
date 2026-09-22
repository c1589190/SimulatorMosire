# MCP 演示：为「大蜀」「西陵」建决策人并跑一次决策

- **日期**：2026-09-22（本机时区；UTC 时间戳 `20260921T164709Z`）
- **对象实例**：`5817`（pid 455569，`--store /tmp/sept-rich5 --demo`，**全程未重启**）
- **MCP 口**：`http://127.0.0.1:5715/mcp`（`EXTERNAL ∪ GM`，**16** 条工具）
- **客户端**：官方 MCP Java SDK（`McpClient.sync` + `HttpClientStreamableHttpTransport`）

## 做了什么（五步）

| 步骤 | 结果 |
|------|------|
| 1. 连 MCP + `tools/list` | ✅ 16 条工具，与源码 `EXTERNAL_WITH_GM` 逐条相等（见 `01-tools-list.md`） |
| 2. 建 sd Nation（先只读确认不存在） | ✅ `大蜀`(rev 6)、`西陵`(rev 7)。**先发现并绕过了一个真缺口**（见下） |
| 3. 建决策人 | ✅ `dm-dashu`(rev 8)、`dm-xiling`(rev 9)，`allowedTools=[sd.IssueDirective,sd.SubmitVerdict]`，`cadence=5`，均 `due=true`（见 `02-decision-makers.md`） |
| 4. 跑一次决策 | ✅ `sd.StartDecision` on `dm-dashu`(rev 10)，全链 `received→committed`（见 `03-decision-chain.md`） |
| 5. 备份地图 | ✅ `/tmp/sept-rich5-backup-20260921T164709Z`，4/4 文件 md5 全等（见 `04-backup.md`） |

最终 `main`：`head=10`，`tick=0`；所有写都落成真 `Command → ChangeSet → Revision`，`initiator=agent:external-mcp`
（经 5715 的 MCP 写），每条敏感写都经**真审批门**（HTTP 审批面留痕在每个 `evidence/*.approval`）。

## 人话总结

1. **连接的 MCP 口（5715）挂了 16 条工具**：9 条读 + 3 条通用写（`submit`/`advance`/`fork`）+ 4 条 GM 窄写
   （`IssueDirective`/`SubmitVerdict`/`SetViewScope`/`StartDecision`）。这与运行日志和源码完全一致，没有意外工具。
2. **`大蜀`/`西陵` 原本既无 sd Nation 也无决策人**。建完 sd Nation 后，为两国各建一个决策人
   （`dm-dashu` / `dm-xiling`），都挂 `nation` 从属、都**不带通用写**（合 N9 白名单），`due` 都是 `true`。
3. **跑了一次决策**：对 `dm-dashu` 发 `sd.StartDecision`，写成功（rev 10），决策发起记录落在 sd INFO 覆盖层
   （地址 `sd:decision.dm-dashu`，key=`start`，note=`T12 demo：大蜀开始决策`）。它**不占** R4 的 Directive 名额。
4. **地图已完整备份**：`cp -a` 整个 store 目录，文件数与逐文件 md5 两侧全等。
5. **界面确认**：打开 `http://127.0.0.1:5817/`，切到「决策」模式，左栏「国家 2」列出
   `大蜀 / dm-dashu / 待决` 与 `西陵 / dm-xiling / 待决`；顶栏/底栏均显示 `rev 10 · head 10`。

## ★ 发现的缺口（如实报告，**未改代码**）

**M6/富世界的区域 tag 与 SDSimos R13 对不上。**

- 富世界（v17levant 复刻）里 252 个区域的 `meta.tag` 全是字面量 **`"Nation"`**
  （`RichWorldTest:90` 就是按 `"Nation"` 数的）。
- 但 `CreateNationHandler` 要求 `homeRegionId` 的 tag 以 **`nation:`** 开头
  （`NationTag.PREFIX="nation:"`，`NationTag.isNationTag` = `startsWith("nation:")`）；WebUI 的「决策」模式
  同样按 `nation:<nationId>` 映射区域→国家（`webui/map.js:2373`）。

**实测证据**：第一次直接 `sd.CreateNation` 落 `homeRegionId=大蜀` 被拒：

```
TEXT=[mosire:code=REJECTED]
{"result":"rejected","reason":"Region 大蜀 无国家 tag（R13：需以 nation: 开头的 tag）", ...}
```

事件表里是 `received → rejected`，**没有** revision（head 停在 3）——`evidence/10-createNation-dashu-attempt1.txt`。

**为完成任务所做的处置（运维动作，非改代码）**：先用正常命令 `map.UpdateRegion`（rev 4/5）把这两个区域的 tag
改成 `nation:大蜀` / `nation:西陵`（`color`/`description`/`annexedBy` 原样保留），再建 Nation。
即：**不是绕过 Command→ChangeSet→Revision，而是补了一条前置命令**。

> 这条缺口本身没被修（按本任务「不去修缺陷」的要求）。它属于**数据/约定层面**：
> 要么富世界种子应直接产出 `nation:<id>` 的 tag，要么 `sd.CreateNation` 的 R13 约定应放宽。
> 只有 `大蜀`/`西陵` 两个区域被改，其余 250 个仍是 `"Nation"` —— 世界此刻是**不一致**的，已如实记录。

## 产物清单

| 文件 | 内容 |
|------|------|
| `01-tools-list.md` | 5715 的 16 条工具清单（真实 `tools/list`） |
| `02-decision-makers.md` | 两个 Nation + 两个决策人的真实读返回 |
| `03-decision-chain.md` | 全链路：命令 → revision → 事件 + rev10 changeset 原文 |
| `04-backup.md` | 备份路径 + 一致性比对 |
| `gui-5817.png` | 工作台截图（常规模式，`rev 10`） |
| `gui-5817-decision-mode.png` | 「决策」模式截图（列出两国决策人，`待决`） |
| `evidence/` | 全部原始证据（MCP 返回、审批留痕、事件 JSON、md5 清单、截图日志） |

### 截图说明

Playwright MCP 服务（`skill_mcp` 的 `playwright`）**未能用**：它要 `chrome` channel
（`/opt/google/chrome/chrome` 不存在），而本机 `~/.cache/ms-playwright` 只有 `chromium-1234`。
故改用**同机 `playwright-core` + 显式 `executablePath` 指向 `chromium-1234`** 直连截图，
启动参数带 `--no-proxy-server`（等价于 `--noproxy '*'`）。两张图均成功（见 `evidence/50-*.log`、`51-*.log`）。

## ★ 我未能核实的

- **`agentlib-mosire` 是外部依赖，不在本仓**：审批/传输/权限的**实现**（`ApprovalHttpEndpoint` /
  `ApprovalCoordinator` / `ToolCallAuthorizer` / MCP 服务端 `AgentToMcpServer`）都来自
  `~/.m2/.../agentlib-mosire-0.1.0-SNAPSHOT.jar`。本任务只**从外部**验证了「审批面确实存在且能放行/
  拒绝」（HTTP 契约由反编译字符串 + 真调用得到），**没有**核对其内部实现、也没有核对本仓声明的
  agentlib 版本与运行 jar 是否逐字节对应（`~/.m2` 不跨机同步）。
- **5717（决策人口）未连**：本任务只连 5715。5717 的工具面仅以日志 `i5817.log:2` 与源码为据
  （11 条，无通用写），**未**经真 `tools/list` 实测。
- **决策的「后续」未跑**：`sd.StartDecision` 只写「发起」事实；真正的出令 `sd.IssueDirective` /
  判决 `sd.SubmitVerdict` **未**在本任务触发（需要决策 Agent 侧介入）。
- **`due` 的计算细节**：只读到 `due=true` 与 `lastDirectiveTick=null`，未逐值核对 `cadence` 公式。
- **备份不是 SQLite 热备**：文件级 `cp -a`（拷贝期间无并发写者，故自洽）；有并发写时应改用 `.backup`。
- **只对一个实例/一份世界**：全部结论基于 `/tmp/sept-rich5` 这一份 `--demo` 世界，未上其它真档。
