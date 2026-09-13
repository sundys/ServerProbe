package main

import (
	"crypto/subtle"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// requireToken 校验 Authorization: Bearer <token>。
// scheme 名是公开信息，可按 RFC 7235 大小写不敏感比较；仅 Token 使用常量时间比较，避免计时侧信道。
func requireToken(token string, next http.Handler) http.Handler {
	const prefix = "Bearer "
	expected := []byte(token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if len(expected) == 0 || len(auth) <= len(prefix) || !strings.EqualFold(auth[:len(prefix)], prefix) {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		got := []byte(auth[len(prefix):])
		if subtle.ConstantTimeCompare(got, expected) != 1 {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, r)
	})
}

type bucket struct {
	minute int64
	count  int
}

// rateLimiter 按 IP 的固定窗口限流，防 Token 爆破与滥用。
type rateLimiter struct {
	mu     sync.Mutex
	limit  int
	counts map[string]*bucket
}

func newRateLimiter(perMinute int) *rateLimiter {
	if perMinute <= 0 {
		perMinute = 120
	}
	return &rateLimiter{limit: perMinute, counts: make(map[string]*bucket)}
}

func (r *rateLimiter) allow(ip string) bool {
	m := time.Now().Unix() / 60
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.counts) > 8192 { // 防止 IP 表无限膨胀
		r.counts = make(map[string]*bucket)
	}
	b, ok := r.counts[ip]
	if !ok || b.minute != m {
		r.counts[ip] = &bucket{minute: m, count: 1}
		return true
	}
	b.count++
	return b.count <= r.limit
}

func (r *rateLimiter) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		ip, _, err := net.SplitHostPort(req.RemoteAddr)
		if err != nil {
			ip = req.RemoteAddr
		}
		if !r.allow(ip) {
			writeErr(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
		next.ServeHTTP(w, req)
	})
}

// recoverPanic 兜底，保证单次请求异常不会击穿进程。
func recoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				writeErr(w, http.StatusInternalServerError, "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
