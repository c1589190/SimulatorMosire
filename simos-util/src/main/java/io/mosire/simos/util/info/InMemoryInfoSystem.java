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
