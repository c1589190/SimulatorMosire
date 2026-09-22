# ② 这次决策的完整链路（命令 → revision → 事件）

分支 `main`，全部落在 tick=0。表里每一行是一条 revision；`事件` 列是该命令在 `events` 表里的链（同一 `correlation_id`）。

| rev | 命令类型 | initiator | parent | 本 revision 的变更 | 事件链（seq:type） |
|-----|----------|-----------|--------|--------------------|--------------------|
| 1 | `core.Bootstrap` | `system:bootstrap` | — | (无变更) | （无事件：创世 core.Bootstrap 不经 CommandBus） |
| 2 | `map.UpdateRegion` | `player:gui` | main@1 | map.regions upsert keys=['区域341234123'] | 1:received → 2:committed |
| 3 | `map.UpdateRegion` | `player:gui` | main@2 | map.regions upsert keys=['区域341234123'] | 3:received → 4:committed |
| 4 | `map.UpdateRegion` | `agent:external-mcp` | main@3 | map.regions upsert keys=['大蜀'] | 7:received → 8:committed |
| 5 | `map.UpdateRegion` | `agent:external-mcp` | main@4 | map.regions upsert keys=['西陵'] | 9:received → 10:committed |
| 6 | `sd.CreateNation` | `agent:external-mcp` | main@5 | sd.nations upsert keys=['大蜀'] | 11:received → 12:committed |
| 7 | `sd.CreateNation` | `agent:external-mcp` | main@6 | sd.nations upsert keys=['西陵'] | 13:received → 14:committed |
| 8 | `sd.CreateDecisionMaker` | `agent:external-mcp` | main@7 | sd.decisionMakers upsert keys=['dm-dashu'] | 15:received → 16:committed |
| 9 | `sd.CreateDecisionMaker` | `agent:external-mcp` | main@8 | sd.decisionMakers upsert keys=['dm-xiling'] | 17:received → 18:committed |
| 10 | `sd.StartDecision` | `agent:external-mcp` | main@9 | sd.info upsert keys=['sd:decision.dm-dashu'] | 19:received → 20:committed |

## 本次任务新增的 revision（rev 4 → rev 10）

- **rev 4**：把区域 `大蜀` 的 `meta.tag` 从 `Nation` 改成 `nation:大蜀`（R13 要求的前缀），顺带保留 color/description/annexedBy
- **rev 5**：把区域 `西陵` 的 `meta.tag` 从 `Nation` 改成 `nation:西陵`
- **rev 6**：建 sd Nation `大蜀`（homeRegion=大蜀，adminBudgetPerTick=10）
- **rev 7**：建 sd Nation `西陵`（homeRegion=西陵，adminBudgetPerTick=8）
- **rev 8**：建决策人 `dm-dashu`（affiliation=nation:大蜀，allowedTools=[sd.IssueDirective,sd.SubmitVerdict]，cadence=5）
- **rev 9**：建决策人 `dm-xiling`（affiliation=nation:西陵，同允许工具，cadence=5）
- **rev 10**：`sd.StartDecision`：发起 `dm-dashu` 的决策，落 INFO 覆盖层

## rev 10（sd.StartDecision）的事件原文

- `seq=19` `simos.command.received` agent=`agent:external-mcp`
  ```json
  {"branch":"main","expectedRevision":"9","commandId":"ffc4b1e8-c6a2-42b0-a099-a29c9fce21e3","payloadDigest":"sha256:519dc182eed38cdf28da66fe2f1a73b3","type":"sd.StartDecision"}
  ```
- `seq=20` `simos.command.committed` agent=`agent:external-mcp`
  ```json
  {"branch":"main","revision":"10"}
  ```

## rev 10 的 changeset 原文（决策记录落在 sd INFO 覆盖层）

```json
{"@class":"io.mosire.simos.core.state.WorldChangeSet","modules":{"sd":{"@class":"io.mosire.simos.sd.change.SdChangeSet","nations":{"@class":"unchanged"},"armies":{"@class":"unchanged"},"combats":{"@class":"unchanged"},"combatStates":{"@class":"unchanged"},"decisionMakers":{"@class":"unchanged"},"directives":{"@class":"unchanged"},"effects":{"@class":"unchanged"},"verdicts":{"@class":"unchanged"},"lossRecords":{"@class":"unchanged"},"info":{"@class":"upsert","entries":{"sd:decision.dm-dashu":[{"key":"start","value":"0","note":"T12 demo：大蜀开始决策","at":{"value":9},"sourceDirective":null}]}}}}}
```

## 一次被拒的尝试（证明「被拒 = 不留 revision」）

第一次直接对 `大蜀` 发 `sd.CreateNation` 被 R13 拒：

- 事件：`seq=5 received` → `seq=6 rejected`（agent=`agent:external-mcp`，corr=`3b20f957-…`）
- revision 表：**没有**对应行（head 停在 3）
- 返回：`{"result":"rejected","reason":"Region 大蜀 无国家 tag（R13：需以 nation: 开头的 tag）"}`
