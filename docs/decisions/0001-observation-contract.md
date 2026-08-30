# ADR 0001: Use a minimal EMF observation contract

Status: accepted

## Decision

Language adapters populate one versioned Ecore metamodel. They do not infer a
domain metamodel from arbitrary source code. The first schema records:

- source units and SHA-256 digests;
- declared classifiers and stable identifiers;
- inheritance edges between declared classifiers;
- unresolved parent references with source locations; and
- adapter identity and version.

No method bodies, comments, or source text are stored. Classifiers and edges are
sorted before serialization. Identifiers derive from language, normalized
relative path, classifier kind, and qualified name.

## Boundary

The source root is a closed observation boundary. Every non-platform parent must
either resolve to a classifier inside the boundary or appear in the explicit
external-parent allowlist. Missing evidence is represented, never discarded.

This boundary makes adapters replaceable while keeping the downstream Alloy
encoding independent of Java, Python, or C++ parser APIs.

## Security and determinism

- Real paths must remain beneath the declared source root.
- Symbolic-link source files are rejected.
- Output contains hashes and normalized relative paths, not source contents.
- Serialization contains no timestamps or machine-specific absolute paths.
- Schema and adapter versions are explicit compatibility inputs.
