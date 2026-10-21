package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 土地类型 id（D-032 单 hex 三档土地组件树的第一档）。
 *
 * <p>内置 {@link #ARABLE} / {@link #URBAN}；其余类型用开放 wire string（建议 {@code custom:<name>}）。本类型只做身份与
 * 线格式，不携带任何农业生产或经济公式。
 *
 * @param wireValue 线格式（非空白）
 */
public record LandTypeId(String wireValue) {

  /** 耕地。 */
  public static final LandTypeId ARABLE = new LandTypeId("arable");

  /** 城市/建成区用地。 */
  public static final LandTypeId URBAN = new LandTypeId("urban");

  public LandTypeId {
    if (wireValue == null || wireValue.isBlank()) {
      throw new IllegalArgumentException("LandTypeId.wireValue 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return wireValue;
  }

  /** 线格式 → 类型（开放词表；只拒绝空白，不静默归一大小写）。 */
  @JsonCreator
  public static LandTypeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LandTypeId 不得为空白: " + text);
    }
    return new LandTypeId(text);
  }
}
