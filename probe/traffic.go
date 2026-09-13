package main

import (
	"encoding/json"
	"os"
	"time"
)

// TrafficUsage 流量用量（字节）。
type TrafficUsage struct {
	Rx uint64 `json:"rx"`
	Tx uint64 `json:"tx"`
}

// TrafficState 持久化的流量累计器：天/月/总量三种口径。
// Last* 保存上次采样的系统累计计数，用于差值推进；重启清零（计数回绕）自动识别。
type TrafficState struct {
	Day       string `json:"day"`   // 归属日期 YYYY-MM-DD
	Month     string `json:"month"` // 归属月份 YYYY-MM
	DayRx     uint64 `json:"day_rx"`
	DayTx     uint64 `json:"day_tx"`
	MonthRx   uint64 `json:"month_rx"`
	MonthTx   uint64 `json:"month_tx"`
	TotalRx   uint64 `json:"total_rx"`
	TotalTx   uint64 `json:"total_tx"`
	LastRx    uint64 `json:"last_rx"`
	LastTx    uint64 `json:"last_tx"`
	HasLast   bool   `json:"has_last"`
	UpdatedAt int64  `json:"updated_at"`
}

// ApplyUpdate 用当前系统累计计数推进统计。
//   - 计数回绕（服务器重启）：回绕后新增部分全部计入
//   - 日期/月份翻转：对应口径清零重新累计
//   - 首次运行（无历史）：无法得知停机期间流量，从当前时刻起算
func (s *TrafficState) ApplyUpdate(now time.Time, curRx, curTx uint64) {
	day := now.Format("2006-01-02")
	month := now.Format("2006-01")

	var dRx, dTx uint64
	if s.HasLast {
		if curRx >= s.LastRx {
			dRx = curRx - s.LastRx
		} else {
			dRx = curRx
		}
		if curTx >= s.LastTx {
			dTx = curTx - s.LastTx
		} else {
			dTx = curTx
		}
	}

	if s.Day != day {
		s.Day = day
		s.DayRx, s.DayTx = 0, 0
	}
	if s.Month != month {
		s.Month = month
		s.MonthRx, s.MonthTx = 0, 0
	}

	s.DayRx += dRx
	s.DayTx += dTx
	s.MonthRx += dRx
	s.MonthTx += dTx
	s.TotalRx += dRx
	s.TotalTx += dTx
	s.LastRx, s.LastTx = curRx, curTx
	s.HasLast = true
	s.UpdatedAt = now.Unix()
}

// Snapshot 返回今日/本月/总量快照。
func (s *TrafficState) Snapshot() (day, month, total TrafficUsage) {
	return TrafficUsage{Rx: s.DayRx, Tx: s.DayTx},
		TrafficUsage{Rx: s.MonthRx, Tx: s.MonthTx},
		TrafficUsage{Rx: s.TotalRx, Tx: s.TotalTx}
}

// saveTraffic 原子写入（临时文件 + rename），避免落盘中途损坏。
func saveTraffic(path string, s *TrafficState) error {
	b, err := json.MarshalIndent(s, "", " ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// loadTraffic 读取持久化状态；文件不存在或损坏时返回零值。
func loadTraffic(path string) *TrafficState {
	s := &TrafficState{}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, s)
	}
	return s
}
