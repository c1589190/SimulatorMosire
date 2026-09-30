package io.mosire.simos.economy.classfirst;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.ClassFlowEventId;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.id.HouseholdProductionAccountId;
import io.mosire.simos.economy.api.id.MobilityPolicyId;
import io.mosire.simos.economy.api.id.ModeParticipationId;
import io.mosire.simos.economy.api.id.ProductionModeId;

/**
 * ★★ <b>{@link ClassFirstState} 各张表的键反序列化器（裸 Jackson 的全局键绑定）</b>。
 *
 * <p>★★ <b>为什么需要它</b>：{@code ClassFirstState} 的九张表以七类<b>自定义 ID</b> 为键。{@code EconomyCodec} 的私有
 * mapper 在 {@code keyModule()} 里注册过它们，但 Timeline 的 changeset mapper（以及任何别的裸 mapper）没有那份注册 ⇒ 读回时
 * {@code Cannot find a (Map) Key deserializer}。本类把"键文本 → ID"的七个映射放在<b>状态类型自己的注解</b>上 （见 {@link
 * ClassFirstState} 的组件注解），任何 mapper 都走同一份 {@code parse}，不再依赖"谁记得注册模块"。
 *
 * <p>★ 每个映射都委托各 ID 自己的 {@code static parse}（唯一拼写点）；本类不重解释任何格式。
 */
public final class ClassFirstJsonKeys {

  private ClassFirstJsonKeys() {}

  /** 键文本 → {@link ClassPoolId}（{@code classPools} / {@code classBounds}）。 */
  public static final class ClassPoolKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ClassPoolId.parse(key);
    }
  }

  /** 键文本 → {@link ModeParticipationId}（{@code modeParticipations}）。 */
  public static final class ModeParticipationKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ModeParticipationId.parse(key);
    }
  }

  /** 键文本 → {@link HouseholdProductionAccountId}（{@code householdAccounts}）。 */
  public static final class HouseholdProductionAccountKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return HouseholdProductionAccountId.parse(key);
    }
  }

  /** 键文本 → {@link ProductionModeId}（{@code assetStateSchemas}）。 */
  public static final class ProductionModeKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ProductionModeId.parse(key);
    }
  }

  /** 键文本 → {@link MobilityPolicyId}（{@code mobilityPolicies}）。 */
  public static final class MobilityPolicyKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return MobilityPolicyId.parse(key);
    }
  }

  /** 键文本 → {@link ClassFlowEventId}（{@code classFlowEvents}）。 */
  public static final class ClassFlowEventKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ClassFlowEventId.parse(key);
    }
  }

  /** 键文本 → {@link ClassFirstAccountId}（{@code accounts}）。 */
  public static final class ClassFirstAccountKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ClassFirstAccountId.parse(key);
    }
  }

  /** 键文本 → {@link ExternalLenderId}（{@code lenders}）。 */
  public static final class ExternalLenderKey extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) {
      return ExternalLenderId.parse(key);
    }
  }
}
