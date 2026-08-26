package tunnel

import (
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestShouldApplyRuntimeConnectivityOrder(t *testing.T) {
	if shouldApplyRuntimeConnectivityOrder(C.Selector) {
		t.Fatal("select should not apply runtime connectivity order")
	}
	if !shouldApplyRuntimeConnectivityOrder(C.URLTest) {
		t.Fatal("url-test should apply runtime connectivity order")
	}
	if !shouldApplyRuntimeConnectivityOrder(C.Fallback) {
		t.Fatal("fallback should apply runtime connectivity order")
	}
}
