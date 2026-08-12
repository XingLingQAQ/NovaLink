/**
 * Login Screen for NovaPanel.
 * Gates the dashboard until authService.isAuthenticated() is true.
 *
 * Layout mirrors the reference design (logs/frontend/src/features/auth/login-page.tsx):
 * min-h-screen bg-background, a centered max-w-[960px] layout with a header,
 * a two-column hero (hidden on small screens) + a max-w-[336px] form card.
 * Light-first, Inter font, pill Button (size="sm" w-full), Input h-9 bg-card.
 */

import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import authService from '../../services/auth';
import { getApiBaseUrl, getWsUrl, setConnectionUrls } from '../../services/api';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Label from '../ui/Label';

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
    <div className="flex min-h-screen flex-col bg-background">
      {/* Header */}
      <header className="mx-auto flex h-16 w-full max-w-[960px] items-center justify-between px-5 sm:px-8 lg:px-0">
        <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <img src="/novalink-logo.svg" alt="NovaLink" className="size-7 shrink-0 object-contain" />
          {t('appName')}
        </span>
      </header>

      {/* Main */}
      <main className="mx-auto flex w-full max-w-[960px] flex-1 items-center justify-center px-5 py-12 sm:px-8 lg:px-0">
        <div className="grid w-full max-w-[840px] -translate-y-6 items-center lg:-translate-y-10 lg:grid-cols-[minmax(0,1fr)_1px_336px] lg:gap-14">
          {/* Hero (hidden on small screens) */}
          <section className="hidden min-h-72 flex-col justify-center lg:flex">
            <p className="text-xs font-medium text-muted-foreground">{t('appName')}</p>
            <h2 className="mt-3 max-w-sm text-3xl font-medium leading-tight text-foreground">
              {t('login.subtitle')}
            </h2>
            <p className="mt-4 max-w-xs text-xs leading-6 text-muted-foreground">
              {t('login.advanced_hint')}
            </p>
          </section>

          <div className="hidden h-64 bg-border lg:block" aria-hidden="true" />

          {/* Form */}
          <section className="w-full max-w-[336px] justify-self-center lg:justify-self-auto">
            <div className="mb-6">
              <h1 className="text-xl font-medium text-foreground">{t('login.title')}</h1>
              <p className="mt-2 text-xs leading-5 text-muted-foreground lg:hidden">
                {t('login.subtitle')}
              </p>
            </div>

            {error && (
              <div className="mb-4 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                {error}
              </div>
            )}

            <form className="space-y-4" onSubmit={handleSubmit}>
              <div className="space-y-2">
                <Label htmlFor="username">{t('login.username')}</Label>
                <Input
                  id="username"
                  className="h-9 bg-card"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder={t('login.username_placeholder')}
                  autoFocus
                  disabled={loading}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="password">{t('login.password')}</Label>
                <Input
                  id="password"
                  className="h-9 bg-card"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={t('login.password_placeholder')}
                  disabled={loading}
                />
              </div>

              {/* Advanced settings toggle */}
              <button
                type="button"
                onClick={() => setShowAdvanced(!showAdvanced)}
                className="text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                {t('login.advanced')}
              </button>

              {showAdvanced && (
                <div className="space-y-3 rounded-md border border-border bg-muted/40 p-3">
                  <div className="space-y-2">
                    <Label htmlFor="apiUrl">{t('login.api_address')}</Label>
                    <Input
                      id="apiUrl"
                      className="h-9 bg-card"
                      value={apiUrl}
                      onChange={(e) => setApiUrl(e.target.value)}
                      placeholder="/api"
                      disabled={loading}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="wsUrl">{t('login.ws_address')}</Label>
                    <Input
                      id="wsUrl"
                      className="h-9 bg-card"
                      value={wsUrl}
                      onChange={(e) => setWsUrl(e.target.value)}
                      placeholder="ws://localhost:8889"
                      disabled={loading}
                    />
                  </div>
                  <p className="text-[11px] text-muted-foreground">{t('login.advanced_hint')}</p>
                </div>
              )}

              <Button type="submit" size="sm" className="w-full" disabled={loading}>
                {loading ? <Loader2 size={16} className="animate-spin" /> : null}
                {loading ? t('login.logging_in') : t('login.login_button')}
              </Button>
            </form>
          </section>
        </div>
      </main>
    </div>
  );
}
