import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from _lib import replace_once
replace_once(sys.argv[1],
"""    return (regions || [])
      .filter(function (region) {
        return nationTagOf(region) === want;
      })
      .map(function (region) {
        return String(region.id);
      })
      .sort(function (a, b) {
        return a.localeCompare(b);
      });""",
"""    var hits = (regions || []).filter(function (region) {
      return nationTagOf(region) === want;
    });
    return hits.length ? [String(hits[0].id)] : [];""")
