package tunnel

import "sync"

// delayTestEarlyOrder writes the score order captured at the start of a group
// delay test as soon as a higher-ranked member passes. It does not pin a node.
// The same rule is the desktop delay-test early picker.
type delayTestEarlyOrder struct {
	mu        sync.Mutex
	ordered   []string
	passed    map[string]struct{}
	best      string
	timeoutMs int
	apply     func([]string)
	stopped   bool
}

func newDelayTestEarlyOrder(ordered []string, timeoutMs int, apply func([]string)) *delayTestEarlyOrder {
	return &delayTestEarlyOrder{
		ordered:   append([]string(nil), ordered...),
		passed:    make(map[string]struct{}, len(ordered)),
		timeoutMs: timeoutMs,
		apply:     apply,
	}
}

func (e *delayTestEarlyOrder) onResult(name string, delay int) {
	if e == nil || e.apply == nil {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped || name == "" || name == "DIRECT" || name == "REJECT" {
		return
	}
	if delay <= 0 || delay >= e.timeoutMs {
		return
	}
	e.passed[name] = struct{}{}
	next := ""
	for _, candidate := range e.ordered {
		if _, ok := e.passed[candidate]; ok {
			next = candidate
			break
		}
	}
	if next == "" || next == e.best {
		return
	}
	e.best = next
	e.apply(e.ordered)
}

func (e *delayTestEarlyOrder) stop() {
	if e == nil {
		return
	}
	e.mu.Lock()
	e.stopped = true
	e.mu.Unlock()
}
