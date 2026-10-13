package io.mosire.simos.army.spi;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.Incident;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>"两军相遇"判据</b>（2026-10-13 急停协议，约束设计书 §4.4）：给定<b>某一天</b>各军的位置，报出当天全部"相遇"的军对。
 *
 * <p>用户原话（职责归属的由来）：「我首先说的是<b>两支军队相遇</b>，所以说<b>监控职能甚至应该包给 Army 模块</b>，<b>触发器本身只管是否被触发，
 * 不管怎么触发怎么监控的</b>」⇒ 判据住 Army，core/util 不认识 {@link #KIND} 这个字面量（不变量 I5）。
 *
 * <p>★ <b>它是纯函数</b>：只吃 {@code (day, armies, positions)}，不读状态、不写状态、不碰提案（不变量 I1）。可逐日自足地调用 ⇒
 * 分段等价不破（不变量 I2）：把 {@code advance(0, 10)} 切成十次单日推进，每一天算出的军对与整段推进逐字相同（无跨日记忆）。
 *
 * <p>★★ <b>为什么必须由调用方"逐日"喂进来</b>：只在推进终点算一次会<b>漏掉两军擦肩而过</b>（第 5 天相遇、第 10 天分开 ⇒
 * 终点看过去"从没相遇"）。<b>逐日</b>这件事是 {@link ArmyTimeParticipant} 的责任，本类只负责"这一天算不算相遇"。
 *
 * <p>★ <b>判据</b>：两军位置<b>同格或相邻</b>（六角距离 ≤ 1）<b>且</b>至少一方被 GM 要求监控（{@link Army#contactWatch()}，口径 2 =
 * (a) 的最小形态：{@code false} = 没要求过 ⇒ 相遇也不报，P4）。一方启用即算 —— "这两军相遇时告诉我"是 GM 的语义。
 *
 * <p>★ <b>决定论</b>：军对按 id <b>字典序</b>遍历，故同一天的 {@link Incident} 清单次序是内容的纯函数（{@code
 * StopReport.reasons()} 的次序因此可复现，与"参与者按 namespace 字典序"同一条纪律）。
 *
 * <p>★ <b>代价（账本已记，不得当成免费）</b>：本类每个军对每天做一次 {@link HexCoord#distanceTo}；而"每天各军在哪"是 {@link
 * ArmyTimeParticipant} 用 {@code UnitMoves.evaluate} 现算的——那是与 Unit 侧<b>重复计算</b>（两阶段模型里参与者之间看不到彼此的提案，
 * C25）。
 */
public final class ArmyContacts {

  /** 本模块自报的突发事件种类（★ 不变量 I5：这个字符串只住 Army —— util/core 一个具体 kind 字面量都没有）。 */
  public static final String KIND = "army.contact";

  /** 上报时的参与者身份（= {@link ArmyTimeParticipant#NAMESPACE}）。 */
  public static final String NAMESPACE = ArmyTimeParticipant.NAMESPACE;

  /** "同格或相邻"里的界：同格 = 0、相邻 = 1（{@link HexCoord#distanceTo} 的六角距离）。 */
  private static final int MAX_CONTACT_DISTANCE = 1;

  private ArmyContacts() {}

  /**
   * 判据：当天的相遇清单。
   *
   * @param day 世界日（★ 每一条 {@link Incident#tick()} <b>就是</b>它：core 侧机械核对"事件日 == 当天推进日"，不符即拒）
   * @param armies 军队表（取 {@link Army#contactWatch()} 判"被没被要求监控"）
   * @param positions 各军当天的位置（缺键 = 当天算不出位置 ⇒ 该军不参与判据）
   * @return 当天的突发事件（按军对 id 字典序；空 = 没相遇）
   */
  public static List<Incident> scan(
      long day, Map<ArmyId, Army> armies, Map<ArmyId, HexCoord> positions) {
    Objects.requireNonNull(armies, "armies");
    Objects.requireNonNull(positions, "positions");
    List<ArmyId> ids = new ArrayList<>(positions.keySet());
    ids.sort(Comparator.comparing(ArmyId::value));
    List<Incident> incidents = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      for (int j = i + 1; j < ids.size(); j++) {
        ArmyId aId = ids.get(i);
        ArmyId bId = ids.get(j);
        Army a = armies.get(aId);
        Army b = armies.get(bId);
        if (a == null || b == null) {
          continue; // 位置表里出现了军队表里没有的 id：不造事件（装配异常由调用方与读侧负责）
        }
        if (!a.contactWatch() && !b.contactWatch()) {
          continue; // ★ P4：没被要求监控的军对相遇 ⇒ 不报（因此也不停推进）
        }
        HexCoord aHex = positions.get(aId);
        HexCoord bHex = positions.get(bId);
        if (aHex.distanceTo(bHex) > MAX_CONTACT_DISTANCE) {
          continue;
        }
        HexCoord meeting = meetingHex(a, aHex, b, bHex);
        String hexForLog = meeting.q() + "_" + meeting.r();
        String anchor = anchor(day, aId, bId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("armies", List.of(aId.value(), bId.value()));
        payload.put("hex", hexForLog);
        EventLog.channel(ArmyLog.contact())
            .info(
                LogEvent.of(
                    "ARMY_CONTACT",
                    ArmyLogSource.ARMY_CONTACT,
                    "day",
                    day,
                    "a",
                    aId.value(),
                    "b",
                    bId.value(),
                    "hex",
                    hexForLog,
                    "aWatch",
                    a.contactWatch(),
                    "bWatch",
                    b.contactWatch(),
                    "logRef",
                    anchor));
        incidents.add(new Incident(KIND, day, NAMESPACE, payload, List.of(anchor)));
      }
    }
    return incidents;
  }

  /**
   * 供 GM / 调试 grep 的<b>日志锚</b>（模块自报，util/core 不解释它）：与 {@link #scan} 里那条 INFO 行的字段可逐字对上 —— {@code
   * ARMY_CONTACT day=5 a=a1 b=a2} 在日志里搜得到，故它是**真的**锚，不是装饰。
   */
  public static String anchor(long day, ArmyId a, ArmyId b) {
    return "ARMY_CONTACT day=" + day + " a=" + a.value() + " b=" + b.value();
  }

  /**
   * 相遇那一格的<b>单一拼写点</b>：同格 ⇒ 就是那一格；相邻 ⇒ <b>被监控那一军</b>所在的格（两军都被监控 ⇒ id 序在前者的格，即 {@code aHex}）。
   *
   * <p>★ 相邻时"取谁的格"是有意选的：payload 里只有一个 {@code hex}，而 GM 关心的是<b>他要求盯着的那支军队</b>旁边出现了谁。
   */
  private static HexCoord meetingHex(Army a, HexCoord aHex, Army b, HexCoord bHex) {
    if (aHex.equals(bHex)) {
      return aHex;
    }
    if (a.contactWatch() && !b.contactWatch()) {
      return aHex;
    }
    if (!a.contactWatch() && b.contactWatch()) {
      return bHex;
    }
    return aHex; // 两军都被监控：a 是 id 字典序在前的那一个 ⇒ 决定论
  }
}
