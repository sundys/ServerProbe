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
