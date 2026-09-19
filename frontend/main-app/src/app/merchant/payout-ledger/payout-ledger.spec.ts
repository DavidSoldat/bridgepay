import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PayoutLedger } from './payout-ledger';

describe('PayoutLedger', () => {
  let component: PayoutLedger;
  let fixture: ComponentFixture<PayoutLedger>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PayoutLedger],
    }).compileComponents();

    fixture = TestBed.createComponent(PayoutLedger);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
