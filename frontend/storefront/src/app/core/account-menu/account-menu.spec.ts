import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Auth } from '../auth';
import { AccountMenu } from './account-menu';

describe('AccountMenu', () => {
  function setup() {
    const logout = vi.fn();
    TestBed.configureTestingModule({
      imports: [AccountMenu],
      providers: [provideRouter([]), { provide: Auth, useValue: { logout } }],
    });
    const fixture = TestBed.createComponent(AccountMenu);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = el.querySelector('button[aria-label="Account"]') as HTMLButtonElement;
    return { fixture, el, button, logout };
  }

  it('is closed by default', () => {
    const { el, button } = setup();
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('[role="region"]')).toBeNull();
  });

  it('opens on click with My Account and Sign out, aria wired to the panel', () => {
    const { fixture, el, button } = setup();
    button.click();
    fixture.detectChanges();
    const panel = el.querySelector('[role="region"]') as HTMLElement;
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(panel.getAttribute('aria-label')).toBe('Account');
    expect(button.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.textContent).toContain('My Account');
    expect(panel.textContent).toContain('Sign out');
  });

  it('Sign out calls logout', () => {
    const { fixture, el, button, logout } = setup();
    button.click();
    fixture.detectChanges();
    const out = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Sign out') as HTMLButtonElement;
    out.click();
    expect(logout).toHaveBeenCalled();
  });

  it('Escape closes and returns focus to the button', () => {
    const { fixture, el, button } = setup();
    button.click();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el.querySelector('[role="region"]')).toBeNull();
    expect(document.activeElement).toBe(button);
  });

  it('closes on an outside click', () => {
    const { fixture, el, button } = setup();
    button.click();
    fixture.detectChanges();
    document.body.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="region"]')).toBeNull();
  });
});
