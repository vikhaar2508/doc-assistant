import { Component, signal, ViewChild, ElementRef, afterNextRender, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule } from '@angular/common/http';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  isLoading?: boolean;
  isStreaming?: boolean;
  followUps?: string[];
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  confidenceReason?: string;
  gaps?: string[];
  feedback?: 'positive' | 'negative';
  question?: string;
  copied?: boolean;
}

interface Conversation {
  id: string;
  title: string;
  updated_at: string;
  message_count: number;
}

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, HttpClientModule],
  template: `
    <div class="shell">
      <!-- Sidebar -->
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-icon">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M12 2L2 7l10 5 10-5-10-5z"/>
              <path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/>
            </svg>
          </div>
          <span class="brand-name">DocAssistant</span>
        </div>

        <!-- Tab switcher -->
        <div class="tab-switcher">
          <button class="tab-btn" [class.active]="sidebarTab() === 'chats'" (click)="sidebarTab.set('chats')">
            💬 Chats
          </button>
          <button class="tab-btn" [class.active]="sidebarTab() === 'admin'" (click)="loadAdmin()">
            📊 Admin
          </button>
        </div>

        <!-- Chats tab -->
        @if (sidebarTab() === 'chats') {
          <button class="new-chat-btn" (click)="newConversation()">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            New conversation
          </button>

          <div class="conv-list">
            @for (conv of conversations(); track conv.id) {
              <div class="conv-item" [class.active]="conv.id === conversationId"
                   (click)="loadConversation(conv.id)">
                <span class="conv-title">{{ conv.title || 'New conversation' }}</span>
                <button class="conv-delete" (click)="deleteConversation(conv.id, $event)">×</button>
              </div>
            }
          </div>

          <div class="sidebar-section">
            <span class="sidebar-label">Knowledge Base</span>
            <button class="ingest-btn" (click)="triggerIngest('full')" [disabled]="ingesting()">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="1 4 1 10 7 10"/><polyline points="23 20 23 14 17 14"/>
                <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"/>
              </svg>
              {{ ingesting() ? 'Syncing...' : 'Full Sync' }}
            </button>
            <button class="ingest-btn incremental-btn" (click)="triggerIngest('incremental')" [disabled]="ingesting()">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83"/>
              </svg>
              {{ ingesting() ? 'Syncing...' : 'Incremental Sync' }}
            </button>
            @if (ingestMessage()) {
              <p class="ingest-msg">{{ ingestMessage() }}</p>
            }
          </div>

          <div class="sidebar-section sidebar-footer">
            <div class="status-dot" [class.active]="isOnline()"></div>
            <span class="status-text">{{ isOnline() ? 'Claude connected' : 'Offline' }}</span>
          </div>
        }

        <!-- Admin tab -->
        @if (sidebarTab() === 'admin') {
          <div class="admin-panel">
            @if (adminData()) {
              <div class="admin-stat-row">
                <div class="admin-stat">
                  <span class="stat-num">{{ adminData()!.summary['total'] }}</span>
                  <span class="stat-label">Total queries</span>
                </div>
                <div class="admin-stat">
                  <span class="stat-num green">{{ adminData()!.summary['positive_rate'] }}%</span>
                  <span class="stat-label">Positive rate</span>
                </div>
              </div>

              <div class="admin-section">
                <span class="sidebar-label">⚠️ Failing Questions</span>
                @for (q of adminData()!.failingQuestions.slice(0,5); track q['question']) {
                  <div class="admin-item fail">
                    <span class="admin-q">{{ q['question'] }}</span>
                    <span class="admin-count">{{ q['attempts'] }}x</span>
                  </div>
                }
              </div>

              <div class="admin-section">
                <span class="sidebar-label">🔥 Top Questions</span>
                @for (q of adminData()!.topQuestions.slice(0,5); track q['question']) {
                  <div class="admin-item">
                    <span class="admin-q">{{ q['question'] }}</span>
                    <span class="admin-count">{{ q['count'] }}x</span>
                  </div>
                }
              </div>
            } @else {
              <div class="admin-loading">Loading dashboard...</div>
            }
          </div>
        }
      </aside>

      <!-- Main chat -->
      <main class="chat-main">
        <header class="chat-header">
          <h1>Confluence Knowledge Base</h1>
          <p class="header-sub">Ask anything about your internal documentation</p>
        </header>

        <div class="messages-area" #messagesArea>
          @if (messages().length === 0) {
            <div class="empty-state">
              <div class="empty-icon">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                </svg>
              </div>
              <h2>Ask your docs anything</h2>
              <p>Your Confluence knowledge base is ready.</p>
              <div class="suggestions">
                @for (s of suggestions; track s) {
                  <button class="suggestion-chip" (click)="sendSuggestion(s)">{{ s }}</button>
                }
              </div>
            </div>
          }

          @for (msg of messages(); track msg.id) {
            <div class="message-row" [class.user-row]="msg.role === 'user'">
              <div class="message-avatar" [class.user-avatar]="msg.role === 'user'">
                @if (msg.role === 'assistant') {
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M12 2L2 7l10 5 10-5-10-5z"/>
                    <path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/>
                  </svg>
                } @else {
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                    <circle cx="12" cy="7" r="4"/>
                  </svg>
                }
              </div>
              <div class="message-bubble-wrap">
                <div class="message-bubble" [class.user-bubble]="msg.role === 'user'">
                  @if (msg.isLoading) {
                    <div class="typing-dots"><span></span><span></span><span></span></div>
                  } @else {
                    <div class="message-content" [innerHTML]="formatMessage(msg.content)"></div>
                    @if (msg.isStreaming) {
                      <span class="cursor-blink">▋</span>
                    }
                    <div class="message-time">{{ msg.timestamp | date:'HH:mm' }}</div>
                  }
                </div>

                <!-- Meta row: confidence + feedback + copy -->
                @if (!msg.isLoading && !msg.isStreaming && msg.role === 'assistant' && msg.confidence) {
                  <div class="meta-row">
                    <span class="confidence-badge" [class]="'conf-' + msg.confidence?.toLowerCase()">
                      {{ msg.confidence === 'HIGH' ? '🟢' : msg.confidence === 'MEDIUM' ? '🟡' : '🔴' }}
                      {{ msg.confidence }}
                      @if (msg.confidenceReason) {
                        <span class="conf-reason"> — {{ msg.confidenceReason }}</span>
                      }
                    </span>
                    <div class="action-btns">
                      <!-- Copy button -->
                      <button class="action-btn copy-btn" (click)="copyAnswer(msg)"
                              [title]="msg.copied ? 'Copied!' : 'Copy answer'">
                        {{ msg.copied ? '✓' : '⎘' }}
                      </button>
                      <!-- Feedback buttons -->
                      <button class="action-btn fb-btn"
                              [class.active-positive]="msg.feedback === 'positive'"
                              [disabled]="!!msg.feedback"
                              (click)="submitFeedback(msg, 'positive')"
                              title="Helpful">👍</button>
                      <button class="action-btn fb-btn"
                              [class.active-negative]="msg.feedback === 'negative'"
                              [disabled]="!!msg.feedback"
                              (click)="submitFeedback(msg, 'negative')"
                              title="Not helpful">👎</button>
                    </div>
                  </div>
                }

                <!-- Gaps -->
                @if (msg.gaps && msg.gaps.length > 0 && !msg.isStreaming && !msg.isLoading) {
                  <div class="gaps-section">
                    <span class="gaps-label">⚠️ Documentation gaps:</span>
                    @for (gap of msg.gaps; track gap) {
                      <div class="gap-item">{{ gap }}</div>
                    }
                  </div>
                }

                <!-- Follow-ups -->
                @if (msg.followUps && msg.followUps.length > 0 && !msg.isStreaming && !msg.isLoading) {
                  <div class="follow-ups">
                    <span class="follow-ups-label">💡 You might also ask:</span>
                    @for (fu of msg.followUps; track fu) {
                      <button class="follow-up-chip" (click)="sendSuggestion(fu)">{{ fu }}</button>
                    }
                  </div>
                }
              </div>
            </div>
          }
        </div>

        <!-- Input -->
        <div class="input-area">
          <div class="input-wrapper">
            <textarea #inputField [(ngModel)]="userInput"
                      (keydown.enter)="onEnter($event)"
                      placeholder="Ask about your documentation..."
                      rows="1" [disabled]="loading()" class="chat-input"></textarea>
            <button class="send-btn" (click)="sendMessage()" [disabled]="!userInput.trim() || loading()">
              @if (loading()) {
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="spin">
                  <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
                </svg>
              } @else {
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="22" y1="2" x2="11" y2="13"/>
                  <polygon points="22 2 15 22 11 13 2 9 22 2"/>
                </svg>
              }
            </button>
          </div>
          <p class="input-hint">Press Enter to send · Shift+Enter for new line</p>
        </div>
      </main>
    </div>
  `,
  styles: [`
    @import url('https://fonts.googleapis.com/css2?family=Sora:wght@300;400;500;600&family=JetBrains+Mono:wght@400;500&display=swap');
    :host { display: block; height: 100vh; font-family: 'Sora', sans-serif; }
    .shell { display: flex; height: 100vh; background: #0a0a0f; color: #e8e8f0; }

    /* Sidebar */
    .sidebar { width: 260px; min-width: 260px; background: #0f0f1a; border-right: 1px solid rgba(255,255,255,0.06); display: flex; flex-direction: column; padding: 20px 12px; gap: 6px; overflow-y: auto; }
    .brand { display: flex; align-items: center; gap: 10px; padding: 0 8px 16px; border-bottom: 1px solid rgba(255,255,255,0.06); margin-bottom: 4px; }
    .brand-icon { width: 34px; height: 34px; border-radius: 10px; background: linear-gradient(135deg, #5b6af0, #8b5cf6); display: flex; align-items: center; justify-content: center; color: white; }
    .brand-name { font-size: 15px; font-weight: 600; color: #f0f0fa; }

    /* Tab switcher */
    .tab-switcher { display: flex; gap: 4px; background: rgba(255,255,255,0.04); border-radius: 10px; padding: 3px; margin-bottom: 4px; }
    .tab-btn { flex: 1; padding: 7px; border: none; background: transparent; color: #606080; font-family: 'Sora', sans-serif; font-size: 11px; font-weight: 500; border-radius: 8px; cursor: pointer; transition: all 0.2s; }
    .tab-btn.active { background: rgba(91,106,240,0.2); color: #a0b0ff; }

    /* Conversation list */
    .new-chat-btn { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border-radius: 10px; border: 1px solid rgba(255,255,255,0.1); background: transparent; color: #c0c0d8; font-family: 'Sora', sans-serif; font-size: 12px; font-weight: 500; cursor: pointer; transition: all 0.2s; width: 100%; margin-bottom: 4px; }
    .new-chat-btn:hover { background: rgba(91,106,240,0.15); border-color: rgba(91,106,240,0.4); }
    .conv-list { display: flex; flex-direction: column; gap: 2px; max-height: 200px; overflow-y: auto; margin-bottom: 8px; }
    .conv-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer; transition: all 0.2s; }
    .conv-item:hover { background: rgba(255,255,255,0.05); }
    .conv-item.active { background: rgba(91,106,240,0.15); }
    .conv-title { flex: 1; font-size: 11px; color: #9090b8; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .conv-delete { background: none; border: none; color: #555570; cursor: pointer; font-size: 16px; padding: 0 2px; line-height: 1; opacity: 0; transition: opacity 0.2s; }
    .conv-item:hover .conv-delete { opacity: 1; }

    /* Sidebar sections */
    .sidebar-section { margin-top: 12px; display: flex; flex-direction: column; gap: 5px; }
    .sidebar-label { font-size: 10px; font-weight: 600; letter-spacing: 1.2px; text-transform: uppercase; color: #555570; padding: 0 6px; }
    .ingest-btn { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 10px; border: 1px solid rgba(255,255,255,0.08); background: transparent; color: #9090b8; font-family: 'Sora', sans-serif; font-size: 11px; cursor: pointer; transition: all 0.2s; width: 100%; }
    .ingest-btn:hover:not(:disabled) { background: rgba(91,106,240,0.1); color: #c0c0e0; }
    .incremental-btn { border-color: rgba(74,222,128,0.2); color: #4a9070; }
    .incremental-btn:hover:not(:disabled) { background: rgba(74,222,128,0.08); color: #4ade80; }
    .ingest-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .ingest-msg { font-size: 10px; color: #6b6b90; padding: 0 6px; margin: 0; }
    .sidebar-footer { margin-top: auto; flex-direction: row; align-items: center; }
    .status-dot { width: 7px; height: 7px; border-radius: 50%; background: #444; margin: 0 4px 0 6px; }
    .status-dot.active { background: #4ade80; box-shadow: 0 0 6px rgba(74,222,128,0.5); }
    .status-text { font-size: 11px; color: #555570; }

    /* Admin panel */
    .admin-panel { display: flex; flex-direction: column; gap: 12px; padding: 4px 0; }
    .admin-loading { font-size: 12px; color: #555570; padding: 16px 8px; text-align: center; }
    .admin-stat-row { display: flex; gap: 8px; }
    .admin-stat { flex: 1; background: rgba(255,255,255,0.04); border-radius: 10px; padding: 10px; text-align: center; }
    .stat-num { display: block; font-size: 22px; font-weight: 600; color: #d0d0e8; }
    .stat-num.green { color: #4ade80; }
    .stat-label { font-size: 10px; color: #555570; }
    .admin-section { display: flex; flex-direction: column; gap: 4px; }
    .admin-item { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 6px; background: rgba(255,255,255,0.03); }
    .admin-item.fail { background: rgba(248,113,113,0.06); }
    .admin-q { flex: 1; font-size: 10px; color: #8080a0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .admin-count { font-size: 10px; color: #555570; font-weight: 600; }

    /* Main */
    .chat-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
    .chat-header { padding: 18px 28px; border-bottom: 1px solid rgba(255,255,255,0.05); background: rgba(10,10,15,0.8); backdrop-filter: blur(10px); }
    .chat-header h1 { font-size: 18px; font-weight: 600; color: #f0f0fa; margin: 0 0 2px; }
    .header-sub { font-size: 12px; color: #555570; margin: 0; }

    /* Messages */
    .messages-area { flex: 1; overflow-y: auto; padding: 28px; display: flex; flex-direction: column; gap: 24px; scrollbar-width: thin; scrollbar-color: rgba(255,255,255,0.08) transparent; }
    .empty-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; gap: 16px; }
    .empty-icon { width: 72px; height: 72px; border-radius: 20px; background: linear-gradient(135deg, rgba(91,106,240,0.2), rgba(139,92,246,0.2)); border: 1px solid rgba(91,106,240,0.2); display: flex; align-items: center; justify-content: center; color: #7080f0; }
    .empty-state h2 { font-size: 22px; font-weight: 600; color: #d0d0e8; margin: 0; }
    .empty-state p { font-size: 14px; color: #555570; margin: 0; }
    .suggestions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
    .suggestion-chip { padding: 8px 16px; border-radius: 20px; border: 1px solid rgba(91,106,240,0.25); background: rgba(91,106,240,0.08); color: #8090e0; font-family: 'Sora', sans-serif; font-size: 12px; cursor: pointer; transition: all 0.2s; }
    .suggestion-chip:hover { background: rgba(91,106,240,0.18); color: #b0c0f8; }

    /* Message rows */
    .message-row { display: flex; gap: 14px; align-items: flex-start; animation: fadeSlideIn 0.3s ease forwards; }
    .user-row { flex-direction: row-reverse; }
    @keyframes fadeSlideIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
    .message-avatar { width: 34px; height: 34px; min-width: 34px; border-radius: 10px; background: linear-gradient(135deg, #5b6af0, #8b5cf6); display: flex; align-items: center; justify-content: center; color: white; }
    .user-avatar { background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1); color: #9090b8; }
    .message-bubble-wrap { display: flex; flex-direction: column; gap: 6px; max-width: 70%; }
    .message-bubble { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.07); border-radius: 16px; border-top-left-radius: 4px; padding: 14px 18px; }
    .user-bubble { background: rgba(91,106,240,0.15); border-color: rgba(91,106,240,0.25); border-radius: 16px; border-top-right-radius: 4px; }
    .message-content { font-size: 14px; line-height: 1.7; color: #d0d0e8; white-space: pre-wrap; }
    .message-time { font-size: 10px; color: #444460; margin-top: 8px; }
    .cursor-blink { animation: blink 1s infinite; color: #5b6af0; }
    @keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0; } }

    /* Meta row */
    .meta-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .confidence-badge { font-size: 11px; padding: 3px 10px; border-radius: 20px; font-weight: 500; }
    .conf-high { background: rgba(74,222,128,0.1); color: #4ade80; border: 1px solid rgba(74,222,128,0.2); }
    .conf-medium { background: rgba(251,191,36,0.1); color: #fbbf24; border: 1px solid rgba(251,191,36,0.2); }
    .conf-low { background: rgba(248,113,113,0.1); color: #f87171; border: 1px solid rgba(248,113,113,0.2); }
    .conf-reason { font-weight: 400; opacity: 0.8; }
    .action-btns { display: flex; gap: 4px; margin-left: auto; }
    .action-btn { background: none; border: 1px solid rgba(255,255,255,0.1); border-radius: 6px; padding: 3px 8px; cursor: pointer; font-size: 13px; transition: all 0.2s; opacity: 0.6; color: #9090b8; }
    .action-btn:hover:not(:disabled) { opacity: 1; background: rgba(255,255,255,0.08); }
    .action-btn:disabled { cursor: default; }
    .copy-btn { font-family: monospace; }
    .active-positive { opacity: 1 !important; background: rgba(74,222,128,0.15) !important; border-color: rgba(74,222,128,0.3) !important; }
    .active-negative { opacity: 1 !important; background: rgba(248,113,113,0.15) !important; border-color: rgba(248,113,113,0.3) !important; }

    /* Gaps */
    .gaps-section { display: flex; flex-direction: column; gap: 4px; }
    .gaps-label { font-size: 11px; color: #fbbf24; font-weight: 500; }
    .gap-item { font-size: 12px; color: #9090b8; padding: 4px 10px; border-radius: 6px; background: rgba(251,191,36,0.06); border-left: 2px solid rgba(251,191,36,0.3); }

    /* Follow-ups */
    .follow-ups { display: flex; flex-direction: column; gap: 5px; }
    .follow-ups-label { font-size: 11px; color: #555570; }
    .follow-up-chip { padding: 7px 12px; border-radius: 10px; border: 1px solid rgba(91,106,240,0.2); background: rgba(91,106,240,0.06); color: #7080c0; font-family: 'Sora', sans-serif; font-size: 12px; cursor: pointer; transition: all 0.2s; text-align: left; }
    .follow-up-chip:hover { background: rgba(91,106,240,0.15); color: #a0b0e0; }

    /* Typing */
    .typing-dots { display: flex; gap: 5px; padding: 4px 0; }
    .typing-dots span { width: 7px; height: 7px; border-radius: 50%; background: #5b6af0; animation: bounce 1.2s infinite ease-in-out; }
    .typing-dots span:nth-child(2) { animation-delay: 0.2s; }
    .typing-dots span:nth-child(3) { animation-delay: 0.4s; }
    @keyframes bounce { 0%,80%,100% { transform: scale(0.6); opacity: 0.4; } 40% { transform: scale(1); opacity: 1; } }

    /* Input */
    .input-area { padding: 16px 28px 20px; border-top: 1px solid rgba(255,255,255,0.05); background: rgba(10,10,15,0.9); }
    .input-wrapper { display: flex; align-items: flex-end; gap: 12px; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; padding: 12px 14px; transition: border-color 0.2s; }
    .input-wrapper:focus-within { border-color: rgba(91,106,240,0.5); }
    .chat-input { flex: 1; background: transparent; border: none; outline: none; color: #e0e0f0; font-family: 'Sora', sans-serif; font-size: 14px; line-height: 1.5; resize: none; max-height: 160px; }
    .chat-input::placeholder { color: #404058; }
    .send-btn { width: 38px; height: 38px; min-width: 38px; border-radius: 10px; background: linear-gradient(135deg, #5b6af0, #8b5cf6); border: none; cursor: pointer; display: flex; align-items: center; justify-content: center; color: white; transition: all 0.2s; }
    .send-btn:hover:not(:disabled) { transform: scale(1.05); box-shadow: 0 4px 16px rgba(91,106,240,0.4); }
    .send-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .spin { animation: spin 1s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    .input-hint { font-size: 11px; color: #333350; margin: 8px 0 0 4px; }

    /* Confluence links */
    :host ::ng-deep .confluence-link { display: inline-flex; align-items: center; gap: 5px; color: #7b8cde; text-decoration: none; font-size: 12px; padding: 3px 10px; border-radius: 6px; border: 1px solid rgba(91,106,240,0.3); background: rgba(91,106,240,0.08); margin: 4px 2px; transition: all 0.2s; }
    :host ::ng-deep .confluence-link:hover { background: rgba(91,106,240,0.18); color: #a0b0ff; }
    :host ::ng-deep code { font-family: 'JetBrains Mono', monospace; font-size: 12px; background: rgba(0,0,0,0.3); padding: 2px 6px; border-radius: 4px; color: #a0c0ff; }
    :host ::ng-deep strong { color: #e0e0f8; font-weight: 600; }
  `]
})
export class ChatComponent {
  private http = inject(HttpClient);
  @ViewChild('messagesArea') messagesArea!: ElementRef;

