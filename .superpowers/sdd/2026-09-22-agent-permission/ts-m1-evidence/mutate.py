#!/usr/bin/env python3
"""TS/M1 变异体定义：把 DecisionAgentRunner.java 改成某个**确实不同**的字节序列。

每个变异体 = 一组 (old, new) 精确替换；找不到 old（或 old 出现次数不为 1）就**响亮失败**，
绝不"什么都没改却说改了"（那会让整轮退化成假红/假绿）。
"""
import sys

MUTANTS = {
    # m1：不注入 ⇒ 空会话的首轮请求仍是空 messages（= 现场缺陷原样复现）
    "m1": [
        (
            """    if (history.isEmpty()) {
      LlmMessage opening = openingSystemMessage(dm);
      conversations.append(conversationId, opening);
      history.add(opening);
    }
""",
            "",
        )
    ],
    # m2：每轮都注入 ⇒ 身份在上下文里出现两次
    "m2": [
        ("    if (history.isEmpty()) {", "    if (history.size() >= 0) {"),
    ],
    # m3：注入但不落盘 ⇒ 首轮请求有身份、会话里没有（换 store 实例后又空）
    "m3": [
        ("      conversations.append(conversationId, opening);\n", ""),
    ],
    # m4：归属分支写死成"国家"（军队决策人的身份也报 nationId）
    "m4": [
        (
            '      where = "军队（armyId=" + army.armyId().value() + "）";',
            '      where = "国家（nationId=" + army.armyId().value() + "）";',
        )
    ],
    # m5：往永久落盘的正文里拼一个**会漂的值**（模拟把当前 head/版本写进身份消息）
    "m5": [
        ('            + "」，归属："', '            + "」，归属（第 1 号）："'),
    ],
    # m6：身份消息里不写"我是谁"（锚点带上 "你是决策人「" —— 光看 `+ dm.id().value()` 会撞上
    #     回合预算那条异常消息，锚点不唯一 ⇒ 装置判 VOID）
    "m6": [
        (
            '        "你是决策人「"\n            + dm.id().value()\n',
            '        "你是决策人「"\n            + ""\n',
        ),
    ],
}


def main() -> int:
    name, path = sys.argv[1], sys.argv[2]
    edits = MUTANTS[name]
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    for old, new in edits:
        count = text.count(old)
        if count != 1:
            print(f"VOID 变异体 {name} 的锚点命中 {count} 次（必须恰好 1 次）", file=sys.stderr)
            return 3
        text = text.replace(old, new)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
