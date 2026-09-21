import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""    var state = decisionSubpageState(id);
    return { view: state.ok && state.id === "view", approval: state.ok && state.id === "approval" };""",
"""    var state = decisionSubpageState(id);
    void state;
    return { view: true, approval: true };""")
