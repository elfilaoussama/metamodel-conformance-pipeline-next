# Metamodel Conformance Pipeline Next

A deterministic, fail-closed pipeline that observes source code as a small EMF
model, maps extracted evidence to registered invariants, and evaluates those
invariants with the official Alloy engine.

The current policy profile maps five research conditions to semantic invariants:

- `exclusive-declaration-ownership` (trace: O-02);
- `acyclic-generalization` (trace: O-03);
- `inherited-view-consistency` (trace: O-04);
- `local-inherited-separation` (trace: O-05); and
- `local-namespace-uniqueness` (trace: O-08-local).

```text
source -> language observer -> extracted evidence -> canonical observation.xmi
       -> invariant registry + exact Alloy instance -> official Alloy solution
       -> invariant witnesses + provenance -> verification-capsule.json
```

This is deliberately not a general source-code-to-Ecore reverse engineer. The
Ecore model is a stable observation contract shared by language adapters. Each
adapter records evidence; it never implements an invariant.

## Decision semantics

| Result | Meaning |
|---|---|
| `CONFORMANT` | The invariant's Alloy witness relation is empty. |
| `NON_CONFORMANT` | The invariant's Alloy witness relation contains one or more tuples. |
| `NOT_EVALUATED` | Required evidence is missing or evaluation failed; no conformance claim is made. |

Unresolved parent types therefore cannot silently disappear from the graph.

## Build and run

Requirements: JDK 17 and Maven 3.9+.

```bash
mvn verify
mvn -q -DskipTests package
java -jar target/metamodel-conformance-pipeline-next-0.4.0-SNAPSHOT.jar \
  analyze --source examples/acyclic --output build/acyclic
java -jar target/metamodel-conformance-pipeline-next-0.4.0-SNAPSHOT.jar \
  verify-capsule --capsule build/acyclic/verification-capsule.json
```

The same input and tool version produce byte-identical `observation.xmi`, Alloy
model, and capsule. Wall-clock timestamps are intentionally excluded. Invariant
metadata and evidence requirements come from one registry; all invariant semantics
come from one Alloy resource. The evaluator solves the exact observation once, checks that it is
satisfiable, and evaluates every Alloy-defined witness function on that same exact
solution. Java only checks declared evidence prerequisites and maps Alloy witness
atoms back to source locations. An inconsistent encoding is `NOT_EVALUATED`, never
`CONFORMANT`.

Spoon's aggregate inherited-member APIs are currently preserved only as provisional
diagnostic observations: corpus validation showed that they do not implement the
Java inheritance view accurately for all interface and overriding cases. The Java
adapter therefore does not declare `INHERITED_MEMBERS` complete. Invariants requiring
that evidence return `NOT_EVALUATED` until an independently validated frontend view
is available; Alloy still derives the expected memberships from the observed structure.

## Invariant extensibility

The Java evaluator contains no invariant identifiers and no invariant-specific
branches. Adding or changing an invariant consists of changing its entry in
`src/main/resources/invariants/registry.json` and its Alloy witness function in
`src/main/resources/alloy/invariants.als`. The registry declares the required
evidence and witness arity. The generic evaluator discovers every entry, checks
its evidence, evaluates its Alloy function, maps its tuples to provenance, and
records the result. Java changes are needed only when a genuinely new kind of
source evidence must be observed—not when an invariant formula changes.

## Scope

The current adapter accepts a closed Java source root. Parent types declared
outside that root must be explicitly allowlisted with `--external-parent`; an
unallowlisted parent makes hierarchy-dependent invariants `NOT_EVALUATED`. Resource exhaustion and
solver failures are reported as failures, not scientific limits or findings.

See [the observation contract](docs/decisions/0001-observation-contract.md) and
[the invariant pipeline contract](docs/invariant-pipeline.md).
