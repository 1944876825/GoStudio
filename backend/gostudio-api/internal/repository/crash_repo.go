// Package repository 封装崩溃数据的 GORM 查询，service 层不直接碰 DB。
package repository

import (
	"errors"
	"strings"
	"time"

	"gorm.io/gorm"

	"github.com/jmwl/gostudio/gostudio-api/internal/model"
)

var ErrNotFound = errors.New("record not found")

// TypeWithDeviceCount 列表页用：类型 + 去重设备数（查询后另行聚合，非表列）。
type TypeWithDeviceCount struct {
	model.ErrorType
	DeviceCount int `json:"device_count" gorm:"-"`
}

// DeviceReport 设备视角的历史记录：上报 + 所属类型的当前状态。
type DeviceReport struct {
	model.ErrorReport
	TypeStatus string `json:"type_status"`
}

type CrashRepo struct {
	db *gorm.DB
}

func NewCrashRepo(db *gorm.DB) *CrashRepo {
	return &CrashRepo{db: db}
}

func (r *CrashRepo) FindTypeByFingerprint(fingerprint string) (*model.ErrorType, error) {
	var record model.ErrorType
	err := r.db.Where("fingerprint = ?", fingerprint).First(&record).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &record, nil
}

func (r *CrashRepo) CreateType(record *model.ErrorType) error {
	return r.db.Create(record).Error
}

// TouchType 同类型再次上报：计数 +1 并刷新 last_seen_at。
func (r *CrashRepo) TouchType(id uint) error {
	return r.db.Model(&model.ErrorType{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"occurrence_count": gorm.Expr("occurrence_count + 1"),
			"last_seen_at":     time.Now().UTC(),
		}).Error
}

func (r *CrashRepo) CreateReport(record *model.ErrorReport) error {
	return r.db.Create(record).Error
}

// ListTypes 分页列出错误类型；q 对 title/fingerprint 模糊匹配，status 为空时不过滤。
func (r *CrashRepo) ListTypes(status, q string, page, pageSize int) ([]TypeWithDeviceCount, int64, error) {
	query := r.db.Model(&model.ErrorType{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if keyword := strings.TrimSpace(q); keyword != "" {
		like := "%" + keyword + "%"
		query = query.Where("title LIKE ? OR fingerprint LIKE ?", like, like)
	}
	var total int64
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	var items []TypeWithDeviceCount
	err := query.
		Order("last_seen_at DESC").
		Offset((page - 1) * pageSize).Limit(pageSize).
		Find(&items).Error
	if err != nil {
		return nil, 0, err
	}
	// 设备数单独聚合，避免 join 拉全表。
	for i := range items {
		var count int64
		if err := r.db.Model(&model.ErrorReport{}).
			Where("error_type_id = ?", items[i].ID).
			Distinct("device_id").
			Count(&count).Error; err != nil {
			return nil, 0, err
		}
		items[i].DeviceCount = int(count)
	}
	return items, total, nil
}

func (r *CrashRepo) GetType(id uint) (*model.ErrorType, error) {
	var record model.ErrorType
	err := r.db.First(&record, id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &record, nil
}

func (r *CrashRepo) ListReportsByType(typeID uint, page, pageSize int) ([]model.ErrorReport, int64, error) {
	var total int64
	if err := r.db.Model(&model.ErrorReport{}).Where("error_type_id = ?", typeID).Count(&total).Error; err != nil {
		return nil, 0, err
	}
	var items []model.ErrorReport
	err := r.db.Where("error_type_id = ?", typeID).
		Order("created_at DESC").
		Offset((page - 1) * pageSize).Limit(pageSize).
		Find(&items).Error
	if err != nil {
		return nil, 0, err
	}
	return items, total, nil
}

// ListReportsByDevice 设备的历史记录，按时间倒序，附带类型状态。
func (r *CrashRepo) ListReportsByDevice(deviceID string, page, pageSize int) ([]DeviceReport, int64, error) {
	var total int64
	if err := r.db.Model(&model.ErrorReport{}).Where("device_id = ?", deviceID).Count(&total).Error; err != nil {
		return nil, 0, err
	}
	var reports []model.ErrorReport
	err := r.db.Where("device_id = ?", deviceID).
		Order("created_at DESC").
		Offset((page - 1) * pageSize).Limit(pageSize).
		Find(&reports).Error
	if err != nil {
		return nil, 0, err
	}
	if len(reports) == 0 {
		return []DeviceReport{}, total, nil
	}
	ids := make([]uint, 0, len(reports))
	for _, item := range reports {
		ids = append(ids, item.ErrorTypeID)
	}
	var types []model.ErrorType
	if err := r.db.Select("id", "status").Where("id IN ?", ids).Find(&types).Error; err != nil {
		return nil, 0, err
	}
	statusByID := make(map[uint]string, len(types))
	for _, item := range types {
		statusByID[item.ID] = item.Status
	}
	items := make([]DeviceReport, 0, len(reports))
	for _, report := range reports {
		items = append(items, DeviceReport{
			ErrorReport: report,
			TypeStatus:  statusByID[report.ErrorTypeID],
		})
	}
	return items, total, nil
}

func (r *CrashRepo) UpdateTypeStatus(id uint, status string) error {
	result := r.db.Model(&model.ErrorType{}).Where("id = ?", id).Update("status", status)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

// CountDeviceReportsSince 统计某设备时间窗内的上报数，限流用。
func (r *CrashRepo) CountDeviceReportsSince(deviceID string, since time.Time) (int64, error) {
	var count int64
	err := r.db.Model(&model.ErrorReport{}).
		Where("device_id = ? AND created_at >= ?", deviceID, since).
		Count(&count).Error
	return count, err
}

// Stats 后台概览统计。
func (r *CrashRepo) Stats() (map[string]int64, error) {
	var totalTypes, openTypes, totalReports, todayReports int64
	if err := r.db.Model(&model.ErrorType{}).Count(&totalTypes).Error; err != nil {
		return nil, err
	}
	if err := r.db.Model(&model.ErrorType{}).Where("status = ?", model.StatusOpen).Count(&openTypes).Error; err != nil {
		return nil, err
	}
	if err := r.db.Model(&model.ErrorReport{}).Count(&totalReports).Error; err != nil {
		return nil, err
	}
	if err := r.db.Model(&model.ErrorReport{}).
		Where("created_at >= ?", time.Now().UTC().Add(-24*time.Hour)).
		Count(&todayReports).Error; err != nil {
		return nil, err
	}
	return map[string]int64{
		"total_types":   totalTypes,
		"open_types":    openTypes,
		"total_reports": totalReports,
		"today_reports": todayReports,
	}, nil
}
