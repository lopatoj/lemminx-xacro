# Design notes

## 1. Architecture

```
            editor (VS Code / Neovim / Helix / Emacs …)
                         │ LSP
           ┌─────────────▼─────────────┐
           │ LemMinX (Java)            │  XML parsing, formatting, generic XML features
           │  └─ lemminx-xacro (SPI)   │
           │      ├─ XacroDoc          │  per-file model from LemMinX's tolerant DOM
           │      ├─ XacroWorkspace    │  include graph, package index, scope building
           │      ├─ participants      │  diagnostics, completion, hover, definition, links
           │      └─ XacroPythonRunner │──┐ on open/save, background
           └───────────────────────────┘  │
                                          ▼
                          python3 -P sidecar.py <root> {...}
                          imports the *installed* xacro, runs it
                          on the root file, reports JSON
```

There are two layers. They complement each other.

| | Static layer (Java) | Dynamic layer (Python sidecar) |
|---|---|---|
| When | every keystroke | on open/save, in the background |
| Input | unsaved buffers | files on disk |
| Semantics | approximation | exact: it *is* xacro |
| Gives | completion, go-to-definition, hover, fast diagnostics | expansion errors, evaluated property values, checks on the expanded URDF |

### Scope model (static)

A document's scope is built from three sources:

- **Its own definitions.** Properties defined inside a macro are only visible inside that macro, unless declared with `scope="parent"` or `scope="global"`.
- **Forward includes, transitively.** Includes with `ns="foo"` contribute `foo.`-prefixed names.
- **Context.** These are the files that include this one, transitively. Macro libraries such as `wheel.xacro` usually use properties defined by their includer, so a fragment opened on its own still resolves names. If the include sits inside a macro body, that macro's parameters and local properties are also visible. This matches xacro, which evaluates such an include in the macro's scope.

If an include can't be resolved statically, the scope is marked *incomplete*. This happens with a dynamic filename, an unknown package, or a missing file. "Undefined property" and "unknown macro" are then reported as warnings, not errors. They are also warnings inside a macro body when no includer is known.

## 2. Getting xacro's Python semantics into a Java server

Xacro has no spec: the behavior *is* `xacro/__init__.py`. `${}` contains real Python expressions. Lazy property evaluation, scoping and namespaces are all implemented in Python. The options:

| Option | Fidelity | Cost / risk | Verdict |
|---|---|---|---|
| **A. Reimplement in Java** (tokenizer + symbol resolution, maybe constant-folding) | Approximate | Moderate; drifts from upstream | **Used** for the always-on static layer: name resolution only, no evaluation |
| **B. Sidecar process running the user's real xacro** | Exact, including the user's xacro version and ROS env | ~150–300 ms per run; needs Python + xacro | **Used.** Monkey-patches add source locations (see below) |
| **C. GraalPy** (embed CPython-compatible Python 3 in the JVM, `org.graalvm.polyglot:python`) | Near-exact. Pure-Python xacro + PyYAML (pure fallback) should run; `$(find)` needs a shim | +100–200 MB of runtime, slow warm-up, GraalVM dependency | Worth it only for a zero-dependency bundle on machines without ROS. Could replace B behind the same interface |
| **D. Jython** | — | Python 2.7 only; xacro is Python 3 | Not viable |
| **E. Shell out to the `xacro` CLI** | Exact output | No locations (errors print file names only), no symbols | Strictly worse than B |
| **F. Persistent sidecar** (JSON-RPC, stays alive) | Same as B | Must reset xacro's module globals between runs; risk of state leaks | Possible later optimization. One process per run is simpler and fast enough |

### How the sidecar keeps source locations (`sidecar.py`)

Stock xacro uses `xml.dom.minidom`, which has no line numbers. The sidecar patches four functions *before* running `xacro.process_file`:

1. **`xacro.parse`**: parses with a subclass of `expatbuilder.ExpatBuilderNS` that records `(file, line, col)` for every element in a `WeakKeyDictionary`.
2. **`minidom._clone_node`**: copies the location to the clone. Every macro instantiation clones the macro body, so each expanded element keeps the location of the source line it came from.
3. **`xacro.handle_macro_call`**: maintains a stack of the macro calls being expanded. Clones record that stack, so an element produced by a macro also knows *which call site* produced it.
4. **`substitution_args._eval_find`**: checks the extension's package index first. `$(find pkg)` then works for workspace packages that haven't been built or installed.

