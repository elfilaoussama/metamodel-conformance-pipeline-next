package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class Schema9SourceObserver implements SourceObserver {
    private final SourceObserver delegate;

    Schema9SourceObserver(SourceObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        return new Observation(
                "9",
                base.adapterId(),
                base.adapterVersion(),
                base.externalParents(),
                base.completeEvidence(),
                base.units(),
                base.classifiers(),
                base.members(),
                List.of(),
                base.unresolvedParents(),
                base.diagnostics());
    }
}
