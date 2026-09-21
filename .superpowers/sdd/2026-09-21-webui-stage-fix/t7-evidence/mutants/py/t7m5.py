import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""      id: "decision",
      label: "决策",
      writes: [],""",
"""      id: "decision",
      label: "决策",
      writes: ["sd.StartDecision"],""")
