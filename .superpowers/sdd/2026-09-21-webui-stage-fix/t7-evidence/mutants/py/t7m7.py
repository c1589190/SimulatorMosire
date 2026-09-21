import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""      var unit = byId[cursor];
      var parent = unit ? unit.parent : null;
      cursor = parent === null || parent === undefined || parent === "" ? null : String(parent);""",
"""      cursor = null;""")
