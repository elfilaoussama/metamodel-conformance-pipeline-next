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
java -jar target/metamodel-conformance-pipeline-next-0.6.0-SNAPSHOT.jar \
  analyze --source examples/acyclic --output build/acyclic
java -jar target/metamodel-conformance-pipeline-next-0.6.0-SNAPSHOT.jar \
  verify-capsule --capsule build/acyclic/verification-capsule.json
```

The same input and tool version produce byte-identical `observation.xmi`, Alloy
model, and capsule. Capsule format v6 archives the exact Alloy 6.2.0/SAT4J
execution profile, including symmetry, skolemization, overflow, unrolling, core,
and decomposition options. Replay rejects any missing or changed option before
interpreting the artifacts. Wall-clock timestamps are intentionally excluded. Invariant
metadata and evidence requirements come from one registry; all invariant semantics
come from one Alloy resource. For each evaluable invariant, the registry declares
which observed relations connect an exact solver work unit and which atom kind
roots those units. The evaluator solves every deterministic connected component,
aggregates the Alloy-defined witnesses, and maps their atoms back to source
locations. Java performs structural partitioning only; it does not decide whether
an invariant is violated. An inconsistent work unit is `NOT_EVALUATED`, never
`CONFORMANT`.

Artifact publication is fail-closed. Producers and verifiers enforce the same byte
limits (32 MiB XMI, 16 MiB Alloy, and 1 MiB capsule), with UTF-8 text measured as
encoded bytes. XMI and Alloy files are replaced atomically, and the capsule is
written last as the completed-run marker. Once extraction succeeds, a failed rerun
removes any older capsule before publishing new artifacts, so partial output cannot
be mistaken for a verified result.

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
evidence, witness arity, partition relations, and partition roots. The generic
evaluator discovers every entry, checks its evidence, plans its exact work units,
evaluates its Alloy function, maps its tuples to provenance, and records the
result. Java changes are needed only when a genuinely new kind of source evidence
or structural projection primitive must be supported—not when an invariant
formula changes.

The canonical EMF model retains member kind, name, inheritability, and complete
ordered parameter-type lists. The exact Alloy model preserves names, parameter
positions, and parameter types as separate structural relations. Alloy—not
Java—defines namespace-key equality. Future invariants may reuse this evidence;
if they need a genuinely new source fact, the observation contract and adapter
must be extended rather than evaluating from invented evidence.

## Scope

The current adapter accepts a closed Java source root. Parent types declared
outside that root must be explicitly allowlisted with `--external-parent`; an
unallowlisted parent makes hierarchy-dependent invariants `NOT_EVALUATED`. Resource exhaustion and
solver failures are reported as failures, not scientific limits or findings.
Declarations with the same qualified name in different source paths retain distinct
path-based identities. A reference that cannot be assigned uniquely across those
declarations remains unresolved, so hierarchy-dependent invariants are
`NOT_EVALUATED`; the adapter never chooses a source set implicitly.

Java files rejected by the parser remain in the hashed source set and are recorded
as normalized, source-path diagnostics in schema-v5 `observation.xmi`. Valid files
may still be preserved as partial observations, but no evidence kind is marked
complete and every invariant is `NOT_EVALUATED`. The Alloy artifact and capsule are
still emitted and independently replayable; a parse error is not a missing result.

See [the observation contract](docs/decisions/0001-observation-contract.md) and
[the invariant pipeline contract](docs/invariant-pipeline.md).
