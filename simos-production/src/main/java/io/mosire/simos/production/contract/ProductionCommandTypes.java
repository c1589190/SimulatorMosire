package io.mosire.simos.production.contract;

/**
 * ★★ <b>跨模块需要引用的 production 命令类型字面量的唯一拼写点</b>（D-032 B2c §13.12.4 裁定十）。
 *
 * <p>★★ <b>为什么它住在 contract 包</b>：economy 只允许 import {@code io.mosire.simos.production.contract}
 * （I4/V11）—— 它要"在同一批里先发一条 production 命令"时，不能去 import {@code production.spi.XxxHandler.TYPE}（那是
 * production 内部包），也不该在调用方再抄一遍命令类型字面量 （两处各写一份必然漂开）。⇒ 字面量只写在这里，handler 的 {@code TYPE} 常量引用它。
 *
 * <p>★ <b>只放"确有待跨模块引用的"字面量</b>：目前只有 {@link #CREATE_INSTANCE}（GM 窄工具 {@code
 * simos.economy.upsertGovUnit} 组批时要用）。其余命令类型仍只在各自 handler 里定义；将来确有跨模块引用再 加进来，<b>不得</b>在调用方就地抄字面量。
 */
public final class ProductionCommandTypes {

  /** {@code production.CreateInstance}：按需为一个已存在的方案定义立实例（见 {@code CreateInstanceHandler}）。 */
  public static final String CREATE_INSTANCE = "production.CreateInstance";

  private ProductionCommandTypes() {}
}
