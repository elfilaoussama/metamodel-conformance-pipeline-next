package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Enriches the structural Java observation with independent implementation evidence.
 * Spoon observes bodies and method abstraction; javac independently resolves which
 * canonical declaration each source body corresponds to. Invariant semantics remain in Alloy.
 */
public final class JavaImplementationSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = SpoonJavaObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.1.0";

    private final List<Path> dependencyArchives;

    public JavaImplementationSourceObserver(List<Path> dependencyArchives) {
        this.dependencyArchives = dependencyArchives == null ? List.of() : List.copyOf(dependencyArchives);
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = new SpoonJavaObserver(dependencyArchives).observe(sourceRoot, externalParents);
        if (base.diagnostics().stream().anyMatch(item -> item.kind()
                == metamodel.conformance.pipeline.model.DiagnosticKind.PARSE_ERROR)) {
            return upgrade(base, base.members(), List.of(), List.of(), Set.of(), List.of());
        }
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = discoverJavaFiles(root);
            SpoonMethodBodyObserver.Result bodyResult = observeBodiesBySourceSet(root, files);
            SpoonMethodAbstractionObserver.Result abstractionResult =
                    observeAbstractionBySourceSet(root, files, base.members());
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
                MethodAbstraction abstraction = member.kind() == MemberKind.METHOD
                        ? abstractionResult.abstractionByMember().getOrDefault(
                                member.technicalKey(), MethodAbstraction.UNKNOWN)
                        : MethodAbstraction.UNKNOWN;
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
                        abstraction);
            }).toList();

            BindingResult bindingResult = implementation.complete()
                    ? toBindings(base.classifiers(), implementation.bodyKeysByMember())
                    : BindingResult.incomplete();

            EnumSet<EvidenceKind> added = EnumSet.noneOf(EvidenceKind.class);
            if (bodyResult.complete()) {
                added.add(EvidenceKind.METHOD_BODIES);
            }
            if (abstractionResult.complete()) {
                added.add(EvidenceKind.METHOD_ABSTRACTION);
            }
            if (bodyResult.complete() && implementation.complete() && bindingResult.complete()) {
                added.add(EvidenceKind.IMPLEMENTATION_BINDINGS);
            }
            List<ObservationDiagnostic> extraDiagnostics = new ArrayList<>();
            extraDiagnostics.addAll(bodyResult.diagnostics());
            extraDiagnostics.addAll(abstractionResult.diagnostics());
            extraDiagnostics.addAll(implementation.diagnostics());
            extraDiagnostics.addAll(bindingResult.diagnostics());
            dependencies.verifyUnchanged();
            return upgrade(
                    base,
                    members,
                    bodyResult.bodies(),
                    bindingResult.bindings(),
                    added,
                    extraDiagnostics);
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java implementation-evidence observation failed: " + failure.getMessage(), failure);
        }
    }

    private static BindingResult toBindings(
            List<ClassifierObservation> classifiers,
            Map<String, List<String>> bodyKeysByMember) {
        Map<String, List<String>> ownersByMember = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String memberKey : classifier.declaredMemberKeys()) {
                ownersByMember.computeIfAbsent(memberKey, ignored -> new ArrayList<>())
                        .add(classifier.id());
            }
        }
        List<ImplementationBindingObservation> bindings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bodyKeysByMember.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<String> owners = ownersByMember.getOrDefault(entry.getKey(), List.of());
            if (owners.size() != 1) {
                return BindingResult.incomplete();
            }
            String implementer = owners.get(0);
            for (String bodyKey : entry.getValue()) {
                String technicalKey = "bind_" + Hashing.sha256(
                        "java-binding\0" + implementer + "\0" + entry.getKey() + "\0" + bodyKey);
                bindings.add(new ImplementationBindingObservation(
                        technicalKey, implementer, entry.getKey(), bodyKey));
            }
        }
        List<ImplementationBindingObservation> canonical = bindings.stream()
                .distinct()
                .sorted(Comparator.comparing(ImplementationBindingObservation::technicalKey))
                .toList();
        if (canonical.size() != bindings.size()) {
            return BindingResult.incomplete();
        }
        return new BindingResult(true, canonical, List.of());
    }

    private static SpoonMethodBodyObserver.Result observeBodiesBySourceSet(
            Path root, List<Path> files) {
        Map<String, List<Path>> filesBySourceSet = filesBySourceSet(root, files);
        boolean complete = true;
        List<MethodBodyObservation> bodies = new ArrayList<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        SpoonMethodBodyObserver observer = new SpoonMethodBodyObserver();
        for (List<Path> sourceSetFiles : filesBySourceSet.values()) {
            SpoonMethodBodyObserver.Result result = observer.observe(root, sourceSetFiles);
            complete &= result.complete();
            bodies.addAll(result.bodies());
            diagnostics.addAll(result.diagnostics());
        }
        List<MethodBodyObservation> canonicalBodies = bodies.stream()
                .distinct()
                .sorted(Comparator.comparing(MethodBodyObservation::technicalKey))
                .toList();
        if (canonicalBodies.size() != bodies.size()) {
            complete = false;
        }
        return new SpoonMethodBodyObserver.Result(
                complete,
                canonicalBodies,
                canonicalDiagnostics(diagnostics));
    }

    private static SpoonMethodAbstractionObserver.Result observeAbstractionBySourceSet(
            Path root, List<Path> files, List<MemberObservation> members) {
        Map<String, List<Path>> filesBySourceSet = filesBySourceSet(root, files);
        boolean complete = true;
        Map<String, MethodAbstraction> abstraction = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        SpoonMethodAbstractionObserver observer = new SpoonMethodAbstractionObserver();
        for (Map.Entry<String, List<Path>> entry : filesBySourceSet.entrySet()) {
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> root.relativize(path.toAbsolutePath().normalize())
                            .toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            SpoonMethodAbstractionObserver.Result result =
                    observer.observe(root, entry.getValue(), scopedMembers);
            complete &= result.complete();
            for (Map.Entry<String, MethodAbstraction> observed : result.abstractionByMember().entrySet()) {
                if (abstraction.put(observed.getKey(), observed.getValue()) != null) {
                    complete = false;
                }
            }
            diagnostics.addAll(result.diagnostics());
        }
        return new SpoonMethodAbstractionObserver.Result(
                complete, abstraction, canonicalDiagnostics(diagnostics));
    }

    private static Map<String, List<Path>> filesBySourceSet(Path root, List<Path> files) {
        Map<String, List<Path>> filesBySourceSet = new TreeMap<>();
        for (Path file : files) {
            String relative = root.relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            filesBySourceSet.computeIfAbsent(
                    JavaSourceSets.id(relative), ignored -> new ArrayList<>()).add(file);
        }
        return filesBySourceSet;
    }

    private static List<ObservationDiagnostic> canonicalDiagnostics(
            List<ObservationDiagnostic> diagnostics) {
        return diagnostics.stream().distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
    }

    private static Observation upgrade(
            Observation base,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<ImplementationBindingObservation> bindings,
            Set<EvidenceKind> addedEvidence,
            List<ObservationDiagnostic> extraDiagnostics) {
        EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
        evidence.addAll(base.completeEvidence());
        evidence.addAll(addedEvidence);
        List<ObservationDiagnostic> diagnostics = new ArrayList<>(base.diagnostics());
        diagnostics.addAll(extraDiagnostics);
        return new Observation(
                "10",
                ADAPTER_ID,
                ADAPTER_VERSION,
                base.externalParents(),
                evidence,
                base.units(),
                base.classifiers(),
                members,
                bodies,
                bindings,
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

    private record BindingResult(
            boolean complete,
            List<ImplementationBindingObservation> bindings,
            List<ObservationDiagnostic> diagnostics) {
        private BindingResult {
            bindings = List.copyOf(bindings);
            diagnostics = List.copyOf(diagnostics);
        }

        static BindingResult incomplete() {
            return new BindingResult(false, List.of(), List.of());
        }
    }
}
