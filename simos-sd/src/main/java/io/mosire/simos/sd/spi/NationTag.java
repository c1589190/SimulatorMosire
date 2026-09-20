package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.id.NationId;

/**
 * "国家区域"的 tag 约定（R13 / 计划 §六 G1）：`"nation:" + nationId`。
 *
 * <p>★ **常量集中一处**：{@code sd.CreateNation} 与 A6 的跨模块守卫（{@code RegionDeleteGuard}）**引用同一常量**，
 * 避免两处字面量各自漂移（计划 G1 的建议，执行期采纳）。
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
