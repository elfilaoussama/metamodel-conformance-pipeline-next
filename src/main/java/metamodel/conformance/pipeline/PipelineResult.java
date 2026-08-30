package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.List;

public record PipelineResult(
        Observation observation,
        List<Decision> decisions,
        Path observationPath,
        Path alloyPath,
        Path capsulePath) {

    public PipelineResult {
        decisions = List.copyOf(decisions);
    }

    public Decision decision() {
        return decisions.stream().filter(item -> "O-03".equals(item.constraint())).findFirst().orElseThrow();
    }
}
