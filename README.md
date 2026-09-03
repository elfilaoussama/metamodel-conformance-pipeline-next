# Metamodel Conformance Pipeline Next

A deterministic, fail-closed pipeline that observes source code as a small EMF
model, maps extracted evidence to registered invariants, and evaluates those
invariants with the official Alloy engine.

The current repository profile contains eleven semantic checks spanning the
manuscript conditions O-02 through O-09:

- `exclusive-declaration-ownership` (trace: O-02);
- `acyclic-generalization` (trace: O-03);
- `inherited-view-consistency` (trace: O-04);
- `local-inherited-separation` (trace: O-05);
- `implementation-binding-consistency` (trace: O-06);
- `abstraction-implementation-consistency` (trace: O-07 repository profile);
- `static-abstract-method-separation` (trace: O-07 repository profile);
- `local-namespace-uniqueness` (trace: O-08-local);
- `inherited-namespace-uniqueness` (trace: O-08-inherited);
- `override-relation-consistency` (trace: O-09 bridge/correspondence); and
- `override-discipline` (trace: O-09 strict policy).

O-01 remains an identity-preservation/bridge-integrity question rather than a
source conformance predicate because generated technical keys are not independent
source identities. The O-07 direct-instance clause also remains outside the
repository experiment because runtime/model objects are not observed.

```text
source -> language observer -> extracted evidence -> canonical observation.xmi
       -> invariant registry + exact Alloy instance -> official Alloy solution
       -> invariant witnesses + provenance -> verification-capsule.json
```

This is deliberately not a general source-code-to-Ecore reverse engineer. The
Ecore model is a stable observation contract shared by language adapters. Each
adapter records evidence; it never implements an invariant.
