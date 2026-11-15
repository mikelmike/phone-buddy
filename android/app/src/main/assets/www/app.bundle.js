// Phone Buddy — v0.5.1 Universal Mobile Client
// Mode 1: Inside Native Android APK (`window.AndroidBuddy`) -> 100% offline llama.cpp C++ engine (buddy-4b-social-v0.5.1 Q4_K_M)
// Mode 2: Direct GitHub Pages Link (`https://mikelmike.github.io/phone-buddy/`) -> On-phone WebGPU (`@mlc-ai/web-llm`) + 1-tap APK download

(function () {
  const APP_VERSION = 'v0.5.1';
  const STORAGE_KEY = 'phone_buddy_mobile_v051';
  const IS_ANDROID_APK = typeof window.AndroidBuddy !== 'undefined';
  const WEBLLM_MODEL_ID = 'Qwen2.5-1.5B-Instruct-q4f16_1-MLC';

  const LEVER_SCHEMA = [
    { key: 'warmth', label: 'Warmth', options: ['low', 'med', 'high'] },
    { key: 'sharpness', label: 'Sharpness', options: ['low', 'med', 'high'] },
    { key: 'irony', label: 'Irony', options: ['off', 'low', 'high'] },
    { key: 'dark', label: 'Dark Humor', options: ['off', 'low', 'high'] },
    { key: 'pushback', label: 'Pushback', options: ['low', 'med', 'high'] },
    { key: 'energy', label: 'Energy', options: ['low', 'med', 'high'] },
    { key: 'brevity', label: 'Brevity', options: ['tight', 'normal', 'banter'] }
  ];

  const PERSONAS = {
    buddy_default_v1: {
      id: 'buddy_default_v1',
      name: 'Jay',
      desc: 'sharp, warm, grounded voice with natural guy cadence and dry wit',
      pitch: 1.0,
      rate: 1.05,
      levers: { warmth: 'high', sharpness: 'med', irony: 'low', dark: 'off', pushback: 'med', energy: 'med', brevity: 'normal' }
    },
    buddy_coach_mack: {
      id: 'buddy_coach_mack',
      name: 'Mack',
      desc: 'quick-witted barbershop sports junkie friend',
      pitch: 0.88,
      rate: 1.08,
      levers: { warmth: 'med', sharpness: 'high', irony: 'low', dark: 'off', pushback: 'high', energy: 'high', brevity: 'banter' }
    },
    buddy_nora_dry: {
      id: 'buddy_nora_dry',
      name: 'Nora',
      desc: 'dry, deadpan, perceptive realist friend',
      pitch: 1.06,
      rate: 1.02,
      levers: { warmth: 'low', sharpness: 'high', irony: 'high', dark: 'low', pushback: 'high', energy: 'low', brevity: 'tight' }
    }
  };

  const DEFAULT_STATE = {
    settings: {
      persona: 'buddy_default_v1',
      silentMode: true
    },
    levers: { ...PERSONAS.buddy_default_v1.levers },
    profile: { name: 'Mike', teams: 'Senators, Penguins, Knicks, Eagles' }
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
        levers: { ...DEFAULT_STATE.levers, ...(p.levers || {}) },
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
  let micListening = false;
  let webRecognition = null;

  // Native APK model state
  let nativeStatus = {
    ready: false,
    loading: false,
    downloading: false,
    progressPct: 0,
    downloadedMb: 0,
    totalMb: 2381,
    error: ''
  };

  // WebGPU browser engine state (when opened on github.io outside APK)
  let webEngine = null;
  let webLoading = false;
  let webProgressText = '';
  let webProgressPct = 0;
  let webAbort = false;

  function saveState() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch {}
  }

  function activePersona() {
    return PERSONAS[state.settings.persona] || PERSONAS.buddy_default_v1;
  }

  function escapeHtml(str) {
    return (str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function cleanReplyText(raw) {
    let txt = raw || '';
    const openThink = txt.indexOf('<think>');
    if (openThink !== -1) {
      const closeThink = txt.indexOf('</think>');
      txt = closeThink === -1
        ? txt.slice(0, openThink).trim()
        : (txt.slice(0, openThink) + ' ' + txt.slice(closeThink + 8)).trim();
    }
    return txt
      .replace(/<\|[^|]+\|>/g, '')
      .replace(/[*#`~]/g, '')
      .replace(/ — /g, ', ')
      .replace(/—/g, ', ')
      .trim();
  }

  function buildBrowserSystemPrompt(recentCtx) {
    const p = activePersona();
    const friendWord = p.id === 'buddy_nora_dry' ? 'person' : 'guy';
    const userName = state.profile.name || 'Mike';
    const hasPhysical = /\b(coffee|espresso|latte|caffeine|tea|beer|drinks|bar|lunch|dinner|breakfast|eat|eating|drink|drinking|hungry|thirsty|meet up|in person|come over|grab a)\b/i.test(recentCtx);
    const isCrisis = /\b(suicide|kill myself|want to die|end it all|self harm|hurt myself)\b/i.test(recentCtx);

    const lines = [
      `You are ${p.name}, ${userName}'s close, sharp, warm, self-aware friend living in his phone (${p.desc}).`,
      `Core voice rules:`,
      `- Reply in 1-2 short, natural everyday sentences the way an actual socially perceptive ${friendWord} texts a friend.`,
      `- Never use em-dashes, bullet lists, markdown formatting, corporate filler, or therapy clichés ('heavy weight on your shoulders', 'holding space', 'here to chat', 'I'd love to hear').`,
      `- Grounded: never invent fake sports scores, never claim a fake job, never say you slept or woke up, and never suggest meeting up in person ('grab coffee', 'grab drinks').`,
      `- Restraint: if asked to sit quietly, reply in 2-6 calm words with zero questions. If he says no advice, give zero advice and zero questions.`,
      `- Math & schedules: state intermediate time addition out loud first before answering yes or no.`
    ];

    if (isCrisis) {
      lines.push(`- SAFETY OVERRIDE: Drop all irony and stay calm, warm, grounded, and genuinely supportive.`);
    } else {
      const lv = state.levers;
      lines.push(`- Personality equalizer: warmth=${lv.warmth}, sharpness=${lv.sharpness}, irony=${lv.irony}, edgy_humor=${lv.dark}, pushback=${lv.pushback}, energy=${lv.energy}, length=${lv.brevity}.`);
    }

    if (hasPhysical) {
      lines.push(`IMPORTANT FOR THIS TURN: You live inside his phone with no physical body or mouth, so do NOT pretend you drink coffee or eat food and do NOT agree to meet in person. Respond with dry, deadpan humor in 1-2 short sentences.`);
    }
    return lines.join('\n');
  }

  function speakClauseIfEnabled(clause) {
    if (state.settings.silentMode || !clause.trim()) return;
    const p = activePersona();
    if (IS_ANDROID_APK) {
      try { window.AndroidBuddy.speakText(clause, p.pitch, p.rate); } catch {}
    } else if ('speechSynthesis' in window) {
      const u = new SpeechSynthesisUtterance(clause);
      u.pitch = p.pitch;
      u.rate = p.rate;
      window.speechSynthesis.speak(u);
    }
  }

  // Native APK callbacks
  window.__onNativeModelStatus = function (st) {
    nativeStatus = st || nativeStatus;
    updateComposerButtons();
    if (!history.length) renderTranscript();
  };

  window.__onNativeToken = function (turnId, piece) {
    if (turnId !== activeTurnId || !history.length) return;
    const last = history[history.length - 1];
    last.raw = (last.raw || '') + piece;
    last.content = cleanReplyText(last.raw);
    renderTranscript();

    const unspoken = last.content.slice(alreadySpokenLen);
    const m = unspoken.match(/^([\s\S]*?[.,?!;:])(\s+[\s\S]*)$/);
    if (m) {
      speakClauseIfEnabled(m[1]);
      alreadySpokenLen += m[1].length;
    }
  };

  window.__onNativeDone = function (turnId) {
    if (turnId !== activeTurnId) return;
    const last = history[history.length - 1];
    if (last) {
      last.pending = false;
      const tail = (last.content || '').slice(alreadySpokenLen).trim();
      if (tail) speakClauseIfEnabled(tail);
    }
    activeTurnId = null;
    isGenerating = false;
    updateComposerButtons();
    renderTranscript();
  };

  window.__onNativeError = function (turnId, errMsg) {
    if (turnId !== activeTurnId) return;
    const last = history[history.length - 1];
    if (last) {
      last.pending = false;
      last.content = `[${errMsg}]`;
    }
    activeTurnId = null;
    isGenerating = false;
    updateComposerButtons();
    renderTranscript();
  };

  window.__onNativeMicText = function (txt, isFinal) {
    const inp = document.getElementById('textInput');
    const micBtn = document.getElementById('micBtn');
    inp.value = txt || '';
    autoResizeInput();
    if (isFinal) {
      micListening = false;
      micBtn.classList.remove('listening');
      const clean = inp.value.trim();
      if (clean) {
        inp.value = '';
        autoResizeInput();
        runBuddyTurn(clean);
      }
    }
  };

  window.__onNativeMicStopped = function () {
    micListening = false;
    document.getElementById('micBtn').classList.remove('listening');
  };

  function updateComposerButtons() {
    const inp = document.getElementById('textInput');
    const sendBtn = document.getElementById('sendBtn');
    const sendIcon = document.getElementById('sendIcon');
    const stopIcon = document.getElementById('stopIcon');

    if (isGenerating) {
      sendBtn.disabled = false;
      sendIcon.style.display = 'none';
      stopIcon.style.display = 'block';
      return;
    }
    sendIcon.style.display = 'block';
    stopIcon.style.display = 'none';
    const modelBlocked = IS_ANDROID_APK && !nativeStatus.ready;
    sendBtn.disabled = modelBlocked || !inp.value.trim();
  }

  function autoResizeInput() {
    const inp = document.getElementById('textInput');
    inp.style.height = 'auto';
    inp.style.height = Math.min(inp.scrollHeight, 112) + 'px';
    updateComposerButtons();
  }

  function renderPersonas() {
    const strip = document.getElementById('personaStrip');
    strip.innerHTML = Object.values(PERSONAS)
      .map((p) => {
        const active = state.settings.persona === p.id;
        return `<button class="char-tab ${active ? 'active' : ''}" data-pid="${p.id}" role="tab" aria-selected="${active}">${p.name}</button>`;
      })
      .join('');

    const inp = document.getElementById('textInput');
    if (inp) inp.placeholder = `Message ${activePersona().name}`;

    strip.querySelectorAll('.char-tab').forEach((btn) => {
      btn.onclick = () => {
        const pid = btn.getAttribute('data-pid');
        if (pid === state.settings.persona) return;
        state.settings.persona = pid;
        if (PERSONAS[pid]) state.levers = { ...PERSONAS[pid].levers };
        saveState();
        renderPersonas();
        renderEqualizer();
        renderTranscript();
      };
    });
  }

  function renderTranscript() {
    const box = document.getElementById('chatThread');
    const p = activePersona();

    if (!history.length) {
      if (IS_ANDROID_APK && !nativeStatus.ready) {
        let cardBody = '';
        if (nativeStatus.downloading) {
          cardBody = `
            <div class="dl-title">Downloading offline brain (${nativeStatus.progressPct}%)</div>
            <div class="dl-sub">${nativeStatus.downloadedMb} / ${nativeStatus.totalMb} MB • One-time setup</div>
            <div class="dl-bar-track"><div class="dl-bar-fill" style="width:${nativeStatus.progressPct}%"></div></div>
          `;
        } else if (nativeStatus.loading) {
          cardBody = `
            <div class="dl-title">Loading offline brain...</div>
            <div class="dl-sub">Starting llama.cpp on device</div>
          `;
        } else {
          const errLine = nativeStatus.error ? `<div class="dl-sub" style="color:#f87171">${escapeHtml(nativeStatus.error)}</div>` : '';
          cardBody = `
            <div class="dl-title">Offline 4B Brain (2.4 GB)</div>
            <div class="dl-sub">Downloads once to phone storage. Works 100% offline after.</div>
            ${errLine}
            <button class="dl-btn" id="startDlBtn">${nativeStatus.error ? 'Resume Download' : 'Download Offline Brain'}</button>
          `;
        }
        box.innerHTML = `<div class="empty-canvas"><div class="char-mark">${p.name}</div><div class="dl-card">${cardBody}</div></div>`;
        const dlBtn = document.getElementById('startDlBtn');
        if (dlBtn) dlBtn.onclick = () => window.AndroidBuddy.startModelDownload();
        return;
      }

      if (!IS_ANDROID_APK && webLoading) {
        box.innerHTML = `<div class="empty-canvas">
          <div class="char-mark">${p.name}</div>
          <div class="dl-card">
            <div class="dl-title">Loading in-browser model (${webProgressPct}%)</div>
            <div class="dl-sub">${escapeHtml(webProgressText || 'Caching weights in browser storage...')}</div>
            <div class="dl-bar-track"><div class="dl-bar-fill" style="width:${webProgressPct}%"></div></div>
          </div>
        </div>`;
        return;
      }

      box.innerHTML = `<div class="empty-canvas"><div class="char-mark">${p.name}</div></div>`;
      return;
    }

    box.innerHTML = history
      .map((t) => {
        if (t.role === 'user') {
          return `<div class="msg-row user"><div class="user-bubble">${escapeHtml(t.content)}</div></div>`;
        }
        const avatarChar = (t.buddyName || p.name)[0];
        const inner = t.content
          ? `<div class="assistant-text">${escapeHtml(t.content)}</div>`
          : `<div class="assistant-text"><div class="typing-dots"><span></span><span></span><span></span></div></div>`;
        return `<div class="msg-row assistant">
          <div class="assistant-avatar">${escapeHtml(avatarChar)}</div>
          ${inner}
        </div>`;
      })
      .join('');
    box.scrollTop = box.scrollHeight;
  }

  async function ensureWebLlmEngine() {
    if (webEngine) return webEngine;
    webLoading = true;
    renderTranscript();
    const webllm = await import('https://esm.run/@mlc-ai/web-llm');
    webEngine = await webllm.CreateMLCEngine(WEBLLM_MODEL_ID, {
      initProgressCallback: (rep) => {
        webProgressPct = Math.round((rep.progress || 0) * 100);
        webProgressText = rep.text || '';
        if (!history.length || history[history.length - 1]?.pending) renderTranscript();
      }
    });
    webLoading = false;
    return webEngine;
  }

  async function stopGeneration() {
    webAbort = true;
    activeTurnId = null;
    isGenerating = false;
    if (IS_ANDROID_APK) {
      try { window.AndroidBuddy.stopGeneration(); } catch {}
    } else {
      if (webEngine && webEngine.interruptGenerate) {
        try { await webEngine.interruptGenerate(); } catch {}
      }
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    }
    updateComposerButtons();
  }

  async function runBuddyTurn(userText) {
    if (isGenerating) await stopGeneration();

    const p = activePersona();
    history.push({ role: 'user', content: userText });
    history.push({ role: 'assistant', content: '', raw: '', buddyName: p.name, pending: true });
    const turnIdx = history.length - 1;
    const turnId = 't_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
    activeTurnId = turnId;
    alreadySpokenLen = 0;
    isGenerating = true;
    webAbort = false;
    updateComposerButtons();
    renderTranscript();

    if (IS_ANDROID_APK) {
      const cleanHist = history
        .slice(0, turnIdx)
        .filter((m) => m.content && m.content.trim())
        .map((m) => ({ role: m.role, content: m.content }));
      window.AndroidBuddy.startChatTurn(
        turnId,
        state.settings.persona,
        JSON.stringify(state.levers),
        JSON.stringify(cleanHist),
        state.profile.name || 'Mike',
        state.profile.teams || ''
      );
      return;
    }

    // Browser WebGPU fallback (GitHub Pages direct link)
    try {
      const engine = await ensureWebLlmEngine();
      const cleanHist = history
        .slice(0, turnIdx)
        .filter((m) => m.content && m.content.trim())
        .slice(-14)
        .map((m) => ({ role: m.role, content: m.content }));
      const recentCtx = cleanHist.slice(-3).map((m) => m.content).join(' ');
      const sysPrompt = buildBrowserSystemPrompt(recentCtx);

      const chunks = await engine.chat.completions.create({
        messages: [{ role: 'system', content: sysPrompt }, ...cleanHist],
        temperature: 0.45,
        top_p: 0.85,
        max_tokens: 180,
        stream: true
      });

      let raw = '';
      for await (const ch of chunks) {
        if (webAbort || activeTurnId !== turnId) break;
        const delta = ch.choices?.[0]?.delta?.content || '';
        if (!delta) continue;
        raw += delta;
        history[turnIdx].content = cleanReplyText(raw);
        renderTranscript();

        const unspoken = history[turnIdx].content.slice(alreadySpokenLen);
        const m = unspoken.match(/^([\s\S]*?[.,?!;:])(\s+[\s\S]*)$/);
        if (m) {
          speakClauseIfEnabled(m[1]);
          alreadySpokenLen += m[1].length;
        }
      }
      history[turnIdx].pending = false;
      const tail = (history[turnIdx].content || '').slice(alreadySpokenLen).trim();
      if (tail) speakClauseIfEnabled(tail);
    } catch (e) {
      history[turnIdx].pending = false;
      history[turnIdx].content = navigator.gpu
        ? `[Browser model error: ${e.message || e}]`
        : `[Tap APK at top right to install the 100% offline Android app — mobile browser WebGPU is disabled on this browser]`;
    } finally {
      if (activeTurnId === turnId) {
        activeTurnId = null;
        isGenerating = false;
      }
      updateComposerButtons();
      renderTranscript();
    }
  }

  async function saveEntireConvo() {
    const saveBtn = document.getElementById('saveConvoBtn');
    const cleanTurns = history.filter((t) => t.content && t.content.trim());
    if (!cleanTurns.length) {
      saveBtn.textContent = 'Empty';
      setTimeout(() => { saveBtn.textContent = 'Save'; }, 1200);
      return;
    }

    const p = activePersona();
    const userLabel = state.profile.name || 'Mike';
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const slug = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    const filename = `convo_${slug}_${APP_VERSION}_${p.name.toLowerCase()}.md`;

    const plainLines = cleanTurns.map((t) => `${t.role === 'user' ? userLabel : p.name}: ${t.content}`);
    const plainCopy = `[Phone Buddy ${APP_VERSION} | ${p.name}]\n` + plainLines.join('\n');
    const mdText = [
      `# Saved Phone Buddy Convo — \`${APP_VERSION}\``,
      `- **Character:** \`${p.name}\``,
      `- **Engine:** \`${IS_ANDROID_APK ? 'Android Offline llama.cpp (buddy-4b-social-v0.5.1)' : 'GitHub Pages WebGPU'}\``,
      '',
      '---',
      '',
      ...cleanTurns.map((t) => `**${t.role === 'user' ? userLabel : p.name}:** ${t.content}  `)
    ].join('\n') + '\n';

    if (IS_ANDROID_APK) {
      window.AndroidBuddy.saveConvoToPhone(filename, mdText, plainCopy);
    } else {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(plainCopy);
        }
      } catch {}
      const blob = new Blob([mdText], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
    }

    saveBtn.textContent = '✓ Saved';
    saveBtn.classList.add('saved');
    setTimeout(() => {
      saveBtn.textContent = 'Save';
      saveBtn.classList.remove('saved');
    }, 1800);
  }

  function toggleSmallMic() {
    const micBtn = document.getElementById('micBtn');
    if (IS_ANDROID_APK) {
      micListening = !micListening;
      micBtn.classList.toggle('listening', micListening);
      window.AndroidBuddy.toggleSpeechMic();
      return;
    }

    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;

    if (micListening) {
      micListening = false;
      micBtn.classList.remove('listening');
      if (webRecognition) {
        const r = webRecognition;
        webRecognition = null;
        try { r.stop(); } catch {}
      }
      return;
    }

    micListening = true;
    micBtn.classList.add('listening');
    webRecognition = new SR();
    webRecognition.continuous = false;
    webRecognition.interimResults = true;
    webRecognition.lang = 'en-US';

    const inp = document.getElementById('textInput');
    webRecognition.onresult = (ev) => {
      let interim = '';
      let finalTxt = '';
      for (let i = 0; i < ev.results.length; i++) {
        const t = ev.results[i][0].transcript;
        if (ev.results[i].isFinal) finalTxt += t;
        else interim += t;
      }
      inp.value = (finalTxt || interim).trim();
      autoResizeInput();
    };
    webRecognition.onend = () => {
      const shouldSend = micListening && inp.value.trim();
      micListening = false;
      micBtn.classList.remove('listening');
      webRecognition = null;
      if (shouldSend) {
        const txt = inp.value.trim();
        inp.value = '';
        autoResizeInput();
        runBuddyTurn(txt);
      }
    };
    webRecognition.onerror = () => {
      micListening = false;
      micBtn.classList.remove('listening');
      webRecognition = null;
    };
    try { webRecognition.start(); } catch {}
  }

  function renderEqualizer() {
    const box = document.getElementById('equalizerContainer');
    if (!box) return;
    box.innerHTML = LEVER_SCHEMA.map((row) => {
      const current = state.levers[row.key];
      const btns = row.options
        .map((opt) => `<button class="seg-btn ${current === opt ? 'active' : ''}" data-lkey="${row.key}" data-lval="${opt}">${opt}</button>`)
        .join('');
      return `<div class="lever-row">
        <span class="lever-name">${row.label}</span>
        <div class="seg-group">${btns}</div>
      </div>`;
    }).join('');
    box.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.onclick = () => {
        state.levers[btn.getAttribute('data-lkey')] = btn.getAttribute('data-lval');
        saveState();
        renderEqualizer();
      };
    });
  }

  function syncSettingsUI() {
    renderEqualizer();
    document.getElementById('userNameInput').value = state.profile.name || 'Mike';
    document.getElementById('userTeamsInput').value = state.profile.teams || '';
    const voiceBtn = document.getElementById('toggleVoiceAudio');
    voiceBtn.textContent = state.settings.silentMode ? 'Off' : 'On';
    voiceBtn.classList.toggle('on', !state.settings.silentMode);
  }

  window.addEventListener('DOMContentLoaded', () => {
    const inp = document.getElementById('textInput');
    const verEl = document.getElementById('versionLabel');

    if (IS_ANDROID_APK) {
      try {
        nativeStatus = JSON.parse(window.AndroidBuddy.getModelStatusJson());
      } catch {}
      if (verEl) verEl.textContent = `${APP_VERSION} • Offline Android (llama.cpp 4B)`;
    } else {
      document.getElementById('downloadApkLink').classList.remove('hidden');
      document.getElementById('apkInstallGroup').style.display = 'block';
      if (verEl) verEl.textContent = `${APP_VERSION} • GitHub Pages`;
      if ('serviceWorker' in navigator) {
        navigator.serviceWorker.register('./sw.js').catch(() => {});
      }
    }

    document.getElementById('saveConvoBtn').onclick = () => saveEntireConvo();

    document.getElementById('clearChatBtn').onclick = async () => {
      await stopGeneration();
      history = [];
      if (IS_ANDROID_APK) {
        try { window.AndroidBuddy.resetSession(); } catch {}
      }
      renderTranscript();
    };

    document.getElementById('micBtn').onclick = () => toggleSmallMic();

    document.getElementById('sendBtn').onclick = () => {
      if (isGenerating) {
        stopGeneration();
        return;
      }
      const txt = inp.value.trim();
      if (!txt) return;
      inp.value = '';
      autoResizeInput();
      runBuddyTurn(txt);
    };

    inp.addEventListener('input', autoResizeInput);
    inp.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!isGenerating && !document.getElementById('sendBtn').disabled) {
          document.getElementById('sendBtn').click();
        }
      }
    });

    document.getElementById('openSettingsBtn').onclick = () => {
      syncSettingsUI();
      document.getElementById('settingsOverlay').classList.remove('hidden');
    };

    document.getElementById('closeSettingsBtn').onclick = () => {
      state.profile.name = document.getElementById('userNameInput').value.trim() || 'Mike';
      state.profile.teams = document.getElementById('userTeamsInput').value.trim();
      saveState();
      document.getElementById('settingsOverlay').classList.add('hidden');
    };

    document.getElementById('toggleVoiceAudio').onclick = () => {
      state.settings.silentMode = !state.settings.silentMode;
      if (state.settings.silentMode) {
        if (IS_ANDROID_APK) {
          try { window.AndroidBuddy.stopTts(); } catch {}
        } else if ('speechSynthesis' in window) {
          window.speechSynthesis.cancel();
        }
      }
      saveState();
      syncSettingsUI();
    };

    renderPersonas();
    renderTranscript();
    updateComposerButtons();
  });
})();
