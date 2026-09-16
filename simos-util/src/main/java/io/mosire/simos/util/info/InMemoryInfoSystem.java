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
 * 的逐字段重建断言（铁律 5）依赖它。
 *
 * <p>`bySubject()` 访问器不设防，防线在构造期：逐值 {@code List.copyOf} 再整体 {@code Map.copyOf}——**深**拷贝，否则
 * 调用方的可变列表会穿透进来（浅拷贝下 `clear()` 能改掉本实例的值、连带 `hashCode()` 漂移，spec §十一"集合防御性拷贝"）。
 * 这是**唯一可观测**的防线（删掉它有用例转红）。{@link #put} 里的 {@code List.copyOf} 是不可观测的**纵深防御**：构造期
 * 已深拷贝，删掉它没有用例会转红——留着只为写路径自成一体，别当成承重墙。
 */
public record InMemoryInfoSystem(Map<Address, List<InfoEntry>> bySubject) implements InfoSystem {

  public InMemoryInfoSystem {
    Objects.requireNonNull(bySubject, "bySubject");
    Map<Address, List<InfoEntry>> copy = new LinkedHashMap<>();
    bySubject.forEach((key, entries) -> copy.put(key, List.copyOf(entries)));
    bySubject = Map.copyOf(copy);
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
