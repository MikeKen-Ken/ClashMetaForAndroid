package connectivity

import "sort"

type nameScoreKey struct {
	index int
	score float64
}

// SortNamesByConnectivity 全局按贝叶斯平滑成功率降序，相同时保留原顺序。
func SortNamesByConnectivity(names []string) []string {
	if len(names) <= 1 {
		return names
	}

	ctx := BuildScoreContext()
	keys := make([]nameScoreKey, len(names))
	for i, name := range names {
		keys[i] = nameScoreKey{
			index: i,
			score: ctx.ScoreFor(name),
		}
	}

	sort.SliceStable(keys, func(i, j int) bool {
		a, b := keys[i], keys[j]
		if a.score != b.score {
			return a.score > b.score
		}
		return a.index < b.index
	})

	out := make([]string, len(names))
	for i, key := range keys {
		out[i] = names[key.index]
	}
	return out
}

// CompareProxyNamesByConnectivity 先比贝叶斯成功率，再比原索引。
func CompareProxyNamesByConnectivity(
	nameA, nameB string,
	originalIndexA, originalIndexB int,
	ctx ScoreContext,
) int {
	scoreA := ctx.ScoreFor(nameA)
	scoreB := ctx.ScoreFor(nameB)
	if scoreA != scoreB {
		if scoreA > scoreB {
			return -1
		}
		return 1
	}
	if originalIndexA < originalIndexB {
		return -1
	}
	if originalIndexA > originalIndexB {
		return 1
	}
	return 0
}
