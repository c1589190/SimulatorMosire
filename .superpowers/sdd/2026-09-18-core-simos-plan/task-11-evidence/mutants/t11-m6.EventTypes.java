package io.mosire.simos.core.observe;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 事件类型清单（**冻结，C20**；spec §7.1）。
 *
 * <p>★ **八个，一个不多一个不少**。其中 {@code simos.revision.created} **被有意排除**——它与 {@code revisions} 表
 * **完全重复**（同一事实两个来源就是漂移风险，**正是本项目最贵教训的形态**：GSimulator 的 {@code MapDiff} 手工对着 {@code MapData}
 * 维护，四个字段漂移出去而无人提醒）。总纲 §8.2 的清单里**有**它，M4 spec §7.1 把它删掉 ⇒ **以 M4 spec 为准**（C20）。
 *
 * <p>★ **{@code simos.timeline.conflict} 是 C14 的落点**：读写集冲突在别处无可记录，这条事件是它唯一的痕迹。
 *
 * <p>★ 本类是**常量的家，不是枚举**：事件类型要落进 {@code events.type} 列（TEXT），且 Task 12 的参与者 与 Task 13
 * 的装配都要按字符串比对；枚举会强迫每个使用点 {@code .name()}，而列里存的必须是**线上值**。
 */
public final class EventTypes {

  /** 命令到达总线。★ 本事件的 {@code payload} 按总纲 §8.1 承载命令侧固定字段（含参数摘要）——见类注。 */
  public static final String COMMAND_RECEIVED = "simos.command.received";

  /** 命令被拒（handler 拒绝 / 类型未注册 / 分支不存在）。 */
  public static final String COMMAND_REJECTED = "simos.command.rejected";

  /** 乐观并发冲突（C17）：期望 revision 与真实 head 不符。 */
  public static final String COMMAND_CONFLICTED = "simos.command.conflicted";

  /** 命令提交成功：新 revision 已落盘。 */
  public static final String COMMAND_COMMITTED = "simos.command.committed";

  /** 时间推进开始（Task 12 的两阶段管线起点）。 */
  public static final String TIME_ADVANCE_STARTED = "simos.time.advance.started";

  /** 时间推进结束（Task 12 的两阶段管线终点）。 */
  public static final String TIME_ADVANCE_FINISHED = "simos.time.advance.finished";

  /** 某模块的一次提案（每模块每次推进一条）。 */
  public static final String MODULE_PROPOSAL = "simos.module.proposal";

  /** 读写集冲突（C14）：{@code payload} 形如 {@code {kind, namespaces, addresses}}。 */
  public static final String TIMELINE_CONFLICT = "simos.timeline.conflict";

  /**
   * 冻结清单，**顺序即 spec §7.1 的书写顺序**。
   *
   * <p>★ 它存在的理由：让"八个、且不含 {@code simos.revision.created}"这件事**可被一条断言直接钉住** （{@code
   * EventTypesTest}），而不是散在八个常量的字面量里逐个数。**列表的消费者是用例**——这不是死代码。
   */
  public static final List<String> ALL =
      List.of(
          COMMAND_RECEIVED,
          COMMAND_REJECTED,
          COMMAND_CONFLICTED,
          COMMAND_COMMITTED,
          TIME_ADVANCE_STARTED,
          TIME_ADVANCE_FINISHED,
          MODULE_PROPOSAL,
          TIMELINE_CONFLICT,
          "simos.revision.created");

  /** 与 {@link #ALL} 同集合的查找视图（{@code contains} 是 O(1)）。 */
  private static final Set<String> ALL_SET = Set.copyOf(ALL);

  private EventTypes() {}

  /** 是否属于冻结清单。★ 未知类型**不是错误**（将来可扩展），故给的是布尔而不是抛异常。 */
  public static boolean isKnown(String type) {
    return ALL_SET.contains(Objects.requireNonNull(type, "type"));
  }
}
