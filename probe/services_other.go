//go:build !linux

package main

import "errors"

// 非 Linux 平台返回演示服务列表，便于 App 开发联调。
type systemdManager struct{}

func (systemdManager) List() ([]Service, error) {
	return []Service{
		{Unit: "nginx.service", Load: "loaded", Active: "active", Sub: "running", Description: "A high performance web server"},
		{Unit: "sshd.service", Load: "loaded", Active: "active", Sub: "running", Description: "OpenBSD Secure Shell server"},
		{Unit: "docker.service", Load: "loaded", Active: "active", Sub: "running", Description: "Docker Application Container Engine"},
		{Unit: "cron.service", Load: "loaded", Active: "active", Sub: "running", Description: "Regular background program processing"},
		{Unit: "mysql.service", Load: "loaded", Active: "failed", Sub: "failed", Description: "MySQL Community Server"},
	}, nil
}

func (systemdManager) Action(name, action string) (string, error) {
	return "", errors.New("service control is only supported on Linux")
}
