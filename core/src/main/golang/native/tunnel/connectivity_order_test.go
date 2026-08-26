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

func TestApplyRuntimeConnectivityOrderSkipsDelayCheckGroups(t *testing.T) {
	if ApplyRuntimeConnectivityOrder("Direct") {
		t.Fatal("Direct should not apply runtime connectivity order")
	}
	if ApplyRuntimeConnectivityOrder("Final") {
		t.Fatal("Final should not apply runtime connectivity order")
	}
}
