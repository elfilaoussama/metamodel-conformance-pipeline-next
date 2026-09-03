# O-01–O-09 research condition contract

This contract defines the empirical meaning of each formal condition before a language frontend is allowed to claim it as evaluable. The project separates **observation** from **judgment**: source-language frontends collect independently defensible facts, Ecore/XMI preserves those facts, and Alloy alone defines conformance or violation. O-numbers are manuscript trace labels; executable code uses semantic invariant IDs.

A condition is not made executable merely because its Alloy formula exists. It becomes executable for an observation only when every evidence kind required by that semantic check is complete. Missing evidence yields `NOT_EVALUATED` rather than guessed conformance.

| Trace | Condition | Formal/empirical correspondence | Witness | Repository profile |
|---|---|---|---|---|
| O-01 | Independent identity | Distinct carriers require independently observed identifiers, not generated technical keys; the transport bridge is separately audited for non-conflation | Carrier pair | Source-conformance claim deferred; Source → XMI → Alloy identity preservation audited |
| O-02 | Exclusive declaration ownership | Every observed method/attribute occurs in exactly one local-member relation | Member | Executable |
| O-03 | Acyclic generalization | `no c : Classifier \| c in c.^parents` over the exact observed hierarchy | Classifier | Executable when hierarchy evidence is complete |
| O-04 | Inherited view derivation | Frontend-resolved inherited membership must equal the Alloy-derived ancestor view | Classifier/member pair | Executable when inherited-view evidence is complete |
| O-05 | Local/inherited separation | Local and independently observed inherited membership are atom-disjoint | Classifier/member pair | Executable when inherited-view evidence is complete |
| O-06 | Implementation binding | Standalone source bodies and compiler-resolved declaration/body correspondence form explicit implementer-target-body bindings | Member or body | Executable when implementation evidence is complete |
| O-07 | Abstraction and instantiation | Classifier abstractness must agree with unresolved visible implementations; static methods are separated from abstract method declarations; the formal direct-instance clause additionally requires object evidence | Classifier or member; object for the deferred clause | **Repository-observable abstraction subprofile executable; direct-instance subclause deferred** |
| O-08 | Namespace/conflict | Method key = name + ordered parameter types; attribute key = name | Conflicting member(s) | Local and inherited variants executable under their evidence requirements |
| O-09 | Override discipline | Independently resolved override relationships must satisfy signature/return/implementation policy | Override pair | Deferred pending override/return evidence; O-06 evidence is reusable |

## Evidence and modeling rules

- `technicalKey` and classifier IDs are deterministic generated trace keys. They are not independent semantic identities and cannot satisfy O-01.
- Members and method bodies are technically contained by the observation root, while declaration ownership and implementation binding are explicit relations. This keeps malformed states representable so Alloy can detect them.
- Method-body keys encode source location only; they do not encode a declaring classifier or target method.
- Implementation bindings are ternary observations: **implementer classifier + target method + body**. The schema can therefore represent an implementation whose implementer differs from the target method's declaring classifier.
- Spoon observes standalone Java bodies and source modifiers. javac independently resolves source declarations/body correspondence. Neither frontend decides O-06 or O-07.
- Classifier abstraction (`ABSTRACT`, `CONCRETE`, `UNKNOWN`) and method scope (`INSTANCE`, `STATIC`, `UNKNOWN`) are explicit canonical evidence, not booleans inferred inside Alloy from implementation outcomes.
- Parameter types remain ordered multi-valued observations. They are never flattened into a delimiter-based signature.
- `inheritedMembers` is populated only from frontend semantic resolution. Alloy independently derives its expected inherited view from parents, declarations, visibility, package, inheritability, hiding, and nearer-ancestor priority.
- Each semantic condition is evaluated independently. A violation of one condition does not globally invalidate the exact observation or suppress other decisions.
- `CONFORMANT` means the exact observed facts satisfy that registered check. It does **not** mean unobserved program behavior was proved correct.
- `NOT_EVALUATED` is a first-class research result indicating that the configured observation boundary cannot justify all required evidence.

## O-01 identity bridge audit

O-01 has two questions that must not be conflated.

The first is a **source/model conformance question**: does the observed system expose an independently meaningful identifier space whose uniqueness and closure correspond to the manuscript's formal carrier identifiers? The current repository observers do not justify that claim. Classifier IDs and member `technicalKey` values are generated from deterministic source provenance so that artifacts can be replayed and cross-referenced. Their uniqueness is therefore an implementation property, not independent evidence that a source repository satisfies O-01.

The second is a **representation-bridge question**: once distinct declarations have been observed, can the pipeline transport them without accidentally identifying them by name, signature, value, or serialization label? This narrower question is now audited explicitly.

The bridge audit requires that:

1. distinct declarations with equal names/signatures remain distinct canonical carriers;
2. equal `observedIdentifier` labels remain representable on different carriers rather than acting as XMI identity;
3. XMI round-trip preserves carrier/reference identity;
4. exact Alloy encoding maps distinct technical carriers to distinct atoms and preserves reverse provenance; and
5. the invariant registry contains no executable O-01 check whose only evidence is a pipeline-generated key.

`IdentityBridgeIntegrityTest` exercises these properties. Passing that audit validates the measurement bridge but is **not** reported as repository-level O-01 conformance. See `docs/identity-bridge-audit.md` for the interpretation contract.

## O-07 repository-observable profile

The current repository experiment does not observe runtime/model objects. Therefore it does not reinterpret Java allocation syntax (`new C(...)`) as a formal `Object` or `directInstances` relation. Doing so would change the manuscript semantics and create an unjustified correspondence claim.

The repository-observable O-07 profile instead evaluates two independently registered checks:

1. `abstraction-implementation-consistency`
   - Alloy reuses the O-06 binding relation and derives `unresolvedMethod` itself.
   - If a classifier has any unresolved visible method, the observed classifier must be explicitly abstract.
   - A fully implemented classifier is still allowed to be abstract; the implication is intentionally one-way.
2. `static-abstract-method-separation`
   - Source-observed method abstraction and source-observed static/instance scope are compared in Alloy.
   - A method observed as both static and abstract is a violation.

The formal direct-instance clause remains documented but deferred until an explicit object-observation experiment is defined and independently justified.

## Current executable registry

The registry contains nine semantic checks:

1. `exclusive-declaration-ownership` (O-02)
2. `acyclic-generalization` (O-03)
3. `inherited-view-consistency` (O-04)
4. `local-inherited-separation` (O-05)
5. `implementation-binding-consistency` (O-06)
6. `abstraction-implementation-consistency` (O-07 repository profile)
7. `static-abstract-method-separation` (O-07 repository profile)
8. `local-namespace-uniqueness` (O-08-local)
9. `inherited-namespace-uniqueness` (O-08-inherited)

O-01 remains deferred as a **source-conformance invariant**, with bridge integrity audited separately. O-09 remains deferred on this baseline branch. The O-07 direct-instance subclause also remains deferred even though the repository-observable abstraction subprofile is executable.
