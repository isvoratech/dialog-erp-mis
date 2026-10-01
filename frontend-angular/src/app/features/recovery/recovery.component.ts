import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../../api';

@Component({
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <section class="panel">
    <p class="eyebrow">Controlled review</p>
    <h2>Recovery queue</h2>
    <p>Every approval or rejection requires a reviewer reason. Optional overrides are audited.</p>
    <button (click)="load()" [disabled]="loading">{{loading ? 'Loading…' : 'Refresh queue'}}</button>
    <p *ngIf="message">{{message}}</p>

    <div *ngFor="let r of rows" class="review-card">
      <div><b>{{r.mobile_display}}</b> · {{r.match_status}} · {{r.approval_status}}</div>
      <div>Bill: {{r.source_bill | number:'1.2-2'}} LKR</div>
      <div>Calculated recovery: {{r.recovery_amount | number:'1.2-2'}} LKR</div>
      <div *ngIf="r.review_note">Reason: {{r.review_note}}</div>

      <label>Reviewer note</label>
      <textarea [(ngModel)]="r._note" rows="2"></textarea>

      <label>Recovery override (optional)</label>
      <input [(ngModel)]="r._recoveryOverride" inputmode="decimal">

      <label>Company payable override (optional)</label>
      <input [(ngModel)]="r._companyPayableOverride" inputmode="decimal">

      <div class="actions">
        <button (click)="decide(r,'APPROVED')" [disabled]="r._saving">Approve</button>
        <button (click)="decide(r,'REJECTED')" [disabled]="r._saving">Reject</button>
      </div>
      <p *ngIf="r._error" class="error">{{r._error}}</p>
    </div>

    <p *ngIf="loaded && !rows.length">No pending exceptions.</p>
  </section>`
})
export class RecoveryComponent {
  rows: any[] = [];
  loaded = false;
  loading = false;
  message = '';

  constructor(private http: HttpClient) {}

  load() {
    this.loading = true;
    this.message = '';
    this.http.get<any[]>(`${API_BASE}/recovery/review`).subscribe({
      next: x => { this.rows = x; this.loaded = true; this.loading = false; },
      error: e => { this.message = e?.error?.message || 'Unable to load recovery queue.'; this.loading = false; }
    });
  }

  decide(row: any, decision: string) {
    if ((decision === 'APPROVED' || decision === 'REJECTED') && !String(row._note || '').trim()) {
      row._error = 'A reviewer note is required.';
      return;
    }
    row._error = '';
    row._saving = true;
    const payload = {
      decision,
      note: row._note || '',
      recoveryOverride: row._recoveryOverride || null,
      companyPayableOverride: row._companyPayableOverride || null
    };
    this.http.patch(`${API_BASE}/recovery/${row.id}`, payload).subscribe({
      next: () => { row._saving = false; this.load(); },
      error: e => { row._saving = false; row._error = e?.error?.message || 'Decision failed.'; }
    });
  }
}
