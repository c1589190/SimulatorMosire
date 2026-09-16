### Task 13: `MapResolver` —— `map:` 寻址

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/resolve/MapResolver.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/resolve/MapResolverTest.java`

- [ ] **Step 1: 写 `MapResolver`**

实现 `simos-util` 的 `Resolver` SPI：`namespace()` 返回 `"map"`；
`resolve(Address, ResolveContext)` 认 `map:<mapId>`、`map:<mapId>:hex:<q>_<r>`、
`map:<mapId>:region:<regionId>`、`map:<mapId>:city:<cityId>` 四类地址。

★ **`map:<mapId>` 的第一段是什么** —— M1 的 `Address` 有四段（`Namespace`/`Entity`/`Index`/`Property`），
**按 M1 spec §3.5 判定**（执行者读 M1 spec 的 `AddressSegment` 判定规则），
**不要凭直觉**。判定结果写进报告。

- [ ] **Step 2: 写用例**

```
MapResolverTest
  - resolvesHexByAddress                : map:m1:hex:0_0 → 命中 HexCoord(0,0)
  - resolvesRegionByAddress
  - resolvesCityByAddress
  - resolvesMapItself
  - ★ unknownHexGivesEmptyNotException  : 不存在的坐标 → 空候选，**不抛**
  - ★ wrongNamespaceIsRejected          : map:… 之外的地址不由本解析器认领
  - ★ regionOfHexUsesTheIndex           : 见 Step 3（钉 O(1) 而非线性扫描）
  - malformedHexIndexIsRejected         : map:m1:hex:abc → 明确的错
  - resolverDoesNotDoIO                 : 源码断言：无 java.io / java.nio.file
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `regionOf` 的查询改成遍历全部 regions 找 | **红** | ★ `regionOfHexUsesTheIndex` 有判别力（L5 的守卫） |
| 未知坐标改成抛异常 | **红** | `unknownHexGivesEmptyNotException` 有判别力 |
| `namespace()` 改成 `"mapx"` | **红** | 注册与解析的接缝 |
| 非法 index 静默返回空 | **红** | `malformedHexIndexIsRejected` 不是装饰 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): MapResolver——map: 命名空间寻址`。

---

