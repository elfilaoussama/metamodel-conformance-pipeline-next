package metamodel.conformance.pipeline.alloy;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.A4Tuple;
import edu.mit.csail.sdg.translator.A4TupleSet;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.obligation.ObligationCatalog;
import metamodel.conformance.pipeline.obligation.ObligationDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AlloyObligationRunner {
    public List<Decision> evaluateAll(Observation observation, String alloyModel) {
        ObligationCatalog catalog = ObligationCatalog.load();
        CompModule module;
        try {
            module = CompUtil.parseEverything_fromString(new A4Reporter(), alloyModel);
        } catch (Exception | LinkageError failure) {
            return catalog.all().stream().map(definition -> indeterminate(
                    definition, "Alloy model parsing failed: " + safeMessage(failure))).toList();
        }

        try {
            Command consistencyCommand = findCommand(module, "ObservationConsistency");
            A4Solution consistency = TranslateAlloyToKodkod.execute_command(
                    new A4Reporter(), module.getAllReachableSigs(), consistencyCommand, new A4Options());
            if (!consistency.satisfiable()) {
                return catalog.all().stream().map(definition -> indeterminate(
                        definition, "The exact Alloy observation is inconsistent.")).toList();
            }
        } catch (Exception | LinkageError failure) {
            return catalog.all().stream().map(definition -> indeterminate(
                    definition, "Alloy consistency evaluation failed: " + safeMessage(failure))).toList();
        }

        Map<String, String> atomKeys = new ExactAlloyEncoder().atomTechnicalKeys(observation);
        List<Decision> decisions = new ArrayList<>();
        for (ObligationDefinition definition : catalog.all()) {
            Set<EvidenceKind> missing = definition.requiredEvidence().stream()
                    .filter(required -> !observation.completeEvidence().contains(required))
                    .collect(Collectors.toSet());
            if (!missing.isEmpty()) {
                String names = missing.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
                decisions.add(indeterminate(definition, "Required evidence is incomplete: " + names));
                continue;
            }
            decisions.add(evaluate(module, definition, atomKeys));
        }
        return List.copyOf(decisions);
    }

    private static Decision evaluate(
            CompModule module, ObligationDefinition definition, Map<String, String> atomKeys) {
        try {
            Command command = findCommand(module, definition.command());
            A4Solution solution = TranslateAlloyToKodkod.execute_command(
                    new A4Reporter(), module.getAllReachableSigs(), command, new A4Options());
            if (!solution.satisfiable()) {
                return new Decision(
                        DecisionStatus.CONFORMANT,
                        definition.id(),
                        definition.conformanceMessage(),
                        List.of());
            }
            Func witnessFunction = findFunction(module, definition.witnessFunction());
            Object evaluated = solution.eval(witnessFunction.call());
            if (!(evaluated instanceof A4TupleSet tuples)) {
                throw new IllegalStateException("witness function did not return a relation");
            }
            List<String> witnesses = new ArrayList<>();
            for (A4Tuple tuple : tuples) {
                if (tuple.arity() != 1) {
                    throw new IllegalStateException("witness relation must be unary");
                }
                String atom = normalizeAtom(tuple.atom(0));
                String technicalKey = atomKeys.get(atom);
                if (technicalKey == null) {
                    throw new IllegalStateException("witness atom has no observation mapping: " + atom);
                }
                witnesses.add(technicalKey);
            }
            witnesses = witnesses.stream().distinct().sorted(Comparator.naturalOrder()).toList();
            if (witnesses.isEmpty()) {
                throw new IllegalStateException("satisfiable violation command returned no witness atoms");
            }
            return new Decision(
                    DecisionStatus.NON_CONFORMANT,
                    definition.id(),
                    definition.violationMessage(),
                    witnesses);
        } catch (Exception | LinkageError failure) {
            return indeterminate(definition, "Alloy evaluation failed: " + safeMessage(failure));
        }
    }

    private static Command findCommand(CompModule module, String commandName) {
        return module.getAllCommands().stream()
                .filter(command -> commandName.equals(command.label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "generated Alloy model has no " + commandName + " command"));
    }

    private static Func findFunction(CompModule module, String functionName) {
        for (Func function : module.getAllFunc()) {
            if (function.label.equals(functionName) || function.label.endsWith("/" + functionName)) {
                return function;
            }
        }
        throw new IllegalArgumentException("generated Alloy model has no " + functionName + " function");
    }

    private static String normalizeAtom(String label) {
        int slash = label.lastIndexOf('/');
        String atom = slash >= 0 ? label.substring(slash + 1) : label;
        int instanceSuffix = atom.lastIndexOf('$');
        return instanceSuffix >= 0 ? atom.substring(0, instanceSuffix) : atom;
    }

    private static Decision indeterminate(ObligationDefinition definition, String message) {
        return new Decision(DecisionStatus.INDETERMINATE, definition.id(), message, List.of());
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
