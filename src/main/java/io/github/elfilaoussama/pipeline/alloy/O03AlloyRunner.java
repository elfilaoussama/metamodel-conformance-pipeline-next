package io.github.elfilaoussama.pipeline.alloy;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import io.github.elfilaoussama.pipeline.decision.Decision;
import io.github.elfilaoussama.pipeline.decision.DecisionStatus;
import io.github.elfilaoussama.pipeline.model.Observation;

import java.util.List;

public final class O03AlloyRunner {
    public Decision evaluate(Observation observation, String alloyModel) {
        if (!observation.isComplete()) {
            return new Decision(
                    DecisionStatus.INDETERMINATE,
                    "O-03",
                    "Unresolved parent references prevent an acyclicity decision.",
                    List.of());
        }
        try {
            A4Reporter reporter = new A4Reporter();
            CompModule module = CompUtil.parseEverything_fromString(reporter, alloyModel);
            Command command = module.getAllCommands().stream()
                    .filter(item -> ExactAlloyEncoder.COMMAND.equals(item.label))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "generated Alloy model has no " + ExactAlloyEncoder.COMMAND + " command"));
            A4Options options = new A4Options();
            A4Solution solution = TranslateAlloyToKodkod.execute_command(
                    reporter, module.getAllReachableSigs(), command, options);
            if (!solution.satisfiable()) {
                return new Decision(
                        DecisionStatus.CONFORMANT,
                        "O-03",
                        "No inheritance cycle exists in the exact observed graph.",
                        List.of());
            }
            List<String> witness = CycleWitness.find(observation);
            if (witness.isEmpty()) {
                return new Decision(
                        DecisionStatus.INDETERMINATE,
                        "O-03",
                        "Alloy found a witness but the source witness could not be reconstructed.",
                        List.of());
            }
            return new Decision(
                    DecisionStatus.NON_CONFORMANT,
                    "O-03",
                    "An inheritance cycle exists in the exact observed graph.",
                    witness);
        } catch (Exception | LinkageError failure) {
            return new Decision(
                    DecisionStatus.INDETERMINATE,
                    "O-03",
                    "Alloy evaluation failed: " + safeMessage(failure),
                    List.of());
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
