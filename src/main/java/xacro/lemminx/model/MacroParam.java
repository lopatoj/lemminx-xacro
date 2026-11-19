package xacro.lemminx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One parameter of a {@code xacro:macro params="..."} spec.
 *
 * <p>Port of xacro's {@code parse_macro_arg}: {@code name}, {@code name:=default}, {@code
 * name=default}, {@code name:=^}, {@code name:=^|default}, {@code *block}, {@code **multi_block}.
 *
 * @param offset offset of the name inside the params string
 */
public record MacroParam(
    String name,
    String defaultValue,
    boolean hasDefault,
    boolean forward,
    int blockStars,
    int offset) {

  // Same regex as xacro's re_macro_arg / default_value
  private static final String DEFAULT_VALUE =
      "\\$\\{.*?\\}|\\$\\(.*?\\)|(?:'.*?'|\".*?\"|[^\\s'\"]+)+|";
  private static final Pattern RE_MACRO_ARG =
      Pattern.compile(
          "^(\\s*)([^\\s:=]+?)\\s*:?=\\s*(\\^\\|?)?(" + DEFAULT_VALUE + ")(?:\\s+|$)(.*)",
          Pattern.DOTALL);

  public boolean isBlock() {
    return blockStars > 0;
  }

  public boolean isRequired() {
    return !isBlock() && !hasDefault;
  }

  /** Name as used in the macro body / call attribute (without stars). */
  public String bareName() {
    return name;
  }

  public String display() {
    StringBuilder sb = new StringBuilder();
    sb.append("*".repeat(blockStars)).append(name);
    if (hasDefault) {
      sb.append(":=");
      if (forward) {
        sb.append('^');
        if (defaultValue != null) {
          sb.append('|');
        }
      }
      if (defaultValue != null) {
        sb.append(defaultValue);
      }
    }
    return sb.toString();
  }

  public static List<MacroParam> parse(String params) {
    List<MacroParam> out = new ArrayList<>();
    if (params == null) {
      return out;
    }
    String rest = params;
    int consumed = 0;
    while (!rest.isBlank()) {
      Matcher m = RE_MACRO_ARG.matcher(rest);
      String raw;
      int nameOffset;
      if (m.matches()) {
        raw = m.group(2);
        nameOffset = consumed + m.group(1).length();
        String fwd = m.group(3);
        String def = m.group(4);
        boolean forward = fwd != null;
        String defaultValue = def == null || def.isEmpty() ? null : def;
        out.add(make(raw, defaultValue, true, forward, nameOffset));
        int restStart = m.start(5);
        consumed += restStart;
        rest = m.group(5);
      } else {
        int lead = 0;
        while (lead < rest.length() && Character.isWhitespace(rest.charAt(lead))) {
          lead++;
        }
        int end = lead;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
          end++;
        }
        raw = rest.substring(lead, end);
        nameOffset = consumed + lead;
        out.add(make(raw, null, false, false, nameOffset));
        consumed += end;
        rest = rest.substring(end);
      }
    }
    return out;
  }

  private static MacroParam make(
      String raw, String def, boolean hasDefault, boolean forward, int offset) {
    int stars = 0;
    while (stars < raw.length() && raw.charAt(stars) == '*') {
      stars++;
    }
    return new MacroParam(raw.substring(stars), def, hasDefault, forward, stars, offset + stars);
  }
}
