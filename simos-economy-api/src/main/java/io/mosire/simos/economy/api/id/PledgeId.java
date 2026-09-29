package io.mosire.simos.economy.api.id;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * ★★ <b>质押（Pledge）的稳定身份</b>（E4a；理想架构 §5.4）：一条“某债务合同以某资产份额的多少作质押”的记录。
 *
 * <p>★ <b>E4a 只落形状</b>：不提供确定性 {@code idOf} 工厂 —— 质押的批量/优先级/分期语义要等 E5 的清算路径定了，
 * 才能冻结“同合同同份额多笔质押怎么区分”。在此之前把拼写权留给 E5 的唯一写口，避免先编一个会被推翻的格式。 {@code parse/toString} 与其余 opaque id
 * 同款（非空白 + 裸值互逆，地址 {@code ...:debt.<id>} 接缝要求不含 {@code "."}）。
 */
public record PledgeId(String value) {

  public PledgeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PledgeId 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }

  /** 只校验非空白。 */
  @JsonCreator
  public static PledgeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PledgeId 不得为空白: " + text);
    }
    return new PledgeId(text);
  }
}
