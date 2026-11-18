package xacro.lemminx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import xacro.lemminx.model.ExprScanner;
import xacro.lemminx.model.MacroParam;
import xacro.lemminx.model.PyRefs;

class ModelTest {

	@Test
	void macroParams() {
		List<MacroParam> ps = MacroParam.parse("prefix reflect:=1 *origin **blocks  name:=^ x:=^|${a*2} s:='a b'");
		assertEquals(List.of("prefix", "reflect", "origin", "blocks", "name", "x", "s"),
				ps.stream().map(MacroParam::name).toList());
		assertTrue(ps.get(0).isRequired());
		assertEquals("1", ps.get(1).defaultValue());
		assertEquals(1, ps.get(2).blockStars());
		assertEquals(2, ps.get(3).blockStars());
		assertTrue(ps.get(4).forward());
		assertFalse(ps.get(4).isRequired());
		assertEquals("${a*2}", ps.get(5).defaultValue());
		assertEquals("'a b'", ps.get(6).defaultValue());
		String spec = "prefix reflect:=1 *origin";
		assertEquals(spec.indexOf("reflect"), ps.get(1).offset());
		assertEquals(spec.indexOf("origin"), ps.get(2).offset());
	}

	@Test
	void exprScanner() {
		List<ExprScanner.Segment> s = ExprScanner.scan("a ${x+1} $(find pkg)/f $${esc} ${open");
		assertEquals(3, s.size());
		assertEquals("x+1", s.get(0).body());
		assertEquals(ExprScanner.Kind.EXTENSION, s.get(1).kind());
		assertFalse(s.get(2).closed());
	}

	@Test
	void pyRefs() {
		List<String> names = PyRefs.scan("radians(90) * ns.prop + d['k'] if flag else len(x for x in items) "
				+ "+ f(key=val) + 'str.lit' + 1.5e-3 + math.pi + (lambda q: q*2)(3) + obj.attr.sub")
				.stream().map(PyRefs.Ref::name).toList();
		assertEquals(List.of("radians", "ns.prop", "d", "flag", "len", "items", "f", "val", "math.pi",
				"obj.attr.sub"), names);
		assertEquals("math.p", PyRefs.partialNameBefore("2*math.p", 8));
	}
}