  messages      = signal<Message[]>([]);
  conversations = signal<Conversation[]>([]);
  loading       = signal(false);
  ingesting     = signal(false);
  ingestMessage = signal('');
  isOnline      = signal(false);
  sidebarTab    = signal<'chats' | 'admin'>('chats');
  adminData     = signal<any>(null);
  userInput     = '';
  conversationId = this.newId();

  suggestions = [
    'What are the interfaces in Combined Advices?',
    'Which apps are up for migration?',
    'What is the LDAP URL for Elytron?',
    'How do I rollback a deployment?'
  ];

  private readonly API = '/api';

  constructor() {
    afterNextRender(() => {
      this.checkOllamaStatus();
      this.loadConversations();
    });
  }

  sendSuggestion(text: string) { this.userInput = text; this.sendMessage(); }

  onEnter(event: Event) {
    const ke = event as KeyboardEvent;
    if (!ke.shiftKey) { ke.preventDefault(); this.sendMessage(); }
  }

  sendMessage() {
    const text = this.userInput.trim();
    if (!text || this.loading()) return;
    this.userInput = '';

    this.messages.update(msgs => [...msgs, {
      id: this.newId(), role: 'user', content: text, timestamp: new Date()
    }]);

    const assistantId = this.newId();
    this.messages.update(msgs => [...msgs, {
      id: assistantId, role: 'assistant', content: '',
      timestamp: new Date(), isLoading: true
    }]);

    this.loading.set(true);
    this.scrollToBottom();

    this.http.post<any>(`${this.API}/chat`,
        { message: text, conversationId: this.conversationId }
    ).subscribe({
      next: (res) => {
        this.conversationId = res.conversationId;
        this.loading.set(false);
        this.animateText(assistantId, res.response, res.followUps || [],
            res.confidence || 'MEDIUM', res.confidenceReason || '',
            res.gaps || [], text);
        this.loadConversations();
      },
      error: () => {
        this.messages.update(msgs => msgs.map(m =>
            m.id === assistantId ? { ...m, isLoading: false,
              content: '⚠️ Could not reach the backend.' } : m
        ));
        this.loading.set(false);
      }
    });
  }

