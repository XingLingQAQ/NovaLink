/**
 * Login Screen for NovaPanel.
 * Gates the dashboard until authService.isAuthenticated() is true.
 *
 * Allows the user to configure the API base URL + WebSocket URL on the same
 * screen (collapsed by default; expanded via the advanced-settings toggle).
 */

import React, { useState } from 'react';
import { Zap, Lock, User, Loader2, Server, Wifi, ChevronDown, ChevronUp } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import authService from '../../services/auth';
import { getApiBaseUrl, getWsUrl, setConnectionUrls } from '../../services/api';

export default function LoginScreen({ onLoginSuccess }) {
  const { t } = useTranslation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [apiUrl, setApiUrl] = useState(getApiBaseUrl());
  const [wsUrl, setWsUrl] = useState(getWsUrl());
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!username || !password) {
      setError(t('login.error_empty'));
      return;
    }
    setLoading(true);
    setError(null);
    try {
      // Persist connection URLs so the REST client + WS service use them.
      setConnectionUrls(apiUrl, wsUrl);
      await authService.login(username, password, getApiBaseUrl());
      if (onLoginSuccess) onLoginSuccess(authService.getUser());
    } catch (err) {
      setError(err.message || t('login.error_failed'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full flex items-center justify-center relative overflow-hidden font-sans">
      {/* Background */}
      <div className="fixed inset-0 z-0 bg-gradient-to-br from-slate-950 via-slate-900 to-sky-900" />
      <div className="fixed inset-0 z-0 bg-black/40" />
      <div className="fixed top-[-10%] right-[-10%] w-[500px] h-[500px] bg-sky-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none" />
      <div className="fixed bottom-[-10%] left-[-10%] w-[600px] h-[600px] bg-purple-500/20 rounded-full blur-[120px] animate-pulse z-0 pointer-events-none" style={{ animationDelay: '2s' }} />

      <div className="relative z-10 w-full max-w-md mx-4">
        <div className="bg-black/40 border border-white/10 backdrop-blur-2xl rounded-3xl shadow-2xl p-8">
          {/* Logo */}
          <div className="flex flex-col items-center mb-8">
            <div className="w-14 h-14 rounded-2xl flex items-center justify-center bg-gradient-to-br from-sky-400 to-blue-500 text-white shadow-lg mb-4">
              <Zap size={28} />
            </div>
            <h1 className="text-2xl font-bold text-white">Nova<span className="font-light">Panel</span></h1>
            <p className="text-sm text-slate-400 mt-1">{t('login.subtitle')}</p>
          </div>

          {/* Error */}
          {error && (
            <div className="mb-4 px-4 py-3 rounded-xl bg-rose-500/20 border border-rose-500/30 text-rose-300 text-sm flex items-center gap-2">
              <span className="font-medium">{error}</span>
            </div>
          )}

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider mb-1.5 text-slate-400">{t('login.username')}</label>
              <div className="relative">
                <User size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder={t('login.username_placeholder')}
                  autoFocus
                  disabled={loading}
                  className="w-full pl-10 pr-4 py-2.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder:text-white/30 outline-none focus:ring-2 focus:ring-sky-500 transition-all"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider mb-1.5 text-slate-400">{t('login.password')}</label>
              <div className="relative">
                <Lock size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={t('login.password_placeholder')}
                  disabled={loading}
                  className="w-full pl-10 pr-4 py-2.5 rounded-xl bg-white/10 border border-white/20 text-white placeholder:text-white/30 outline-none focus:ring-2 focus:ring-sky-500 transition-all"
                />
              </div>
            </div>

            {/* Advanced settings toggle */}
            <button
              type="button"
              onClick={() => setShowAdvanced(!showAdvanced)}
              className="flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200 transition-colors"
            >
              {showAdvanced ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              {t('login.advanced')}
            </button>

            {showAdvanced && (
              <div className="space-y-3 p-3 rounded-xl bg-white/5 border border-white/10">
                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider mb-1.5 text-slate-400">{t('login.api_address')}</label>
                  <div className="relative">
                    <Server size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
                    <input
                      type="text"
                      value={apiUrl}
                      onChange={(e) => setApiUrl(e.target.value)}
                      placeholder="/api"
                      disabled={loading}
                      className="w-full pl-9 pr-3 py-2 rounded-lg bg-white/10 border border-white/20 text-white text-sm placeholder:text-white/30 outline-none focus:ring-2 focus:ring-sky-500 transition-all"
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-semibold uppercase tracking-wider mb-1.5 text-slate-400">{t('login.ws_address')}</label>
                  <div className="relative">
                    <Wifi size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
                    <input
                      type="text"
                      value={wsUrl}
                      onChange={(e) => setWsUrl(e.target.value)}
                      placeholder="ws://localhost:8889"
                      disabled={loading}
                      className="w-full pl-9 pr-3 py-2 rounded-lg bg-white/10 border border-white/20 text-white text-sm placeholder:text-white/30 outline-none focus:ring-2 focus:ring-sky-500 transition-all"
                    />
                  </div>
                </div>
                <p className="text-[10px] text-slate-500">{t('login.advanced_hint')}</p>
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full py-2.5 rounded-xl bg-gradient-to-r from-sky-500 to-blue-600 hover:from-sky-400 hover:to-blue-500 text-white font-semibold shadow-lg shadow-sky-500/20 transition-all active:scale-95 disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              {loading ? <Loader2 size={18} className="animate-spin" /> : <Lock size={18} />}
              {loading ? t('login.logging_in') : t('login.login_button')}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
