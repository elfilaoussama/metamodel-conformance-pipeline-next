package io.github.elfilaoussama.pipeline.model;

import java.util.List;
import java.util.Objects;

public record MemberObservation(
        String technicalKey,
        String observedIdentifier,
        MemberKind kind,
        String memberName,
        String sourcePath,
        int startLine,
        int endLine,
        List<String> parameterTypes) {

    public MemberObservation {
        technicalKey = requireText(technicalKey, "technicalKey");
        observedIdentifier = observedIdentifier == null || observedIdentifier.isBlank()
                ? null : observedIdentifier;
        kind = Objects.requireNonNull(kind, "kind");
        memberName = requireText(memberName, "memberName");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid member source line range");
        }
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        if (kind == MemberKind.ATTRIBUTE && !parameterTypes.isEmpty()) {
            throw new IllegalArgumentException("attributes cannot have parameter types");
        }
        if (parameterTypes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("parameter types must not be blank");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
