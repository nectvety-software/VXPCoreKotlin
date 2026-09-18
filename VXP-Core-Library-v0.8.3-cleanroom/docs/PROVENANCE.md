# Implementation provenance

## Core

`vxp-core` is Kotlin/JVM source authored for this project. It contains:

- ELF32 ARM loader and relocation handling
- raw ARM/zlib VXP loader
- ARM/Thumb interpreter
- guest memory, heap, stack and event loop
- compatibility dispatcher for guest-imported `vm_*` symbols
- RGB565 layers/canvas/blitting
- sandboxed C:/E: guest filesystem
- resource, PNG and text compatibility helpers

## Android host

`vxp-core-android` contains Kotlin Android adapters only:

- RGB565 to Android Bitmap adapter
- Android text rasterizer
- Flash Lite/SWF/AVM1 Android renderer
- high-level session wrapper

## Compatibility evidence tracked by tests

Current regression families include:

- Flash Lite/SWF timeline and AVM1 button flow
- raw ARM/zlib lifecycle, graphics and resources
- GCC-built ELF initialization, relocation and constructors
- ARM/Thumb instruction semantics found by regression traces
- guest file enumeration and UCS2 path handling

No proprietary vendor SDK files are required to build this repository.
