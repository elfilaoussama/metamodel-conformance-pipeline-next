# O-01–O-09 condition contract

This contract fixes the semantic source of truth before additional adapters are
implemented. Ecore stores observations; Alloy alone defines violations. O-numbers
are research-specification trace labels only; executable code uses semantic invariant
IDs.

| ID | Condition | Formal relation or key | Witness unit | Current empirical status |
|---|---|---|---|---|
| O-01 | Independent identity | Distinct carriers have distinct independently observed identifiers | Carrier pair | Deferred: source-observed identity is not yet justified independently of generated technical keys |
| O-02 | Exclusive declaration ownership | Every method and attribute occurs in exactly one classifier's local-member relation | Member | Implemented |
| O-03 | Acyclic generalization | `no c : Classifier \| c in c.^parents` | Classifier | Implemented |
| O-04 | Inherited view derivation | Frontend-observed inherited view equals the Alloy-derived ancestor view | Classifier/member pair | Implemented when inherited-view evidence is complete |
| O-05 | Local/inherited separation | Local and frontend-observed inherited member relations are atom-disjoint | Classifier/member pair | Implemented when inherited-view evidence is complete |
| O-06 | Implementation binding | Bindings connect available declarations to independently observed bodies | Binding/body | Deferred pending defensible body and binding evidence |
| O-07 | Abstraction and instantiation | Abstractness, direct instances, and unresolved implementations agree | Classifier/member/object | Deferred pending instantiation and binding evidence |
| O-08 | Namespace and conflict | Method key is name plus ordered parameter types; attribute key is name | Conflicting member pair | Local and inherited variants implemented; inherited evaluation requires complete inherited-view evidence |
| O-09 | Override discipline | Independently resolved overrides satisfy return and implementation policy | Override pair | Deferred pending frontend-resolved override and return-policy evidence |

## Non-negotiable rules

- `technicalKey` is generated, unique, and used only for traceability.
- Any observed identifier used by an invariant must be separate from generated technical identity and must have an independently defensible source meaning.
- Members are technically contained by the observation root. Declaration ownership is a separate, permissive relation so zero-owner and multi-owner structures remain representable.
- Parameter types remain an ordered multi-valued structure. They are never flattened into a delimiter-separated signature.
- `inheritedMembers` is populated only from frontend semantic resolution. Alloy independently derives its expected view from parents, local declarations, member visibility, classifier package, inheritability, local hiding, and nearer-ancestor priority.
- Each condition is an independent Alloy predicate and violation function, not a global fact. One violation cannot prevent evaluation of another condition.
- Missing required evidence yields `NOT_EVALUATED`; the pipeline never manufactures facts to turn an incomplete observation into conformance.

## Current executable invariant set

The current registry contains six semantic invariants:

1. `exclusive-declaration-ownership` (O-02)
2. `acyclic-generalization` (O-03)
3. `inherited-view-consistency` (O-04)
4. `local-inherited-separation` (O-05)
5. `local-namespace-uniqueness` (O-08-local)
6. `inherited-namespace-uniqueness` (O-08-inherited)

O-01, O-06, O-07, and O-09 remain intentionally deferred until their evidence can
be observed without circularly deriving it from the invariant being tested.
