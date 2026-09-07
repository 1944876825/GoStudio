package router

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jmwl/gostudio/gostudio-api/internal/config"
	"github.com/jmwl/gostudio/gostudio-api/internal/database"
	"github.com/jmwl/gostudio/gostudio-api/internal/translation"
)

const testAPIKey = "test-key"

func newTestApp(t *testing.T) *App {
	t.Helper()
	db, err := database.OpenMemory()
	if err != nil {
		t.Fatalf("open memory db: %v", err)
	}
	cfg := config.Config{
		Admin: config.AdminConfig{
			Username:   "admin",
			Password:   "admin-pass",
			JWTSecret:  "0123456789abcdef",
			SessionTTL: time.Hour,
		},
		Crash: config.CrashConfig{
			APIKeys:           []string{testAPIKey},
			MaxReportBytes:    256 * 1024,
			DeviceHourlyLimit: 10,
		},
		Translation: config.TranslationConfig{
			Enabled:            true,
			Provider:           translation.ProviderMock,
			APIKeys:            []string{testAPIKey},
			MaxTextBytes:       1024,
			MaxCacheEntries:    16,
			MaxConcurrentJobs:  2,
		},
	}
	app, err := NewApp(cfg, db, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatalf("new app: %v", err)
	}
	return app
}

func newTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(newTestApp(t).Engine())
}

// ===== 翻译（移植自旧 translation-api 的 server_test.go） =====

