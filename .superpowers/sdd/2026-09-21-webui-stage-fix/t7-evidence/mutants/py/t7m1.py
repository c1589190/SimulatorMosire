import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""    return kinds.map(function (kind) {
      var list = buckets[kind].slice().sort(function (a, b) {""",
"""    kinds = [""];
    buckets = { "": (makers || []).slice() };
    return kinds.map(function (kind) {
      var list = buckets[kind].slice().sort(function (a, b) {""")
