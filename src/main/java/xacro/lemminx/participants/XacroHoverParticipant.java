package xacro.lemminx.participants;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.services.extensions.hover.IHoverParticipant;
import org.eclipse.lemminx.services.extensions.hover.IHoverRequest;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import xacro.lemminx.Scope;
import xacro.lemminx.Target;
import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.model.Builtins;
import xacro.lemminx.model.MacroParam;
import xacro.lemminx.model.UrdfVocabulary;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ArgDef;
import xacro.lemminx.model.XacroDoc.MacroDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;
import xacro.lemminx.model.XacroTags;
import xacro.lemminx.python.XacroPythonRunner;

public class XacroHoverParticipant implements IHoverParticipant {

	private final XacroWorkspace ws;
	private final XacroPythonRunner python;

	public XacroHoverParticipant(XacroWorkspace ws, XacroPythonRunner python) {
		this.ws = ws;
		this.python = python;
	}

	private static Hover md(String s, Range r) {
		return s == null ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, s), r);
	}

	@Override
	public Hover onTag(IHoverRequest request, CancelChecker cancelChecker) {
		if (!(request.getNode() instanceof DOMElement e)) {
			return null;
		}
		XacroDoc doc = ws.model(request.getXMLDocument());
		String local = XacroDoc.xacroLocal(e);
		if (local != null) {
			XacroTags.Tag tag = XacroTags.TAGS.get(local);
			if (tag != null) {
				return md("**xacro:" + local + "**\n\n" + tag.doc(), request.getHoverRange());
			}
			List<MacroDef> defs = ws.scopeFor(doc).lookupMacro(local);
			if (defs.isEmpty()) {
				return md("Unknown macro `" + local + "`", request.getHoverRange());
			}
			return md(macroDoc(defs.get(defs.size() - 1), doc), request.getHoverRange());
		}
		UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(UrdfVocabulary.effectiveParent(e), e.getTagName());
		return v == null || v.doc == null ? null : md("**<" + e.getTagName() + ">**\n\n" + v.doc, request.getHoverRange());
	}

	@Override
	public Hover onAttributeName(IHoverRequest request, CancelChecker cancelChecker) {
		if (!(request.getNode() instanceof DOMElement e)) {
			return null;
		}
		String attr = request.getCurrentAttributeName();
		XacroDoc doc = ws.model(request.getXMLDocument());
		String local = XacroDoc.xacroLocal(e);
		if (local != null && !XacroDoc.BUILTIN_TAGS.contains(local)) {
			for (MacroDef m : ws.scopeFor(doc).lookupMacro(local)) {
				MacroParam p = m.param(attr);
				if (p != null) {
					return md("Parameter `" + p.display() + "` of macro **" + m.name() + "**"
							+ (p.isRequired() ? " (required)" : ""), request.getHoverRange());
				}
			}
			return null;
		}
		UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(UrdfVocabulary.effectiveParent(e), e.getTagName());
		if (v != null && v.attrs.containsKey(attr)) {
			UrdfVocabulary.Attr a = v.attrs.get(attr);
			StringBuilder sb = new StringBuilder("**").append(attr).append("**");
			if (a.required) {
				sb.append(" (required)");
			}
			if (a.doc != null) {
				sb.append("\n\n").append(a.doc);
			}
			if (!a.values.isEmpty()) {
				sb.append("\n\nValues: ").append(String.join(", ", a.values));
			}
			return md(sb.toString(), request.getHoverRange());
		}
		return null;
	}

	@Override
	public Hover onAttributeValue(IHoverRequest request, CancelChecker cancelChecker) {
		return hoverTarget(request);
	}

	@Override
	public Hover onText(IHoverRequest request, CancelChecker cancelChecker) {
		return hoverTarget(request);
	}

	private Hover hoverTarget(IHoverRequest request) {
		XacroDoc doc = ws.model(request.getXMLDocument());
		if (!doc.isXacro) {
			return null;
		}
		Target t = Target.at(doc, request.getOffset());
		if (t == null) {
			return null;
		}
		Range r = t.range(doc);
		Scope scope = ws.scopeFor(doc);
		switch (t.kind) {
		case NAME -> {
			String head = t.name.split("\\.")[0];
			Target.Resolved res = Target.resolveName(doc, scope, t.name, t.context);
			if (res.param != null) {
				MacroParam p = res.param.param(res.paramName);
				return md("Parameter `" + p.display() + "` of macro **" + res.param.name() + "**", r);
			}
			if (!res.properties.isEmpty()) {
				PropertyDef p = res.properties.get(res.properties.size() - 1);
				StringBuilder sb = new StringBuilder("```xml\n<xacro:property name=\"").append(p.name()).append('"');
				if (p.block()) {
					sb.append("> (block) …");
				} else {
					sb.append(" value=\"").append(p.value()).append("\"/>");
				}
				sb.append("\n```");
				String evaluated = python == null ? null : python.evaluatedValue(doc, res.matched);
				if (evaluated != null) {
					sb.append("\n\n= `").append(evaluated).append("` *(from last xacro run)*");
				}
				sb.append("\n\nDefined in ").append(fileName(p.owner()));
				if (res.properties.size() > 1) {
					sb.append(" (").append(res.properties.size()).append(" definitions)");
				}
				return md(sb.toString(), r);
			}
			if (res.namespace != null) {
				return md("Include namespace `" + res.namespace + "`", r);
			}
			if (head.equals("xacro") && t.name.contains(".")) {
				String fn = t.name.substring("xacro.".length());
				return md(Builtins.XACRO_NS.getOrDefault(fn, null), r);
			}
			if (Builtins.MATH.contains(head) || head.equals("math")) {
				return md("Python `math` " + (head.equals("math") ? "module" : "symbol `" + head + "`"), r);
			}
			if (Builtins.isBuiltinHead(head)) {
				return md("Python builtin `" + head + "`", r);
			}
			return null;
		}
		case SUBST_ARG -> {
			List<ArgDef> defs = scope.args.getOrDefault(t.name, List.of());
			String mapped = ws.settings().args.get(t.name);
			StringBuilder sb = new StringBuilder("**arg** `").append(t.name).append('`');
			if (mapped != null) {
				sb.append("\n\nValue from settings: `").append(mapped).append('`');
			}
			for (ArgDef a : defs) {
				sb.append("\n\nDefault: `").append(a.defaultValue() == null ? "(none — required)" : a.defaultValue())
						.append("` in ").append(fileName(a.owner()));
			}
			return md(sb.toString(), r);
		}
		case SUBST_FIND -> {
			Path p = ws.packages.find(t.name);
			return md("**package** `" + t.name + "`\n\n" + (p == null ? "*not found*" : "`" + p + "`"), r);
		}
		case INCLUDE -> {
			if (t.include == null) {
				return null;
			}
			XacroWorkspace.IncludeResult res = ws.resolveInclude(doc, t.include);
			StringBuilder sb = new StringBuilder();
			switch (res.status()) {
			case OK -> res.files().forEach(f -> sb.append('`').append(f).append("`  \n"));
			case DYNAMIC -> sb.append("Resolved at expansion time (dynamic filename)");
			case UNKNOWN_PACKAGE -> sb.append("Unknown package `").append(res.detail()).append('`');
			default -> sb.append("Not found: `").append(res.detail()).append('`');
			}
			return md(sb.toString(), r);
		}
		case MACRO_CALL -> {
			List<MacroDef> defs = scope.lookupMacro(t.name);
			return defs.isEmpty() ? null : md(macroDoc(defs.get(defs.size() - 1), doc), r);
		}
		default -> {
			return null;
		}
		}
	}

	static String macroDoc(MacroDef m, XacroDoc from) {
		StringBuilder sb = new StringBuilder("```python\n").append(m.signature()).append("\n```");
		if (m.doc() != null) {
			sb.append("\n\n").append(m.doc());
		}
		sb.append("\n\nDefined in ").append(fileName(m.owner()));
		return sb.toString();
	}

	static String fileName(XacroDoc d) {
		return d.path == null ? d.uri : "`" + d.path.getFileName() + "`";
	}
}
