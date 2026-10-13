package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code army.SetArmyContactWatch} 命令的处理器（2026-10-13 急停协议，口径 2 = (a) 的最小形态）： <b>GM
 * 注册"这两军相遇时报警"</b>的窄写入口（工具 {@code simos.army.contactWatch}）。
 *
 * <pre>{@code
 * {"armyId":"a1","contactWatch":true}
 * }</pre>
 *
 * <p>★★ <b>为什么这条注册住 Army、且落在军队记录里</b>（约束设计书 §4.4 / 用户裁定 2 =
 * A）：用户原话「<b>这些条件之类都是可自定义、具体条件交给对应模块具体维护</b>」 ——"什么时候要报警"是 Army 自己的条件，故它是 {@link
 * Army#contactWatch()} 这个<b>世界状态</b>字段（随 revision 落盘 ⇒ 重启后 GM 的注册不丢）， 而不是进程内的配置。判据在 {@link
 * ArmyContacts}，上报在 {@link ArmyTimeParticipant}。
 *
 * <p>★ <b>语义</b>：{@code armyId} 必须已存在（不存在 ⇒ 具名拒，不做"先建后设"的隐式创建）；{@code contactWatch} 必须显式给布尔值
 * （缺键/非布尔 ⇒ 具名拒）——**没有缺省**是有意的：这条命令唯一的效果就是那个位，猜一个缺省等于让"少写一个字段"变成一项无声裁决。
 *
 * <p>★ <b>幂等</b>：重复设同一个值 = 一条不变变更集（命令仍成功、仍落 revision；日志里 {@code changed=false} 分得出"真的改了吗"）。
 *
 * <p>★★ <b>不迁入 {@code DmUsableCommand}</b>（反默认落地）：它改的是<b>GM 的监控注册</b>，不是世界编辑权 —— 决策人不该给自己加监控。 与
 * {@code army.CreateArmy} / {@code army.SetArmyMasterGov} 同在 GM 面。
 */
public final class SetArmyContactWatchHandler implements CommandHandler {

  private static final Logger LOG = ArmyLog.armies();

  /** 命令类型（信封上的 {@code type}）。前缀 {@code army.} 决定变更集施加到 army 切片。 */
  public static final String TYPE = "army.SetArmyContactWatch";

  /** 载荷字段名（与工具 {@code simos.army.contactWatch} 的文档同源）。 */
  public static final String FIELD_ARMY_ID = "armyId";

  /** 载荷字段名：启用位。 */
  public static final String FIELD_CONTACT_WATCH = "contactWatch";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String armyForLog = null;
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      ArmyId id = ArmyId.parse(ArmyPayloads.requireText(payload, FIELD_ARMY_ID));
      armyForLog = id.value();
      boolean contactWatch = requireBoolean(payload, FIELD_CONTACT_WATCH);
      Army existing = snapshot.data().armies().get(id);
      if (existing == null) {
        return rejected("军队不存在: " + id, "army", armyForLog);
      }
      ArmyData next =
          snapshot
              .data()
              .withArmy(
                  new Army(
                      existing.id(),
                      existing.masterGovUnitId(),
                      existing.rootUnit(),
                      existing.name(),
                      // ★ 逐字段重建既有记录：除了本命令要改的那一位，一个字都不许漂（同 SetArmyMasterGov 的纪律）。
                      contactWatch));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ARMY_CONTACT_WATCH_SET",
                  ArmyLogSource.ARMY_REGISTRY,
                  "army",
                  id.value(),
                  "contactWatch",
                  contactWatch,
                  "changed",
                  contactWatch != existing.contactWatch(),
                  "armies",
                  next.armies().size()));
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "army", armyForLog);
    }
  }

  /** 必须显式给布尔值：缺键 / 非布尔 ⇒ 具名拒（见类注"没有缺省"）。 */
  private static boolean requireBoolean(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || !node.isBoolean()) {
      throw new IllegalArgumentException(
          "字段 " + field + " 必须显式给布尔值（true/false）：" + (node == null ? "缺键" : node));
    }
    return node.booleanValue();
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * ArmyPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    ArmyPayloads.logRejected(
        LOG, ArmyLogSource.ARMY_REGISTRY, "ARMY_CONTACT_WATCH_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
