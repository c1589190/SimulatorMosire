package io.mosire.simos.economy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.AssetId;
import io.mosire.simos.economy.api.id.AssetRightId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ContractId;
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
 * 共用契约的稳定 ID 三件套与 {@link ActorRef} 的护栏（设计稿 §2/§3，铁律 1）。
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
          new IdKind("LaborAllocationId", LaborAllocationId::new, LaborAllocationId::parse));

  @Test
  void everyIdCoversTheThreePieceContract() {
    assertThat(IDS).as("清单必须覆盖全部 21 个 ID（漏一个 = 那一类没有护栏）").hasSize(21);

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
      //     ⇒ 它用**自己的合法值**取样例；其余 20 类仍用通用样例 `u-1`。
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

  /**
   * ★★ **逐值断言**（R2 由四档扩到七档）：扩枚举是 {@code LaborAllocation.actor} 选了 {@code ActorRef} 的代价 （第三阶段设计稿
   * §八.1 明写"要扩枚举 + 同步改 {@code EconomyIdsTest} 的逐值断言"），故本条就是那份"连带改"。
   *
   * <p>★ 前四档的**次序与拼写一字不动**（它们已进过 JSON：{@code LedgerCodec} 写 {@code kind} 用 {@code name()}）；
   * 新增三档追加在**末尾**，理由同上——插在中间会让"词表位置"这种没进线格式的东西产生 diff 噪声。
   */
  @Test
  void actorKindCoversTheSevenDocumentedKinds() {
    assertThat(ActorKind.values())
        .as("设计稿 §2/§4/§5/§7 的四类主体 + R2 的生产关系三类（家户/庄园/作坊）")
        .containsExactly(
            ActorKind.PEOPLE_LOT,
            ActorKind.UNIT,
            ActorKind.GOVERNMENT,
            ActorKind.ORGANIZATION,
            ActorKind.HOUSEHOLD,
            ActorKind.ESTATE,
            ActorKind.WORKSHOP);
  }

  @Test
  void actorKindParseRejectsTextOutsideTheVocabularyAndListsLegalValues() {
    assertThatThrownBy(() -> ActorKind.parse("NOPE"))
        .as("词表外的种类必须即抛，且消息里列出合法值")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PEOPLE_LOT")
        .hasMessageContaining("UNIT")
        .hasMessageContaining("GOVERNMENT")
        .hasMessageContaining("ORGANIZATION")
        .hasMessageContaining("HOUSEHOLD")
        .hasMessageContaining("ESTATE")
        .hasMessageContaining("WORKSHOP");

    assertThatThrownBy(() -> ActorKind.parse(null))
        .as("null 种类即抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActorKind.parse("  "))
        .as("空白种类即抛")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(ActorKind.parse("UNIT")).isEqualTo(ActorKind.UNIT);
  }

  @Test
  void actorRefValidatesKindAndId() {
    assertThatThrownBy(() -> new ActorRef(null, "u-1"))
        .as("kind 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActorRef(ActorKind.UNIT, null))
        .as("id 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ActorRef(ActorKind.UNIT, "  "))
        .as("id 不得为空白")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(new ActorRef(ActorKind.UNIT, "u-1").toString()).isEqualTo("UNIT:u-1");
  }

  @Test
  void actorRefParseHasBothRefusalReasons() {
    // 拒因 1：kind 词表外
    assertThatThrownBy(() -> ActorRef.parse("NOPE", "u-1"))
        .as("kind 词表外即抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PEOPLE_LOT");

    // 拒因 2：id 空白
    assertThatThrownBy(() -> ActorRef.parse("UNIT", "  "))
        .as("id 空白即抛")
        .isInstanceOf(IllegalArgumentException.class);

    // 合法：kind 词表内 + id 非空白
    assertThat(ActorRef.parse("GOVERNMENT", "g-1"))
        .isEqualTo(new ActorRef(ActorKind.GOVERNMENT, "g-1"));
  }
}
