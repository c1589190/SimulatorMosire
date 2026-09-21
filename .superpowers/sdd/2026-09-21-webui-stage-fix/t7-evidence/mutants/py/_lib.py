import sys

def replace_once(path, old, new):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    if text.count(old) != 1:
        sys.stderr.write("needle count != 1: %d\n" % text.count(old))
        sys.exit(1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new, 1))
