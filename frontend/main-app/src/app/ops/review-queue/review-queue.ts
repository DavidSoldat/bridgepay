import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { Applications } from '../applications';
import { ApplicationResponse } from '../../shared/models/application';

@Component({
  selector: 'app-review-queue',
  imports: [RouterLink, DatePipe, DecimalPipe],
  templateUrl: './review-queue.html',
  styleUrl: './review-queue.css',
})
export class ReviewQueue {
  private readonly applications = inject(Applications);

  protected readonly rows = toSignal(
    this.applications.listManualReview().pipe(map((page) => page.content)),
    { initialValue: [] as ApplicationResponse[] },
  );
}
