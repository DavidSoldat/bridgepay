import { Component, OnInit, inject, input, output } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Auth } from '../../core/auth';

export interface DeliveryAddress {
  fullName: string;
  street: string;
  city: string;
  postalCode: string;
}

// Only shown back on the review step: the demo store has no order backend to send it to.
@Component({
  selector: 'app-delivery-form',
  imports: [ReactiveFormsModule],
  templateUrl: './delivery-form.html',
})
export class DeliveryForm implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(Auth);

  address = input<DeliveryAddress | null>(null);
  submitted = output<DeliveryAddress>();

  readonly form = this.fb.nonNullable.group({
    fullName: ['', Validators.required],
    street: ['', Validators.required],
    city: ['', Validators.required],
    postalCode: ['', Validators.required],
  });

  ngOnInit(): void {
    this.form.patchValue(this.address() ?? { fullName: this.auth.fullName() });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit(this.form.getRawValue());
  }
}
