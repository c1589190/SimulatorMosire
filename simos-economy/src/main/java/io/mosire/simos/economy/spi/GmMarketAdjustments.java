package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyGmAdjustments.COMMAND;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Change;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Projection;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.UPSERT_ASSET_RULE;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.assetKind;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.booleanField;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.noOp;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.parseAssetRuleId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.productionModeId;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.requireExistingField;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GM 调整拆分 helper（由 EconomyGmAdjustments 抽取；行为不变）。 */
final class GmMarketAdjustments {

  private GmMarketAdjustments() {}

  /**
   * {@code upsertAssetRule}：{@code {modeId,assetKind,id?,isCoreMeans?,rentRule?,transferRule?}}。id
   * 由 {@code AssetRuleId.idOf(modeId, assetKind)} 确定性派生（显式给必须与派生值一致）。mode 必须存在；新建
   * isCoreMeans/transferRule 必填，rentRule 缺省空；既有则未给字段沿用原值（rentRule 显式 null 清空）。
   */
  static Projection upsertAssetRule(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_ASSET_RULE;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
    }
    io.mosire.simos.actor.api.asset.AssetKind assetKind =
        assetKind(
            label, "assetKind", EconomyCommandPayloads.requireText(label, parameters, "assetKind"));
    AssetRuleId id = AssetRuleId.idOf(modeId, assetKind);
    if (parameters.hasNonNull("id")) {
      AssetRuleId declared =
          parseAssetRuleId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
      if (!declared.equals(id)) {
        throw new IllegalArgumentException(
            label
                + " 的 id 必须与 (modeId, assetKind) 派生值一致（不许手写第二份身份）：声明="
                + declared.value()
                + " 派生="
                + id.value());
      }
    }
    AssetRule before = base.assetRules().get(id);
    boolean isCoreMeans =
        booleanField(
            label, parameters, "isCoreMeans", before == null ? null : before.isCoreMeans());
    Optional<RentRule> rentRule;
    if (parameters.has("rentRule")) {
      JsonNode node = parameters.get("rentRule");
      if (node == null || node.isNull()) {
        rentRule = Optional.empty();
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 rentRule 必须是对象: " + node);
        }
        rentRule = Optional.of(EconomyPayloads.rentRule(node));
      }
    } else {
      rentRule = before == null ? Optional.empty() : before.rentRule();
    }
    TransferRule transferRule;
    if (parameters.has("transferRule")) {
      JsonNode node = parameters.get("transferRule");
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(label + " 的 transferRule 必须是对象: " + node);
      }
      transferRule = EconomyPayloads.transferRule(node);
    } else {
      transferRule =
          requireExistingField(
              label, "transferRule", before == null ? null : before.transferRule());
    }
    AssetRule after = new AssetRule(id, modeId, assetKind, isCoreMeans, rentRule, transferRule);
    if (after.equals(before)) {
      return noOp(UPSERT_ASSET_RULE, reason, day, base);
    }
    Map<AssetRuleId, AssetRule> rules = new LinkedHashMap<>(base.assetRules());
    rules.put(id, after);
    EconomyData projected = base.withAssetRules(rules);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_ASSET_RULE,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("assetRules", id.value(), before, after)));
  }
}
