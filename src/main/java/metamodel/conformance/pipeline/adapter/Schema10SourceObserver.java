package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Upgrades frontends that do not yet emit schema-10 implementation evidence. */
final class Schema10SourceObserver implements SourceObserver {
    private final SourceObserver delegate;

    Schema10SourceObserver(SourceObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        return new Observation(
                "10",
                base.adapterId(),
                base.adapterVersion(),
                base.externalParents(),
                base.completeEvidence(),
                base.units(),
                base.classifiers(),
                base.members(),
                List.of(),
                List.of(),
                base.unresolvedParents(),
                base.diagnostics());
    }
}
