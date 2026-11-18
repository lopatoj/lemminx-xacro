"""
xacro sidecar for lemminx-xacro.

Runs the *real* xacro (whatever `import xacro` resolves to in this environment) on one root
file and reports, as a single JSON object on stdout:

  {"ok": bool,
   "error": {"message": str, "locations": [{"file", "line", "col", "what"}...]} | null,
   "warnings": [{"message", "file", "line", "col"}],
   "symbols": {"name": "repr", "ns.name": "repr", ...},   # evaluated global properties
   "files": [...],                                         # all files that took part
   "urdf": [{"message", "severity", "file", "line", "col"}]}  # semantic checks on the output

Source locations survive macro expansion: every element parsed by xacro is tagged with
(file, line, col) via a custom expat builder, and the tag is propagated through
minidom's cloneNode (used for every macro instantiation). So an error inside a macro
body, or a duplicate <link> produced by a macro, points at real source lines, together
with the chain of macro calls that produced it.

Usage: python3 -P sidecar.py <root-file> [<json-config>]
  json-config: {"args": {name: value}, "packages": {pkg: dir}}
  "packages" supplements ament_index for $(find pkg), so unbuilt workspace packages resolve.
"""

import io
import json
import os
import sys
import traceback
import weakref
import xml.dom.minidom as minidom
import xml.parsers.expat
from xml.dom import expatbuilder

result = {"ok": False, "error": None, "warnings": [], "symbols": {}, "files": [], "urdf": []}

try:
    import xacro
except Exception as e:  # pragma: no cover - environment problem
    result["error"] = {"message": "cannot import xacro: %s (is the ROS environment sourced?)" % e,
                       "locations": [], "import_error": True}
    print(json.dumps(result))
    sys.exit(0)

# ----------------------------------------------------------------------------- locations

LOC = weakref.WeakKeyDictionary()  # minidom node -> (file, line, col)
CALLS = weakref.WeakKeyDictionary()  # cloned node -> [locations of the macro calls that produced it]
CALLSTACK = []  # macro call nodes currently being expanded
FILES = []


class LocatingBuilder(expatbuilder.ExpatBuilderNS):
    """Expat builder that remembers where each element started."""

    def __init__(self, filename, *a, **kw):
        super().__init__(*a, **kw)
        self._filename = filename

    def start_element_handler(self, name, attributes):
        super().start_element_handler(name, attributes)
        p = self._parser
        LOC[self.curNode] = (self._filename, p.CurrentLineNumber, p.CurrentColumnNumber)


def _parse_located(text, filename):
    builder = LocatingBuilder(filename)
    return builder.parseString(text)


_orig_clone = minidom._clone_node


def _clone_node(node, deep, newOwnerDocument):
    # children are cloned through recursive calls to this (patched) module function,
    # so copying the node's own location is enough
    clone = _orig_clone(node, deep, newOwnerDocument)
    loc = LOC.get(node)
    if loc is not None:
        LOC[clone] = loc
        if CALLSTACK:
            CALLS[clone] = [l for l in (_loc_of(n) for n in CALLSTACK) if l] + CALLS.get(CALLSTACK[0], [])
    return clone


_orig_handle_macro_call = xacro.handle_macro_call


def _handle_macro_call(node, macros, symbols):
    CALLSTACK.append(node)
    try:
        return _orig_handle_macro_call(node, macros, symbols)
    finally:
        CALLSTACK.pop()


xacro.handle_macro_call = _handle_macro_call


minidom._clone_node = _clone_node

_orig_parse = xacro.parse


def _parse(inp, filename=None):
    if inp is None:
        path = os.path.join(xacro.root_dir, filename)
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except IOError as e:
            xacro.filestack.pop()
            raise xacro.XacroException(e.strerror + ": " + e.filename, exc=e)
        abspath = os.path.abspath(path)
        FILES.append(abspath)
        return _parse_located(text, abspath)
    return _orig_parse(inp, filename)


xacro.parse = _parse

# capture the top-level symbol table (process_doc creates it locally)
_captured = {}
_orig_eval_all = xacro.eval_all


def _eval_all(node, macros, symbols):
    if "symbols" not in _captured:
        _captured["symbols"] = symbols
    return _orig_eval_all(node, macros, symbols)


xacro.eval_all = _eval_all


def _loc_of(node):
    while node is not None:
        if node in LOC:
            return LOC[node]
        node = getattr(node, "parentNode", None)
    return None


def _describe(node):
    if node is None:
        return ""
    if node.nodeType == node.ELEMENT_NODE:
        return "<%s>" % node.tagName
    return node.nodeName


def _locations_from_traceback(tb):
    """Innermost-first list of located DOM nodes held in 'node'/'elt' locals of xacro frames."""
    frames = []
    while tb is not None:
        frames.append(tb.tb_frame)
        tb = tb.tb_next
    out, seen = [], set()
    for fr in reversed(frames):
        if fr.f_code.co_filename != xacro.__file__:
            continue
        for key in ("node", "elt"):
            n = fr.f_locals.get(key)
            if n is None or not hasattr(n, "nodeType"):
                continue
            loc = _loc_of(n)
            if loc is None:
                continue
            what = _describe(n)
            if fr.f_code.co_name == "handle_macro_call":
                what = "macro call " + what
            k = (loc, what)
            if k in seen:
                continue
            seen.add(k)
            out.append({"file": loc[0], "line": loc[1], "col": loc[2], "what": what})
    return out


# ----------------------------------------------------------------------------- URDF checks

