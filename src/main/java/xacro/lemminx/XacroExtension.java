package xacro.lemminx;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.services.IXMLValidationService;
import org.eclipse.lemminx.services.extensions.IDocumentLifecycleParticipant;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lemminx.services.extensions.IXMLExtension;
import org.eclipse.lemminx.services.extensions.XMLExtensionsRegistry;
import org.eclipse.lemminx.services.extensions.save.ISaveContext;
import org.eclipse.lemminx.utils.JSONUtility;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;

import xacro.lemminx.participants.XacroCompletionParticipant;
import xacro.lemminx.participants.XacroDefinitionParticipant;
import xacro.lemminx.participants.XacroDiagnosticsParticipant;
import xacro.lemminx.participants.XacroDocumentLinkParticipant;
import xacro.lemminx.participants.XacroHoverParticipant;
import xacro.lemminx.python.XacroPythonRunner;

/**
 * LemMinX extension entry point (registered via META-INF/services). Adds xacro + URDF
 * language features on top of LemMinX's generic XML support.
 */
public class XacroExtension implements IXMLExtension {

	private static final Logger LOG = Logger.getLogger(XacroExtension.class.getName());

	private final XacroWorkspace ws = new XacroWorkspace();
	private final XacroPythonRunner python = new XacroPythonRunner(ws);
	private final List<Path> roots = new ArrayList<>();

	private XacroDiagnosticsParticipant diagnostics;
	private XacroCompletionParticipant completion;
	private XacroHoverParticipant hover;
	private XacroDefinitionParticipant definition;
	private XacroDocumentLinkParticipant links;
	private IDocumentLifecycleParticipant lifecycle;
	private IWorkspaceServiceParticipant workspace;
	private XacroGrammarProvider grammar;

	public XacroWorkspace workspace() {
		return ws;
	}

	public XacroPythonRunner python() {
		return python;
	}

	@Override
	public void start(InitializeParams params, XMLExtensionsRegistry registry) {
		if (params != null) {
			if (params.getWorkspaceFolders() != null) {
				for (WorkspaceFolder f : params.getWorkspaceFolders()) {
					addRoot(f.getUri());
				}
			} else if (params.getRootUri() != null) {
				addRoot(params.getRootUri());
			}
			ws.setWorkspaceRoots(roots);
		}

		diagnostics = new XacroDiagnosticsParticipant(ws, python);
		completion = new XacroCompletionParticipant(ws);
		hover = new XacroHoverParticipant(ws, python);
		definition = new XacroDefinitionParticipant(ws);
		links = new XacroDocumentLinkParticipant(ws);
		lifecycle = new IDocumentLifecycleParticipant() {
			@Override
			public void didOpen(DOMDocument document) {
				ws.didOpenOrChange(document);
				if (isXacroFile(document)) {
					python.schedule(XacroWorkspace.toPath(document.getDocumentURI()));
				}
			}

			@Override
			public void didChange(DOMDocument document) {
				ws.didOpenOrChange(document);
			}

			@Override
			public void didSave(DOMDocument document) {
				ws.didSave(document);
				if (isXacroFile(document)) {
					python.schedule(XacroWorkspace.toPath(document.getDocumentURI()));
				}
			}

			@Override
			public void didClose(DOMDocument document) {
				ws.didClose(document);
			}
		};
		workspace = new IWorkspaceServiceParticipant() {
			@Override
			public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams p) {
				for (WorkspaceFolder f : p.getEvent().getRemoved()) {
					roots.remove(XacroWorkspace.toPath(f.getUri()));
				}
				for (WorkspaceFolder f : p.getEvent().getAdded()) {
					addRoot(f.getUri());
				}
				ws.setWorkspaceRoots(roots);
			}
		};
		python.setOnResults(paths -> {
			IXMLValidationService validation = registry.getValidationService();
			if (validation == null) {
				return;
			}
			for (Path p : paths) {
				DOMDocument dom = ws.openDocument(p);
				if (dom != null) {
					try {
						validation.validate(dom);
					} catch (Exception e) {
						LOG.log(Level.FINE, "revalidation failed", e);
					}
				}
			}
		});

		grammar = new XacroGrammarProvider(ws);
		registry.getResolverExtensionManager().registerResolver(grammar);
		registry.registerDiagnosticsParticipant(diagnostics);
		registry.registerCompletionParticipant(completion);
		registry.registerHoverParticipant(hover);
		registry.registerDefinitionParticipant(definition);
		registry.registerDocumentLinkParticipant(links);
		registry.registerDocumentLifecycleParticipant(lifecycle);
		registry.registerWorkspaceServiceParticipant(workspace);
	}

	private static boolean isXacroFile(DOMDocument d) {
		String uri = d.getDocumentURI();
		return uri != null && (uri.endsWith(".xacro") || uri.endsWith(".urdf"));
	}

	private void addRoot(String uri) {
		Path p = XacroWorkspace.toPath(uri);
		if (p != null && !roots.contains(p)) {
			roots.add(p);
		}
	}

	@Override
	public void stop(XMLExtensionsRegistry registry) {
		registry.getResolverExtensionManager().unregisterResolver(grammar);
		registry.unregisterDiagnosticsParticipant(diagnostics);
		registry.unregisterCompletionParticipant(completion);
		registry.unregisterHoverParticipant(hover);
		registry.unregisterDefinitionParticipant(definition);
		registry.unregisterDocumentLinkParticipant(links);
		registry.unregisterDocumentLifecycleParticipant(lifecycle);
		registry.unregisterWorkspaceServiceParticipant(workspace);
		python.dispose();
	}

	@Override
	public void doSave(ISaveContext context) {
		if (context.getType() == ISaveContext.SaveContextType.SETTINGS) {
			try {
				XacroSettings.Root root = JSONUtility.toModel(context.getSettings(), XacroSettings.Root.class);
				if (root != null && root.xml != null && root.xml.xacro != null) {
					ws.setSettings(root.xml.xacro);
				}
			} catch (Exception e) {
				LOG.log(Level.WARNING, "invalid xml.xacro settings", e);
			}
		}
	}
}
