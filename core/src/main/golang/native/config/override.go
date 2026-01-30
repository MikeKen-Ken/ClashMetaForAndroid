package config

import (
	"encoding/json"
	"io"
	"os"
	"strings"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

type OverrideSlot int

const (
	OverrideSlotPersist OverrideSlot = iota
	OverrideSlotSession
)

const defaultPersistOverride = `{}`
const defaultSessionOverride = `{}`

var sessionOverride = defaultSessionOverride

// sessionOverrideLogLevel is used to apply log-level from session override immediately
type sessionOverrideLogLevel struct {
	LogLevel *string `json:"log-level"`
}

func overridePersistPath() string {
	return constant.Path.Resolve("override.json")
}

func ReadOverride(slot OverrideSlot) string {
	switch slot {
	case OverrideSlotPersist:
		file, err := os.OpenFile(overridePersistPath(), os.O_RDONLY, 0600)
		if err != nil {
			return defaultPersistOverride
		}

		buf, err := io.ReadAll(file)
		if err != nil {
			return defaultPersistOverride
		}

		return string(buf)
	case OverrideSlotSession:
		return sessionOverride
	}

	return ""
}

func WriteOverride(slot OverrideSlot, content string) {
	switch slot {
	case OverrideSlotPersist:
		file, err := os.OpenFile(overridePersistPath(), os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
		if err != nil {
			return
		}

		_, err = file.Write([]byte(content))
	case OverrideSlotSession:
		sessionOverride = content
		// Apply log-level immediately so changing level in UI takes effect without reloading config
		var o sessionOverrideLogLevel
		if err := json.Unmarshal([]byte(content), &o); err == nil && o.LogLevel != nil {
			levelText := strings.ToLower(strings.TrimSpace(*o.LogLevel))
			if level, ok := log.LogLevelMapping[levelText]; ok {
				log.SetLevel(level)
			}
		}
	}
}

func ClearOverride(slot OverrideSlot) {
	switch slot {
	case OverrideSlotPersist:
		_ = os.Remove(overridePersistPath())
	case OverrideSlotSession:
		sessionOverride = defaultSessionOverride
	}
}
