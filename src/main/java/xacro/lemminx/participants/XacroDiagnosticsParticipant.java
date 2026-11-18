package xacro.lemminx.participants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import xacro.lemminx.Scope;
import xacro.lemminx.Target;
import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.XacroWorkspace.IncludeResult;
import xacro.lemminx.model.Builtins;
import xacro.lemminx.model.ExprScanner;
import xacro.lemminx.model.MacroParam;
import xacro.lemminx.model.PyRefs;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.ExprOcc;
import xacro.lemminx.model.XacroDoc.IncludeDef;
import xacro.lemminx.model.XacroDoc.MacroCall;
import xacro.lemminx.model.XacroDoc.MacroDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;
import xacro.lemminx.python.XacroPythonRunner;

public class XacroDiagnosticsParticipant implements IDiagnosticsParticipant {

	public static final String SOURCE = "xacro";

	private final XacroWorkspace ws;
	private final XacroPythonRunner python;

	public XacroDiagnosticsParticipant(XacroWorkspace ws, XacroPythonRunner python) {
		this.ws = ws;
		this.python = python;
	}

	@Override
	public void doDiagnostics(DOMDocument dom, List<Diagnostic> diagnostics, XMLValidationSettings validationSettings,
			CancelChecker cancelChecker) {
		XacroDoc doc = ws.model(dom);
		if (doc.isXacro) {
			Scope scope = ws.scopeFor(doc);
			checkIncludes(doc, diagnostics);
			checkMacroDefs(doc, diagnostics);
			checkProperties(doc, diagnostics);
			checkCalls(doc, scope, diagnostics);
			checkExpressions(doc, scope, diagnostics);
		} else if (dom.getDocumentURI() != null && dom.getDocumentURI().endsWith(".urdf")) {
			UrdfChecks.check(doc, diagnostics);
		}
		checkResourceUris(doc, diagnostics);
		if (python != null) {
			// skip python-side errors already reported statically on the same line
			Set<Integer> staticErrorLines = new HashSet<>();
			for (Diagnostic d : diagnostics) {
				if (d.getSeverity() == DiagnosticSeverity.Error) {
					staticErrorLines.add(d.getRange().getStart().getLine());
				}
			}
			for (Diagnostic d : python.diagnosticsFor(doc)) {
				if (!(d.getSeverity() == DiagnosticSeverity.Error
						&& staticErrorLines.contains(d.getRange().getStart().getLine()))) {
					diagnostics.add(d);
				}
			}
		}
	}

	private static Diagnostic diag(Range r, String msg, DiagnosticSeverity sev, String code) {
		Diagnostic d = new Diagnostic(r, msg, sev, SOURCE);
		d.setCode(code);
		return d;
	}

