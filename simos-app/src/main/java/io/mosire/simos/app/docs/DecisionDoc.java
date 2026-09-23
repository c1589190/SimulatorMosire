package io.mosire.simos.app.docs;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import java.util.Optional;

/**
 * 决策文档（Docs）的**地址约定唯一拼写点**：一份文档 = sd INFO 覆盖层里的一条条目，地址形如 {@code sd:doc.<docId>}。
 *
 * <p>★★ **为什么文档建在 INFO 上而不是新开一个模块**（用户 2026-09-23 原话）：「对于单个决策人，文档必然是有关程序中国家、地块等的信息； 本质问题是单个 INFO
 * 无法储存更多信息，因此应该和 INFO 机制结合，作为 INFO 机制的扩展」。落法：
 *
 * <ul>
 *   <li>**地址** = {@link #ADDRESS_PREFIX} + docId（本类给出，唯一）；
 *   <li>**正文** = {@code SdInfoEntry.value}。★ {@code value} 是裸 {@code Object}、**只保证标量往返**，故结构化内容一律
 *       **自行序列化成 JSON 字符串**再存（先例：{@code AdjudicateTickTool} 的决策结果）；
 *   <li>**可见性** = {@code tags}（显式指派决策人）∪ {@code affiliations}（归属自动），判定只在 {@code
 *       RedactingQueryService#docs} 一处。
 * </ul>
 *
 * <p>★ **docId 必须是"裸词"**（不含 {@code '.'}/{@code ':'}/空白）：地址的 canonical 是**段序列拼 {@code ':'}**、段内是
 * {@code kind.name}（见 {@code Address#canonical}），docId 里若带这些字符会把 canonical 撕成别的段形状，于是**写进去的地址与读出来的
 * "是不是文档"判据不再一致**。{@link #addressOf} 在写入侧就拒，故这条不会以"文档静默消失"的形式出现。
 *
 * <p>★ **读侧判据用解析出的 {@code kind}、不用字符串前缀**：{@code startsWith} 只在"docId 一定是裸词"这个前提下成立，而 {@link
 * #docIdOf} 的入参可能来自**任何人写的任意地址**（{@code sd.PutInfo} 是通用写口，GM 可能手滑）。解析成 {@link Entity} 再比 {@code
 * kind} 才与 canonical 的唯一性同源（同时规避 {@code sd:docx.foo} 这类前缀假阳性）。
 */
public final class DecisionDoc {

  /**
   * 文档地址前缀（{@code sd:doc.<docId>}）。★ 与 {@code AdjudicateTickTool.RESULT_ADDRESS_PREFIX} 并列的一条地址约定。
   */
  public static final String ADDRESS_PREFIX = "sd:doc.";

  /** 文档条目的 {@code key}（固定值：一份文档一条 INFO）。 */
  public static final String KEY = "doc";

  private DecisionDoc() {}

  /**
   * docId → canonical 地址。
   *
   * @throws IllegalArgumentException docId 空白、或含 {@code '.'}/{@code ':'}/空白（见类注）
   */
  public static String addressOf(String docId) {
    if (docId == null || docId.isBlank()) {
      throw new IllegalArgumentException("docId 不得为空白");
    }
    for (int i = 0; i < docId.length(); i++) {
      char c = docId.charAt(i);
      if (c == '.' || c == ':' || Character.isWhitespace(c)) {
        throw new IllegalArgumentException("docId 不得含 '.' / ':' / 空白: " + docId);
      }
    }
    return ADDRESS_PREFIX + docId;
  }

  /**
   * canonical 地址 → docId；**不是文档地址就返回空**（fail-closed：判不出来就不当文档，绝不放行）。
   *
   * <p>★ 解析失败（非法地址）也返回空：{@code sd.info()} 的键本该都是 {@code Address#canonical()}，但读侧不该因为一条脏键把整次查询炸掉。
   */
  public static Optional<String> docIdOf(String canonicalAddress) {
    if (canonicalAddress == null || !canonicalAddress.startsWith(ADDRESS_PREFIX)) {
      return Optional.empty();
    }
    try {
      Address address = Address.parse(canonicalAddress);
      if (address.segments().size() != 2) {
        return Optional.empty();
      }
      AddressSegment subject = address.segments().get(1);
      if (subject instanceof Entity entity
          && entity.kind().isPresent()
          && KEY.equals(entity.kind().get())) {
        return Optional.of(entity.name());
      }
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
