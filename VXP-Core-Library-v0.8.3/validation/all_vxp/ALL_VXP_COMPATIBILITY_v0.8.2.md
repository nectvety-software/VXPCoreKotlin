# VXP-Core Library v0.8.2 — Compatibility run

Run date: 2026-09-18

Scope: every `.vxp` entry currently visible in Conversation + Library. There are 3 entries but only 2 unique binaries; the two Spider-Man files are byte-identical.

| File | SHA-256 | Backend | Boot/render | Menu | Gameplay | Remaining issue |
|---|---|---|---|---|---|---|
| `CrazyTaxi_1.0.vxp` | `6c4fa09ba9e03bb799a361cf4bd64a1d8e8776ad2458d0cad6c43cb9519ec058` | `FLASH_LITE` | PASS, 176x220, 20 FPS, SWF timeline renders | PASS, startup menu frame 4 | PASS to gameplay scene: OK -> AVM1 actions -> frame 5, `playing=true` | Deeper gameplay/input/audio compatibility not exhaustively tested in this batch |
| `The Amazing Spider-Man - The Daily Bugle(1).vxp` | `54f1b4b5b0a5d3354638f526241a8717a1ba9b64ddf10f6d9d67324d7f0fdae3` | `RAW_ARM_ZLIB` | PASS, 240x320, ~6.87M instructions in 6s smoke, no stubbed symbols | PASS, title/menu screens reached in input-driven run | NOT REACHED | Supplied binary follows a `GL_Demo` / SMS / UNLOCK commercial-demo flow; core intentionally does not bypass billing/unlock. Some internal bitmap-font glyphs remain visually imperfect. |
| `The Amazing Spider-Man - The Daily Bugle.vxp` | `54f1b4b5b0a5d3354638f526241a8717a1ba9b64ddf10f6d9d67324d7f0fdae3` | `RAW_ARM_ZLIB` | PASS, 240x320, ~6.87M instructions in 6s smoke, no stubbed symbols | Same as `(1)`; exact duplicate binary | Same as `(1)` | Exact byte duplicate; no separate compatibility defect found |

## Current rerun evidence

### Crazy Taxi

- `menuFrame=4`
- Stage `176x220`
- `shapes=25`, `sprites=12`, `bitmaps=5`, `buttons=7`
- Pressing `OK` executes 10 AVM1 actions and moves to `afterOkFrame=5`
- `inputEvents=1`
- `playing=true`

### Spider-Man smoke tests

`The Amazing Spider-Man - The Daily Bugle(1).vxp`:

- backend: `RAW_ARM_ZLIB`
- instructions: `6,870,936`
- frames: `75`
- events: `76`
- stubbed symbols: none
- framebuffer: `240x320`
- framebuffer SHA-256: `7613e735b14923b10b6b4c8992c1e2572f2e540640882781d39a8c0505ca432a`

`The Amazing Spider-Man - The Daily Bugle.vxp`:

- backend: `RAW_ARM_ZLIB`
- instructions: `6,868,260`
- frames: `73`
- events: `74`
- stubbed symbols: none
- framebuffer: `240x320`
- framebuffer SHA-256: `7613e735b14923b10b6b4c8992c1e2572f2e540640882781d39a8c0505ca432a`

The slight instruction/frame-count difference is host scheduling/timing; the final framebuffer hash is identical.

An input-driven Spider-Man run additionally reached the title and several menu/operator screens without the previous CPU/memory faults. It did not enter full gameplay because the supplied VXP is a demo/billing-shell build and the library does not emulate purchase/unlock or send real SMS.

## Status summary

Unique binaries tested: 2

- Menu reached: 2/2
- Gameplay scene reached: 1/2 (`CrazyTaxi_1.0.vxp`)
- Stable boot/render with no CPU/memory fault in current smoke path: 2/2
- Unknown/unloadable VXP: 0
