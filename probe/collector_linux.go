//go:build linux

package main

import (
	"context"
	"fmt"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type cpuSample struct {
	at    time.Time
	total cpuTimes
	cores []cpuTimes
}

type netSample struct {
	at  time.Time
	ifs map[string][2]uint64 // iface -> rx, tx
}

// Collector 后台每 2s 采样一次，处理请求时直接返回缓存快照，保证接口低延迟。
type Collector struct {
	mu      sync.Mutex
	info    HostInfo
	prevCPU cpuSample
	prevNet netSample
	cache   Status
	started time.Time
}

func NewCollector() *Collector {
	c := &Collector{started: time.Now()}
	c.info = HostInfo{
		Hostname:     hostname(),
		OS:           osRelease(),
		Kernel:       kernelVersion(),
		Arch:         runtime.GOARCH,
		CPUModel:     cpuModel(),
		CPUCores:     runtime.NumCPU(),
		ProbeVersion: Version,
		StartedAt:    c.started.Format(time.RFC3339),
	}
	return c
}

func (c *Collector) Start(ctx context.Context) {
	c.Refresh() // 立即产出首帧
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

// Refresh 采集一次并更新缓存。首次调用时做短间隔双采样，使 CPU/网速立即有有效值。
func (c *Collector) Refresh() {
	now := time.Now()
	cpuNow := readCPUTimes()
	netNow := readNetTotals()

	c.mu.Lock()
	if c.prevCPU.at.IsZero() {
		c.prevCPU = cpuSample{at: now, total: cpuNow.total, cores: cpuNow.cores}
		c.prevNet = netSample{at: now, ifs: netNow}
		c.mu.Unlock()
		time.Sleep(400 * time.Millisecond)
		now = time.Now()
		cpuNow = readCPUTimes()
		netNow = readNetTotals()
		c.mu.Lock()
	}

	cpuDt := now.Sub(c.prevCPU.at).Seconds()
	netDt := now.Sub(c.prevNet.at).Seconds()

	var cpuPct float64
	var corePcts []float64
	if cpuDt > 0 {
		cpuPct = cpuPercent(cpuNow.total, c.prevCPU.total)
		n := len(cpuNow.cores)
		if len(c.prevCPU.cores) == n {
			corePcts = make([]float64, n)
			for i := 0; i < n; i++ {
				corePcts[i] = cpuPercent(cpuNow.cores[i], c.prevCPU.cores[i])
			}
		}
	}

	var memTotal, memAvail, swapTotal, swapFree uint64
	if mi, err := readMemInfo(); err == nil {
		memTotal = mi["MemTotal"]
		memAvail = mi["MemAvailable"]
		swapTotal = mi["SwapTotal"]
		swapFree = mi["SwapFree"]
	}
	memUsed := uint64(0)
	memPct := 0.0
	if memTotal > 0 {
		memUsed = memTotal - memAvail
		memPct = float64(memUsed) / float64(memTotal) * 100
	}
	swapUsed := uint64(0)
	if swapTotal > 0 {
		swapUsed = swapTotal - swapFree
	}

	var netRates []NetRate
	if netDt > 0 {
		ifaces := make([]string, 0, len(netNow))
		for k := range netNow {
			ifaces = append(ifaces, k)
		}
		sort.Strings(ifaces)
		for _, name := range ifaces {
			cur := netNow[name]
			prev, ok := c.prevNet.ifs[name]
			if !ok {
				prev = cur
			}
			rx := float64(cur[0]-prev[0]) / netDt
			tx := float64(cur[1]-prev[1]) / netDt
			if rx < 0 {
				rx = 0
			}
			if tx < 0 {
				tx = 0
			}
			netRates = append(netRates, NetRate{Iface: name, RxBps: rx, TxBps: tx, RxTotal: cur[0], TxTotal: cur[1]})
		}
	}

	l1, l5, l15, _ := readLoad()
	upSec, _ := readUptime()
	procs, _ := countProcs()

	c.prevCPU = cpuSample{at: now, total: cpuNow.total, cores: cpuNow.cores}
	c.prevNet = netSample{at: now, ifs: netNow}
	c.cache = Status{
		HostInfo:     c.info,
		CPUPercent:   cpuPct,
		CPUPerCore:   corePcts,
		MemUsed:      memUsed,
		MemAvailable: memAvail,
		MemPercent:   memPct,
		SwapTotal:    swapTotal,
		SwapUsed:     swapUsed,
		Load1:        l1, Load5: l5, Load15: l15,
		Procs:     procs,
		UptimeSec: upSec,
		Disks:     readDisks(),
		Net:       netRates,
		Time:      now.Format(time.RFC3339),
	}
	c.mu.Unlock()
}

// ---- /proc 解析（纯函数便于测试）----

// readCPUTimes 读取 /proc/stat 的 cpu 汇总与每核数据。
func readCPUTimes() (agg cpuSample) {
	b, err := os.ReadFile("/proc/stat")
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) < 5 {
			continue
		}
		if f[0] != "cpu" && !strings.HasPrefix(f[0], "cpu") {
			continue
		}
		var idle, iowait, total uint64
		for i := 1; i < len(f); i++ {
			v, _ := strconv.ParseUint(f[i], 10, 64)
			total += v
			if i == 4 {
				idle = v
			}
			if i == 5 {
				iowait = v
			}
		}
		busy := total - idle - iowait
		ct := cpuTimes{total: total, busy: busy}
		if f[0] == "cpu" {
			agg.total = ct
		} else {
			agg.cores = append(agg.cores, ct)
		}
	}
	return
}

