# Validation v0.8.0

Game thương mại chỉ được dùng để kiểm thử trong workspace; binary không được đóng gói vào thư viện.

## The Amazing Spider-Man - The Daily Bugle

Public `VxpCoreLibrary.open()` test, 6000 ms:

```text
backend=RAW_ARM_ZLIB
instructions=6896572
frames=76
events=77
stubbed=
frame=240x320 serial=76
sha256(rgb565)=7613e735b14923b10b6b4c8992c1e2572f2e540640882781d39a8c0505ca432a
```

Fixes giữ lại từ v0.6.x:

- RAW ARM two-zlib loader
- ARM/Thumb halfword/signed loads
- ARM BLX immediate
- Thumb LDMIA base-in-list regression fix
- reusable heap split/coalesce/realloc
- resource archive / canvas / blt
- text/file runtime

v0.8 thêm:

- library public API
- `vm_malloc(0)` trả NULL nhưng không tính là OOM/failure
- audio background suspend/resume compatibility API đăng ký trực tiếp
- known `vm_graphic_mirror` compatibility no-op đăng ký trực tiếp, không còn báo unresolved/stub

## CrazyTaxi_1.0.vxp

Android Flash backend source được compile-test với JVM Android-graphics stubs:

```text
menu frame=4 176x220 shapes=25 buttons=7
menu rgb565 sha256=fbf54d2fb50ca06297dfb103f28afd543afb2b421e62f3eacde47fa79c2fb357

after OK frame=5
actions=10
inputs=1
playing=true
gameplay rgb565 sha256=417846c0f972533fd506363cba2fec497f28c57c568fd4341b0848e322222635
```

AVM1 Start vẫn là button action thật, không hard-code frame 5.
