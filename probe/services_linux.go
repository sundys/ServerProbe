//go:build linux

package main

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

type systemdManager struct{}

// List 通过 systemctl list-units 获取全部 service 单元状态。
func (systemdManager) List() ([]Service, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "systemctl", "list-units", "--type=service", "--all", "--no-legend", "--no-pager", "--plain").Output()
	if err != nil {
		return nil, fmt.Errorf("systemctl failed: %w", err)
	}
	var list []Service
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		f := strings.Fields(line)
		if len(f) < 4 {
			continue
		}
		s := Service{Unit: f[0], Load: f[1], Active: f[2], Sub: f[3]}
		if len(f) > 4 {
			s.Description = strings.Join(f[4:], " ")
		}
		list = append(list, s)
	}
	return list, nil
}

// Action 执行 systemctl start/stop/restart/status <name>。名称已在上层通过白名单正则校验。
func (systemdManager) Action(name, action string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "systemctl", action, name).CombinedOutput()
	return string(out), err
}