def _urdf_checks(doc):
    issues = []

    def add(msg, node, severity="error"):
        loc = _loc_of(node)
        if loc:
            chain = []
            n = node
            while n is not None and not chain:
                chain = CALLS.get(n, [])
                n = getattr(n, "parentNode", None)
            issues.append({"message": msg, "severity": severity, "file": loc[0], "line": loc[1], "col": loc[2],
                           "calls": [{"file": c[0], "line": c[1], "col": c[2]} for c in reversed(chain)]})

    robot = doc.documentElement
    if robot is None or robot.tagName != "robot":
        return issues
    links, joints, children = {}, {}, set()
    for e in robot.childNodes:
        if e.nodeType != e.ELEMENT_NODE:
            continue
        if e.tagName in ("link", "joint"):
            name = e.getAttribute("name")
            table = links if e.tagName == "link" else joints
            if not name:
                add("<%s> without name" % e.tagName, e)
            elif name in table:
                add("duplicate %s name '%s' (first defined at %s:%s)" % (
                    e.tagName, name, os.path.basename((_loc_of(table[name]) or ("?",))[0]),
                    (_loc_of(table[name]) or ("?", "?"))[1]), e)
            else:
                table[name] = e
    for name, j in joints.items():
        jtype = j.getAttribute("type")
        if jtype not in ("revolute", "continuous", "prismatic", "fixed", "floating", "planar"):
            add("joint '%s' has invalid type '%s'" % (name, jtype), j)
        for rel in ("parent", "child"):
            els = [c for c in j.childNodes if c.nodeType == c.ELEMENT_NODE and c.tagName == rel]
            if not els:
                add("joint '%s' has no <%s>" % (name, rel), j)
                continue
            link = els[0].getAttribute("link")
            if rel == "child":
                children.add(link)
            if link not in links:
                add("joint '%s' references unknown %s link '%s'" % (name, rel, link), els[0])
        if jtype in ("revolute", "prismatic"):
            if not any(c.nodeType == c.ELEMENT_NODE and c.tagName == "limit" for c in j.childNodes):
                add("%s joint '%s' requires <limit>" % (jtype, name), j)
    roots = [l for l in links if l not in children]
    if links and len(roots) != 1:
        add("expected exactly one root link, found %d: %s" % (len(roots), ", ".join(sorted(roots)[:5])), robot,
            "warning")
    # ros2_control joints must exist
    for rc in robot.getElementsByTagName("ros2_control"):
        for j in rc.getElementsByTagName("joint"):
            if j.parentNode is rc and j.getAttribute("name") not in joints:
                add("ros2_control joint '%s' is not a URDF joint" % j.getAttribute("name"), j)
    for g in robot.getElementsByTagName("gazebo"):
        ref = g.getAttribute("reference")
        if ref and ref not in links and ref not in joints:
            add("gazebo reference '%s' is neither a link nor a joint" % ref, g, "warning")
    return issues


# ----------------------------------------------------------------------------- symbols

def _dump_symbols(table, prefix, out, depth=0):
    if table is None or depth > 3:
        return
    for key in list(dict.keys(table)):
        if key.startswith("**") or key.startswith("*") or key.startswith("_"):
            continue
        try:
            value = table[key]
        except Exception:
            continue
        if isinstance(value, xacro.NameSpace):
            _dump_symbols(value, prefix + key + ".", out, depth + 1)
            continue
        if hasattr(value, "nodeType"):
            continue
        r = repr(value)
        out[prefix + key] = r if len(r) <= 200 else r[:197] + "..."


# ----------------------------------------------------------------------------- main

def main():
    root = os.path.abspath(sys.argv[1])
    config = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    mappings = config.get("args", {})
    packages = config.get("packages", {})
    from xacro import substitution_args
    _orig_find = substitution_args._eval_find

    def _eval_find(pkg):
        if pkg in packages:
            return packages[pkg]
        return _orig_find(pkg)

    substitution_args._eval_find = _eval_find
    os.chdir(os.path.dirname(root))
    stderr = io.StringIO()
    old_stderr = sys.stderr
    sys.stderr = stderr
    doc = None
    try:
        doc = xacro.process_file(root, mappings=mappings)
        result["ok"] = True
    except xml.parsers.expat.ExpatError as e:
        f = FILES[-1] if FILES else root
        if xacro.filestack:
            f = os.path.abspath(xacro.filestack[-1] or root)
        result["error"] = {"message": "XML parse error: %s" % xml.parsers.expat.ErrorString(e.code),
                           "locations": [{"file": f, "line": e.lineno, "col": e.offset, "what": ""}]}
    except Exception as e:
        tb = sys.exc_info()[2]
        locs = _locations_from_traceback(tb)
        if not locs and xacro.filestack and xacro.filestack[-1]:
            locs = [{"file": os.path.abspath(xacro.filestack[-1]), "line": 1, "col": 0, "what": ""}]
        result["error"] = {"message": str(e) or type(e).__name__, "locations": locs,
                           "type": type(e).__name__}
    finally:
        sys.stderr = old_stderr
    for line in stderr.getvalue().splitlines():
        if line.strip() and not line.startswith(("when ", "in file:", "included from:", "instantiated from:")):
            result["warnings"].append({"message": line.strip()})
    _dump_symbols(_captured.get("symbols"), "", result["symbols"])
    result["files"] = sorted(set(FILES))
    if doc is not None:
        result["urdf"] = _urdf_checks(doc)


if __name__ == "__main__":
    try:
        main()
    except Exception:  # never let the sidecar crash without JSON
        result["error"] = {"message": "sidecar failure: " + traceback.format_exc(), "locations": []}
    sys.stdout.write(json.dumps(result))
