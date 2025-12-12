// Package auth provides authentication and authorization functionality.
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

var (
	// ErrInvalidToken is returned when the token format is invalid.
	ErrInvalidToken = errors.New("invalid token")
	// ErrTokenExpired is returned when the token has expired.
	ErrTokenExpired = errors.New("token expired")
	// ErrInvalidSignature is returned when the token signature is invalid.
	ErrInvalidSignature = errors.New("invalid signature")
	// ErrEmptySecret is returned when the secret is empty.
	ErrEmptySecret = errors.New("secret cannot be empty")
)

// JWTService handles JWT token generation and verification.
// It uses HMAC-SHA256 for signing tokens.
type JWTService struct {
	secret     []byte
	expiration time.Duration
	issuer     string
}

// JWTClaims represents the claims in a JWT token.
type JWTClaims struct {
	Subject   string `json:"sub"`
	Issuer    string `json:"iss,omitempty"`
	IssuedAt  int64  `json:"iat"`
	ExpiresAt int64  `json:"exp"`
	Username  string `json:"username,omitempty"`
	Role      string `json:"role,omitempty"`
	ClientID  string `json:"client_id,omitempty"`
	Type      string `json:"type,omitempty"` // "refresh" for refresh tokens
}

// JWTHeader represents the JWT header.
type JWTHeader struct {
	Algorithm string `json:"alg"`
	Type      string `json:"typ"`
}

// NewJWTService creates a new JWTService.
// secret is the HMAC secret key for signing tokens.
// expiration is the default token expiration duration.
func NewJWTService(secret string, expiration time.Duration) *JWTService {
	return &JWTService{
		secret:     []byte(secret),
		expiration: expiration,
		issuer:     "NovaLink",
	}
}

// NewJWTServiceWithIssuer creates a new JWTService with a custom issuer.
func NewJWTServiceWithIssuer(secret string, expiration time.Duration, issuer string) *JWTService {
	return &JWTService{
		secret:     []byte(secret),
		expiration: expiration,
		issuer:     issuer,
	}
}

// GenerateToken creates a new JWT token for the given subject.
// This returns an ACCESS token (no "type" claim).
//
// Backward compatibility note:
// legacy callers used subject=username. This method still works that way.
func (s *JWTService) GenerateToken(subject, role string) (string, error) {
	return s.GenerateAccessToken(subject, subject, role, "")
}

// GenerateTokenWithClaims creates an ACCESS token with optional client_id.
// This is kept for backward compatibility with older Go-side API code.
func (s *JWTService) GenerateTokenWithClaims(subject, role, clientID string) (string, error) {
	return s.GenerateAccessToken(subject, subject, role, clientID)
}

// GenerateAccessToken creates a new ACCESS token aligned with Java backend claims:
// - sub: user ID
// - username: login username
// - role: role string
// - type: absent (or empty)
func (s *JWTService) GenerateAccessToken(userID, username, role, clientID string) (string, error) {
	now := time.Now()
	claims := JWTClaims{
		Subject:   userID,
		Issuer:    s.issuer,
		IssuedAt:  now.Unix(),
		ExpiresAt: now.Add(s.expiration).Unix(),
		Username:  username,
		Role:      role,
		ClientID:  clientID,
	}

	return s.generateTokenFromClaims(claims)
}

// GenerateRefreshToken creates a refresh token (claim "type" = "refresh").
func (s *JWTService) GenerateRefreshToken(userID, username, role string, expiration time.Duration) (string, error) {
	now := time.Now()
	claims := JWTClaims{
		Subject:   userID,
		Issuer:    s.issuer,
		IssuedAt:  now.Unix(),
		ExpiresAt: now.Add(expiration).Unix(),
		Username:  username,
		Role:      role,
		Type:      "refresh",
	}
	return s.generateTokenFromClaims(claims)
}

