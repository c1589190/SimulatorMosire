package io.mosire.simos.economy.api.inventory;

/** lot 的来源分类；只用于审计与追溯，不参与价格形成。 */
public enum LotOriginKind {
  CURRENT_STOCK,
  PRODUCED,
  SPLIT,
  TRANSFERRED,
  UNSPECIFIED
}
