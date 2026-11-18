package xacro.lemminx.ros;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves ROS package names to directories, like {@code $(find pkg)} / {@code package://pkg}.
 * <p>
 * Sources, highest priority first: workspace folders and configured paths (scanned for
 * {@code package.xml}; source packages win over installed copies so go-to-definition lands
 * in editable files), then {@code AMENT_PREFIX_PATH} (ament resource index), then
 * {@code ROS_PACKAGE_PATH}.
 */
public final class PackageIndex {

	private static final Set<String> SKIP_DIRS = Set.of("build", "install", "log", ".git", "node_modules", ".pixi",
			".venv", "__pycache__", ".cache");
	private static final Pattern NAME = Pattern.compile("<name>\\s*([^<\\s]+)\\s*</name>");

	private final Map<String, Path> source = new ConcurrentHashMap<>();
	private final Map<String, Path> installed = new ConcurrentHashMap<>();
	private volatile boolean scanned;
	private List<Path> roots = List.of();

	public synchronized void setRoots(Collection<Path> roots) {
		this.roots = new ArrayList<>(roots);
		scanned = false;
	}

	public synchronized void invalidate() {
		scanned = false;
	}

	public Path find(String pkg) {
		ensureScanned();
		Path p = source.get(pkg);
		return p != null ? p : installed.get(pkg);
	}

	public Map<String, Path> all() {
		ensureScanned();
		Map<String, Path> m = new TreeMap<>(installed);
		m.putAll(source);
		return m;
	}

	private synchronized void ensureScanned() {
		if (scanned) {
			return;
		}
		source.clear();
		installed.clear();
		for (Path r : roots) {
			scanSource(r, 6);
		}
		String ament = System.getenv("AMENT_PREFIX_PATH");
		if (ament != null) {
			for (String prefix : ament.split(":")) {
				scanAmentPrefix(Path.of(prefix));
			}
		}
		String rpp = System.getenv("ROS_PACKAGE_PATH");
		if (rpp != null) {
			for (String p : rpp.split(":")) {
				if (!p.isBlank()) {
					scanSourceInto(Path.of(p), 6, installed);
				}
			}
		}
		scanned = true;
	}

	private void scanSource(Path root, int maxDepth) {
		scanSourceInto(root, maxDepth, source);
	}

	private static void scanSourceInto(Path root, int maxDepth, Map<String, Path> into) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try {
			Files.walkFileTree(root, Set.of(), maxDepth, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
					if (!dir.equals(root) && (SKIP_DIRS.contains(n) || n.startsWith("."))) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					if (Files.exists(dir.resolve("COLCON_IGNORE")) || Files.exists(dir.resolve("CATKIN_IGNORE"))) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					Path manifest = dir.resolve("package.xml");
					if (Files.isRegularFile(manifest)) {
						String name = readName(manifest);
						if (name != null) {
							into.putIfAbsent(name, dir);
						}
						return FileVisitResult.SKIP_SUBTREE; // packages don't nest
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// ignore unreadable roots
		}
	}

	private void scanAmentPrefix(Path prefix) {
		Path idx = prefix.resolve("share/ament_index/resource_index/packages");
		if (!Files.isDirectory(idx)) {
			return;
		}
		try (var s = Files.list(idx)) {
			s.forEach(marker -> {
				String pkg = marker.getFileName().toString();
				installed.putIfAbsent(pkg, prefix.resolve("share").resolve(pkg));
			});
		} catch (IOException e) {
			// ignore
		}
	}

	static String readName(Path manifest) {
		try {
			String s = Files.readString(manifest);
			Matcher m = NAME.matcher(s);
			return m.find() ? m.group(1) : null;
		} catch (IOException e) {
			return null;
		}
	}
}
