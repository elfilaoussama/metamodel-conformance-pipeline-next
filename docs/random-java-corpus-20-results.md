# Random Java corpus: 20-repository result

This report records the first cross-repository execution of the pipeline. It is
an engineering validation, not a population estimate for Java repositories.

## Reproducibility

- Pipeline commit: `45c3eabd5ebc5f2757365405ae73c4646dd8e2b0`
- GitHub Actions run: [33335401769](https://github.com/elfilaoussama/metamodel-conformance-pipeline-next/actions/runs/33335401769)
- Run date: 2026-08-30
- Manifest: `corpus/random-java-20.tsv`
- Sampling seed: `mcp-corpus-2026-08-30-v1`
- Sampling frame: the first 100 GitHub search results documented in
  `corpus/README.md`; repositories were ranked by
  `SHA-256(seed NUL repository_full_name)` and the first 20 were selected.
- Every repository was checked out at the commit recorded in the manifest.
- Repository code was neither built nor executed.

The run processed 2,466 Java files. All 20 matrix entries produced a report,
and the summary job refused to succeed unless exactly 20 reports were present.

## Outcome

| Repository | Java files | Outcome | Detail |
|---|---:|---|---|
| AnJiaoDe/TabLayoutNiubility | 71 | NON_CONFORMANT | O-04: 292 witnesses |
| BitgetLimited/proof-of-reserves | 12 | CONFORMANT | All five implemented conditions |
| Enigmatis/graphql-java-annotations | 177 | INDETERMINATE | Alloy ternary-relation translation capacity exceeded at 1,743 atoms |
| ManishK4514/Strivers-A2Z-DSA-Sheet | 382 | TOOL_FAILURE | Duplicate type identity `Node` reported by Spoon |
| MaxToyberman/react-native-ssl-pinning | 4 | CONFORMANT | All five implemented conditions |
| Tickaroo/tikxml | 242 | TOOL_FAILURE | Duplicate type identity `WeekTest` reported by Spoon |
| alpha037/data-structures-and-algorithms | 373 | INDETERMINATE | Alloy ternary-relation translation capacity exceeded at 2,004 atoms |
| eduardo-mior/URI-Online-Judge-Solutions | 315 | TOOL_FAILURE | Duplicate type identity `URI` reported by Spoon |
| ekoz/kbase-doc | 68 | CONFORMANT | All five implemented conditions |
| ggsava/block-this | 21 | CONFORMANT | All five implemented conditions |
| hearsilent/DiscreteSlider | 12 | CONFORMANT | All five implemented conditions |
| kymjs/CJFrameForAndroid | 25 | CONFORMANT | All five implemented conditions |
| librespot-org/librespot-java | 148 | INDETERMINATE | Stack overflow while Alloy parsed the exact instance |
| rayokota/kareldb | 106 | INDETERMINATE | Stack overflow while Alloy parsed the exact instance |
| reveny/Android-GUI-Injector | 33 | CONFORMANT | All five implemented conditions |
| shamanland/floating-action-button | 8 | CONFORMANT | All five implemented conditions |
| t0thkr1s/allsafe-android | 20 | CONFORMANT | All five implemented conditions |
| tpcstld/2048 | 12 | CONFORMANT | All five implemented conditions |
| wellzhi/springboot-flowable | 46 | CONFORMANT | All five implemented conditions |
| yjjdick/sdb-mall | 391 | INDETERMINATE | Stack overflow while Alloy parsed the exact instance |

Aggregate repository outcomes:

- 11 fully conformant;
- 1 nonconformant;
- 5 indeterminate;
- 3 tool failures.

Across the 17 repositories that reached condition evaluation, the condition
counts were:

| Condition | Conformant | Nonconformant | Indeterminate |
|---|---:|---:|---:|
| O-02 | 12 | 0 | 5 |
| O-03 | 12 | 0 | 5 |
| O-04 | 11 | 1 | 5 |
| O-05 | 12 | 0 | 5 |
| O-08-local | 12 | 0 | 5 |

## What the exceptional results mean

The three tool failures are an input-identity problem. A repository may contain
colliding type identities in separate source sets, examples, or alternative
implementations. The current adapter submits the entire repository to one Spoon
model and cannot represent those source-set boundaries, so it produces no
observation or decision.

The five indeterminate outcomes are Alloy representation problems, not imposed
classifier, RAM, or harness limits. Three exact generated modules caused an
Alloy parser `StackOverflowError`. Two larger universes exceeded Alloy's fixed
capacity for representing ternary relations. The pipeline preserved these as
`INDETERMINATE`; it did not convert them to violations or successful checks.

The O-04 result is a genuine disagreement between the two independently
constructed inherited-member relations. For `TabLayoutNiubility`, Spoon's
`getAllMethods`-based observation contained 144 relations absent from the
formal nearest-declaration view and omitted 148 relations required by that
view. The typical pattern is an interface declaration shadowed by a nearer
class implementation. Therefore, the finding identifies an adapter-semantics
defect or an unresolved policy choice; it must not be reported as a defect in
the sampled repository.

## Decision

The vertical slice is deterministic and correctly preserves evidence and
three-valued outcomes, but it is not yet reusable at arbitrary repository
scale. The next implementation boundary should be a canonical, source-set-aware
observation model followed by bounded semantic partitioning of the exact Alloy
instance. Partitioning must preserve cross-partition inheritance facts and
produce one aggregate decision per obligation; merely increasing memory or
silently dropping declarations would invalidate the result.
