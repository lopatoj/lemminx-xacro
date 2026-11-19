package xacro.lemminx;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.uriresolver.IExternalGrammarLocationProvider;
import org.eclipse.lemminx.uriresolver.URIResolverExtension;

/**
 * Binds a permissive {@code <robot>} XSD to xacro/URDF files, so LemMinX considers them
 * grammar-bound and stops reporting "No grammar constraints (DTD or XML Schema)". The schema
 * accepts any content; real checking is done by this extension.
 *
 * <p>Registered after LemMinX's own file-association resolver, so a user's {@code
 * xml.fileAssociations} entry for these files still wins.
 */
public class XacroGrammarProvider
    implements URIResolverExtension, IExternalGrammarLocationProvider {

  private static final Pattern ROBOT_ROOT = Pattern.compile("^\\s*<robot[\\s>/]");
  private static final Pattern PROLOG =
      Pattern.compile("(?s)\\A(\\s*(<\\?.*?\\?>|<!--.*?-->|<!DOCTYPE[^>]*>))*");

  private final XacroWorkspace ws;
  private String schemaUri;

  public XacroGrammarProvider(XacroWorkspace ws) {
    this.ws = ws;
  }

  @Override
  public String getName() {
    return "xacro-permissive-grammar";
  }

  @Override
  public String resolve(String baseLocation, String publicId, String systemId) {
    return null;
  }

  @Override
  public Map<String, String> getExternalGrammarLocation(URI fileURI) {
    Path p;
    try {
      p = Path.of(fileURI);
    } catch (Exception e) {
      return null;
    }
    String name = p.getFileName() == null ? "" : p.getFileName().toString();
    if (!(name.endsWith(".xacro") || name.endsWith(".urdf")) || !hasRobotRoot(p)) {
      return null;
    }
    String uri = schemaUri();
    return uri == null ? null : Map.of(NO_NAMESPACE_SCHEMA_LOCATION, uri);
  }

  private boolean hasRobotRoot(Path p) {
    DOMDocument open = ws.openDocument(p.toAbsolutePath().normalize());
    if (open != null) {
      DOMElement root = open.getDocumentElement();
      return root != null && "robot".equals(root.getTagName());
    }
    try {
      String head = Files.readString(p);
      head = PROLOG.matcher(head).replaceFirst("");
      return ROBOT_ROOT.matcher(head).find();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private synchronized String schemaUri() {
    if (schemaUri == null) {
      try (InputStream in = getClass().getResourceAsStream("/xacro/lemminx/robot-permissive.xsd")) {
        Path dir = Files.createTempDirectory("lemminx-xacro-xsd");
        Path f = dir.resolve("robot-permissive.xsd");
        Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
        f.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        schemaUri = f.toUri().toString();
      } catch (IOException | RuntimeException e) {
        return null;
      }
    }
    return schemaUri;
  }
}
