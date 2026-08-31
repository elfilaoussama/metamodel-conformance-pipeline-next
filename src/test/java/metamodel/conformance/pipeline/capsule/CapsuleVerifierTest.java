package metamodel.conformance.pipeline.capsule;

import metamodel.conformance.pipeline.ConformancePipeline;
import metamodel.conformance.pipeline.PipelineResult;
import metamodel.conformance.pipeline.adapter.java.SpoonJavaObserver;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.emf.ObservationXmiWriter;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapsuleVerifierTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsCapsuleWhenCanonicalXmiAndArchivedAlloyDisagreeEvenWithUpdatedXmiDigest() throws Exception {
        ConformancePipeline pipeline = new ConformancePipeline(new SpoonJavaObserver());
        PipelineResult result = pipeline.analyze(fixture("acyclic"), temporary.resolve("case"), Set.of());

        Observation original = new ObservationXmiReader().read(result.observationPath());
        List<ClassifierObservation> withoutParents = original.classifiers().stream()
                .map(classifier -> new ClassifierObservation(
                        classifier.id(),
                        classifier.qualifiedName(),
                        classifier.kind(),
                        classifier.sourcePath(),
                        classifier.startLine(),
                        classifier.endLine(),
                        List.of(),
                        classifier.declaredMemberKeys(),
                        classifier.inheritedMemberKeys()))
                .toList();
        Observation modified = new Observation(
                original.schemaVersion(),
                original.adapterId(),
                original.adapterVersion(),
                original.externalParents(),
                original.completeEvidence(),
                original.units(),
                withoutParents,
                original.members(),
                original.unresolvedParents(),
                original.diagnostics());
        new ObservationXmiWriter().write(modified, result.observationPath());

        VerificationCapsule capsule = CapsuleJson.MAPPER.readValue(
                result.capsulePath().toFile(), VerificationCapsule.class);
        VerificationCapsule rehashed = new VerificationCapsule(
                capsule.formatVersion(),
                capsule.toolId(),
                capsule.toolVersion(),
                capsule.schemaVersion(),
                capsule.adapterId(),
                capsule.adapterVersion(),
                capsule.sourceSetSha256(),
                capsule.observationPath(),
                Hashing.sha256(result.observationPath()),
                capsule.alloyPath(),
                capsule.alloySha256(),
                capsule.observationDiagnostics(),
                capsule.decisions());
        new CapsuleWriter().write(rehashed, result.capsulePath());

        CapsuleVerification verification = new CapsuleVerifier().verify(result.capsulePath());
        assertFalse(verification.valid());
        assertTrue(verification.message().contains("does not match canonical observation encoding"));
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(CapsuleVerifierTest.class.getResource("/fixtures/" + name).toURI());
    }
}
