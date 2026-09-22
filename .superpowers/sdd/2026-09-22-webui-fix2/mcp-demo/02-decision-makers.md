# ② 决策人清单（读接口的真实返回）

## 前置：`大蜀` / `西陵` 的 sd Nation 先建好（否则 affiliation 无处可挂）

`simos.state.resolve`（MCP 读工具，5715）：

```
CALL=simos.state.resolve ARGS={"address":"sd:nation.大蜀"}
TEXT={"candidates":[{"id":{"namespace":"sd","localId":"nation.大蜀"},"canonicalAddress":"sd:nation.大蜀","typeName":"Nation"}]}

CALL=simos.state.resolve ARGS={"address":"sd:nation.西陵"}
TEXT={"candidates":[{"id":{"namespace":"sd","localId":"nation.西陵"},"canonicalAddress":"sd:nation.西陵","typeName":"Nation"}]}
```

建的命令（各一条真 revision）：

| rev | 命令 | 载荷 |
|-----|------|------|
| 6 | `sd.CreateNation` | `{"nationId":"大蜀","name":"大蜀","homeRegionId":"大蜀","adminBudgetPerTick":10}` |
| 7 | `sd.CreateNation` | `{"nationId":"西陵","name":"西陵","homeRegionId":"西陵","adminBudgetPerTick":8}` |

## 决策人列表：`GET /api/sd/decision-makers`（真实返回，截自 head=10）

```json
{
  "decisionMakers": [
    {
      "id": "dm-dashu",
      "affiliation": { "kind": "nation", "id": "大蜀", "displayName": "大蜀", "nationId": "大蜀", "rootUnit": null },
      "allowedTools": ["sd.IssueDirective", "sd.SubmitVerdict"],
      "cadence": 5,
      "viewScope": { "visibleRegions": 0, "visibleHexes": 0, "visibleUnits": 0, "seeOwnUnits": false,
                     "adjudicationDisclosure": "WITHHELD", "redactedFields": [] },
      "due": true,
      "lastDirectiveTick": null,
      "ticksSinceLast": null
    },
    {
      "id": "dm-xiling",
      "affiliation": { "kind": "nation", "id": "西陵", "displayName": "西陵", "nationId": "西陵", "rootUnit": null },
      "allowedTools": ["sd.IssueDirective", "sd.SubmitVerdict"],
      "cadence": 5,
      "viewScope": { "visibleRegions": 0, "visibleHexes": 0, "visibleUnits": 0, "seeOwnUnits": false,
                     "adjudicationDisclosure": "WITHHELD", "redactedFields": [] },
      "due": true,
      "lastDirectiveTick": null,
      "ticksSinceLast": null
    }
  ]
}
```

原始文件：`evidence/22-dms-final.json`、单条详情 `evidence/32-dm-dashu-after.json`。

## 建决策人的命令（各一条真 revision）

| rev | 命令 | 载荷 | 返回 |
|-----|------|------|------|
| 8 | `sd.CreateDecisionMaker` | `{"id":"dm-dashu","affiliation":{"kind":"nation","id":"大蜀"},"allowedTools":["sd.IssueDirective","sd.SubmitVerdict"],"cadence":5}` | `{"result":"committed","ref":{"branch":"main","revision":8}}` |
| 9 | `sd.CreateDecisionMaker` | `{"id":"dm-xiling","affiliation":{"kind":"nation","id":"西陵"},"allowedTools":["sd.IssueDirective","sd.SubmitVerdict"],"cadence":5}` | `{"result":"committed","ref":{"branch":"main","revision":9}}` |

★ `allowedTools` **不含** `simos.command.submit`（N9：决策人不得持通用写）；含了会被 handler 直接拒。
原始返回见 `evidence/20-createDM-dashu.txt` / `evidence/21-createDM-xiling.txt`（含各自的审批留痕 `.approval`）。

## 待决信号（`due`）

`due: dm-dashu -> True (lastDirectiveTick=None, ticksSinceLast=None)`
`due: dm-xiling -> True (lastDirectiveTick=None, ticksSinceLast=None)`

原始：`evidence/30-due-state.json`。
