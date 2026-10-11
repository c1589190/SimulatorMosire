# 跑商与迁移（本期 = day 601..689（89 个已完结日））

## MERCHANT_HAUL_RUN（逐笔承运，TRACE）

| day | household | pureMerchant | commodity | from | to | quantityMilli | serviceMilli | waivedFreightMilli |
|---|---|---|---|---|---|---|---|---|
| 603 | hh-0_0-urban-landlord | true | cloth | 0_0 | -1_1 | 255 | 516 | 0 |
| 603 | hh-0_0-urban-landlord | true | grain | 0_0 | -1_1 | 2600 | 2626 | 0 |
| 633 | hh-0_0-urban-landlord | true | cloth | 0_0 | -1_1 | 1584 | 3200 | 0 |
| 633 | hh-0_0-urban-landlord | true | grain | 0_0 | -1_1 | 400 | 404 | 0 |
| 663 | hh-0_0-urban-landlord | true | cloth | 0_0 | -1_1 | 1677 | 3388 | 0 |
| 663 | hh-0_0-urban-landlord | true | grain | 0_0 | -1_1 | 200 | 202 | 0 |

## MIGRATION_MOVE / MIGRATION_PLAN / PRIMARY_MODE_*

```
MIGRATION_PLAN {'event': 'MIGRATION_PLAN', 'originKind': 'tick', 'day': '630', 'moves': '2', 'organizations': '68', 'modeHexProfits': '50'}
MIGRATION_PLAN {'event': 'MIGRATION_PLAN', 'originKind': 'tick', 'day': '660', 'moves': '2', 'organizations': '68', 'modeHexProfits': '50'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '630', 'source': 'hh-0_0-urban-landlord', 'target': 'hh--1_1-rural-rich_peasant', 'targetHex': '-1,1', 'targetMode': 'family_farm', 'population': '1', 'moneyMilli': '17', 'moneyByCurrency': '{silver=17}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '630', 'source': 'hh-0_0-urban-rich_peasant', 'target': 'hh-0_0-rural-landlord', 'targetHex': '0,0', 'targetMode': 'tenancy_fixed_kind', 'population': '1', 'moneyMilli': '11', 'moneyByCurrency': '{silver=11}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '660', 'source': 'hh-0_0-urban-landlord', 'target': 'hh--1_1-rural-rich_peasant', 'targetHex': '-1,1', 'targetMode': 'family_farm', 'population': '1', 'moneyMilli': '17', 'moneyByCurrency': '{silver=17}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_MOVE {'event': 'MIGRATION_MOVE', 'originKind': 'tick', 'day': '660', 'source': 'hh-0_0-urban-rich_peasant', 'target': 'hh-0_0-rural-landlord', 'targetHex': '0,0', 'targetMode': 'tenancy_fixed_kind', 'population': '1', 'moneyMilli': '11', 'moneyByCurrency': '{silver=11}', 'debtMilli': '0', 'speedPerMille': '10', 'reason': 'PROFIT_WEIGHTED'}
MIGRATION_APPLIED {'event': 'MIGRATION_APPLIED', 'originKind': 'tick', 'day': '630', 'moves': '2', 'rowsBefore': '178', 'rowsAfter': '178', 'transfersToday': '10'}
MIGRATION_APPLIED {'event': 'MIGRATION_APPLIED', 'originKind': 'tick', 'day': '660', 'moves': '2', 'rowsBefore': '178', 'rowsAfter': '178', 'transfersToday': '11'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_0-urban-landlord', 'fromPositionId': 'merchant-principal', 'fromModeId': 'merchant', 'toPositionId': 'family-farm-family-farmer', 'toModeId': 'family_farm', 'toHex': '-1_0', 'fromYieldPerLaborScaled': '-150', 'toYieldPerLaborScaled': '0', 'bargainingPowerPerMille': '0', 'inventoryMilli': '1485', 'reason': 'RANKED_HIGHEST'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_0-urban-rich_peasant', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_0', 'fromYieldPerLaborScaled': '-6', 'toYieldPerLaborScaled': '833', 'bargainingPowerPerMille': '0', 'inventoryMilli': '1191', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_0-urban-landlord', 'fromPositionId': 'merchant-principal', 'fromModeId': 'merchant', 'toPositionId': 'family-farm-family-farmer', 'toModeId': 'family_farm', 'toHex': '-1_0', 'fromYieldPerLaborScaled': '-153', 'toYieldPerLaborScaled': '0', 'bargainingPowerPerMille': '0', 'inventoryMilli': '1471', 'reason': 'RANKED_HIGHEST'}
PRIMARY_MODE_CHANGED {'event': 'PRIMARY_MODE_CHANGED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_0-urban-rich_peasant', 'fromPositionId': 'merchant-self-employed', 'fromModeId': 'merchant', 'toPositionId': 'handicraft-workshop-artisan', 'toModeId': 'handicraft_workshop', 'toHex': '0_0', 'fromYieldPerLaborScaled': '-6', 'toYieldPerLaborScaled': '833', 'bargainingPowerPerMille': '0', 'inventoryMilli': '1180', 'reason': 'RANKED_HIGHEST', 'rowReason': 'WAGE_SHARE_ESTIMATE,WAGE_POSITION'}
PRIMARY_MODE_RANKING_EXCLUDED {'event': 'PRIMARY_MODE_RANKING_EXCLUDED', 'originKind': 'tick', 'day': '630', 'household': 'hh-0_2-urban-landlord', 'modeId': 'merchant', 'hex': '0_2', 'reason': 'no-bargaining-power'}
PRIMARY_MODE_RANKING_EXCLUDED {'event': 'PRIMARY_MODE_RANKING_EXCLUDED', 'originKind': 'tick', 'day': '660', 'household': 'hh-0_2-urban-landlord', 'modeId': 'merchant', 'hex': '0_2', 'reason': 'no-bargaining-power'}
MODE_MIGRATION_BRIDGED {'event': 'MODE_MIGRATION_BRIDGED', 'originKind': 'tick', 'day': '630', 'mapId': 'small-world', 'transfers': '2', 'migratedPopulation': '2', 'createdTargets': '0', 'socialPopulationBefore': '3237', 'socialPopulationAfter': '3237', 'economyPopulationAfter': '3237'}
MODE_MIGRATION_BRIDGED {'event': 'MODE_MIGRATION_BRIDGED', 'originKind': 'tick', 'day': '660', 'mapId': 'small-world', 'transfers': '2', 'migratedPopulation': '2', 'createdTargets': '0', 'socialPopulationBefore': '3177', 'socialPopulationAfter': '3177', 'economyPopulationAfter': '3177'}
```

