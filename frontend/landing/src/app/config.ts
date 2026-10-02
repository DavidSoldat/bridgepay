import { InjectionToken } from '@angular/core';

export type Role = 'shopper' | 'ops' | 'merchant';

export interface DemoLogin {
  role: Role;
  username: string;
  password: string;
}

export interface LandingConfig {
  storefrontUrl: string;
  appUrl: string;
  githubUrl: string | null;
  demoLogins: DemoLogin[];
}

/** docker-compose URLs. No logins: a broken config must never reveal built-in passwords. */
export const DEFAULT_CONFIG: LandingConfig = {
  storefrontUrl: 'http://localhost:4201',
  appUrl: 'http://localhost:4200',
  githubUrl: null,
  demoLogins: [],
};

export const LANDING_CONFIG = new InjectionToken<LandingConfig>('LANDING_CONFIG', {
  factory: () => DEFAULT_CONFIG,
});

const ROLES: readonly string[] = ['shopper', 'ops', 'merchant'];

const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value.trim() : null);

export function parseConfig(raw: unknown): LandingConfig {
  const o = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const logins = Array.isArray(o['demoLogins']) ? o['demoLogins'] : [];
  return {
    storefrontUrl: text(o['storefrontUrl']) ?? DEFAULT_CONFIG.storefrontUrl,
    appUrl: text(o['appUrl']) ?? DEFAULT_CONFIG.appUrl,
    githubUrl: text(o['githubUrl']),
    demoLogins: logins.flatMap((entry) => {
      const l = (entry && typeof entry === 'object' ? entry : {}) as Record<string, unknown>;
      const username = text(l['username']);
      const password = text(l['password']);
      return typeof l['role'] === 'string' && ROLES.includes(l['role']) && username && password
        ? [{ role: l['role'] as Role, username, password }]
        : [];
    }),
  };
}

export async function loadConfig(fetchFn: typeof fetch = fetch): Promise<LandingConfig> {
  try {
    const res = await fetchFn('/config.json');
    return res.ok ? parseConfig(await res.json()) : DEFAULT_CONFIG;
  } catch {
    return DEFAULT_CONFIG;
  }
}
