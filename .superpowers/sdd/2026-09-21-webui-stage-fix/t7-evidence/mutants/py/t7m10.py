import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""    return postJson("/approvals/" + encodeURIComponent(id), {""",
"""    return postJson("/evil/" + encodeURIComponent(id), {""")
