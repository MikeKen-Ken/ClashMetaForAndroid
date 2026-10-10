package connectivity

import "math"

const (
	displayScoreBestDelayMs  = 50.0
	displayScoreWorstDelayMs = 5000.0
)

// displayScoreFromAvgDelay maps effective delay onto 0 to 100 on a log scale.
// It is clamped, so it must not be used as a sort key: every node at or above
// the worst delay would tie at 0.
func displayScoreFromAvgDelay(avgDelayMs float64) float64 {
	if math.IsNaN(avgDelayMs) || math.IsInf(avgDelayMs, 0) || avgDelayMs < 0 {
		avgDelayMs = fallbackDelayMs
	}
	d := math.Min(math.Max(avgDelayMs, displayScoreBestDelayMs), displayScoreWorstDelayMs)
	return 100 * math.Log(displayScoreWorstDelayMs/d) /
		math.Log(displayScoreWorstDelayMs/displayScoreBestDelayMs)
}
