package xacro.lemminx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ArgDef;
import xacro.lemminx.model.XacroDoc.MacroDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;

/**
 * Everything visible from one document: its own definitions, those of its (transitive) includes —
 * prefixed with {@code ns.} for namespaced includes — and those of the files that include it
 * ("context"), since fragment files commonly rely on their includer's properties.
 */
public final class Scope {

  public final Map<String, List<PropertyDef>> properties = new LinkedHashMap<>();
  public final Map<String, List<MacroDef>> macros = new LinkedHashMap<>();
  public final Map<String, List<ArgDef>> args = new LinkedHashMap<>();
  public final Set<String> namespaces = new LinkedHashSet<>();

  /** Some include could not be resolved statically: missing names may come from there. */
  public boolean incomplete;

  /** The document is included by at least one other file. */
  public boolean hasContext;

  /** Files whose definitions contributed to this scope. */
  public final Set<XacroDoc> docs = new LinkedHashSet<>();

  /** Macro-local properties visible here because we are included from inside that macro. */
  public final Set<PropertyDef> exported = new LinkedHashSet<>();

  /** Parameters of macros whose body includes us (directly or transitively). */
  public final Map<String, MacroDef> contextParams = new LinkedHashMap<>();

  void add(XacroDoc d, String prefix, boolean ownDoc) {
    docs.add(d);
    for (PropertyDef p : d.properties) {
      if (ownDoc || p.isGlobal()) {
        properties.computeIfAbsent(prefix + p.name(), k -> new ArrayList<>()).add(p);
      }
    }
    for (MacroDef m : d.macros) {
      macros.computeIfAbsent(prefix + m.name(), k -> new ArrayList<>()).add(m);
    }
    for (ArgDef a : d.args) {
      args.computeIfAbsent(a.name(), k -> new ArrayList<>()).add(a);
    }
  }

  /** Property definitions of {@code name} visible from {@code at} (macro-local ones filtered). */
  public List<PropertyDef> lookupProperty(String name, DOMNode at) {
    List<PropertyDef> defs = properties.get(name);
    if (defs == null) {
      return List.of();
    }
    List<DOMElement> enclosing = at == null ? List.of() : XacroDoc.enclosingMacros(at);
    List<PropertyDef> out = new ArrayList<>();
    for (PropertyDef p : defs) {
      if (p.isGlobal() || enclosing.contains(p.enclosingMacro()) || exported.contains(p)) {
        out.add(p);
      }
    }
    return out;
  }

  public List<MacroDef> lookupMacro(String name) {
    return macros.getOrDefault(name, List.of());
  }

  /** Macro parameters (innermost macro first) visible at a node, as (macro, param name) pairs. */
  public static List<Map.Entry<MacroDef, String>> paramsAt(XacroDoc doc, DOMNode at) {
    List<Map.Entry<MacroDef, String>> out = new ArrayList<>();
    for (DOMElement me : XacroDoc.enclosingMacros(at)) {
      for (MacroDef m : doc.macros) {
        if (m.element() == me) {
          m.params().forEach(p -> out.add(Map.entry(m, p.name())));
        }
      }
    }
    return out;
  }
}
