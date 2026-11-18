package xacro.lemminx.model;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lemminx.dom.DOMElement;

import com.google.gson.Gson;

/** URDF (+gazebo, transmission, ros2_control) vocabulary loaded from urdf-vocabulary.json. */
public final class UrdfVocabulary {

	public static class Attr {
		public String doc;
		public boolean required;
		public List<String> values = new ArrayList<>();
	}

	public static class Element {
		public String doc;
		public Map<String, Attr> attrs = new LinkedHashMap<>();
		public List<String> children = new ArrayList<>();
		public List<String> text = new ArrayList<>();
	}

	private static class File {
		Map<String, Element> elements;
	}

	private static UrdfVocabulary instance;

	private final Map<String, Element> elements;

	private UrdfVocabulary(Map<String, Element> elements) {
		this.elements = elements;
		for (Element e : elements.values()) {
			if (e.attrs == null) {
				e.attrs = new LinkedHashMap<>();
			}
			if (e.children == null) {
				e.children = new ArrayList<>();
			}
			if (e.text == null) {
				e.text = new ArrayList<>();
			}
			for (Attr a : e.attrs.values()) {
				if (a.values == null) {
					a.values = new ArrayList<>();
				}
			}
		}
	}

	public static synchronized UrdfVocabulary get() {
		if (instance == null) {
			try (Reader r = new InputStreamReader(
					UrdfVocabulary.class.getResourceAsStream("/xacro/lemminx/urdf-vocabulary.json"),
					StandardCharsets.UTF_8)) {
				instance = new UrdfVocabulary(new Gson().fromJson(r, File.class).elements);
			} catch (Exception e) {
				instance = new UrdfVocabulary(new LinkedHashMap<>());
			}
		}
		return instance;
	}

	/** Look up an element definition, preferring a 'parent/tag' specific entry. */
	public Element lookup(String parent, String tag) {
		if (tag == null) {
			return null;
		}
		if (parent != null) {
			Element e = elements.get(parent + "/" + tag);
			if (e != null) {
				return e;
			}
		}
		return elements.get(tag);
	}

	/**
	 * Allowed child tag names for a (effective) parent. If the parent is unknown (e.g. directly
	 * inside a xacro:macro), returns every element so completion is still useful.
	 */
	public List<String> childrenOf(String parent) {
		if (parent == null) {
			Set<String> all = new LinkedHashSet<>(elements.get("robot").children);
			for (String k : elements.keySet()) {
				all.add(k.contains("/") ? k.substring(k.indexOf('/') + 1) : k);
			}
			return new ArrayList<>(all);
		}
		Element e = lookup(null, parent);
		return e == null ? List.of() : e.children;
	}

	/**
	 * The nearest ancestor that is a real URDF element, skipping xacro control tags
	 * (if/unless/element/macro calls). Returns null at a xacro:macro boundary or the root.
	 */
	public static String effectiveParent(DOMElement e) {
		DOMElement p = e == null ? null : e.getParentElement();
		while (p != null) {
			String local = XacroDoc.xacroLocal(p);
			if (local == null) {
				return p.getTagName();
			}
			if (local.equals("macro")) {
				return null;
			}
			p = p.getParentElement();
		}
		return null;
	}

	/** Effective parent for content directly inside {@code e} (e is the parent itself). */
	public static String effectiveSelf(DOMElement e) {
		DOMElement p = e;
		while (p != null) {
			String local = XacroDoc.xacroLocal(p);
			if (local == null) {
				return p.getTagName();
			}
			if (local.equals("macro")) {
				return null;
			}
			p = p.getParentElement();
		}
		return null;
	}
}
