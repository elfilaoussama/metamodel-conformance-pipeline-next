# Invariant-driven pipeline contract

The pipeline is independent of the current invariant set.

```text
Source repositories
  -> language-specific observers
  -> extracted evidence with provenance and completeness
  -> canonical EMF observation
  -> mechanical exact Alloy encoding
  -> invariant registry
  -> Alloy witness functions
  -> generic invariant evaluator
  -> CONFORMANT | NON_CONFORMANT | NOT_EVALUATED
  -> witnesses mapped to source provenance
  -> verification capsule and independent replay
```

## Terminology

- **Extracted evidence** is what a language observer can establish from source.
- **Invariant** is a formal property evaluated by Alloy over that evidence.
- **Policy profile** is the selected registry of invariants.
- **Specification trace** links an invariant to a research condition such as
  O-03; it is metadata and never controls execution.

## Stable extension contract

Every registry entry declares a semantic invariant ID, an Alloy witness
function, witness arity, required evidence kinds, and messages. The evaluator
iterates the registry and contains no switch, branch, or class named after an
individual invariant.

Changing an existing formula changes only its Alloy witness function. Adding an
invariant adds one registry entry and one witness function. An observer or the
EMF schema changes only when the new invariant requires evidence that the
canonical observation cannot yet represent.

Missing required evidence produces `NOT_EVALUATED` for only the affected
invariant. A non-empty Alloy witness relation produces `NON_CONFORMANT`; an empty
relation over a satisfiable exact observation produces `CONFORMANT`. Parsing,
encoding, solving, witness-arity, or provenance failures can never produce
`CONFORMANT`.

## Exact work units

The registry declares `partitionRelations` and `partitionRoots` for each
invariant. The generic planner treats the selected observed relations as an
undirected connectivity graph and produces deterministic connected components.
Disconnected components may be packed together to reduce solver startup cost,
but a component is never split. Directed relation tuples are preserved unchanged;
connectivity is used only to identify independent solver work. The packing target
is an execution heuristic, not a repository-size limit: no observed atom or tuple
is dropped, and an oversized connected component remains intact.

All witness tuples from all work units are deduplicated and aggregated before the
invariant decision is produced. A failure in any required work unit makes that
invariant `NOT_EVALUATED`. This avoids repository-size caps while preserving every
cross-atom relation declared relevant by the invariant registry.

## Reproducible Alloy execution

Capsule format v6 records the immutable execution configuration actually owned
by the evaluator: Alloy version, solver ID, partial-instance inference, symmetry,
skolem depth, core settings, Kodkod recording, overflow policy, unrolling, and
decomposition settings. The verifier accepts only the frozen supported profile,
reconstructs `A4Options` from that archived object, and rejects configuration
drift before reading or solving the observation artifacts. Machine-specific
temporary-directory fields are deliberately excluded from experiment identity.

## Evidence projections

The canonical EMF observation is the durable evidence boundary. It preserves
member kind, name, inheritability, and complete ordered method-parameter type
lists. The exact Alloy model carries those observations structurally through
`kind`, `memberName`, and `parameterTypeAt` relations. Parameter positions and
type tokens remain separate atoms, so Java never groups members into semantic
namespace-key equivalence classes.

Alloy alone defines whether two methods have the same ordered signature or two
attributes have the same local name. A future invariant may reuse these relations
without changing Java control flow. If it needs a genuinely new source fact, that
fact must first be added to the canonical observation and independently observed;
the pipeline must never infer element-level evidence that the frontend did not
provide.

## Parse diagnostics

Parser-rejected source units remain part of the canonical source-set digest and
are represented as source-path diagnostics in the observation model. Successfully
parsed units may be retained for diagnostic value, but any parse error clears all
global evidence-completeness claims. Consequently every registered invariant is
`NOT_EVALUATED`, while deterministic XMI, Alloy, decisions, and capsule replay are
still produced. A parse failure is therefore explicit evidence about observation
completeness, never a tool-success result or an absent artifact.
