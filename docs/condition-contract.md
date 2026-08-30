# O-01–O-09 condition contract

This contract fixes the semantic source of truth before additional adapters are
implemented. Ecore stores observations; Alloy alone defines violations.

| ID | Condition | Formal relation or key | Witness unit | Current empirical status |
|---|---|---|---|---|
| O-01 | Independent identity | Distinct carriers have distinct observed identifiers | Carrier pair | Deferred: source-observed identity is not yet justified |
| O-02 | Exclusive declaration ownership | Every method and attribute occurs in exactly one classifier's local-member relation | Member | Implemented |
| O-03 | Acyclic generalization | `no c : Classifier \| c in c.^parents` | Classifier | Implemented |
| O-04 | Inherited view derivation | Observed inherited view equals the policy-derived ancestor view | Classifier/member pair | Deferred pending independent frontend evidence |
| O-05 | Local/inherited separation | Local and inherited member relations are disjoint | Classifier/member pair | Deferred with O-04 |
| O-06 | Implementation binding | Bindings connect available declarations to independently observed bodies | Binding/body | Deferred pending defensible body evidence |
| O-07 | Abstraction and instantiation | Abstractness, direct instances, and unresolved implementations agree | Classifier/member/object | Deferred pending instantiation and binding evidence |
| O-08 | Namespace and conflict | Method key is name plus ordered parameter types; attribute key is name | Conflicting member pair | Local part implemented; inherited part deferred with O-04 |
| O-09 | Override discipline | Independently resolved overrides satisfy return and implementation policy | Override pair | Deferred pending frontend-resolved override evidence |

## Non-negotiable rules

- `technicalKey` is generated, unique, and used only for traceability.
- `observedIdentifier` is separate, may be absent or duplicate, and is the only
  candidate input to O-01.
- Members are technically contained by the observation root. Declaration
  ownership is a separate, permissive relation so zero-owner and multi-owner
  structures remain representable.
- Parameter types remain an ordered multi-valued structure. They are never
  flattened into a delimiter-separated signature.
- Each condition is an independent Alloy predicate and violation function, not
  a global fact. One violation cannot prevent evaluation of another condition.
