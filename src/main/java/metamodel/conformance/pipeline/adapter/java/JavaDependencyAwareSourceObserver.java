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
import metamodel.conformance.pipeline.model.SourceUnit;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Adds dependency-bytecode support evidence without changing source-observation semantics. */
public final class JavaDependencyAwareSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = JavaImplementationSourceObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.6.0";

    private final JavaDependencyInputs dependencyInputs;
    private final SourceObserver delegate;

    public JavaDependencyAwareSourceObserver(List<Path> dependencyArchives) {
        this(JavaDependencyInputs.global(dependencyArchives));
    }

    public JavaDependencyAwareSourceObserver(JavaDependencyInputs dependencyInputs) {
        this(dependencyInputs, new JavaImplementationSourceObserver(dependencyInputs));
    }

    JavaDependencyAwareSourceObserver(JavaDependencyInputs dependencyInputs, SourceObserver delegate) {
        this.dependencyInputs = dependencyInputs == null ? JavaDependencyInputs.none() : dependencyInputs;
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        if (dependencyInputs.isEmpty()
                || base.diagnostics().stream().anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR)) {
            return base;
        }
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = discoverJavaFiles(root);
            Map<String, List<Path>> filesByModule = filesByModule(root, files);
            Map<String, ClassifierObservation> sourceById = new HashMap<>();
            base.classifiers().forEach(classifier -> sourceById.put(classifier.id(), classifier));

            Map<String, List<UnresolvedParent>> unresolvedByModule = new TreeMap<>();
            Map<String, String> sourceSetByUnresolvedKey = new HashMap<>();
            for (UnresolvedParent unresolved : base.unresolvedParents()) {
                ClassifierObservation owner = sourceById.get(unresolved.ownerId());
                if (owner == null) {
                    throw new ObservationException(
                            "unresolved parent references an unavailable source classifier: " + unresolved.ownerId());
                }
                String sourceSet = JavaSourceSets.id(owner.sourcePath());
                sourceSetByUnresolvedKey.put(unresolvedKey(unresolved), sourceSet);
                unresolvedByModule.computeIfAbsent(
                        JavaSourceSets.moduleKey(sourceSet), ignored -> new ArrayList<>()).add(unresolved);
            }

            Map<String, String> supportParentByUnresolvedKey = new HashMap<>();
            Map<String, ClassifierObservation> supportClassifiersById = new TreeMap<>();
            Map<String, MemberObservation> supportMembersByKey = new TreeMap<>();
            Map<String, List<String>> inheritedByClassifier = new HashMap<>();
            Map<String, List<String>> overridesByMember = new HashMap<>();
            Map<String, String> returnTypesByMember = new HashMap<>();
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            boolean dependencyEvidenceComplete = true;

            Set<String> modules = new TreeSet<>();
            modules.addAll(filesByModule.keySet());
            modules.addAll(unresolvedByModule.keySet());
            for (String module : modules) {
                List<UnresolvedParent> moduleUnresolved = unresolvedByModule.getOrDefault(module, List.of());
                if (moduleUnresolved.isEmpty()) {
                    continue;
                }
                List<Path> moduleFiles = filesByModule.getOrDefault(module, List.of());
                if (moduleFiles.isEmpty()) {
                    dependencyEvidenceComplete = false;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            firstSourcePath(base, module),
                            0,
                            "dependency evidence has no Java source files for module " + module));
                    continue;
                }

                Map<String, JavaDependencySymbols.TypeSymbol> symbolsByQualifiedName = new TreeMap<>();
                Map<String, Set<String>> resolvedRootsBySourceSet = new HashMap<>();
                Set<String> unresolvedSymbolRoots = new TreeSet<>();
                boolean supportAmbiguous = false;
                Set<String> sourceSets = moduleUnresolved.stream()
                        .map(item -> sourceSetByUnresolvedKey.get(unresolvedKey(item)))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

                for (String sourceSet : sourceSets) {
                    List<Path> archives = dependencyInputs.pathsForSourceSet(sourceSet);
                    if (archives.isEmpty()) {
                        continue;
                    }
                    JavaDependencyClasspath.Result classpath = JavaDependencyClasspath.resolve(archives);
                    Set<String> roots = moduleUnresolved.stream()
                            .filter(item -> sourceSet.equals(
                                    sourceSetByUnresolvedKey.get(unresolvedKey(item))))
                            .map(UnresolvedParent::targetName)
                            .filter(name -> classpath.ownerOfType(name) != null)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                    if (roots.isEmpty()) {
                        classpath.verifyUnchanged();
                        continue;
                    }

                    JavaDependencySymbols.Result observedSymbols = JavaDependencySymbols.resolve(classpath, roots);
                    Set<String> resolvedRoots = new TreeSet<>(roots);
                    resolvedRoots.removeAll(observedSymbols.unresolvedRootTypes());
                    resolvedRootsBySourceSet.put(sourceSet, Set.copyOf(resolvedRoots));
                    unresolvedSymbolRoots.addAll(observedSymbols.unresolvedRootTypes());
                    for (JavaDependencySymbols.TypeSymbol type : observedSymbols.types()) {
                        JavaDependencySymbols.TypeSymbol previous =
                                symbolsByQualifiedName.putIfAbsent(type.qualifiedName(), type);
                        if (previous != null && !previous.equals(type)) {
                            supportAmbiguous = true;
                            diagnostics.add(new ObservationDiagnostic(
                                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                                    firstSourcePath(base, module),
                                    0,
                                    "dependency type resolves to conflicting bytecode within module "
                                            + module + ": " + type.qualifiedName()));
                        }
                    }
                    for (String missing : observedSymbols.unresolvedRootTypes()) {
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                firstSourcePath(base, module),
                                0,
                                "dependency bytecode root could not be materialized for source set "
                                        + sourceSet + ": " + missing));
                    }
                    classpath.verifyUnchanged();
                }

                if (supportAmbiguous) {
                    dependencyEvidenceComplete = false;
                    continue;
                }
                dependencyEvidenceComplete &= unresolvedSymbolRoots.isEmpty();
                if (symbolsByQualifiedName.isEmpty()) {
                    continue;
                }

                JavaDependencySymbols.Result mergedSymbols = new JavaDependencySymbols.Result(
                        List.copyOf(symbolsByQualifiedName.values()), Set.copyOf(unresolvedSymbolRoots));
                JavaDependencyObservation.Result support = JavaDependencyObservation.materialize(mergedSymbols);
                for (ClassifierObservation classifier : support.classifiers()) {
                    ClassifierObservation previous = supportClassifiersById.putIfAbsent(classifier.id(), classifier);
                    if (previous != null && !previous.equals(classifier)) {
                        throw new ObservationException(
                                "dependency support classifier identity collision: " + classifier.id());
                    }
                }
                for (MemberObservation member : support.members()) {
                    MemberObservation previous = supportMembersByKey.putIfAbsent(member.technicalKey(), member);
                    if (previous != null && !previous.equals(member)) {
                        throw new ObservationException(
                                "dependency support member identity collision: " + member.technicalKey());
                    }
                }

                for (UnresolvedParent unresolved : moduleUnresolved) {
                    String sourceSet = sourceSetByUnresolvedKey.get(unresolvedKey(unresolved));
                    if (!resolvedRootsBySourceSet.getOrDefault(sourceSet, Set.of())
                            .contains(unresolved.targetName())) {
                        continue;
                    }
                    String supportId = support.classifierId(unresolved.targetName());
                    if (supportId != null) {
                        supportParentByUnresolvedKey.put(unresolvedKey(unresolved), supportId);
                    }
                }

                Set<String> modulePaths = moduleFiles.stream()
                        .map(path -> relativePath(root, path))
                        .collect(java.util.stream.Collectors.toSet());
                List<ClassifierObservation> moduleClassifiers = base.classifiers().stream()
                        .filter(item -> modulePaths.contains(item.sourcePath())).toList();
                List<MemberObservation> moduleMembers = base.members().stream()
                        .filter(item -> modulePaths.contains(item.sourcePath())).toList();

                JavacDependencyEvidenceObserver.Result observed =
                        new JavacDependencyEvidenceObserver().observe(
                                root,
                                moduleFiles,
                                moduleClassifiers,
                                moduleMembers,
                                support,
                                dependencyInputs);
                dependencyEvidenceComplete &= observed.complete();
                diagnostics.addAll(observed.diagnostics());
                inheritedByClassifier.putAll(observed.inheritedByClassifier());
                overridesByMember.putAll(observed.overriddenMemberKeysByMember());
                returnTypesByMember.putAll(observed.returnTypesByMember());
            }

            Map<String, List<String>> supportParentsBySourceClassifier = new HashMap<>();
            List<UnresolvedParent> remainingUnresolved = new ArrayList<>();
            for (UnresolvedParent unresolved : base.unresolvedParents()) {
                String supportId = supportParentByUnresolvedKey.get(unresolvedKey(unresolved));
                if (supportId == null) {
                    remainingUnresolved.add(unresolved);
                } else {
                    supportParentsBySourceClassifier
                            .computeIfAbsent(unresolved.ownerId(), ignored -> new ArrayList<>())
                            .add(supportId);
                }
            }

            List<ClassifierObservation> sourceClassifiers = base.classifiers().stream().map(classifier -> {
                LinkedHashSet<String> parents = new LinkedHashSet<>(classifier.parentIds());
                parents.addAll(supportParentsBySourceClassifier.getOrDefault(classifier.id(), List.of()));
                List<String> inherited = inheritedByClassifier.containsKey(classifier.id())
                        ? inheritedByClassifier.get(classifier.id())
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
                if (member.kind() != MemberKind.METHOD) {
                    return member;
                }
                String returnType = returnTypesByMember.getOrDefault(member.technicalKey(), member.returnType());
                List<String> overridden = overridesByMember.getOrDefault(
                        member.technicalKey(), member.overriddenMemberKeys());
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
                        returnType,
                        overridden);
            }).toList();

            List<ClassifierObservation> classifiers = new ArrayList<>(sourceClassifiers);
            classifiers.addAll(supportClassifiersById.values());
            List<MemberObservation> members = new ArrayList<>(sourceMembers);
            members.addAll(supportMembersByKey.values());

            EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
            evidence.addAll(base.completeEvidence());
            boolean boundaryComplete = dependencyEvidenceComplete && remainingUnresolved.isEmpty();
            if (boundaryComplete) {
                evidence.add(EvidenceKind.HIERARCHY);
                evidence.add(EvidenceKind.INHERITED_MEMBERS);
                if (!overridesByMember.isEmpty() || base.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS)) {
                    evidence.add(EvidenceKind.OVERRIDE_RELATIONS);
                }
                if (!returnTypesByMember.isEmpty() || base.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES)) {
                    evidence.add(EvidenceKind.METHOD_RETURN_TYPES);
                }
            } else {
                evidence.remove(EvidenceKind.HIERARCHY);
                evidence.remove(EvidenceKind.INHERITED_MEMBERS);
                evidence.remove(EvidenceKind.METHOD_RETURN_TYPES);
                evidence.remove(EvidenceKind.OVERRIDE_RELATIONS);
            }

            JavaDependencyClasspath.Result allDependencies = JavaDependencyClasspath.resolve(dependencyInputs);
            allDependencies.verifyUnchanged();
            return new Observation(
                    "13",
                    ADAPTER_ID,
                    ADAPTER_VERSION,
                    base.externalParents(),
                    evidence,
                    mergeUnits(base.units(), allDependencies.units()),
                    classifiers,
                    members,
                    base.methodBodies(),
                    base.implementationBindings(),
                    remainingUnresolved,
                    mergeDiagnostics(base.diagnostics(), diagnostics));
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java dependency-aware observation failed: " + failure.getMessage(), failure);
        }
    }

    private static Map<String, List<Path>> filesByModule(Path root, List<Path> files) {
        Map<String, List<Path>> result = new TreeMap<>();
        for (Path file : files) {
            String sourceSet = JavaSourceSets.id(relativePath(root, file));
            result.computeIfAbsent(JavaSourceSets.moduleKey(sourceSet), ignored -> new ArrayList<>()).add(file);
        }
        return result;
    }

    private static String unresolvedKey(UnresolvedParent unresolved) {
        return unresolved.ownerId() + "\0" + unresolved.targetName() + "\0"
                + unresolved.sourcePath() + "\0" + unresolved.line();
    }

    private static String firstSourcePath(Observation base, String module) {
        return base.classifiers().stream()
                .filter(item -> JavaSourceSets.moduleKey(JavaSourceSets.id(item.sourcePath())).equals(module))
                .map(ClassifierObservation::sourcePath)
                .sorted()
                .findFirst()
                .orElseGet(() -> base.units().stream()
                        .filter(unit -> unit.language() == metamodel.conformance.pipeline.model.Language.JAVA)
                        .map(unit -> unit.path()).sorted().findFirst().orElse("<unknown>.java"));
    }

    private static List<SourceUnit> mergeUnits(List<SourceUnit> base, List<SourceUnit> dependencies) {
        Map<String, SourceUnit> byPath = new TreeMap<>();
        for (SourceUnit unit : base) {
            byPath.put(unit.path(), unit);
        }
        for (SourceUnit unit : dependencies) {
            SourceUnit previous = byPath.putIfAbsent(unit.path(), unit);
            if (previous != null && !previous.equals(unit)) {
                throw new IllegalArgumentException("source-unit identity collision: " + unit.path());
            }
        }
        return List.copyOf(byPath.values());
    }

    private static List<ObservationDiagnostic> mergeDiagnostics(
            List<ObservationDiagnostic> base,
            List<ObservationDiagnostic> extra) {
        List<ObservationDiagnostic> all = new ArrayList<>(base);
        all.addAll(extra);
        return all.stream().distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(item -> item.kind().name())
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
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
