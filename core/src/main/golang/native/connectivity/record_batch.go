package connectivity

import (
	"sync"
	"time"
)

const statsRecordBatchLimit = 64
const statsRecordBatchWindow = 5 * time.Millisecond

type statsRecordSample struct {
	name           string
	delay, timeout int
	at             time.Time
}

type statsRecordRequest struct {
	sample statsRecordSample
	done   chan struct{}
}

type statsRecordBatchWriter struct {
	once     sync.Once
	requests chan statsRecordRequest
}

var statsRecordWriter statsRecordBatchWriter

func (w *statsRecordBatchWriter) record(sample statsRecordSample) {
	w.once.Do(func() {
		// Keep the queue bounded so a large delay test cannot grow memory
		// without limit. Acknowledgement remains synchronous: when URLTest
		// returns, its sample is already durable.
		w.requests = make(chan statsRecordRequest, statsRecordBatchLimit)
		go runStatsRecordBatches(w.requests, persistStatsRecordBatch)
	})
	done := make(chan struct{})
	w.requests <- statsRecordRequest{sample: sample, done: done}
	<-done
}

func runStatsRecordBatches(
	requests <-chan statsRecordRequest,
	persist func([]statsRecordSample),
) {
	for first := range requests {
		batch := []statsRecordRequest{first}
		timer := time.NewTimer(statsRecordBatchWindow)
	collect:
		for len(batch) < statsRecordBatchLimit {
			select {
			case request, ok := <-requests:
				if !ok {
					break collect
				}
				batch = append(batch, request)
			case <-timer.C:
				break collect
			}
		}
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}

		samples := make([]statsRecordSample, len(batch))
		for i, request := range batch {
			samples[i] = request.sample
		}
		persist(samples)
		for _, request := range batch {
			close(request.done)
		}
	}
}

func persistStatsRecordBatch(samples []statsRecordSample) {
	statsMu.Lock()
	defer statsMu.Unlock()
	ensureStatsLoaded()

	for _, sample := range samples {
		entry := statsCache[sample.name]
		if entry.Days == nil {
			entry.Days = make(map[string]dayCounts)
		}
		day := todayKey(sample.at)
		counts := entry.Days[day]
		if sample.delay > 0 && sample.delay < sample.timeout {
			counts.Success = safeAddCount(counts.Success, 1)
			counts.DelaySum = safeAddCount(counts.DelaySum, int64(sample.delay))
			entry.LastSuccessAt = sample.at.Unix()
		} else {
			counts.Failure = safeAddCount(counts.Failure, 1)
			counts.DelaySum = safeAddCount(counts.DelaySum, defaultPenaltyDelayMs)
		}
		entry.Days[day] = counts
		statsCache[sample.name] = entry
	}

	pruneExpiredEntries(time.Now())
	_ = persistConnectivityStats()
}
