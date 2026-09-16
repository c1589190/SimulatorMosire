package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Optional;

/**
 * 外挂时态属性系统（总纲 §4.6）：**不挂在任何 Java 对象上**，是"地址 → 信息"的外挂关系。
 *
 * <p>实现必须不可变——{@link #put} 返回新实例（spec §十-D4），否则 `equals()` 往返断言无从谈起。
 */
public interface InfoSystem {

  /** 取 `subject` 上 `key` 在时刻 `at` 有效的那条；同一 key 重叠时**插入序最后者胜**。 */
  Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at);

  /** 追加一条（新开有效期的写法就是"改值"）；返回新实例，原实例不变。 */
  InfoSystem put(Address subject, InfoEntry entry);
}
