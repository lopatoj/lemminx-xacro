package xacro.lemminx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xacro.lemminx.model.XacroDoc;
import xacro.lemminx.python.XacroPythonRunner;

class XacroExtensionTest {

  static final Path WS = Path.of("src/test/resources/ws").toAbsolutePath().normalize();
  static final Path URDF = WS.resolve("src/demo_description/urdf");

  XMLLanguageService ls;
  XacroExtension ext;

  @BeforeEach
  void setup() {
    ls = new XMLLanguageService();
    // the real server installs a document provider; schema resolution needs one
    ls.setDocumentProvider(uri -> null);
    ls.initializeIfNeeded();
    ext =
        ls.getExtensions().stream()
            .filter(XacroExtension.class::isInstance)
            .map(XacroExtension.class::cast)
            .findFirst()
            .orElseThrow();
    ext.workspace().setWorkspaceRoots(List.of(WS));
  }

  DOMDocument open(String file) throws IOException {
    Path p = URDF.resolve(file);
    return open(p, Files.readString(p));
  }

  DOMDocument open(Path p, String text) {
    DOMDocument d =
        DOMParser.getInstance().parse(text, p.toUri().toString(), ls.getResolverExtensionManager());
    ext.workspace().didOpenOrChange(d);
    return d;
  }

  List<Diagnostic> xacroDiagnostics(DOMDocument d) {
    return ls.doDiagnostics(d, new XMLValidationSettings(), Map.of(), () -> {}).stream()
        .filter(x -> "xacro".equals(x.getSource()))
        .toList();
  }

  static Position pos(DOMDocument d, String needle, int delta) throws Exception {
    int i = d.getText().indexOf(needle);
    assertTrue(i >= 0, needle);
    return d.positionAt(i + delta);
  }

  @Test
  void cleanRobotHasNoXacroErrors() throws Exception {
    List<Diagnostic> diags = xacroDiagnostics(open("robot.urdf.xacro"));
    assertEquals(List.of(), diags.stream().map(Diagnostic::getMessage).toList());
  }

  @Test
  void fragmentUsesIncluderContext() throws Exception {
    // wheel.xacro uses wheel_radius/wheel_width, defined by the files that include it
    List<Diagnostic> diags = xacroDiagnostics(open("wheel.xacro"));
    assertEquals(List.of(), diags.stream().map(Diagnostic::getMessage).toList());
  }

  @Test
  void includeInsideMacroSeesMacroScope() throws Exception {
    DOMDocument d = open("arm_control.xacro");
    assertEquals(List.of(), xacroDiagnostics(d).stream().map(Diagnostic::getMessage).toList());
    List<? extends LocationLink> param = ls.findDefinition(d, pos(d, "${prefix}arm", 4), () -> {});
    assertEquals(1, param.size());
    assertTrue(param.get(0).getTargetUri().endsWith("arm_macro.xacro"));
    List<? extends LocationLink> local = ls.findDefinition(d, pos(d, "${arm_len}", 4), () -> {});
    assertTrue(local.get(0).getTargetUri().endsWith("arm_macro.xacro"));
  }

  @Test
  void brokenFileDiagnostics() throws Exception {
    List<Diagnostic> diags = xacroDiagnostics(open("broken.urdf.xacro"));
    String codes =
        diags.stream().map(d -> d.getCode().getLeft()).sorted().collect(Collectors.joining(","));
    assertEquals("include-not-found,undefined-name,unknown-macro,unknown-param", codes);
    Diagnostic undefined =
        diags.stream()
            .filter(d -> d.getCode().getLeft().equals("undefined-name"))
            .findFirst()
            .orElseThrow();
    // broken file includes a missing file → scope incomplete → only a warning
    assertEquals(org.eclipse.lsp4j.DiagnosticSeverity.Warning, undefined.getSeverity());
    assertTrue(undefined.getMessage().contains("arm_len"));
  }

  @Test
  void missingRequiredParamAndBlocks() throws Exception {
    Path p = URDF.resolve("tmp_test.urdf.xacro");
    DOMDocument d =
        open(
            p,
            """
      <robot name="t" xmlns:xacro="http://www.ros.org/wiki/xacro">
        <xacro:property name="wheel_radius" value="1"/>
        <xacro:property name="wheel_width" value="1"/>
        <xacro:include filename="wheel.xacro"/>
        <xacro:wheel reflect="1"/>
        <xacro:property name="9bad" value="1"/>
        <link name="${undefined_thing}"/>
      </robot>""");
    List<Diagnostic> diags = xacroDiagnostics(d);
    String codes =
        diags.stream().map(x -> x.getCode().getLeft()).sorted().collect(Collectors.joining(","));
    assertEquals("missing-block,missing-param,property-name,undefined-name", codes);
    assertTrue(
        diags.stream()
            .anyMatch(
                x ->
                    x.getSeverity() == org.eclipse.lsp4j.DiagnosticSeverity.Error
                        && x.getMessage().contains("undefined_thing")));
  }

