package main

import (
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"time"
)

// ---- 数据模型（与 Android 端 JSON 字段一一对应）----

type HostInfo struct {
	Hostname     string `json:"hostname"`
	OS           string `json:"os"`
	Kernel       string `json:"kernel"`
	Arch         string `json:"arch"`
	CPUModel     string `json:"cpu_model"`
	CPUCores     int    `json:"cpu_cores"`
	MemTotal     uint64 `json:"mem_total_bytes"`
	ProbeVersion string `json:"probe_version"`
	StartedAt    string `json:"started_at"`
	Demo         bool   `json:"demo,omitempty"`
}

type DiskInfo struct {
	Device  string  `json:"device"`
	Mount   string  `json:"mount"`
	Fs      string  `json:"fs"`
	Total   uint64  `json:"total_bytes"`
	Used    uint64  `json:"used_bytes"`
	Avail   uint64  `json:"avail_bytes"`
	UsedPct float64 `json:"used_percent"`
}

type NetRate struct {
	Iface   string  `json:"iface"`
	RxBps   float64 `json:"rx_bps"`
	TxBps   float64 `json:"tx_bps"`
	RxTotal uint64  `json:"rx_total_bytes"`
	TxTotal uint64  `json:"tx_total_bytes"`
}

type Status struct {
	HostInfo
	CPUPercent   float64    `json:"cpu_percent"`
	CPUPerCore   []float64  `json:"cpu_per_core"`
	MemUsed      uint64     `json:"mem_used_bytes"`
	MemAvailable uint64     `json:"mem_available_bytes"`
	MemPercent   float64    `json:"mem_percent"`
	SwapTotal    uint64     `json:"swap_total_bytes"`
	SwapUsed     uint64     `json:"swap_used_bytes"`
	Load1        float64    `json:"load1"`
	Load5        float64    `json:"load5"`
	Load15       float64    `json:"load15"`
	Procs        int        `json:"procs"`
	UptimeSec    float64    `json:"uptime_sec"`
	Disks        []DiskInfo `json:"disks"`
	Net          []NetRate  `json:"net"`
	// 流量统计（探针持久化累计，单位字节）：今日 / 本月 / 总量
	NetDay   TrafficUsage `json:"net_day"`
	NetMonth TrafficUsage `json:"net_month"`
	NetTotal TrafficUsage `json:"net_total"`
	Time     string       `json:"time"`
}

type Service struct {
	Unit        string `json:"unit"`
	Load        string `json:"load"`
	Active      string `json:"active"`
	Sub         string `json:"sub"`
	Description string `json:"description"`
}

type ServiceManager interface {
	List() ([]Service, error)
	Action(name, action string) (string, error)
}

// ---- HTTP 服务 ----

type apiServer struct {
	cfg *Config
	col *Collector
	svc ServiceManager
	rl  *rateLimiter
}

var actionRe = regexp.MustCompile(`^[A-Za-z0-9_.@-]{1,100}(\.service)?$`)
var validActions = map[string]bool{"start": true, "stop": true, "restart": true, "status": true}

func (s *apiServer) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/info", s.handleInfo)
	mux.HandleFunc("GET /api/v1/status", s.handleStatus)
	mux.HandleFunc("GET /api/v1/services", s.handleServices)
	mux.HandleFunc("POST /api/v1/services/action", s.handleServiceAction)
	var h http.Handler = requireToken(s.cfg.Token, mux)
	h = s.rl.middleware(h)
	h = recoverPanic(h)
	return h
}

func (s *apiServer) handleInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.col.Info())
}

func (s *apiServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.col.Snapshot())
}

func (s *apiServer) handleServices(w http.ResponseWriter, r *http.Request) {
	list, err := s.svc.List()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"services": list})
}

type serviceActionReq struct {
	Name   string `json:"name"`
	Action string `json:"action"`
}

func (s *apiServer) handleServiceAction(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.AllowServiceControl {
		writeErr(w, http.StatusForbidden, "service control is disabled in probe config")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	var req serviceActionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json body")
		return
	}
	// 服务名与动作白名单校验，exec 数组传参，杜绝命令注入
	if !actionRe.MatchString(req.Name) || !validActions[req.Action] {
		writeErr(w, http.StatusBadRequest, "invalid service name or action")
		return
	}
	out, err := s.svc.Action(req.Name, req.Action)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "output": out, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "output": out})
}

// ---- JSON 工具 ----

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	if err := enc.Encode(v); err != nil {
		log.Printf("write json: %v", err)
	}
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]any{"error": msg, "time": time.Now().Format(time.RFC3339)})
}
