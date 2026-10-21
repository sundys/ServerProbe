package main

import "strings"

// parseUnitList 解析 systemctl list-units --no-legend --plain 的表格输出。
// 列：UNIT LOAD ACTIVE SUB DESCRIPTION，其中 DESCRIPTION 可含空格。
func parseUnitList(out string) []Service {
	var list []Service
	for _, line := range strings.Split(out, "\n") {
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
	return list
}
