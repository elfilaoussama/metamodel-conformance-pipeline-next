# O-01–O-09 condition contract

This contract fixes the semantic source of truth before additional adapters are
implemented. Ecore stores observations; Alloy alone defines violations.

| ID | Condition | Formal relation or key | Witness unit | Current empirical status |
|---|---|---|---|---|
| O-01 | Independent identity | Distinct carriers have distinct observed identifiers | Carrier pair | Deferred: source-observed identity is not yet justified |
| O-02 | Exclusive declaration ownership | Every method and attribute occurs in exactly one classifier's local-member relation | Member | Implemented |
| O-03 | Acyclic generalization | `no c : Classifier \| c in c.^parents`, where `parents` is the exact projection of resolved internal generalization observations | Classifier | Implemented for the resolved declared-source graph when hierarchy evidence is complete |
| O-04 | Inherited view derivation | Frontend-observed inherited view equals the Alloy-derived ancestor view | Classifier/member pair | Implemented when inherited-view evidence is complete |
| O-05 | Local/inherited separation | Local and frontend-observed inherited member relations are atom-disjoint | Classifier/member pair | Implemented when inherited-view evidence is complete |
| O-06 | Implementation binding | Bindings connect available declarations to independently observed bodies | Binding/body | Deferred pending defensible body evidence |
| O-07 | Abstraction and instantiation | Abstractness, direct instances, and unresolved implementations agree | Classifier/member/object | Deferred pending instantiation and binding evidence |
| O-08 | Namespace and conflict | Method key is name plus ordered parameter types; attribute key is name | Conflicting member pair | Local part implemented; inherited part deferred with O-04 |
| O-09 | Override discipline | Independently resolved overrides satisfy return and implementation policy | Override pair | Deferred pending frontend-resolved override evidence |

## O-03 observation boundary

Observation schema v5 stores each direct generalization as first-class evidence.
A generalization records its child, observed target name, language-specific role,
resolution status, source provenance, and declaration order only when that order
is independently observable.

Resolution status has three meanings:

- `RESOLVED_INTERNAL`: the target is exactly one classifier declared inside the
  analyzed source boundary. Only these edges are projected into Alloy `parents`.
- `EXTERNAL_BOUNDARY`: the target is a recognized platform root or an explicitly
  allowlisted external classifier. The edge remains in EMF evidence but no Alloy
  classifier atom is invented for it.
- `UNRESOLVED`: the target cannot be resolved uniquely. The observation remains
  explicit and hierarchy evidence is incomplete, so O-03 is not evaluated.

Therefore an O-03 conformance result means acyclicity of the exact resolved
internal classifier graph represented by the analyzed declared-source boundary.
It must not be reported as proof of acyclicity across unobserved external code.

## Non-negotiable rules

- `technicalKey` is generated, unique, and used only for traceability.
- `observedIdentifier` is separate, may be absent or duplicate, and is the only
  candidate input to O-01.
- Members are technically contained by the observation root. Declaration
  ownership is a separate, permissive relation so zero-owner and multi-owner
  structures remain representable.
- Parameter types remain an ordered multi-valued structure. They are never
  flattened into a delimiter-separated signature.
- Generalization declaration order is evidence, not a normalization. If source
  positions do not establish the complete sibling order, `declaredOrder` remains
  unknown rather than being synthesized from collection iteration or sorted IDs.
- `inheritedMembers` is populated only from frontend semantic resolution. Alloy
  independently derives its expected view from parents, local declarations,
  inheritability, local hiding, and nearer-ancestor priority.
- Each condition is an independent Alloy predicate and violation function, not
  a global fact. One violation cannot prevent evaluation of another condition.
