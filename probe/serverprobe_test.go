package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCPUPercent(t *testing.T) {
	prev := cpuTimes{total: 1000, busy: 300}
	cur := cpuTimes{total: 2000, busy: 800}
	got := cpuPercent(cur, prev)
	if got < 49.9 || got > 50.1 {
		t.Fatalf("want ~50, got %v", got)
	}
	if cpuPercent(cur, cur) != 0 {
		t.Fatal("no delta should be 0")
	}
	if cpuPercent(cpuTimes{total: 100, busy: 200}, prev) != 0 {
		t.Fatal("negative delta should clamp to 0")
	}
}

func okHandler(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) }

func TestTokenAuth(t *testing.T) {
	h := requireToken("secret", http.HandlerFunc(okHandler))
	do := func(auth string) int {
		req := httptest.NewRequest("GET", "/api/v1/status", nil)
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	if code := do("Bearer secret"); code != 200 {
		t.Fatalf("valid token rejected: %d", code)
	}
	// RFC 7235：scheme 大小写不敏感
	if code := do("bearer secret"); code != 200 {
		t.Fatalf("lowercase scheme should pass: %d", code)
	}
	for _, bad := range []string{"Bearer Secret", "Bearer", "Bearer ", "Token secret", "Bearersecretx", ""} {
		if code := do(bad); code != 401 {
			t.Fatalf("token %q should be 401, got %d", bad, code)
		}
	}
}

func TestRateLimiter(t *testing.T) {
	rl := newRateLimiter(3)
	for i := 0; i < 3; i++ {
		if !rl.allow("1.2.3.4") {
			t.Fatalf("req %d should pass", i+1)
		}
	}
	if rl.allow("1.2.3.4") {
		t.Fatal("4th request should be limited")
	}
	if !rl.allow("5.6.7.8") {
		t.Fatal("other ip should pass")
	}
}

func TestActionValidation(t *testing.T) {
	valid := []string{"nginx.service", "nginx", "php-fpm@7.4.service", "user@1000.service"}
	for _, n := range valid {
		if !actionRe.MatchString(n) {
			t.Fatalf("%q should be valid", n)
		}
	}
	invalid := []string{"nginx;reboot", "nginx && reboot", "$(reboot)", "nginx `id`", "a b", "../etc/passwd", ""}
	for _, n := range invalid {
		if actionRe.MatchString(n) {
			t.Fatalf("%q should be invalid", n)
		}
	}
	for _, a := range []string{"start", "stop", "restart", "status"} {
		if !validActions[a] {
			t.Fatalf("action %q should be valid", a)
		}
	}
	if validActions["reboot"] || validActions["kill"] {
		t.Fatal("dangerous actions must not be allowed")
	}
}

// systemctl list-units --no-legend --plain 的典型输出：描述可含空格，
// 非 systemd 环境还可能夹杂错误行/空行，解析必须跳过而不是错位。
func TestParseUnitList(t *testing.T) {
	out := `
nginx.service         loaded    active   running A high performance web server
mysql.service         loaded    failed   failed  MySQL Community Server
auditd.service        not-found inactive dead    auditd.service
php-fpm@8.2.service   loaded    active   running The PHP 8.2 FastCGI Process Manager
broken line
`
	list := parseUnitList(out)
	if len(list) != 4 {
		t.Fatalf("want 4 services, got %d: %+v", len(list), list)
	}
	if list[0].Unit != "nginx.service" || list[0].Active != "active" || list[0].Sub != "running" {
		t.Fatalf("first service parsed wrong: %+v", list[0])
	}
	if list[0].Description != "A high performance web server" {
		t.Fatalf("description should keep spaces: %q", list[0].Description)
	}
	if list[1].Active != "failed" || list[2].Load != "not-found" {
		t.Fatalf("failed/not-found entries parsed wrong: %+v / %+v", list[1], list[2])
	}
	if list[3].Unit != "php-fpm@8.2.service" {
		t.Fatalf("template unit parsed wrong: %+v", list[3])
	}
	if parseUnitList("") != nil {
		t.Fatal("empty output should yield nil")
	}
}

func TestFingerprintFormat(t *testing.T) {
	fp := fingerprintDER(make([]byte, 10))
	if len(fp) != 3*32-1 {
		t.Fatalf("bad fingerprint length: %s", fp)
	}
	if fp != strings.ToUpper(fp) {
		t.Fatalf("fingerprint should be uppercase: %s", fp)
	}
	if !strings.Contains(fp, ":") {
		t.Fatalf("fingerprint should be colon separated: %s", fp)
	}
}
