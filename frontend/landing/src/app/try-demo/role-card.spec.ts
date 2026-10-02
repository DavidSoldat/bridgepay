import { TestBed } from '@angular/core/testing';
import { RoleCard } from './role-card';

function render(login?: { username: string; password: string }) {
  const f = TestBed.createComponent(RoleCard);
  f.componentRef.setInput('role', 'shopper');
  f.componentRef.setInput('href', 'http://shop.example');
  f.componentRef.setInput('login', login ? { role: 'shopper', ...login } : undefined);
  f.detectChanges();
  return { f, el: f.nativeElement as HTMLElement };
}

const copyButton = (el: HTMLElement, label: string) =>
  el.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement;

function stubClipboard(clipboard: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });
}

describe('RoleCard', () => {
  afterEach(() => stubClipboard(undefined));

  it('names the role, says what it does and links to its app', () => {
    const { el } = render({ username: 'shopper1', password: 'shopper1' });
    expect(el.querySelector('h3')!.textContent).toContain('Shopper');
    expect(el.textContent).toContain('Buy something at the demo store and pay in 4.');
    const link = el.querySelector('a')!;
    expect(link.getAttribute('href')).toBe('http://shop.example');
    expect(link.textContent).toContain('Open the store');
  });

  it('shows the username and password with a copy button each', () => {
    const { el } = render({ username: 'shopper1', password: 'secret-pw' });
    expect(el.querySelector('[data-testid="username"]')!.textContent!.trim()).toBe('shopper1');
    expect(el.querySelector('[data-testid="password"]')!.textContent!.trim()).toBe('secret-pw');
    expect(copyButton(el, 'Copy shopper username')).toBeTruthy();
    expect(copyButton(el, 'Copy shopper password')).toBeTruthy();
  });

  it('copies the exact value and says so', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    stubClipboard({ writeText });
    const { f, el } = render({ username: 'shopper1', password: 'secret-pw' });

    copyButton(el, 'Copy shopper password').click();
    await f.whenStable();
    f.detectChanges();

    expect(writeText).toHaveBeenCalledWith('secret-pw');
    expect(el.querySelector('[role="status"]')!.textContent).toContain('Password copied');
  });

  it('tells the user to select the text when the clipboard is missing or refuses', async () => {
    for (const clipboard of [undefined, { writeText: vi.fn().mockRejectedValue(new Error('denied')) }]) {
      stubClipboard(clipboard);
      TestBed.resetTestingModule();
      const { f, el } = render({ username: 'shopper1', password: 'secret-pw' });
      copyButton(el, 'Copy shopper username').click();
      await f.whenStable();
      f.detectChanges();
      expect(el.querySelector('[role="status"]')!.textContent).toContain('Copy failed — select the text instead');
    }
  });

  it('says access is on request when there is no login', () => {
    const { el } = render();
    expect(el.textContent).toContain('Demo access on request.');
    expect(el.querySelectorAll('button').length).toBe(0);
    expect(el.querySelector('[data-testid="password"]')).toBeNull();
  });

  it('lets a long value wrap instead of widening the page', () => {
    const { el } = render({ username: 'demo.shopper.with.a.long.name@example.com', password: 'x' });
    expect(el.querySelector('[data-testid="username"]')!.className).toContain('break-all');
  });
});
