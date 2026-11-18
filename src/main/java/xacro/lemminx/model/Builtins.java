package xacro.lemminx.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Names available in every {@code ${}} expression (see xacro's create_global_symbols). */
public final class Builtins {

	private Builtins() {
	}

	public static final List<String> PY_DIRECT = List.of("list", "dict", "map", "len", "str", "float", "int", "bool",
			"True", "False", "None", "min", "max", "round");

	public static final List<String> PY_NS = List.of("list", "dict", "map", "len", "str", "float", "int", "bool",
			"True", "False", "min", "max", "round", "abs", "all", "any", "complex", "divmod", "enumerate", "filter",
			"frozenset", "hash", "isinstance", "issubclass", "ord", "repr", "reversed", "slice", "set", "sum",
			"tuple", "type", "vars", "zip", "sorted", "range");

	public static final List<String> MATH = List.of("pi", "e", "tau", "inf", "nan", "acos", "acosh", "asin",
			"asinh", "atan", "atan2", "atanh", "cbrt", "ceil", "comb", "copysign", "cos", "cosh", "degrees", "dist",
			"erf", "erfc", "exp", "exp2", "expm1", "fabs", "factorial", "floor", "fmod", "frexp", "fsum", "gamma",
			"gcd", "hypot", "isclose", "isfinite", "isinf", "isnan", "isqrt", "lcm", "ldexp", "lgamma", "log",
			"log10", "log1p", "log2", "modf", "nextafter", "perm", "pow", "prod", "radians", "remainder", "sin",
			"sinh", "sqrt", "sumprod", "tan", "tanh", "trunc", "ulp");

	/** xacro.* namespace with short docs. */
	public static final Map<String, String> XACRO_NS = new LinkedHashMap<>();
	static {
		XACRO_NS.put("load_yaml", "load_yaml(filename) — load a YAML file; result supports dot access");
		XACRO_NS.put("abs_filename", "abs_filename(name) — make a path absolute relative to the current file");
		XACRO_NS.put("dotify", "dotify(dict) — wrap a dict for attribute-style access");
		XACRO_NS.put("arg", "arg(name) — value of a substitution arg (like $(arg name))");
		XACRO_NS.put("message", "message(*args) — print a message during processing");
		XACRO_NS.put("warning", "warning(*args) — print a warning with location");
		XACRO_NS.put("error", "error(*args) — print an error with location");
		XACRO_NS.put("print_location", "print_location() — print the current file/macro stack");
		XACRO_NS.put("fatal", "fatal(*args) — abort processing with an error");
		XACRO_NS.put("tokenize", "tokenize(s, sep=',; ', skip_empty=True) — split a string into a list");
	}

	/** Deprecated direct aliases of xacro.* functions that still work. */
	public static final Set<String> XACRO_DIRECT = Set.of("load_yaml", "abs_filename", "dotify");

	public static final Map<String, String> SUBSTITUTIONS = new LinkedHashMap<>();
	static {
		SUBSTITUTIONS.put("find", "$(find pkg) — absolute path of a ROS package (ament index / ROS_PACKAGE_PATH)");
		SUBSTITUTIONS.put("arg", "$(arg name) — value of a xacro:arg / command line argument name:=value");
		SUBSTITUTIONS.put("env", "$(env VAR) — environment variable (error if unset)");
		SUBSTITUTIONS.put("optenv", "$(optenv VAR default) — environment variable or default");
		SUBSTITUTIONS.put("eval", "$(eval expr) — evaluate a Python expression (roslaunch-style)");
		SUBSTITUTIONS.put("dirname", "$(dirname) — directory of the current file");
		SUBSTITUTIONS.put("anon", "$(anon name) — anonymous unique name");
	}

	/** Is the head of a dotted reference a builtin symbol? */
	public static boolean isBuiltinHead(String head) {
		return PY_DIRECT.contains(head) || MATH.contains(head) || XACRO_DIRECT.contains(head) || head.equals("math")
				|| head.equals("python") || head.equals("xacro");
	}
}
