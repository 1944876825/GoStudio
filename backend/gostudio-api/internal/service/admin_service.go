package service

import (
	"crypto/subtle"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var ErrInvalidCredentials = errors.New("invalid username or password")

// AdminService 管理后台的账号校验与 JWT 会话签发。
type AdminService struct {
	username  string
	password  string
	jwtSecret []byte
	sessionTTL time.Duration
}

func NewAdminService(username, password, jwtSecret string, sessionTTL time.Duration) *AdminService {
	return &AdminService{
		username:   username,
		password:   password,
		jwtSecret:  []byte(jwtSecret),
		sessionTTL: sessionTTL,
	}
}

// Login 校验账号密码并签发 Bearer token。
func (s *AdminService) Login(username, password string) (token string, expiresAt time.Time, err error) {
	userOK := subtle.ConstantTimeCompare([]byte(username), []byte(s.username)) == 1
	passOK := subtle.ConstantTimeCompare([]byte(password), []byte(s.password)) == 1
	if !userOK || !passOK {
		return "", time.Time{}, ErrInvalidCredentials
	}
	now := time.Now()
	expiresAt = now.Add(s.sessionTTL)
	claims := jwt.RegisteredClaims{
		Subject:   username,
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(expiresAt),
	}
	token, err = jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(s.jwtSecret)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("sign token: %w", err)
	}
	return token, expiresAt, nil
}

// Verify 校验后台 token，通过返回用户名。
func (s *AdminService) Verify(token string) (string, error) {
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method %v", t.Header["alg"])
		}
		return s.jwtSecret, nil
	})
	if err != nil || !parsed.Valid {
		return "", errors.New("invalid or expired token")
	}
	if subject, err := parsed.Claims.GetSubject(); err == nil {
		return subject, nil
	}
	return "", errors.New("invalid token subject")
}
