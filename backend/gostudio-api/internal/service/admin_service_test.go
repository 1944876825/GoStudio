package service

import (
	"testing"
	"time"
)

func TestAdminLoginAndVerify(t *testing.T) {
	admins := NewAdminService("admin", "secret-pass", "0123456789abcdef", time.Hour)

	if _, _, err := admins.Login("admin", "wrong"); err == nil {
		t.Fatal("wrong password must fail")
	}
	if _, _, err := admins.Login("root", "secret-pass"); err == nil {
		t.Fatal("wrong username must fail")
	}
	token, expiresAt, err := admins.Login("admin", "secret-pass")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if time.Until(expiresAt) <= 0 {
		t.Fatal("token should expire in the future")
	}
	subject, err := admins.Verify(token)
	if err != nil || subject != "admin" {
		t.Fatalf("verify = %q, %v; want admin, nil", subject, err)
	}
	if _, err := admins.Verify(token + "x"); err == nil {
		t.Fatal("tampered token must fail verification")
	}
}

// 用不同密钥构造的服务不能验证彼此的 token。
func TestAdminVerifyRejectsForeignSecret(t *testing.T) {
	a := NewAdminService("admin", "p", "0123456789abcdef", time.Hour)
	b := NewAdminService("admin", "p", "fedcba9876543210", time.Hour)
	token, _, err := a.Login("admin", "p")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if _, err := b.Verify(token); err == nil {
		t.Fatal("token signed with another secret must not verify")
	}
}
