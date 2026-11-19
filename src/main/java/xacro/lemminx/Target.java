package xacro.lemminx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Range;
import xacro.lemminx.model.ExprScanner;
import xacro.lemminx.model.PyRefs;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ExprOcc;
import xacro.lemminx.model.XacroDoc.IncludeDef;
import xacro.lemminx.model.XacroDoc.MacroDef;

/** "What symbol is at this offset?" — shared by definition, hover and references. */
public final class Target {

  public enum Kind {
    MACRO_CALL,
    MACRO_PARAM_ATTR,
    NAME,
    SUBST_ARG,
    SUBST_FIND,
    INCLUDE,
    INSERT_BLOCK
  }

  public final Kind kind;
  public final String name;
  public final int start;
  public final int end;
  public final DOMElement element;
  public final DOMNode context;
  public IncludeDef include;
  public MacroDef macro;

  Target(Kind kind, String name, int start, int end, DOMElement element, DOMNode context) {
    this.kind = kind;
    this.name = name;
    this.start = start;
    this.end = end;
    this.element = element;
    this.context = context;
  }

  public Range range(XacroDoc doc) {
    return XMLPositionUtility.createRange(start, end, doc.dom);
  }

  public static Target at(XacroDoc doc, int offset) {
    DOMNode node = doc.dom.findNodeAt(offset);
    // 1) inside ${...} / $(...)
    ExprOcc occ = doc.exprAt(offset);
    if (occ != null) {
      Target t = inExpr(doc, occ, offset);
      if (t != null) {
        return t;
      }
    }
    if (node == null || !node.isElement()) {
      return null;
    }
    DOMElement e = (DOMElement) node;
    String local = XacroDoc.xacroLocal(e);
    // 2) on a tag name
    int nameStart = e.getStart() + 1;
    int nameEnd = nameStart + (e.getTagName() == null ? 0 : e.getTagName().length());
    if (local != null
        && offset >= nameStart
        && offset <= nameEnd
        && !XacroDoc.BUILTIN_TAGS.contains(local)) {
      return new Target(Kind.MACRO_CALL, local, nameStart, nameEnd, e, e);
    }
    if (e.getEndTagOpenOffset() >= 0 && local != null && !XacroDoc.BUILTIN_TAGS.contains(local)) {
      int es = e.getEndTagOpenOffset() + 2;
      if (offset >= es && offset <= es + e.getTagName().length()) {
        return new Target(Kind.MACRO_CALL, local, es, es + e.getTagName().length(), e, e);
      }
    }
    // 3) attributes
    DOMAttr attr = attrAt(e, offset);
    if (attr == null) {
      return null;
    }
    boolean onName =
        attr.getNodeAttrName() != null
            && offset >= attr.getNodeAttrName().getStart()
            && offset <= attr.getNodeAttrName().getEnd();
    if (local != null && !XacroDoc.BUILTIN_TAGS.contains(local) && onName) {
      Target t =
          new Target(
              Kind.MACRO_PARAM_ATTR,
              attr.getName(),
              attr.getNodeAttrName().getStart(),
              attr.getNodeAttrName().getEnd(),
              e,
              e);
      t.macro = null;
      return t;
    }
    if (onName || attr.getNodeAttrValue() == null) {
      return null;
    }
    int vs = attr.getNodeAttrValue().getStart() + 1;
    int ve = attr.getNodeAttrValue().getEnd() - 1;
    if ("include".equals(local) && "filename".equals(attr.getName())) {
      Target t = new Target(Kind.INCLUDE, attr.getValue(), vs, ve, e, e);
      for (IncludeDef inc : doc.includes) {
        if (inc.element() == e) {
          t.include = inc;
        }
      }
      return t;
    }
    if ("insert_block".equals(local) && "name".equals(attr.getName())) {
      return new Target(Kind.INSERT_BLOCK, attr.getValue(), vs, ve, e, e);
    }
    if ("call".equals(local)
        && "macro".equals(attr.getName())
        && attr.getValue() != null
        && !attr.getValue().contains("$")) {
      return new Target(Kind.MACRO_CALL, attr.getValue(), vs, ve, e, e);
    }
    return null;
  }

  private static DOMAttr attrAt(DOMElement e, int offset) {
    if (e.getAttributeNodes() == null) {
      return null;
    }
    for (DOMAttr a : e.getAttributeNodes()) {
      int s = a.getStart();
      int end = a.getEnd();
      if (offset >= s && offset <= end) {
        return a;
      }
    }
    return null;
  }

  private static Target inExpr(XacroDoc doc, ExprOcc occ, int offset) {
    String body = occ.seg().body();
    int rel = offset - occ.bodyAbs();
    if (occ.seg().kind() == ExprScanner.Kind.EXPR) {
      for (PyRefs.Ref r : PyRefs.scan(body)) {
        if (rel >= r.start() && rel <= r.end()) {
          return new Target(
              Kind.NAME,
              r.name(),
              occ.bodyAbs() + r.start(),
              occ.bodyAbs() + r.end(),
              occ.element(),
              occ.attr() != null ? occ.attr().getOwnerElement() : occ.element());
        }
      }
      return null;
    }
    // $(cmd arg ...)
    String trimmed = body.stripLeading();
    int lead = body.length() - trimmed.length();
    String[] parts = trimmed.split("\\s+", 3);
    if (parts.length < 2) {
      return null;
    }
    int argStart = occ.bodyAbs() + body.indexOf(parts[1], lead + parts[0].length());
    int argEnd = argStart + parts[1].length();
    if (offset < argStart - 1 || offset > argEnd) {
      return null;
    }
    return switch (parts[0]) {
      case "arg" ->
          new Target(Kind.SUBST_ARG, parts[1], argStart, argEnd, occ.element(), occ.element());
      case "find" ->
          new Target(Kind.SUBST_FIND, parts[1], argStart, argEnd, occ.element(), occ.element());
      default -> null;
    };
  }

  /** Resolve a (possibly namespaced/dotted) name against the scope. */
  public static Resolved resolveName(XacroDoc doc, Scope scope, String dotted, DOMNode at) {
    Resolved r = new Resolved();
    String[] parts = dotted.split("\\.");
    // macro params of enclosing macros
    for (Map.Entry<MacroDef, String> e : Scope.paramsAt(doc, at)) {
      if (e.getValue().equals(parts[0])) {
        r.param = e.getKey();
        r.paramName = parts[0];
        return r;
      }
    }
    MacroDef ctx = scope.contextParams.get(parts[0]);
    if (ctx != null && scope.lookupProperty(parts[0], at).isEmpty()) {
      r.param = ctx;
      r.paramName = parts[0];
      return r;
    }
    // longest matching dotted prefix: ns.sub.prop, then prop (yaml dict access)
    for (int n = parts.length; n >= 1; n--) {
      String cand = String.join(".", java.util.Arrays.copyOf(parts, n));
      var props = scope.lookupProperty(cand, at);
      if (!props.isEmpty()) {
        r.properties = props;
        r.matched = cand;
        return r;
      }
    }
    if (scope.namespaces.contains(parts[0])) {
      r.namespace = parts[0];
      return r;
    }
    if (scope.macros.containsKey(dotted)) {
      r.macros = scope.lookupMacro(dotted);
    }
    return r;
  }

  public static final class Resolved {
    public List<XacroDoc.PropertyDef> properties = new ArrayList<>();
    public List<MacroDef> macros = new ArrayList<>();
    public MacroDef param;
    public String paramName;
    public String namespace;
    public String matched;

    public boolean found() {
      return !properties.isEmpty() || param != null || namespace != null || !macros.isEmpty();
    }
  }
}
