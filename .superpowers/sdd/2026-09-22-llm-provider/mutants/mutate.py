#!/usr/bin/env python3
"""Apply exactly-one literal/regex replacement; fail loudly if the pattern is absent or ambiguous."""
import re
import sys

path, mode, old, new = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(path, "r", encoding="utf-8") as handle:
    text = handle.read()

if mode == "literal":
    count = text.count(old)
    if count != 1:
        print("PATTERN-COUNT=%d" % count)
        sys.exit(2)
    mutated = text.replace(old, new, 1)
else:
    matches = re.findall(old, text, flags=re.DOTALL | re.MULTILINE)
    if len(matches) != 1:
        print("PATTERN-COUNT=%d" % len(matches))
        sys.exit(2)
    mutated = re.sub(old, new, text, count=1, flags=re.DOTALL | re.MULTILINE)

with open(path, "w", encoding="utf-8") as handle:
    handle.write(mutated)
print("OK")
