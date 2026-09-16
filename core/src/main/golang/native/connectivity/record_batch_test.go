package connectivity

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestRecordBatchesAreBoundedAndAcknowledgedAfterPersistence(t *testing.T) {
	requests := make(chan statsRecordRequest, 130)
	done := make([]chan struct{}, 130)
	for i := range done {
		done[i] = make(chan struct{})
		requests <- statsRecordRequest{
			sample: statsRecordSample{name: fmt.Sprint(i), delay: 25, timeout: 5000, at: time.Now()},
			done:   done[i],
		}
	}
	close(requests)

	total, batches := 0, 0
	runStatsRecordBatches(requests, func(samples []statsRecordSample) {
		if len(samples) > statsRecordBatchLimit {
			t.Fatal("unbounded record batch")
		}
		for i := total; i < total+len(samples); i++ {
			select {
			case <-done[i]:
				t.Fatal("record acknowledged before persistence")
			default:
			}
		}
		total += len(samples)
		batches++
	})

	if total != 130 || batches != 3 {
		t.Fatalf("total=%d batches=%d, want total=130 batches=3", total, batches)
	}
	for _, ch := range done {
		select {
		case <-ch:
		default:
			t.Fatal("missing persistence acknowledgement")
		}
	}
}

func TestConcurrentRecordingPreservesEverySample(t *testing.T) {
	ClearAll()
	t.Cleanup(ClearAll)

	const sampleCount = 300
	var wg sync.WaitGroup
	wg.Add(sampleCount)
	for i := 0; i < sampleCount; i++ {
		go func() {
			defer wg.Done()
			RecordDelayTestResult("concurrent", 25, 5000)
		}()
	}
	wg.Wait()

	statsMu.Lock()
	counts := statsCache["concurrent"].Days[todayKey(time.Now())]
	statsMu.Unlock()
	if counts.Success != sampleCount || counts.Failure != 0 || counts.DelaySum != sampleCount*25 {
		t.Fatalf("concurrent samples were lost: %+v", counts)
	}
}
