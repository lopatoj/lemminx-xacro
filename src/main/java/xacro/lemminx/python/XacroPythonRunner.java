package xacro.lemminx.python;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.google.gson.Gson;

import xacro.lemminx.XacroSettings;
import xacro.lemminx.XacroWorkspace;
import xacro.lemminx.model.XacroDoc;

/**
 * Runs the user's real xacro (Python) on root files in a background process and turns its
 * result into diagnostics and evaluated property values. See sidecar.py for how source
 * locations are preserved through macro expansion.
 */
public final class XacroPythonRunner {

	private static final Logger LOG = Logger.getLogger(XacroPythonRunner.class.getName());
	public static final String SOURCE = "xacro (python)";
	private static final int MAX_ROOTS = 4;

	// ---- JSON shapes produced by sidecar.py
	public static class Loc {
		public String file;
		public int line;
		public int col;
		public String what;
	}

	public static class Err {
		public String message;
		public List<Loc> locations = new ArrayList<>();
		public Boolean import_error;
	}

	public static class Issue {
		public String message;
		public String severity;
		public String file;
		public int line;
		public int col;
		public List<Loc> calls = new ArrayList<>();
	}

	public static class Result {
		public boolean ok;
		public Err error;
		public Map<String, String> symbols = new LinkedHashMap<>();
		public List<String> files = new ArrayList<>();
		public List<Issue> urdf = new ArrayList<>();
	}

	/** A finished run for one root, with the content hashes of the files it read. */
	public record Run(Path root, Result result, Map<Path, Integer> hashes) {
	}

