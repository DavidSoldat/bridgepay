import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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

  it('labels the icon-only log out button', () => {
    const fixture = setup(['ops']);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('button[aria-label="Log out"]')).not.toBeNull();
  });
});
