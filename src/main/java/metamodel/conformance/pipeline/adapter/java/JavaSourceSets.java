package metamodel.conformance.pipeline.adapter.java;

import java.nio.file.Path;
import java.util.Arrays;

final class JavaSourceSets {
    private static final String ROOT = "<root>";

    private JavaSourceSets() {
    }

    static String id(String canonicalPath) {
        String[] segments = canonicalPath.split("/");
        for (int index = 0; index + 2 < segments.length; index++) {
            if ("src".equals(segments[index]) && "java".equals(segments[index + 2])) {
                return String.join("/", Arrays.copyOfRange(segments, 0, index + 3));
            }
        }
        return ROOT;
    }

    static boolean isConventional(String sourceSet) {
        if (sourceSet == null || ROOT.equals(sourceSet)) {
            return false;
        }
        String[] segments = sourceSet.split("/");
        return segments.length >= 3
                && "src".equals(segments[segments.length - 3])
                && !segments[segments.length - 2].isBlank()
                && "java".equals(segments[segments.length - 1])
                && id(sourceSet).equals(sourceSet);
    }

    static String productionSibling(String sourceSet) {
        if (!isConventional(sourceSet)) {
            return null;
        }
        String[] segments = sourceSet.split("/");
        int sourceIndex = segments.length - 3;
        if ("main".equals(segments[sourceIndex + 1])) {
            return null;
        }
        segments[sourceIndex + 1] = "main";
        return String.join("/", segments);
    }

    static Path moduleRoot(Path root, String sourceSet) {
        String key = moduleKey(sourceSet);
        return ".".equals(key) ? root : root.resolve(key).normalize();
    }

    static String moduleKey(String sourceSet) {
        if (!isConventional(sourceSet) || sourceSet.startsWith("src/")) {
            return ".";
        }
        int marker = sourceSet.lastIndexOf("/src/");
        if (marker < 0) {
            return ".";
        }
        String prefix = sourceSet.substring(0, marker);
        return prefix.isBlank() ? "." : prefix;
    }
}