	/** Severity for "name not found": soft when the scope may be missing definitions. */
	private static DiagnosticSeverity missingSeverity(Scope scope, DOMNode at) {
		if (scope.incomplete) {
			return DiagnosticSeverity.Warning;
		}
		if (!scope.hasContext && !XacroDoc.enclosingMacros(at).isEmpty()) {
			// macro library file: body may use properties that its (unknown) includer defines
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Error;
	}

	private void checkIncludes(XacroDoc doc, List<Diagnostic> out) {
		for (IncludeDef inc : doc.includes) {
			Range r = inc.filenameAttr() != null ? XMLPositionUtility.selectAttributeValue(inc.filenameAttr())
					: XMLPositionUtility.selectStartTagName(inc.element());
			IncludeResult res = ws.resolveInclude(doc, inc);
			switch (res.status()) {
			case MISSING_ATTR -> out.add(diag(r, "xacro:include requires a 'filename' attribute",
					DiagnosticSeverity.Error, "include-missing-filename"));
			case NOT_FOUND -> {
				if (!inc.optional()) {
					out.add(diag(r, "Included file not found: " + (res.detail() != null ? res.detail() : res.spec()),
							DiagnosticSeverity.Error, "include-not-found"));
				}
			}
			case UNKNOWN_PACKAGE -> out.add(diag(r,
					"Unknown ROS package '" + res.detail()
							+ "' (not in the workspace, AMENT_PREFIX_PATH or xml.xacro.packagePaths)",
					DiagnosticSeverity.Warning, "unknown-package"));
			default -> {
			}
			}
		}
	}

	private void checkMacroDefs(XacroDoc doc, List<Diagnostic> out) {
		Map<String, MacroDef> seen = new HashMap<>();
		for (MacroDef m : doc.macros) {
			Range nameRange = m.nameAttr() != null ? XMLPositionUtility.selectAttributeValue(m.nameAttr())
					: XMLPositionUtility.selectStartTagName(m.element());
			if (m.name().equals("call")) {
				out.add(diag(nameRange, "Invalid macro name 'call' (reserved)", DiagnosticSeverity.Error,
						"macro-name"));
			} else if (m.name().contains(".")) {
				out.add(diag(nameRange, "Macro names must not contain '.' (reserved for namespaces)",
						DiagnosticSeverity.Error, "macro-name"));
			}
			MacroDef prev = seen.put(m.name(), m);
			if (prev != null && prev.enclosingMacro() == m.enclosingMacro()) {
				out.add(diag(nameRange, "Macro '" + m.name() + "' redefines an earlier macro in this file",
						DiagnosticSeverity.Information, "macro-redefined"));
			}
			Set<String> names = new HashSet<>();
			for (MacroParam p : m.params()) {
				if (!names.add(p.name())) {
					Range r = m.paramsAttr() == null ? nameRange
							: XMLPositionUtility.createRange(m.paramsAttr().getNodeAttrValue().getStart() + 1 + p.offset(),
									m.paramsAttr().getNodeAttrValue().getStart() + 1 + p.offset() + p.name().length(),
									doc.dom);
					out.add(diag(r, "Duplicate macro parameter '" + p.name() + "'", DiagnosticSeverity.Error,
							"macro-param-duplicate"));
				}
			}
		}
	}

	private void checkProperties(XacroDoc doc, List<Diagnostic> out) {
		for (PropertyDef p : doc.properties) {
			if (p.nameAttr() == null || p.name().contains("$")) {
				continue;
			}
			Range r = XMLPositionUtility.selectAttributeValue(p.nameAttr());
			if (!p.name().matches("[A-Za-z_][A-Za-z0-9_]*")) {
				out.add(diag(r, "Property names must be valid Python identifiers: " + p.name(),
						DiagnosticSeverity.Error, "property-name"));
			} else if (p.name().startsWith("__")) {
				out.add(diag(r, "Property names must not start with a double underscore", DiagnosticSeverity.Error,
						"property-name"));
			} else if (Builtins.isBuiltinHead(p.name()) && !p.name().equals("python") && !p.name().equals("xacro")) {
				out.add(diag(r, "Property '" + p.name() + "' shadows a global symbol", DiagnosticSeverity.Warning,
						"property-shadows-global"));
			}
			int set = (p.element().hasAttribute("value") ? 1 : 0) + (p.element().hasAttribute("default") ? 1 : 0)
					+ ("true".equals(p.element().getAttribute("remove")) ? 1 : 0);
			if (set > 1) {
				out.add(diag(r, "Property attributes value, default and remove are mutually exclusive",
						DiagnosticSeverity.Error, "property-attrs"));
			}
		}
	}

	private void checkCalls(XacroDoc doc, Scope scope, List<Diagnostic> out) {
		for (MacroCall call : doc.calls) {
			DOMElement e = call.element();
			Range tag = XMLPositionUtility.selectStartTagName(e);
			List<MacroDef> defs = scope.lookupMacro(call.name());
			if (defs.isEmpty()) {
				out.add(diag(tag, "Unknown macro 'xacro:" + call.name() + "'", missingSeverity(scope, e),
						"unknown-macro"));
				continue;
			}
			MacroDef m = defs.get(defs.size() - 1);
			Set<String> given = new HashSet<>();
			if (e.getAttributeNodes() != null) {
				for (DOMAttr a : e.getAttributeNodes()) {
					String n = a.getName();
					if (n.startsWith("xmlns") || n.startsWith("xacro:")) {
						continue;
					}
					given.add(n);
					MacroParam p = m.param(n);
					if (p == null || p.isBlock()) {
						out.add(diag(XMLPositionUtility.createRange(a.getNodeAttrName()),
								"Invalid parameter '" + n + "' for macro '" + m.name() + "'", DiagnosticSeverity.Error,
								"unknown-param"));
					}
				}
			}
			StringBuilder missing = new StringBuilder();
			int blocks = 0;
			for (MacroParam p : m.params()) {
				if (p.isBlock()) {
					blocks++;
				} else if (p.isRequired() && !given.contains(p.name())) {
					missing.append(missing.length() == 0 ? "" : ", ").append(p.name());
				}
			}
			if (missing.length() > 0) {
				out.add(diag(tag, "Missing required parameter(s) for macro '" + m.name() + "': " + missing,
						DiagnosticSeverity.Error, "missing-param"));
			}
			int children = 0;
			for (DOMNode c : e.getChildren()) {
				if (c.isElement()) {
					children++;
				}
			}
			if (children < blocks) {
				out.add(diag(tag, "Macro '" + m.name() + "' expects " + blocks + " block parameter(s), got " + children,
						DiagnosticSeverity.Error, "missing-block"));
			} else if (children > blocks) {
				out.add(diag(tag, "Unused block(s): macro '" + m.name() + "' takes " + blocks + " block parameter(s), got "
						+ children, DiagnosticSeverity.Error, "unused-block"));
			}
		}
	}

	private void checkExpressions(XacroDoc doc, Scope scope, List<Diagnostic> out) {
		for (ExprOcc occ : doc.exprs) {
			if (!occ.seg().closed()) {
				out.add(diag(XMLPositionUtility.createRange(occ.absStart(), occ.absEnd(), doc.dom),
						"Unterminated " + (occ.seg().kind() == ExprScanner.Kind.EXPR ? "${...}" : "$(...)"),
						DiagnosticSeverity.Error, "unterminated"));
				continue;
			}
			DOMNode at = occ.attr() != null ? occ.attr().getOwnerElement() : occ.element();
			if (occ.seg().kind() == ExprScanner.Kind.EXPR) {
				if (occ.seg().body().isBlank()) {
					out.add(diag(XMLPositionUtility.createRange(occ.absStart(), occ.absEnd(), doc.dom),
							"Empty expression", DiagnosticSeverity.Error, "empty-expr"));
					continue;
				}
				for (PyRefs.Ref r : PyRefs.scan(occ.seg().body())) {
					if (Builtins.isBuiltinHead(r.head())) {
						continue;
					}
					Target.Resolved res = Target.resolveName(doc, scope, r.name(), at);
					if (!res.found()) {
						Range range = XMLPositionUtility.createRange(occ.bodyAbs() + r.start(),
								occ.bodyAbs() + r.start() + r.head().length(), doc.dom);
						out.add(diag(range, "Undefined property '" + r.head() + "'", missingSeverity(scope, at),
								"undefined-name"));
					}
				}
			} else {
				String[] parts = occ.seg().body().strip().split("\\s+");
				String cmd = parts[0];
				Range range = XMLPositionUtility.createRange(occ.absStart(), occ.absEnd(), doc.dom);
				if (!Builtins.SUBSTITUTIONS.containsKey(cmd)) {
					out.add(diag(range, "Unknown substitution '$(" + cmd + " ...)'", DiagnosticSeverity.Error,
							"unknown-substitution"));
				} else if (cmd.equals("find") && parts.length >= 2 && !parts[1].contains("$")
						&& !isIncludeFilename(occ) && ws.packages.find(parts[1]) == null) {
					out.add(diag(range, "Unknown ROS package '" + parts[1] + "'", DiagnosticSeverity.Warning,
							"unknown-package"));
				} else if (cmd.equals("arg") && parts.length >= 2 && !scope.args.containsKey(parts[1])
						&& !ws.settings().args.containsKey(parts[1])) {
					out.add(diag(range,
							"Argument '" + parts[1]
									+ "' is not declared with <xacro:arg>; it must be passed as " + parts[1] + ":=...",
							scope.incomplete ? DiagnosticSeverity.Information : DiagnosticSeverity.Warning,
							"undeclared-arg"));
				}
			}
		}
	}

	private static boolean isIncludeFilename(ExprOcc occ) {
		return occ.attr() != null && "filename".equals(occ.attr().getName())
				&& "include".equals(XacroDoc.xacroLocal(occ.element()));
	}

	/** filename="package://pkg/meshes/x.stl" — warn if the package or file does not exist. */
	private void checkResourceUris(XacroDoc doc, List<Diagnostic> out) {
		walkFilenames(doc, doc.dom.getDocumentElement(), out);
	}

	private void walkFilenames(XacroDoc doc, DOMElement e, List<Diagnostic> out) {
		if (e == null) {
			return;
		}
		DOMAttr a = e.getAttributeNode("filename");
		if (a != null && a.getValue() != null && !a.getValue().contains("$")
				&& (a.getValue().startsWith("package://") || a.getValue().startsWith("file://"))) {
			Path p = ws.resolveResourceUri(a.getValue());
			Range r = XMLPositionUtility.selectAttributeValue(a);
			if (p == null) {
				out.add(diag(r, "Cannot resolve package in '" + a.getValue() + "'", DiagnosticSeverity.Warning,
						"unknown-package"));
			} else if (!Files.exists(p)) {
				out.add(diag(r, "File not found: " + p, DiagnosticSeverity.Warning, "resource-not-found"));
			}
		}
		for (DOMNode c : e.getChildren()) {
			if (c.isElement()) {
				walkFilenames(doc, (DOMElement) c, out);
			}
		}
	}
}
