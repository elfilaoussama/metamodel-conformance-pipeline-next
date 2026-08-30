# Metamodel Conformance Pipeline Next

A deterministic, fail-closed pipeline that observes source code as a small EMF
model and evaluates structural constraints with the official Alloy engine.

The pipeline currently evaluates five independent catalog entries:

- **O-02:** every observed member has exactly one declaring classifier;
- **O-03:** inheritance must be acyclic;
- **O-04:** the frontend-observed inherited view must equal Alloy's formal derivation;
- **O-05:** local and inherited member atoms must be disjoint; and
- **O-08-local:** method keys and attribute names are locally unique.

```text
Java source -> Spoon adapter -> observation.xmi -> exact Alloy instance
            -> official Alloy solution -> verification-capsule.json
```

This is deliberately not a general source-code-to-Ecore reverse engineer. The
Ecore model is a stable observation contract shared by language adapters. Each
adapter records only evidence required by the constraints.

## Decision semantics

| Result | Meaning |
|---|---|
| `CONFORMANT` | The condition's Alloy witness relation is empty. |
| `NON_CONFORMANT` | The condition's Alloy witness relation contains one or more tuples. |
| `INDETERMINATE` | Required evidence is missing or the tool failed; no conformance claim is made. |

Unresolved parent types therefore cannot silently disappear from the graph.

## Build and run

Requirements: JDK 17 and Maven 3.9+.

```bash
mvn verify
mvn -q -DskipTests package
java -jar target/metamodel-conformance-pipeline-next-0.3.0-SNAPSHOT.jar \
  analyze --source examples/acyclic --output build/acyclic
java -jar target/metamodel-conformance-pipeline-next-0.3.0-SNAPSHOT.jar \
  verify-capsule --capsule build/acyclic/verification-capsule.json
```

The same input and tool version produce byte-identical `observation.xmi`, Alloy
model, and capsule. Wall-clock timestamps are intentionally excluded. Obligation
metadata comes from one catalog and all formal semantics come from one Alloy
resource. The runner solves the exact observation once, checks that it is
satisfiable, and evaluates every Alloy-defined witness function on that same exact
solution. Java only checks declared evidence prerequisites and maps Alloy witness
atoms back to source locations. An inconsistent encoding is `INDETERMINATE`, never
`CONFORMANT`.

For O-04, Spoon supplies the observed inherited memberships while Alloy derives
the expected memberships independently from ancestry, inheritability, member
keys, local hiding, and nearer-ancestor priority. If the frontend cannot resolve
that view completely, only inheritance-dependent conditions are `INDETERMINATE`.

## Scope

The current adapter accepts a closed Java source root. Parent types declared
outside that root must be explicitly allowlisted with `--external-parent`; an
unallowlisted parent makes the result `INDETERMINATE`. Resource exhaustion and
solver failures are reported as failures, not scientific limits or findings.

See [the observation contract](docs/decisions/0001-observation-contract.md) and
[the O-03 decision protocol](docs/decisions/0002-o03-decision-protocol.md).
