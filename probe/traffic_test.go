package main

import (
	"testing"
	"time"
)

func base(t *testing.T) time.Time {
	tm, err := time.ParseInLocation("2006-01-02 15:04:05", "2026-09-14 10:00:00", time.Local)
	if err != nil {
		t.Fatal(err)
	}
	return tm
}

func TestTrafficAccumulate(t *testing.T) {
	now := base(t)
	s := &TrafficState{}
	s.ApplyUpdate(now, 1000, 500)                     // 首次：不计历史
	s.ApplyUpdate(now.Add(2*time.Second), 3000, 1500) // 差值 2000/1000
	day, month, total := s.Snapshot()
	if day.Rx != 2000 || day.Tx != 1000 {
		t.Fatalf("day = %v", day)
	}
	if month.Rx != 2000 || total.Tx != 1000 {
		t.Fatalf("month/total mismatch: %v %v", month, total)
	}
}

func TestTrafficRebootCounterReset(t *testing.T) {
	now := base(t)
	s := &TrafficState{}
	s.ApplyUpdate(now, 1_000_000, 500_000)
	// 重启后计数从 300 重新开始：新增 300 全部计入
	s.ApplyUpdate(now.Add(2*time.Second), 300, 200)
	day, _, total := s.Snapshot()
	if day.Rx != 300 || day.Tx != 200 {
		t.Fatalf("after reboot day = %v", day)
	}
	if total.Rx != 300 {
		t.Fatalf("total = %v", total)
	}
}

func TestTrafficDayRollover(t *testing.T) {
	now := base(t)
	s := &TrafficState{}
	s.ApplyUpdate(now, 1000, 1000)
	s.ApplyUpdate(now, 5000, 5000) // 当日累计 4000/4000
	// 次日：日计数清零，月/总继续
	next := now.Add(24 * time.Hour)
	s.ApplyUpdate(next, 6000, 6000)
	day, month, total := s.Snapshot()
	if s.Day != next.Format("2006-01-02") || day.Rx != 1000 || day.Tx != 1000 {
		t.Fatalf("day rollover wrong: %v %v", s.Day, day)
	}
	if month.Rx != 5000 {
		t.Fatalf("month = %v", month)
	}
	if total.Rx != 5000 {
		t.Fatalf("total = %v", total)
	}
}

func TestTrafficMonthRollover(t *testing.T) {
	now := base(t)
	s := &TrafficState{}
	s.ApplyUpdate(now, 1000, 1000)
	s.ApplyUpdate(now, 5000, 5000)
	// 次月 1 日：月计数清零，总继续
	next := now.Add(31 * 24 * time.Hour) // 2026-10-15，跨月
	s.ApplyUpdate(next, 8000, 8000)
	_, month, total := s.Snapshot()
	if s.Month != next.Format("2006-01") || month.Rx != 3000 {
		t.Fatalf("month rollover wrong: %v %v", s.Month, month)
	}
	if total.Rx != 8000-1000 {
		t.Fatalf("total = %v", total)
	}
}
