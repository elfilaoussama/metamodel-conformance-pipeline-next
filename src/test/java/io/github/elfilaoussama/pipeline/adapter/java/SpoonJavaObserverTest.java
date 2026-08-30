package io.github.elfilaoussama.pipeline.adapter.java;

import io.github.elfilaoussama.pipeline.model.Observation;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpoonJavaObserverTest {
    private final SpoonJavaObserver observer = new SpoonJavaObserver();

    @Test
    void observesMultipleInheritanceEdgesDeterministically() throws Exception {
        Observation observation = observer.observe(fixture("multiple-parents"), Set.of());
        var join = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Join"))
                .findFirst().orElseThrow();

        assertEquals(2, join.parentIds().size());
        assertTrue(observation.unresolvedParents().isEmpty());
        assertTrue(observation.completeEvidence().containsAll(Set.of(
                io.github.elfilaoussama.pipeline.model.EvidenceKind.HIERARCHY,
                io.github.elfilaoussama.pipeline.model.EvidenceKind.DECLARATION_OWNERSHIP,
                io.github.elfilaoussama.pipeline.model.EvidenceKind.LOCAL_SIGNATURES)));
    }

    @Test
    void preservesAnUnresolvedParentAsEvidence() throws Exception {
        Observation observation = observer.observe(fixture("unresolved"), Set.of());
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("example.MissingParent", observation.unresolvedParents().get(0).targetName());
    }

    @Test
    void treatsLanguageDefinedRootsAsResolvedPlatformEvidence() throws Exception {
        Observation observation = observer.observe(fixture("platform-roots"), Set.of());

        assertEquals(3, observation.classifiers().size());
        assertTrue(observation.unresolvedParents().isEmpty());
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SpoonJavaObserverTest.class.getResource("/fixtures/" + name).toURI());
    }
}
