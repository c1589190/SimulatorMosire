package io.mosire.simos.culture.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureLog;
import io.mosire.simos.culture.CultureLogSource;
import io.mosire.simos.culture.change.CultureChangeSet;
import io.mosire.simos.culture.contract.CultureId;
import io.mosire.simos.culture.contract.CultureTag;
import io.mosire.simos.culture.contract.CustomTag;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.culture.contract.ReligionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@code culture.SetHouseholdCulture} 命令的处理器（D-031 spec §6.2）。
 *
 * <pre>{@code
 * {"householdId":"h-0001","cultureId":"latium","religionId":"roman","customs":["patrilineal"]}
 * }</pre>
 *
 * <p>★ <b>幂等键 = {@code householdId}</b>（spec §6.2）：设置/覆盖该家户的文化标签；内容逐值相同 ⇒ {@code
 * CultureChangeSet.between} 得全 {@code Unchanged}（不落实质变化、不报错）。{@code customs} 可缺（缺省 = 无习俗）。
 *
 * <p>★ <b>本轮不生成传播/同化行为</b>（spec §2 非目标）：本 handler 只把标签写进状态，不通知任何消费者、不动人口、不动经济。
 *
 * <p>★ 拒绝 = INFO 具名理由；契约/切片装配故障 = {@code IllegalStateException}（不降级）。
 */
public final class SetHouseholdCultureHandler implements CommandHandler {

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "culture.SetHouseholdCulture";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    CultureData base = CultureSnapshots.of(state);
    String householdForLog = null;
    try {
      JsonNode payload = CulturePayloads.parse(payloadJson);
      HouseholdId household =
          HouseholdId.parse(CulturePayloads.requireText(payload, "householdId"));
      householdForLog = household.value();
      CultureId culture = CultureId.parse(CulturePayloads.requireText(payload, "cultureId"));
      ReligionId religion = ReligionId.parse(CulturePayloads.requireText(payload, "religionId"));
      Set<CustomTag> customs = new LinkedHashSet<>();
      for (String custom : CulturePayloads.optionalTextList(payload, "customs")) {
        customs.add(CustomTag.parse(custom));
      }
      HouseholdCulture value =
          new HouseholdCulture(household, new CultureTag(culture, religion, customs));
      CultureData target = base.withHouseholdCulture(value);
      CultureChangeSet changeSet = CultureChangeSet.between(base, target);
      EventLog.channel(CultureLog.command())
          .info(
              LogEvent.of(
                  "CULTURE_HOUSEHOLD_CULTURE_SET",
                  CultureLogSource.CULTURE_COMMAND,
                  "household",
                  household.value(),
                  "culture",
                  culture.value(),
                  "religion",
                  religion.value(),
                  "customs",
                  customs.size(),
                  "householdCultures",
                  target.householdCultures().size(),
                  "changed",
                  !changeSet.isEmpty()));
      return new HandlerOutcome.Applied(changeSet);
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "household", householdForLog);
    }
  }

  /** 具名拒绝的唯一发射点（被拒绝一律 INFO）：{@code reason} + 关键 id（取不到 {@code -}）；载荷原文不进日志。 */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    CulturePayloads.logRejected(
        CultureLog.command(),
        CultureLogSource.CULTURE_COMMAND,
        "CULTURE_SET_HOUSEHOLD_CULTURE_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
