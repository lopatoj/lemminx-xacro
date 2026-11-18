# lemminx-xacro

Xacro and URDF language support as an extension to [LemMinX](https://github.com/eclipse-lemminx/lemminx), the Eclipse XML language server. Since it builds on LemMinX, it works in any editor that runs LemMinX: VS Code (vscode-xml), Neovim, Helix, Emacs, Eclipse and others.

## Features

**Xacro**
- **Go to definition:** macro calls → `xacro:macro`, `${prop}` → `xacro:property` (also across includes and from fragments to their includer), macro params, `$(arg x)` → `xacro:arg`, `$(find pkg)` → `package.xml`, include filenames → files, `insert_block` → block param.
- **Hover:** macro signature and doc comment, property definition plus its *evaluated value* from the last real xacro run, macro param details, arg defaults, package paths, include targets.
- **Completion:**
  - `xacro:` tags (snippets);
  - macros in scope, with required params pre-filled;
  - macro params as attributes;
  - inside `${…}`: properties, namespaces (`ns.`), `math.*`, `xacro.*`, `python.*`;
  - inside `$(…)`: substitution commands, package names after `$(find `, args after `$(arg `.
- **Diagnostics, as you type:** unknown macro, invalid or missing macro params, wrong number of blocks, undefined properties, missing include files, unknown packages, undeclared args, invalid property names, unterminated `${`, and missing `package://` mesh files.
- **Diagnostics from real xacro, on save:** the extension runs your installed xacro on each root file in the background. It reports expansion errors and URDF problems in the *expanded* robot (duplicate names, dangling joint links, ros2_control joints that don't exist, …) at the right source line. Errors inside macros point at both the macro body and the call site.
- **Document links** for includes and `package://` URIs.

**URDF**: completion and hover for core URDF, `<gazebo>` (Classic + gz), transmissions (both styles) and `<ros2_control>`. Plain `.urdf` files get semantic checks.

See [docs/DESIGN.md](docs/DESIGN.md) for how it works, including how xacro's Python semantics are used from Java and the survey of existing URDF schemas.

## Build

Requires JDK 17+ (Maven is fetched by `./mvnw`).

```bash
./mvnw package          # target/lemminx-xacro-<v>.jar      (extension only, for vscode-xml)
./mvnw -Puber package   # target/lemminx-xacro-<v>-uber.jar (LemMinX + extension, standalone server)
```

Tests: `./mvnw test`. The end-to-end LSP smoke test is `python3 scripts/lsp_smoke.py` (after `-Puber package`). To scan your own workspaces for false positives:

```bash
./mvnw test -Dtest=CorpusTest -Dcorpus=$HOME/ws1,$HOME/ws2
```

## Editor setup

LemMinX's native binary can't load extensions, so Java is required.

**VS Code** (with Red Hat's XML extension): `clients/vscode` is a minimal extension that contributes the jar through `xml.javaExtensions` and associates `*.xacro` / `*.urdf` with XML. Copy the built jar to `clients/vscode/server/` and package or run it, or just add the jar path to your settings:

```jsonc
"xml.extension.jars": ["/path/to/lemminx-xacro-0.1.0-SNAPSHOT.jar"],
"files.associations": { "*.xacro": "xml", "*.urdf": "xml" }
```

**Neovim** (nvim-lspconfig `lemminx`):

```lua
require('lspconfig').lemminx.setup {
  cmd = { 'java', '-jar', '/path/to/lemminx-xacro-0.1.0-SNAPSHOT-uber.jar' },
  filetypes = { 'xml', 'xacro', 'urdf' },
  settings = { xml = { xacro = { python = { setupScript = '/opt/ros/jazzy/setup.bash' } } } },
}
vim.filetype.add { extension = { xacro = 'xml', urdf = 'xml' } }
```

**Helix** (`languages.toml`):

```toml
[language-server.lemminx-xacro]
command = "java"
args = ["-jar", "/path/to/lemminx-xacro-0.1.0-SNAPSHOT-uber.jar"]

[[language]]
name = "xml"
file-types = ["xml", "xacro", "urdf"]
language-servers = ["lemminx-xacro"]
```

## Settings

| Setting | Default | |
|---|---|---|
| `xml.xacro.packagePaths` | `[]` | Extra directories scanned for `package.xml`. Workspace folders and `AMENT_PREFIX_PATH` are always used. |
| `xml.xacro.args` | `{}` | `name: value` mappings for `$(arg)`, as on the command line. |
| `xml.xacro.python.enabled` | `true` | Run real xacro on open/save. |
| `xml.xacro.python.executable` | `python3` | |
| `xml.xacro.python.setupScript` | auto | Sourced before running Python if the server wasn't started from a ROS-sourced shell. Defaults to the newest `/opt/ros/*/setup.bash`. |
| `xml.xacro.python.timeoutMs` | `20000` | |
