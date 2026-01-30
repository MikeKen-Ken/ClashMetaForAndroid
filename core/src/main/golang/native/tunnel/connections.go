package tunnel

import (
	"time"

	"github.com/metacubex/mihomo/tunnel/statistic"
)

// connectionDTO is JSON-serializable (atomic types copied to int64, etc.)
type connectionDTO struct {
	Id           string         `json:"id"`
	Metadata     *metadataDTO   `json:"metadata"`
	Upload       int64          `json:"upload"`
	Download     int64          `json:"download"`
	Start        string         `json:"start"`
	Chains       string         `json:"chains"`
	ProviderChains string       `json:"providerChains,omitempty"`
	Rule         string         `json:"rule"`
	RulePayload  string         `json:"rulePayload"`
	RuleDetail   string         `json:"ruleDetail,omitempty"`
}

type metadataDTO struct {
	Network  string `json:"network"`
	Host     string `json:"host"`
	Process  string `json:"process"`
	SourceIP string `json:"sourceIP"`
	DstPort  uint16 `json:"destinationPort,string"`
}

type connectionsSnapshotDTO struct {
	DownloadTotal int64           `json:"downloadTotal"`
	UploadTotal   int64           `json:"uploadTotal"`
	Connections   []connectionDTO `json:"connections"`
}

func QueryConnectionsSnapshot() *connectionsSnapshotDTO {
	snap := statistic.DefaultManager.Snapshot()
	if snap == nil {
		return &connectionsSnapshotDTO{Connections: []connectionDTO{}}
	}
	out := &connectionsSnapshotDTO{
		DownloadTotal: snap.DownloadTotal,
		UploadTotal:   snap.UploadTotal,
		Connections:   make([]connectionDTO, 0, len(snap.Connections)),
	}
	for _, c := range snap.Connections {
		info := c.Info()
		if info == nil {
			continue
		}
		dto := connectionDTO{
			Id:          info.UUID.String(),
			Upload:      info.UploadTotal.Load(),
			Download:    info.DownloadTotal.Load(),
			Start:       info.Start.Format(time.RFC3339),
			Chains:      info.Chain.String(),
			Rule:        info.Rule,
			RulePayload: info.RulePayload,
			RuleDetail:  info.RuleDetail,
		}
		if info.ProviderChain != nil && len(info.ProviderChain) > 0 {
			dto.ProviderChains = info.ProviderChain.String()
		}
		if info.Metadata != nil {
			m := info.Metadata
			dto.Metadata = &metadataDTO{
				Network:  m.NetWork.String(),
				Host:     m.Host,
				Process:  m.Process,
				SourceIP: m.SrcIP.String(),
				DstPort:  m.DstPort,
			}
		}
		out.Connections = append(out.Connections, dto)
	}
	return out
}

func CloseConnection(id string) bool {
	c := statistic.DefaultManager.Get(id)
	if c == nil {
		return false
	}
	_ = c.Close()
	return true
}
