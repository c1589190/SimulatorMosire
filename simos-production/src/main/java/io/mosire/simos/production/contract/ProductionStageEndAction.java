package io.mosire.simos.production.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>D-032 U4a：阶段结束动作（多阶段生产定义 schema）</b>。
 *
 * <p>语义（spec §5.3）：
 *
 * <ul>
 *   <li>{@link #NEXT_STAGE}：本阶段结束进入<b>列表下一项</b>；只有非最后一项可以且必须用它。
 *   <li>{@link #HARVEST}：本阶段结束收获；只有最后一项可以且必须用它。
 * </ul>
 *
 * <p>★★ <b>本枚举是大写词表的唯一拼写点</b>：载荷/线格式一律用 {@link #parse(String)} 解析，大小写敏感、不做归一
 * （归一即猜）。未知值具名拒绝，<b>不</b>静默取默认。
 *
 * <p>★ U4a 只定义 schema，不接 runtime；阶段推进算法见 U4b。
 */
public enum ProductionStageEndAction {

  /** 结束本阶段并进入列表下一项（非最后一项必须用它）。 */
  NEXT_STAGE,

  /** 结束本阶段并收获（最后一项必须用它）。 */
  HARVEST;

  /** 线格式/载荷里的稳定键（= 枚举名，不另写第二份字面量）。 */
  public String key() {
    return name();
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<ProductionStageEndAction> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static ProductionStageEndAction parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionStageEndAction 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (ProductionStageEndAction action : values()) {
      if (action.name().equals(text)) {
        return action;
      }
      legal.add(action.name());
    }
    throw new IllegalArgumentException("未登记的阶段结束动作: " + text + "；合法值: " + legal);
  }
}
