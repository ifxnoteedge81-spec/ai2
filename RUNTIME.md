# Local runtime

**Native sandbox (always):** Android's `/system/bin/sh` with toybox utilities, cwd = workspace, minimal environment, timeout, output cap, `ulimit -v`. No Python/Node/gcc.

**Alpine Linux via PRoot (optional):** PRoot is a user-space chroot (ptrace). It is not a VM and cannot run a second Android.
Android 10+ blocks executing downloaded binaries from app storage, so PRoot must be packaged inside the APK as `libproot.so`:

1. Obtain a PRoot binary for your ABI that you trust (PRoot is GPL-2.0: distributing it means complying with the GPL for that component) and its SHA-256.
2. `runtime/scripts/add-proot.sh arm64-v8a /path/to/proot <sha256> [/path/to/loader]` copies it to `app/src/main/jniLibs/arm64-v8a/libproot.so` (git-ignored).
3. Rebuild. In the app: Terminal → *Install Linux (Alpine)* downloads Alpine's minirootfs from dl-cdn.alpinelinux.org, verifies the SHA-256 from Alpine's own release manifest, extracts it, and runs a self-test. Until the self-test passes the app keeps using the native sandbox.
4. Then `apk add python3 nodejs gcc` etc. work inside the shell (network + approval required).

Neither the PRoot path nor the rootfs install has been run on a device yet.
