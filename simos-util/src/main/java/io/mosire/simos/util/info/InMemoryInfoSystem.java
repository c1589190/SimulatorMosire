package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link InfoSystem} 的内存实现：写时复制，读不修改任何状态。
 *
 * <p>它是 **record**——值语义由 record 提供（spec §十一：禁止手写 `equals`，`equals` 是往返断言的判据本身）， `SimulationState`
 * 的逐字段重建断言（铁律 5）依赖它。`bySubject()` 访问器返回的是不可变结构：构造期过一遍 {@code Map.copyOf}，本类自身的写路径（{@link
 * #put}）对各主体列表一律 {@code List.copyOf}，外部无从经它改写内部状态。
 */
public record InMemoryInfoSystem(Map<Address, List<InfoEntry>> bySubject) implements InfoSystem {

  public InMemoryInfoSystem {
    bySubject = Map.copyOf(bySubject);
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
    return new InMemoryInfoSystem(next);
  }
}