## HAUL_SERVICE_*（逐轮对账）

| day | producedMilli | deliveredMilli | expiredMilli | trades | providers |
|---|---|---|---|---|---|
| 603 | 0 | 3142 | 0 | 2 | 38 |
| 605 | 0 | 0 | 0 | 0 | 38 |
| 608 | 0 | 0 | 0 | 0 | 38 |
| 610 | 0 | 0 | 0 | 0 | 38 |
| 613 | 0 | 0 | 0 | 0 | 38 |
| 615 | 0 | 0 | 0 | 0 | 38 |
| 618 | 0 | 0 | 0 | 0 | 38 |
| 620 | 0 | 0 | 0 | 0 | 38 |
| 623 | 0 | 0 | 0 | 0 | 38 |
| 625 | 0 | 0 | 0 | 0 | 38 |
| 628 | 0 | 0 | 0 | 0 | 38 |
| 630 | 0 | 0 | 0 | 0 | 38 |
| 633 | 0 | 3604 | 0 | 2 | 38 |
| 635 | 0 | 0 | 0 | 0 | 38 |
| 638 | 0 | 0 | 0 | 0 | 38 |
| 640 | 0 | 0 | 0 | 0 | 38 |
| 643 | 0 | 0 | 0 | 0 | 38 |
| 645 | 0 | 0 | 0 | 0 | 38 |
| 648 | 0 | 0 | 0 | 0 | 38 |
| 650 | 0 | 0 | 0 | 0 | 38 |
| 653 | 0 | 0 | 0 | 0 | 38 |
| 655 | 0 | 0 | 0 | 0 | 38 |
| 658 | 0 | 0 | 0 | 0 | 38 |
| 660 | 0 | 0 | 0 | 0 | 38 |
| 663 | 0 | 3590 | 0 | 2 | 38 |
| 665 | 0 | 0 | 0 | 0 | 38 |
| 668 | 0 | 0 | 0 | 0 | 38 |
| 670 | 0 | 0 | 0 | 0 | 38 |
| 673 | 0 | 0 | 0 | 0 | 38 |
| 675 | 0 | 0 | 0 | 0 | 38 |
| 678 | 0 | 0 | 0 | 0 | 38 |
| 680 | 0 | 0 | 0 | 0 | 38 |
| 683 | 0 | 0 | 0 | 0 | 38 |
| 685 | 0 | 0 | 0 | 0 | 38 |
| 688 | 0 | 0 | 0 | 0 | 38 |
| 690 | 0 | 0 | 0 | 0 | 38 |
