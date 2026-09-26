package io.mosire.simos.economy.api.id;

/**
 * 生产制度 ID（新经济设计 §3.1/§5）：一个生产制度的**稳定身份**。
 *
 * <p>★★ <b>本类型没有词表</b>（S1 阶段 3 D8 更正）：构造器与 {@link #parse} 只校验"非空白" ⇒ 任何字面量都构造得出来，
 * 本类**不认识**任何一档。本注原先写"小农 / 封建租佃 / 手工业 / 资本主义工业**四档**"——那是一句**假声明** （本类里根本没有那个封闭集合），且其中"封建租佃"已由裁定 D7
 * 更正为 **领主自营庄园**（租佃另立 {@code tenant} 档）。
 *
 * <p>★ **"有哪几档"的知识住在两处显式的地方**，都不在本类：默认经营主体的推导表 {@code RegimeOperators} （登记了哪几档、未登记即抛；住在 {@code
 * simos-economy} 的 {@code model} 包，本模块够不着故只用 {@code @code} 点名）， 以及**具体世界播种时写下的字面量**（如 {@code
 * EconomySeeder.REGIME_*} 常量）。
 *
 * <p>制度决定"允许哪些阶层槽位"（§3.1 {@code Industry.regime}）与默认的分配函数参数（§5，参数版本化）。 它只是身份标签，**不含任何规则/公式**。裸值
 * {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record RegimeId(String value) {

  public RegimeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RegimeId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static RegimeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("RegimeId 不得为空白: " + text);
    }
    return new RegimeId(text);
  }
}
