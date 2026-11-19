package xacro.lemminx.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Very small Python expression scanner. It does not evaluate anything; it finds the free names an
 * expression refers to so we can resolve them against xacro properties.
 *
 * <p>Handles: string literals (incl. prefixes), numbers, dotted names ({@code ns.prop}, {@code
 * math.pi}), keyword arguments in calls ({@code f(x=1)}), and names bound by {@code lambda} /
 * comprehension {@code for} clauses (excluded from the result).
 */
public final class PyRefs {

  /** A reference to a (possibly dotted) name. Offsets are relative to the scanned text. */
  public record Ref(String name, List<String> parts, int start, int end) {
    public String head() {
      return parts.get(0);
    }
  }

  private static final Set<String> KEYWORDS =
      Set.of(
          "and", "or", "not", "if", "else", "for", "in", "is", "lambda", "None", "True", "False",
          "await", "async", "yield", "del", "pass", "return");

  private PyRefs() {}

  public static List<Ref> scan(String expr) {
    List<Ref> refs = new ArrayList<>();
    Set<String> bound = new HashSet<>();
    int n = expr.length();
    int i = 0;
    int depth = 0;
    char prevSig = 0; // previous significant (non-space) character
    String prevWord = null;
    boolean bindingFor = false; // between 'for' and 'in'
    boolean bindingLambda = false; // between 'lambda' and ':'
    while (i < n) {
      char c = expr.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '\'' || c == '"') {
        i = skipString(expr, i);
        prevSig = 's';
        prevWord = null;
        continue;
      }
      if (Character.isDigit(c)
          || (c == '.'
              && i + 1 < n
              && Character.isDigit(expr.charAt(i + 1))
              && !isIdentPart(prevSig))) {
        int j = i + 1;
        while (j < n
            && (Character.isLetterOrDigit(expr.charAt(j))
                || expr.charAt(j) == '.'
                || expr.charAt(j) == '_'
                || ((expr.charAt(j) == '+' || expr.charAt(j) == '-')
                    && (expr.charAt(j - 1) == 'e' || expr.charAt(j - 1) == 'E')))) {
          j++;
        }
        i = j;
        prevSig = '0';
        prevWord = null;
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && isIdentPart(expr.charAt(j))) {
          j++;
        }
        String word = expr.substring(i, j);
        // string prefix like r'..', b"..", f'..'
        if (j < n
            && (expr.charAt(j) == '\'' || expr.charAt(j) == '"')
            && word.length() <= 2
            && word.toLowerCase().matches("[rbuf]+")) {
          i = skipString(expr, j);
          prevSig = 's';
          prevWord = null;
          continue;
        }
        if (KEYWORDS.contains(word)) {
          if (word.equals("for")) {
            bindingFor = true;
          } else if (word.equals("in")) {
            bindingFor = false;
          } else if (word.equals("lambda")) {
            bindingLambda = true;
          }
          prevSig = 'k';
          prevWord = word;
          i = j;
          continue;
        }
        if (prevSig == '.') {
          // attribute of a non-name expression, e.g. "foo().bar" or "'x'.join" — ignore
          i = j;
          prevSig = 'a';
          prevWord = word;
          continue;
        }
        // collect dotted chain
        List<String> parts = new ArrayList<>();
        parts.add(word);
        int end = j;
        int k = j;
        while (true) {
          int m = k;
          while (m < n && expr.charAt(m) == ' ') {
            m++;
          }
          if (m < n
              && expr.charAt(m) == '.'
              && m + 1 < n
              && (Character.isLetter(expr.charAt(m + 1)) || expr.charAt(m + 1) == '_')) {
            int p = m + 1;
            while (p < n && isIdentPart(expr.charAt(p))) {
              p++;
            }
            parts.add(expr.substring(m + 1, p));
            end = p;
            k = p;
          } else {
            break;
          }
        }
        if (bindingFor || bindingLambda) {
          bound.add(word);
        } else if (!isKeywordArg(expr, end, depth, prevSig)) {
          refs.add(new Ref(String.join(".", parts), parts, i, end));
        }
        i = end;
        prevSig = 'a';
        prevWord = word;
        continue;
      }
      switch (c) {
        case '(', '[', '{' -> depth++;
        case ')', ']', '}' -> depth = Math.max(0, depth - 1);
        case ':' -> bindingLambda = false;
        default -> {}
      }
      prevSig = c;
      prevWord = null;
      i++;
    }
    if (!bound.isEmpty()) {
      refs.removeIf(r -> bound.contains(r.head()));
    }
    return refs;
  }

  private static boolean isKeywordArg(String expr, int end, int depth, char prevSig) {
    if (depth == 0 || !(prevSig == '(' || prevSig == ',')) {
      return false;
    }
    int m = end;
    while (m < expr.length() && expr.charAt(m) == ' ') {
      m++;
    }
    return m < expr.length()
        && expr.charAt(m) == '='
        && (m + 1 >= expr.length() || expr.charAt(m + 1) != '=');
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static int skipString(String s, int i) {
    char q = s.charAt(i);
    boolean triple = i + 2 < s.length() && s.charAt(i + 1) == q && s.charAt(i + 2) == q;
    int j = i + (triple ? 3 : 1);
    while (j < s.length()) {
      char c = s.charAt(j);
      if (c == '\\') {
        j += 2;
        continue;
      }
      if (c == q) {
        if (!triple) {
          return j + 1;
        }
        if (j + 2 < s.length() && s.charAt(j + 1) == q && s.charAt(j + 2) == q) {
          return j + 3;
        }
      }
      j++;
    }
    return s.length();
  }

  /**
   * Returns the partial dotted identifier ending at {@code pos} (for completion), e.g. {@code
   * "math.p"} for {@code "2*math.p|"}. Empty string if the cursor is not after an identifier
   * character or dot.
   */
  public static String partialNameBefore(String expr, int pos) {
    int i = Math.min(pos, expr.length());
    while (i > 0 && (isIdentPart(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) {
      i--;
    }
    String s = expr.substring(i, Math.min(pos, expr.length()));
    if (!s.isEmpty() && Character.isDigit(s.charAt(0))) {
      return "";
    }
    return s;
  }
}
