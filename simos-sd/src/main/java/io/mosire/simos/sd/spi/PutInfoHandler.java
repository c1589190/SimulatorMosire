package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.PutInfo} 命令的处理器（spec §六，R14）：sd 自有 INFO 覆盖层写路径。
 *
 * <pre>{@code
 * {"address":"map:Map1:region.r1","key":"brief","value":"……","note":"可选"}
 * }</pre>
 *
 * <p>★ **不复用全局 {@code InfoSystem}**（spec §六）：写进 {@code SdChangeSet.info} → 进 revision，可重放、受铁律 5
 * 往返守卫。 **写的是感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有。
 *
 * <p>★ **诚实边界**（spec §六 + M4 裁定 38 同口径）：{@code SdInfoEntry.value} 是**裸 {@code Object}**，结构化值的
 * {@code equals} 往返不满足 ⇒ 判据只覆盖标量值；结构化值列为挂起项。
 *
 * <p>拒绝：地址非法（{@link Address#parse} 抛）；{@code key} 空白；{@code value} 缺失。
 */
public final class PutInfoHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.PutInfo";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      Address address = SdPayloads.requireAddress(payload, "address");
      String key = SdPayloads.requireText(payload, "key");
      Object value = SdPayloads.requireValue(payload, "value");
      Optional<String> note = SdPayloads.optionalText(payload, "note");
      RevisionId at = state.meta().ref().revision();
      SdInfoEntry entry = new SdInfoEntry(key, value, note, at, Optional.empty());
      String mapKey = address.canonical();
      Map<String, List<SdInfoEntry>> next = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(next.getOrDefault(mapKey, List.of()));
      entries.add(entry);
      next.put(mapKey, List.copyOf(entries));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withInfo(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
