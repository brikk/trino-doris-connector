# Developer documentation

The public [README](../README.md) describes using the connector. This directory
contains the current development reference, not a chronological work log.

- [Trino 483 compatibility](TRINO-483.md): minimal adaptation and reused qualification.
- [Development](DEVELOPMENT.md): build/test commands, disposable clusters,
  explicit permissions, resource management and diagnostics.
- [Architecture and invariants](ARCHITECTURE.md): read-only layers, mapping and
  pushdown boundaries, metadata/cache policy, HA and cancellation.
- [Metadata contract](METADATA.md): the pinned registry, drift checks and Unicode
  evidence handed to brikk-sql.
- [Validation](VALIDATION.md): the qualified source, coverage and how to repeat it.
- [Releasing](RELEASING.md): existing version/build/package/publish mechanics.
- [Backlog](BACKLOG.md): unresolved requirements, risks and deferred decisions;
  not an automatically expanding release-blocker list.
- [Opt-in probes](probes/README.md): targeted regression replays outside the default build.

[archive/](archive/README.md) retains founding plans, implementation journals,
probe output, reviews and qualification records. Their dates/versions/statuses
describe the recorded checkpoint, not today's implementation. Completed TODOs
are not active work; remaining knowledge is carried into the guides/backlog above.

Two short report redirects remain at their published paths for links in the
upstream Unicode evidence issue. Their historical payloads live in the archive.
