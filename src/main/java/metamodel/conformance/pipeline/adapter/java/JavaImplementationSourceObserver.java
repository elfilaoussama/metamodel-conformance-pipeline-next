package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class JavaImplementationSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = SpoonJavaObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.0.0";

    private final List<Path> dependencyArchives;

    public JavaImplementationSourceObserver(List<Path> dependencyArchives) {
        this.dependencyArchives = dependencyArchives == null ? List.of() : List.copyOf(dependencyArchives);
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = new SpoonJavaObserver(dependencyArchives).observe(sourceRoot, externalParents);
        if (base.diagnostics().stream().anyMatch(item -> item.kind()
                == metamodel.conformance.pipeline.model.DiagnosticKind.PARSE_ERROR)) {
            return upgrade(base, base.members(), List.of(), Set.of(), List.of());
        }
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = discoverJavaFiles(root);
            SpoonMethodBodyObserver.Result bodyResult = new SpoonMethodBodyObserver().observe(root, files);
            JavaDependencyClasspath.Result dependencies = JavaDependencyClasspath.resolve(dependencyArchives);
            JavacImplementationObserver.Result implementation = bodyResult.complete()
                    ? new JavacImplementationObserver().observe(
                            root,
                            files,
                            base.classifiers(),
                            base.members(),
                            bodyResult.bodies(),
                            dependencies.paths())
                    : JavacImplementationObserver.Result.incomplete();

            List<MemberObservation> members = base.members().stream().map(member -> {
                if (member.kind() != MemberKind.METHOD) {
                    return member;
                }
                return new MemberObservation(
                        member.technicalKey(),
                        member.observedIdentifier(),
                        member.kind(),
                        member.inheritability(),
                        member.visibility(),
                        member.memberName(),
                        member.sourcePath(),
                        member.startLine(),
                        member.endLine(),
                        member.parameterTypes(),
                        implementation.availabilityByMember().getOrDefault(
                                member.technicalKey(), ImplementationAvailability.UNKNOWN),
                        implementation.bodyKeysByMember().getOrDefault(
                                member.technicalKey(), List.of()));
            }).toList();

            EnumSet<EvidenceKind> added = EnumSet.noneOf(EvidenceKind.class);
            if (bodyResult.complete()) {
                added.add(EvidenceKind.METHOD_BODIES);
            }
            if (bodyResult.complete() && implementation.complete()) {
                added.add(EvidenceKind.IMPLEMENTATION_BINDINGS);
            }
            List<ObservationDiagnostic> extraDiagnostics = new ArrayList<>();
            extraDiagnostics.addAll(bodyResult.diagnostics());
            extraDiagnostics.addAll(implementation.diagnostics());
            dependencies.verifyUnchanged();
            return upgrade(base, members, bodyResult.bodies(), added, extraDiagnostics);
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java implementation-evidence observation failed: " + failure.getMessage(), failure);
        }
    }

    private static Observation upgrade(
            Observation base,
            List<MemberObservation> members,
            List<metamodel.conformance.pipeline.model.MethodBodyObservation> bodies,
            Set<EvidenceKind> addedEvidence,
            List<ObservationDiagnostic> extraDiagnostics) {
        EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
        evidence.addAll(base.completeEvidence());
        evidence.addAll(addedEvidence);
        List<ObservationDiagnostic> diagnostics = new ArrayList<>(base.diagnostics());
        diagnostics.addAll(extraDiagnostics);
        return new Observation(
                "9",
                ADAPTER_ID,
                ADAPTER_VERSION,
                base.externalParents(),
                evidence,
                base.units(),
                base.classifiers(),
                members,
                bodies,
                base.unresolvedParents(),
                diagnostics.stream().distinct().toList());
    }

    private static List<Path> discoverJavaFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("non-regular or symbolic-link Java source rejected: " + file);
            }
            if (!file.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
                throw new ObservationException("source escapes declared root: " + file);
            }
        }
        return files;
    }
}
