# Multilanguage adapter contract

This branch extends the source-observation boundary without changing the semantic
pipeline. Python and C++ must feed the same canonical observation contract used by
Java:

```text
source -> language observer -> canonical observation.xmi
       -> invariant registry + exact Alloy evaluation
       -> witnesses/provenance -> verification capsule
```

No language receives a private invariant evaluator. Alloy remains the sole semantic
authority.

## Adapter obligations

A language observer may mark an `EvidenceKind` complete only when the corresponding
facts are independently observable from the configured source and dependency
boundary. Unsupported, ambiguous, or unresolved facts remain incomplete and every
invariant requiring them is `NOT_EVALUATED`.

In particular:

- `DECLARATION_OWNERSHIP` requires observable local declarations and their owners.
- `HIERARCHY` requires a complete direct-generalization relation for the declared
  boundary; unresolved bases or parents keep hierarchy-dependent invariants
  unevaluable.
- `LOCAL_SIGNATURES` requires stable member names and ordered parameter-type
  identities. A dynamic language must not invent static types merely to satisfy
  this evidence kind.
- `INHERITABILITY` and visibility must be mapped from the language's own semantics,
  not copied mechanically from Java rules.
- `INHERITED_MEMBERS` must come from an independent semantic resolution mechanism
  appropriate to the language. It must not be populated by reusing the Alloy
  relation that it is intended to validate.

## Technical identity versus empirical identity

Generated classifier/member keys exist only so artifacts can refer to atoms
stably. They are not empirical identifiers and must never be used to make O-01
executable. Any future identity invariant requires an independently justified
source-level identifier for each relevant carrier.

## Language-specific boundaries

### Java

The existing Java observer remains the default. Spoon observes declarations;
`javac` independently observes inherited membership. Explicit dependency JARs are
validated and fingerprinted into the canonical source set.

### Python

The Python adapter must define, before claiming evidence completeness:

1. which source constructs are canonical classifiers and members;
2. how direct base classes are resolved across modules/packages;
3. whether local parameter types are empirically available for a given project;
4. how Python MRO/descriptors/overrides are observed independently; and
5. which dynamic constructs force evidence to remain incomplete.

Type annotations may be recorded as evidence when present, but absence of an
annotation must not be converted into a fabricated static type.

### C++

The C++ adapter must use a compiler-grade semantic frontend for declarations,
qualified types, inheritance, visibility, overloads, and overrides. Preprocessor
configuration, include paths, language standard, and compilation database inputs
are part of the empirical boundary and must be fingerprinted or otherwise made
replayable. A parser-only approximation must not be reported as complete semantic
evidence when macros or compilation options affect the observed program.

## CLI selection

`analyze` accepts an optional canonical source-language selector:

```text
--language java|python|cpp
```

`java` is the backward-compatible default. The Python and C++ selectors fail
explicitly until their observers are implemented; this prevents a language label
from silently routing through Java extraction.

## Extension rule

Adding a language adapter may change observation code and language-specific tests.
It must not add language-specific invariant branches to `ConformancePipeline`, the
Alloy evaluator, or the registry machinery. If a language exposes genuinely new
facts needed by a future invariant, the canonical observation schema must be
extended explicitly and all adapters must state whether that new evidence kind is
complete, incomplete, or unsupported for their configured boundary.
