package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.java.SpoonJavaObserver;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(DecisionStatus.CONFORMANT,
                first.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                first.invariant("local-inherited-separation").status());
        assertArrayEquals(Files.readAllBytes(first.observationPath()), Files.readAllBytes(second.observationPath()));
        assertArrayEquals(Files.readAllBytes(first.alloyPath()), Files.readAllBytes(second.alloyPath()));
        assertArrayEquals(Files.readAllBytes(first.capsulePath()), Files.readAllBytes(second.capsulePath()));
        assertEquals(new ObservationXmiReader().read(first.observationPath()), first.observation());
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
    void independentlyObservedInheritedViewMatchesAlloyDerivation() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("inherited-view"), temporary.resolve("inherited-view"), Set.of());

        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("local-inherited-separation").status());
        assertTrue(result.observation().completeEvidence()
                .contains(metamodel.conformance.pipeline.model.EvidenceKind.INHERITED_MEMBERS));
        assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void evaluatesCrossPackageAccessibilityInAlloy() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("package-private-inheritance"),
                temporary.resolve("package-private-inheritance"), Set.of());

        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("local-inherited-separation").status());
        assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void evaluatesSamePackageAccessibilityInAlloy() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("package-private-same-package"),
                temporary.resolve("package-private-same-package"), Set.of());

        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("local-inherited-separation").status());
    }

    @Test
    void doesNotResurrectPackagePrivateMembersAcrossAPackageBoundary() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("package-private-broken-chain"),
                temporary.resolve("package-private-broken-chain"), Set.of());

        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("inherited-view-consistency").status());
    }

    @Test
    void rejectsACorruptedCapsuleArtifact() throws Exception {
        PipelineResult result = pipeline.analyze(fixture("acyclic"), temporary.resolve("tampered"), Set.of());
        Files.writeString(result.alloyPath(), Files.readString(result.alloyPath()) + "\n// modified\n");

        assertFalse(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void rejectsCapsuleExecutionConfigurationDrift() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("acyclic"), temporary.resolve("configuration-drift"), Set.of());
        String capsule = Files.readString(result.capsulePath());
        String changed = capsule.replace("\"symmetry\" : 20", "\"symmetry\" : 0");
        assertFalse(capsule.equals(changed));
        Files.writeString(result.capsulePath(), changed);

        assertFalse(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void rejectsCapsuleProducerVersionDrift() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("acyclic"), temporary.resolve("producer-drift"), Set.of());
        String capsule = Files.readString(result.capsulePath());
        String changed = capsule.replace("\"toolVersion\" : \"0.9.0\"", "\"toolVersion\" : \"9.9.9\"");
        assertFalse(capsule.equals(changed));
        Files.writeString(result.capsulePath(), changed);

        assertFalse(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void emitsReplayableNotEvaluatedArtifactsForParseDiagnostics() throws Exception {
        PipelineResult result = pipeline.analyze(
                fixture("parse-error"), temporary.resolve("parse-error"), Set.of());

        assertEquals(1, result.observation().diagnostics().size());
        assertTrue(result.decisions().stream()
                .allMatch(decision -> decision.status() == DecisionStatus.NOT_EVALUATED));
        assertTrue(Files.readString(result.observationPath()).contains("diagnostics"));
        assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void removesAnOldCapsuleBeforePublishingNewArtifacts() throws Exception {
        Path output = temporary.resolve("failed-publication");
        Files.createDirectories(output);
        Path oldCapsule = output.resolve("verification-capsule.json");
        Files.writeString(oldCapsule, "old-but-apparently-complete");
        Observation base = TestObservations.acyclic();
        Observation unsupported = new Observation(
                "unsupported", base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                base.unresolvedParents(), base.diagnostics());
        ConformancePipeline failing = new ConformancePipeline((source, parents) -> unsupported);

        assertThrows(IllegalArgumentException.class,
                () -> failing.analyze(fixture("acyclic"), output, Set.of()));

        assertFalse(Files.exists(oldCapsule));
    }

    @Test
    void preservesAnOldCapsuleWhenExtractionNeverCompletes() throws Exception {
        Path output = temporary.resolve("extraction-failure");
        Files.createDirectories(output);
        Path oldCapsule = output.resolve("verification-capsule.json");
        Files.writeString(oldCapsule, "previous-complete-run");
        ConformancePipeline failing = new ConformancePipeline((source, parents) -> {
            throw new ObservationException("extraction failed");
        });

        assertThrows(ObservationException.class,
                () -> failing.analyze(fixture("acyclic"), output, Set.of()));

        assertEquals("previous-complete-run", Files.readString(oldCapsule));
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(ConformancePipelineTest.class.getResource("/fixtures/" + name).toURI());
    }
}
