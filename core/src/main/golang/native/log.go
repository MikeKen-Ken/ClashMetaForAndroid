package main

//#include "bridge.h"
import "C"

import (
	"strings"
	"sync"
	"time"
	"unsafe"

	"cfa/native/delegate"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

func init() {
	go func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			cPayload := C.CString(msg.Payload)

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	}()
}

var (
	logcatMu     sync.Mutex
	logcatDone   chan struct{}
	logcatRemote unsafe.Pointer
)

func stopLogcatLocked() {
	if logcatDone != nil {
		close(logcatDone)
		logcatDone = nil
	}
	if logcatRemote != nil {
		C.release_object(logcatRemote)
		logcatRemote = nil
	}
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) {
	logcatMu.Lock()
	stopLogcatLocked()
	done := make(chan struct{})
	logcatDone = done
	logcatRemote = remote
	logcatMu.Unlock()

	go func(remote unsafe.Pointer, done <-chan struct{}) {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		defer func() {
			logcatMu.Lock()
			if logcatRemote == remote {
				C.release_object(remote)
				logcatRemote = nil
				logcatDone = nil
			}
			logcatMu.Unlock()
		}()

		for {
			select {
			case <-done:
				log.Debugln("Logcat subscriber unsubscribed")
				return
			case msg, ok := <-sub:
				if !ok {
					return
				}
				if msg.LogLevel < log.Level() && !strings.HasPrefix(msg.Payload, "[APP]") {
					continue
				}

				rMsg := &message{
					Level:   msg.LogLevel.String(),
					Message: msg.Payload,
					Time:    time.Now().UnixNano() / 1000 / 1000,
				}

				if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
					log.Debugln("Logcat subscriber closed")
					return
				}
			}
		}
	}(remote, done)

	log.Infoln("[APP] Logcat level: %s", log.Level().String())
}

//export unsubscribeLogcat
func unsubscribeLogcat() {
	logcatMu.Lock()
	defer logcatMu.Unlock()
	stopLogcatLocked()
}

var (
	healthCheckMu       sync.Mutex
	healthCheckCallback unsafe.Pointer
)

//export subscribeHealthCheck
func subscribeHealthCheck(remote unsafe.Pointer) {
	healthCheckMu.Lock()
	if healthCheckCallback != nil {
		C.release_object(healthCheckCallback)
	}
	healthCheckCallback = remote
	healthCheckMu.Unlock()

	log.Infoln("[APP] Health check callback registered")

	delegate.SetHealthCheckNotifyFunc(func(payload string) {
		healthCheckMu.Lock()
		cb := healthCheckCallback
		healthCheckMu.Unlock()
		if cb == nil {
			return
		}
		cPayload := C.CString(payload)
		C.health_check_triggered(cb, cPayload)
	})
}
