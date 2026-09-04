package metamodel.conformance.pipeline.adapter.java;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives the analyzed project's Java language level without repository-specific assumptions. */
final class JavaCompilerProfile {
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    private final int release;

    private JavaCompilerProfile(int release) {
        if (release <= 0) {
            throw new IllegalArgumentException("Java release must be positive");
        }
        this.release = release;
    }

    static JavaCompilerProfile discover(Path sourceRoot) {
        return discover(sourceRoot, null);
    }

    static JavaCompilerProfile discover(Path sourceRoot, String sourceSet) {
        int runtimeRelease = Runtime.version().feature();
        if (sourceRoot == null) {
            return new JavaCompilerProfile(runtimeRelease);
        }
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        Path moduleRoot = JavaSourceSets.moduleRoot(normalizedRoot, sourceSet);
        Integer declared = declaredMavenRelease(moduleRoot.resolve("pom.xml"));
        if (declared == null && !moduleRoot.equals(normalizedRoot)) {
            declared = declaredMavenRelease(normalizedRoot.resolve("pom.xml"));
        }
        return new JavaCompilerProfile(declared == null ? runtimeRelease : declared);
    }

    int release() {
        return release;
    }

    private static Integer declaredMavenRelease(Path pom) {
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pom)) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(pom.toFile());

            Map<String, String> properties = properties(document);
            String pluginRelease = compilerPluginValue(document, "release");
            String pluginSource = compilerPluginValue(document, "source");
            String[] candidates = {
                    properties.get("maven.compiler.release"),
                    pluginRelease,
                    properties.get("maven.compiler.source"),
                    pluginSource,
                    properties.get("java.version")
            };
            for (String candidate : candidates) {
                Integer parsed = parseRelease(resolveProperties(candidate, properties));
                if (parsed != null) {
                    return parsed;
                }
            }
            return null;
        } catch (Exception ignored) {
            // Build metadata is optional evidence. A malformed/unsupported POM must not be guessed from.
            return null;
        }
    }

    private static Map<String, String> properties(Document document) {
        Map<String, String> result = new LinkedHashMap<>();
        NodeList containers = document.getElementsByTagNameNS("*", "properties");
        if (containers.getLength() == 0) {
            return result;
        }
        NodeList children = containers.item(0).getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element) {
                String name = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
                String value = element.getTextContent();
                if (value != null && !value.isBlank()) {
                    result.put(name, value.trim());
                }
            }
        }
        return result;
    }

    private static String compilerPluginValue(Document document, String field) {
        NodeList plugins = document.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            if (!(plugins.item(index) instanceof Element plugin)) {
                continue;
            }
            if (!"maven-compiler-plugin".equals(directChildText(plugin, "artifactId"))) {
                continue;
            }
            Element configuration = directChild(plugin, "configuration");
            if (configuration != null) {
                String value = directChildText(configuration, field);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element) {
                String name = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
                if (localName.equals(name)) {
                    return element;
                }
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String localName) {
        Element child = directChild(parent, localName);
        return child == null ? null : child.getTextContent();
    }

    private static String resolveProperties(String value, Map<String, String> properties) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String resolved = value.trim();
        int remaining = properties.size() + 1;
        while (remaining-- > 0) {
            Matcher matcher = PROPERTY_REFERENCE.matcher(resolved);
            StringBuffer output = new StringBuffer();
            boolean replaced = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) {
                    return null;
                }
                matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
                replaced = true;
            }
            matcher.appendTail(output);
            if (!replaced) {
                return resolved;
            }
            resolved = output.toString().trim();
        }
        return PROPERTY_REFERENCE.matcher(resolved).find() ? null : resolved;
    }

    private static Integer parseRelease(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(normalized.substring(0, end));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
