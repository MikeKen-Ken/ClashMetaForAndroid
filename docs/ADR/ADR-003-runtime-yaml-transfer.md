# ADR-003: Transactional Runtime YAML Transfer

- Status: accepted
- Date: 2026-08-30

## Context

Android and desktop can display their generated Mihomo runtime YAML, but users
cannot save that generated configuration or move it back into either client.
Runtime YAML can contain credentials, and applying an invalid upload must not
break the currently working tunnel.

## Decision

Both profile panels expose confirmed upload and download actions.

- Download writes the currently generated runtime YAML to a user-selected YAML
  document and warns that the export can contain sensitive connection data.
- Upload accepts only a non-empty YAML mapping no larger than 10 MB, imports it
  as a new local profile, and activates it through the existing validated
  profile-switch path.
- Upload never overwrites a subscription or another source profile in place.
- A failed validation or activation keeps the previous active profile. Partial
  candidates and their files are removed.
- Desktop serializes upload with the existing profile-switch lock and places a
  successful upload first because that client treats the first local profile as
  its active local target.
- Android copies the selected document into service-owned pending storage before
  validation, so no long-lived document permission is required.

## Consequences

- Runtime exports are portable between Android and desktop while source
  subscriptions remain unchanged.
- Imported runtime YAML becomes a normal local profile and may still receive the
  client's normal global enhancements and overrides.
- File-picker, runtime, Android-device, and rebuilt-desktop acceptance remain
  necessary; static checks cannot prove those interactions.
