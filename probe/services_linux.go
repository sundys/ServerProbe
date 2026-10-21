//go:build linux

package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

type systemdManager struct{}

// List 通过 systemctl list-units 获取全部 service 单元状态。
func (systemdManager) List() ([]Service, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "systemctl", "list-units", "--type=service", "--all", "--no-legend", "--no-pager", "--plain")
	// 固定为英文输出，避免不同 locale 影响列内容
	cmd.Env = append(os.Environ(), "LC_ALL=C", "LANG=C")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		// 把 systemctl 的真实原因带回 App：非 systemd 主机 / 容器 / 权限不足
		// 的报错都在 stderr 里，只返回 "exit status 1" 无法定位。
		detail := strings.TrimSpace(stderr.String())
		if detail == "" {
			detail = err.Error()
		}
		return nil, fmt.Errorf("systemctl list-units failed: %s", detail)
	}
	return parseUnitList(string(out)), nil
}

// Action 执行 systemctl start/stop/restart/status <name>。名称已在上层通过白名单正则校验。
func (systemdManager) Action(name, action string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "systemctl", action, name).CombinedOutput()
	return string(out), err
}
