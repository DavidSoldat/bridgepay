import { TestBed } from '@angular/core/testing';
import { DeliveryForm, DeliveryAddress } from './delivery-form';
import { Auth } from '../../core/auth';

describe('DeliveryForm', () => {
  const address: DeliveryAddress = { fullName: 'Sam Shopper', street: '12 Pine Rd', city: 'Boulder', postalCode: '80302' };

  function setup(fullName = 'Sam Shopper', initial?: DeliveryAddress) {
    TestBed.configureTestingModule({
      imports: [DeliveryForm],
      providers: [{ provide: Auth, useValue: { fullName: () => fullName } }],
    });
    const fixture = TestBed.createComponent(DeliveryForm);
    if (initial) fixture.componentRef.setInput('address', initial);
    fixture.detectChanges();
    return fixture;
  }

  function fill(fixture: ReturnType<typeof setup>, name: string, value: string) {
    const input = (fixture.nativeElement as HTMLElement).querySelector(`[formControlName="${name}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(fixture: ReturnType<typeof setup>) {
    (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit'));
  }

  it('prefills the full name from the signed-in user', () => {
    const fixture = setup('Sam Shopper');
    const input = (fixture.nativeElement as HTMLElement).querySelector('[formControlName="fullName"]') as HTMLInputElement;
    expect(input.value).toBe('Sam Shopper');
  });

  it('does not continue while required fields are empty', () => {
    const fixture = setup();
    let emitted = false;
    fixture.componentInstance.submitted.subscribe(() => (emitted = true));

    submit(fixture);

    expect(emitted).toBe(false);
  });

  it('emits the address once every field is filled', () => {
    const fixture = setup('Sam Shopper');
    let emitted: DeliveryAddress | undefined;
    fixture.componentInstance.submitted.subscribe((a) => (emitted = a));

    fill(fixture, 'street', '12 Pine Rd');
    fill(fixture, 'city', 'Boulder');
    fill(fixture, 'postalCode', '80302');
    submit(fixture);

    expect(emitted).toEqual(address);
  });

  it('restores a previously entered address when coming back to edit it', () => {
    const fixture = setup('Someone Else', address);
    const input = (fixture.nativeElement as HTMLElement).querySelector('[formControlName="street"]') as HTMLInputElement;
    expect(input.value).toBe('12 Pine Rd');
    const name = (fixture.nativeElement as HTMLElement).querySelector('[formControlName="fullName"]') as HTMLInputElement;
    expect(name.value).toBe('Sam Shopper');
  });
});
