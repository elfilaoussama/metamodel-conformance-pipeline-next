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
