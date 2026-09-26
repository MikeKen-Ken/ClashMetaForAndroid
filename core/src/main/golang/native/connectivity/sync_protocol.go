package connectivity

import (
	"encoding/json"
	"errors"
)

const maxResetEntries = 4096

type resetGeneration struct {
	Counter  int64  `json:"counter"`
	DeviceID string `json:"deviceId"`
}

type resetWatermarksPayload struct {
	V        int                        `json:"v"`
	Resets   map[string]resetGeneration `json:"resets"`
	ClearAll resetGeneration            `json:"clearAll,omitempty"`
}

func validResetDeviceID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, char := range value {
		if (char < 'a' || char > 'z') && (char < 'A' || char > 'Z') &&
			(char < '0' || char > '9') && char != '-' && char != '_' {
			return false
		}
	}
	return true
}

func compareResetGeneration(left, right resetGeneration) int {
	if left.Counter < right.Counter {
		return -1
	}
	if left.Counter > right.Counter {
		return 1
	}
	if left.DeviceID < right.DeviceID {
		return -1
	}
	if left.DeviceID > right.DeviceID {
		return 1
	}
	return 0
}

func sanitizeResetWatermarks(input map[string]resetGeneration) (map[string]resetGeneration, error) {
	result := make(map[string]resetGeneration)
	if len(input) > maxResetEntries {
		return nil, errors.New("too many connectivity reset watermarks")
	}
	for name, generation := range input {
		if name == "" || generation.Counter < 1 || generation.Counter > maxSafeCount ||
			!validResetDeviceID(generation.DeviceID) {
			return nil, errors.New("invalid connectivity reset watermark")
		}
		result[name] = generation
	}
	return result, nil
}

func mergeResetWatermarks(parts ...map[string]resetGeneration) (map[string]resetGeneration, error) {
	merged := make(map[string]resetGeneration)
	for _, part := range parts {
		sanitized, err := sanitizeResetWatermarks(part)
		if err != nil {
			return nil, err
		}
		for name, generation := range sanitized {
			current, found := merged[name]
			if !found || compareResetGeneration(generation, current) > 0 {
				merged[name] = generation
				if len(merged) > maxResetEntries {
					return nil, errors.New("too many connectivity reset watermarks")
				}
			}
		}
	}
	return merged, nil
}

func sanitizeClearAll(value resetGeneration) (resetGeneration, error) {
	if value.Counter == 0 && value.DeviceID == "" {
		return resetGeneration{}, nil
	}
	if value.Counter < 1 || value.Counter > maxSafeCount || !validResetDeviceID(value.DeviceID) {
		return resetGeneration{}, errors.New("invalid connectivity clear-all generation")
	}
	return value, nil
}

func mergeClearAll(parts ...resetGeneration) (resetGeneration, error) {
	var best resetGeneration
	for _, part := range parts {
		generation, err := sanitizeClearAll(part)
		if err != nil {
			return resetGeneration{}, err
		}
		if compareResetGeneration(generation, best) > 0 {
			best = generation
		}
	}
	return best, nil
}

func decodeResetWatermarks(raw string) (map[string]resetGeneration, resetGeneration, error) {
	var payload resetWatermarksPayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return nil, resetGeneration{}, err
	}
	if payload.V != 2 || payload.Resets == nil || len(payload.Resets) > maxResetEntries {
		return nil, resetGeneration{}, errors.New("unsupported connectivity reset watermarks")
	}
	resets, err := sanitizeResetWatermarks(payload.Resets)
	if err != nil {
		return nil, resetGeneration{}, err
	}
	clearAll, err := sanitizeClearAll(payload.ClearAll)
	if err != nil {
		return nil, resetGeneration{}, err
	}
	return resets, clearAll, nil
}

func removeAdvancedResetData(
	current map[string]proxyConnectivityEntry,
	baseline map[string]proxyConnectivityEntry,
	previous map[string]resetGeneration,
	active map[string]resetGeneration,
) {
	for name, generation := range active {
		prior, found := previous[name]
		if !found || compareResetGeneration(generation, prior) > 0 {
			delete(current, name)
			delete(baseline, name)
		}
	}
}
