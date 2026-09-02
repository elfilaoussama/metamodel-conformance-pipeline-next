package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.invariant.InvariantDefinition;
import metamodel.conformance.pipeline.invariant.ProjectionRelation;
import metamodel.conformance.pipeline.invariant.ProjectionRoot;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class AlloyWorkUnitPlanner {
    static final int WORK_UNIT_ATOM_TARGET = 256;
    private static final String CLASSIFIER_PREFIX = "C:";
    private static final String MEMBER_PREFIX = "M:";

    List<Observation> plan(Observation observation, InvariantDefinition definition) {
        Map<String, Set<String>> graph = graph(observation, definition.partitionRelations());
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        for (String node : graph.keySet()) {
            if (visited.contains(node)) {
                continue;
            }
            Set<String> component = component(node, graph, visited);
            if (containsRoot(component, definition.partitionRoots())) {
                components.add(component);
            }
        }
        List<Observation> result = new ArrayList<>();
        Set<String> packed = new TreeSet<>();
        for (Set<String> component : components) {
            if (!packed.isEmpty() && packed.size() + component.size() > WORK_UNIT_ATOM_TARGET) {
                result.add(project(observation, packed, definition.partitionRelations()));
                packed = new TreeSet<>();
            }
            packed.addAll(component);
            if (packed.size() >= WORK_UNIT_ATOM_TARGET) {
                result.add(project(observation, packed, definition.partitionRelations()));
                packed = new TreeSet<>();
            }
        }
        if (!packed.isEmpty()) {
            result.add(project(observation, packed, definition.partitionRelations()));
        }
        return List.copyOf(result);
    }

    private static Map<String, Set<String>> graph(
            Observation observation, Set<ProjectionRelation> relations) {
        Map<String, Set<String>> graph = new TreeMap<>();
        observation.classifiers().forEach(item -> graph.put(classifierNode(item.id()), new TreeSet<>()));
        observation.members().forEach(item -> graph.put(memberNode(item.technicalKey()), new TreeSet<>()));
        for (ClassifierObservation classifier : observation.classifiers()) {
            if (relations.contains(ProjectionRelation.PARENTS)) {
                classifier.parentIds().forEach(parent -> connect(
                        graph, classifierNode(classifier.id()), classifierNode(parent)));
            }
            if (relations.contains(ProjectionRelation.DECLARED_MEMBERS)) {
                classifier.declaredMemberKeys().forEach(member -> connect(
                        graph, classifierNode(classifier.id()), memberNode(member)));
            }
            if (relations.contains(ProjectionRelation.OBSERVED_INHERITED_MEMBERS)) {
                classifier.inheritedMemberKeys().forEach(member -> connect(
                        graph, classifierNode(classifier.id()), memberNode(member)));
            }
        }
        return graph;
    }

    private static void connect(Map<String, Set<String>> graph, String left, String right) {
        graph.get(left).add(right);
        graph.get(right).add(left);
    }

    private static Set<String> component(
            String start, Map<String, Set<String>> graph, Set<String> visited) {
        Set<String> result = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(start);
        visited.add(start);
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            result.add(node);
            for (String adjacent : graph.get(node)) {
                if (visited.add(adjacent)) {
                    pending.addLast(adjacent);
                }
            }
        }
        return result;
    }

    private static boolean containsRoot(Set<String> component, Set<ProjectionRoot> roots) {
        return component.stream().anyMatch(node ->
                roots.contains(ProjectionRoot.CLASSIFIER) && node.startsWith(CLASSIFIER_PREFIX)
                        || roots.contains(ProjectionRoot.MEMBER) && node.startsWith(MEMBER_PREFIX));
    }

    private static Observation project(
            Observation source,
            Set<String> component,
            Set<ProjectionRelation> relations) {
        Set<String> classifierIds = new HashSet<>();
        Set<String> memberKeys = new HashSet<>();
        component.forEach(node -> {
            if (node.startsWith(CLASSIFIER_PREFIX)) {
                classifierIds.add(node.substring(CLASSIFIER_PREFIX.length()));
            } else if (node.startsWith(MEMBER_PREFIX)) {
                memberKeys.add(node.substring(MEMBER_PREFIX.length()));
            }
        });

        List<ClassifierObservation> classifiers = source.classifiers().stream()
                .filter(item -> classifierIds.contains(item.id()))
                .map(item -> new ClassifierObservation(
                        item.id(), item.qualifiedName(), item.packageName(), item.kind(), item.sourcePath(),
                        item.startLine(), item.endLine(),
                        relations.contains(ProjectionRelation.PARENTS)
                                ? retained(item.parentIds(), classifierIds) : List.of(),
                        relations.contains(ProjectionRelation.DECLARED_MEMBERS)
                                ? retained(item.declaredMemberKeys(), memberKeys) : List.of(),
                        relations.contains(ProjectionRelation.OBSERVED_INHERITED_MEMBERS)
                                ? retained(item.inheritedMemberKeys(), memberKeys) : List.of()))
                .toList();
        List<MemberObservation> members = source.members().stream()
                .filter(item -> memberKeys.contains(item.technicalKey()))
                .toList();
        return new Observation(
                source.schemaVersion(), source.adapterId(), source.adapterVersion(),
                source.externalParents(), source.completeEvidence(), source.units(),
                classifiers, members, List.of(), List.of());
    }

    private static List<String> retained(List<String> values, Set<String> retained) {
        return values.stream().filter(retained::contains).toList();
    }

    private static String classifierNode(String id) {
        return CLASSIFIER_PREFIX + id;
    }

    private static String memberNode(String key) {
        return MEMBER_PREFIX + key;
    }
}
