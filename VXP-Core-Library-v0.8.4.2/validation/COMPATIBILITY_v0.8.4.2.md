# VXP-Core v0.8.4.2 compatibility validation

v0.8.4.2 is a focused FILE_RESOURCE release built on the v0.8.4.1 clean-room Kotlin runtime.

- Existing SYSTEM/GRAPHICS regression behavior remains intact.
- FILE_RESOURCE regression PASS adds path helpers, tell/EOF, copy/rename edge cases, timestamp/attributes and resource-from-file behavior.
- Short post-patch corpus smoke: CatBoxMRE 12 frames, RetroMRE 4 frames, Whisk3D 1 frame; all rendered 240×320 before host timeout.
- Existing observed corpus surface remains 76/76 first-class `vm_*` symbols.

See `FILE_RESOURCE_v0.8.4.2.md` for scope and synthetic-ABI caveats.