func TestTranslationRequiresAPIKey(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	response, err := http.Post(server.URL+"/translation/v1/translate", "application/json",
		bytes.NewBufferString(`{"text":"Add returns the sum.","target_language":"zh-cn"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusUnauthorized)
	}
}

func TestTranslationAndCache(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	body, _ := json.Marshal(translation.Request{
		Text:           "Add returns the sum.",
		TargetLanguage: "zh-cn",
		Kind:           "gopls-hover",
	})
	call := func() translation.Response {
		t.Helper()
		request, err := http.NewRequest(http.MethodPost, server.URL+"/translation/v1/translate", bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-GoStudio-Translation-Key", testAPIKey)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			t.Fatalf("status = %d", response.StatusCode)
		}
		var decoded translation.Response
		if err := json.NewDecoder(response.Body).Decode(&decoded); err != nil {
			t.Fatal(err)
		}
		return decoded
	}
	first := call()
	if first.Cached {
		t.Fatal("first response should not be cached")
	}
	second := call()
	if !second.Cached || second.TranslatedText != first.TranslatedText {
		t.Fatalf("second response should use cache: first=%q second=%q", first.TranslatedText, second.TranslatedText)
	}
}

// ===== 崩溃上报 =====

func submitReport(t *testing.T, server *httptest.Server, apiKey, deviceID string) (int, map[string]any) {
	t.Helper()
	payload, _ := json.Marshal(map[string]any{
		"device_id":           deviceID,
		"device_brand":        "Xiaomi",
		"device_manufacturer": "Xiaomi",
		"device_model":        "23127PN04C",
		"android_version":     "15",
		"app_version":         "1.0.7",
		"contact":             "dev@example.com",
		"crash_log":           "IllegalStateException: editor state is detached",
		"crash_stack": "java.lang.IllegalStateException: editor state is detached\n" +
			"\tat com.jmwl.gostudio.editor.editor_activity.onResume(editor_activity.kt:120)\n",
	})
	request, err := http.NewRequest(http.MethodPost, server.URL+"/crash/v1/reports", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		request.Header.Set("X-GoStudio-Backend-Key", apiKey)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var decoded map[string]any
	_ = json.NewDecoder(response.Body).Decode(&decoded)
	return response.StatusCode, decoded
}

func TestCrashReportRequiresAPIKey(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	status, _ := submitReport(t, server, "", "device-a")
	if status != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", status, http.StatusUnauthorized)
	}
}

func TestCrashReportSubmitAndDedupe(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	status, first := submitReport(t, server, testAPIKey, "device-a")
	if status != http.StatusOK {
		t.Fatalf("status = %d body=%v", status, first)
	}
	if first["first_report"] != true {
		t.Fatalf("first submit should be first_report, got %v", first)
	}

	// 不同设备、行号不同的同一处崩溃 → 同一错误类型，计数 +1。
	status, second := submitReport(t, server, testAPIKey, "device-b")
	if status != http.StatusOK {
		t.Fatalf("status = %d body=%v", status, second)
	}
	if second["first_report"] != false {
		t.Fatalf("duplicate should not be first_report, got %v", second)
	}
	if second["error_type_id"] != first["error_type_id"] {
		t.Fatalf("error_type_id drifted: %v vs %v", second["error_type_id"], first["error_type_id"])
	}
	if int(second["occurrence_count"].(float64)) != 2 {
		t.Fatalf("occurrence_count = %v, want 2", second["occurrence_count"])
	}
}

func TestCrashDeviceHistory(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	if status, _ := submitReport(t, server, testAPIKey, "device-a"); status != http.StatusOK {
		t.Fatal("submit failed")
	}

	request, _ := http.NewRequest(http.MethodGet, server.URL+"/crash/v1/reports?device_id=device-a", nil)
	request.Header.Set("X-GoStudio-Backend-Key", testAPIKey)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	var decoded struct {
		Total   int `json:"total"`
		Reports []struct {
			Title  string `json:"title"`
			Status string `json:"status"`
		} `json:"reports"`
	}
	if err := json.NewDecoder(response.Body).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Total != 1 || len(decoded.Reports) != 1 {
		t.Fatalf("total=%d len=%d, want 1", decoded.Total, len(decoded.Reports))
	}
	if !strings.HasPrefix(decoded.Reports[0].Title, "IllegalStateException") {
		t.Fatalf("title = %q", decoded.Reports[0].Title)
	}
	if decoded.Reports[0].Status != "open" {
		t.Fatalf("status = %q, want open", decoded.Reports[0].Status)
	}
}

// 单设备小时窗限流。
func TestCrashReportRateLimit(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	limited := false
	for i := 0; i < 12; i++ {
		status, _ := submitReport(t, server, testAPIKey, "device-limit")
		if status == http.StatusTooManyRequests {
			limited = true
			break
		}
		if status != http.StatusOK {
			t.Fatalf("unexpected status %d at %d", status, i)
		}
	}
	if !limited {
		t.Fatal("device should hit the hourly rate limit")
	}
}

// ===== 管理后台 =====

func adminLogin(t *testing.T, server *httptest.Server) string {
	t.Helper()
	payload, _ := json.Marshal(map[string]string{"username": "admin", "password": "admin-pass"})
	response, err := http.Post(server.URL+"/admin/api/login", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d", response.StatusCode)
	}
	var decoded struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(response.Body).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
	return decoded.Token
}

func TestAdminFlow(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	// 未登录访问被拒。
	response, err := http.Get(server.URL + "/admin/api/error-types")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d", response.StatusCode)
	}

	// 错误密码。
	payload, _ := json.Marshal(map[string]string{"username": "admin", "password": "bad"})
	bad, _ := http.Post(server.URL+"/admin/api/login", "application/json", bytes.NewReader(payload))
	bad.Body.Close()
	if bad.StatusCode != http.StatusUnauthorized {
		t.Fatalf("bad login status = %d", bad.StatusCode)
	}

	if status, _ := submitReport(t, server, testAPIKey, "device-a"); status != http.StatusOK {
		t.Fatal("seed report failed")
	}

	token := adminLogin(t, server)

	// 列表。
	request, _ := http.NewRequest(http.MethodGet, server.URL+"/admin/api/error-types", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	list, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer list.Body.Close()
	if list.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", list.StatusCode)
	}
	var listBody struct {
		Total int `json:"total"`
		Items []struct {
			ID          uint `json:"id"`
			DeviceCount int  `json:"device_count"`
		} `json:"items"`
	}
	if err := json.NewDecoder(list.Body).Decode(&listBody); err != nil {
		t.Fatal(err)
	}
	if listBody.Total != 1 || len(listBody.Items) != 1 || listBody.Items[0].DeviceCount != 1 {
		t.Fatalf("unexpected list body: %+v", listBody)
	}

	// 详情 + 状态流转。
	id := listBody.Items[0].ID
	detailRequest, _ := http.NewRequest(http.MethodGet, server.URL+"/admin/api/error-types/"+itoa(id), nil)
	detailRequest.Header.Set("Authorization", "Bearer "+token)
	detail, err := http.DefaultClient.Do(detailRequest)
	if err != nil {
		t.Fatal(err)
	}
	var detailBody struct {
		ErrorType struct {
			Status string `json:"status"`
		} `json:"error_type"`
		Reports []map[string]any `json:"reports"`
	}
	_ = json.NewDecoder(detail.Body).Decode(&detailBody)
	detail.Body.Close()
	if len(detailBody.Reports) != 1 {
		t.Fatalf("reports = %d, want 1", len(detailBody.Reports))
	}

	patchPayload, _ := json.Marshal(map[string]string{"status": "resolved"})
	patchRequest, _ := http.NewRequest(http.MethodPatch, server.URL+"/admin/api/error-types/"+itoa(id)+"/status", bytes.NewReader(patchPayload))
	patchRequest.Header.Set("Authorization", "Bearer "+token)
	patchRequest.Header.Set("Content-Type", "application/json")
	patch, err := http.DefaultClient.Do(patchRequest)
	if err != nil {
		t.Fatal(err)
	}
	patch.Body.Close()
	if patch.StatusCode != http.StatusOK {
		t.Fatalf("patch status = %d", patch.StatusCode)
	}
}

func TestAdminStaticPage(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	response, err := http.Get(server.URL + "/admin/")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	page, _ := io.ReadAll(response.Body)
	if !strings.Contains(string(page), "错误反馈后台") {
		t.Fatal("admin page should render the dashboard shell")
	}
}

func TestHealthz(t *testing.T) {
	server := newTestServer(t)
	defer server.Close()

	response, err := http.Get(server.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
}

func itoa(value uint) string {
	if value == 0 {
		return "0"
	}
	var digits []byte
	for value > 0 {
		digits = append([]byte{byte('0' + value%10)}, digits...)
		value /= 10
	}
	return string(digits)
}