	private final XacroWorkspace ws;
	private final Map<Path, Run> runs = new ConcurrentHashMap<>();
	private final Set<Path> pending = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xacro-python");
		t.setDaemon(true);
		return t;
	});
	private Path sidecar;
	private Consumer<Set<Path>> onResults = paths -> {
	};
	private volatile String unavailable;

	public XacroPythonRunner(XacroWorkspace ws) {
		this.ws = ws;
	}

	/** Called with the files whose diagnostics may have changed after a run. */
	public void setOnResults(Consumer<Set<Path>> onResults) {
		this.onResults = onResults;
	}

	public void dispose() {
		executor.shutdownNow();
	}

	private boolean enabled() {
		XacroSettings.Python p = ws.settings().python;
		return p == null || p.enabled == null || p.enabled;
	}

	/** Schedule a run for every root that (transitively) includes {@code file}. */
	public void schedule(Path file) {
		if (file == null || !enabled()) {
			return;
		}
		Set<Path> roots = ws.rootsOf(file);
		int n = 0;
		for (Path root : roots) {
			if (n++ >= MAX_ROOTS) {
				break;
			}
			if (pending.add(root)) {
				executor.submit(() -> {
					pending.remove(root);
					runRoot(root);
				});
			}
		}
	}

	/** Blocking run, used by tests. */
	public Run runNow(Path root) {
		runRoot(root);
		return runs.get(root);
	}

	private void runRoot(Path root) {
		try {
			Result r = execute(root);
			if (r == null) {
				return;
			}
			Map<Path, Integer> hashes = new HashMap<>();
			Set<Path> touched = new LinkedHashSet<>();
			touched.add(root);
			for (String f : r.files) {
				touched.add(Path.of(f));
			}
			if (r.error != null) {
				for (Loc l : r.error.locations) {
					touched.add(Path.of(l.file));
				}
			}
			for (Path p : touched) {
				try {
					hashes.put(p, Files.readString(p).hashCode());
				} catch (IOException e) {
					// missing file
				}
			}
			Run previous = runs.put(root, new Run(root, r, hashes));
			if (previous != null) {
				touched.addAll(previous.hashes().keySet());
			}
			onResults.accept(touched);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "xacro run failed for " + root, e);
		}
	}

	private Result execute(Path root) throws IOException, InterruptedException {
		XacroSettings s = ws.settings();
		XacroSettings.Python py = s.python == null ? new XacroSettings.Python() : s.python;
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("args", s.args);
		Map<String, String> pkgs = new LinkedHashMap<>();
		ws.packages.all().forEach((k, v) -> pkgs.put(k, v.toString()));
		config.put("packages", pkgs);
		String configJson = new Gson().toJson(config);

		String exe = py.executable == null ? "python3" : py.executable;
		String setup = py.setupScript != null ? py.setupScript : autodetectSetup();
		List<String> cmd = new ArrayList<>();
		if (setup != null) {
			cmd.addAll(List.of("bash", "-c", "source \"$1\" >/dev/null 2>&1; shift; exec \"$@\"", "xacro-sidecar",
					setup, exe, "-P", sidecar().toString(), root.toString(), configJson));
		} else {
			cmd.addAll(List.of(exe, "-P", sidecar().toString(), root.toString(), configJson));
		}
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
				.directory(root.getParent().toFile());
		Process p = pb.start();
		p.getOutputStream().close();
		CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
			try (InputStream in = p.getInputStream()) {
				return in.readAllBytes();
			} catch (IOException e) {
				return new byte[0];
			}
		});
		int timeout = py.timeoutMs == null ? 20000 : py.timeoutMs;
		if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
			p.destroyForcibly();
			return null;
		}
		String json;
		try {
			json = new String(out.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
		} catch (Exception e) {
			return null;
		}
		if (json.isEmpty()) {
			unavailable = "could not run '" + exe + "' (exit code " + p.exitValue() + ")";
			return null;
		}
		Result r = new Gson().fromJson(json, Result.class);
		if (r.error != null && Boolean.TRUE.equals(r.error.import_error)) {
			unavailable = r.error.message;
			return null;
		}
		unavailable = null;
		return r;
	}

	private static String autodetectSetup() {
		if (System.getenv("AMENT_PREFIX_PATH") != null || System.getenv("ROS_DISTRO") != null) {
			return null;
		}
		Path opt = Path.of("/opt/ros");
		if (!Files.isDirectory(opt)) {
			return null;
		}
		try (var s = Files.list(opt)) {
			List<Path> distros = s.filter(d -> Files.isRegularFile(d.resolve("setup.bash"))).sorted().toList();
			return distros.isEmpty() ? null : distros.get(distros.size() - 1).resolve("setup.bash").toString();
		} catch (IOException e) {
			return null;
		}
	}

	private synchronized Path sidecar() throws IOException {
		if (sidecar == null || !Files.exists(sidecar)) {
			Path dir = Files.createTempDirectory("lemminx-xacro");
			Path f = dir.resolve("sidecar.py");
			try (InputStream in = getClass().getResourceAsStream("/xacro/lemminx/sidecar.py")) {
				Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
			}
			f.toFile().deleteOnExit();
			dir.toFile().deleteOnExit();
			sidecar = f;
		}
		return sidecar;
	}

	public String unavailableReason() {
		return unavailable;
	}

	// ------------------------------------------------------------------ queries

	private List<Run> runsFor(XacroDoc doc) {
		List<Run> out = new ArrayList<>();
		if (doc.path == null) {
			return out;
		}
		Integer hash = doc.dom.getText().hashCode();
		for (Run r : runs.values()) {
			Integer h = r.hashes().get(doc.path);
			if (h != null && h.equals(hash)) {
				out.add(r);
			}
		}
		return out;
	}

	public String evaluatedValue(XacroDoc doc, String name) {
		if (name == null) {
			return null;
		}
		for (Run r : runsFor(doc)) {
			String v = r.result().symbols.get(name);
			if (v != null) {
				return v;
			}
		}
		// also accept runs whose hash is stale (value is still informative)
		for (Run r : runs.values()) {
			if (doc.path != null && r.hashes().containsKey(doc.path)) {
				String v = r.result().symbols.get(name);
				if (v != null) {
					return v;
				}
			}
		}
		return null;
	}

	public List<Diagnostic> diagnosticsFor(XacroDoc doc) {
		List<Diagnostic> out = new ArrayList<>();
		for (Run run : runsFor(doc)) {
			String rootName = run.root().getFileName().toString();
			Result r = run.result();
			if (r.error != null && r.error.message != null) {
				List<Loc> locs = r.error.locations;
				Loc here = null;
				int idx = -1;
				for (int i = 0; i < locs.size(); i++) {
					if (doc.path.equals(Path.of(locs.get(i).file))) {
						here = locs.get(i);
						idx = i;
						break;
					}
				}
				if (here == null && doc.path.equals(run.root()) && locs.isEmpty()) {
					here = new Loc();
					here.line = 1;
				}
				if (here != null) {
					String msg = r.error.message;
					if (idx > 0) {
						msg = "error during expansion: " + msg;
					}
					Diagnostic d = new Diagnostic(rangeAt(doc, here.line, here.col), msg + "  [" + rootName + "]",
							DiagnosticSeverity.Error, SOURCE);
					List<DiagnosticRelatedInformation> rel = new ArrayList<>();
					for (Loc l : locs) {
						if (l != here) {
							rel.add(related(l, l.what == null || l.what.isEmpty() ? "here" : l.what));
						}
					}
					if (!rel.isEmpty()) {
						d.setRelatedInformation(rel);
					}
					out.add(d);
				}
			}
			for (Issue i : r.urdf) {
				DiagnosticSeverity sev = "warning".equals(i.severity) ? DiagnosticSeverity.Warning
						: DiagnosticSeverity.Error;
				String msg = i.message + "  [" + rootName + "]";
				if (doc.path.equals(Path.of(i.file))) {
					Diagnostic d = new Diagnostic(rangeAt(doc, i.line, i.col), msg, sev, SOURCE);
					List<DiagnosticRelatedInformation> rel = new ArrayList<>();
					for (Loc c : i.calls) {
						rel.add(related(c, "instantiated by this macro call"));
					}
					if (!rel.isEmpty()) {
						d.setRelatedInformation(rel);
					}
					out.add(d);
				} else {
					// report at the outermost macro call in this file that produced the element
					for (Loc c : i.calls) {
						if (doc.path.equals(Path.of(c.file))) {
							Diagnostic d = new Diagnostic(rangeAt(doc, c.line, c.col), "in expansion: " + msg, sev,
									SOURCE);
							Loc site = new Loc();
							site.file = i.file;
							site.line = i.line;
							site.col = i.col;
							d.setRelatedInformation(List.of(related(site, "element defined here")));
							out.add(d);
							break;
						}
					}
				}
			}
		}
		return out;
	}

	private static DiagnosticRelatedInformation related(Loc l, String message) {
		Position p = new Position(Math.max(0, l.line - 1), Math.max(0, l.col));
		return new DiagnosticRelatedInformation(new Location(Path.of(l.file).toUri().toString(), new Range(p, p)),
				message);
	}

	/** Range of the start tag name at (1-based line, 0-based column), or the line start. */
	private static Range rangeAt(XacroDoc doc, int line, int col) {
		try {
			int offset = doc.dom.offsetAt(new Position(Math.max(0, line - 1), Math.max(0, col)));
			DOMNode n = doc.dom.findNodeAt(offset + 1);
			if (n instanceof DOMElement e && e.getStart() == offset) {
				return XMLPositionUtility.selectStartTagName(e);
			}
			Position p = doc.dom.positionAt(offset);
			return new Range(p, new Position(p.getLine(), p.getCharacter() + 1));
		} catch (Exception e) {
			Position p = new Position(Math.max(0, line - 1), 0);
			return new Range(p, p);
		}
	}
}
