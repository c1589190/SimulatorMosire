import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""        ? window.SimosPanels.decisionSubpageVisibility(state.decisionSubpage)""",
"""        ? { view: true, approval: true }""")
