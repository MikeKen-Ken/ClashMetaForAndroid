package tunnel

import "testing"

type mockSelectable struct {
	name string
}

func (m *mockSelectable) Set(name string) error {
	m.name = name
	return nil
}

func (m *mockSelectable) ForceSet(name string) {
	m.name = name
}

func TestScoreEarlyPickerStopsPinningAfterStop(t *testing.T) {
	sel := &mockSelectable{}
	picker := &scoreEarlyPicker{
		group:      "Auto",
		order:      []string{"a", "b"},
		passed:     make(map[string]struct{}),
		selectable: sel,
	}

	picker.onResult("b", 80, 5000)
	if sel.name != "b" {
		t.Fatalf("expected early pick to pin b, got %q", sel.name)
	}

	picker.stop()
	picker.onResult("a", 20, 5000)
	if sel.name != "b" {
		t.Fatalf("stopped picker must not pin again, got %q", sel.name)
	}
}