  private animateText(msgId: string, fullText: string, followUps: string[],
                      confidence: any = 'MEDIUM', confidenceReason = '',
                      gaps: string[] = [], question = '') {

    const words = fullText.split(' ');
    let current = ''; let i = 0;

    this.messages.update(msgs => msgs.map(m =>
        m.id === msgId ? { ...m, isLoading: false, isStreaming: true, content: '' } : m
    ));

    const interval = setInterval(() => {
      if (i >= words.length) {
        clearInterval(interval);
        this.messages.update(msgs => msgs.map(m =>
            m.id === msgId ? { ...m, isStreaming: false, content: fullText,
              followUps, confidence, confidenceReason, gaps, question } : m
        ));
        this.scrollToBottom();
        return;
      }
      const batch = Math.min(4, words.length - i);
      for (let b = 0; b < batch; b++) current += (current ? ' ' : '') + words[i++];
      this.messages.update(msgs => msgs.map(m =>
          m.id === msgId ? { ...m, content: current } : m
      ));
      this.scrollToBottom();
    }, 20);
  }

  newConversation() {
    this.conversationId = this.newId();
    this.messages.set([]);
  }

  loadConversations() {
    this.http.get<Conversation[]>(`${this.API}/history`).subscribe({
      next: (convs) => this.conversations.set(convs),
      error: () => {}
    });
  }

