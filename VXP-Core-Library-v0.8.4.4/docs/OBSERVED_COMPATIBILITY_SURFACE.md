# Observed compatibility surface — clean-room method

VXP-Core v0.8.4.3 does not build an API catalog from proprietary vendor headers. Compatibility expansion follows a binary-observation workflow:

1. Take a user-supplied `.vxp` used only as test input.
2. Inspect ELF/container metadata and printable imported/wrapper symbol names present in that binary.
3. Run the independently written Kotlin runtime and record guest register/stack/memory behavior.
4. Implement the smallest general compatibility behavior that explains the observation.
5. Add a synthetic regression and rerun the representative VXP corpus.

`tools/observed_vxp_surface.py` automates step 2 for printable `vm_*` names. It never reads vendor SDK files.

The v0.8.4.3 corpus closes several naming differences seen across toolchains, including screen width/height aliases, callback aliases, image buffer/property aliases, resource init/load aliases and file-size variants.
