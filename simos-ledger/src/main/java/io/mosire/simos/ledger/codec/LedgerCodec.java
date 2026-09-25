package io.mosire.simos.ledger.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.LedgerData;
import io.mosire.simos.ledger.LedgerSnapshot;
import io.mosire.simos.ledger.change.LedgerChangeSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * ledger 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code SocialCodec} 同制，理由不重复——只记 ledger 自己的那点差异。
 *
 * <p>★ 树里的自定义键有四个：{@code AccountId}/{@code ClaimId}/{@code TransferId}（三张表的键）与 {@code
 * CommodityId}（{@code Account.goods}/{@code reserved} 与 {@code Transfer.goods} 的键）。四者都住在 {@code
 * simos-economy-api}，ledger 依赖它故够得着（铁律 3 允许）。键反序列化器照裁定 16 在**本模块**注册， 不进共享基座。四个类型都重写了 {@code
 * toString()}（= 裸值），与各自的 {@code parse} 互为逆，故只需读侧。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * LedgerChangeSet#between(LedgerData, LedgerData)}。
 */
public final class LedgerCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ 把 {@code LedgerChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code SocialCodec}/{@code MapCodec} 同制）：
   * Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是
   * 派生判断不是状态，**不进线格式**；mixin 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。
   *
   * <p>★ 共享基座 {@code SimosObjectMapper} 已有一条与领域类型无关的同款规则（{@code Timeline} 那台第四台 mapper 看不见模块级
   * mixin）⇒ 本 mixin 在功能上**冗余**，但保留：它是**更具体的同一句话**，且改动面最小 （{@code SocialCodec} 同样保留）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(LedgerChangeSet.class, LedgerChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class LedgerChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("ledger-json-keys");
    module.addKeyDeserializer(AccountId.class, keyDeserializer(AccountId::parse));
    module.addKeyDeserializer(ClaimId.class, keyDeserializer(ClaimId::parse));
    module.addKeyDeserializer(TransferId.class, keyDeserializer(TransferId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
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

  /** 同 {@code SocialCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("ledger 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "ledger";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, LedgerChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((LedgerChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, LedgerSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asLedgerSnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    LedgerSnapshot ledgerBase = asLedgerSnapshot(base);
    LedgerData next = LedgerChangeSet.apply((LedgerChangeSet) changeSet, ledgerBase.data());
    return new LedgerSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link LedgerChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return LedgerChangeSet.between(asLedgerSnapshot(base).data(), asLedgerSnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸（照 {@code SocialCodec.asSocialSnapshot}：裸 cast 同样会
   * 抛，但那是**未确认的下转型**，错误信息读不出"这是装配给错了切片"）。
   */
  private static LedgerSnapshot asLedgerSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof LedgerSnapshot ledgerSnapshot)) {
      throw new IllegalStateException(
          "ledger codec 的切片不是 LedgerSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return ledgerSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("ledger 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
