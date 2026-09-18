# Commercial distribution checklist

Engineering checklist for distributing an Android VXP emulator product. This is not legal advice.

## Runtime provenance

- [ ] Ship only independently authored Kotlin source/binaries for the emulator runtime.
- [ ] Do not bundle proprietary vendor SDK headers, libraries, tools, generated SDK catalogs or documentation dumps.
- [ ] Do not include MREmu source/binaries or code copied from another emulator.
- [ ] Keep a provenance record for compatibility changes and regression tests.

## Content separation

- [ ] APK/AAB contains no commercial `.vxp` game/application files unless redistribution rights are documented.
- [ ] Users import their own files through Android SAF/file picker.
- [ ] Save data is stored in an app-private sandbox per imported title.
- [ ] Do not market bundled third-party game names/logos as if they are part of the emulator product without permission.

## Sensitive platform behavior

- [ ] Guest SMS/telephony/payment operations must be denied or require an explicit host policy; never silently perform billed actions.
- [ ] Network access should be opt-in/policy-controlled and clearly disclosed before implementing guest HTTP/socket passthrough.
- [ ] Guest filesystem access must not escape the app sandbox.
- [ ] Device identifiers returned to guests should be synthetic unless the user explicitly enables a documented feature that requires a real identifier and platform policy permits it.

## Monetization

- [ ] Ads or a Pro tier should monetize emulator features, not unauthorized copyrighted content.
- [ ] Pro features can include performance controls, save states, controller customization, visual filters, library organization or advanced debugging.
- [ ] Clearly separate emulator functionality from third-party games/apps supplied by users.

## Release process

- [ ] Run `verify_clean_room.sh` before every release.
- [ ] Run CPU/regression tests and representative VXP compatibility tests.
- [ ] Review all third-party dependencies and preserve their required notices/licenses.
- [ ] Obtain qualified legal review for target jurisdictions, trademarks, reverse-engineering exceptions and store policies before commercial launch.
