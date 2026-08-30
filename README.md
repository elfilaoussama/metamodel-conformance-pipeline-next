# Metamodel Conformance Pipeline Next

A deterministic, fail-closed pipeline that observes source code as a small EMF
model and evaluates structural constraints with the official Alloy engine.

The first vertical slice implements **O-03: inheritance must be acyclic**:

```text
Java source -> Spoon adapter -> observation.xmi -> exact Alloy instance
            -> official Alloy command -> verification-capsule.json
```

This is deliberately not a general source-code-to-Ecore reverse engineer. The
Ecore model is a stable observation contract shared by language adapters. Each
adapter records only evidence required by the constraints.

## Decision semantics

| Result | Meaning |
|---|---|
| `CONFORMANT` | The exact observed graph contains no O-03 witness. |
| `NON_CONFORMANT` | Alloy found a cycle in the exact observed graph. |
| `INDETERMINATE` | Required evidence is missing or the tool failed; no conformance claim is made. |

Unresolved parent types therefore cannot silently disappear from the graph.

## Build and run

Requirements: JDK 17 and Maven 3.9+.

```bash
mvn verify
mvn -q -DskipTests package
java -jar target/metamodel-conformance-pipeline-next-0.1.0-SNAPSHOT.jar \
  analyze --source examples/acyclic --output build/acyclic
java -jar target/metamodel-conformance-pipeline-next-0.1.0-SNAPSHOT.jar \
  verify-capsule --capsule build/acyclic/verification-capsule.json
```

The same input and tool version produce byte-identical `observation.xmi`, Alloy
model, and capsule. Wall-clock timestamps are intentionally excluded.

## Scope

The current adapter accepts a closed Java source root. Parent types declared
outside that root must be explicitly allowlisted with `--external-parent`; an
unallowlisted parent makes the result `INDETERMINATE`. Resource exhaustion and
solver failures are reported as failures, not scientific limits or findings.

See [the observation contract](docs/decisions/0001-observation-contract.md) and
[the O-03 decision protocol](docs/decisions/0002-o03-decision-protocol.md).
