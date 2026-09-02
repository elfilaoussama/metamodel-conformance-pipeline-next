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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExactAlloyEncoder {
    private static final int RELATION_CHUNK_SIZE = 64;

    public String encode(Observation observation) {
        Map<String, String> nameAtoms = tokens(observation.members().stream()
                .map(MemberObservation::memberName).toList(), "N_");
        Map<String, String> typeAtoms = tokens(observation.members().stream()
                .flatMap(member -> member.parameterTypes().stream()).toList(), "T_");
        Map<String, String> packageAtoms = tokens(observation.classifiers().stream()
                .map(ClassifierObservation::packageName).toList(), "PKG_");
        int positionCount = observation.members().stream()
                .mapToInt(member -> member.parameterTypes().size()).max().orElse(0);

        StringBuilder alloy = new StringBuilder();
        alloy.append("module repository_instance\n\n");
        alloy.append("abstract sig Classifier {\n")
                .append("  parents: set Classifier,\n")
                .append("  declaredMembers: set Member,\n")
                .append("  observedInheritedMembers: set Member,\n")
                .append("  packageName: one PackageToken\n")
                .append("}\n");
        alloy.append("abstract sig MemberKind {}\n")
                .append("one sig METHOD, ATTRIBUTE extends MemberKind {}\n")
                .append("abstract sig Inheritability {}\n")
                .append("one sig INHERITABLE, NOT_INHERITABLE, UNKNOWN extends Inheritability {}\n")
                .append("abstract sig MemberVisibility {}\n")
                .append("one sig PUBLIC, PROTECTED, PACKAGE, PRIVATE extends MemberVisibility {}\n")
                .append("abstract sig PackageToken {}\n")
                .append("abstract sig NameToken {}\n")
                .append("abstract sig TypeToken {}\n")
                .append("abstract sig PositionToken {}\n")
                .append("abstract sig Member {\n")
                .append("  kind: one MemberKind,\n")
                .append("  inheritability: one Inheritability,\n")
                .append("  visibility: one MemberVisibility,\n")
                .append("  memberName: one NameToken,\n")
                .append("  parameterTypeAt: PositionToken -> lone TypeToken\n")
                .append("}\n\n");

        observation.classifiers().forEach(item -> alloy.append("one sig ")
                .append(classifierAtom(item.id())).append(" extends Classifier {}\n"));
        observation.members().forEach(item -> alloy.append("one sig ")
                .append(memberAtom(item.technicalKey())).append(" extends Member {}\n"));
        nameAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom)
                .append(" extends NameToken {}\n"));
        typeAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom)
                .append(" extends TypeToken {}\n"));
        packageAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom)
                .append(" extends PackageToken {}\n"));
        for (int position = 0; position < positionCount; position++) {
            alloy.append("one sig P_").append(position).append(" extends PositionToken {}\n");
        }

        alloy.append("\nfact ExactObservation {\n");
        relation(alloy, "parents", parentEdges(observation));
        relation(alloy, "declaredMembers", declarationEdges(observation));
        relation(alloy, "observedInheritedMembers", inheritedMembershipEdges(observation));
        relation(alloy, "packageName", packageEdges(observation, packageAtoms));
        relation(alloy, "kind", kindEdges(observation));
        relation(alloy, "inheritability", inheritabilityEdges(observation));
        relation(alloy, "visibility", visibilityEdges(observation));
        relation(alloy, "memberName", nameEdges(observation, nameAtoms));
        relation(alloy, "parameterTypeAt", parameterTypeEdges(observation, typeAtoms));
        alloy.append("}\n\n");
        alloy.append(loadRules()).append('\n');

        String scope = scope(observation, nameAtoms.size(), typeAtoms.size(), packageAtoms.size(), positionCount);
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
        values.stream().distinct().sorted()
                .forEach(value -> result.put(value, prefix + Hashing.sha256(value)));
        return Collections.unmodifiableMap(result);
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

    private static List<String> inheritedMembershipEdges(Observation observation) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            classifier.inheritedMemberKeys().forEach(member -> edges.add(
                    classifierAtom(classifier.id()) + "->" + memberAtom(member)));
        }
        return edges;
    }

    private static List<String> kindEdges(Observation observation) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + (member.kind() == MemberKind.METHOD ? "METHOD" : "ATTRIBUTE")).toList();
    }

    private static List<String> inheritabilityEdges(Observation observation) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + member.inheritability().name()).toList();
    }

    private static List<String> visibilityEdges(Observation observation) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + member.visibility().name()).toList();
    }

    private static List<String> packageEdges(
            Observation observation, Map<String, String> packageAtoms) {
        return observation.classifiers().stream().map(classifier -> classifierAtom(classifier.id()) + "->"
                + packageAtoms.get(classifier.packageName())).toList();
    }

    private static List<String> nameEdges(Observation observation, Map<String, String> nameAtoms) {
        return observation.members().stream().map(member -> memberAtom(member.technicalKey()) + "->"
                + nameAtoms.get(member.memberName())).toList();
    }

    private static List<String> parameterTypeEdges(
            Observation observation, Map<String, String> typeAtoms) {
        List<String> edges = new ArrayList<>();
        for (MemberObservation member : observation.members()) {
            for (int position = 0; position < member.parameterTypes().size(); position++) {
                edges.add(memberAtom(member.technicalKey()) + "->P_" + position + "->"
                        + typeAtoms.get(member.parameterTypes().get(position)));
            }
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
            Observation observation, int nameCount, int typeCount, int packageCount, int positionCount) {
        return "for exactly " + observation.classifiers().size() + " Classifier, exactly "
                + observation.members().size() + " Member, exactly " + nameCount
                + " NameToken, exactly " + typeCount + " TypeToken, exactly "
                + packageCount + " PackageToken, exactly " + positionCount + " PositionToken";
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
