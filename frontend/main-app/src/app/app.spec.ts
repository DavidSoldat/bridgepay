import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from './app';
import { Auth } from './core/auth';

describe('App', () => {
  function setup(roles: string[]) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        {
          provide: Auth,
          useValue: {
            hasRole: (r: string) => roles.includes(r),
            username: () => 'test-user',
            logout: () => {},
          },
        },
      ],
    });
    return TestBed.createComponent(App);
  }

  it('shows the Review Queue link for an ops role', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Review Queue');
    expect(text).toContain('Dashboard');
    expect(text).not.toContain('Payouts');
    expect(text).not.toContain('Sales');
  });

  it('shows the Sales and Payouts links for a merchant role', () => {
    const fixture = setup(['merchant']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const links = Array.from(el.querySelectorAll('aside a')).map((a) => [a.textContent?.trim(), a.getAttribute('href')]);
    expect(links).toEqual([
      ['Sales', '/merchant'],
      ['Payouts', '/merchant/payouts'],
    ]);
    expect(el.textContent).not.toContain('Review Queue');
    expect(el.textContent).not.toContain('Dashboard');
  });

  it('opens and closes the navigation from the menu button on small screens', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const menu = el.querySelector('button[aria-controls="main-nav"]') as HTMLButtonElement;
    const nav = el.querySelector('#main-nav')!;

    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(nav.classList).toContain('hidden');

    menu.click();
    fixture.detectChanges();
    expect(menu.getAttribute('aria-expanded')).toBe('true');
    expect(nav.classList).not.toContain('hidden');
  });

  it('closes the opened menu when a navigation link is chosen', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('button[aria-controls="main-nav"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    (el.querySelector('#main-nav a') as HTMLAnchorElement).click();
    fixture.detectChanges();
    expect(el.querySelector('#main-nav')!.classList).toContain('hidden');
  });

  it('marks the active nav link in the darker coral that passes AA contrast on blush', async () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/ops');
    fixture.detectChanges();

    const active = (fixture.nativeElement as HTMLElement).querySelector('#main-nav a[href="/ops"]')!;
    expect(active.classList).toContain('text-coral-hover');
    // text-ink comes later in the generated CSS, so it would override the active colour if both were set.
    expect(active.classList).not.toContain('text-ink');
  });

  it('shows the signed-in user and a red Log out button at the bottom of the sidebar, not in a top header', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const footer = el.querySelector('#main-nav [data-testid="sidebar-account"]') as HTMLElement;

    expect(footer.textContent).toContain('test-user');
    const logout = Array.from(footer.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Log out')!;
    expect(logout.classList).toContain('text-declined');
    // after the nav links, so it sits at the bottom
    expect(footer.previousElementSibling?.tagName).toBe('A');
    expect(el.querySelector('header')).toBeNull();
  });

  it('logs out from the sidebar button', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    const auth = TestBed.inject(Auth);
    const spy = vi.spyOn(auth, 'logout');
    const el = fixture.nativeElement as HTMLElement;

    (Array.from(el.querySelectorAll('#main-nav button')).find((b) => b.textContent?.trim() === 'Log out') as HTMLButtonElement).click();
    expect(spy).toHaveBeenCalled();
  });
});
