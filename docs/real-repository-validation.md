# Real-repository integration validation

The integration gate analyzes the real
[`elfilaoussama/metamodel-conformance-pipeline`](https://github.com/elfilaoussama/metamodel-conformance-pipeline)
repository, not a copied fixture.

For reproducibility, CI checks out the immutable commit
`25a80241f7514aa0a9e9a5ad2c5ec3fa90277527`. That revision contains 56 Java
source files. The script explicitly declares the six parent types supplied by
the JDK, Swing, and JGit rather than silently omitting those edges.

The gate succeeds only when:

1. source observation completes without unresolved parents;
2. the official Alloy O-03 command returns `CONFORMANT`;
3. a fresh capsule verification repeats the same decision; and
4. every recorded artifact digest remains valid.

The pinned commit makes the test reproducible. Updating the repository under
test is a deliberate reviewable change, not an untracked dependency on its
latest branch state.