// GenerateTokenWithExpiration creates a token with a custom expiration.
func (s *JWTService) GenerateTokenWithExpiration(subject, role string, expiration time.Duration) (string, error) {
	now := time.Now()
	claims := JWTClaims{
		Subject:   subject,
		Issuer:    s.issuer,
		IssuedAt:  now.Unix(),
		ExpiresAt: now.Add(expiration).Unix(),
		Role:      role,
	}

	return s.generateTokenFromClaims(claims)
}

// generateTokenFromClaims creates a token from the given claims.
func (s *JWTService) generateTokenFromClaims(claims JWTClaims) (string, error) {
	// Create header
	header := JWTHeader{
		Algorithm: "HS256",
		Type:      "JWT",
	}

	headerJSON, err := json.Marshal(header)
	if err != nil {
		return "", err
	}

	claimsJSON, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}

	headerB64 := base64.RawURLEncoding.EncodeToString(headerJSON)
	claimsB64 := base64.RawURLEncoding.EncodeToString(claimsJSON)

	// Create signature
	signatureInput := headerB64 + "." + claimsB64
	signature := s.sign(signatureInput)

	return signatureInput + "." + signature, nil
}

// VerifyToken verifies a JWT token and returns the claims.
// Returns an error if the token is invalid, expired, or has an invalid signature.
func (s *JWTService) VerifyToken(token string) (*JWTClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, ErrInvalidToken
	}

	// Verify signature
	signatureInput := parts[0] + "." + parts[1]
	expectedSignature := s.sign(signatureInput)
	if parts[2] != expectedSignature {
		return nil, ErrInvalidSignature
	}

	// Decode claims
	claimsJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, ErrInvalidToken
	}

	var claims JWTClaims
	if err := json.Unmarshal(claimsJSON, &claims); err != nil {
		return nil, ErrInvalidToken
	}

	// Check expiration
	if time.Now().Unix() > claims.ExpiresAt {
		return nil, ErrTokenExpired
	}

	return &claims, nil
}

// VerifyTokenIgnoreExpiration verifies a token without checking expiration.
// Useful for token refresh operations.
func (s *JWTService) VerifyTokenIgnoreExpiration(token string) (*JWTClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, ErrInvalidToken
	}

	// Verify signature
	signatureInput := parts[0] + "." + parts[1]
	expectedSignature := s.sign(signatureInput)
	if parts[2] != expectedSignature {
		return nil, ErrInvalidSignature
	}

	// Decode claims
	claimsJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, ErrInvalidToken
	}

	var claims JWTClaims
	if err := json.Unmarshal(claimsJSON, &claims); err != nil {
		return nil, ErrInvalidToken
	}

	return &claims, nil
}

// sign creates an HMAC-SHA256 signature.
func (s *JWTService) sign(input string) string {
	h := hmac.New(sha256.New, s.secret)
	h.Write([]byte(input))
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}

// RefreshToken creates a new token with extended expiration.
// The new token will have the same subject and role as the original.
func (s *JWTService) RefreshToken(token string) (string, error) {
	claims, err := s.VerifyTokenIgnoreExpiration(token)
	if err != nil {
		return "", err
	}

	// Only allow refresh tokens for this flow.
	if claims.Type != "refresh" {
		return "", ErrInvalidToken
	}

	return s.GenerateAccessToken(claims.Subject, claims.Username, claims.Role, claims.ClientID)
}

// IsTokenExpired checks if a token is expired without full verification.
func (s *JWTService) IsTokenExpired(token string) bool {
	claims, err := s.VerifyTokenIgnoreExpiration(token)
	if err != nil {
		return true
	}
	return time.Now().Unix() > claims.ExpiresAt
}

// GetTokenExpiration returns the expiration time of a token.
func (s *JWTService) GetTokenExpiration(token string) (time.Time, error) {
	claims, err := s.VerifyTokenIgnoreExpiration(token)
	if err != nil {
		return time.Time{}, err
	}
	return time.Unix(claims.ExpiresAt, 0), nil
}

// GetExpiration returns the default token expiration duration.
func (s *JWTService) GetExpiration() time.Duration {
	return s.expiration
}

// GetIssuer returns the token issuer.
func (s *JWTService) GetIssuer() string {
	return s.issuer
}
