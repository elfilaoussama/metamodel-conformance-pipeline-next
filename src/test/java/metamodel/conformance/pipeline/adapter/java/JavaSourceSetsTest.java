package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaSourceSetsTest {
    @Test
    void identifiesConventionalModuleSourceSets() {
        assertEquals("src/main/java", JavaSourceSets.id("src/main/java/example/A.java"));
        assertEquals("module-a/src/test/java",
                JavaSourceSets.id("module-a/src/test/java/example/A.java"));
        assertEquals("<root>", JavaSourceSets.id("example/A.java"));
    }

    @Test
    void mapsAuxiliarySourceSetsToTheirProductionSibling() {
        assertEquals("src/main/java", JavaSourceSets.productionSibling("src/test/java"));
        assertEquals("module/src/main/java",
                JavaSourceSets.productionSibling("module/src/integrationTest/java"));
        assertNull(JavaSourceSets.productionSibling("src/main/java"));
        assertNull(JavaSourceSets.productionSibling("<root>"));
    }
}
