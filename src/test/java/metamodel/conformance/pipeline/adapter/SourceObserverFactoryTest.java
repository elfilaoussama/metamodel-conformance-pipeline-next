package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.adapter.java.SpoonJavaObserver;
import metamodel.conformance.pipeline.model.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceObserverFactoryTest {
    @Test
    void defaultsToJava() {
        assertEquals(Language.JAVA, SourceObserverFactory.parseLanguage(null));
        assertEquals(Language.JAVA, SourceObserverFactory.parseLanguage(""));
    }

    @Test
    void parsesCanonicalLanguageNames() {
        assertEquals(Language.JAVA, SourceObserverFactory.parseLanguage("java"));
        assertEquals(Language.PYTHON, SourceObserverFactory.parseLanguage("python"));
        assertEquals(Language.CPP, SourceObserverFactory.parseLanguage("cpp"));
    }

    @Test
    void rejectsUnknownLanguageNames() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceObserverFactory.parseLanguage("javascript"));
    }

    @Test
    void createsJavaObserverWithoutChangingExistingDefault() {
        assertInstanceOf(SpoonJavaObserver.class,
                SourceObserverFactory.create(Language.JAVA, List.of()));
    }

    @Test
    void unavailableFrontendsFailExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceObserverFactory.create(Language.PYTHON, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SourceObserverFactory.create(Language.CPP, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SourceObserverFactory.create(Language.JAVA_ARCHIVE, List.of()));
    }
}
