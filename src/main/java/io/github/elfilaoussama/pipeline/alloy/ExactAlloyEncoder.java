package io.github.elfilaoussama.pipeline.alloy;

import io.github.elfilaoussama.pipeline.model.ClassifierObservation;
import io.github.elfilaoussama.pipeline.model.Observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ExactAlloyEncoder {
    public static final String COMMAND = "O03Violation";

    public String encode(Observation observation) {
        StringBuilder alloy = new StringBuilder();
        alloy.append("module repository_instance\n\n");
        alloy.append("abstract sig Classifier { parent: set Classifier }\n\n");
        for (ClassifierObservation classifier : observation.classifiers()) {
            alloy.append("one sig ").append(atom(classifier.id())).append(" extends Classifier {}\n");
        }
        alloy.append("\nfact ExactParent {\n  ");
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation classifier : observation.classifiers()) {
            for (String parentId : classifier.parentIds()) {
                edges.add(atom(classifier.id()) + "->" + atom(parentId));
            }
        }
        edges.sort(Comparator.naturalOrder());
        if (edges.isEmpty()) {
            alloy.append("no parent\n");
        } else {
            alloy.append("parent = ").append(String.join(" + ", edges)).append("\n");
        }
        alloy.append("}\n\n");
        alloy.append("pred ").append(COMMAND)
                .append(" { some c: Classifier | c in c.^parent }\n");
        alloy.append("run ").append(COMMAND).append(" for exactly ")
                .append(observation.classifiers().size()).append(" Classifier\n");
        return alloy.toString();
    }

    static String atom(String classifierId) {
        if (!classifierId.matches("cls_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("unsafe or invalid classifier id: " + classifierId);
        }
        return "C_" + classifierId.substring(4);
    }
}
