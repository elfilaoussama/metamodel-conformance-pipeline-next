package io.github.elfilaoussama.pipeline.cli;

import io.github.elfilaoussama.pipeline.ConformancePipeline;
import io.github.elfilaoussama.pipeline.PipelineResult;
import io.github.elfilaoussama.pipeline.adapter.java.SpoonJavaObserver;
import io.github.elfilaoussama.pipeline.capsule.CapsuleVerification;
import io.github.elfilaoussama.pipeline.capsule.CapsuleVerifier;
import io.github.elfilaoussama.pipeline.model.ClassifierObservation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PipelineCli {
    private PipelineCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        try {
            if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
                usage();
                return args.length == 0 ? 64 : 0;
            }
            return switch (args[0]) {
                case "analyze" -> analyze(slice(args));
                case "verify-capsule" -> verifyCapsule(slice(args));
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    usage();
                    yield 64;
                }
            };
        } catch (IllegalArgumentException usageError) {
            System.err.println("Usage error: " + usageError.getMessage());
            return 64;
        } catch (Exception failure) {
            String message = failure.getMessage();
            System.err.println("INDETERMINATE: "
                    + (message == null ? failure.getClass().getSimpleName() : message));
            return 3;
        }
    }

    private static int analyze(String[] args) throws Exception {
        ParsedOptions options = ParsedOptions.parse(args, Set.of("source", "output"), Set.of("external-parent"));
        Path source = Path.of(options.one("source"));
        Path output = Path.of(options.one("output"));
        PipelineResult result = new ConformancePipeline(new SpoonJavaObserver())
                .analyze(source, output, new HashSet<>(options.many("external-parent")));
        System.out.println(result.decision().status() + ": " + result.decision().message());
        if (!result.decision().witnessClassifierIds().isEmpty()) {
            Map<String, ClassifierObservation> byId = new HashMap<>();
            result.observation().classifiers().forEach(item -> byId.put(item.id(), item));
            System.out.println("Witness:");
            for (String id : result.decision().witnessClassifierIds()) {
                ClassifierObservation item = byId.get(id);
                System.out.println("  " + item.qualifiedName() + " (" + item.sourcePath()
                        + ":" + item.startLine() + ")");
            }
        }
        System.out.println("Capsule: " + result.capsulePath());
        return switch (result.decision().status()) {
            case CONFORMANT -> 0;
            case NON_CONFORMANT -> 2;
            case INDETERMINATE -> 3;
        };
    }

    private static int verifyCapsule(String[] args) {
        ParsedOptions options = ParsedOptions.parse(args, Set.of("capsule"), Set.of());
        CapsuleVerification verification = new CapsuleVerifier().verify(Path.of(options.one("capsule")));
        System.out.println((verification.valid() ? "VALID: " : "INVALID: ") + verification.message());
        return verification.valid() ? 0 : 3;
    }

    private static String[] slice(String[] values) {
        return java.util.Arrays.copyOfRange(values, 1, values.length);
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  analyze --source <dir> --output <dir> [--external-parent <qualified-name>]...
                  verify-capsule --capsule <verification-capsule.json>

                Exit codes: 0 conformant/valid, 2 non-conformant, 3 indeterminate/invalid, 64 usage.
                """);
    }

    private record ParsedOptions(Map<String, List<String>> values) {
        static ParsedOptions parse(String[] args, Set<String> required, Set<String> repeatable) {
            Map<String, List<String>> values = new HashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (!args[index].startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("options must be --name value pairs");
                }
                String name = args[index].substring(2);
                if (!required.contains(name) && !repeatable.contains(name)) {
                    throw new IllegalArgumentException("unknown option --" + name);
                }
                if (required.contains(name) && values.containsKey(name)) {
                    throw new IllegalArgumentException("option --" + name + " may appear only once");
                }
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(args[index + 1]);
            }
            for (String name : required) {
                if (!values.containsKey(name) || values.get(name).get(0).isBlank()) {
                    throw new IllegalArgumentException("missing --" + name);
                }
            }
            return new ParsedOptions(values);
        }

        String one(String name) {
            return values.get(name).get(0);
        }

        List<String> many(String name) {
            return values.getOrDefault(name, List.of());
        }
    }
}
