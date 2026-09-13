//go:build !linux

package main

import (
	"context"
	"math"
	"os"
	"runtime"
	"sync"
	"time"
)

// 非 Linux 平台（如开发机 Windows）使用演示数据，便于开发与联调 App。
type Collector struct {
	mu          sync.Mutex
	info        HostInfo
	cache       Status
	started     time.Time
	base        time.Time
	traffic     TrafficState
	trafficMu   sync.Mutex
	trafficPath string
	demoRx      uint64
	demoTx      uint64
}

func NewCollector(dataDir string) *Collector {
	c := &Collector{started: time.Now(), base: time.Now().Add(-72 * time.Hour)}
	if dataDir != "" {
		c.trafficPath = dataDir + string(os.PathSeparator) + "traffic.json"
		c.traffic = *loadTraffic(c.trafficPath)
	}
	h, _ := os.Hostname()
	c.info = HostInfo{
		Hostname:     h,
		OS:           "Demo Linux 22.04 (dev mode)",
		Kernel:       "6.8.0-demo",
		Arch:         "x86_64",
		CPUModel:     "Demo CPU @ 3.20GHz",
		CPUCores:     runtime.NumCPU(),
		MemTotal:     16 << 30,
		ProbeVersion: Version,
		StartedAt:    c.started.Format(time.RFC3339),
		Demo:         true,
	}
	return c
}

func (c *Collector) Start(ctx context.Context) {
	c.Refresh()
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				c.Refresh()
			}
		}
	}()
}

func (c *Collector) Info() HostInfo { return c.info }

func (c *Collector) Snapshot() Status {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.cache
}

func (c *Collector) Refresh() {
	s := float64(time.Now().Unix())
	cpu := clamp(38+30*math.Sin(s/37)+8*math.Sin(s/7), 0, 100)
	mem := clamp(55+12*math.Sin(s/61), 0, 100)
	rx := clamp(2.5e6+2e6*math.Sin(s/13)+3e5*math.Sin(s/3), 0, 1e9)
	tx := clamp(8e5+6e5*math.Sin(s/17+1)+1e5*math.Sin(s/4), 0, 1e9)
	dataDisk := 2 << 40 // 2TB，运行期变量避免整型常量转换限制

	st := Status{
		HostInfo:   c.info,
		CPUPercent: cpu,
		CPUPerCore: []float64{cpu, clamp(cpu*0.8+10, 0, 100), clamp(cpu*0.6+5, 0, 100), clamp(cpu*0.4, 0, 100)},
		MemUsed:    uint64(float64(c.info.MemTotal) * mem / 100),
		MemPercent: mem,
		SwapTotal:  4 << 30,
		SwapUsed:   uint64(float64(4<<30) * (mem / 400)),
		Load1:      clamp(cpu/100*4, 0, 16),
		Load5:      clamp(cpu/100*3.5, 0, 16),
		Load15:     clamp(cpu/100*3, 0, 16),
		Procs:      180 + int(20*math.Sin(s/50)),
		UptimeSec:  time.Since(c.base).Seconds(),
		Disks: []DiskInfo{
			{Device: "/dev/sda1", Mount: "/", Fs: "ext4", Total: 500 << 30,
				Used:  uint64(float64(500<<30) * (62 + 3*math.Sin(s/99)) / 100),
				Avail: uint64(float64(500<<30) * 0.35), UsedPct: clamp(62+3*math.Sin(s/99), 0, 100)},
			{Device: "/dev/sdb1", Mount: "/data", Fs: "xfs", Total: 2 << 40,
				Used: uint64(float64(dataDisk) * 0.41), Avail: uint64(float64(dataDisk) * 0.59), UsedPct: 41},
		},
		Net:  []NetRate{{Iface: "eth0", RxBps: rx, TxBps: tx, RxTotal: uint64(s) * 1e5, TxTotal: uint64(s) * 4e4}},
		Time: time.Now().Format(time.RFC3339),
	}
	st.MemAvailable = st.MemTotal - st.MemUsed

	// 演示流量：按当前速率×2s 推进 日/月/总 统计
	c.demoRx += uint64(rx * 2)
	c.demoTx += uint64(tx * 2)
	c.trafficMu.Lock()
	c.traffic.ApplyUpdate(time.Now(), c.demoRx, c.demoTx)
	day, month, total := c.traffic.Snapshot()
	c.trafficMu.Unlock()
	st.NetDay, st.NetMonth, st.NetTotal = day, month, total

	c.mu.Lock()
	c.cache = st
	c.mu.Unlock()
}

// PersistTraffic 演示模式：数据目录为空时跳过落盘。
func (c *Collector) PersistTraffic() {
	if c.trafficPath == "" {
		return
	}
	c.trafficMu.Lock()
	s := c.traffic
	c.trafficMu.Unlock()
	_ = saveTraffic(c.trafficPath, &s)
}

func clamp(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
