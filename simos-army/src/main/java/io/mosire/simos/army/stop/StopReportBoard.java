package io.mosire.simos.army.stop;

import io.mosire.simos.util.spi.StopReport;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★★ <b>最近一次急停的原因系统（进程内）</b>：GM 用 MCP 工具 {@code simos.army.stopReport} 从这里读回 {@link StopReport}。
 *
 * <p>★★ <b>为什么是"进程内账"而不是世界状态</b>（照 {@code DecisionRunRegistry} 的取舍，逐条同源）：
 *
 * <ul>
 *   <li>进 revision ⇒ <b>污染世界状态</b>：{@code Incident}/{@code StopReport} 是<b>过程观测</b>（"谁在什么时候要求停下"），
 *       不是世界事实 —— 它没有 {@code Id}、不参与铁律 1 的稳定实体体系，把它塞进 revision 会让"回放世界"多出一堆与模拟无关的行；
 *   <li>另存一张表 ⇒ 要动 Core 的 DDL（本批的范围之外），且那是"为一次观测新建一份持久化契约"，代价远大于收益。
 * </ul>
 *
 * <p>★★ <b>进程重启即失 —— 这是已知取舍，必须让 GM 知道</b>：重启后本板子是空的，读工具返回 {@code present=false} 并明说"没有记录 ≠
 * 没发生过"。{@link #stops()} 只数<b>本进程</b>见过的急停次数。★ 若将来要跨重启留痕，<b>另开</b>一份世界事实 （或一张表），不许就地把它塞进 revision。
 *
 * <p>★ <b>线程模型</b>：写者是推进路径（{@code CoreSimos.submitAdvance} 的调用方），读者是 MCP 工具线程 ⇒ 单字段 {@code
 * volatile} 就够了（不需要复合不变量：读到的要么是"某一次急停的完整报告"，要么是"还没有"）。
 */
public final class StopReportBoard {

  /** 最近一次急停（{@code null} = 本进程还没有见过急停）。 */
  private volatile StopReport latest;

  /**
   * 本进程见过的急停次数（跨重启不保留，见类注）。
   *
   * <p>★ 用 {@link AtomicInteger} 而不是 {@code volatile int} + {@code ++}：后者**不是原子操作**（读-改-写三步），
   * 两个推进线程同时停下时会丢计数 —— 门禁实测把这条报成 Medium（{@code VO_VOLATILE_INCREMENT} + {@code
   * AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE}），故就地改对，而不是加抑制。
   */
  private final AtomicInteger stops = new AtomicInteger();

  /** 记下一次急停（后写覆盖前写：GM 关心的是"最近一次为什么停"）。 */
  public void record(StopReport report) {
    this.latest = Objects.requireNonNull(report, "report");
    this.stops.incrementAndGet();
  }

  /** 最近一次急停；空 = 本进程没有记录过（**不等于**"世界从没停过"，见类注）。 */
  public Optional<StopReport> latest() {
    return Optional.ofNullable(latest);
  }

  /** 本进程见过的急停次数。 */
  public int stops() {
    return stops.get();
  }
}
