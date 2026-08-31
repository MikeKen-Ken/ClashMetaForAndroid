# Connectivity Statistics

This context describes the shared language for delay-test history synchronized between installations.

## Language

**Device contribution**:
The retained connectivity measurements recorded by one installation.
_Avoid_: Device total, local aggregate

**Merged view**:
The connectivity measurements derived from all current device contributions.
_Avoid_: Shared counter, master copy

**Reset generation**:
A per-node identity that separates measurements accepted after a global clear from older measurements that must remain excluded.
_Avoid_: Delete timestamp, file generation

**Global clear**:
A reset that advances a node's reset generation so every installation excludes measurements from older generations.
_Avoid_: Local delete, baseline reset
