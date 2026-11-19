package xacro.lemminx.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Documentation and attribute sets for xacro's built-in tags. */
public final class XacroTags {

  public record Tag(String name, String doc, List<String> attrs, String snippet) {}

  public static final Map<String, Tag> TAGS = new LinkedHashMap<>();

  private static void add(String name, String doc, List<String> attrs, String snippet) {
    TAGS.put(name, new Tag(name, doc, attrs, snippet));
  }

  static {
    add(
        "property",
        """
				Define a property. Use `${name}` to reference it.
				- `value`: evaluated lazily (unless `lazy_eval="false"`)
				- `default`: only set if not yet defined
				- `scope`: `parent` or `global` to export from a macro
				- `remove="true"`: delete the property
				- no value: a *block property*, inserted with `<xacro:insert_block name="..."/>`""",
        List.of("name", "value", "default", "scope", "lazy_eval", "remove"),
        "xacro:property name=\"$1\" value=\"$2\"/>$0");
    add(
        "macro",
        """
				Define a macro, called as `<xacro:NAME param="..."/>`.
				`params`: space-separated list. `p:=default`, `p:=^` (forward from caller scope), \
				`p:=^|default`, `*block` (one element child), `**block` (insert only its children).""",
        List.of("name", "params"),
        "xacro:macro name=\"$1\" params=\"$2\">\n\t$0\n</xacro:macro>");
    add(
        "include",
        """
				Include another xacro file. `filename` may use `$(find pkg)`, `$(arg ...)`, `${...}` and globs.
				`ns="foo"`: put its macros/properties in namespace `foo` (`xacro:foo.macro`, `${foo.prop}`).
				`optional="true"`: ignore missing files.""",
        List.of("filename", "ns", "optional"),
        "xacro:include filename=\"$(find ${1:pkg})/${2:urdf/file.xacro}\"/>$0");
    add(
        "if",
        "Keep the content only if `value` evaluates to true (true/false/1/0 or an expression).",
        List.of("value"),
        "xacro:if value=\"\\${$1}\">\n\t$0\n</xacro:if>");
    add(
        "unless",
        "Keep the content only if `value` evaluates to false.",
        List.of("value"),
        "xacro:unless value=\"\\${$1}\">\n\t$0\n</xacro:unless>");
    add(
        "arg",
        "Declare a command line argument `name:=value`, used as `$(arg name)` or `xacro.arg('name')`.",
        List.of("name", "default"),
        "xacro:arg name=\"$1\" default=\"$2\"/>$0");
    add(
        "element",
        "Create an element whose tag name is computed: `xacro:name=\"${...}\"`.",
        List.of("xacro:name"),
        "xacro:element xacro:name=\"$1\">$0</xacro:element>");
    add(
        "attribute",
        "Add an attribute with a computed name/value to the parent element.",
        List.of("name", "value"),
        "xacro:attribute name=\"$1\" value=\"$2\"/>$0");
    add(
        "insert_block",
        "Insert a block parameter (`*name` / `**name`) or a block property.",
        List.of("name"),
        "xacro:insert_block name=\"$1\"/>$0");
    add(
        "call",
        "Call a macro whose name is computed: `<xacro:call macro=\"${name}\" .../>`.",
        List.of("macro"),
        "xacro:call macro=\"$1\"/>$0");
  }

  private XacroTags() {}
}
