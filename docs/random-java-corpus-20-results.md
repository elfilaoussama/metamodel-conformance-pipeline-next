# Random Java corpus: 20-repository validation

This report records the frozen 20-repository engineering validation of the
metamodel-conformance pipeline. It is not a population estimate for Java
repositories. The corpus is intentionally pinned so architectural regressions can
be distinguished from changes in external repositories.

## Reproducibility

- Current validated pipeline commit: `e68ef12007578403798b1596d55a934d78b272aa`
- Current GitHub Actions run: `33629584804`
- Run date: 2026-09-02
- Manifest: `corpus/random-java-20.tsv`
- Sampling seed: `mcp-corpus-2026-08-30-v1`
- Sampling frame: the first 100 GitHub search results documented in
  `corpus/README.md`; repositories were ranked by
  `SHA-256(seed NUL repository_full_name)` and the first 20 were selected.
- Every repository was checked out at the immutable commit recorded in the
  manifest.
- Repository code and build scripts were not executed.

The current run processed exactly **2,466 Java files**. All **20/20** repositories
completed pipeline analysis and produced independently replayable verification
capsules. CI now rejects the corpus if any repository loses that property or if its
decision set diverges from the current invariant registry.

## Current outcome after architectural corrections

The principal architectural failures found by the first corpus execution are now
closed:

| Previous failure class | Baseline | Current run | Status |
|---|---:|---:|---|
| Duplicate qualified type identity causing tool failure | 3 repositories | 0 | Resolved by source-set-aware path identity |
| Alloy parser stack overflow on monolithic exact instance | 3 repositories | 0 | Resolved by deterministic semantic work-unit partitioning |
| Alloy ternary-relation translation capacity failure | 2 repositories | 0 | Resolved by deterministic semantic work-unit partitioning |
| Repositories producing a report but no valid capsule | 8 repositories | 0 | Resolved |

Current aggregate tool outcome:

- **20 analyzed repositories**;
- **20 valid replayable capsules**;
- **0 tool failures**;
- **0 Alloy representation/capacity failures**.

## Invariant outcomes

The current registry contains six semantic invariants. Their repository-level
results are:

| Invariant | Conformant | Nonconformant | Not evaluated |
|---|---:|---:|---:|
| `exclusive-declaration-ownership` | 19 | 0 | 1 |
| `acyclic-generalization` | 19 | 0 | 1 |
| `local-namespace-uniqueness` | 19 | 0 | 1 |
| `inherited-view-consistency` | 1 | 0 | 19 |
| `local-inherited-separation` | 1 | 0 | 19 |
| `inherited-namespace-uniqueness` | 1 | 0 | 19 |

`alpha037/data-structures-and-algorithms` is the current corpus entry for which all
six registered invariants have complete evidence and all six are conformant.

`ManishK4514/Strivers-A2Z-DSA-Sheet` contains source units that the Java parser
rejects. The pipeline preserves those diagnostics and therefore marks all six
invariants `NOT_EVALUATED`; it does not silently discard the rejected files.

For the other 18 repositories, declaration ownership, hierarchy, and local
signature evidence are sufficient for the three local/core invariants, but the
independent `javac` inherited-member observation is incomplete because the frozen
source checkout does not include a complete compile-time dependency environment.
Those inherited-evidence-dependent invariants therefore remain `NOT_EVALUATED`.

## Evidence diagnostics

The current run records:

- 3,857 `EVIDENCE_INCOMPLETE` diagnostics, overwhelmingly from missing third-party
  compile-time types such as Android, JUnit, SLF4J, generated project types, and
  other dependency classes;
- 11 `PARSE_ERROR` diagnostics in the standalone algorithm corpus entry.

These diagnostics are evidence-boundary findings, not invariant violations. The
corpus harness deliberately does not execute Maven/Gradle or repository code to
manufacture a build environment. Exact dependency archives can be supplied to the
pipeline explicitly and are hashed into the canonical source set.

## Historical baseline: first corpus execution

The first run on 2026-08-30 used pipeline commit
`45c3eabd5ebc5f2757365405ae73c4646dd8e2b0` and GitHub Actions run
`33335401769`. It found:

- 11 fully conformant repositories under the then-five-condition implementation;
- 1 apparent O-04 nonconformance;
- 5 indeterminate Alloy representation failures;
- 3 source-identity tool failures.

The apparent O-04 result on `AnJiaoDe/TabLayoutNiubility` contained 144
Spoon-only and 148 Alloy-only inherited memberships. That result was correctly
interpreted as an adapter-semantics problem rather than a defect in the sampled
repository. The current architecture no longer uses Spoon's inherited-member view
as the independent empirical authority: declaration facts come from Spoon, while
inherited membership is independently observed with the JDK compiler and compared
with the Alloy-derived formal view.

Because the current corpus checkout lacks that repository's full dependency
classpath, O-04 is now correctly `NOT_EVALUATED` rather than reproducing or hiding
the old disagreement.

## Scientific conclusion of this validation phase

The frozen corpus now supports the following claim:

> The core pipeline is deterministic and fail-closed across the 20 pinned Java
> repositories, including source-set collisions and models that previously
> exceeded the monolithic Alloy representation. Every repository produces a
> replayable capsule, and missing empirical evidence remains explicit as
> `NOT_EVALUATED`.

It does **not** support the stronger claim that inherited-member evidence is
complete for arbitrary dependency-rich repositories without an explicit compile
classpath. That boundary is intentional and must remain visible in later empirical
experiments.
