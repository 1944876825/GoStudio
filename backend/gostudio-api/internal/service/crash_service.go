// Package service 承载业务逻辑：崩溃指纹计算、类型去重聚合、历史查询。
package service

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strings"
	"time"

	"github.com/jmwl/gostudio/gostudio-api/internal/model"
	"github.com/jmwl/gostudio/gostudio-api/internal/repository"
)

// appPackagePrefix 指纹优先采用应用自身代码帧，过滤掉框架噪声。
const appPackagePrefix = "com.jmwl.gostudio"

// framePattern 匹配堆栈帧 "at com.jmwl.gostudio.foo(editor.kt:123)"。
var framePattern = regexp.MustCompile(`^\s*at\s+(\S+)`)

// linePattern 去掉帧尾的行号（editor.kt:123 → editor.kt），同一处崩溃在不同
// 构建下行号会漂移，指纹不能受它影响。
var linePattern = regexp.MustCompile(`:\d+\)?\s*$`)

// fingerprintFrames 参与指纹的帧数。
const fingerprintFrames = 3

// titleLimit 列表展示的标题长度。
const titleLimit = 160

type SubmitInput struct {
	DeviceID           string
	DeviceBrand        string
	DeviceManufacturer string
	DeviceModel        string
	AndroidVersion     string
	AppVersion         string
	IP                 string
	Contact            string
	Comment            string
	CrashLog           string
	CrashStack         string
}

type SubmitResult struct {
	Report   *model.ErrorReport
	Type     *model.ErrorType
	FirstReport bool
}

type CrashService struct {
	repo *repository.CrashRepo
}

func NewCrashService(repo *repository.CrashRepo) *CrashService {
	return &CrashService{repo: repo}
}

// ComputeFingerprint 由崩溃日志与堆栈计算去重指纹：
// 异常类名（crash_log 首行冒号前）+ 前 3 个应用帧（去行号）。
// 完全没有 "at" 帧时退化为异常类名 + 堆栈前几行。
func ComputeFingerprint(crashLog, crashStack string) string {
	firstLine := firstNonEmptyLine(crashLog)
	exceptionClass := firstLine
	if idx := strings.Index(exceptionClass, ":"); idx > 0 {
		exceptionClass = exceptionClass[:idx]
	}
	exceptionClass = strings.TrimSpace(exceptionClass)

	frames := collectFrames(crashStack, fingerprintFrames)
	material := exceptionClass + "\x00" + strings.Join(frames, "\x00")
	sum := sha256.Sum256([]byte(material))
	return hex.EncodeToString(sum[:])
}

func collectFrames(stack string, limit int) []string {
	var appFrames, anyFrames []string
	for _, line := range strings.Split(stack, "\n") {
		match := framePattern.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		frame := linePattern.ReplaceAllString(strings.TrimSpace(match[1]), ")")
		anyFrames = append(anyFrames, frame)
		if strings.HasPrefix(frame, appPackagePrefix) {
			appFrames = append(appFrames, frame)
			if len(appFrames) >= limit {
				break
			}
		}
	}
	if len(appFrames) > 0 {
		return appFrames
	}
	if len(anyFrames) > limit {
		anyFrames = anyFrames[:limit]
	}
	return anyFrames
}

func firstNonEmptyLine(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			return trimmed
		}
	}
	return "UnknownError"
}

// BuildTitle 列表标题：crash_log 首行截断。
func BuildTitle(crashLog string) string {
	title := firstNonEmptyLine(crashLog)
	if len(title) > titleLimit {
		title = title[:titleLimit] + "…"
	}
	return title
}

// Submit 提交一次崩溃：同指纹只累加计数，不重复建错误类型。
func (s *CrashService) Submit(input SubmitInput) (*SubmitResult, error) {
	fingerprint := ComputeFingerprint(input.CrashLog, input.CrashStack)
	now := time.Now().UTC()

	existing, err := s.repo.FindTypeByFingerprint(fingerprint)
	if err != nil && err != repository.ErrNotFound {
		return nil, err
	}

	var errorType *model.ErrorType
	firstReport := existing == nil
	if firstReport {
		errorType = &model.ErrorType{
			Fingerprint:     fingerprint,
			Title:           BuildTitle(input.CrashLog),
			OccurrenceCount: 1,
			Status:          model.StatusOpen,
			SampleStack:     input.CrashStack,
			FirstSeenAt:     now,
			LastSeenAt:      now,
		}
		if err := s.repo.CreateType(errorType); err != nil {
			return nil, err
		}
	} else {
		errorType = existing
		if err := s.repo.TouchType(errorType.ID); err != nil {
			return nil, err
		}
		errorType.OccurrenceCount++
		errorType.LastSeenAt = now
	}

	report := &model.ErrorReport{
		ErrorTypeID:        errorType.ID,
		DeviceID:           input.DeviceID,
		DeviceBrand:        input.DeviceBrand,
		DeviceManufacturer: input.DeviceManufacturer,
		DeviceModel:        input.DeviceModel,
		AndroidVersion:     input.AndroidVersion,
		AppVersion:         input.AppVersion,
		IP:                 input.IP,
		Contact:            input.Contact,
		Comment:            input.Comment,
		CrashLog:           input.CrashLog,
		CrashStack:         input.CrashStack,
		CreatedAt:          now,
	}
	if err := s.repo.CreateReport(report); err != nil {
		return nil, err
	}
	return &SubmitResult{Report: report, Type: errorType, FirstReport: firstReport}, nil
}

func (s *CrashService) ListTypes(status, q string, page, pageSize int) ([]repository.TypeWithDeviceCount, int64, error) {
	return s.repo.ListTypes(status, q, page, pageSize)
}

func (s *CrashService) TypeDetail(id uint) (*model.ErrorType, error) {
	return s.repo.GetType(id)
}

func (s *CrashService) TypeReports(id uint, page, pageSize int) ([]model.ErrorReport, int64, error) {
	return s.repo.ListReportsByType(id, page, pageSize)
}

func (s *CrashService) UpdateTypeStatus(id uint, status string) error {
	return s.repo.UpdateTypeStatus(id, status)
}

func (s *CrashService) DeviceReports(deviceID string, page, pageSize int) ([]repository.DeviceReport, int64, error) {
	return s.repo.ListReportsByDevice(deviceID, page, pageSize)
}

func (s *CrashService) Stats() (map[string]int64, error) {
	return s.repo.Stats()
}
