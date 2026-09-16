### Task 5: 外挂时态属性（`info` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InfoEntry.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InfoSystem.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/info/InMemoryInfoSystemTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`SubjectId`（Task 3）、`SimosTimestamp` / `TimeRange`（Task 4）
- Produces: `InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note)`；`InfoSystem.get(Address, String key, SimosTimestamp) → Optional<InfoEntry>`、`InfoSystem put(Address, InfoEntry) → InfoSystem`；`InMemoryInfoSystem.empty()`；`InMemoryInfoSystem.put(...) → InMemoryInfoSystem`

**对总纲的偏离**（spec §十-D4，本节落地）：

- `subject` 用 **`Address`**（总纲 §4.6 原样）——Info 能挂到 `map` 命名空间本身、`map:Map1`、甚至 `map:Map1:terra.Grass:height` 上，这些锚点没有 `SubjectId`；`SubjectId` 只出现在 `InfoEntry.source`（这条信息的来源实体）。
- `get` **增 `key` 参数**（一个主体可同时挂多个 key）。
- `put` **返回新实例**（总纲草案是 `void put`）——否则 `SimulationState` 的 `equals()` 往返断言无从谈起（铁律 5）。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.info;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec §十-D4：按 key + 时刻取值、有效期左闭右开、`put` 返回新实例。 */
class InMemoryInfoSystemTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");

  @Test
  void readsByKeyAndMoment() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "河口渡口", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("icon", "anchor", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(5))).map(InfoEntry::value).contains("河口渡口");
    assertThat(info.get(HEX, "icon", SimosTimestamp.of(5))).map(InfoEntry::value).contains("anchor");
    assertThat(info.get(HEX, "unknown", SimosTimestamp.of(5))).isEmpty();
    assertThat(info.get(Address.parse("map:Map1"), "alias", SimosTimestamp.of(5))).isEmpty();
  }

  @Test
  void validityWindowIsHalfOpen() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(
                HEX,
                entry(
                    "alias",
                    "旧名",
                    new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(10)))))
            .put(HEX, entry("alias", "新名", TimeRange.since(SimosTimestamp.of(10))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(9))).map(InfoEntry::value).contains("旧名");
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(10)))
        .map(InfoEntry::value)
        .contains("新名"); // 切换点归新条目
  }

  @Test
  void theLastInsertedOverlappingEntryWins() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "先写的", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("alias", "后写的", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(1)))
        .map(InfoEntry::value)
        .contains("后写的");
  }

  @Test
  void putReturnsANewInstanceAndLeavesTheOriginalUntouched() {
    InMemoryInfoSystem base = InMemoryInfoSystem.empty();
    InMemoryInfoSystem next = base.put(HEX, entry("alias", "甲", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(base.get(HEX, "alias", SimosTimestamp.of(0))).isEmpty();
    assertThat(next.get(HEX, "alias", SimosTimestamp.of(0))).isPresent();
  }

  private static InfoEntry entry(String key, Object value, TimeRange valid) {
    return new InfoEntry(key, value, valid, new SubjectId("map.hex", "h-0001"), Optional.empty());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`
Expected: 编译失败——`cannot find symbol: class InMemoryInfoSystem`

- [ ] **Step 3: 实现三个类型**

`InfoEntry.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.TimeRange;
import java.util.Objects;
import java.util.Optional;

/**
 * 一条外挂信息：`key` + 结构化值 + 有效期 + 来源（总纲 §4.6）。
 *
 * <p>**Info ≠ 领域字段**：凡影响领域计算的（`TerraType.height` 影响河流生成）都是领域字段，
 * 不许走这条路；否则 Info 会退化成绕过领域模型的 JSON 垃圾桶（总纲 §4.6）。
 *
 * <p>`value` 是结构化 `Object`，不是预格式化字符串。
 */
public record InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note) {

  public InfoEntry {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("InfoEntry.key 不得为空白");
    }
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(valid, "valid");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(note, "note");
  }
}
```

`InfoSystem.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Optional;

/**
 * 外挂时态属性系统（总纲 §4.6）：**不挂在任何 Java 对象上**，是"地址 → 信息"的外挂关系。
 *
 * <p>实现必须不可变——{@link #put} 返回新实例（spec §十-D4），否则 `equals()` 往返断言无从谈起。
 */
public interface InfoSystem {

  /** 取 `subject` 上 `key` 在时刻 `at` 有效的那条；同一 key 重叠时**插入序最后者胜**。 */
  Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at);

  /** 追加一条（新开有效期的写法就是"改值"）；返回新实例，原实例不变。 */
  InfoSystem put(Address subject, InfoEntry entry);
}
```

`InMemoryInfoSystem.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** {@link InfoSystem} 的内存实现：写时复制，读不修改任何状态。 */
public final class InMemoryInfoSystem implements InfoSystem {

  private final Map<Address, List<InfoEntry>> bySubject;

  private InMemoryInfoSystem(Map<Address, List<InfoEntry>> bySubject) {
    this.bySubject = bySubject;
  }

  public static InMemoryInfoSystem empty() {
    return new InMemoryInfoSystem(Map.of());
  }

  @Override
  public Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at) {
    InfoEntry found = null;
    for (InfoEntry entry : bySubject.getOrDefault(subject, List.of())) {
      if (entry.key().equals(key) && entry.valid().contains(at)) {
        found = entry;
      }
    }
    return Optional.ofNullable(found);
  }

  @Override
  public InMemoryInfoSystem put(Address subject, InfoEntry entry) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(entry, "entry");
    Map<Address, List<InfoEntry>> next = new LinkedHashMap<>(bySubject);
    List<InfoEntry> entries = new ArrayList<>(next.getOrDefault(subject, List.of()));
    entries.add(entry);
    next.put(subject, List.copyOf(entries));
    return new InMemoryInfoSystem(Map.copyOf(next));
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/info/ simos-util/src/test/java/io/mosire/simos/util/info/
git diff --cached --stat
git commit -m "feat(util): InfoEntry/InfoSystem/InMemoryInfoSystem（M1 Task 5）"
```

---

