package io.mosire.simos.economy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.AssetId;
import io.mosire.simos.economy.api.id.AssetRightId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ContractId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.EconomicRuleId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketId;
import io.mosire.simos.economy.api.id.OrderId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionCycleId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RecipeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.TransferId;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 共用契约的稳定 ID 三件套的护栏（设计稿 §2/§3，铁律 1）。
 *
 * <p>★ S1 阶段 2：{@code ActorRef} / {@code ActorKind} 的护栏**随类型搬到 {@code simos-actor-api}** （那边的
 * {@code ActorTypesTest}）—— 它们的家已不在本模块，故本类也不再覆盖它们。
 *
 * <p>★ **覆盖方式**：{@link #IDS} 是**逐类登记的清单**，循环对每一个 ID 断言同一组性质。清单用 {@code hasSize(20)} 钉住 ——漏登记一个 ID
 * 就等于那一类没有护栏（"数个数对张冠李戴零判别力"，但这里的清单同时是**遍历源**，少一项立刻少一类断言， 故个数断言与循环互补）。
 *
 * <p>★ **本轮不删旧的 16 个**（新经济设计 §1 待裁 D1）：只把新经济设计 §3 的 4 个新 ID 登记进来 ⇒ 16 + 4 = 20。 裁剪留到 D1 裁定后的独立提交。
 */
class EconomyIdsTest {

  /** 一个 ID 类的三件套入口：名字（报错定位用）、构造器、{@code static parse}。 */
  private record IdKind(String name, Function<String, ?> create, Function<String, ?> parse) {}

  /**
   * 全部共用 ID——社会人口批次 1 + property 2 + production 4 + ledger 3 + market 3 + government 2 + 商品 1 +
   * **新经济设计 4**（Industry/ClassSlot/Regime/Debt）。
   *
   * <p>★ 就这些：设计稿 §2 未列出的 ID 一律不加（现在写形状就是编造设计）；旧的 16 个本轮一律不删（D1 裁定后再裁）。
   */
  private static final List<IdKind> IDS =
      List.of(
          new IdKind("PeopleLotId", PeopleLotId::new, PeopleLotId::parse),
          new IdKind("AssetId", AssetId::new, AssetId::parse),
          new IdKind("AssetRightId", AssetRightId::new, AssetRightId::parse),
          new IdKind("ProductionUnitId", ProductionUnitId::new, ProductionUnitId::parse),
          new IdKind("RecipeId", RecipeId::new, RecipeId::parse),
          new IdKind("ProductionCycleId", ProductionCycleId::new, ProductionCycleId::parse),
          new IdKind("ContractId", ContractId::new, ContractId::parse),
          new IdKind("AccountId", AccountId::new, AccountId::parse),
          new IdKind("ClaimId", ClaimId::new, ClaimId::parse),
          new IdKind("TransferId", TransferId::new, TransferId::parse),
          new IdKind("MarketId", MarketId::new, MarketId::parse),
          new IdKind("OrderId", OrderId::new, OrderId::parse),
          new IdKind("ShipmentId", ShipmentId::new, ShipmentId::parse),
          new IdKind("GovernmentId", GovernmentId::new, GovernmentId::parse),
          new IdKind("EconomicRuleId", EconomicRuleId::new, EconomicRuleId::parse),
          new IdKind("CommodityId", CommodityId::new, CommodityId::parse),
          new IdKind("IndustryId", IndustryId::new, IndustryId::parse),
          new IdKind("SocialClassId", SocialClassId::new, SocialClassId::parse),
          new IdKind("RegimeId", RegimeId::new, RegimeId::parse),
          new IdKind("DebtId", DebtId::new, DebtId::parse),
          // ★ R2：劳动分配表的主键（第三阶段设计稿 §四）。
          new IdKind("LaborAllocationId", LaborAllocationId::new, LaborAllocationId::parse),
          // ★★ H2：货币 ID（用户 2026-09-27「货币作为接口留好」）—— 与 CommodityId 同族、同形制。
          new IdKind("CurrencyId", CurrencyId::new, CurrencyId::parse));

  @Test
  void everyIdCoversTheThreePieceContract() {
    assertThat(IDS).as("清单必须覆盖全部 22 个 ID（漏一个 = 那一类没有护栏）").hasSize(22);

    for (IdKind id : IDS) {
      String what = id.name();

      // ① 构造器：null / 空串 / 纯空白 一律即抛
      assertThatThrownBy(() -> id.create().apply(null))
          .as("%s 构造器必须拒 null", what)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> id.create().apply(""))
          .as("%s 构造器必须拒空串", what)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> id.create().apply("   "))
          .as("%s 构造器必须拒纯空白", what)
          .isInstanceOf(IllegalArgumentException.class);

      // ② parse：同样拒 null / 空白
      assertThatThrownBy(() -> id.parse().apply(null))
          .as("%s.parse 必须拒 null", what)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> id.parse().apply(""))
          .as("%s.parse 必须拒空串", what)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> id.parse().apply(" \t "))
          .as("%s.parse 必须拒纯空白", what)
          .isInstanceOf(IllegalArgumentException.class);

      // ③ toString 返回裸值（不加类型名前缀、不引号）
      //   ★ S1 阶段 1：`SocialClassId` 是**唯一带词表校验**的 id（产业无关的阶层身份，词表外即抛）
      //     ⇒ 它用**自己的合法值**取样例；其余各类仍用通用样例 `u-1`。
      //     这样 ①② 两条（拒 null/空白）对它照旧生效，③④ 也测得到（而不是把整条从清单里删掉）。
      String sample = "SocialClassId".equals(what) ? "poor_peasant" : "u-1";
      assertThat(id.create().apply(sample)).as("%s 的 toString 返回裸值", what).hasToString(sample);
      assertThat(id.parse().apply(sample)).as("%s.parse 的 toString 返回裸值", what).hasToString(sample);

      // ④ parse 与构造器同一身份（parse 不是另一个类型）
      assertThat(id.parse().apply(sample))
          .as("%s.parse 与构造器同值", what)
          .isEqualTo(id.create().apply(sample));
    }
  }
}
