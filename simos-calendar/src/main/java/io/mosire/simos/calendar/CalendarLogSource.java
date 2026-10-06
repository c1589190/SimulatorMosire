package io.mosire.simos.calendar;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>calendar 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 calendar 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记。本模块是纯计算/无状态模块，没有 tick 推进、也没有
 * handler：历法时钟与季节系统都只在装配期绑定一次、季度查询是逐日纯函数，故三类来源一律归 {@link LogOriginKind#SYSTEM} （按纪律“TICK 类必带
 * day；本模块无 day/tick 上下文，改用 SYSTEM 表项并在此说明”）。
 *
 * <ul>
 *   <li>{@link #CAL_CLOCK}：历法时钟绑定与锚点配置（装配期一次，配置漂移可查）；
 *   <li>{@link #CAL_SEASON}：季节系统配置与季界事件（纯计算，无 tick 上下文）；
 *   <li>{@link #CAL_TRACE}：逐次季节查询明细（默认关，慎开）。
 * </ul>
 */
public enum CalendarLogSource implements LogOrigin {
  CAL_CLOCK("calendar-clock", "历法时钟绑定与锚点配置（装配期一次，无 day 上下文）", LogOriginKind.SYSTEM),
  CAL_SEASON("calendar-season", "季节系统配置与季界事件（纯计算，无 tick 上下文）", LogOriginKind.SYSTEM),
  CAL_TRACE("calendar-trace", "逐次季节查询明细（默认关，慎开）", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  CalendarLogSource(String id, String description, LogOriginKind kind) {
    this.id = id;
    this.description = description;
    this.kind = kind;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public LogOriginKind kind() {
    return kind;
  }
}
