package io.mosire.simos.social.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * social 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code MapCodec} 同制，理由不重复——只记 social 自己的那点差异。
 *
 * <p>★ 树里的自定义键有三个：{@code HexCoord}（{@code populations} 的键）、{@code CityId}（{@code cities} 的键）与
 * {@code PeopleLotId}（{@code groups} 的键，R1 新增）；前两者住在 simos-map、后者住在 simos-economy-api，social
 * 对两者都有依赖 （后者见设计稿 §八.1/§八.2），铁律 3 允许。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * SocialChangeSet#between(SocialData, SocialData)}。
 */
public final class SocialCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ **把 {@code SocialChangeSet.isEmpty()} 摘出 JSON 形态**（M4 Task 3 实测新发现，探针只往返过快照、没往返过变更集）：Jackson
   * 会把 {@code isEmpty()} 当成 {@code "empty"} 属性写进字节，而严格读入（{@code FAIL_ON_UNKNOWN_PROPERTIES}
   * 保持默认）随即炸掉—— **严格恰好在这里立了功**，把它从"静默写脏存档"变成了"当场响"。{@code isEmpty} 是派生判断不是状态，**不进线格式**；mixin 放本类
   * （mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。与 {@code MapCodec} 同制，理由不重复。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(SocialChangeSet.class, SocialChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class SocialChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("social-json-keys");
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    module.addKeyDeserializer(CityId.class, keyDeserializer(CityId::parse));
    // ★ R1：`groups` 的键是 PeopleLotId（住在 simos-economy-api，social 依赖它 —— 文档明文允许，
    //   spec §八.1/§八.2）。与上面两条同制：裸值 toString() 作键、parse 还原。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
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

  /** 同 {@code MapCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("social 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "social";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, SocialChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((SocialChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, SocialSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asSocialSnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    SocialSnapshot socialBase = asSocialSnapshot(base);
    SocialData next = SocialChangeSet.apply((SocialChangeSet) changeSet, socialBase.data());
    return new SocialSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link SocialChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return SocialChangeSet.between(asSocialSnapshot(base).data(), asSocialSnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸。
   *
   * <p>★ 原先这里是裸 {@code (SocialSnapshot) base}。它合法，但那是**未确认的下转型**——{@code Snapshot} 有
   * map/social/unit 三个实现，转错只在下游落成 {@code ClassCastException}，读不出"这是装配给错了切片"。改成 {@code instanceof}
   * 之后连 cast 都不存在，错误信息指名道姓。
   */
  private static SocialSnapshot asSocialSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "social codec 的切片不是 SocialSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return socialSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("social 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