On an exception, the sidecar walks the traceback for `node` and `elt` locals in xacro's frames. The innermost located node is the error site. Outer ones form the macro call chain, which becomes LSP `relatedInformation`. After a successful run, the sidecar:

- dumps the evaluated global symbol table, which hover shows as "= `0.1` *(from last xacro run)*";
- runs URDF checks on the **expanded** document: duplicate link/joint names, joint parent/child links that don't exist, number of root links, `<limit>` on revolute/prismatic joints, ros2_control joints that aren't URDF joints, and gazebo references to unknown links/joints. Each check is reported at the element's source line and, in the root file, at the macro call that produced it.

Results are tagged with a hash of each file's contents and dropped once the buffer is edited, so stale line numbers never show.

**Environment:** the sidecar runs `python3 -P`. Isolated mode (`-I`) would also drop `PYTHONPATH`, which ROS relies on. If `AMENT_PREFIX_PATH`/`ROS_DISTRO` aren't set, it sources `xml.xacro.python.setupScript`, or else the newest `/opt/ros/*/setup.bash`.

## 3. URDF vocabulary and schemas

I surveyed existing schemas in November 2026:

| Source | Covers | Notes |
|---|---|---|
| [ros/urdfdom `xsd/urdf.xsd`](https://github.com/ros/urdfdom/tree/master/xsd) | Core URDF, old PR2-style transmission, `gazebo` as lax `xs:any`, sensor/camera/ray | BSD-3, maintained. Strict: `<gazebo reference=…>` and `<ros2_control>` fail validation. No new-style transmission |
| urdfdom PR #223 (open) | Same plus ~135 doc annotations from the wiki, lax `xs:any` for xacro | Doesn't compile (namespace bug). The docs are worth harvesting |
| devrt/urdfdom `xsd-with-xacro` (2019) | urdf.xsd + xacro.xsd | Forces URDF into a namespace real files never use. Abandoned |
| space-concordia-robotics `xacro.xsd` (MIT) | Most xacro tags | Wrong namespace URI (`https://ros.org/wiki/xacro`) |
| CogniPilot/hcdformat `hcdf-ext-ros2-control.xsd` (Apache-2.0) | ros2_control | The only ros2_control XSD found. Wrapped for HCDF; needs adapting |
| rde-urdf `schemas/*.json` | Toy JSON Schemas | Not usable |
| sdformat `.sdf` descriptions | SDF, not URDF `<gazebo>` | The URDF extension tags exist only in `parser_urdf.cc` and the docs |

No existing schema covers URDF, ros2_control, gazebo, transmissions and xacro together. More fundamentally, **XSD validation doesn't fit xacro files.** Macro calls are arbitrary `<xacro:foo>` elements that can appear anywhere, and any attribute can be `${…}`. So this extension:

- drives completion and hover from its own vocabulary (`src/main/resources/xacro/lemminx/urdf-vocabulary.json`). It covers core URDF, both transmission styles, `<gazebo>` with the common Classic/gz extension tags, and `<ros2_control>` (hardware, plugin, joint, sensor, gpio, command and state interfaces, param). Context-specific entries use `parent/tag` keys (`ros2_control/joint` vs `transmission/joint` vs `joint`);
- runs URDF semantic checks on expanded output (sidecar) and on plain `.urdf` files (`UrdfChecks`).

**Next step for plain `.urdf` validation:** bundle a "URDF+" XSD generated from the same vocabulary, or urdfdom's XSD extended with `ros2_control` (adapted from hcdformat) plus `gazebo@reference` and `anyAttribute`. Bind it via `IExternalGrammarLocationProvider` for `*.urdf` only, never for `*.xacro`.

## 4. Roadmap

- References and rename for macros, properties and args across the workspace (the index already has what's needed).
- Expanded-URDF preview command (`xacro.expand`), using the sidecar's output.
- Static constant-folding for hover when Python isn't available (simple arithmetic and `math.*`).
- Semantic tokens for `${…}` and `$(…)`. Inlay hints with evaluated values.
- Generated URDF+ XSD for `.urdf` (see above). Doc strings harvested from urdfdom PR #223.
- Code actions: add missing required macro params, declare a missing `xacro:arg`, create a property.
- Workspace scan performance: watch files instead of rescanning on graph invalidation.
