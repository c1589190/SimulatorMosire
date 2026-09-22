package io.mosire.simos.app.gm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * GM MCP 工具使用记录（T8，spec §七.4 C22）：**进程内、有界、只读导出**。
 *
 * <p>★ **它是什么**：GM 口（= 运行时 MCP 口，spec §2.1）上**每个工具执行**的一条留痕——工具名 + 结果（成功 / 失败码）+ 时刻。GM 交互界面（{@code
 * GET /api/gm/tool-usage}）读它。
 *
 * <p>★ **它不是什么**：**不是世界状态**、不落盘、不进 {@code Command → ChangeSet → Revision}（铁律 2）——工具调用是
 * <b>观测面</b>，不是可回放的世界事实；进程重启即清空。业务上的持久事实仍由 {@code revisions}/{@code events} 承载。
 *
 * <p>★ **为什么只记"实际执行"**：装饰发生在 {@link io.mosire.agentlib.tool.AgentTool#execute}，故被权限硬拒 / 审批未放行 /
 * 工具不存在的调用**不在此列**——那些在工具执行之前就被 {@code ToolCallAuthorizer} 挡下了。口径是"用过的工具"，不是"请求过的工具"。
 *
 * <p>★ **有界**：最多 {@value #MAX_ENTRIES} 条，超出丢最旧（防长跑进程无界增长）。读出口返回**最新在前**的不可变快照。
 */
public final class GmToolUsage {

  /** 记录上限（有界；超出丢最旧）。 */
  public static final int MAX_ENTRIES = 200;

  private final int maxEntries;
  private final Deque<Entry> entries = new ArrayDeque<>();

  /** 缺省上限（生产用）。 */
  public GmToolUsage() {
    this(MAX_ENTRIES);
  }

  /** 指定上限（测试用）；必须 ≥ 1。 */
  public GmToolUsage(int maxEntries) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries 必须 ≥ 1: " + maxEntries);
    }
    this.maxEntries = maxEntries;
  }

  /**
   * 追加一条记录（线程安全）。
   *
   * @param tool 工具名（非空）
   * @param ok 执行是否成功（{@code ToolResult.success()}）
   * @param code 失败码（成功为 {@code null}）
   * @param atEpochMs 记录时刻（epoch 毫秒）
   */
  public synchronized void record(String tool, boolean ok, String code, long atEpochMs) {
    Objects.requireNonNull(tool, "tool");
    if (entries.size() >= maxEntries) {
      entries.removeFirst();
    }
    entries.addLast(new Entry(tool, ok, code, atEpochMs));
  }

  /** 最近使用记录，**最新在前**的不可变快照。 */
  public synchronized List<Entry> recent() {
    List<Entry> view = new ArrayList<>(entries.size());
    Iterator<Entry> descending = entries.descendingIterator();
    while (descending.hasNext()) {
      view.add(descending.next());
    }
    return List.copyOf(view);
  }

  /** 当前条数（测试 / 运维读数用）。 */
  public synchronized int size() {
    return entries.size();
  }

  /** 一条工具使用记录：工具名 + 结果（成功 / 失败码）+ 发生时刻。 */
  public record Entry(String tool, boolean ok, String code, long atEpochMs) {

    public Entry {
      Objects.requireNonNull(tool, "tool");
    }
  }
}
