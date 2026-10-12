package io.mosire.simos.gov.nation;

/**
 * "国家区域"的 tag 约定（R13 / 计划 §六 G1）：`"nation:" + nationId`。
 *
 * <p>★ **常量集中一处**：{@code gov.CreateNation} 与 A6 的跨模块守卫（{@link
 * io.mosire.simos.gov.guard.RegionDeleteGuard}） **引用同一常量**，避免两处字面量各自漂移（计划 G1 的建议，执行期采纳）。
 *
 * <p>★★ <b>2026-10-12 批次 3c：自 {@code io.mosire.simos.sd.spi} 迁入本包</b>——它按定义要拿 {@link NationId}（已归
 * gov）， 而 sd 不得依赖 gov。前缀串与 {@link #tagFor}/{@link #isNationTag} 的形状一字未改；map 侧区域标签（{@code
 * region.meta().tag()}）也不受影响。
 */
public final class NationTag {

  public static final String PREFIX = "nation:";

  private NationTag() {}

  public static String tagFor(NationId nationId) {
    return PREFIX + nationId.value();
  }

  public static boolean isNationTag(String tag) {
    return tag != null && tag.startsWith(PREFIX);
  }
}
