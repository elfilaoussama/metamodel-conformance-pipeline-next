package io.github.elfilaoussama.pipeline.alloy;

import io.github.elfilaoussama.pipeline.model.ClassifierObservation;
import io.github.elfilaoussama.pipeline.model.Observation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CycleWitness {
    private CycleWitness() {
    }

    static List<String> find(Observation observation) {
        Map<String, List<String>> graph = new HashMap<>();
        observation.classifiers().forEach(item -> graph.put(item.id(), item.parentIds()));
        Map<String, Integer> color = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            List<String> cycle = visit(classifier.id(), graph, color, stack);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> visit(
            String node, Map<String, List<String>> graph, Map<String, Integer> color, List<String> stack) {
        int state = color.getOrDefault(node, 0);
        if (state == 2) {
            return List.of();
        }
        if (state == 1) {
            int start = stack.indexOf(node);
            List<String> cycle = new ArrayList<>(stack.subList(start, stack.size()));
            cycle.add(node);
            return List.copyOf(cycle);
        }
        color.put(node, 1);
        stack.add(node);
        for (String parent : graph.getOrDefault(node, List.of())) {
            List<String> cycle = visit(parent, graph, color, stack);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        stack.remove(stack.size() - 1);
        color.put(node, 2);
        return List.of();
    }
}
