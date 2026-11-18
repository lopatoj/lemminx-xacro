package xacro.lemminx.participants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.services.extensions.completion.ICompletionParticipant;
import org.eclipse.lemminx.services.extensions.completion.ICompletionRequest;
import org.eclipse.lemminx.services.extensions.completion.ICompletionResponse;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import xacro.lemminx.Scope;
import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.model.Builtins;
import xacro.lemminx.model.MacroParam;
import xacro.lemminx.model.PyRefs;
import xacro.lemminx.model.UrdfVocabulary;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ArgDef;
import xacro.lemminx.model.XacroDoc.MacroDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;
import xacro.lemminx.model.XacroTags;

public class XacroCompletionParticipant implements ICompletionParticipant {

	private final XacroWorkspace ws;

	public XacroCompletionParticipant(XacroWorkspace ws) {
		this.ws = ws;
	}

	private static boolean isRobotDoc(XacroDoc doc) {
		DOMElement root = doc.dom.getDocumentElement();
		return doc.isXacro || (root != null && "robot".equals(root.getTagName()));
	}

	// ------------------------------------------------------------------ tags

	@Override
	public void onTagOpen(ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker)
			throws Exception {
		XacroDoc doc = ws.model(request.getXMLDocument());
		if (!isRobotDoc(doc)) {
			return;
		}
		Range range = request.getReplaceRangeForTagName();
		boolean snippets = request.isCompletionSnippetsSupported();
		DOMElement parent = parentForNewTag(request);
		if (doc.isXacro) {
			for (XacroTags.Tag t : XacroTags.TAGS.values()) {
				CompletionItem item = item("xacro:" + t.name(), CompletionItemKind.Keyword, range,
						snippets ? t.snippet() : "xacro:" + t.name(), snippets);
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, t.doc()));
				response.addCompletionItem(item);
			}
			Scope scope = ws.scopeFor(doc);
			for (Map.Entry<String, List<MacroDef>> e : scope.macros.entrySet()) {
				MacroDef m = e.getValue().get(e.getValue().size() - 1);
				CompletionItem item = item("xacro:" + e.getKey(), CompletionItemKind.Function, range,
						snippets ? macroSnippet(e.getKey(), m) : "xacro:" + e.getKey(), snippets);
				item.setDetail(m.signature());
				item.setDocumentation(
						new MarkupContent(MarkupKind.MARKDOWN, XacroHoverParticipant.macroDoc(m, doc)));
				response.addCompletionItem(item);
			}
		}
		String effParent = parent == null ? null : UrdfVocabulary.effectiveSelf(parent);
		List<String> children;
		if (parent == null) {
			children = List.of("robot");
		} else {
			children = UrdfVocabulary.get().childrenOf(effParent);
		}
		for (String tag : children) {
			UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(effParent, tag);
			CompletionItem item = item(tag, CompletionItemKind.Property, range,
					snippets ? urdfSnippet(tag, v) : tag, snippets);
			if (v != null && v.doc != null) {
				item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, v.doc));
			}
			response.addCompletionItem(item);
		}
	}

	/** The element a tag typed at the cursor will become a child of. */
	private static DOMElement parentForNewTag(ICompletionRequest request) {
		DOMNode node = request.getNode();
		int offset = request.getOffset();
		if (node != null && node.isElement()) {
			DOMElement e = (DOMElement) node;
			// cursor inside the start tag of e (i.e. e is the partially typed element)
			if (e.getStart() < offset && (!e.hasStartTag() || e.getStartTagCloseOffset() < 0
					|| offset <= e.getStartTagCloseOffset())) {
				return e.getParentElement();
			}
			return e;
		}
		return request.getParentElement();
	}

	private static String macroSnippet(String name, MacroDef m) {
		StringBuilder sb = new StringBuilder("xacro:").append(name);
		int i = 1;
		int blocks = 0;
		for (MacroParam p : m.params()) {
			if (p.isBlock()) {
				blocks++;
			} else if (p.isRequired()) {
				sb.append(' ').append(p.name()).append("=\"$").append(i++).append('"');
			}
		}
		if (blocks > 0) {
			sb.append(">\n\t$0\n</xacro:").append(name).append('>');
		} else {
			sb.append("/>$0");
		}
		return sb.toString();
	}

	private static String urdfSnippet(String tag, UrdfVocabulary.Element v) {
		StringBuilder sb = new StringBuilder(tag);
		int i = 1;
		if (v != null) {
			for (Map.Entry<String, UrdfVocabulary.Attr> a : v.attrs.entrySet()) {
				if (a.getValue().required) {
					sb.append(' ').append(a.getKey()).append("=\"$").append(i++).append('"');
				}
			}
		}
		boolean hasContent = v != null && (!v.children.isEmpty() || !v.text.isEmpty() || v.attrs.isEmpty());
		if (hasContent) {
			sb.append(">$0</").append(tag).append('>');
		} else {
			sb.append("/>$0");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ attribute names

	@Override
	public void onAttributeName(boolean generateValue, ICompletionRequest request, ICompletionResponse response,
			CancelChecker cancelChecker) throws Exception {
		if (!(request.getNode() instanceof DOMElement e)) {
			return;
		}
		XacroDoc doc = ws.model(request.getXMLDocument());
		Range range = request.getReplaceRange();
		boolean snippets = request.isCompletionSnippetsSupported() && generateValue;
		String local = XacroDoc.xacroLocal(e);
		Map<String, String> attrs = new LinkedHashMap<>(); // name → detail
		if (local != null) {
			XacroTags.Tag t = XacroTags.TAGS.get(local);
			if (t != null) {
				t.attrs().forEach(a -> attrs.put(a, "xacro:" + local));
			} else {
				List<MacroDef> defs = ws.scopeFor(doc).lookupMacro(local);
				if (!defs.isEmpty()) {
					for (MacroParam p : defs.get(defs.size() - 1).params()) {
						if (!p.isBlock()) {
							attrs.put(p.name(), p.isRequired() ? "required" : "default: " + p.defaultValue());
						}
					}
				}
			}
		} else if (isRobotDoc(doc)) {
			UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(UrdfVocabulary.effectiveParent(e), e.getTagName());
			if (v != null) {
				v.attrs.forEach((k, a) -> attrs.put(k, a.required ? "required" : a.doc));
			}
		}
		for (Map.Entry<String, String> a : attrs.entrySet()) {
			if (response.hasAttribute(a.getKey()) || e.hasAttribute(a.getKey())) {
				continue;
			}
			CompletionItem item = item(a.getKey(), CompletionItemKind.Field, range,
					snippets ? a.getKey() + "=\"$1\"$0" : a.getKey(), snippets);
			item.setDetail(a.getValue());
			response.addCompletionAttribute(item);
		}
	}

	// ------------------------------------------------------------------ attribute values / text

	@Override
	public void onAttributeValue(String valuePrefix, ICompletionRequest request, ICompletionResponse response,
			CancelChecker cancelChecker) throws Exception {
		DOMAttr attr = request.getCurrentAttribute();
		if (attr == null || !(request.getNode() instanceof DOMElement e)) {
			return;
		}
		XacroDoc doc = ws.model(request.getXMLDocument());
		int offset = request.getOffset();
		if (attr.getNodeAttrValue() != null) {
			int vs = attr.getNodeAttrValue().getStart() + 1;
			if (offset >= vs && offset <= doc.dom.getText().length()) {
				String before = doc.dom.getText().substring(vs, offset);
				if (doc.isXacro && completeInExpression(doc, e, before, offset, request, response)) {
					return;
				}
			}
		}
		// enumerated URDF values
		if (XacroDoc.xacroLocal(e) == null) {
			UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(UrdfVocabulary.effectiveParent(e), e.getTagName());
			if (v != null && v.attrs.containsKey(attr.getName())) {
				for (String val : v.attrs.get(attr.getName()).values) {
					response.addCompletionItem(item(val, CompletionItemKind.EnumMember, request.getReplaceRange(),
							request.getInsertAttrValue(val), false));
				}
			}
		}
	}

	@Override
	public void onXMLContent(ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker)
			throws Exception {
		XacroDoc doc = ws.model(request.getXMLDocument());
		DOMNode node = request.getNode();
		DOMElement parent = node == null ? null : node.isElement() ? (DOMElement) node : node.getParentElement();
		if (parent == null) {
			return;
		}
		int offset = request.getOffset();
		String text = doc.dom.getText();
		int start = Math.max(text.lastIndexOf('>', offset - 1) + 1, 0);
		String before = text.substring(start, offset);
		if (doc.isXacro && completeInExpression(doc, parent, before, offset, request, response)) {
			return;
		}
		UrdfVocabulary.Element v = UrdfVocabulary.get().lookup(UrdfVocabulary.effectiveParent(parent),
				parent.getTagName());
		if (v != null && !v.text.isEmpty() && before.isBlank()) {
			Range r = XMLPositionUtility.createRange(offset, offset, doc.dom);
			for (String val : v.text) {
				response.addCompletionItem(item(val, CompletionItemKind.Value, r, val, false));
			}
		}
	}

	/**
	 * If the cursor is inside an unclosed {@code ${...}} or {@code $(...)} in {@code before}
	 * (the text from the start of the value up to the cursor), add completions and return true.
	 */
	private boolean completeInExpression(XacroDoc doc, DOMElement at, String before, int offset,
			ICompletionRequest request, ICompletionResponse response) {
		int expr = before.lastIndexOf("${");
		int subst = before.lastIndexOf("$(");
		if (expr >= 0 && expr > subst && before.indexOf('}', expr) < 0) {
			String body = before.substring(expr + 2);
			String partial = PyRefs.partialNameBefore(body, body.length());
			completeNames(doc, at, partial, offset, response);
			return true;
		}
		if (subst >= 0 && before.indexOf(')', subst) < 0) {
			String body = before.substring(subst + 2);
			String[] parts = body.stripLeading().split("\\s+", -1);
			if (parts.length <= 1) {
				Range r = XMLPositionUtility.createRange(offset - parts[0].length(), offset, doc.dom);
				for (Map.Entry<String, String> s : Builtins.SUBSTITUTIONS.entrySet()) {
					CompletionItem item = item(s.getKey(), CompletionItemKind.Keyword, r, s.getKey() + " ", false);
					item.setDetail(s.getValue());
					response.addCompletionItem(item);
				}
			} else if (parts.length == 2) {
				Range r = XMLPositionUtility.createRange(offset - parts[1].length(), offset, doc.dom);
				switch (parts[0]) {
				case "find" -> ws.packages.all().forEach((pkg, path) -> {
					CompletionItem item = item(pkg, CompletionItemKind.Module, r, pkg, false);
					item.setDetail(path.toString());
					response.addCompletionItem(item);
				});
				case "arg" -> {
					Map<String, String> args = new LinkedHashMap<>();
					for (Map.Entry<String, List<ArgDef>> a : ws.scopeFor(doc).args.entrySet()) {
						args.put(a.getKey(), "default: " + a.getValue().get(0).defaultValue());
					}
					ws.settings().args.forEach((k, v) -> args.putIfAbsent(k, "from settings: " + v));
					args.forEach((k, d) -> {
						CompletionItem item = item(k, CompletionItemKind.Variable, r, k, false);
						item.setDetail(d);
						response.addCompletionItem(item);
					});
				}
				default -> {
				}
				}
			}
			return true;
		}
		return false;
	}

	private void completeNames(XacroDoc doc, DOMElement at, String partial, int offset,
			ICompletionResponse response) {
		int dot = partial.lastIndexOf('.');
		String qualifier = dot < 0 ? "" : partial.substring(0, dot + 1);
		String stem = partial.substring(dot + 1);
		Range r = XMLPositionUtility.createRange(offset - stem.length(), offset, doc.dom);
		Map<String, CompletionItem> items = new LinkedHashMap<>();
		Scope scope = ws.scopeFor(doc);
		// macro parameters of enclosing macros
		if (qualifier.isEmpty()) {
			for (Map.Entry<MacroDef, String> p : Scope.paramsAt(doc, at)) {
				MacroParam mp = p.getKey().param(p.getValue());
				if (mp != null && !mp.isBlock()) {
					CompletionItem item = item(p.getValue(), CompletionItemKind.Field, r, p.getValue(), false);
					item.setDetail("param of " + p.getKey().name());
					item.setSortText("0" + p.getValue());
					items.putIfAbsent(p.getValue(), item);
				}
			}
		}
		for (Map.Entry<String, List<PropertyDef>> e : scope.properties.entrySet()) {
			String full = e.getKey();
			if (!full.startsWith(qualifier) || scope.lookupProperty(full, at).isEmpty()) {
				continue;
			}
			String rest = full.substring(qualifier.length());
			if (rest.contains(".")) {
				continue; // deeper namespace: offered via the namespace item
			}
			PropertyDef p = e.getValue().get(e.getValue().size() - 1);
			if (p.block()) {
				continue;
			}
			CompletionItem item = item(rest, CompletionItemKind.Variable, r, rest, false);
			item.setDetail(p.value());
			item.setSortText("1" + rest);
			items.putIfAbsent(rest, item);
		}
		for (String ns : scope.namespaces) {
			if (ns.startsWith(qualifier) && !ns.substring(qualifier.length()).contains(".")) {
				String rest = ns.substring(qualifier.length());
				CompletionItem item = item(rest, CompletionItemKind.Module, r, rest, false);
				item.setDetail("include namespace");
				items.putIfAbsent(rest, item);
			}
		}
		switch (qualifier) {
		case "" -> {
			for (String b : Builtins.PY_DIRECT) {
				items.putIfAbsent(b, builtin(b, r, "builtin"));
			}
			for (String m : List.of("math", "xacro", "python")) {
				items.putIfAbsent(m, builtin(m, r, "namespace"));
			}
			for (String m : List.of("pi", "radians", "degrees", "sin", "cos", "tan", "atan2", "sqrt", "fabs")) {
				items.putIfAbsent(m, builtin(m, r, "math." + m));
			}
		}
		case "math." -> Builtins.MATH.forEach(m -> items.putIfAbsent(m, builtin(m, r, "math")));
		case "python." -> Builtins.PY_NS.forEach(m -> items.putIfAbsent(m, builtin(m, r, "python builtin")));
		case "xacro." -> Builtins.XACRO_NS.forEach((k, d) -> items.putIfAbsent(k, builtin(k, r, d)));
		default -> {
		}
		}
		items.values().forEach(response::addCompletionItem);
	}

	private static CompletionItem builtin(String name, Range r, String detail) {
		CompletionItem item = item(name, CompletionItemKind.Function, r, name, false);
		item.setDetail(detail);
		item.setSortText("9" + name);
		return item;
	}

	private static CompletionItem item(String label, CompletionItemKind kind, Range range, String insert,
			boolean snippet) {
		CompletionItem item = new CompletionItem(label);
		item.setKind(kind);
		item.setFilterText(label);
		item.setTextEdit(Either.forLeft(new TextEdit(range, insert)));
		item.setInsertTextFormat(snippet ? InsertTextFormat.Snippet : InsertTextFormat.PlainText);
		return item;
	}

	@Override
	public void onDTDSystemId(String valuePrefix, ICompletionRequest request, ICompletionResponse response,
			CancelChecker cancelChecker) throws Exception {
	}
}
