package xacro.lemminx.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;

/**
 * Static xacro model of a single file: definitions, uses and expressions, with source offsets.
 * Built purely from the LemMinX DOM (which is error-tolerant), so it works on incomplete buffers.
 */
public final class XacroDoc {

  public static final String PREFIX = "xacro:";
  public static final String NAMESPACE = "http://www.ros.org/wiki/xacro";
  public static final Set<String> BUILTIN_TAGS =
      Set.of(
          "macro",
          "property",
          "include",
          "if",
          "unless",
          "arg",
          "element",
          "attribute",
          "insert_block",
          "call");

  public record MacroDef(
      String name,
      List<MacroParam> params,
      DOMElement element,
      DOMAttr nameAttr,
      DOMAttr paramsAttr,
      String doc,
      DOMElement enclosingMacro,
      XacroDoc owner) {
    public MacroParam param(String n) {
      for (MacroParam p : params) {
        if (p.name().equals(n)) {
          return p;
        }
      }
      return null;
    }

    public String signature() {
      StringBuilder sb = new StringBuilder("xacro:macro ").append(name).append('(');
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(params.get(i).display());
      }
      return sb.append(')').toString();
    }
  }

  public record PropertyDef(
      String name,
      String value,
      boolean block,
      String scope,
      DOMElement element,
      DOMAttr nameAttr,
      DOMElement enclosingMacro,
      XacroDoc owner) {
    /** Visible outside its defining macro? */
    public boolean isGlobal() {
      return enclosingMacro == null || "global".equals(scope) || "parent".equals(scope);
    }
  }

  public record ArgDef(
      String name, String defaultValue, DOMElement element, DOMAttr nameAttr, XacroDoc owner) {}

  public record IncludeDef(
      String filename, String ns, boolean optional, DOMElement element, DOMAttr filenameAttr) {}

  public record MacroCall(String name, DOMElement element) {}

  /**
   * An occurrence of {@code ${...}} or {@code $(...)}.
   *
   * @param absStart absolute document offset of the '$'
   * @param bodyAbs absolute document offset of the first body character
   * @param attr the attribute containing it, or null for text content
   */
  public record ExprOcc(
      ExprScanner.Segment seg,
      int absStart,
      int bodyAbs,
      DOMElement element,
      DOMAttr attr,
      DOMElement enclosingMacro) {
    public int absEnd() {
      return absStart + (seg.end() - seg.start());
    }

    public boolean contains(int offset) {
      return offset >= absStart && offset <= absEnd();
    }
  }

  public final Path path;
  public final String uri;
  public final DOMDocument dom;
  public final List<MacroDef> macros = new ArrayList<>();
  public final List<PropertyDef> properties = new ArrayList<>();
  public final List<ArgDef> args = new ArrayList<>();
  public final List<IncludeDef> includes = new ArrayList<>();
  public final List<MacroCall> calls = new ArrayList<>();
  public final List<ExprOcc> exprs = new ArrayList<>();
  public final boolean isXacro;

  private XacroDoc(Path path, DOMDocument dom) {
    this.path = path;
    this.uri = dom.getDocumentURI();
    this.dom = dom;
    this.isXacro = detectXacro(dom);
  }

  public static XacroDoc build(Path path, DOMDocument dom) {
    XacroDoc d = new XacroDoc(path, dom);
    for (DOMNode child : dom.getChildren()) {
      d.walk(child, null);
    }
    return d;
  }

  public static boolean detectXacro(DOMDocument dom) {
    String uri = dom.getDocumentURI();
    if (uri != null && uri.endsWith(".xacro")) {
      return true;
    }
    DOMElement root = dom.getDocumentElement();
    if (root == null) {
      return false;
    }
    if (root.hasAttribute("xmlns:xacro")) {
      return true;
    }
    return dom.getText().contains("<xacro:");
  }

  public static boolean isXacroTag(DOMElement e) {
    String t = e.getTagName();
    return t != null && t.startsWith(PREFIX);
  }

  /** Local xacro tag name ("macro", "foo" for a call), or null if not a xacro element. */
  public static String xacroLocal(DOMElement e) {
    String t = e.getTagName();
    return t != null && t.startsWith(PREFIX) ? t.substring(PREFIX.length()) : null;
  }

  public static boolean isBuiltin(DOMElement e) {
    String l = xacroLocal(e);
    return l != null && BUILTIN_TAGS.contains(l);
  }

  private void walk(DOMNode node, DOMElement macro) {
    if (node.isElement()) {
      DOMElement e = (DOMElement) node;
      for (DOMAttr a : e.getAttributeNodes() == null ? List.<DOMAttr>of() : e.getAttributeNodes()) {
        scanAttr(e, a, macro);
      }
      String local = xacroLocal(e);
      DOMElement childMacro = macro;
      if (local != null) {
        switch (local) {
          case "macro" -> {
            DOMAttr nameAttr = e.getAttributeNode("name");
            DOMAttr paramsAttr = e.getAttributeNode("params");
            String name = nameAttr == null ? null : nameAttr.getValue();
            if (name != null && name.startsWith(PREFIX)) {
              name = name.substring(PREFIX.length());
            }
            if (name != null && !name.isEmpty()) {
              macros.add(
                  new MacroDef(
                      name,
                      MacroParam.parse(paramsAttr == null ? null : paramsAttr.getValue()),
                      e,
                      nameAttr,
                      paramsAttr,
                      docComment(e),
                      macro,
                      this));
            }
            childMacro = e;
          }
          case "property" -> {
            DOMAttr nameAttr = e.getAttributeNode("name");
            String name = nameAttr == null ? null : nameAttr.getValue();
            if (name != null && !name.isEmpty() && !"true".equals(e.getAttribute("remove"))) {
              String value =
                  e.hasAttribute("value") ? e.getAttribute("value") : e.getAttribute("default");
              boolean block = !e.hasAttribute("value") && !e.hasAttribute("default");
              properties.add(
                  new PropertyDef(
                      name, value, block, e.getAttribute("scope"), e, nameAttr, macro, this));
            }
          }
          case "arg" -> {
            DOMAttr nameAttr = e.getAttributeNode("name");
            if (nameAttr != null && nameAttr.getValue() != null) {
              args.add(
                  new ArgDef(nameAttr.getValue(), e.getAttribute("default"), e, nameAttr, this));
            }
          }
          case "include" -> {
            DOMAttr f = e.getAttributeNode("filename");
            includes.add(
                new IncludeDef(
                    f == null ? null : f.getValue(),
                    e.getAttribute("ns"),
                    "true".equals(e.getAttribute("optional")),
                    e,
                    f));
          }
          default -> {
            if (!BUILTIN_TAGS.contains(local)) {
              calls.add(new MacroCall(local, e));
            }
          }
        }
      }
      for (DOMNode c : e.getChildren()) {
        walk(c, childMacro);
      }
    } else if (node.isText()) {
      DOMElement parent = node.getParentElement();
      if (parent == null) {
        return;
      }
      String text =
          dom.getText().substring(node.getStart(), Math.min(node.getEnd(), dom.getText().length()));
      for (ExprScanner.Segment s : ExprScanner.scan(text)) {
        exprs.add(
            new ExprOcc(
                s,
                node.getStart() + s.start(),
                node.getStart() + s.bodyStart(),
                parent,
                null,
                macro));
      }
    }
  }

  private void scanAttr(DOMElement e, DOMAttr a, DOMElement macro) {
    if (a.getNodeAttrValue() == null || a.getValue() == null) {
      return;
    }
    int valueStart = a.getNodeAttrValue().getStart() + 1; // skip quote
    String raw =
        dom.getText()
            .substring(valueStart, Math.max(valueStart, a.getNodeAttrValue().getEnd() - 1));
    for (ExprScanner.Segment s : ExprScanner.scan(raw)) {
      exprs.add(new ExprOcc(s, valueStart + s.start(), valueStart + s.bodyStart(), e, a, macro));
    }
  }

  private String docComment(DOMElement e) {
    DOMNode prev = (DOMNode) e.getPreviousSibling();
    StringBuilder sb = new StringBuilder();
    while (prev != null) {
      if (prev.isComment()) {
        String t = dom.getText().substring(prev.getStart(), prev.getEnd());
        if (t.startsWith("<!--")) {
          t = t.substring(4);
        }
        if (t.endsWith("-->")) {
          t = t.substring(0, t.length() - 3);
        }
        sb.insert(
            0,
            t.strip().lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("") + "\n");
      } else if (prev.isText()) {
        String t = dom.getText().substring(prev.getStart(), prev.getEnd());
        if (!t.isBlank() || t.chars().filter(ch -> ch == '\n').count() > 1) {
          break; // blank line separates the comment from the macro
        }
      } else {
        break;
      }
      prev = (DOMNode) prev.getPreviousSibling();
    }
    String s = sb.toString().strip();
    return s.isEmpty() ? null : s;
  }

  public ExprOcc exprAt(int offset) {
    for (ExprOcc o : exprs) {
      if (o.contains(offset)) {
        return o;
      }
    }
    return null;
  }

  /** Enclosing xacro:macro elements of a node, innermost first. */
  public static List<DOMElement> enclosingMacros(DOMNode node) {
    List<DOMElement> out = new ArrayList<>();
    DOMElement p = node.isElement() ? (DOMElement) node : node.getParentElement();
    while (p != null) {
      if ("xacro:macro".equals(p.getTagName())) {
        out.add(p);
      }
      p = p.getParentElement();
    }
    return out;
  }
}
