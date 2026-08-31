package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.java.SpoonJavaObserver;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformancePipelineTest {
    @TempDir
    Path temporary;

    private final ConformancePipeline pipeline = new ConformancePipeline(new SpoonJavaObserver());

    @Test
    void producesByteIdenticalArtifactsForTheSameInput() throws Exception {
        PipelineResult first = pipeline.analyze(fixture("acyclic"), temporary.resolve("one"), Set.of());
        PipelineResult second = pipeline.analyze(fixture("acyclic"), temporary.resolve("two"), Set.of());

        assertEquals(DecisionStatus.CONFORMANT, first.invariant("acyclic-generalization").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                first.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                first.invariant("local-inherited-separation").status());
        assertArrayEquals(Files.readAllBytes(first.observationPath()), Files.readAllBytes(second.observationPath()));
        assertArrayEquals(Files.readAllBytes(first.alloyPath()), Files.readAllBytes(second.alloyPath()));
        assertArrayEquals(Files.readAllBytes(first.capsulePath()), Files.readAllBytes(second.capsulePath()));
        assertTrue(new CapsuleVerifier().verify(first.capsulePath()).valid());
    }

    @Test
    void reportsCycleAndFailsClosedOnMissingEvidence() throws Exception {
        PipelineResult cyclic = pipeline.analyze(fixture("cyclic"), temporary.resolve("cyclic"), Set.of());
        PipelineResult unresolved = pipeline.analyze(
                fixture("unresolved"), temporary.resolve("unresolved"), Set.of());

        assertEquals(DecisionStatus.NON_CONFORMANT, cyclic.invariant("acyclic-generalization").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, unresolved.invariant("acyclic-generalization").status());
        assertEquals(DecisionStatus.CONFORMANT, unresolved.decisions().stream()
                .filter(item -> "exclusive-declaration-ownership".equals(item.invariantId())).findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.CONFORMANT, unresolved.decisions().stream()
                .filter(item -> "local-namespace-uniqueness".equals(item.invariantId())).findFirst().orElseThrow().status());
    }

    @Test
    void rejectsACorruptedCapsuleArtifact() throws Exception {
        PipelineResult result = pipeline.analyze(fixture("acyclic"), temporary.resolve("tampered"), Set.of());
        Files.writeString(result.alloyPath(), Files.readString(result.alloyPath()) + "\n// modified\n");

        assertFalse(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(ConformancePipelineTest.class.getResource("/fixtures/" + name).toURI());
    }
}
