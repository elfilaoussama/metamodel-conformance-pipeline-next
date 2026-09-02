package metamodel.conformance.pipeline.adapter.java;

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

    static String productionSibling(String sourceSet) {
        if (ROOT.equals(sourceSet)) {
            return null;
        }
        String[] segments = sourceSet.split("/");
        int sourceIndex = segments.length - 3;
        if (sourceIndex < 0 || "main".equals(segments[sourceIndex + 1])) {
            return null;
        }
        segments[sourceIndex + 1] = "main";
        return String.join("/", segments);
    }
}
