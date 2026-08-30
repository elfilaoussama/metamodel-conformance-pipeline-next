package io.github.elfilaoussama.pipeline;

import io.github.elfilaoussama.pipeline.adapter.ObservationException;
import io.github.elfilaoussama.pipeline.adapter.SourceObserver;
import io.github.elfilaoussama.pipeline.alloy.ExactAlloyEncoder;
import io.github.elfilaoussama.pipeline.alloy.O03AlloyRunner;
import io.github.elfilaoussama.pipeline.capsule.CapsuleWriter;
import io.github.elfilaoussama.pipeline.capsule.VerificationCapsule;
import io.github.elfilaoussama.pipeline.decision.Decision;
import io.github.elfilaoussama.pipeline.emf.ObservationXmiWriter;
import io.github.elfilaoussama.pipeline.model.Observation;
import io.github.elfilaoussama.pipeline.util.AtomicFiles;
import io.github.elfilaoussama.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

public final class ConformancePipeline {
    private static final String OBSERVATION_FILE = "observation.xmi";
    private static final String ALLOY_FILE = "repository-instance.als";
    private static final String CAPSULE_FILE = "verification-capsule.json";

    private final SourceObserver observer;
    private final ExactAlloyEncoder encoder;
    private final O03AlloyRunner runner;

    public ConformancePipeline(SourceObserver observer) {
        this(observer, new ExactAlloyEncoder(), new O03AlloyRunner());
    }

    ConformancePipeline(SourceObserver observer, ExactAlloyEncoder encoder, O03AlloyRunner runner) {
        this.observer = observer;
        this.encoder = encoder;
        this.runner = runner;
    }

    public PipelineResult analyze(Path sourceRoot, Path outputDirectory, Set<String> externalParents)
            throws ObservationException, IOException {
        Path output = prepareOutputDirectory(outputDirectory);
        Observation observation = observer.observe(sourceRoot, externalParents);

        Path observationPath = output.resolve(OBSERVATION_FILE);
        new ObservationXmiWriter().write(observation, observationPath);

        String alloy = encoder.encode(observation);
        Path alloyPath = output.resolve(ALLOY_FILE);
        AtomicFiles.writeUtf8(alloyPath, alloy);

        Decision decision = runner.evaluate(observation, alloy);
        VerificationCapsule capsule = new VerificationCapsule(
                "1",
                PipelineVersion.TOOL_ID,
                PipelineVersion.VERSION,
                observation.schemaVersion(),
                observation.adapterId(),
                observation.adapterVersion(),
                Hashing.sourceSetDigest(observation.units()),
                OBSERVATION_FILE,
                Hashing.sha256(observationPath),
                ALLOY_FILE,
                Hashing.sha256(alloyPath),
                decision.constraint(),
                ExactAlloyEncoder.COMMAND,
                decision.status(),
                decision.message(),
                decision.witnessClassifierIds());
        Path capsulePath = output.resolve(CAPSULE_FILE);
        new CapsuleWriter().write(capsule, capsulePath);
        return new PipelineResult(observation, decision, observationPath, alloyPath, capsulePath);
    }

    private static Path prepareOutputDirectory(Path outputDirectory) throws IOException {
        if (outputDirectory == null) {
            throw new IOException("output directory is required");
        }
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(output)) {
            throw new IOException("symbolic-link output directories are forbidden");
        }
        Files.createDirectories(output);
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("output path is not a directory: " + output);
        }
        return output.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
}
