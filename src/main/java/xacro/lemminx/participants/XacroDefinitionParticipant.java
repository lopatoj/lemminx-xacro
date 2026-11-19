package xacro.lemminx.participants;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.services.extensions.IDefinitionParticipant;
import org.eclipse.lemminx.services.extensions.IDefinitionRequest;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import xacro.lemminx.Scope;
import xacro.lemminx.Target;
import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.XacroWorkspace.IncludeResult;
import xacro.lemminx.model.MacroParam;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ArgDef;
import xacro.lemminx.model.XacroDoc.MacroDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;

public class XacroDefinitionParticipant implements IDefinitionParticipant {

  private final XacroWorkspace ws;

  public XacroDefinitionParticipant(XacroWorkspace ws) {
    this.ws = ws;
  }

  @Override
  public void findDefinition(
      IDefinitionRequest request, List<LocationLink> locations, CancelChecker cancelChecker) {
    XacroDoc doc = ws.model(request.getXMLDocument());
    if (!doc.isXacro) {
      return;
    }
    Target t = Target.at(doc, request.getOffset());
    if (t == null) {
      return;
    }
    Range origin = t.range(doc);
    Scope scope = ws.scopeFor(doc);
    switch (t.kind) {
      case MACRO_CALL -> {
        for (MacroDef m : scope.lookupMacro(t.name)) {
          locations.add(link(m.owner(), m.element(), m.nameAttr(), origin));
        }
      }
      case MACRO_PARAM_ATTR -> {
        String callName = XacroDoc.xacroLocal(t.element);
        for (MacroDef m : scope.lookupMacro(callName)) {
          MacroParam p = m.param(t.name);
          if (p != null && m.paramsAttr() != null) {
            int s = m.paramsAttr().getNodeAttrValue().getStart() + 1 + p.offset();
            Range r = XMLPositionUtility.createRange(s, s + p.name().length(), m.owner().dom);
            locations.add(new LocationLink(m.owner().uri, r, r, origin));
          }
        }
      }
      case NAME -> {
        Target.Resolved res = Target.resolveName(doc, scope, t.name, t.context);
        if (res.param != null && res.param.paramsAttr() != null) {
          MacroParam p = res.param.param(res.paramName);
          XacroDoc owner = res.param.owner();
          int s = res.param.paramsAttr().getNodeAttrValue().getStart() + 1 + p.offset();
          Range r = XMLPositionUtility.createRange(s, s + p.name().length(), owner.dom);
          locations.add(new LocationLink(owner.uri, r, r, origin));
        }
        for (PropertyDef p : res.properties) {
          locations.add(link(p.owner(), p.element(), p.nameAttr(), origin));
        }
        for (MacroDef m : res.macros) {
          locations.add(link(m.owner(), m.element(), m.nameAttr(), origin));
        }
      }
      case SUBST_ARG -> {
        for (ArgDef a : scope.args.getOrDefault(t.name, List.of())) {
          locations.add(link(a.owner(), a.element(), a.nameAttr(), origin));
        }
      }
      case SUBST_FIND -> {
        Path pkg = ws.packages.find(t.name);
        if (pkg != null) {
          Path manifest = pkg.resolve("package.xml");
          locations.add(fileLink(manifest.toFile().exists() ? manifest : pkg, origin));
        }
      }
      case INCLUDE -> {
        if (t.include != null) {
          IncludeResult r = ws.resolveInclude(doc, t.include);
          for (Path p : r.files()) {
            locations.add(fileLink(p, origin));
          }
        }
      }
      case INSERT_BLOCK -> {
        for (var e : Scope.paramsAt(doc, t.element)) {
          if (e.getValue().equals(t.name) && e.getKey().paramsAttr() != null) {
            MacroParam p = e.getKey().param(t.name);
            int s = e.getKey().paramsAttr().getNodeAttrValue().getStart() + 1 + p.offset();
            Range r = XMLPositionUtility.createRange(s, s + p.name().length(), doc.dom);
            locations.add(new LocationLink(doc.uri, r, r, origin));
            return;
          }
        }
        for (PropertyDef p : scope.lookupProperty(t.name, t.element)) {
          if (p.block()) {
            locations.add(link(p.owner(), p.element(), p.nameAttr(), origin));
          }
        }
      }
    }
  }

  static LocationLink link(XacroDoc owner, DOMElement element, DOMAttr nameAttr, Range origin) {
    Range target = XMLPositionUtility.createRange(element.getStart(), element.getEnd(), owner.dom);
    Range sel =
        nameAttr != null
            ? XMLPositionUtility.selectAttributeValue(nameAttr)
            : XMLPositionUtility.selectStartTagName(element);
    return new LocationLink(owner.uri, target, sel, origin);
  }

  static LocationLink fileLink(Path p, Range origin) {
    Range zero = new Range(new Position(0, 0), new Position(0, 0));
    return new LocationLink(p.toUri().toString(), zero, zero, origin);
  }
}
