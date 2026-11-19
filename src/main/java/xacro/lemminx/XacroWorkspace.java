package xacro.lemminx;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.model.XacroDoc.IncludeDef;
import xacro.lemminx.model.XacroDoc.PropertyDef;
import xacro.lemminx.ros.PackageIndex;

/**
 * Workspace-wide xacro index: open buffers + files on disk, include resolution, and the include
 * graph (forward and reverse) used to build a {@link Scope}.
 */
public final class XacroWorkspace {

  public enum IncludeStatus {
    OK,
    DYNAMIC,
    NOT_FOUND,
    UNKNOWN_PACKAGE,
    MISSING_ATTR
  }

  public record IncludeResult(IncludeStatus status, List<Path> files, String spec, String detail) {}

  private static final Pattern FIND = Pattern.compile("\\$\\(\\s*find\\s+([^\\s)]+)\\s*\\)");
  private static final Pattern ARG = Pattern.compile("\\$\\(\\s*arg\\s+([^\\s)]+)\\s*\\)");
  private static final Pattern DIRNAME = Pattern.compile("\\$\\(\\s*dirname\\s*\\)");
  private static final Pattern SIMPLE_EXPR =
      Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}");
  private static final Set<String> SKIP_DIRS =
      Set.of("build", "install", "log", "node_modules", "__pycache__");

  private record Cached(long mtime, XacroDoc doc) {}

  public final PackageIndex packages = new PackageIndex();
  private final Map<Path, DOMDocument> open = new HashMap<>();
  private final Map<Path, XacroDoc> openModels = new HashMap<>();
  private final Map<Path, Cached> disk = new HashMap<>();
  private final List<Path> workspaceRoots = new ArrayList<>();
  private final List<Path> extraRoots = new ArrayList<>();

  /** file → files it includes (statically resolvable ones). */
  private final Map<Path, Set<Path>> forward = new HashMap<>();

  private boolean graphBuilt;
  private XacroSettings settings = new XacroSettings();

  // ---------------------------------------------------------------- configuration

  public synchronized void setWorkspaceRoots(Collection<Path> roots) {
    workspaceRoots.clear();
    workspaceRoots.addAll(roots);
    refreshRoots();
  }

  public synchronized void setSettings(XacroSettings s) {
    settings = s == null ? new XacroSettings() : s;
    extraRoots.clear();
    for (String p : settings.packagePaths) {
      extraRoots.add(Path.of(p));
    }
    refreshRoots();
  }

  public synchronized XacroSettings settings() {
    return settings;
  }

  public synchronized List<Path> roots() {
    List<Path> all = new ArrayList<>(workspaceRoots);
    all.addAll(extraRoots);
    return all;
  }

  private void refreshRoots() {
    packages.setRoots(roots());
    graphBuilt = false;
  }

  // ---------------------------------------------------------------- documents

  public static Path toPath(String uri) {
    if (uri == null) {
      return null;
    }
    try {
      URI u = URI.create(uri);
      if ("file".equals(u.getScheme())) {
        return Path.of(u).toAbsolutePath().normalize();
      }
    } catch (Exception e) {
      // fall through
    }
    if (uri.startsWith("/")) {
      return Path.of(uri).toAbsolutePath().normalize();
    }
    return null;
  }

  public synchronized void didOpenOrChange(DOMDocument dom) {
    Path p = toPath(dom.getDocumentURI());
    if (p != null) {
      open.put(p, dom);
    }
  }

  public synchronized DOMDocument openDocument(Path p) {
    return open.get(p);
  }

  public synchronized void didClose(DOMDocument dom) {
    Path p = toPath(dom.getDocumentURI());
    if (p != null) {
      open.remove(p);
      openModels.remove(p);
    }
  }

  public synchronized void didSave(DOMDocument dom) {
    Path p = toPath(dom.getDocumentURI());
    if (p != null) {
      disk.remove(p);
      if (graphBuilt) {
        updateEdges(model(dom));
      }
    }
    if (dom.getDocumentURI() != null && dom.getDocumentURI().endsWith("package.xml")) {
      packages.invalidate();
    }
  }

  /** Model of a document the client sent us (always fresh for the given DOM instance). */
  public synchronized XacroDoc model(DOMDocument dom) {
    Path p = toPath(dom.getDocumentURI());
    XacroDoc cached = p == null ? null : openModels.get(p);
    if (cached != null && cached.dom == dom) {
      return cached;
    }
    XacroDoc d = XacroDoc.build(p, dom);
    if (p != null) {
      openModels.put(p, d);
      if (!open.containsKey(p)) {
        open.put(p, dom);
      }
    }
    return d;
  }

  /** Model for a path: open buffer if any, else parsed from disk (cached by mtime). */
  public synchronized XacroDoc load(Path p) {
    DOMDocument o = open.get(p);
    if (o != null) {
      return model(o);
    }
    try {
      long mtime = Files.getLastModifiedTime(p).toMillis();
      Cached c = disk.get(p);
      if (c != null && c.mtime == mtime) {
        return c.doc;
      }
      String text = Files.readString(p);
      DOMDocument dom = DOMParser.getInstance().parse(text, p.toUri().toString(), null);
      XacroDoc d = XacroDoc.build(p, dom);
      disk.put(p, new Cached(mtime, d));
      return d;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------- include resolution

  /**
   * Statically substitute {@code $(find)}, {@code $(dirname)}, {@code $(arg)} (defaults) and simple
   * {@code ${prop}} references with literal values. Returns null if something dynamic remains.
   */
  public String substitute(String spec, XacroDoc from, StringBuilder unknownPackage) {
    if (spec == null) {
      return null;
    }
    String s = spec;
    Matcher m = FIND.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      if (m.group(1).contains("$")) {
        return null; // $(find ${name}) — dynamic
      }
      Path pkg = packages.find(m.group(1));
      if (pkg == null) {
        if (unknownPackage != null) {
          unknownPackage.append(m.group(1));
        }
        return null;
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(pkg.toString()));
    }
    m.appendTail(sb);
    s = sb.toString();
    if (from != null && from.path != null) {
      s = DIRNAME.matcher(s).replaceAll(Matcher.quoteReplacement(from.path.getParent().toString()));
    }
    if (from != null) {
      Matcher a = ARG.matcher(s);
      sb = new StringBuilder();
      while (a.find()) {
        String def = null;
        String name = a.group(1);
        String mapped = settings.args.get(name);
        if (mapped != null) {
          def = mapped;
        } else {
          for (XacroDoc.ArgDef ad : from.args) {
            if (ad.name().equals(name)
                && ad.defaultValue() != null
                && !ad.defaultValue().contains("$")) {
              def = ad.defaultValue();
            }
          }
        }
        if (def == null) {
          return null;
        }
        a.appendReplacement(sb, Matcher.quoteReplacement(def));
      }
      a.appendTail(sb);
      s = sb.toString();
      Matcher e = SIMPLE_EXPR.matcher(s);
      sb = new StringBuilder();
      while (e.find()) {
        String lit = null;
        for (PropertyDef p : from.properties) {
          if (p.name().equals(e.group(1)) && p.value() != null && !p.value().contains("$")) {
            lit = p.value();
          }
        }
        if (lit == null) {
          return null;
        }
        e.appendReplacement(sb, Matcher.quoteReplacement(lit));
      }
      e.appendTail(sb);
      s = sb.toString();
    }
    return s.contains("$") ? null : s;
  }

  public IncludeResult resolveInclude(XacroDoc from, IncludeDef inc) {
    if (inc.filename() == null || inc.filename().isBlank()) {
      return new IncludeResult(IncludeStatus.MISSING_ATTR, List.of(), inc.filename(), null);
    }
    StringBuilder unknownPkg = new StringBuilder();
    String s = substitute(inc.filename(), from, unknownPkg);
    if (s == null) {
      if (unknownPkg.length() > 0) {
        return new IncludeResult(
            IncludeStatus.UNKNOWN_PACKAGE, List.of(), inc.filename(), unknownPkg.toString());
      }
      return new IncludeResult(IncludeStatus.DYNAMIC, List.of(), inc.filename(), null);
    }
    Path base = from.path == null ? Path.of(".") : from.path.getParent();
    Path target = base.resolve(s).normalize();
    if (s.matches(".*[*?\\[].*")) {
      List<Path> matches = glob(target);
      return new IncludeResult(
          matches.isEmpty() ? IncludeStatus.NOT_FOUND : IncludeStatus.OK, matches, s, null);
    }
    if (Files.isRegularFile(target)) {
      return new IncludeResult(IncludeStatus.OK, List.of(target), s, null);
    }
    return new IncludeResult(IncludeStatus.NOT_FOUND, List.of(), s, target.toString());
  }

  private static List<Path> glob(Path pattern) {
    // find the longest non-glob prefix directory
    Path dir = pattern.getRoot();
    for (Path part : pattern) {
      if (part.toString().matches(".*[*?\\[].*")) {
        break;
      }
      dir = dir == null ? part : dir.resolve(part);
    }
    if (dir == null || !Files.isDirectory(dir)) {
      return List.of();
    }
    PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<Path> out = new ArrayList<>();
    try (var s = Files.walk(dir, 4)) {
      s.filter(pm::matches).sorted().forEach(out::add);
    } catch (IOException e) {
      // ignore
    }
    return out;
  }

  /** Resolve a {@code package://pkg/rest} or {@code file://} URI used for meshes. */
  public Path resolveResourceUri(String uri) {
    if (uri.startsWith("package://")) {
      String rest = uri.substring("package://".length());
      int slash = rest.indexOf('/');
      if (slash < 0) {
        return null;
      }
      Path pkg = packages.find(rest.substring(0, slash));
      return pkg == null ? null : pkg.resolve(rest.substring(slash + 1)).normalize();
    }
    if (uri.startsWith("file://")) {
      return Path.of(uri.substring("file://".length()));
    }
    return null;
  }

  // ---------------------------------------------------------------- scope / include graph

  public synchronized Scope scopeFor(XacroDoc doc) {
    ensureGraph();
    Scope s = new Scope();
    Set<Path> visited = new HashSet<>();
    addForward(doc, "", s, visited, true);
    // context: who includes us (transitively)
    if (doc.path != null) {
      Set<Path> seen = new HashSet<>();
      // (includer, the file it includes on our path)
      List<Path[]> queue = new ArrayList<>();
      includersOf(doc.path).forEach(i -> queue.add(new Path[] {i, doc.path}));
      s.hasContext = !queue.isEmpty();
      while (!queue.isEmpty()) {
        Path[] edge = queue.remove(0);
        Path p = edge[0];
        XacroDoc inc = load(p);
        if (inc != null) {
          addMacroLocalsAroundInclude(inc, edge[1], s);
        }
        if (!seen.add(p)) {
          continue;
        }
        if (inc != null) {
          addForward(inc, "", s, visited, false);
        }
        includersOf(p).forEach(i -> queue.add(new Path[] {i, p}));
      }
    }
    return s;
  }

  /**
   * An include inside a macro body is evaluated in that macro's scope, so the included file sees
   * the macro's parameters and local properties.
   */
  private void addMacroLocalsAroundInclude(XacroDoc includer, Path included, Scope s) {
    for (IncludeDef inc : includer.includes) {
      if (!resolveInclude(includer, inc).files().contains(included)) {
        continue;
      }
      List<org.eclipse.lemminx.dom.DOMElement> macros = XacroDoc.enclosingMacros(inc.element());
      if (macros.isEmpty()) {
        continue;
      }
      for (PropertyDef p : includer.properties) {
        if (p.enclosingMacro() != null && macros.contains(p.enclosingMacro())) {
          s.properties.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
          s.exported.add(p);
        }
      }
      for (XacroDoc.MacroDef m : includer.macros) {
        if (macros.contains(m.element())) {
          m.params().forEach(par -> s.contextParams.putIfAbsent(par.name(), m));
        }
      }
    }
  }

  private void addForward(XacroDoc d, String prefix, Scope s, Set<Path> visited, boolean own) {
    if (d.path != null && !visited.add(d.path)) {
      return;
    }
    s.add(d, prefix, own);
    for (IncludeDef inc : d.includes) {
      IncludeResult r = resolveInclude(d, inc);
      if (r.status() != IncludeStatus.OK) {
        if (own && !inc.optional()) {
          s.incomplete = true;
        }
        continue;
      }
      String childPrefix = prefix;
      if (inc.ns() != null && !inc.ns().isEmpty()) {
        childPrefix = prefix + inc.ns() + ".";
        s.namespaces.add(prefix + inc.ns());
      }
      for (Path f : r.files()) {
        XacroDoc child = load(f);
        if (child != null) {
          addForward(child, childPrefix, s, visited, false);
        }
      }
    }
  }

  public synchronized Set<Path> includersOf(Path p) {
    ensureGraph();
    Set<Path> out = new LinkedHashSet<>();
    forward.forEach(
        (from, tos) -> {
          if (tos.contains(p)) {
            out.add(from);
          }
        });
    return out;
  }

  /** Files that are not included by anything (candidates for running real xacro). */
  public synchronized Set<Path> rootsOf(Path p) {
    ensureGraph();
    Set<Path> roots = new LinkedHashSet<>();
    Set<Path> seen = new HashSet<>();
    List<Path> queue = new ArrayList<>(List.of(p));
    while (!queue.isEmpty()) {
      Path c = queue.remove(0);
      if (!seen.add(c)) {
        continue;
      }
      Set<Path> up = includersOf(c);
      if (up.isEmpty()) {
        roots.add(c);
      }
      queue.addAll(up);
    }
    return roots;
  }

  private void ensureGraph() {
    if (graphBuilt) {
      return;
    }
    forward.clear();
    for (Path root : roots()) {
      for (Path f : findXacroFiles(root)) {
        XacroDoc d = load(f);
        if (d != null) {
          updateEdges(d);
        }
      }
    }
    for (Path p : new ArrayList<>(open.keySet())) {
      XacroDoc d = load(p);
      if (d != null) {
        updateEdges(d);
      }
    }
    graphBuilt = true;
  }

  private void updateEdges(XacroDoc d) {
    if (d.path == null) {
      return;
    }
    Set<Path> tos = new LinkedHashSet<>();
    for (IncludeDef inc : d.includes) {
      IncludeResult r = resolveInclude(d, inc);
      tos.addAll(r.files());
    }
    forward.put(d.path, tos);
  }

  private static List<Path> findXacroFiles(Path root) {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(root)) {
      return out;
    }
    try {
      Files.walkFileTree(
          root,
          Set.of(),
          12,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
              if (!dir.equals(root) && (SKIP_DIRS.contains(n) || n.startsWith("."))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String n = file.getFileName().toString();
              if (n.endsWith(".xacro") || n.endsWith(".urdf")) {
                out.add(file.toAbsolutePath().normalize());
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // ignore
    }
    return out;
  }
}
