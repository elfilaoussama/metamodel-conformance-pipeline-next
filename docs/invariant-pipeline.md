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

## Evidence projections

The canonical EMF observation is the durable evidence boundary. It preserves
complete ordered method-parameter type lists. The active Alloy profile projects
each distinct complete list to one deterministic `SignatureToken`, because the
current namespace invariant needs equality of whole signatures, not access to
individual positions. This projection is injective within the exact observation:
different ordered lists receive different atoms, including lists containing the
same types in a different order.

An invariant that inspects parameter positions or individual types must declare
that richer evidence/projection in its registry contract. The encoder may then
add the required Alloy relation without changing existing invariant-control flow.
Until that projection is available, the invariant is `NOT_EVALUATED`; the
pipeline must never infer element-level evidence from the compact token.

## Parse diagnostics

Parser-rejected source units remain part of the canonical source-set digest and
are represented as source-path diagnostics in the observation model. Successfully
parsed units may be retained for diagnostic value, but any parse error clears all
global evidence-completeness claims. Consequently every registered invariant is
`NOT_EVALUATED`, while deterministic XMI, Alloy, decisions, and capsule replay are
still produced. A parse failure is therefore explicit evidence about observation
completeness, never a tool-success result or an absent artifact.
