package xacro.lemminx.participants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import xacro.lemminx.model.XacroDoc;

/**
 * Semantic checks for plain (non-xacro) URDF: unique names, joint parent/child links exist,
 * exactly one root link. For xacro files the same checks run on the expanded output in the
 * Python sidecar, with locations mapped back to the source.
 */
final class UrdfChecks {

	private UrdfChecks() {
	}

	static void check(XacroDoc doc, List<Diagnostic> out) {
		DOMElement robot = doc.dom.getDocumentElement();
		if (robot == null || !"robot".equals(robot.getTagName())) {
			return;
		}
		Map<String, DOMElement> links = new HashMap<>();
		Map<String, DOMElement> joints = new HashMap<>();
		Set<String> children = new HashSet<>();
		for (DOMNode n : robot.getChildren()) {
			if (!n.isElement()) {
				continue;
			}
			DOMElement e = (DOMElement) n;
			String tag = e.getTagName();
			if ("link".equals(tag) || "joint".equals(tag)) {
				DOMAttr name = e.getAttributeNode("name");
				if (name == null) {
					out.add(new Diagnostic(XMLPositionUtility.selectStartTagName(e), "<" + tag + "> requires a name",
							DiagnosticSeverity.Error, "urdf"));
					continue;
				}
				Map<String, DOMElement> m = "link".equals(tag) ? links : joints;
				if (m.put(name.getValue(), e) != null) {
					out.add(new Diagnostic(XMLPositionUtility.selectAttributeValue(name),
							"Duplicate " + tag + " name '" + name.getValue() + "'", DiagnosticSeverity.Error, "urdf"));
				}
			}
		}
		for (DOMElement j : joints.values()) {
			for (String rel : new String[] { "parent", "child" }) {
				for (DOMNode c : j.getChildren()) {
					if (c.isElement() && rel.equals(((DOMElement) c).getTagName())) {
						DOMAttr link = ((DOMElement) c).getAttributeNode("link");
						if (link == null) {
							continue;
						}
						if (rel.equals("child")) {
							children.add(link.getValue());
						}
						if (!links.containsKey(link.getValue())) {
							out.add(new Diagnostic(XMLPositionUtility.selectAttributeValue(link),
									"Joint references unknown link '" + link.getValue() + "'", DiagnosticSeverity.Error,
									"urdf"));
						}
					}
				}
			}
		}
		long roots = links.keySet().stream().filter(l -> !children.contains(l)).count();
		if (!links.isEmpty() && roots > 1) {
			out.add(new Diagnostic(XMLPositionUtility.selectStartTagName(robot),
					"URDF has " + roots + " root links (links that are no joint's child); expected exactly one",
					DiagnosticSeverity.Warning, "urdf"));
		}
	}
}
