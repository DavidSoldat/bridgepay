import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { ShopperSearch } from './shopper-search';
import { ShoppersApi } from '../shoppers-api';
import { OpsApplicant } from '../../../shared/models/ops-applicant';
import { Page } from '../../../shared/models/page';

const ana: OpsApplicant = {
  subject: 's-1', firstName: 'Ana', lastName: 'Doe', email: 'ana@example.com', phone: '+1',
  dateOfBirth: '1995-04-12', createdAt: '2026-10-01T10:00:00Z',
};

function page(content: OpsApplicant[]): Page<OpsApplicant> {
  return { content, totalElements: content.length, totalPages: content.length ? 1 : 0, number: 0, size: 20 };
}

async function setup(url: string, search: (q: string, p: number) => Observable<Page<OpsApplicant>>) {
  const calls: [string, number][] = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'ops/shoppers', component: ShopperSearch }]),
      { provide: ShoppersApi, useValue: { search: (q: string, p: number) => (calls.push([q, p]), search(q, p)) } },
    ],
  });
  const harness = await RouterTestingHarness.create(url);
  return { harness, calls, el: harness.routeNativeElement as HTMLElement };
}

describe('ShopperSearch', () => {
  it('restoresSearchFromQueryParams', async () => {
    const { calls, el } = await setup('/ops/shoppers?q=ana&page=0', () => of(page([ana])));

    expect(calls).toEqual([['ana', 0]]);
    expect((el.querySelector('input[type="search"]') as HTMLInputElement).value).toBe('ana');
    expect(el.textContent).toContain('Ana Doe');
    expect(el.querySelector('a[href="/ops/shoppers/s-1"]')).not.toBeNull();
  });

  it('doesNotSearchUnderTwoCharacters', async () => {
    const { calls, el } = await setup('/ops/shoppers?q=%20a%20', () => of(page([ana])));

    expect(calls).toEqual([]);
    expect(el.textContent).toContain('Search by name or email');
  });

  it('puts the typed query in the URL after a pause, then searches', async () => {
    const { harness, calls, el } = await setup('/ops/shoppers', () => of(page([ana])));
    const input = el.querySelector('input[type="search"]') as HTMLInputElement;

    input.value = 'ana d';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
    expect(calls).toEqual([]); // not before the pause
    await new Promise((resolve) => setTimeout(resolve, 350));
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(TestBed.inject(Router).url).toContain('q=ana%20d');
    expect(calls).toEqual([['ana d', 0]]);
  });

  it('shows an empty state naming the query', async () => {
    const { el } = await setup('/ops/shoppers?q=zed', () => of(page([])));
    expect(el.textContent).toContain('No shoppers match "zed"');
  });

  it('shows a distinct error when search fails', async () => {
    const { el } = await setup('/ops/shoppers?q=zed', () => throwError(() => new Error('down')));
    expect(el.textContent).toContain('Could not search shoppers');
  });
});
