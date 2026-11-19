# Xacro for Zed

Adds a **Xacro** language (`*.xacro`, `*.urdf`) to Zed and runs the stock
[Eclipse LemMinX](https://github.com/eclipse-lemminx/lemminx) language server with the
lemminx-xacro extension on its classpath:

```
java -cp org.eclipse.lemminx-<v>-uber.jar:lemminx-xacro-<v>.jar org.eclipse.lemminx.XMLServerLauncher
```

Plain `*.xml` files are untouched: they keep using the [XML extension](https://github.com/sweetppro/zed-xml)'s
native LemMinX, so the two servers never run on the same file.

- LemMinX is downloaded from Eclipse's Maven repository at a pinned version and SHA-256.
- The extension jar comes from the latest [GitHub release](https://github.com/lopatoj/lemminx-xacro/releases)
  of this repository (published by `.github/workflows/release.yml` on a `v*` tag).
- **Requires Java 17+** (`java` on `PATH`, or `JAVA_HOME`). For the "real xacro" diagnostics,
  Python with xacro installed (a ROS install) is also needed.

## Settings

```jsonc
"lsp": {
  "lemminx-xacro": {
    // passed to LemMinX (both as initializationOptions.settings and workspace configuration)
    "settings": {
      "xml": { "xacro": { "python": { "setupScript": "/opt/ros/jazzy/setup.bash" } } }
    },
    // optional: extra JVM arguments
    "binary": { "arguments": ["-Xmx1g"] }
  }
}
```

All `xml.*` LemMinX settings work too (formatting, validation, ...). See the main
[README](../../README.md#settings) for the `xml.xacro.*` options.

## Development

1. Build the jars: `mvn -Puber package -DskipTests` (repository root).
2. In Zed, run **zed: install dev extension** and pick this `clients/zed` folder (needs Rust via rustup;
   Zed adds the WebAssembly target itself).
3. Until a release exists, point the server at your local build. A `binary.path` replaces the whole
   command:

```jsonc
"lsp": {
  "lemminx-xacro": {
    "binary": {
      "path": "java",
      "arguments": ["-jar", "/path/to/lemminx-xacro/target/lemminx-xacro-0.1.0-SNAPSHOT-uber.jar"]
    }
  }
}
```

4. Remove any `file_types` entry that maps `xacro`/`urdf` to XML — user `file_types` take precedence
   over this extension's language, which would send those files back to the XML extension.
5. Logs: command palette → *language server logs* → `lemminx-xacro`.

Run the unit tests with `cargo test`.

## Publishing

Open a PR to [zed-industries/extensions](https://github.com/zed-industries/extensions) adding this
repository as a submodule with `path = "clients/zed"`. The registry requires an OSI license file in the
repository. The query files in `languages/xacro/` are adapted from zed-xml (MIT, see
`languages/xacro/LICENSE-zed-xml`).