func readMemInfo() (map[string]uint64, error) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return nil, err
	}
	out := make(map[string]uint64)
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		v, err := strconv.ParseUint(f[1], 10, 64)
		if err != nil {
			continue
		}
		out[f[0][:len(f[0])-1]] = v * 1024
	}
	return out, nil
}

func readLoad() (l1, l5, l15 float64, err error) {
	b, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return
	}
	f := strings.Fields(string(b))
	if len(f) < 3 {
		err = fmt.Errorf("bad loadavg")
		return
	}
	l1, _ = strconv.ParseFloat(f[0], 64)
	l5, _ = strconv.ParseFloat(f[1], 64)
	l15, _ = strconv.ParseFloat(f[2], 64)
	return
}

func readUptime() (float64, error) {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0, err
	}
	f := strings.Fields(string(b))
	if len(f) < 1 {
		return 0, fmt.Errorf("bad uptime")
	}
	return strconv.ParseFloat(f[0], 64)
}

func countProcs() (int, error) {
	ents, err := os.ReadDir("/proc")
	if err != nil {
		return 0, err
	}
	n := 0
	for _, e := range ents {
		if _, err := strconv.Atoi(e.Name()); err == nil {
			n++
		}
	}
	return n, nil
}

var realFS = map[string]bool{
	"ext2": true, "ext3": true, "ext4": true, "xfs": true, "btrfs": true, "zfs": true,
	"f2fs": true, "jfs": true, "reiserfs": true, "ntfs": true, "ntfs3": true,
	"vfat": true, "exfat": true, "udf": true,
}

func readDisks() []DiskInfo {
	b, err := os.ReadFile("/proc/mounts")
	if err != nil {
		return nil
	}
	seen := map[string]bool{}
	var out []DiskInfo
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 || !realFS[f[2]] {
			continue
		}
		dev, mount := f[0], f[1]
		if seen[dev] || seen[mount] || strings.HasPrefix(dev, "loop") {
			continue
		}
		seen[dev] = true
		seen[mount] = true
		mount = unescapeMount(mount)

		var st syscall.Statfs_t
		if err := syscall.Statfs(mount, &st); err != nil {
			continue
		}
		bs := uint64(st.Bsize)
		total := st.Blocks * bs
		avail := st.Bavail * bs
		used := (st.Blocks - st.Bfree) * bs
		if total == 0 {
			continue
		}
		pct := float64(used) / float64(used+avail) * 100
		out = append(out, DiskInfo{Device: dev, Mount: mount, Fs: f[2], Total: total, Used: used, Avail: avail, UsedPct: pct})
	}
	return out
}

func unescapeMount(m string) string {
	r := strings.NewReplacer(`\040`, " ", `\011`, "\t", `\134`, `\`)
	return r.Replace(m)
}

func readNetTotals() map[string][2]uint64 {
	out := make(map[string][2]uint64)
	b, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return out
	}
	for i, line := range strings.Split(string(b), "\n") {
		if i < 2 {
			continue
		}
		idx := strings.Index(line, ":")
		if idx < 0 {
			continue
		}
		name := strings.TrimSpace(line[:idx])
		if name == "lo" {
			continue
		}
		f := strings.Fields(line[idx+1:])
		if len(f) < 9 {
			continue
		}
		rx, _ := strconv.ParseUint(f[0], 10, 64)
		tx, _ := strconv.ParseUint(f[8], 10, 64)
		out[name] = [2]uint64{rx, tx}
	}
	return out
}

func hostname() string {
	h, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return h
}

func osRelease() string {
	b, err := os.ReadFile("/etc/os-release")
	if err != nil {
		return "Linux"
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "PRETTY_NAME=") {
			return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), `"`)
		}
	}
	return "Linux"
}

func cpuModel() string {
	b, err := os.ReadFile("/proc/cpuinfo")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "model name") {
			if i := strings.Index(line, ":"); i >= 0 {
				return strings.TrimSpace(line[i+1:])
			}
		}
	}
	return ""
}

func kernelVersion() string {
	var u syscall.Utsname
	if err := syscall.Uname(&u); err != nil {
		return "Linux"
	}
	return int8ArrayString(u.Release[:])
}

func int8ArrayString(arr []int8) string {
	b := make([]byte, 0, len(arr))
	for _, v := range arr {
		if v == 0 {
			break
		}
		b = append(b, byte(v))
	}
	return string(b)
}
