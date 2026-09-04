package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.UnresolvedParent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Adds dependency-bytecode support evidence without changing source-observation semantics. */
public final class JavaDependencyAwareSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = JavaImplementationSourceObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.4.0";

    private final List<Path> dependencyArchives;
    private final SourceObserver delegate;

    public JavaDependencyAwareSourceObserver(List<Path> dependencyArchives) {
        this(dependencyArchives, new JavaImplementationSourceObserver(dependencyArchives));
    }

    JavaDependencyAwareSourceObserver(List<Path> dependencyArchives, SourceObserver delegate) {
        this.dependencyArchives = dependencyArchives == null ? List.of() : List.copyOf(dependencyArchives);
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        if (dependencyArchives.isEmpty()
                || base.diagnostics().stream().anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR)) {
            return base;
        }
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            JavaDependencyClasspath.Result dependencies = JavaDependencyClasspath.resolve(dependencyArchives);
            Set<String> dependencyRoots = base.unresolvedParents().stream()
                    .map(UnresolvedParent::targetName)
                    .filter(name -> dependencies.ownerOfType(name) != null)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            if (dependencyRoots.isEmpty()) {
                return base;
            }

            JavaDependencySymbols.Result symbols = JavaDependencySymbols.resolve(dependencies, dependencyRoots);
            JavaDependencyObservation.Result support = JavaDependencyObservation.materialize(symbols);

            Map<String, List<String>> supportParentsBySourceClassifier = new HashMap<>();
            List<UnresolvedParent> remainingUnresolved = new ArrayList<>();
            for (UnresolvedParent unresolved : base.unresolvedParents()) {
                String supportId = support.classifierId(unresolved.targetName());
                if (supportId == null) {
                    remainingUnresolved.add(unresolved);
                } else {
                    supportParentsBySourceClassifier
                            .computeIfAbsent(unresolved.ownerId(), ignored -> new ArrayList<>())
                            .add(supportId);
                }
            }

            List<Path> files = discoverJavaFiles(root);
            JavacDependencyEvidenceObserver.Result dependencyEvidence =
                    new JavacDependencyEvidenceObserver().observe(
                            root,
                            files,
                            base.classifiers(),
                            base.members(),
                            support,
                            dependencies.paths());

            List<ClassifierObservation> sourceClassifiers = base.classifiers().stream().map(classifier -> {
                LinkedHashSet<String> parents = new LinkedHashSet<>(classifier.parentIds());
                parents.addAll(supportParentsBySourceClassifier.getOrDefault(classifier.id(), List.of()));
                List<String> inherited = dependencyEvidence.complete()
                        ? dependencyEvidence.inheritedByClassifier()
                                .getOrDefault(classifier.id(), List.of())
                        : classifier.inheritedMemberKeys();
                return new ClassifierObservation(
                        classifier.id(),
                        classifier.qualifiedName(),
                        classifier.packageName(),
                        classifier.kind(),
                        classifier.sourcePath(),
                        classifier.startLine(),
                        classifier.endLine(),
                        parents.stream().sorted().toList(),
                        classifier.declaredMemberKeys(),
                        inherited,
                        classifier.abstraction());
            }).toList();

            List<MemberObservation> sourceMembers = base.members().stream().map(member -> {
                if (member.kind() != MemberKind.METHOD || !dependencyEvidence.complete()) {
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
                        member.abstraction(),
                        member.scope(),
                        dependencyEvidence.returnTypesByMember()
                                .getOrDefault(member.technicalKey(), member.returnType()),
                        dependencyEvidence.overriddenMemberKeysByMember()
                                .getOrDefault(member.technicalKey(), member.overriddenMemberKeys()));
            }).toList();

            List<ClassifierObservation> classifiers = new ArrayList<>(sourceClassifiers);
            classifiers.addAll(support.classifiers());
            List<MemberObservation> members = new ArrayList<>(sourceMembers);
            members.addAll(support.members());

            EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
            evidence.addAll(base.completeEvidence());
            boolean boundaryComplete = dependencyEvidence.complete()
                    && symbols.unresolvedRootTypes().isEmpty()
                    && remainingUnresolved.isEmpty();
            if (boundaryComplete) {
                evidence.add(EvidenceKind.HIERARCHY);
                evidence.add(EvidenceKind.INHERITED_MEMBERS);
                evidence.add(EvidenceKind.METHOD_RETURN_TYPES);
                evidence.add(EvidenceKind.OVERRIDE_RELATIONS);
            } else {
                evidence.remove(EvidenceKind.HIERARCHY);
                evidence.remove(EvidenceKind.INHERITED_MEMBERS);
                evidence.remove(EvidenceKind.METHOD_RETURN_TYPES);
                evidence.remove(EvidenceKind.OVERRIDE_RELATIONS);
            }

            List<ObservationDiagnostic> diagnostics = new ArrayList<>(base.diagnostics());
            diagnostics.addAll(dependencyEvidence.diagnostics());
            if (!symbols.unresolvedRootTypes().isEmpty()) {
                String source = base.classifiers().isEmpty()
                        ? base.units().get(0).path() : base.classifiers().get(0).sourcePath();
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        source,
                        0,
                        "dependency bytecode roots could not be materialized: "
                                + symbols.unresolvedRootTypes().stream().sorted().toList()));
            }

            dependencies.verifyUnchanged();
            return new Observation(
                    "13",
                    ADAPTER_ID,
                    ADAPTER_VERSION,
                    base.externalParents(),
                    evidence,
                    base.units(),
                    classifiers,
                    members,
                    base.methodBodies(),
                    base.implementationBindings(),
                    remainingUnresolved,
                    diagnostics.stream().distinct()
                            .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                                    .thenComparingInt(ObservationDiagnostic::line)
                                    .thenComparing(item -> item.kind().name())
                                    .thenComparing(ObservationDiagnostic::message))
                            .toList());
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java dependency-aware observation failed: " + failure.getMessage(), failure);
        }
    }

    private static List<Path> discoverJavaFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("non-regular or symbolic-link Java source rejected: " + file);
            }
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new ObservationException("source escapes declared root: " + file);
            }
        }
        return files;
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
