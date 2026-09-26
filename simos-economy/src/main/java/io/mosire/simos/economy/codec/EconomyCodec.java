package io.mosire.simos.economy.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * economy 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} 同制，理由不重复——只记 economy 自己的那点差异。
 *
 * <p>★ 树里的自定义键有六个：{@code IndustryId}（{@code industries} 的键）、{@code CohortKey}（{@code
 * classes}/{@code flows} 的键，H0 起 = <b>家户身份</b>）、{@code DebtId}（{@code debts} 的键）与 {@code
 * CommodityId}（{@code Industry.outputPerUnit} / {@code ClassRow.goods} / {@code naturalNeeds} /
 * {@code effectiveDemand} / {@code FlowRow.consumed} / {@code
 * AllocationRule.WageFirst.ownerResidual} 的键），以及 **R2 的两个**：{@code PeopleLotId} （{@code
 * laborSupply} 的键）与 {@code LaborAllocationId}（{@code allocations} 的键）。六者都住在 {@code
 * simos-economy-api}（H0 起 {@code CohortKey} 也在那里；本模块 {@code model} 里那个两段式的旧键已按裁定 K2 删除）， economy
 * 依赖它故够得着（铁律 3 允许）。键反序列化器照裁定 16 在**本模块** 注册，不进共享基座。
 *
 * <p>★ {@code AssetKind} 作键（{@code dailyInputPerUnit}/{@code capacity}）走 Jackson **默认的枚举键** 绑定（按
 * {@code name()}），无需自定义；其余 ID/键类型都重写了 {@code toString()}（= 裸值）并与各自的 {@code parse} 互为逆，故只需读侧。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)}。
 */
public final class EconomyCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ 把 {@code EconomyChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code LedgerCodec} 同制）：Jackson 会把 {@code
   * isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断不是状态，**不进线格式**； mixin
   * 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解（{@code AllocationRule} 的 sealed
   * 多态注解除外——那是往返的硬前提）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(EconomyChangeSet.class, EconomyChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class EconomyChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("economy-json-keys");
    module.addKeyDeserializer(IndustryId.class, keyDeserializer(IndustryId::parse));
    module.addKeyDeserializer(CohortKey.class, keyDeserializer(CohortKey::parse));
    module.addKeyDeserializer(DebtId.class, keyDeserializer(DebtId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    // ★ R2 起是两张新表的键：laborSupply（PeopleLotId → LaborSupply）与 allocations（LaborAllocationId
    //   → LaborAllocation）。两者都重写了 toString()（= 裸值）并与各自的 parse 互为逆，故只需读侧。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
    module.addKeyDeserializer(LaborAllocationId.class, keyDeserializer(LaborAllocationId::parse));
    return module;
  }

  private static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

  /** 同 {@code LedgerCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "economy";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, EconomyChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((EconomyChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, EconomySnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asEconomySnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    EconomySnapshot economyBase = asEconomySnapshot(base);
    EconomyData next = EconomyChangeSet.apply((EconomyChangeSet) changeSet, economyBase.data());
    return new EconomySnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link EconomyChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return EconomyChangeSet.between(
        asEconomySnapshot(base).data(), asEconomySnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸（照 {@code LedgerCodec.asLedgerSnapshot}：裸 cast 同样会
   * 抛，但那是**未确认的下转型**，错误信息读不出"这是装配给错了切片"）。
   */
  private static EconomySnapshot asEconomySnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy codec 的切片不是 EconomySnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return economySnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
