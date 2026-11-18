Minimal VS Code client. It has no code of its own: it contributes the extension jar to
Red Hat's XML extension through `xml.javaExtensions`, and maps `.xacro` / `.urdf` to XML.

    (cd ../.. && mvn package) && cp ../../target/lemminx-xacro-*-SNAPSHOT.jar server/
    npx @vscode/vsce package --allow-missing-repository

Then install the produced .vsix. vscode-xml switches to its Java server automatically when an
extension jar is present.