  loadConversation(id: string) {
    this.conversationId = id;
    this.http.get<any[]>(`${this.API}/history/${id}`).subscribe({
      next: (msgs) => {
        this.messages.set(msgs.map(m => ({
          id: this.newId(),
          role: m['role'],
          content: m['content'],
          timestamp: new Date(m['created_at']),
          confidence: m['confidence'],
          gaps: m['gaps'] ? m['gaps'].split('||') : []
        })));
        this.scrollToBottom();
      }
    });
  }

  deleteConversation(id: string, event: Event) {
    event.stopPropagation();
    this.http.delete(`${this.API}/history/${id}`).subscribe({
      next: () => {
        this.loadConversations();
        if (id === this.conversationId) this.newConversation();
      }
    });
  }

  loadAdmin() {
    this.sidebarTab.set('admin');
    this.http.get<any>(`${this.API}/admin/dashboard`).subscribe({
      next: (data) => this.adminData.set(data),
      error: () => {}
    });
  }

  copyAnswer(msg: Message) {
    const plainText = msg.content.replace(/<[^>]*>/g, '');
    navigator.clipboard.writeText(plainText).then(() => {
      this.messages.update(msgs => msgs.map(m =>
          m.id === msg.id ? { ...m, copied: true } : m
      ));
      setTimeout(() => {
        this.messages.update(msgs => msgs.map(m =>
            m.id === msg.id ? { ...m, copied: false } : m
        ));
      }, 2000);
    });
  }

