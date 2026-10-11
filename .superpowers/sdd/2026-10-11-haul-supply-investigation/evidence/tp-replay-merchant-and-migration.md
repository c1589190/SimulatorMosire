# 跑商与迁移（本期 = day 601..660（60 个已完结日））

## MERCHANT_HAUL_RUN（逐笔承运，TRACE）

| day | household | pureMerchant | commodity | from | to | quantityMilli | serviceMilli | waivedFreightMilli |
|---|---|---|---|---|---|---|---|---|
| 630 | hh-0_-3-urban-rich_peasant | true | cloth | 0_-3 | -1_-2 | 120 | 243 | 0 |
| 630 | hh-0_-3-urban-rich_peasant | true | cloth | 0_-3 | 0_-2 | 210 | 425 | 0 |
| 630 | hh-0_-3-urban-rich_peasant | true | cloth | 0_-3 | 1_-3 | 2322 | 4691 | 0 |

## MIGRATION_MOVE / MIGRATION_PLAN / PRIMARY_MODE_*

```
MIGRATION_PLAN {'event': 'MIGRATION_PLAN', 'originKind': 'tick', 'day': '630', 'moves': '3', 'organizations': '122', 'modeHexProfits': '81'}
MIGRATION_PLAN {'event': 'MIGRATION_PLAN', 'originKind': 'tick', 'day': '660', 'moves': '3', 'organizations': '122', 'modeHexProfits': '81'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '630', 'source': 'hh-0_-3-urban-landlord', 'target': 'hh-0_-3-urban-middle_peasant', 'targetHex': '0,-3', 'targetMode': 'handicraft_workshop', 'population': '1', 'moneyMilli': '92', 'moneyByCurrency': '{copper=92}', 'debtMilli': '34', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '630', 'source': 'hh-0_-3-urban-middle_peasant', 'target': 'hh-0_-3-urban-landlord', 'targetHex': '0,-3', 'targetMode': 'merchant', 'population': '1', 'moneyMilli': '35', 'moneyByCurrency': '{copper=35}', 'debtMilli': '65', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '630', 'source': 'hh-0_-3-urban-rich_peasant', 'target': 'hh-0_-3-urban-middle_peasant', 'targetHex': '0,-3', 'targetMode': 'handicraft_workshop', 'population': '1', 'moneyMilli': '314', 'moneyByCurrency': '{copper=314}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '660', 'source': 'hh-0_-3-urban-landlord', 'target': 'hh-0_-3-urban-poor_peasant', 'targetHex': '0,-3', 'targetMode': 'handicraft_workshop', 'population': '1', 'moneyMilli': '87', 'moneyByCurrency': '{copper=87}', 'debtMilli': '35', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '660', 'source': 'hh-0_-3-urban-middle_peasant', 'target': 'hh-0_-3-urban-landlord', 'targetHex': '0,-3', 'targetMode': 'merchant', 'population': '1', 'moneyMilli': '38', 'moneyByCurrency': '{copper=38}', 'debtMilli': '65', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '660', 'source': 'hh-0_-3-urban-rich_peasant', 'target': 'hh-0_-3-urban-poor_peasant', 'targetHex': '0,-3', 'targetMode': 'handicraft_workshop', 'population': '1', 'moneyMilli': '314', 'moneyByCurrency': '{copper=314}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_APPLIED {'event': 'MIGRATION_APPLIED', 'originKind': 'tick', 'day': '630', 'moves': '3', 'rowsBefore': '342', 'rowsAfter': '342', 'transfersToday': '47'}
MIGRATION_APPLIED {'event': 'MIGRATION_APPLIED', 'originKind': 'tick', 'day': '660', 'moves': '3', 'rowsBefore': '342', 'rowsAfter': '342', 'transfersToday': '39'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_-3-urban-landlord', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '-342', 'toYieldPerLaborScaled': '2083', 'bargainingPowerPerMille': '0', 'inventoryMilli': '1016', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_-3-urban-middle_peasant', 'fromPositionId': 'handicraft-workshop-artisan', 'fromModeId': 'handicraft_workshop', 'toPositionId': 'merchant-porter', 'toModeId': 'merchant', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '0', 'toYieldPerLaborScaled': '7', 'bargainingPowerPerMille': '897', 'inventoryMilli': '3895', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_-3-urban-rich_peasant', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '-19', 'toYieldPerLaborScaled': '2083', 'bargainingPowerPerMille': '0', 'inventoryMilli': '5024', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_-3-urban-landlord', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '-321', 'toYieldPerLaborScaled': '2083', 'bargainingPowerPerMille': '0', 'inventoryMilli': '959', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_-3-urban-middle_peasant', 'fromPositionId': 'handicraft-workshop-artisan', 'fromModeId': 'handicraft_workshop', 'toPositionId': 'merchant-porter', 'toModeId': 'merchant', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '0', 'toYieldPerLaborScaled': '7', 'bargainingPowerPerMille': '901', 'inventoryMilli': '4266', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_-3-urban-rich_peasant', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_-3', 'fromYieldPerLaborScaled': '-20', 'toYieldPerLaborScaled': '2083', 'bargainingPowerPerMille': '0', 'inventoryMilli': '4710', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
MODE_MIGRATION_BRIDGED {'event': 'MODE_MIGRATION_BRIDGED', 'originKind': 'tick', 'day': '630', 'mapId': 'three-powers', 'transfers': '3', 'migratedPopulation': '3', 'createdTargets': '0', 'socialPopulationBefore': '4213', 'socialPopulationAfter': '4213', 'economyPopulationAfter': '4213'}
MODE_MIGRATION_BRIDGED {'event': 'MODE_MIGRATION_BRIDGED', 'originKind': 'tick', 'day': '660', 'mapId': 'three-powers', 'transfers': '3', 'migratedPopulation': '3', 'createdTargets': '0', 'socialPopulationBefore': '4127', 'socialPopulationAfter': '4127', 'economyPopulationAfter': '4127'}
```

