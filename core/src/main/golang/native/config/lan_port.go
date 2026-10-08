package config

// cfa-lan-port is a dedicated override. Historical mixed-port values stay stripped.
const lanPortOverrideKey = "cfa-lan-port"

func consumeLanPortOverride(payload map[string]any) (int, bool) {
	raw, exists := payload[lanPortOverrideKey]
	delete(payload, lanPortOverrideKey)
	if !exists || raw == nil {
		return 0, false
	}

	port, ok := portFromJSON(raw)
	if !ok || port < 1 || port > 65535 {
		return 0, false
	}
	return port, true
}

func portFromJSON(raw any) (int, bool) {
	switch value := raw.(type) {
	case float64:
		port := int(value)
		if value != float64(port) {
			return 0, false
		}
		return port, true
	case int:
		return value, true
	default:
		return 0, false
	}
}