  @Test
  void definitionOfMacroPropertyAndInclude() throws Exception {
    DOMDocument d = open("robot.urdf.xacro");
    // LemMinX itself also links start tag <-> end tag; keep only our cross-file result
    List<? extends LocationLink> macro =
        ls.findDefinition(d, pos(d, "<xacro:wheel prefix=\"left\"", 9), () -> {}).stream()
            .filter(l -> !l.getTargetUri().equals(d.getDocumentURI()))
            .toList();
    assertEquals(1, macro.size());
    assertTrue(macro.get(0).getTargetUri().endsWith("wheel.xacro"));
    assertEquals(4, macro.get(0).getTargetSelectionRange().getStart().getLine());

    List<? extends LocationLink> prop =
        ls.findDefinition(d, pos(d, "${base_width/2}", 4), () -> {});
    assertEquals(1, prop.size());
    assertEquals(5, prop.get(0).getTargetSelectionRange().getStart().getLine());

    List<? extends LocationLink> inc =
        ls.findDefinition(d, pos(d, "$(find demo_description)/urdf/mat", 30), () -> {});
    assertEquals(1, inc.size());
    assertTrue(inc.get(0).getTargetUri().endsWith("materials.xacro"));

    // property used inside the macro library resolves to the includer's definition
    DOMDocument w = open("wheel.xacro");
    List<? extends LocationLink> ctx = ls.findDefinition(w, pos(w, "${wheel_radius}", 4), () -> {});
    assertFalse(ctx.isEmpty());
    // macro parameter
    List<? extends LocationLink> param =
        ls.findDefinition(w, pos(w, "${prefix}_wheel\"", 4), () -> {});
    assertEquals(1, param.size());
    assertEquals(4, param.get(0).getTargetSelectionRange().getStart().getLine());
  }

  @Test
  void hover() throws Exception {
    DOMDocument d = open("robot.urdf.xacro");
    Hover h = ls.doHover(d, pos(d, "<xacro:wheel prefix=\"left\"", 9), new SharedSettings());
    assertNotNull(h);
    String s = h.getContents().getRight().getValue();
    assertTrue(s.contains("xacro:macro wheel(prefix, reflect:=1, *joint_origin)"), s);
    assertTrue(s.contains("A wheel attached to base_link"), s);

    Hover p = ls.doHover(d, pos(d, "${base_width/2}", 4), new SharedSettings());
    assertTrue(p.getContents().getRight().getValue().contains("value=\"0.4\""));

    Hover joint = ls.doHover(d, pos(d, "<ros2_control", 3), new SharedSettings());
    assertTrue(joint.getContents().getRight().getValue().contains("ros2_control"));
  }

  List<String> complete(DOMDocument d, Position p) {
    return ls.doComplete(d, p, new SharedSettings()).getItems().stream()
        .map(CompletionItem::getLabel)
        .toList();
  }

  @Test
  void completion() throws Exception {
    Path p = URDF.resolve("tmp_complete.urdf.xacro");
    String text =
        """
    <robot name="t" xmlns:xacro="http://www.ros.org/wiki/xacro">
      <xacro:property name="wheel_radius" value="1"/>
      <xacro:property name="wheel_width" value="1"/>
      <xacro:include filename="$(find demo_description)/urdf/wheel.xacro" ns="w"/>
      <xacro:property name="mesh_dir" value="$(find demo_d)"/>
      <link name="${wh}"/>
      <link name="${w.}"/>
      <joint name="j" type="fixed"><  </joint>
      <xacro:w.wheel  />
      <
    </robot>""";
    DOMDocument d = open(p, text);
    List<String> props = complete(d, pos(d, "${wh}", 4));
    assertTrue(props.containsAll(List.of("wheel_radius", "wheel_width")), props.toString());
    List<String> ns = complete(d, pos(d, "${w.}", 4));
    assertFalse(ns.contains("wheel_radius"), ns.toString());
    List<String> pkgs = complete(d, pos(d, "demo_d)", 6));
    assertTrue(pkgs.contains("demo_description"), pkgs.toString());
    List<String> tags = complete(d, pos(d, "<\n</robot>", 1));
    assertTrue(
        tags.containsAll(List.of("xacro:property", "xacro:w.wheel", "link", "ros2_control")),
        tags.toString());
    List<String> jointChildren = complete(d, pos(d, "><  </joint>", 2));
    assertTrue(
        jointChildren.containsAll(List.of("parent", "child", "limit", "origin")),
        jointChildren.toString());
    assertFalse(jointChildren.contains("visual"));
    List<String> params = complete(d, pos(d, "<xacro:w.wheel  />", 15));
    assertTrue(params.containsAll(List.of("prefix", "reflect")), params.toString());
    assertFalse(params.contains("joint_origin"));
    List<String> wProps = complete(d, pos(d, "${w.}", 4));
    assertFalse(wProps.contains("wheel_radius"), wProps.toString());
  }

