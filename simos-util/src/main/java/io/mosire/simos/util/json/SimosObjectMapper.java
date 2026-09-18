package io.mosire.simos.util.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.mosire.simos.util.address.Address;
import java.io.IOException;

/**
 * 全项目**唯一**的 {@code ObjectMapper} 装配点（M4 计划增补，spec §1.2 未列）。
 *
 * <p>★ 为什么要单点：三个 {@code ModuleCodec} 分处三个模块，各自 {@code new ObjectMapper()} 就等于**两份配置、两个真相**——而快照
 * JSON 同时是 checkpoint 与重放的输入。配置本身**不含任何领域类型**，故放共享层不违反铁律 3。
 *
 * <p>★ 配置的**每一项**都要有理由，不许"顺手加上"：
 *
 * <ul>
 *   <li>**注册 {@link Jdk8Module}**——快照树里处处是 {@code Optional}（{@code
 *       SimosTimestamp.calendarLabel}、{@code Unit.position/parent} 的 {@code
 *       SegmentedSeries&lt;Optional&lt;…&gt;&gt;}、{@code
 *       Unit.movement}），且处于**嵌套泛型位置**。探针实测（2026-09-18，计划末节「探针结论」）：不注册它，三个快照**全部**死在序列化期（{@code Java
 *       8 optional type ... not supported by default}，值被写成 {@code {"present":…}} 直接丢）。它属于共享基座，因为
 *       {@code Optional} 是 {@code java.util} 的类型，util 够得着——与下面的键反序列化器不同
 *   <li>**不启用** {@code ORDER_MAP_ENTRIES_BY_KEYS}——台账裁定 11：开启即抛（{@code RegionId} 等四个键类型不是 {@code
 *       Comparable}），**且就算不抛也打不中靶**（跨 JVM 的字节漂移源是 {@code Region.hexes} 的 {@code Set.copyOf} 数组序，不是
 *       Map 键序，而七个 Map 本就 {@code LinkedHashMap} 保序）。⇒ **既抛异常又无效**，一条都不留
 *   <li>**不关闭** {@code FAIL_ON_UNKNOWN_PROPERTIES}——保持默认的严格：多出来的字段是**漂移信号**（铁律 5），静默吞掉等于把守卫拆掉一半
 * </ul>
 *
 * <p>★ **{@code create(Module... extraModules)} 这个口子的边界**（台账裁定 16）：六个自定义键类型（{@code HexCoord}/{@code
 * EdgeRef}/{@code RegionId}/{@code CityId}/{@code PathwayId} 在 simos-map、{@code UnitId} 在
 * simos-unit）**全在领域模块里**，util 一个都够不着（铁律 3 + bannedDependencies）⇒ 键反序列化器**只能由各 codec 追加自己的 {@code
 * SimpleModule}** 传进来。本类只守"基础 feature 单一来源"，**不认识**任何领域类型——传进来的模块若装了别的私货，编译期拦不住，靠各 codec 的往返用例兜底。
 */
public final class SimosObjectMapper {

  private SimosObjectMapper() {}

  /**
   * 返回一份**新的**、配置固定的 mapper（调用方不得再改它的 feature）。
   *
   * <p>★ 走 {@code JsonMapper.builder().addModule(...)} 在**建造期**注册，而不是 {@code create().copy()} 之后再
   * {@code registerModule}：后者会先交出一台**缺键反序列化器**的 mapper，才有补装这一步——那条中间态是纯粹的暴露面。建造期注册让每个出厂的 mapper
   * 从第一刻起就是完整的。
   */
  public static ObjectMapper create(Module... extraModules) {
    JsonMapper.Builder builder =
        JsonMapper.builder().addModule(new Jdk8Module()).addModule(addressKeys());
    for (Module module : extraModules) {
      builder.addModule(module);
    }
    return builder.build();
  }

  /**
   * {@link Address} 作 **Map 键**时的绑定（裁定 38）：写成 {@link Address#canonical()}，读回走 {@link
   * Address#parse(String)}。
   *
   * <p>★ **为什么 util 自己注册它**：{@code Address} 是 **util 自己的类型**（与那六个领域键类型不同，util 够得着）， 而它是全仓**唯一**的
   * mapper 装配点——util 的类型在 util 接上 Jackson，是这条"单点"的自然推论。
   *
   * <p>★ **为什么序列化器与反序列化器都要**（对照领域模块只注册后者）：模块的键类型（{@code UnitId}/{@code HexCoord}/…）都**重写了 {@code
   * toString()}**，与各自的 {@code parse} 互为逆——Jackson 的默认键序列化器调 {@code toString()} 恰好就对了。{@link
   * Address} 没有重写它（record 默认给出 {@code Address[segments=[…]]}， **不可解析**），所以写侧必须显式指向 {@code
   * canonical()}。
   *
   * <p>★ **这不动任何既有字节**：实测 {@code Map<Address, …>} 全仓只出现在 {@link
   * io.mosire.simos.util.info.InMemoryInfoSystem} 一处，没有任何领域快照以 {@code Address} 为键——键序列化器只作用于 **Map
   * 的键**，故领域模块的快照字节不受影响。
   */
  private static Module addressKeys() {
    SimpleModule module = new SimpleModule("util-address-keys");
    module.addKeySerializer(
        Address.class,
        new JsonSerializer<Address>() {
          @Override
          public void serialize(
              Address value, JsonGenerator generator, SerializerProvider serializers)
              throws IOException {
            generator.writeFieldName(value.canonical());
          }
        });
    module.addKeyDeserializer(
        Address.class,
        new KeyDeserializer() {
          @Override
          public Object deserializeKey(String key, DeserializationContext context) {
            return Address.parse(key);
          }
        });
    return module;
  }
}
