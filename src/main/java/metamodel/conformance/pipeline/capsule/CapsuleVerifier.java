package metamodel.conformance.pipeline.capsule;

import metamodel.conformance.pipeline.alloy.AlloyObligationRunner;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class CapsuleVerifier {
    private static final long MAX_CAPSULE_BYTES = 1024L * 1024L;
    private static final long MAX_ALLOY_BYTES = 16L * 1024L * 1024L;

    public CapsuleVerification verify(Path capsulePath) {
        try {
            Path capsuleFile = capsulePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(capsuleFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(capsulePath)
                    || Files.size(capsuleFile) > MAX_CAPSULE_BYTES) {
                return invalid("capsule is not a regular file or exceeds the size limit");
            }
            VerificationCapsule capsule = CapsuleJson.MAPPER.readValue(capsuleFile.toFile(), VerificationCapsule.class);
            if (!"3".equals(capsule.formatVersion()) || capsule.decisions().isEmpty()) {
                return invalid("unsupported or empty capsule format");
            }
            Path root = capsuleFile.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path observationPath = resolveArtifact(root, capsule.observationPath());
            Path alloyPath = resolveArtifact(root, capsule.alloyPath());
            if (Files.size(alloyPath) > MAX_ALLOY_BYTES) {
                return invalid("Alloy artifact exceeds the size limit");
            }
            if (!digestEquals(capsule.observationSha256(), Hashing.sha256(observationPath))) {
                return invalid("observation digest mismatch");
            }
            if (!digestEquals(capsule.alloySha256(), Hashing.sha256(alloyPath))) {
                return invalid("Alloy digest mismatch");
            }

            Observation observation = new ObservationXmiReader().read(observationPath);
            if (!capsule.schemaVersion().equals(observation.schemaVersion())
                    || !capsule.adapterId().equals(observation.adapterId())
                    || !capsule.adapterVersion().equals(observation.adapterVersion())) {
                return invalid("observation metadata does not match capsule");
            }
            if (!digestEquals(capsule.sourceSetSha256(), Hashing.sourceSetDigest(observation.units()))) {
                return invalid("source-set digest mismatch");
            }
            String alloy = Files.readString(alloyPath, StandardCharsets.UTF_8);
            var repeated = new AlloyObligationRunner().evaluateAll(observation, alloy);
            if (!repeated.equals(capsule.decisions())) {
                return invalid("repeated Alloy decisions do not match capsule");
            }
            return new CapsuleVerification(true, "Capsule artifacts and repeated Alloy decisions are valid.");
        } catch (Exception | LinkageError failure) {
            String message = failure.getMessage();
            return invalid(message == null ? failure.getClass().getSimpleName() : message);
        }
    }

    private static Path resolveArtifact(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("artifact path is blank");
        }
        Path relativePath = Path.of(relative);
        if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
            throw new IOException("artifact path escapes capsule directory: " + relative);
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (Files.isSymbolicLink(candidate)) {
            throw new IOException("symbolic-link artifacts are forbidden: " + relative);
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("artifact is outside capsule directory or is not a file: " + relative);
        }
        return real;
    }

    private static boolean digestEquals(String expected, String actual) {
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static CapsuleVerification invalid(String message) {
        return new CapsuleVerification(false, message);
    }
}
