import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../../api';

@Component({
  standalone: true,
  imports: [CommonModule],
  template: `
  <section class="hero">
    <p class="eyebrow">Corporate connections control</p>
    <h1>Billing and recovery, with evidence.</h1>
    <p>Live figures from Dialog ERP. No hard-coded operational totals.</p>

    <p *ngIf="loading">Loading current data…</p>
    <p *ngIf="error" class="error">{{error}}</p>

    <div class="cards" *ngIf="data && !loading">
      <article><b>{{data.billingLines || 0}}</b><span>billing lines in latest cycle</span></article>
      <article><b>{{ (data.grossBill || 0) | number:'1.2-2' }}</b><span>current bill (LKR)</span></article>
      <article><b>{{data.unmatched || 0}}</b><span>unmatched billing numbers</span></article>
      <article><b>{{data.needsReview || 0}}</b><span>recoveries needing review</span></article>
      <article><b>{{data.connections || 0}}</b><span>master connections</span></article>
      <article><b>{{data.openQualityIssues || 0}}</b><span>open quality issues</span></article>
    </div>

    <p *ngIf="data?.latestBatch">
      Latest period: <b>{{data.latestBatch.period_end || 'Not set'}}</b>
      · Contract: <b>{{data.latestBatch.contract_no || 'Not set'}}</b>
    </p>
    <p *ngIf="data && !data.latestBatch">No billing cycle has been imported yet.</p>
  </section>`
})
export class DashboardComponent implements OnInit {
  data: any;
  loading = true;
  error = '';

  constructor(private http: HttpClient) {}

  ngOnInit() { this.load(); }

  load() {
    this.loading = true;
    this.error = '';
    this.http.get(`${API_BASE}/reports/dashboard`).subscribe({
      next: x => { this.data = x; this.loading = false; },
      error: e => { this.error = e?.error?.message || 'Unable to load dashboard.'; this.loading = false; }
    });
  }
}
