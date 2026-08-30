package io.github.elfilaoussama.pipeline.adapter;

import io.github.elfilaoussama.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.Set;

public interface SourceObserver {
    Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException;
}
