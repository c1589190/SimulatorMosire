# 具名业务拒绝（本期 = day 601..689（89 个已完结日）；LOGISTICS_SERVICE_SUPPLY_REJECTED，逐笔 TRACE 原始行）

★ 口径：逐行 `truncatedMilli` 是**每次观察**的值，同一请求可能被多轮/多次试配反复观察
（`MerchantCapacityPool` 类注：Σ 逐行**不可**当真实量）；本表按发货格给的是**行数与原始额**，
权威净额以 `MARKET` 行的 `logisticsDetailQuantities` 为准。

| 发货格 | 行数 | ΣtruncatedMilli(原始) | ΣrequestedMilli(原始) |
|---|---|---|---|
| -2_1 | 2 | 2 | 2 |
| -1_-1 | 93 | 336 | 336 |
| 0_-1 | 22 | 25 | 25 |
| 0_2 | 6 | 875 | 875 |

## 车道明细（发货格 → 收货格 / 商品）Top 25

| from | to | commodity | ΣtruncatedMilli(原始) |
|---|---|---|---|
| 0_2 | 0_0 | tool | 875 |
| -1_-1 | 0_0 | tool | 322 |
| 0_-1 | 0_0 | grain | 22 |
| -1_-1 | 0_0 | grain | 14 |
| 0_-1 | -1_1 | grain | 3 |
| -2_1 | -1_1 | grain | 1 |
| -2_1 | 0_0 | grain | 1 |
