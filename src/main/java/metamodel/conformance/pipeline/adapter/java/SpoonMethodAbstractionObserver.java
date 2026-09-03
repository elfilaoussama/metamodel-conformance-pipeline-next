package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.util.Hashing;
import spoon.Launcher;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Observes source-level method abstraction independently of the Alloy policy.
 * This class records modifiers/body form only; it never decides O-06 or O-07.
 */
final class SpoonMethodAbstractionObserver {
    Result observe(Path root, List<Path> files, List<MemberObservation> canonicalMembers) {
        try {
            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setComplianceLevel(17);
            launcher.getEnvironment().setCommentEnabled(false);
            files.forEach(file -> launcher.addInputResource(file.toString()));
            var model = launcher.buildModel();

            Map<String, MethodAbstraction> abstraction = new HashMap<>();
            for (CtType<?> type : model.getAllTypes().stream()
                    .filter(item -> item.getPosition().isValidPosition())
                    .sorted(Comparator.comparing(CtType::getQualifiedName)).toList()) {
                for (CtMethod<?> method : type.getMethods().stream()
                        .filter(item -> item.getPosition().isValidPosition())
                        .sorted(Comparator.comparing(CtMethod::getSimpleName)
                                .thenComparingInt(item -> item.getPosition().getLine()))
                        .toList()) {
                    Path source = method.getPosition().getFile().toPath()
                            .toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!source.startsWith(root)) {
                        return incomplete(root, files, "Spoon reported method abstraction outside the source root");
                    }
                    String path = root.relativize(source).toString().replace('\\', '/');
                    List<String> parameterTypes = method.getParameters().stream()
                            .map(parameter -> {
                                String value = parameter.getType().getQualifiedName();
                                return value == null || value.isBlank() ? "<unknown>" : value;
                            }).toList();
                    String canonical = "java\0" + path + "\0" + type.getQualifiedName() + "\0"
                            + MemberKind.METHOD + "\0" + method.getSimpleName() + "\0"
                            + String.join("\0", parameterTypes) + "\0" + method.getPosition().getLine();
                    String memberKey = "mem_" + Hashing.sha256(canonical);
                    boolean isAbstract = method.hasModifier(ModifierKind.ABSTRACT)
                            || (type instanceof CtInterface<?>
                                    && method.getBody() == null
                                    && !method.hasModifier(ModifierKind.STATIC)
                                    && !method.hasModifier(ModifierKind.PRIVATE));
                    if (abstraction.put(memberKey,
                            isAbstract ? MethodAbstraction.ABSTRACT : MethodAbstraction.CONCRETE) != null) {
                        return incomplete(root, files, "duplicate method abstraction observation");
                    }
                }
            }

            long expected = canonicalMembers.stream()
                    .filter(item -> item.kind() == MemberKind.METHOD).count();
            boolean complete = abstraction.size() == expected
                    && canonicalMembers.stream()
                            .filter(item -> item.kind() == MemberKind.METHOD)
                            .allMatch(item -> abstraction.containsKey(item.technicalKey()));
            if (!complete) {
                return incomplete(root, files,
                        "not every canonical method has one source-level abstraction observation");
            }
            return new Result(true, abstraction, List.of());
        } catch (IOException | RuntimeException failure) {
            return incomplete(root, files,
                    "Spoon method-abstraction observation failed: " + failure.getClass().getSimpleName());
        }
    }

    private static Result incomplete(Path root, List<Path> files, String message) {
        String sourcePath = files.isEmpty() ? "<unknown>.java"
                : root.relativize(files.get(0)).toString().replace('\\', '/');
        return new Result(false, Map.of(), List.of(new ObservationDiagnostic(
                DiagnosticKind.EVIDENCE_INCOMPLETE, sourcePath, 0, message)));
    }

    record Result(
            boolean complete,
            Map<String, MethodAbstraction> abstractionByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            abstractionByMember = Map.copyOf(abstractionByMember);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
