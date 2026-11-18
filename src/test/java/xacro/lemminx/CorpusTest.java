package xacro.lemminx;

/**
 * Opt-in smoke test over real ROS workspaces (prints diagnostic counts, asserts nothing):
 *   mvn test -Dtest=CorpusTest -Dcorpus=$HOME/ws1,$HOME/ws2
 */
import java.nio.file.*; import java.util.*; import java.util.stream.*;
import org.eclipse.lemminx.dom.*; import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
class CorpusTest {
  @Test void corpus() throws Exception {
    Assumptions.assumeTrue(System.getProperty("corpus") != null, "set -Dcorpus=ws1,ws2 to run");
    for (String w : System.getProperty("corpus").split(",")) {
      Path ws = Path.of(w);
      var ls = new XMLLanguageService(); ls.initializeIfNeeded();
      var ext = ls.getExtensions().stream().filter(XacroExtension.class::isInstance).map(XacroExtension.class::cast).findFirst().get();
      ext.workspace().setWorkspaceRoots(List.of(ws.resolve("src")));
      List<Path> files;
      try (var s = Files.walk(ws.resolve("src"))) { files = s.filter(p -> p.toString().endsWith(".xacro")).toList(); }
      Map<String,Integer> counts = new TreeMap<>(); List<String> samples = new ArrayList<>();
      long t0 = System.currentTimeMillis();
      for (Path f : files) {
        DOMDocument d = DOMParser.getInstance().parse(Files.readString(f), f.toUri().toString(), null);
        ext.workspace().didOpenOrChange(d);
        for (var diag : ls.doDiagnostics(d, new XMLValidationSettings(), Map.of(), () -> {})) {
          if (!"xacro".equals(diag.getSource())) continue;
          String k = diag.getCode().getLeft() + "/" + diag.getSeverity();
          counts.merge(k, 1, Integer::sum);
          if (samples.size() < 25) samples.add(ws.relativize(f) + ":" + (diag.getRange().getStart().getLine()+1) + " " + diag.getMessage());
        }
      }
      System.out.println("## " + ws.getFileName() + " files=" + files.size() + " ms=" + (System.currentTimeMillis()-t0) + " " + counts);
      samples.forEach(s -> System.out.println("   " + s));
    }
  }
}
