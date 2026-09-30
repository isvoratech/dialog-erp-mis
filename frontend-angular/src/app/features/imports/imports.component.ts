import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../../api';
@Component({standalone:true,imports:[FormsModule,CommonModule],template:`<section class="panel"><p class="eyebrow">Billing imports</p><h2>Import a workbook</h2><p>Use the original workbook as source evidence. The API hashes the file and rejects duplicate imports for the same cycle.</p><input type="file" accept=".xlsx" (change)="file=$any($event.target).files[0]"><input [(ngModel)]="contract" placeholder="Contract number"><input [(ngModel)]="period" type="date"><button (click)="upload()" [disabled]="!file">Upload and reconcile</button><pre *ngIf="result">{{result|json}}</pre></section>`})
export class ImportsComponent {file!:File; contract='';period='';result:any;constructor(private http:HttpClient){} upload(){const f=new FormData();f.append('file',this.file);if(this.contract)f.append('contractNo',this.contract);if(this.period)f.append('periodEnd',this.period);this.http.post(`${API_BASE}/imports/workbook`,f).subscribe(x=>this.result=x);}}
