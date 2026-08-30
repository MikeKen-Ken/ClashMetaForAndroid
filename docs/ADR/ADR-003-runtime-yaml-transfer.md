# ADR-003: Transactional Runtime YAML Transfer

- Status: accepted
- Date: 2026-08-30
- Updated: 2026-08-30

## Context

Android and desktop can display their generated Mihomo runtime YAML, but users
need a shared way to move that generated configuration between clients. Runtime
YAML can contain credentials, and applying an invalid import must not break the
currently working tunnel. Both clients already have a user-configured WebDAV
account used by backups, wallpapers, and connectivity sync.

## Decision

Both profile panels expose confirmed upload and download actions that use the
same HTTPS WebDAV account. The shared object is `clash-runtime.yaml` at the
WebDAV root. A previous nested path `clash-runtime-yaml/runtime.yaml` is still
read as a fallback.

- Upload serializes the currently generated runtime YAML and PUTs it to that
  WebDAV object, and warns that the export can contain sensitive connection data.
- Download GETs that WebDAV object, accepts only a non-empty YAML mapping no
  larger than 10 MB, imports it into a dedicated managed local profile, and
  activates it through the existing validated profile-switch path. Later
  downloads reuse only a profile carrying the client's private ownership marker.
- Transfer requires a configured WebDAV URL, username, and password, and the
  URL must be HTTPS. Desktop uses the strict TLS client (valid certificates).
- Download never identifies an overwrite target by display name alone and never
  overwrites a subscription or another source profile in place. If the preferred
  managed-profile identifier is already occupied, the client creates a new owned
  slot with a collision-free identifier.
- A failed validation or activation keeps the previous active profile. Partial
  candidates and their files are removed; when reusing a managed slot, its prior
  files and metadata are restored.
- Desktop serializes import with the existing profile-switch lock and places a
  successful import first because that client treats the first local profile as
  its active local target.

## Consequences

- Runtime exports are portable between Android and desktop through the shared
  WebDAV object while source subscriptions remain unchanged.
- Imported runtime YAML becomes a managed local profile and may still receive the
  client's normal global enhancements and overrides.
- WebDAV, runtime, Android-device, and rebuilt-desktop acceptance remain necessary;
  static checks cannot prove those interactions.
