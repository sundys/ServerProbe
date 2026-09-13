package main

// 与平台无关的指标计算纯函数（便于在任意平台测试）。

type cpuTimes struct{ total, busy uint64 }

// cpuPercent 按 (busy 差)/(total 差) 计算区间 CPU 占用率，钳制在 [0,100]。
func cpuPercent(cur, prev cpuTimes) float64 {
	dTotal := float64(cur.total) - float64(prev.total)
	if dTotal <= 0 {
		return 0
	}
	dBusy := float64(cur.busy) - float64(prev.busy)
	p := dBusy / dTotal * 100
	if p < 0 {
		p = 0
	}
	if p > 100 {
		p = 100
	}
	return p
}

func clampF(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
