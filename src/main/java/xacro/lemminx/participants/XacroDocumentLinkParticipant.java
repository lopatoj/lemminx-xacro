package xacro.lemminx.participants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.services.extensions.IDocumentLinkParticipant;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.DocumentLink;

import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.model.XacroDoc;

/** Clickable links for xacro:include filenames and package:// mesh/texture URIs. */
public class XacroDocumentLinkParticipant implements IDocumentLinkParticipant {

	private final XacroWorkspace ws;

	public XacroDocumentLinkParticipant(XacroWorkspace ws) {
		this.ws = ws;
	}

	@Override
	public void findDocumentLinks(DOMDocument dom, List<DocumentLink> links) {
		XacroDoc doc = ws.model(dom);
		for (XacroDoc.IncludeDef inc : doc.includes) {
			if (inc.filenameAttr() == null) {
				continue;
			}
			var r = ws.resolveInclude(doc, inc);
			if (r.files().size() == 1) {
				links.add(new DocumentLink(XMLPositionUtility.selectAttributeValue(inc.filenameAttr()),
						r.files().get(0).toUri().toString()));
			}
		}
		walk(dom.getDocumentElement(), links);
	}

	private void walk(DOMElement e, List<DocumentLink> links) {
		if (e == null) {
			return;
		}
		DOMAttr a = e.getAttributeNode("filename");
		if (a != null && a.getValue() != null && !a.getValue().contains("$")) {
			Path p = ws.resolveResourceUri(a.getValue());
			if (p != null && Files.exists(p)) {
				links.add(new DocumentLink(XMLPositionUtility.selectAttributeValue(a), p.toUri().toString()));
			}
		}
		for (DOMNode c : e.getChildren()) {
			if (c.isElement()) {
				walk((DOMElement) c, links);
			}
		}
	}
}
