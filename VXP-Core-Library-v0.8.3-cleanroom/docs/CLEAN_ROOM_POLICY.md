# Clean-room development policy

This project is an independent Kotlin implementation of a VXP execution environment.

## Hard boundaries

- Do not copy or redistribute proprietary vendor SDK headers, libraries, source code, binaries, documentation dumps, or generated catalogs derived from proprietary SDK files.
- Do not link against vendor runtime libraries.
- Do not use any third-party emulator implementation as runtime code.
- Do not use JNI/NDK/C/C++ for guest execution in this project.
- Guest ARM/Thumb code is interpreted by Kotlin code in `vxp-core`.
- Flash Lite compatibility is implemented in Kotlin/Android code in `vxp-core-android`.

## Permitted compatibility inputs for this repository

Compatibility behavior is implemented from independently observed facts such as:

1. ELF metadata, relocation records, imports and exported symbol names present in user-provided `.vxp` binaries.
2. Guest register/stack values and memory accesses observed while running those binaries in this independently written runtime.
3. Input/output behavior seen in regression tests created for this project.
4. Publicly documented ARM/ELF/PNG/zlib/SWF specifications and ordinary platform APIs with compatible licensing.

Function names required by a guest binary may appear in the compatibility dispatcher because the binary imports those names. Their implementation must remain independently written.

## Contribution rule

A contribution that depends on proprietary SDK material must not be merged. Contributors should describe the observable behavior that motivated a patch and add a regression test where possible.

## Distribution

The library does not bundle commercial VXP games. Users supply their own `.vxp` files. Test reports may identify a title used during development, but game binaries are not part of the release archive.

This document is an engineering provenance policy, not legal advice. Distribution and commercialization should still be reviewed for the jurisdictions and third-party licenses relevant to the product.
