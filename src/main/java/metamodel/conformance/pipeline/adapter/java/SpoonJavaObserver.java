package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.Hashing;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.reference.CtTypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class SpoonJavaObserver implements SourceObserver {
    public static final String ADAPTER_ID = "spoon-java";
    public static final String ADAPTER_VERSION = "0.2.0";
    private static final Set<String> PLATFORM_ROOTS = Set.of(
            "java.lang.Object",
            "java.lang.Record",
            "java.lang.Enum",
            "java.lang.annotation.Annotation");

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverJavaFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no .java files: " + sourceRoot);
            }

            List<SourceUnit> units = new ArrayList<>();
            for (Path file : files) {
                units.add(new SourceUnit(relativePath(root, file), Hashing.sha256(file)));
            }

            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.getEnvironment().setCommentEnabled(false);
            files.forEach(file -> launcher.addInputResource(file.toString()));
            CtModel model = launcher.buildModel();

            List<CtType<?>> types = model.getAllTypes().stream()
                    .filter(type -> type.getPosition().isValidPosition())
                    .sorted(Comparator.comparing(CtType::getQualifiedName))
                    .toList();
            Map<String, TypeDraft> drafts = new HashMap<>();
            for (CtType<?> type : types) {
                String path = sourcePath(root, type);
                ClassifierKind kind = kindOf(type);
                String id = stableId(path, kind, type.getQualifiedName());
                SourcePosition position = type.getPosition();
                TypeDraft previous = drafts.put(type.getQualifiedName(), new TypeDraft(
                        type, id, path, kind, position.getLine(), position.getEndLine()));
                if (previous != null) {
                    throw new ObservationException("duplicate qualified classifier name: " + type.getQualifiedName());
                }
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<MemberObservation> members = new ArrayList<>();
            Map<String, List<String>> memberKeysByOwner = new HashMap<>();
            boolean localSignaturesComplete = true;
            for (TypeDraft draft : drafts.values().stream().sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                List<String> memberKeys = new ArrayList<>();
                for (CtTypeMember typeMember : draft.type().getTypeMembers()) {
                    if (!typeMember.getPosition().isValidPosition()) {
                        continue;
                    }
                    if (typeMember instanceof CtMethod<?> method) {
                        List<String> parameterTypes = new ArrayList<>();
                        for (var parameter : method.getParameters()) {
                            String parameterType = parameter.getType().getQualifiedName();
                            if (parameterType == null || parameterType.isBlank()) {
                                parameterType = "<unknown>";
                                localSignaturesComplete = false;
                            }
                            parameterTypes.add(parameterType);
                        }
                        MemberObservation member = memberObservation(
                                root, draft, MemberKind.METHOD, method.getSimpleName(),
                                method.getPosition(), parameterTypes);
                        members.add(member);
                        memberKeys.add(member.technicalKey());
                    } else if (typeMember instanceof CtField<?> field) {
                        MemberObservation member = memberObservation(
                                root, draft, MemberKind.ATTRIBUTE, field.getSimpleName(),
                                field.getPosition(), List.of());
                        members.add(member);
                        memberKeys.add(member.technicalKey());
                    }
                }
                memberKeysByOwner.put(draft.type().getQualifiedName(), memberKeys);
            }

            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            for (TypeDraft draft : drafts.values().stream().sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                for (CtTypeReference<?> parent : directParents(draft.type())) {
                    String parentName = parent.getQualifiedName();
                    TypeDraft internal = drafts.get(parentName);
                    if (internal != null) {
                        parentIds.add(internal.id());
                    } else if (!PLATFORM_ROOTS.contains(parentName) && !allowed.contains(parentName)) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), parentName, draft.path(), referenceLine(parent, draft.startLine())));
                    }
                }
                classifiers.add(new ClassifierObservation(
                        draft.id(), draft.type().getQualifiedName(), draft.kind(), draft.path(),
                        draft.startLine(), draft.endLine(), List.copyOf(parentIds),
                        memberKeysByOwner.getOrDefault(draft.type().getQualifiedName(), List.of())));
            }
            EnumSet<EvidenceKind> completeEvidence = EnumSet.of(EvidenceKind.DECLARATION_OWNERSHIP);
            if (unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }
            if (localSignaturesComplete) {
                completeEvidence.add(EvidenceKind.LOCAL_SIGNATURES);
            }
            return new Observation(
                    "2", ADAPTER_ID, ADAPTER_VERSION, List.copyOf(allowed), completeEvidence,
                    units, classifiers, members, unresolved);
        } catch (ObservationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new ObservationException("Java observation failed: " + exception.getMessage(), exception);
        }
    }

    private static Path validateRoot(Path sourceRoot) throws IOException, ObservationException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObservationException("source root is not a directory: " + sourceRoot);
        }
        if (Files.isSymbolicLink(sourceRoot)) {
            throw new ObservationException("symbolic-link source roots are not accepted: " + sourceRoot);
        }
        return sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
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

    private static List<CtTypeReference<?>> directParents(CtType<?> type) {
        List<CtTypeReference<?>> parents = new ArrayList<>();
        if (type instanceof CtClass<?> ctClass && ctClass.getSuperclass() != null) {
            parents.add(ctClass.getSuperclass());
        }
        parents.addAll(type.getSuperInterfaces());
        return parents.stream().sorted(Comparator.comparing(CtTypeReference::getQualifiedName)).toList();
    }

    private static ClassifierKind kindOf(CtType<?> type) throws ObservationException {
        if (type instanceof CtAnnotationType<?>) {
            return ClassifierKind.ANNOTATION;
        }
        if (type instanceof CtEnum<?>) {
            return ClassifierKind.ENUM;
        }
        if (type instanceof CtInterface<?>) {
            return ClassifierKind.INTERFACE;
        }
        if (type instanceof CtClass<?>) {
            return ClassifierKind.CLASS;
        }
        throw new ObservationException("unsupported Java classifier: " + type.getQualifiedName());
    }

    private static String sourcePath(Path root, CtType<?> type) throws IOException, ObservationException {
        Path source = type.getPosition().getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            throw new ObservationException("Spoon reported a source outside the declared root: " + source);
        }
        return relativePath(root, source);
    }

    private static int referenceLine(CtTypeReference<?> reference, int fallback) {
        return reference.getPosition().isValidPosition() ? reference.getPosition().getLine() : fallback;
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String stableId(String path, ClassifierKind kind, String qualifiedName) {
        return "cls_" + Hashing.sha256("java\0" + path + "\0" + kind + "\0" + qualifiedName);
    }

    private static MemberObservation memberObservation(
            Path root,
            TypeDraft owner,
            MemberKind kind,
            String memberName,
            SourcePosition position,
            List<String> parameterTypes) throws IOException, ObservationException {
        Path source = position.getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            throw new ObservationException("Spoon reported a member outside the declared root: " + source);
        }
        String path = relativePath(root, source);
        String canonical = "java\0" + path + "\0" + owner.type().getQualifiedName() + "\0"
                + kind + "\0" + memberName + "\0" + String.join("\0", parameterTypes)
                + "\0" + position.getLine();
        return new MemberObservation(
                "mem_" + Hashing.sha256(canonical),
                null,
                kind,
                memberName,
                path,
                position.getLine(),
                position.getEndLine(),
                parameterTypes);
    }

    private record TypeDraft(
            CtType<?> type, String id, String path, ClassifierKind kind, int startLine, int endLine) {
    }
}
