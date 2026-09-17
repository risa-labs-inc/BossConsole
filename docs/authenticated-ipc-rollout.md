# Authenticated IPC rollout

This host change is paired with [runtime #21](https://github.com/risa-labs-inc/boss-microkernel-runtime/pull/21).
It is not a standalone runtime release.

- Build and validate both artifacts together. The runtime must explicitly package
  `META-INF/boss-runtime/security-contract` containing `pinned-tls-v1;subprocess-env-v1`
  followed by a newline, and declare a compatible `minIpcVersion`.
- Missing, old, malformed, and future transport markers fail before spawning a child.
  Do not remove that check to make an old runtime launch.
- A maintainer must assign the minimum supported host version and coordinate runtime
  distribution. Old hosts downloading runtime releases directly may ignore a minimum
  host field. Publishing the new runtime as the generic latest artifact before coordinating
  those clients is not safe.
- Roll back a failed deployment as a matched host/runtime pair, not one component alone.
  PR approval does not authorize production publication.

The transport pins the server certificate and authenticates callers with per-instance
bearer credentials. It is not mutual TLS or an OS sandbox. Code running with the user's
OS privileges can inspect process environments; unrelated subprocesses must not inherit
the IPC credentials. Revocation invalidates an old process instance, including idle streams.

Private state is owner-instance scoped. The current API intentionally returns an empty
value for an absent key and a permission error for an existing foreign key; key existence
is therefore not confidential. Watching an absent key waits, and fails permission checks
if a foreign instance subsequently creates it. Applications requiring confidential key
existence need a separately specified state-addressing contract before relying on this API.

The authentication contract was introduced in `boss-ipc-1.1.0.jar`, and a paired runtime declares
`minIpcVersion: 1.1.0`. This distinguishes the credential-required JVM API from the
old `boss-ipc-1.0.0.jar` and lets version-aware old hosts refuse the new runtime. Later
additive IPC releases retain that authenticated contract while advancing the artifact version.
The transport marker is still required; a numeric version is not a TLS capability check.
`connectToService` no longer opens unauthenticated peer connections: absent services
return null, and known services without a delegated credential fail explicitly.
The IPC jar also stops forcing all gRPC classes to initialize at native-image build time;
validate the paired runtime native image with its platform-dependent channel factories.

Terminal sessions and retained exit output are owner-instance scoped. HOST may administer
all sessions; SUPERVISOR does not inherit that authority. An active close requests termination
and retains bounded exit history; a later close removes the stopped entry. Superseded owners
cannot access that history, which HOST can remove or new admissions can evict. Unknown IDs
return NOT_FOUND and foreign IDs PERMISSION_DENIED, so UUID existence is not confidential.
The host-controller credential accepted by a child lives for that child's lifetime; kernel-side
process-token revocation does not revoke this opposite direction. Terminate the child to end it.