  @Test
  void documentLinks() throws Exception {
    DOMDocument d = open("robot.urdf.xacro");
    var links = ls.findDocumentLinks(d);
    assertTrue(links.stream().anyMatch(l -> l.getTarget().endsWith("materials.xacro")));
    assertTrue(links.stream().anyMatch(l -> l.getTarget().endsWith("base.stl")));
  }

  @Test
  void pythonSidecarMapsExpansionErrorsToSource() throws Exception {
    XacroPythonRunner runner = ext.python();
    var run = runner.runNow(URDF.resolve("dup.urdf.xacro"));
    Assumptions.assumeTrue(
        run != null, "xacro (Python) not available: " + runner.unavailableReason());

    DOMDocument dup = open("dup.urdf.xacro");
    List<Diagnostic> top = runner.diagnosticsFor(ext.workspace().model(dup));
    // duplicate names come from the second <xacro:wheel prefix="left"> call (line 10)
    assertTrue(
        top.stream()
            .anyMatch(
                x ->
                    x.getMessage().contains("duplicate link name 'left_wheel'")
                        && x.getRange().getStart().getLine() == 9),
        top.toString());

    DOMDocument wheel = open("wheel.xacro");
    List<Diagnostic> inMacro = runner.diagnosticsFor(ext.workspace().model(wheel));
    assertTrue(
        inMacro.stream()
            .anyMatch(
                x ->
                    x.getMessage().contains("duplicate link name")
                        && x.getRange().getStart().getLine() == 5
                        && x.getRelatedInformation() != null),
        inMacro.toString());

    var ok = runner.runNow(URDF.resolve("robot.urdf.xacro"));
    assertTrue(ok.result().ok, String.valueOf(ok.result().error));
    DOMDocument robot = open("robot.urdf.xacro");
    assertEquals("0.1", runner.evaluatedValue(ext.workspace().model(robot), "wheel_radius"));
    Hover h = ls.doHover(robot, pos(robot, "value=\"${base_width / 4}\"", 0), new SharedSettings());
    Hover r = ls.doHover(robot, pos(robot, "${base_width/2}", 4), new SharedSettings());
    assertTrue(
        r.getContents().getRight().getValue().contains("= `0.4`"),
        r.getContents().getRight().getValue());
    assertTrue(h == null || h.getContents() != null);

    var broken = runner.runNow(URDF.resolve("broken.urdf.xacro"));
    assertFalse(broken.result().ok);
    List<Diagnostic> b = runner.diagnosticsFor(ext.workspace().model(open("broken.urdf.xacro")));
    assertTrue(
        b.stream()
            .anyMatch(
                x ->
                    x.getMessage().contains("missing.xacro")
                        && x.getRange().getStart().getLine() == 5),
        b.toString());
  }

  @Test
  void noGrammarWarningSuppressedButNoSchemaErrors() throws Exception {
    for (String f : List.of("robot.urdf.xacro", "broken.urdf.xacro", "wheel.xacro")) {
      DOMDocument d = open(f);
      List<Diagnostic> xml =
          ls.doDiagnostics(d, new XMLValidationSettings(), Map.of(), () -> {}).stream()
              .filter(x -> !String.valueOf(x.getSource()).startsWith("xacro"))
              .toList();
      assertEquals(List.of(), xml.stream().map(Diagnostic::getMessage).toList(), f);
    }
    // a non-robot root keeps LemMinX's default behaviour
    DOMDocument other = open(URDF.resolve("tmp_other.xacro"), "<launch/>");
    assertTrue(
        ls.doDiagnostics(other, new XMLValidationSettings(), Map.of(), () -> {}).stream()
            .anyMatch(x -> x.getMessage().startsWith("No grammar constraints")));
  }

  @Test
  void plainUrdfChecks() throws Exception {
    Path p = URDF.resolve("tmp_plain.urdf");
    DOMDocument d =
        open(
            p,
            """
      <robot name="r">
        <link name="a"/>
        <link name="a"/>
        <joint name="j" type="fixed"><parent link="a"/><child link="nope"/></joint>
      </robot>""");
    List<String> msgs =
        ls.doDiagnostics(d, new XMLValidationSettings(), Map.of(), () -> {}).stream()
            .map(Diagnostic::getMessage)
            .toList();
    assertTrue(msgs.contains("Duplicate link name 'a'"), msgs.toString());
    assertTrue(msgs.contains("Joint references unknown link 'nope'"), msgs.toString());
    assertFalse(XacroDoc.detectXacro(d));
  }
}
