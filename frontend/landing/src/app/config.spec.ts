import { DEFAULT_CONFIG, loadConfig, parseConfig } from './config';

const full = {
  storefrontUrl: 'http://shop.example',
  appUrl: 'http://app.example',
  githubUrl: 'https://github.com/x/y',
  demoLogins: [
    { role: 'shopper', username: 'shopper1', password: 'shopper1' },
    { role: 'ops', username: 'ops1', password: 'ops1' },
    { role: 'merchant', username: 'merchant1', password: 'merchant1' },
  ],
};

const response = (body: string, ok = true) =>
  Promise.resolve({ ok, json: () => Promise.resolve().then(() => JSON.parse(body)) } as Response);

describe('parseConfig', () => {
  it('reads a full config', () => {
    expect(parseConfig(full)).toEqual(full);
  });

  it('falls back to the default URLs and no GitHub link', () => {
    const c = parseConfig({ demoLogins: [] });
    expect(c.storefrontUrl).toBe(DEFAULT_CONFIG.storefrontUrl);
    expect(c.appUrl).toBe(DEFAULT_CONFIG.appUrl);
    expect(c.githubUrl).toBeNull();
  });

  it('has no logins when they are missing, empty or not a list', () => {
    expect(parseConfig({}).demoLogins).toEqual([]);
    expect(parseConfig({ demoLogins: [] }).demoLogins).toEqual([]);
    expect(parseConfig({ demoLogins: 'shopper1' }).demoLogins).toEqual([]);
    expect(parseConfig(null).demoLogins).toEqual([]);
  });

  it('drops unknown roles and logins with a blank username or password', () => {
    const c = parseConfig({
      demoLogins: [
        { role: 'admin', username: 'root', password: 'root' },
        { role: 'shopper', username: 'shopper1', password: '' },
        { role: 'ops', username: '  ', password: 'ops1' },
        { role: 'merchant', username: 'merchant1', password: 'merchant1' },
        null,
      ],
    });
    expect(c.demoLogins).toEqual([{ role: 'merchant', username: 'merchant1', password: 'merchant1' }]);
  });
});

describe('loadConfig', () => {
  it('parses /config.json', async () => {
    const fetchFn = vi.fn(() => response(JSON.stringify(full)));
    expect(await loadConfig(fetchFn as unknown as typeof fetch)).toEqual(full);
    expect(fetchFn).toHaveBeenCalledWith('/config.json');
  });

  it('never falls back to built-in passwords: a failed, non-OK or non-JSON answer means defaults with no logins', async () => {
    const failing = [
      () => Promise.reject(new Error('offline')),
      () => response(JSON.stringify(full), false),
      () => response('<!doctype html><html></html>'),
    ];
    for (const fetchFn of failing) {
      expect(await loadConfig(fetchFn as unknown as typeof fetch)).toEqual(DEFAULT_CONFIG);
    }
    expect(DEFAULT_CONFIG.demoLogins).toEqual([]);
  });
});
