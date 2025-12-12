/**
 * Authentication Service for NovaPanel
 * Handles JWT token management and authentication state
 * 
 * Requirements: 24.4
 */

// Storage keys
const TOKEN_KEY = 'nova_panel_token';
const USER_KEY = 'nova_panel_user';
const REFRESH_TOKEN_KEY = 'nova_panel_refresh_token';

/**
 * Authentication Service class
 */
class AuthService {
  constructor() {
    this.token = null;
    this.refreshToken = null;
    this.user = null;
    this.listeners = new Set();
    this._loadFromStorage();
  }

  /**
   * Load authentication state from localStorage
   */
  _loadFromStorage() {
    try {
      const token = localStorage.getItem(TOKEN_KEY);
      const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
      const userJson = localStorage.getItem(USER_KEY);

      if (token) {
        this.token = token;
        this.refreshToken = refreshToken;
        
        if (userJson) {
          this.user = JSON.parse(userJson);
        }

        // Check if token is expired
        if (this._isTokenExpired(token)) {
          this.logout();
        }
      }
    } catch (error) {
      console.error('[Auth] Failed to load from storage:', error);
      this.logout();
    }
  }

  /**
   * Save authentication state to localStorage
   */
  _saveToStorage() {
    try {
      if (this.token) {
        localStorage.setItem(TOKEN_KEY, this.token);
      } else {
        localStorage.removeItem(TOKEN_KEY);
      }

      if (this.refreshToken) {
        localStorage.setItem(REFRESH_TOKEN_KEY, this.refreshToken);
      } else {
        localStorage.removeItem(REFRESH_TOKEN_KEY);
      }

      if (this.user) {
        localStorage.setItem(USER_KEY, JSON.stringify(this.user));
      } else {
        localStorage.removeItem(USER_KEY);
      }
    } catch (error) {
      console.error('[Auth] Failed to save to storage:', error);
    }
  }

  /**
   * Login with username and password
   * @param {string} username - Username
   * @param {string} password - Password
   * @param {string} apiUrl - API base URL
   * @returns {Promise<object>} - Login result
   */
  async login(username, password, apiUrl = '/api') {
    try {
      const response = await fetch(`${apiUrl}/auth/login`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ username, password })
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.message || 'Login failed');
      }

      const data = await response.json();
      
      this.token = data.token;
      this.refreshToken = data.refreshToken;
      this.user = data.user;
      
      this._saveToStorage();
      this._notifyListeners();

      return {
        success: true,
        user: this.user
      };
    } catch (error) {
      console.error('[Auth] Login failed:', error);
      throw error;
    }
  }

  /**
   * Login with JWT token directly (for development/testing)
   * @param {string} token - JWT token
   * @param {object} user - User info
   */
  loginWithToken(token, user = null) {
    this.token = token;
    this.user = user || this._parseToken(token);
    this._saveToStorage();
    this._notifyListeners();
  }

  /**
   * Logout and clear authentication state
   */
  logout() {
    this.token = null;
    this.refreshToken = null;
    this.user = null;
    this._saveToStorage();
    this._notifyListeners();
  }

  /**
   * Refresh the access token using refresh token
   * @param {string} apiUrl - API base URL
   * @returns {Promise<string>} - New access token
   */
  async refreshAccessToken(apiUrl = '/api') {
    if (!this.refreshToken) {
      throw new Error('No refresh token available');
    }

    try {
      const response = await fetch(`${apiUrl}/auth/refresh`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ refreshToken: this.refreshToken })
      });

      if (!response.ok) {
        throw new Error('Token refresh failed');
      }

      const data = await response.json();
      
      this.token = data.token;
      if (data.refreshToken) {
        this.refreshToken = data.refreshToken;
      }
      
      this._saveToStorage();
      this._notifyListeners();

      return this.token;
    } catch (error) {
      console.error('[Auth] Token refresh failed:', error);
      this.logout();
      throw error;
    }
  }

  /**
   * Get current JWT token
   * @returns {string|null}
   */
  getToken() {
    return this.token;
  }

  /**
   * Get current user info
   * @returns {object|null}
   */
  getUser() {
    return this.user;
  }

  /**
   * Check if user is authenticated
   * @returns {boolean}
   */
  isAuthenticated() {
    return !!this.token && !this._isTokenExpired(this.token);
  }

  /**
   * Check if token is expired
   * @param {string} token - JWT token
   * @returns {boolean}
   */
  _isTokenExpired(token) {
    try {
      const payload = this._parseToken(token);
      if (!payload || !payload.exp) {
        return true;
      }
      
      // Check if expired (with 60 second buffer)
      return Date.now() >= (payload.exp * 1000) - 60000;
    } catch (error) {
      return true;
    }
  }

  /**
   * Parse JWT token payload
   * @param {string} token - JWT token
   * @returns {object|null}
   */
  _parseToken(token) {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }
      
      const payload = parts[1];
      const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return JSON.parse(decoded);
    } catch (error) {
      console.error('[Auth] Failed to parse token:', error);
      return null;
    }
  }

  /**
   * Get token expiration time
   * @returns {Date|null}
   */
  getTokenExpiration() {
    if (!this.token) return null;
    
    const payload = this._parseToken(this.token);
    if (!payload || !payload.exp) return null;
    
    return new Date(payload.exp * 1000);
  }

  /**
   * Add authentication state change listener
   * @param {function} callback - Callback function
   */
  onAuthChange(callback) {
    this.listeners.add(callback);
    return () => this.listeners.delete(callback);
  }

  /**
   * Notify listeners of authentication state change
   */
  _notifyListeners() {
    const state = {
      isAuthenticated: this.isAuthenticated(),
      user: this.user,
      token: this.token
    };
    
    this.listeners.forEach(callback => {
      try {
        callback(state);
      } catch (error) {
        console.error('[Auth] Listener error:', error);
      }
    });
  }

  /**
   * Create authorization header
   * @returns {object}
   */
  getAuthHeader() {
    if (!this.token) {
      return {};
    }
    return {
      'Authorization': `Bearer ${this.token}`
    };
  }
}

// Export singleton instance
export const authService = new AuthService();
export default authService;
