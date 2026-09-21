import sys, pathlib
mid, path = sys.argv[1], pathlib.Path(sys.argv[2])
t = path.read_text(encoding="utf-8")
def sub(old, new, n=1):
    global t
    c = t.count(old)
    if c != n:
        sys.exit("match %d (want %d): %r" % (c, n, old[:70]))
    t = t.replace(old, new)
if mid == "sx1":
    # 删掉整条通知栏元素（“通知栏被删 ⇒ 新断言应红”）
    sub('    <div id="notifications" class="notify-bar" hidden aria-live="polite" aria-label="待批审批"></div>\n', '')
elif mid == "sx2r":
    sub('const MIN_TESTS = 96;', 'const MIN_TESTS = 90;')
elif mid == "sx2c":
    sub('const MIN_ASSERTIONS = 96;', 'const MIN_ASSERTIONS = 90;')
elif mid == "sx3":
    # 停用一条断言（去掉一条 test(...) ⇒ 计数跌破下界）
    sub('test("summaryText-shows-the-count"', 'xtest("summaryText-shows-the-count"')
else:
    sys.exit("unknown id")
path.write_text(t, encoding="utf-8")
print("mutant=%s ok" % mid)
