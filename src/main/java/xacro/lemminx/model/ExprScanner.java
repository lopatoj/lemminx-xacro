package xacro.lemminx.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a text/attribute value into xacro segments, mirroring xacro's QuickLexer:
 * <ul>
 * <li>{@code $${...}} / {@code $$(...)} — escaped, not evaluated</li>
 * <li>{@code ${...}} — Python expression</li>
 * <li>{@code $(...)} — substitution arg (find, arg, env, optenv, eval, dirname, anon)</li>
 * </ul>
 * Unlike xacro, an unterminated {@code ${} at the end of the string is still reported
 * (with {@code closed == false}) so completion works while typing.
 */
public final class ExprScanner {

	public enum Kind {
		EXPR, EXTENSION
	}

	/**
	 * @param start  offset of the leading '$' relative to the scanned string
	 * @param end    offset just past the closing brace/paren (or end of string if unclosed)
	 * @param body   text between the delimiters
	 * @param bodyStart offset of the first body character
	 */
	public record Segment(Kind kind, int start, int end, String body, int bodyStart, boolean closed) {
	}

	private ExprScanner() {
	}

	public static List<Segment> scan(String s) {
		List<Segment> out = new ArrayList<>();
		if (s == null || s.indexOf('$') < 0) {
			return out;
		}
		int i = 0;
		int n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			if (c != '$') {
				i++;
				continue;
			}
			// count consecutive '$'
			int j = i;
			while (j < n && s.charAt(j) == '$') {
				j++;
			}
			if (j >= n) {
				break;
			}
			char open = s.charAt(j);
			if (open != '{' && open != '(') {
				i = j;
				continue;
			}
			if (j - i > 1) {
				// escaped: $${ ... → literal; skip the opener
				i = j + 1;
				continue;
			}
			char close = open == '{' ? '}' : ')';
			int k = s.indexOf(close, j + 1);
			Kind kind = open == '{' ? Kind.EXPR : Kind.EXTENSION;
			if (k < 0) {
				out.add(new Segment(kind, i, n, s.substring(j + 1), j + 1, false));
				break;
			}
			out.add(new Segment(kind, i, k + 1, s.substring(j + 1, k), j + 1, true));
			i = k + 1;
		}
		return out;
	}
}
