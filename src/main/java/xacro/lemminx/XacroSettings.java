package xacro.lemminx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client settings, read from {@code xml.xacro.*}:
 *
 * <pre>
 * "xml.xacro.packagePaths": ["/extra/ws/src"],      // extra dirs scanned for package.xml
 * "xml.xacro.python.enabled": true,                 // run real xacro on save for exact diagnostics
 * "xml.xacro.python.executable": "python3",
 * "xml.xacro.python.setupScript": "/opt/ros/jazzy/setup.bash", // sourced before running python
 * "xml.xacro.args": { "use_sim": "true" }            // name:=value mappings for expansion
 * </pre>
 */
public class XacroSettings {

	public List<String> packagePaths = new ArrayList<>();
	public Python python = new Python();
	public Map<String, String> args = new LinkedHashMap<>();

	public static class Python {
		public Boolean enabled = Boolean.TRUE;
		public String executable = "python3";
		public String setupScript;
		public Integer timeoutMs = 20000;
	}

	/** Shape of the settings object the client sends: { "xml": { "xacro": {...} } }. */
	public static class Root {
		public Xml xml;
	}

	public static class Xml {
		public XacroSettings xacro;
	}
}
