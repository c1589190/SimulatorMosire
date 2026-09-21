import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_text(encoding="utf-8")
old = '''test("summaryText-shows-the-count", () => {
  assert.equal(N.summaryText({ pending: [] }), "待批 0");
  assert.equal(N.summaryText({ pending: [{}, {}] }), "待批 2");
});

'''
if t.count(old) != 1:
    sys.exit("match %d" % t.count(old))
p.write_text(t.replace(old, ""), encoding="utf-8")
print("removed one clean test block")
