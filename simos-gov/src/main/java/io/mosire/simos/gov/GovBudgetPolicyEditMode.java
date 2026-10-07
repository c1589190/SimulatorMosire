package io.mosire.simos.gov;

import java.util.Locale;
import java.util.Objects;

/**
 * ★★ <b>{@code gov.SetBudgetPolicy} 的编辑模式（Z7e-3，控制方 2026-10-23 裁定 A+B："AB同时应用吧"）</b>。
 *
 * <ul>
 *   <li>{@link #PATCH}（<b>缺省</b>）：载荷里<b>缺省字段保留现值</b>——只改 {@code remittancePerMilleToSuperior}
 *       不会顺手清空类别表 / 工资规则；要清空必须显式给出（{@code orderedCategories:[]}；工资规则逐内层字段显式给 0）。
 *       既有政策键不存在（首次写入）时无"现值"可保留，等价于中性默认（空表 + 0/0 + 0）。
 *   <li>{@link #REPLACE}：旧整表替换语义——类别表缺省 = 空（不自动付）、工资规则缺省 = 0/0、上缴比例缺省 = 0。
 * </ul>
 *
 * <p>★ <b>词表三面同源</b>：命令载荷的 {@code mode} 字段（本枚举 {@link #parse} 收大小写不敏感）、 决策人/GM 工具的 {@code mode}
 * 参数、目录提示 {@code payloadHints} 都指这一处。{@code mode} 只影响本次解析，<b>不落状态、不进 {@link GovBudgetPolicy}
 * 值对象</b>（幂等重放判定仍是逐值 {@code equals}）。
 *
 * <p>★ 起因（run7 首跑污染，见 {@code docs/superpowers/reports/2026-10-23-fiscal-loop-run7-vs-run6.md}
 * §4）：省级决策人 只传上缴率改 0 抗税时，整表替换把 {@code orderedCategories} + {@code officialSalaryRule} 清空 ⇒ 次日
 * {@code ADMIN_PLAN_MISSING} ⇒ 停俸 ⇒ 官吏逃亡。PATCH 让"改上缴率"这一高频动作不再误伤预算。
 */
public enum GovBudgetPolicyEditMode {
  /** 缺省：缺省字段保留现值，显式字段才覆盖（显式空表 = 清空类别表）。 */
  PATCH,
  /** 旧语义：缺省字段 = 空表 / 0/0 / 0（整表替换）。 */
  REPLACE;

  /**
   * 解析 {@code mode} 文本：{@code PATCH|REPLACE}，大小写不敏感、两侧空白容忍。
   *
   * @throws IllegalArgumentException 非文本/空白/词表外（调用方折成业务拒绝 INFO / 工具 BAD_REQUEST）
   */
  public static GovBudgetPolicyEditMode parse(String raw) {
    Objects.requireNonNull(raw, "raw");
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (GovBudgetPolicyEditMode mode : values()) {
      if (mode.name().equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("字段 mode 只支持 PATCH|REPLACE，收到: " + raw);
  }
}