## HAUL_SERVICE_*（逐轮对账）

| day | producedMilli | deliveredMilli | expiredMilli | trades | providers |
|---|---|---|---|---|---|
| 603 | 0 | 0 | 0 | 0 | 74 |
| 605 | 0 | 0 | 0 | 0 | 74 |
| 608 | 0 | 0 | 0 | 0 | 74 |
| 610 | 0 | 0 | 0 | 0 | 74 |
| 613 | 0 | 0 | 0 | 0 | 74 |
| 615 | 0 | 0 | 0 | 0 | 74 |
| 618 | 0 | 0 | 0 | 0 | 74 |
| 620 | 0 | 0 | 0 | 0 | 74 |
| 623 | 0 | 0 | 0 | 0 | 74 |
| 625 | 0 | 0 | 0 | 0 | 74 |
| 628 | 0 | 0 | 0 | 0 | 74 |
| 630 | 7760 | 5359 | 1 | 3 | 74 |
| 633 | 0 | 0 | 0 | 0 | 74 |
| 635 | 0 | 0 | 0 | 0 | 74 |
| 638 | 0 | 0 | 0 | 0 | 74 |
| 640 | 0 | 0 | 0 | 0 | 74 |
| 643 | 0 | 0 | 0 | 0 | 74 |
| 645 | 0 | 0 | 0 | 0 | 74 |
| 648 | 0 | 0 | 0 | 0 | 74 |
| 650 | 0 | 0 | 0 | 0 | 74 |
| 653 | 0 | 0 | 0 | 0 | 74 |
| 655 | 0 | 0 | 0 | 0 | 74 |
| 658 | 0 | 0 | 0 | 0 | 74 |
| 660 | 0 | 0 | 0 | 0 | 74 |
