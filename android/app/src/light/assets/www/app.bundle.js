// Phone Buddy Light — Smart Personal Assistant (ChatGPT Style)
// Embedded on-device AI Edition (v0.8.0-light)

(function () {
  const APP_VERSION = 'v0.8.0-light';
  const STORAGE_KEY = 'phone_buddy_light_state_v080';
  const IS_ANDROID_APK = typeof window.AndroidBuddy !== 'undefined';

  const DEFAULT_STATE = {
    settings: {
      spokenVoice: false,
      speechRate: 1.05,
      speechPitch: 1.0,
      tone: 'concise' // 'concise' or 'detailed'
    },
    profile: {
      name: 'Mike',
      context: 'Schedule: 2:30 PM project review (45m), 25m transit, 4:00 PM team call. Bilingual EN/RU.'
    }
  };

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return JSON.parse(JSON.stringify(DEFAULT_STATE));
      const p = JSON.parse(raw);
      return {
        ...JSON.parse(JSON.stringify(DEFAULT_STATE)),
        ...p,
        settings: { ...DEFAULT_STATE.settings, ...(p.settings || {}) },
        profile: { ...DEFAULT_STATE.profile, ...(p.profile || {}) }
      };
    } catch {
      return JSON.parse(JSON.stringify(DEFAULT_STATE));
    }
  }

  let state = loadState();
  let history = [];
  let isGenerating = false;
  let activeTurnId = null;
  let alreadySpokenLen = 0;
  let micActive = false;

  let nativeStatus = {
    ready: false,
    loading: false,
    downloading: false,
    progressPct: 0,
    downloadedMb: 0,
    totalMb: 1189,
    error: ''
  };

  function saveState() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch {}
  }

  function escapeHtml(str) {
    return (str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function renderMarkdown(raw) {
    if (!raw) return '';
    let txt = raw.trim();

    // Strip out <think>...</think> if present
    const openThink = txt.indexOf('<think>');
    if (openThink !== -1) {
      const closeThink = txt.indexOf('</think>');
      txt = closeThink === -1
        ? txt.slice(0, openThink).trim()
        : (txt.slice(0, openThink) + ' ' + txt.slice(closeThink + 8)).trim();
    }
    txt = txt.replace(/<\|[^|]+\|>/g, '');

    // Code blocks ```code```
    const codeBlocks = [];
    txt = txt.replace(/```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
      const idx = codeBlocks.length;
      codeBlocks.push(`<pre><code>${escapeHtml(code.trim())}</code></pre>`);
      return `@@CODE_BLOCK_${idx}@@`;
    });

    // Inline code `code`
    txt = txt.replace(/`([^`]+)`/g, (_, c) => `<code>${escapeHtml(c)}</code>`);

    // Bold **text**
    txt = txt.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

    // Bullet lists
    const lines = txt.split('\n');
    let inUl = false;
    let inOl = false;
    const out = [];

    for (let line of lines) {
      const bulletMatch = line.match(/^[\*\-]\s+(.*)$/);
      const numMatch = line.match(/^\d+\.\s+(.*)$/);

      if (bulletMatch) {
        if (inOl) { out.push('</ol>'); inOl = false; }
        if (!inUl) { out.push('<ul>'); inUl = true; }
        out.push(`<li>${bulletMatch[1]}</li>`);
      } else if (numMatch) {
        if (inUl) { out.push('</ul>'); inUl = false; }
        if (!inOl) { out.push('<ol>'); inOl = true; }
        out.push(`<li>${numMatch[1]}</li>`);
      } else {
        if (inUl) { out.push('</ul>'); inUl = false; }
        if (inOl) { out.push('</ol>'); inOl = false; }
        if (line.trim().length > 0) {
          out.push(`<p>${line}</p>`);
        }
      }
    }
    if (inUl) out.push('</ul>');
    if (inOl) out.push('</ol>');

    let html = out.join('');
    // Restore code blocks
    codeBlocks.forEach((block, i) => {
      html = html.replace(`@@CODE_BLOCK_${i}@@`, block);
    });

    return html || `<p>${escapeHtml(txt)}</p>`;
  }

  function updateStatusBadge() {
    const badge = document.getElementById('engineStatusBadge');
    if (!badge) return;
    if (nativeStatus.ready) {
      badge.textContent = 'On-Device • Ready';
      badge.classList.add('ready');
    } else if (nativeStatus.downloading) {
      badge.textContent = `Downloading ${nativeStatus.progressPct}%`;
      badge.classList.remove('ready');
    } else if (nativeStatus.loading) {
      badge.textContent = 'Starting Engine...';
      badge.classList.remove('ready');
    } else if (nativeStatus.error) {
      badge.textContent = 'Setup Needed';
      badge.classList.remove('ready');
    } else {
      badge.textContent = 'Embedded AI';
      badge.classList.remove('ready');
    }
  }

  function renderTranscript() {
    const box = document.getElementById('chatThread');
    if (!box) return;

    if (!history.length) {
      if (IS_ANDROID_APK && !nativeStatus.ready) {
        let cardBody = '';
        if (nativeStatus.downloading) {
          cardBody = `
            <div class="dl-title">Downloading Offline Brain (${nativeStatus.progressPct}%)</div>
            <div class="dl-sub">${nativeStatus.downloadedMb} / ${nativeStatus.totalMb} MB • One-time setup</div>
            <div class="dl-bar-track"><div class="dl-bar-fill" style="width:${nativeStatus.progressPct}%"></div></div>
          `;
        } else if (nativeStatus.loading) {
          cardBody = `
            <div class="dl-title">Loading On-Device Engine...</div>
            <div class="dl-sub">Initializing ARM64 llama.cpp on Galaxy S24</div>
          `;
        } else {
          const errLine = nativeStatus.error ? `<div class="dl-sub" style="color:#f87171;margin-bottom:8px">${escapeHtml(nativeStatus.error)}</div>` : '';
          cardBody = `
            <div class="dl-title">Embedded Assistant Brain (1.2 GB)</div>
            <div class="dl-sub" style="margin-bottom:12px">Downloads once to device storage. Runs 100% locally and privately.</div>
            ${errLine}
            <button class="dl-btn" id="startDlBtn">${nativeStatus.error ? 'Resume Download' : 'Download Assistant Model'}</button>
          `;
        }

        box.innerHTML = `
          <div class="empty-canvas">
            <div class="assistant-logo">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="12" y1="8" x2="12" y2="12"></line>
                <line x1="12" y1="16" x2="12.01" y2="16"></line>
              </svg>
            </div>
            <div class="dl-card">${cardBody}</div>
          </div>
        `;
        const dlBtn = document.getElementById('startDlBtn');
        if (dlBtn) dlBtn.onclick = () => window.AndroidBuddy.startModelDownload();
        return;
      }

      box.innerHTML = `
        <div class="empty-canvas">
          <div class="assistant-logo">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm0 18a8 8 0 1 1 8-8 8 8 0 0 1-8 8z"/>
              <circle cx="12" cy="12" r="3"/>
            </svg>
          </div>
          <div class="empty-title">How can I help you today?</div>
          <div class="prompt-chips">
            <button class="chip-btn" data-p="Plan my schedule: I have a 2:30 PM review (45m), 25m transit, and a 4:00 PM team call. Walk me through the timeline.">
              <span>📅</span> <span>Plan schedule &amp; time</span>
            </button>
            <button class="chip-btn" data-p="Draft a crisp, professional message to my team summarizing our next milestone delivery.">
              <span>✍️</span> <span>Draft a clear message</span>
            </button>
            <button class="chip-btn" data-p="Calculate: 2:30 PM + 45 min + 25 min travel. What exact time do I arrive, and do I make my 4:00 PM call?">
              <span>🧮</span> <span>Calculate time &amp; math</span>
            </button>
            <button class="chip-btn" data-p="Give me 3 practical, high-impact ideas to optimize my daily workflow on my Galaxy S24.">
              <span>💡</span> <span>Brainstorm action items</span>
            </button>
          </div>
        </div>
      `;

      box.querySelectorAll('.chip-btn').forEach((btn) => {
        btn.onclick = () => {
          const prompt = btn.getAttribute('data-p');
          if (prompt) submitMessage(prompt);
        };
      });
      return;
    }

    box.innerHTML = history
      .map((t) => {
        if (t.role === 'user') {
          return `<div class="msg-row user"><div class="user-bubble">${escapeHtml(t.content)}</div></div>`;
        }
        const inner = t.content
          ? `<div class="assistant-body">${renderMarkdown(t.content)}</div>`
          : `<div class="assistant-body"><div class="typing-dots"><span></span><span></span><span></span></div></div>`;
        return `<div class="msg-row assistant">${inner}</div>`;
      })
      .join('');
    box.scrollTop = box.scrollHeight;
  }

  function updateComposerButtons() {
    const sendBtn = document.getElementById('sendBtn');
    const sendIcon = document.getElementById('sendIcon');
    const stopIcon = document.getElementById('stopIcon');
    const inp = document.getElementById('textInput');

    if (!sendBtn || !sendIcon || !stopIcon || !inp) return;

    if (isGenerating) {
      sendBtn.disabled = false;
      sendBtn.title = 'Stop response';
      sendIcon.style.display = 'none';
      stopIcon.style.display = 'block';
    } else {
      sendBtn.disabled = inp.value.trim().length === 0;
      sendBtn.title = 'Send';
      sendIcon.style.display = 'block';
      stopIcon.style.display = 'none';
    }
  }

  function speakClauseIfEnabled(text) {
    if (!state.settings.spokenVoice) return;
    const clean = text.replace(/[*_#`~]/g, '').trim();
    if (!clean) return;
    if (IS_ANDROID_APK) {
      try {
        window.AndroidBuddy.speakText(clean, state.settings.speechPitch, state.settings.speechRate);
      } catch {}
    } else if ('speechSynthesis' in window) {
      const u = new SpeechSynthesisUtterance(clean);
      u.rate = state.settings.speechRate;
      u.pitch = state.settings.speechPitch;
      window.speechSynthesis.speak(u);
    }
  }

  function stopGeneration() {
    activeTurnId = null;
    isGenerating = false;
    if (IS_ANDROID_APK) {
      try {
        window.AndroidBuddy.stopGeneration();
        window.AndroidBuddy.stopTts();
      } catch {}
    } else {
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    }
    updateComposerButtons();
  }

  function submitMessage(text) {
    const clean = text.trim();
    if (!clean) return;

    if (isGenerating) stopGeneration();

    history.push({ role: 'user', content: clean });
    history.push({ role: 'assistant', content: '', raw: '', pending: true });
    const turnIdx = history.length - 1;
    const turnId = 't_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    activeTurnId = turnId;
    alreadySpokenLen = 0;
    isGenerating = true;

    const inp = document.getElementById('textInput');
    if (inp) {
      inp.value = '';
      inp.style.height = 'auto';
    }
    updateComposerButtons();
    renderTranscript();

    if (IS_ANDROID_APK) {
      const cleanHist = history
        .slice(0, turnIdx)
        .filter((m) => m.content && m.content.trim())
        .map((m) => ({ role: m.role, content: m.content }));

      window.AndroidBuddy.startChatTurn(
        turnId,
        'assistant',
        JSON.stringify({ tone: state.settings.tone }),
        JSON.stringify(cleanHist),
        state.profile.name || 'Mike',
        state.profile.context || ''
      );
    } else {
      // Mock / fallback response in browser preview
      setTimeout(() => {
        const dummyReply = `I am running in browser preview mode. Install the signed **Phone Buddy Light** APK on your phone for 100% on-device embedded inference.`;
        history[turnIdx].content = dummyReply;
        history[turnIdx].pending = false;
        isGenerating = false;
        activeTurnId = null;
        updateComposerButtons();
        renderTranscript();
      }, 500);
    }
  }

  function saveEntireConvo() {
    const saveBtn = document.getElementById('saveConvoBtn');
    const cleanTurns = history.filter((t) => t.content && t.content.trim());
    if (!cleanTurns.length) {
      saveBtn.textContent = 'Empty';
      setTimeout(() => { saveBtn.textContent = 'Save'; }, 1200);
      return;
    }

    const userLabel = state.profile.name || 'Mike';
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const slug = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    const filename = `convo_${slug}_v080_assistant.md`;

    const plainLines = cleanTurns.map((t) => `${t.role === 'user' ? userLabel : 'Buddy'}: ${t.content}`);
    const plainCopy = `[Phone Buddy Light ${APP_VERSION}]\n` + plainLines.join('\n');
    const mdText = [
      `# Saved Phone Buddy Light Convo — \`${APP_VERSION}\``,
      `- **Assistant Mode:** \`Personal Assistant (${state.settings.tone})\``,
      `- **Device:** \`Samsung Galaxy S24 (Embedded ARM64 JNI)\``,
      '',
      '---',
      '',
      ...cleanTurns.map((t) => `**${t.role === 'user' ? userLabel : 'Buddy'}:**\n${t.content}\n`)
    ].join('\n') + '\n';

    if (IS_ANDROID_APK) {
      window.AndroidBuddy.saveConvoToPhone(filename, mdText, plainCopy);
      saveBtn.textContent = 'Saved!';
      saveBtn.classList.add('saved');
      setTimeout(() => {
        saveBtn.textContent = 'Save';
        saveBtn.classList.remove('saved');
      }, 1500);
    } else {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(plainCopy);
        }
      } catch {}
      saveBtn.textContent = 'Copied!';
      setTimeout(() => { saveBtn.textContent = 'Save'; }, 1500);
    }
  }

  // Native Callbacks from Android Kotlin Bridge
  window.__onNativeModelStatus = function (statusObj) {
    nativeStatus = { ...nativeStatus, ...statusObj };
    updateStatusBadge();
    if (!history.length) renderTranscript();
  };

  window.__onNativeToken = function (turnId, tokenPiece) {
    if (activeTurnId !== turnId) return;
    const last = history[history.length - 1];
    if (!last || last.role !== 'assistant') return;

    last.raw = (last.raw || '') + tokenPiece;
    last.content = last.raw;
    renderTranscript();

    const unspoken = last.content.slice(alreadySpokenLen);
    const m = unspoken.match(/^([\s\S]*?[.,?!;:])(\s+[\s\S]*)$/);
    if (m) {
      speakClauseIfEnabled(m[1]);
      alreadySpokenLen += m[1].length;
    }
  };

  window.__onNativeDone = function (turnId) {
    if (activeTurnId !== turnId) return;
    const last = history[history.length - 1];
    if (last && last.role === 'assistant') {
      last.pending = false;
      const tail = (last.content || '').slice(alreadySpokenLen).trim();
      if (tail) speakClauseIfEnabled(tail);
    }
    isGenerating = false;
    activeTurnId = null;
    updateComposerButtons();
    renderTranscript();
  };

  window.__onNativeError = function (turnId, errorMsg) {
    if (activeTurnId !== turnId) return;
    const last = history[history.length - 1];
    if (last && last.role === 'assistant') {
      last.pending = false;
      last.content = `[Assistant error: ${errorMsg || 'Internal inference error'}]`;
    }
    isGenerating = false;
    activeTurnId = null;
    updateComposerButtons();
    renderTranscript();
  };

  // Native Mic callbacks
  window.__onNativeMicStarted = function () {
    micActive = true;
    const micBtn = document.getElementById('micBtn');
    if (micBtn) micBtn.classList.add('active');
  };

  window.__onNativeMicStopped = function () {
    micActive = false;
    const micBtn = document.getElementById('micBtn');
    if (micBtn) micBtn.classList.remove('active');
  };

  window.__onNativeMicText = function (recognizedText, isFinal) {
    const inp = document.getElementById('textInput');
    if (!inp) return;
    inp.value = recognizedText;
    inp.style.height = 'auto';
    inp.style.height = Math.min(inp.scrollHeight, 120) + 'px';
    updateComposerButtons();

    if (isFinal && recognizedText.trim().length > 0) {
      // Auto-submit after voice silence
      setTimeout(() => {
        if (!micActive && inp.value.trim() === recognizedText.trim()) {
          submitMessage(recognizedText);
        }
      }, 400);
    }
  };

  // Wire UI Events
  function init() {
    const inp = document.getElementById('textInput');
    const sendBtn = document.getElementById('sendBtn');
    const micBtn = document.getElementById('micBtn');
    const clearBtn = document.getElementById('clearChatBtn');
    const saveBtn = document.getElementById('saveConvoBtn');
    const openSettingsBtn = document.getElementById('openSettingsBtn');
    const closeSettingsBtn = document.getElementById('closeSettingsBtn');
    const settingsOverlay = document.getElementById('settingsOverlay');
    const voiceToggle = document.getElementById('toggleVoiceAudio');
    const speechRateSlider = document.getElementById('speechRateSlider');
    const speechRateVal = document.getElementById('speechRateVal');
    const userNameInput = document.getElementById('userNameInput');
    const userContextInput = document.getElementById('userContextInput');
    const toneSegmented = document.getElementById('toneSegmented');
    const resetChatBtn = document.getElementById('resetChatHistoryBtn');

    // Auto-grow textarea
    if (inp) {
      inp.oninput = () => {
        inp.style.height = 'auto';
        inp.style.height = Math.min(inp.scrollHeight, 120) + 'px';
        updateComposerButtons();
      };
      inp.onkeydown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          if (!isGenerating && inp.value.trim().length > 0) {
            submitMessage(inp.value);
          }
        }
      };
    }

    if (sendBtn) {
      sendBtn.onclick = () => {
        if (isGenerating) {
          stopGeneration();
        } else if (inp && inp.value.trim().length > 0) {
          submitMessage(inp.value);
        }
      };
    }

    if (micBtn) {
      micBtn.onclick = () => {
        if (IS_ANDROID_APK) {
          window.AndroidBuddy.toggleSpeechMic();
        } else {
          alert('Native speech microphone requires the Android APK.');
        }
      };
    }

    if (clearBtn) {
      clearBtn.onclick = () => {
        if (isGenerating) stopGeneration();
        history = [];
        if (IS_ANDROID_APK) {
          try { window.AndroidBuddy.resetSession(); } catch {}
        }
        renderTranscript();
      };
    }

    if (saveBtn) {
      saveBtn.onclick = saveEntireConvo;
    }

    // Settings
    if (openSettingsBtn && settingsOverlay) {
      openSettingsBtn.onclick = () => settingsOverlay.classList.remove('hidden');
    }
    if (closeSettingsBtn && settingsOverlay) {
      closeSettingsBtn.onclick = () => settingsOverlay.classList.add('hidden');
    }
    if (settingsOverlay) {
      settingsOverlay.onclick = (e) => {
        if (e.target === settingsOverlay) settingsOverlay.classList.add('hidden');
      };
    }

    // Voice toggle
    if (voiceToggle) {
      const updateToggle = () => {
        voiceToggle.textContent = state.settings.spokenVoice ? 'On' : 'Off';
        voiceToggle.classList.toggle('on', state.settings.spokenVoice);
      };
      updateToggle();
      voiceToggle.onclick = () => {
        state.settings.spokenVoice = !state.settings.spokenVoice;
        saveState();
        updateToggle();
      };
    }

    // Speech rate
    if (speechRateSlider && speechRateVal) {
      speechRateSlider.value = state.settings.speechRate || 1.05;
      speechRateVal.textContent = speechRateSlider.value + 'x';
      speechRateSlider.oninput = () => {
        const val = parseFloat(speechRateSlider.value);
        state.settings.speechRate = val;
        speechRateVal.textContent = val.toFixed(2) + 'x';
        saveState();
      };
    }

    // Tone segmented
    if (toneSegmented) {
      const btns = toneSegmented.querySelectorAll('.tone-btn');
      btns.forEach((b) => {
        b.classList.toggle('active', b.getAttribute('data-tone') === state.settings.tone);
        b.onclick = () => {
          btns.forEach((x) => x.classList.remove('active'));
          b.classList.add('active');
          state.settings.tone = b.getAttribute('data-tone') || 'concise';
          saveState();
        };
      });
    }

    // Profile inputs
    if (userNameInput) {
      userNameInput.value = state.profile.name || 'Mike';
      userNameInput.onchange = () => {
        state.profile.name = userNameInput.value.trim() || 'Mike';
        saveState();
      };
    }

    if (userContextInput) {
      userContextInput.value = state.profile.context || '';
      userContextInput.onchange = () => {
        state.profile.context = userContextInput.value.trim();
        saveState();
      };
    }

    if (resetChatBtn) {
      resetChatBtn.onclick = () => {
        if (isGenerating) stopGeneration();
        history = [];
        if (IS_ANDROID_APK) {
          try { window.AndroidBuddy.resetSession(); } catch {}
        }
        renderTranscript();
        if (settingsOverlay) settingsOverlay.classList.add('hidden');
      };
    }

    // Initial query to native bridge
    if (IS_ANDROID_APK) {
      try {
        const raw = window.AndroidBuddy.getModelStatusJson();
        if (raw) {
          nativeStatus = JSON.parse(raw);
          updateStatusBadge();
        }
      } catch {}
    }

    renderTranscript();
    updateComposerButtons();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
