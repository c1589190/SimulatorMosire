package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>出令「执行原文」条目（sd INFO 覆盖层）的<b>唯一拼写点</b></b>（2026-10-12 批次 3b′）。
 *
 * <p>★★ <b>为什么需要它</b>：执行原文的落点是 sd INFO 覆盖层里地址 {@code sd:directive.<id>}、key {@value #INFO_KEY}
 * 的那条条目。批次 3b′ 之前它由 {@code IssueDirectiveHandler} **在同一条命令里**写入（那时令与 INFO 都在 {@code sd} 切片）； 该批把令搬进
 * {@code simos-ga}（{@code GaState.directives}）后，一条命令 = 一个命名空间（{@code CommandBus.namespaceOf}
 * 取类型前缀）⇒ {@code ga.IssueDirective} **只能**写 {@code GaChangeSet}，而 {@code info} 表本批**仍住 sd** ⇒
 * 执行原文改由**同批的第二条命令 {@code sd.PutInfo}** 落（仓内既有形制：{@code RejectDirectiveTool} / {@code
 * AdjudicateTickTool} / {@code DecisionEffectExecutor} 等十余处审计条目都这么做），两条命令装进**同一批** ⇒ 令与执行原文仍落在**同一条
 * revision**。
 *
 * <p>★★ <b>本类就是那条第二命令的载荷生产者</b>：{@link #putInfoPayloadJson(String)} 从<b>同一份出令载荷</b>派生 {@code
 * sd.PutInfo} 的载荷（{@link #addressOf} + {@value #INFO_KEY} + {@value #SOURCE_DIRECTIVE_FIELD}
 * 三处拼写都在这里， 调用方不再各抄一份）。地址族串（{@code sd:directive.<id>}）**一字未改**（它的裁量属批次 3e）。
 *
 * <p>★ <b>失败方向 = 不拦出令</b>：载荷缺字段 / JSON 非法 / id 的形状拼不出合法地址 ⇒ 返回 {@link Optional#empty()}（**不抛**） ——
 * 让 {@code ga.IssueDirective} 自己给出真正的具名拒因（否则这里会先抛，把 {@code Rejected} 变成 {@code BAD_REQUEST}，
 * 或让一条本该能落的令因配套条目写不成而整批被拒）。此时**只有执行原文条目缺席**，令照落。
 */
public final class DirectiveIntentInfo {

  /** 出令执行原文条目在 sd INFO 覆盖层里的地址前缀（{@code sd:directive.<id>}）。 */
  public static final String ADDRESS_PREFIX = "sd:directive.";

  /** 该条目的 key（{@code Directive.intentInfoKey} 记的就是它）。 */
  public static final String INFO_KEY = "intent";

  /** {@code sd.PutInfo} 载荷里"本条记的是哪条令"那一项的字段名（写进 {@code SdInfoEntry.sourceDirective}）。 */
  public static final String SOURCE_DIRECTIVE_FIELD = "sourceDirective";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private DirectiveIntentInfo() {}

  /** 执行原文条目的 canonical 地址（{@code sd:directive.<id>}；裸 id 下与 canonical 逐字相同）。 */
  public static String addressOf(DirectiveId id) {
    Objects.requireNonNull(id, "id");
    return ADDRESS_PREFIX + id.value();
  }

  /**
   * 由**出令载荷**（{@code ga.IssueDirective} 的 {@code payloadJson}）派生出配套 {@code sd.PutInfo} 的载荷 JSON。
   *
   * <p>取四个字段：{@code directiveId}（地址与来源引用）、{@code decisionMakerId}（tags）、{@code tick}（条目归属）、 {@code
   * intentInfo}（value）。{@code id} **不显式给**：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成（与 {@code
   * IssueDirectiveHandler} 原来的 {@code SdInfoIds.synthesize(address, entries.size())} 同口径）。
   *
   * @param directivePayloadJson 出令命令的载荷原文（逐字节转交，不 trim、不 re-serialize 后再喂给主命令）
   * @return 配套 {@code sd.PutInfo} 的载荷 JSON；任一前提不成立 ⇒ 空（见类注的失败方向）
   */
  public static Optional<String> putInfoPayloadJson(String directivePayloadJson) {
    Objects.requireNonNull(directivePayloadJson, "directivePayloadJson");
    try {
      JsonNode payload = MAPPER.readTree(directivePayloadJson);
      if (payload == null || !payload.isObject()) {
        return Optional.empty();
      }
      JsonNode idNode = payload.get("directiveId");
      JsonNode dmNode = payload.get("decisionMakerId");
      JsonNode tickNode = payload.get("tick");
      JsonNode intentNode = payload.get("intentInfo");
      if (idNode == null
          || !idNode.isTextual()
          || dmNode == null
          || !dmNode.isTextual()
          || tickNode == null
          || !tickNode.isIntegralNumber()
          || intentNode == null
          || !intentNode.isTextual()) {
        return Optional.empty();
      }
      DirectiveId id = DirectiveId.parse(idNode.asText());
      DecisionMakerId decisionMakerId = DecisionMakerId.parse(dmNode.asText());
      String address = addressOf(id);
      // ★ 地址必须先拼得出合法 Address：拼不出 ⇒ 不提交配套命令（否则整批会被 sd.PutInfo 拒掉，连令一起落不了）。
      Address.parse(address);
      Map<String, Object> put = new LinkedHashMap<>();
      put.put("address", address);
      put.put("key", INFO_KEY);
      put.put("value", intentNode.asText());
      put.put("tick", tickNode.asLong());
      put.put("tags", List.of(decisionMakerId.value()));
      put.put(SOURCE_DIRECTIVE_FIELD, id.value());
      return Optional.of(MAPPER.writeValueAsString(put));
    } catch (JsonProcessingException | IllegalArgumentException e) {
      // ★ 解析 / 序列化失败（JsonProcessingException）或 id/地址形状不成立（IllegalArgumentException）⇒ 空（不抛）：
      //   真正的拒因由主命令给，见类注的失败方向。
      return Optional.empty();
    }
  }
}
