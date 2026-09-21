import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""    if (due === false) {
      return "非待决";
    }
    return "—";
  }""",
"""    if (due === false) {
      return "非待决";
    }
    return "非待决";
  }""")
