package adapter

import (
 "context"
 "testing"
 "time"

 "github.com/metacubex/mihomo/adapter/outbound"
 C "github.com/metacubex/mihomo/constant"
)

func TestReviewAndroidSuccessfulTestRecordsHistory(t *testing.T) {
 previous := C.Path.HomeDir()
 C.SetHomeDir(t.TempDir())
 t.Cleanup(func(){ C.SetHomeDir(previous) })
 server := startTwoShotHEADServer(t, 10*time.Millisecond, 0)
 proxy := NewProxy(&delayedDialAdapter{Base: outbound.NewBase(outbound.BaseOption{Name:"review-node", Type:C.Direct})})
 ctx := C.WithDelayTestTimeoutMs(context.Background(), 1000)
 delay, err := proxy.URLTest(ctx, server.URL, nil)
 if err != nil || delay == 0 { t.Fatalf("baseline failed: delay=%d error=%v",delay,err) }
 if history := proxy.DelayHistory(); len(history) != 1 {
  t.Fatalf("successful Android-style URLTest returned %dms but recorded %d history entries", delay, len(history))
 }
}
