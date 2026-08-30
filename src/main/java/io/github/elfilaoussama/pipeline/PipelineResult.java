package io.github.elfilaoussama.pipeline;

import io.github.elfilaoussama.pipeline.decision.Decision;
import io.github.elfilaoussama.pipeline.model.Observation;

import java.nio.file.Path;

public record PipelineResult(
        Observation observation,
        Decision decision,
        Path observationPath,
        Path alloyPath,
        Path capsulePath) {
}
