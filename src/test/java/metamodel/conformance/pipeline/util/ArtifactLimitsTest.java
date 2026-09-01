package metamodel.conformance.pipeline.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactLimitsTest {
    @TempDir
    Path temporary;

    @Test
    void measuresUtf8BytesAtTheExactBoundary() throws Exception {
        ArtifactLimits.requireUtf8Within("test artifact", "éé", 4);

        assertThrows(IOException.class,
                () -> ArtifactLimits.requireUtf8Within("test artifact", "ééx", 4));
    }

    @Test
    void rejectsOversizedReplacementBeforeTouchingExistingTarget() throws Exception {
        Path target = temporary.resolve("artifact.txt");
        Files.writeString(target, "previous");

        assertThrows(IOException.class,
                () -> AtomicFiles.writeUtf8(target, "12345", 4, "test artifact"));

        assertEquals("previous", Files.readString(target));
    }
}