  submitFeedback(msg: Message, rating: 'positive' | 'negative') {
    this.messages.update(msgs => msgs.map(m =>
        m.id === msg.id ? { ...m, feedback: rating } : m
    ));
    this.http.post(`${this.API}/feedback`, {
      conversationId: this.conversationId,
      question: msg.question || '',
      answer: msg.content,
      rating, confidence: msg.confidence || 'MEDIUM', comment: ''
    }).subscribe({ error: (e) => console.error('Feedback failed', e) });
  }

  triggerIngest(type: 'full' | 'incremental') {
    this.ingesting.set(true);
    this.ingestMessage.set('');
    const endpoint = type === 'incremental'
        ? `${this.API}/ingest/confluence/incremental`
        : `${this.API}/ingest/confluence`;
    this.http.post<any>(endpoint, {}).subscribe({
      next: (res) => {
        this.ingestMessage.set(type === 'incremental'
            ? `✓ +${res.added} new, ~${res.updated} updated`
            : `✓ ${res.chunksStored} chunks synced`);
        this.ingesting.set(false);
      },
      error: () => { this.ingestMessage.set('✗ Sync failed'); this.ingesting.set(false); }
    });
  }

  formatMessage(content: string): string {
    return content
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g,
            '<a href="$2" target="_blank" class="confluence-link">📄 $1</a>')
        .replace(/(?<![("'])https:\/\/[a-z0-9-]+\.atlassian\.net\/[^\s<)"]+/g,
            (url) => `<a href="${url}" target="_blank" class="confluence-link">📄 View in Confluence</a>`)
        .replace(/\n/g, '<br>');
  }

  private scrollToBottom() {
    setTimeout(() => {
      const el = this.messagesArea?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 50);
  }

  private checkOllamaStatus() {
    this.http.get<{status: string}>(`${this.API}/health/ollama`).subscribe({
      next: (res) => this.isOnline.set(res.status === 'online'),
      error: () => this.isOnline.set(false)
    });
  }

  private newId() { return Math.random().toString(36).substring(2); }
}