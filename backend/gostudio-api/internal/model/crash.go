// Package model 定义崩溃上报相关的 GORM 模型。
//
// 去重单位是 error_types（"错误类型"）：service 层对每次崩溃计算指纹，
// 同指纹的上报只在类型上累加 occurrence_count，不重复建类型。
package model

import "time"

const (
	StatusOpen     = "open"
	StatusResolved = "resolved"
)

type ErrorType struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	Fingerprint     string    `gorm:"uniqueIndex;size:64" json:"fingerprint"`
	Title           string    `gorm:"size:256" json:"title"`
	OccurrenceCount int       `json:"occurrence_count"`
	Status          string    `gorm:"size:16;default:open;index" json:"status"`
	SampleStack     string    `json:"sample_stack"`
	FirstSeenAt     time.Time `json:"first_seen_at"`
	LastSeenAt      time.Time `json:"last_seen_at"`
}

func (ErrorType) TableName() string { return "error_types" }

type ErrorReport struct {
	ID                 uint      `gorm:"primaryKey" json:"id"`
	ErrorTypeID        uint      `gorm:"index" json:"error_type_id"`
	DeviceID           string    `gorm:"index;size:64" json:"device_id"`
	DeviceBrand        string    `gorm:"size:64" json:"device_brand"`
	DeviceManufacturer string    `gorm:"size:64" json:"device_manufacturer"`
	DeviceModel        string    `gorm:"size:128" json:"device_model"`
	AndroidVersion     string    `gorm:"size:32" json:"android_version"`
	AppVersion         string    `gorm:"size:32" json:"app_version"`
	IP                 string    `gorm:"size:64" json:"ip"`
	// Contact 用户自愿留下的联系方式（邮箱/QQ 等）。
	Contact   string `gorm:"size:128" json:"contact"`
	Comment   string `gorm:"size:1024" json:"comment"`
	CrashLog  string `json:"crash_log"`
	CrashStack string `json:"crash_stack"`
	CreatedAt time.Time `gorm:"index" json:"created_at"`
}

func (ErrorReport) TableName() string { return "error_reports" }
