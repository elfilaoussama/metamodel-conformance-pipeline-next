package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExactAlloyEncoder {
    private static final int RELATION_CHUNK_SIZE = 64;

    public String encode(Observation observation) {
        Map<String, String> nameAtoms = tokens(observation.members().stream()
                .map(MemberObservation::memberName).toList(), "N_");
        Map<List<String>, String> signatureAtoms = signatureTokens(observation);

        StringBuilder alloy = new StringBuilder();
        alloy.append("module repository_instance\n\n");
        alloy.append("abstract sig Classifier {\n")
                .append("  parents: set Classifier,\n")
                .append("  declaredMembers: set Member,\n")
                .append("  observedInheritedMembers: set Member\n")
                .append("}\n");
        alloy.append("abstract sig MemberKind {}\n")
                .append("one sig METHOD, ATTRIBUTE extends MemberKind {}\n")
                .append("abstract sig Inheritability {}\n")
                .append("one sig INHERITABLE, NOT_INHERITABLE, UNKNOWN extends Inheritability {}\n")
                .append("abstract sig NameToken {}\n")
                .append("abstract sig SignatureToken {}\n")
                .append("abstract sig Member {\n")
                .append("  kind: one MemberKind,\n")
                .append("  inheritability: one Inheritability,\n")
                .append("  memberName: one NameToken,\n")
                .append("  parameterSignature: one SignatureToken\n")
                .append("}\n\n");

        observation.classifiers().forEach(item -> alloy.append("one sig ")
                .append(classifierAtom(item.id())).append(" extends Classifier {}\n"));
        observation.members().forEach(item -> alloy.append("one sig ")
                .append(memberAtom(item.technicalKey())).append(" extends Member {}\n"));
        nameAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom)
                .append(" extends NameToken {}\n"));
        signatureAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom)
                .append(" extends SignatureToken {}\n"));

        alloy.append("\nfact ExactObservation {\n");
        relation(alloy, "parents", parentEdges(observation));
        relation(alloy, "declaredMembers", declarationEdges(observation));
        relation(alloy, "observedInheritedMembers", inheritedMembershipEdges(observation));
        relation(alloy, "kind", kindEdges(observation));
        relation(alloy, "inheritability", inheritabilityEdges(observation));
        relation(alloy, "memberName", nameEdges(observation, nameAtoms));
        relation(alloy, "parameterSignature", parameterSignatureEdges(observation, signatureAtoms));
        alloy.append("}\n\n");
        alloy.append(loadRules()).append('\n');

        String scope = scope(observation, nameAtoms.size(), signatureAtoms.size());
        alloy.append("run ObservationConsistency ").append(scope).append('\n');
        return alloy.toString();
    }

    public Map<String, String> atomTechnicalKeys(Observation observation) {
        Map<String, String> result = new LinkedHashMap<>();
        observation.classifiers().forEach(item -> result.put(classifierAtom(item.id()), item.id()));
        observation.members().forEach(item -> result.put(memberAtom(item.technicalKey()), item.technicalKey()));
        return Map.copyOf(result);
    }

    static String classifierAtom(String classifierId) {
        if (!classifierId.matches("cls_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("unsafe or invalid classifier id: " + classifierId);
        }
        return "C_" + classifierId.substring(4);
    }

    static String memberAtom(String technicalKey) {
        if (!technicalKey.matches("mem_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("unsafe or invalid member key: " + technicalKey);
        }
        return "M_" + technicalKey.substring(4);
    }

    private static Map<String, String> tokens(List<String> values, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        values.stream().distinct().sorted().forEach(value -> result.put(value, prefix + Hashing.sha256(value)));
        return result;
    }

    private static Map<List<String>, String> signatureTokens(Observation observation) {
        Map<List<String>, String> result = new LinkedHashMap<>();
        List<List<String>> signatures = observation.members().stream()
                .map(MemberObservation::parameterTypes)
                .distinct()
                .sorted(Comparator.comparing(ExactAlloyEncoder::signatureKey))
                .toList();
        for (int index = 0; index < signatures.size(); index++) {
            result.put(signatures.get(index), "S_" + index);
        }
        return result;
    }

    private static String signatureKey(List<String> parameterTypes) {
        StringBuilder key = new StringBuilder();
        key.append(parameterTypes.size()).append('|');
        for (String type : parameterTypes) {
            key.append(type.length()).append(':').append(type);
        }
        return key.toString();
    }

    private static List<String> parentEdges(Observation observation) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            classifier.parentIds().forEach(parent -> edges.add(
                    classifierAtom(classifier.id()) + "->" + classifierAtom(parent)));
        }
        return edges;
    }

    private static List<String> declarationEdges(Observation observation) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            classifier.declaredMemberKeys().forEach(member -> edges.add(
                    classifierAtom(classifier.id()) + "->" + memberAtom(member)));
        }
        return edges;
    }

    private static List<String> kindEdges(Observation observation) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + (member.kind() == MemberKind.METHOD ? "METHOD" : "ATTRIBUTE")).toList();
    }

    private static List<String> inheritedMembershipEdges(Observation observation) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            classifier.inheritedMemberKeys().forEach(member -> edges.add(
                    classifierAtom(classifier.id()) + "->" + memberAtom(member)));
        }
        return edges;
    }

    private static List<String> inheritabilityEdges(Observation observation) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + member.inheritability().name()).toList();
    }

    private static List<String> nameEdges(Observation observation, Map<String, String> nameAtoms) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + nameAtoms.get(member.memberName())).toList();
    }

    private static List<String> parameterSignatureEdges(
            Observation observation,
            Map<List<String>, String> signatureAtoms) {
        List<String> edges = new ArrayList<>();
        for (MemberObservation member : observation.members()) {
            edges.add(memberAtom(member.technicalKey()) + "->"
                    + signatureAtoms.get(member.parameterTypes()));
        }
        return edges;
    }

    private static void relation(StringBuilder alloy, String name, List<String> edges) {
        List<String> sorted = edges.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.isEmpty()) {
            alloy.append("  no ").append(name).append('\n');
        } else {
            alloy.append("  ").append(name).append(" = ");
            for (int start = 0; start < sorted.size(); start += RELATION_CHUNK_SIZE) {
                if (start > 0) {
                    alloy.append(" +\n    ");
                }
                int end = Math.min(start + RELATION_CHUNK_SIZE, sorted.size());
                alloy.append('(').append(String.join(" + ", sorted.subList(start, end))).append(')');
            }
            alloy.append('\n');
        }
    }

    private static String scope(
            Observation observation,
            int nameCount,
            int signatureCount) {
        return "for exactly " + observation.classifiers().size() + " Classifier, exactly "
                + observation.members().size() + " Member, exactly " + nameCount
                + " NameToken, exactly " + signatureCount + " SignatureToken";
    }

    private static String loadRules() {
        try (InputStream input = ExactAlloyEncoder.class.getResourceAsStream("/alloy/invariants.als")) {
            if (input == null) {
                throw new IllegalStateException("bundled Alloy invariants are missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load Alloy invariants", failure);
        }
    }
}
