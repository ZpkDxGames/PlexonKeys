# PlexonKeys 1.4.0 — Core Provenance Parity Audit

Audited against the released PlexonCore 2.0.x Runtime contracts and the 2.0.2 final-block-outcome patch.

| Current Keys event | Current semantic | Core equivalent | 1.4 ownership/parity |
|---|---|---|---|
| BlockPlace | artificial | Core player-placed origin | YES — Core in Runtime mode |
| BlockMultiPlace | all replaced positions artificial | Core primary BlockPlace path only | PARTIAL — Keys overlay keeps full multi-place set |
| Piston | move provenance | Core piston move | YES for Core state; Keys moves overlay/legacy entries only |
| FallingBlock | carry provenance | none | NO — Keys overlay retained |
| Enderman | carry provenance | none | NO — Keys overlay retained |
| StructureGrow | configurable artificial | none | NO — Keys overlay retained |
| Fertilize | configurable artificial | none | NO — Keys overlay retained |
| BlockGrow | configurable artificial | none | NO — Keys overlay retained |
| BlockForm | configurable artificial | none | NO — Keys overlay retained |
| BlockSpread | preserve artificial source/configured formation | point origin query only | PARTIAL — source can query Core; destination is Keys overlay |
| Explosion | remove | Core removal | YES; Keys also cleans only legacy/overlay state |
| Burn | remove | Core removal | YES; Keys also cleans only legacy/overlay state |
| LeavesDecay | remove | none | NO — Keys overlay cleanup retained |
| Fade | remove | Core removal | YES; Keys also cleans only legacy/overlay state |

## Authority model

In `CORE_RUNTIME`, ordinary player placement and final BlockBreak origin are Core-owned. PlexonKeys keeps its pre-1.4 persisted provenance and unsupported derived-artificial semantics as a conservative policy overlay. A block is reward-eligible only when the Keys overlay does not mark it artificial **and** Core reports `NATURAL`.

`UNKNOWN` is never promoted to natural. Falling blocks without a known carried flag remain conservatively artificial. Growth and formation settings keep their 1.3 behavior.

## Migration note

PlexonCore 2.0.1 introduced idempotent provenance import APIs. PlexonKeys 1.4.0 does not destructively migrate or delete the legacy provenance database during startup; retaining the local overlay preserves rollback and avoids converting derived-artificial positions into weaker semantics. A later release may perform chunk-marked import once derived provenance has a first-class shared representation.
