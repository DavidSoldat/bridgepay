import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { FirstPayment, POLL_ATTEMPTS, POLL_INTERVAL_MS } from './first-payment';
import { RepaymentPlans } from '../../account/repayment-plans';
import { RepaymentPlanResponse } from '../../account/repayment-plan.model';
import { PaddleCheckout } from '../paddle-checkout';

const plan = (checkoutTransactionId: string | null): RepaymentPlanResponse => ({
  planId: 'plan-1', applicationId: 'app-1', status: 'ACTIVE', totalAmount: 200,
  installmentCount: 4, installmentAmount: 50, installments: [], checkoutTransactionId,
});
const notFound = (): Observable<RepaymentPlanResponse> => throwError(() => new HttpErrorResponse({ status: 404 }));

describe('FirstPayment', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup(opts: {
    awaitPlan: boolean;
    responses: Array<() => Observable<RepaymentPlanResponse>>;
    enabled?: boolean;
    open?: ReturnType<typeof vi.fn>;
  }) {
    const queue = [...opts.responses];
    const getPlan = vi.fn(() => (queue.shift() ?? notFound)());
    const open = opts.open ?? vi.fn().mockResolvedValue('completed');
    TestBed.configureTestingModule({
      imports: [FirstPayment],
      providers: [
        provideRouter([]),
        { provide: RepaymentPlans, useValue: { getPlan } },
        { provide: PaddleCheckout, useValue: { enabled: opts.enabled ?? true, open } },
      ],
    });
    const fixture = TestBed.createComponent(FirstPayment);
    fixture.componentRef.setInput('applicationId', 'app-1');
    fixture.componentRef.setInput('awaitPlan', opts.awaitPlan);
    fixture.detectChanges();
    return { fixture, getPlan, open };
  }

  async function settle(fixture: { detectChanges(): void }, ms = 0) {
    await vi.advanceTimersByTimeAsync(ms);
    fixture.detectChanges();
  }

  const text = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement.textContent ?? '';

  it('waits for the plan on the result screen, then opens Paddle and confirms on completion', async () => {
    const { fixture, open } = setup({ awaitPlan: true, responses: [notFound, notFound, () => of(plan('txn_1'))] });
    expect(text(fixture)).toContain('Setting up your payment plan');

    await settle(fixture, 2 * POLL_INTERVAL_MS);

    expect(open).toHaveBeenCalledWith('txn_1');
    expect(text(fixture)).toContain('Payment received');
    expect(text(fixture)).toContain('charged automatically each week');
  });

  it('says the order is not confirmed when closed without paying, and Pay now reopens', async () => {
    const open = vi.fn().mockResolvedValueOnce('closed').mockResolvedValueOnce('completed');
    const { fixture } = setup({ awaitPlan: true, responses: [() => of(plan('txn_1'))], open });
    await settle(fixture);

    expect(text(fixture)).toContain("isn't confirmed until the first payment is made");
    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    await settle(fixture);

    expect(open).toHaveBeenCalledTimes(2);
    expect(text(fixture)).toContain('Payment received');
  });

  it('gives up after 20 attempts and points to the account page', async () => {
    const { fixture, getPlan, open } = setup({ awaitPlan: true, responses: [] });

    await settle(fixture, POLL_ATTEMPTS * POLL_INTERVAL_MS);

    expect(getPlan).toHaveBeenCalledTimes(POLL_ATTEMPTS);
    expect(open).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('Still setting up your plan');
    expect((fixture.nativeElement as HTMLElement).querySelector('a')!.getAttribute('href')).toBe('/account');
  });

  it('on the account page shows Action required with the amount and waits for a click', async () => {
    const { fixture, open } = setup({ awaitPlan: false, responses: [() => of(plan('txn_1'))] });
    await settle(fixture);

    expect(text(fixture)).toContain('Action required');
    expect(text(fixture)).toContain('$50.00');
    expect(text(fixture)).toContain('cancelled after 24 hours');
    expect(open).not.toHaveBeenCalled();
  });

  it('renders nothing once the first installment is paid', async () => {
    const { fixture, open } = setup({ awaitPlan: false, responses: [() => of(plan(null))] });
    await settle(fixture);

    expect(text(fixture).trim()).toBe('');
    expect(open).not.toHaveBeenCalled();
  });

  it('on the account page fetches once and renders nothing when no plan exists', async () => {
    const { fixture, getPlan } = setup({ awaitPlan: false, responses: [] });
    await settle(fixture, 5 * POLL_INTERVAL_MS);

    expect(getPlan).toHaveBeenCalledTimes(1);
    expect(text(fixture).trim()).toBe('');
  });

  it('renders nothing and fetches nothing when Paddle is not configured', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { fixture, getPlan } = setup({ awaitPlan: true, responses: [], enabled: false });
    await settle(fixture, POLL_INTERVAL_MS);

    expect(getPlan).not.toHaveBeenCalled();
    expect(text(fixture).trim()).toBe('');
    expect(warn).toHaveBeenCalled();
  });

  it('shows a load error when Paddle.js cannot be loaded, with Pay now to retry', async () => {
    const open = vi.fn().mockRejectedValue(new Error('Could not load Paddle.js'));
    const { fixture } = setup({ awaitPlan: true, responses: [() => of(plan('txn_1'))], open });
    await settle(fixture);

    expect(text(fixture)).toContain("Couldn't open the payment window");
    expect(text(fixture)).toContain('Pay now');
  });
});
