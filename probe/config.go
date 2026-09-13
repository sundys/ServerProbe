package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

const defaultPort = "9822"

// Config 探针运行配置，由 serverprobe init 生成，权限 0600。
type Config struct {
	Listen              string `json:"listen"`
	Token               string `json:"token"`
	TLS                 bool   `json:"tls"`
	CertFile            string `json:"cert_file"`
	KeyFile             string `json:"key_file"`
	AllowServiceControl bool   `json:"allow_service_control"`
	RateLimitPerMin     int    `json:"rate_limit_per_min"`
}

func defaultDir() string {
	if runtime.GOOS == "windows" {
		return `.\serverprobe`
	}
	return "/etc/serverprobe"
}

func configPath(dir string) string { return filepath.Join(dir, "config.json") }

func loadConfig(path string) (*Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if c.Token == "" {
		return nil, errors.New("config.token is empty")
	}
	if c.Listen == "" {
		c.Listen = ":" + defaultPort
	}
	if c.RateLimitPerMin <= 0 {
		c.RateLimitPerMin = 120
	}
	return &c, nil
}

func genToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
