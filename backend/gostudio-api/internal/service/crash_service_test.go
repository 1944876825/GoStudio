package service

import (
	"testing"

	"github.com/jmwl/gostudio/gostudio-api/internal/database"
	"github.com/jmwl/gostudio/gostudio-api/internal/repository"
)

func newTestService(t *testing.T) *CrashService {
	t.Helper()
	db, err := database.OpenMemory()
	if err != nil {
		t.Fatalf("open memory db: %v", err)
	}
	return NewCrashService(repository.NewCrashRepo(db))
}

func sampleInput(deviceID, line string) SubmitInput {
	return SubmitInput{
		DeviceID:       deviceID,
		DeviceBrand:    "Xiaomi",
		DeviceModel:    "23127PN04C",
		AndroidVersion: "15",
		AppVersion:     "1.0.7",
		IP:             "1.2.3.4",
		CrashLog:       "IllegalStateException: editor state is detached",
		CrashStack: "java.lang.IllegalStateException: editor state is detached\n" +
			"\tat com.jmwl.gostudio.editor.editor_activity.onResume(editor_activity.kt:" + line + ")\n" +
			"\tat android.app.Activity.performResume(Activity.java:8912)\n" +
			"\tat com.jmwl.gostudio.core.runner.go_runner.start(go_runner.kt:" + line + ")\n",
	}
}

// 同类崩溃（行号不同）只应聚合为一个错误类型。
func TestSubmitDeduplicatesByFingerprint(t *testing.T) {
	svc := newTestService(t)

	first, err := svc.Submit(sampleInput("device-a", "120"))
	if err != nil {
		t.Fatalf("first submit: %v", err)
	}
	if !first.FirstReport {
		t.Fatal("first submit should be first report")
	}

	second, err := svc.Submit(sampleInput("device-b", "999"))
	if err != nil {
		t.Fatalf("second submit: %v", err)
	}
	if second.FirstReport {
		t.Fatal("same fingerprint must not create a new error type")
	}
	if second.Type.ID != first.Type.ID {
		t.Fatalf("type id mismatch: %d vs %d", second.Type.ID, first.Type.ID)
	}
	if second.Type.OccurrenceCount != 2 {
		t.Fatalf("occurrence count = %d, want 2", second.Type.OccurrenceCount)
	}

	// 不同异常 → 新类型。
	other, err := svc.Submit(SubmitInput{
		DeviceID:  "device-a",
		CrashLog:  "NullPointerException: editor view is null",
		CrashStack: "java.lang.NullPointerException\n\tat com.jmwl.gostudio.editor.editor_activity.onResume(editor_activity.kt:120)\n",
	})
	if err != nil {
		t.Fatalf("other submit: %v", err)
	}
	if other.Type.ID == first.Type.ID {
		t.Fatal("different exception must produce a different error type")
	}

	types, total, err := svc.ListTypes("", "", 1, 20)
	if err != nil {
		t.Fatalf("list types: %v", err)
	}
	if total != 2 || len(types) != 2 {
		t.Fatalf("total=%d len=%d, want 2 types", total, len(types))
	}
}

// 无应用帧时退化为任意前几帧，指纹仍应稳定。
// 分组规则：异常类名 + 帧（去行号）；同类同位置的 message 差异归为同一错误类型。
func TestFingerprintFallsBackWithoutAppFrames(t *testing.T) {
	stackA := "java.lang.Error: boom\n\tat java.base Foo.bar(Foo.java:1)\n\tat java.base Baz.qux(Baz.java:2)\n"
	a := ComputeFingerprint("java.lang.Error: boom", stackA)
	b := ComputeFingerprint("java.lang.Error: boom", "java.lang.Error: boom\n\tat java.base Foo.bar(Foo.java:99)\n\tat java.base Baz.qux(Baz.java:2)\n")
	if a != b {
		t.Fatal("line numbers should not affect fingerprint")
	}
	sameClassOtherMessage := ComputeFingerprint("java.lang.Error: other", stackA)
	if a != sameClassOtherMessage {
		t.Fatal("same class at same frames should group into one error type")
	}
	otherClass := ComputeFingerprint("java.lang.RuntimeError: boom", stackA)
	if a == otherClass {
		t.Fatal("different exception class should change fingerprint")
	}
}

// 设备历史查询应附带错误类型的当前状态。
func TestDeviceReportsIncludeTypeStatus(t *testing.T) {
	svc := newTestService(t)
	if _, err := svc.Submit(sampleInput("device-a", "120")); err != nil {
		t.Fatalf("submit: %v", err)
	}
	reports, total, err := svc.DeviceReports("device-a", 1, 20)
	if err != nil {
		t.Fatalf("device reports: %v", err)
	}
	if total != 1 || len(reports) != 1 {
		t.Fatalf("total=%d len=%d, want 1", total, len(reports))
	}
	if reports[0].TypeStatus != "open" {
		t.Fatalf("type status = %q, want open", reports[0].TypeStatus)
	}
	if err := svc.UpdateTypeStatus(reports[0].ErrorTypeID, "resolved"); err != nil {
		t.Fatalf("update status: %v", err)
	}
	reports, _, err = svc.DeviceReports("device-a", 1, 20)
	if err != nil || reports[0].TypeStatus != "resolved" {
		t.Fatalf("after resolve, status = %q err=%v", reports[0].TypeStatus, err)
	}
}
