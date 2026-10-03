import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';
import { Notifications } from './notifications/notifications';

describe('App', () => {
  function setup(authenticated: boolean, login: (redirectUri: string) => void = () => {}) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: Notifications, useValue: { unreadCount: signal(0), latest: signal([]), readVersion: signal(0), refresh: () => {}, markAllRead: () => {}, clearUnreadDots: () => {} } },
        { provide: Auth, useValue: { authenticated: () => authenticated, logout: () => {}, login } },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('signed out: Sign in, no account menu, no bell', () => {
    const fixture = setup(false);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Sign in');
    expect(el.querySelector('app-account-menu')).toBeNull();
    expect(el.querySelector('app-notification-bell')).toBeNull();
  });

  it('signed in: bell and Account button, sign out only inside the menu', () => {
    const fixture = setup(true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('app-notification-bell')).not.toBeNull();
    expect(el.querySelector('button[aria-label="Account"]')).not.toBeNull();
    expect(el.textContent).not.toContain('Sign out');
    expect(el.textContent).not.toContain('Sign in');
  });

  it('brand links home with the store name as accessible text, hidden visually on small screens', () => {
    const fixture = setup(true);
    fixture.detectChanges();
    const brand = (fixture.nativeElement as HTMLElement).querySelector('header a') as HTMLElement;
    expect(brand.getAttribute('href')).toBe('/');
    expect(brand.textContent).toContain('Ridgeline Supply Co.');
    const name = Array.from(brand.querySelectorAll('span')).find((s) => s.textContent?.trim() === 'Ridgeline Supply Co.') as HTMLElement;
    expect(name.className).toContain('max-sm:sr-only');
  });

  it('offers a way back to the shop from anywhere', () => {
    const fixture = setup(true);
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('nav a[href="/"]');
    expect(link?.textContent?.trim()).toBe('Shop');
  });

  it('shows the notifications bell only when signed in', () => {
    const signedIn = setup(true);
    signedIn.detectChanges();
    expect((signedIn.nativeElement as HTMLElement).querySelector('app-notification-bell')).not.toBeNull();
    TestBed.resetTestingModule();
    const signedOut = setup(false);
    signedOut.detectChanges();
    expect((signedOut.nativeElement as HTMLElement).querySelector('app-notification-bell')).toBeNull();
  });

  it('names the store and offers Sign in when signed out', () => {
    const redirects: string[] = [];
    const fixture = setup(false, (uri) => redirects.push(uri));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('header')?.textContent).toContain('Ridgeline Supply Co.');
    const signIn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Sign in') as HTMLButtonElement;
    signIn.click();
    expect(redirects).toEqual([window.location.href]);
  });
});
