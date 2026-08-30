package io.github.elfilaoussama.pipeline;

import io.github.elfilaoussama.pipeline.model.ClassifierKind;
import io.github.elfilaoussama.pipeline.model.ClassifierObservation;
import io.github.elfilaoussama.pipeline.model.Observation;
import io.github.elfilaoussama.pipeline.model.SourceUnit;
import io.github.elfilaoussama.pipeline.model.UnresolvedParent;
import io.github.elfilaoussama.pipeline.util.Hashing;

import java.util.List;

public final class TestObservations {
    public static final String A = id("A");
    public static final String B = id("B");

    private TestObservations() {
    }

    public static Observation acyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of()), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation cyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of(B)), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation unresolved() {
        return observation(
                List.of(classifier(A, "example.A", List.of())),
                List.of(new UnresolvedParent(A, "missing.Parent", "example/A.java", 3)));
    }

    public static String id(String seed) {
        return "cls_" + Hashing.sha256(seed);
    }

    private static ClassifierObservation classifier(String id, String name, List<String> parents) {
        return new ClassifierObservation(id, name, ClassifierKind.CLASS, "example/" + name.substring(8)
                + ".java", 3, 4, parents);
    }

    private static Observation observation(
            List<ClassifierObservation> classifiers, List<UnresolvedParent> unresolved) {
        return new Observation(
                "1", "test-adapter", "1.0.0", List.of(),
                List.of(new SourceUnit("example/A.java", Hashing.sha256("source"))),
                classifiers,
                unresolved);
    }
}
