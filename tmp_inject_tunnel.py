path = r"f:/work/ClashMetaForAndroid/core/src/main/golang/native/tunnel.go"
with open(path, encoding="utf-8") as f:
    s = f.read()

if "patchRuntimeConnectivityPatchJSON" in s:
    print("already patched")
    raise SystemExit(0)

if 'ncfg "cfa/native/config"' not in s:
    s = s.replace(
        '\t"cfa/native/tunnel"\n\n\t"github.com/metacubex/mihomo/hub/executor"',
        '\t"cfa/native/tunnel"\n\n\tncfg "cfa/native/config"\n\n\t"github.com/metacubex/mihomo/hub/executor"',
        1,
    )

inj = '''

//export patchRuntimeConnectivityPatchJSON
func patchRuntimeConnectivityPatchJSON(p *C.char) C.int {
	if ncfg.ApplyConnectivityPatchFromJSON(C.GoString(p)) {
		return 1
}

	return 0
}

'''

marker = "//export patchSelector"
idx = s.find(marker)
if idx == -1:
    raise SystemExit("marker not found")

s = s[:idx] + inj + s[idx:]

with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

print("tunnel.go patched")
