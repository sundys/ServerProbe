package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

const Version = "1.0.0"

func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "init":
		cmdInit(os.Args[2:])
	case "serve":
		cmdServe(os.Args[2:])
	case "show":
		cmdShow(os.Args[2:])
	case "version":
		fmt.Println("serverprobe", Version)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Print(`ServerProbe - lightweight server monitoring agent

Usage:
  serverprobe init [--dir DIR] [--listen ADDR] [--token TOKEN] [--insecure-tls] [--force]
  serverprobe serve [--config FILE]
  serverprobe show  [--dir DIR]
  serverprobe version
`)
}

// cmdInit 生成配置目录：config.json + 自签证书。已存在时幂等：仅按需更新 token/listen。
func cmdInit(args []string) {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	dir := fs.String("dir", defaultDir(), "config directory")
	listen := fs.String("listen", ":"+defaultPort, "listen address, e.g. :9822")
	tokenFlag := fs.String("token", "", "auth token (generate one if empty)")
	insecure := fs.Bool("insecure-tls", false, "disable TLS (strongly discouraged)")
	force := fs.Bool("force", false, "regenerate token and certificate")
	_ = fs.Parse(args)

	if err := os.MkdirAll(*dir, 0o700); err != nil {
		log.Fatalf("create dir: %v", err)
	}

	var cfg *Config
	if !*force {
		if c, err := loadConfig(configPath(*dir)); err == nil {
			cfg = c
		}
	}
	if cfg == nil {
		tok, err := genToken()
		if err != nil {
			log.Fatalf("generate token: %v", err)
		}
		cfg = &Config{Token: tok, TLS: true, RateLimitPerMin: 120, AllowServiceControl: true}
	}
	if *tokenFlag != "" {
		cfg.Token = *tokenFlag
	}
	cfg.Listen = *listen
	cfg.TLS = !*insecure
	cfg.CertFile = filepath.Join(*dir, "cert.pem")
	cfg.KeyFile = filepath.Join(*dir, "key.pem")
	if cfg.RateLimitPerMin <= 0 {
		cfg.RateLimitPerMin = 120
	}

	fingerprint := "-"
	if cfg.TLS {
		_, _, fp, err := ensureCert(*dir)
		if err != nil {
			log.Fatalf("generate certificate: %v", err)
		}
		fingerprint = fp
	}

	b, _ := json.MarshalIndent(cfg, "", "  ")
	if err := os.WriteFile(configPath(*dir), b, 0o600); err != nil {
		log.Fatalf("write config: %v", err)
	}

	fmt.Printf("config:       %s\n", configPath(*dir))
	fmt.Printf("listen:       %s\n", cfg.Listen)
	fmt.Printf("tls:          %v\n", cfg.TLS)
	fmt.Printf("token:        %s\n", cfg.Token)
	fmt.Printf("fingerprint:  %s\n", fingerprint)
}

// cmdShow 打印当前配置摘要（供安装脚本/运维取值）。
func cmdShow(args []string) {
	fs := flag.NewFlagSet("show", flag.ExitOnError)
	dir := fs.String("dir", defaultDir(), "config directory")
	_ = fs.Parse(args)

	cfg, err := loadConfig(configPath(*dir))
	if err != nil {
		log.Fatalf("load config: %v (run 'serverprobe init' first)", err)
	}
	fp := "-"
	if cfg.TLS {
		fp, err = certFingerprint(cfg.CertFile)
		if err != nil {
			fp = "-"
		}
	}
	fmt.Printf("listen:       %s\n", cfg.Listen)
	fmt.Printf("tls:          %v\n", cfg.TLS)
	fmt.Printf("token:        %s\n", cfg.Token)
	fmt.Printf("fingerprint:  %s\n", fp)
}

// cmdServe 启动 HTTP(S) 服务与采集循环。
func cmdServe(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	cfgFile := fs.String("config", configPath(defaultDir()), "config file path")
	_ = fs.Parse(args)

	cfg, err := loadConfig(*cfgFile)
	if err != nil {
		log.Fatalf("load config: %v (run 'serverprobe init' first)", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	col := NewCollector()
	col.Start(ctx)

	var svc ServiceManager = systemdManager{}
	srv := &apiServer{cfg: cfg, col: col, svc: svc, rl: newRateLimiter(cfg.RateLimitPerMin)}

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       90 * time.Second,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12},
	}

	errCh := make(chan error, 1)
	go func() {
		fmt.Printf("serverprobe %s listening on %s (tls=%v)\n", Version, cfg.Listen, cfg.TLS)
		if cfg.TLS {
			errCh <- httpSrv.ListenAndServeTLS(cfg.CertFile, cfg.KeyFile)
		} else {
			errCh <- httpSrv.ListenAndServe()
		}
	}()

	select {
	case err := <-errCh:
		if err != nil && err != http.ErrServerClosed {
			log.Fatalf("server: %v", err)
		}
	case <-ctx.Done():
		fmt.Println("shutting down...")
		shCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shCtx)
	}
}
