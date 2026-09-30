package io.mosire.simos.economy.classfirst;

import java.util.List;

/**
 * 阶层池资产向量的维度。
 *
 * <p>租佃制默认状态里只有 {@code OWNED_LAND/TOOLS/GRAIN/CLOTH/MONEY} 是**库存**（参与库存守恒）； {@code OPERATED_LAND}
 * 是本期经营状态量，{@code LEASE_SECURITY} 是租约权利折算量（无价格，记 unpriced）， {@code DEBT} 是负向维度（池持有的欠额折算）。三类派生量都逐
 * tick 从真实库存/租约/账户重算，不参与库存守恒。
 */
public enum AssetKind {
  OWNED_LAND("ownedLand", true),
  OPERATED_LAND("operatedLand", false),
  TOOLS("tools", true),
  GRAIN("grainReserve", true),
  CLOTH("cloth", true),
  MONEY("money", true),
  LEASE_SECURITY("leaseSecurity", false),
  DEBT("debt", false);

  private final String schemaName;
  private final boolean stock;

  AssetKind(String schemaName, boolean stock) {
    this.schemaName = schemaName;
    this.stock = stock;
  }

  public String schemaName() {
    return schemaName;
  }

  /** 是否属于参与库存守恒的实体资产。 */
  public boolean stock() {
    return stock;
  }

  public static List<AssetKind> ordered() {
    return List.of(OWNED_LAND, OPERATED_LAND, TOOLS, GRAIN, CLOTH, MONEY, LEASE_SECURITY, DEBT);
  }
}
