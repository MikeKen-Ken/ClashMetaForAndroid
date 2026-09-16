package connectivity

import "time"

// RecordDelayTestResult counts every completed test symmetrically. Suppressing
// only failures biases the success probability during repeated or shared-group
// checks. Canceled/skipped tests are not observations. The v2 shared counters
// stay additive, so every device's samples still contribute to the same score.
func RecordDelayTestResult(proxyName string, delay int, timeoutMs int) {
	if proxyName == "" || proxyName == "DIRECT" || proxyName == "REJECT" || delay == -2 || delay == -1 {
		return
	}
	if timeoutMs <= 0 {
		timeoutMs = defaultPenaltyDelayMs
	}
	statsRecordWriter.record(statsRecordSample{
		name: proxyName, delay: delay, timeout: timeoutMs, at: time.Now(),
	})
}
