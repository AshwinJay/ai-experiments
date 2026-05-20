import { useState, useEffect, useCallback, useMemo, useRef } from "react";

const getWeekId = (d = new Date()) => {
  const jan1 = new Date(d.getFullYear(), 0, 1);
  const days = Math.floor((d - jan1) / 86400000);
  const week = Math.ceil((days + jan1.getDay() + 1) / 7);
  return `${d.getFullYear()}-W${String(week).padStart(2, "0")}`;
};

const PHASES = [
  { key: "planning", label: "Planning", icon: "📋", color: "#6b7280" },
  { key: "branch-cut", label: "Branch Cut", icon: "✂️", color: "#f59e0b" },
  { key: "labeled", label: "Labeled", icon: "🏷️", color: "#8b5cf6" },
  { key: "testing", label: "Testing", icon: "🧪", color: "#3b82f6" },
  { key: "review", label: "Mon Review", icon: "👀", color: "#ec4899" },
  { key: "deploying", label: "Deploying", icon: "🚀", color: "#f97316" },
  { key: "done", label: "Done", icon: "✅", color: "#10b981" },
];

const SVC_STATUSES = ["pending","branch-cut","labeled","testing","approved","needs-hotfix","hotfix-ready","deploying","deployed","failed"];
const REGION_LIST = ["pre-production", "us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1"];
const REGION_STATUSES = ["pending","deploying","deployed","failed"];
const REGION_COLORS = {pending:"#6b7280",deploying:"#f59e0b",deployed:"#10b981",failed:"#ef4444"};
const CHANGE_TYPES = ["code", "config", "both"];

const STATUS_COLORS = {
  pending:"#6b7280","branch-cut":"#f59e0b",labeled:"#8b5cf6",testing:"#3b82f6",
  approved:"#10b981","needs-hotfix":"#ef4444","hotfix-ready":"#f97316",
  deploying:"#f97316",deployed:"#10b981",failed:"#ef4444",
};

const EMPTY_RELEASE = { releaseManager: "", releaseBranch: "", hotfixBranch: "", phase: "planning", services: [], notes: [] };
const EMPTY_SERVICE = {
  id:"",name:"",repo:"",changeType:"code",label:"",hotfixLabel:"",
  poc:"",dependencies:[],status:"pending",regions:{},hasHotfix:false,
  hotfixNotes:"",deployConfirmed:false,
  hotfixMergedMain:false,hotfixMergedRelease:false,hotfixMergedHotfix:false,
  updatedAt:0,deletedAt:null,
};

const themes = {
  dark: {
    bg:"#0b1120",bgPanel:"#0f172a",bgCard:"#111827",
    border:"#1e293b",borderLight:"#334155",
    text:"#e2e8f0",textMuted:"#94a3b8",textDim:"#64748b",textFaint:"#475569",
    accent:"#3b82f6",inputBg:"#1e293b",
    labelBg:"#f59e0b15",successBg:"#10b98118",dangerBg:"#ef444418",
    successBorder:"#10b98133",dangerBorder:"#ef444433",
    shadow:"none",
    labelColor:"#d97706",saveBg:"#1e293b",
  },
  light: {
    bg:"#f1f5f9",bgPanel:"#ffffff",bgCard:"#ffffff",
    border:"#e2e8f0",borderLight:"#cbd5e1",
    text:"#1e293b",textMuted:"#475569",textDim:"#64748b",textFaint:"#94a3b8",
    accent:"#2563eb",inputBg:"#f8fafc",
    labelBg:"#fef3c7",successBg:"#dcfce7",dangerBg:"#fee2e2",
    successBorder:"#86efac",dangerBorder:"#fca5a5",
    shadow:"0 1px 3px rgba(0,0,0,0.08)",
    labelColor:"#92400e",saveBg:"#e2e8f0",
  },
};

// ── Merge logic ───────────────────────────────────────────────────────────────

// Recursively merge note arrays: union by id, latest updatedAt wins per node.
// Tombstoned notes are retained (so deletions propagate to peers).
export function mergeNoteArrays(noteArrays) {
  const noteMap = new Map();
  for (const arr of noteArrays) {
    for (const note of (arr || [])) {
      const existing = noteMap.get(note.id);
      if (!existing || (note.updatedAt || 0) > (existing.updatedAt || 0)) {
        noteMap.set(note.id, note);
      }
    }
  }
  const result = [];
  for (const [, note] of noteMap) {
    // Collect children arrays from every copy of this note across all files
    const childArrays = noteArrays
      .flatMap(arr => (arr || []).filter(n => n.id === note.id))
      .map(n => n.children || []);
    result.push({ ...note, children: mergeNoteArrays(childArrays) });
  }
  return result;
}

// Merge multiple per-user release files into one unified release.
//   Scalars (releaseManager, releaseBranch, hotfixBranch, phase): latest savedAt wins
//   Services: union by id, latest updatedAt wins; tombstones retained but hidden in UI
//   Notes:    recursive union by id, latest updatedAt wins
//   Checklist: OR-merge (checked by anyone = checked for all)
export function mergeReleases(files) {
  if (!files || files.length === 0) return { ...EMPTY_RELEASE };

  // Scalars from file with latest savedAt
  const latest = files.reduce((a, b) => (b.savedAt || 0) > (a.savedAt || 0) ? b : a);

  // Services: union by id, latest updatedAt wins
  const svcMap = new Map();
  for (const file of files) {
    for (const svc of (file.services || [])) {
      const existing = svcMap.get(svc.id);
      if (!existing || (svc.updatedAt || 0) > (existing.updatedAt || 0)) {
        svcMap.set(svc.id, svc);
      }
    }
  }

  // Checklist: OR-merge
  const mergedChecklist = {};
  for (const file of files) {
    for (const [k, v] of Object.entries(file.checklist || {})) {
      mergedChecklist[k] = mergedChecklist[k] || !!v;
    }
  }

  return {
    releaseManager: latest.releaseManager || "",
    releaseBranch:  latest.releaseBranch  || "",
    hotfixBranch:   latest.hotfixBranch   || "",
    phase:          latest.phase          || "planning",
    savedAt:        latest.savedAt        || 0,
    services:  Array.from(svcMap.values()),
    notes:     mergeNoteArrays(files.map(f => f.notes || [])),
    checklist: mergedChecklist,
  };
}

// ── File-based storage ───────────────────────────────────────────────────────
// Two modes:
//   1. Chrome/Edge: File System Access API — pick a folder once, auto-saves
//   2. Fallback: Export (download) / Import (upload) buttons

function useFileStorage(username) {
  const [baseName, setBaseName] = useState(`release-${getWeekId()}`);
  const [release, setRelease] = useState({ ...EMPTY_RELEASE });
  const [loading, setLoading] = useState(true);
  const [dirty, setDirty] = useState(false);
  const [saveStatus, setSaveStatus] = useState("idle");
  const dirHandleRef = useRef(null);
  const autoSaveTimer = useRef(null);
  // Prevents loadRelease from firing after importFile sets baseName + release together
  const skipNextLoad = useRef(false);
  const [hasDirectoryAccess, setHasDirectoryAccess] = useState(false);
  const [peers, setPeers] = useState([]); // [{username, savedAt}]

  const weekId = getWeekId();
  // Own file uses username when available; fallback to baseName-derived name
  const ownFileName = username
    ? `release-${weekId}-${username}.json`
    : `release-${weekId}.json`;
  // Fallback filename for import/export when no folder is connected
  const exportFileName = hasDirectoryAccess ? ownFileName : `${baseName}.json`;

  const readFromDir = useCallback(async (dirHandle, file) => {
    try {
      const fh = await dirHandle.getFileHandle(file, { create: false });
      const f = await fh.getFile();
      const text = await f.text();
      return JSON.parse(text);
    } catch { return null; }
  }, []);

  const writeToDir = useCallback(async (dirHandle, file, data) => {
    const fh = await dirHandle.getFileHandle(file, { create: true });
    const writable = await fh.createWritable();
    await writable.write(JSON.stringify(data, null, 2));
    await writable.close();
  }, []);

  // Read all release-YYYY-WNN-*.json files from the folder for a given week.
  const loadFromFolder = useCallback(async (dirHandle, wId) => {
    const files = [];
    const prefix = `release-${wId}-`;
    try {
      for await (const entry of dirHandle.values()) {
        if (entry.kind !== "file") continue;
        if (!entry.name.startsWith(prefix) || !entry.name.endsWith(".json")) continue;
        try {
          const fh = await dirHandle.getFileHandle(entry.name);
          const f = await fh.getFile();
          const text = await f.text();
          const data = JSON.parse(text);
          const uname = entry.name.slice(prefix.length, -5); // strip prefix + .json
          files.push({ username: uname, data });
        } catch { /* skip unreadable files */ }
      }
    } catch { /* directory iteration failed */ }
    return files;
  }, []);

  // Read all peer files, merge them, and return { merged, peerList }.
  // Returns null if the folder isn't connected or no files found.
  const refreshFromFolder = useCallback(async () => {
    if (!dirHandleRef.current) return null;
    const files = await loadFromFolder(dirHandleRef.current, getWeekId());
    if (files.length === 0) return null;
    const merged = mergeReleases(files.map(f => f.data));
    const peerList = files
      .filter(f => f.username !== username)
      .map(f => ({ username: f.username, savedAt: f.data.savedAt || 0 }));
    return { merged, peerList };
  }, [loadFromFolder, username]);

  // Apply a refresh result to state (used by manual refresh + poll).
  const applyRefresh = useCallback((result) => {
    if (!result) return;
    setRelease(result.merged);
    setPeers(result.peerList);
  }, []);

  // Load from directory (or reset) whenever baseName or refreshFromFolder changes.
  useEffect(() => {
    if (skipNextLoad.current) { skipNextLoad.current = false; return; }
    const pending = dirHandleRef.current
      ? refreshFromFolder()
      : Promise.resolve(null);
    pending.then(result => {
      if (result) {
        setRelease(result.merged);
        setPeers(result.peerList);
      } else if (!dirHandleRef.current) {
        setRelease({ ...EMPTY_RELEASE });
      }
      setDirty(false);
      setSaveStatus("idle");
      setLoading(false);
    });
  }, [baseName, refreshFromFolder]);

  // Auto-save 1s after last change when directory is connected
  useEffect(() => {
    if (!dirty || !dirHandleRef.current) return;
    if (autoSaveTimer.current) clearTimeout(autoSaveTimer.current);
    autoSaveTimer.current = setTimeout(async () => {
      try {
        setSaveStatus("saving");
        await writeToDir(dirHandleRef.current, ownFileName, { ...release, savedAt: Date.now() });
        setSaveStatus("saved");
        setDirty(false);
        setTimeout(() => setSaveStatus("idle"), 2000);
      } catch { setSaveStatus("error"); }
    }, 1000);
    return () => { if (autoSaveTimer.current) clearTimeout(autoSaveTimer.current); };
  }, [dirty, release, ownFileName, writeToDir]);

  // Poll every 30s when folder connected and no pending changes
  useEffect(() => {
    if (!hasDirectoryAccess) return;
    const id = setInterval(async () => {
      if (dirty) return; // don't clobber unsaved user edits
      const result = await refreshFromFolder();
      applyRefresh(result);
    }, 30000);
    return () => clearInterval(id);
  }, [hasDirectoryAccess, dirty, refreshFromFolder, applyRefresh]);

  const save = (updated) => { setRelease(updated); setDirty(true); };

  const manualRefresh = async () => {
    const result = await refreshFromFolder();
    applyRefresh(result);
  };

  const pickDirectory = async () => {
    if (!window.showDirectoryPicker) return false;
    try {
      const handle = await window.showDirectoryPicker({ mode: "readwrite" });
      dirHandleRef.current = handle;
      setHasDirectoryAccess(true);
      const result = await refreshFromFolder();
      if (result) {
        setRelease(result.merged);
        setPeers(result.peerList);
      }
      return true;
    } catch { return false; }
  };

  const exportFile = () => {
    const blob = new Blob([JSON.stringify({ ...release, savedAt: Date.now() }, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = exportFileName; a.click();
    URL.revokeObjectURL(url);
    setSaveStatus("saved"); setDirty(false);
    setTimeout(() => setSaveStatus("idle"), 2000);
  };

  const importFile = () => {
    return new Promise((resolve) => {
      const input = document.createElement("input");
      input.type = "file"; input.accept = ".json";
      input.onchange = async (e) => {
        const file = e.target.files[0];
        if (!file) return resolve(false);
        try {
          const text = await file.text();
          const data = JSON.parse(text);
          const importedBase = file.name.replace(/\.json$/, "");
          // Set the skip flag before updating baseName so the loadRelease
          // triggered by the fileName change doesn't overwrite our imported data
          skipNextLoad.current = true;
          setBaseName(importedBase);
          setRelease(data);
          setDirty(false);
          resolve(true);
        } catch { resolve(false); }
      };
      input.click();
    });
  };

  return {
    release, loading, save, dirty, saveStatus,
    fileName: exportFileName, baseName, setBaseName,
    pickDirectory, exportFile, importFile,
    hasDirectoryAccess,
    hasFSAccessAPI: !!window.showDirectoryPicker,
    peers, manualRefresh,
    ownFileName,
  };
}

function useColorScheme() {
  const [scheme, setScheme] = useState(() =>
    window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light"
  );
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = (e) => setScheme(e.matches ? "dark" : "light");
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, []);
  return scheme;
}

const Pill = ({ color, children, onClick, active }) => (
  <span onClick={onClick} style={{
    display:"inline-block",padding:"2px 10px",borderRadius:4,fontSize:11,fontWeight:600,
    letterSpacing:"0.04em",textTransform:"uppercase",
    background:active?color:"transparent",color:active?"#fff":color,
    border:`1.5px solid ${color}`,cursor:onClick?"pointer":"default",transition:"all 0.15s",
  }}>{children}</span>
);

// ── Username modal ────────────────────────────────────────────────────────────
function UsernameModal({ onConfirm, t }) {
  const [value, setValue] = useState("");
  const confirm = () => {
    const name = value.trim();
    if (!name) return;
    onConfirm(name);
  };
  return (
    <div style={{position:"fixed",inset:0,background:"rgba(0,0,0,0.6)",display:"flex",alignItems:"center",justifyContent:"center",zIndex:1000}}>
      <div style={{background:t.bgPanel,border:`1px solid ${t.borderLight}`,borderRadius:12,padding:32,maxWidth:400,width:"100%",margin:"0 16px",boxShadow:"0 20px 60px rgba(0,0,0,0.4)"}}>
        <h2 style={{fontFamily:"'JetBrains Mono', monospace",fontSize:18,fontWeight:700,color:t.text,marginBottom:8,marginTop:0}}>Welcome</h2>
        <p style={{color:t.textMuted,fontSize:13,marginBottom:20,lineHeight:1.5}}>
          Enter your name to identify your changes when collaborating via a shared folder.
        </p>
        <input
          autoFocus
          value={value}
          onChange={e => setValue(e.target.value)}
          onKeyDown={e => e.key === "Enter" && confirm()}
          placeholder="Your name (e.g. alice)"
          style={{width:"100%",background:t.inputBg,border:`1px solid ${t.borderLight}`,color:t.text,borderRadius:4,padding:"10px 12px",fontSize:14,outline:"none",fontFamily:"'IBM Plex Sans', sans-serif",boxSizing:"border-box",marginBottom:16}}
        />
        <button
          onClick={confirm}
          disabled={!value.trim()}
          style={{background:t.accent,border:"none",color:"#fff",padding:"10px 24px",borderRadius:6,fontWeight:600,fontSize:14,cursor:value.trim()?"pointer":"default",width:"100%",opacity:value.trim()?1:0.5,fontFamily:"'IBM Plex Sans', sans-serif"}}>
          Get Started
        </button>
      </div>
    </div>
  );
}

// ── Main Dashboard ───────────────────────────────────────────────────────────
export default function ReleaseDashboard() {
  const mode = useColorScheme();
  const t = themes[mode];
  const s = useMemo(() => makeStyles(t), [t]);

  const [tab, setTab] = useState("board");
  const [editingSvc, setEditingSvc] = useState(null);
  const [showAddForm, setShowAddForm] = useState(false);

  // Username persisted in localStorage; modal shown on first visit
  const [username, setUsername] = useState(() => localStorage.getItem("rdUsername") || "");
  const [showUsernameModal, setShowUsernameModal] = useState(() => !localStorage.getItem("rdUsername"));

  const handleSetUsername = (name) => {
    localStorage.setItem("rdUsername", name);
    setUsername(name);
    setShowUsernameModal(false);
  };

  const {
    release, loading, save, dirty, saveStatus, fileName, baseName, setBaseName,
    pickDirectory, exportFile, importFile,
    hasDirectoryAccess, hasFSAccessAPI,
    peers, manualRefresh, ownFileName,
  } = useFileStorage(username);

  // Controlled input for the session file name — only commits to baseName on blur/Enter
  // so typing character-by-character doesn't trigger a reload on each keystroke
  const [fileInput, setFileInput] = useState(baseName);
  useEffect(() => { setFileInput(baseName); }, [baseName]);
  const commitFileName = () => { if (fileInput.trim()) setBaseName(fileInput.trim()); };

  if (loading) return (
    <div style={{ display:"flex",alignItems:"center",justifyContent:"center",height:"100vh",gap:12,background:t.bg }}>
      <span style={{ color:t.textMuted,fontFamily:"'JetBrains Mono', monospace" }}>Loading…</span>
    </div>
  );

  const activeServices = release.services.filter(x => !x.deletedAt);
  const svcCount = activeServices.length;
  const deployedCount = activeServices.filter(x=>x.status==="deployed").length;
  const hotfixCount = activeServices.filter(x=>x.hasHotfix).length;
  const failedCount = activeServices.filter(x=>x.status==="failed").length;
  const approvedCount = activeServices.filter(x=>x.status==="approved"||x.status==="deployed").length;

  const statusLabel = {idle:"",saving:"Saving…",saved:"✓ Saved",error:"⚠ Save failed"}[saveStatus];
  const statusColor = {idle:t.textFaint,saving:t.textDim,saved:"#10b981",error:"#ef4444"}[saveStatus];

  return (
    <div style={s.root}>
      <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap" rel="stylesheet" />

      {showUsernameModal && <UsernameModal onConfirm={handleSetUsername} t={t} />}

      {/* ── Header ─────────────────────────────────────────────── */}
      <header style={s.header}>
        <div style={s.headerLeft}>
          <div style={{display:"flex",alignItems:"center",gap:8}}>
            <span style={{fontSize:22,color:t.accent}}>◈</span>
            <span style={{fontFamily:"'JetBrains Mono', monospace",fontWeight:700,fontSize:14,letterSpacing:"0.1em",color:t.text}}>RELEASE COMMAND</span>
          </div>
          {!hasDirectoryAccess && (
            <div>
              <span style={{color:t.textDim,fontSize:10,textTransform:"uppercase",letterSpacing:"0.08em",display:"block"}}>Session File</span>
              <input
                style={{...s.rmInput,fontFamily:"'JetBrains Mono', monospace",fontSize:14,color:t.accent,minWidth:200}}
                value={fileInput}
                onChange={e=>setFileInput(e.target.value)}
                onBlur={commitFileName}
                onKeyDown={e=>e.key==="Enter"&&commitFileName()}
                placeholder={`release-${getWeekId()}`}
              />
            </div>
          )}
          {hasDirectoryAccess && (
            <div>
              <span style={{color:t.textDim,fontSize:10,textTransform:"uppercase",letterSpacing:"0.08em",display:"block"}}>Active File</span>
              <span style={{fontFamily:"'JetBrains Mono', monospace",fontSize:13,color:t.accent,fontWeight:600}}>{ownFileName}</span>
            </div>
          )}
        </div>
        <div style={{display:"flex",alignItems:"center",gap:16,flexWrap:"wrap"}}>
          {/* Save controls */}
          <div style={{display:"flex",alignItems:"center",gap:8}}>
            {statusLabel&&<span style={{fontFamily:"'JetBrains Mono', monospace",fontSize:11,color:statusColor,fontWeight:600}}>{statusLabel}</span>}
            {dirty&&!hasDirectoryAccess&&<span style={{fontSize:10,color:"#f59e0b",fontWeight:600}}>● unsaved</span>}
          </div>
          {/* Peers indicator */}
          {hasDirectoryAccess && (
            <div style={{display:"flex",alignItems:"center",gap:6,flexWrap:"wrap"}}>
              {username && (
                <span title="Your username" style={{fontSize:11,fontWeight:600,color:t.textMuted,background:t.inputBg,padding:"2px 8px",borderRadius:10,border:`1px solid ${t.border}`}}>
                  👤 {username}
                </span>
              )}
              {peers.map(p => (
                <span key={p.username}
                  title={p.savedAt ? `Last seen: ${new Date(p.savedAt).toLocaleTimeString()}` : "No data yet"}
                  style={{fontSize:11,fontWeight:600,color:"#10b981",background:"#10b98115",padding:"2px 8px",borderRadius:10,border:"1px solid #10b98133"}}>
                  👤 {p.username}
                </span>
              ))}
            </div>
          )}
          <div style={{display:"flex",gap:6}}>
            {hasFSAccessAPI&&(
              <button style={s.fileBtn} onClick={pickDirectory} title="Pick a shared folder for multi-user auto-save">
                {hasDirectoryAccess?"📂 Connected":"📂 Set Sync Folder"}
              </button>
            )}
            {hasDirectoryAccess && (
              <button style={s.fileBtn} onClick={manualRefresh} title="Re-read all files in the shared folder">🔄 Refresh</button>
            )}
            <button style={s.fileBtn} onClick={exportFile} title={`Download ${fileName}`}>💾 Export</button>
            <button style={s.fileBtn} onClick={importFile} title="Load a release JSON file">📄 Import</button>
          </div>
          <div>
            <span style={{color:t.textDim,fontSize:10,textTransform:"uppercase",letterSpacing:"0.08em",display:"block"}}>Release Manager</span>
            <input style={s.rmInput} value={release.releaseManager} placeholder="Enter name…" onChange={e=>save({...release,releaseManager:e.target.value})} />
          </div>
          <div>
            <span style={{color:t.textDim,fontSize:10,textTransform:"uppercase",letterSpacing:"0.08em",display:"block"}}>Release Branch</span>
            <input style={s.rmInput} value={release.releaseBranch||""} placeholder={`release/${getWeekId()}`} onChange={e=>save({...release,releaseBranch:e.target.value})} />
          </div>
          <div>
            <span style={{color:t.textDim,fontSize:10,textTransform:"uppercase",letterSpacing:"0.08em",display:"block"}}>Hotfix Branch</span>
            <input style={s.rmInput} value={release.hotfixBranch||""} placeholder="hotfix/…" onChange={e=>save({...release,hotfixBranch:e.target.value})} />
          </div>
        </div>
      </header>

      {/* Hint banners */}
      {!hasDirectoryAccess&&hasFSAccessAPI&&(
        <div style={{padding:"8px 24px",background:t.accent+"15",borderBottom:`1px solid ${t.accent}33`,display:"flex",alignItems:"center",gap:8}}>
          <span style={{fontSize:13,color:t.accent}}>💡</span>
          <span style={{fontSize:12,color:t.textMuted}}>Click <strong>"Set Sync Folder"</strong> to collaborate via a shared cloud folder (Google Drive, Dropbox, OneDrive…). Each user's changes are saved as a separate file and merged automatically.</span>
        </div>
      )}
      {!hasFSAccessAPI&&(
        <div style={{padding:"8px 24px",background:"#f59e0b15",borderBottom:"1px solid #f59e0b33",display:"flex",alignItems:"center",gap:8}}>
          <span style={{fontSize:13}}>ℹ️</span>
          <span style={{fontSize:12,color:t.textMuted}}>Your browser doesn't support auto-save to disk. Use <strong>Export</strong> to download and <strong>Import</strong> to load files.</span>
        </div>
      )}

      {/* Stats */}
      <div style={s.statsStrip}>
        {[{l:"Services",v:svcCount,c:"#3b82f6"},{l:"Approved",v:approvedCount,c:"#10b981"},{l:"Deployed",v:deployedCount,c:"#10b981"},{l:"Hotfixes",v:hotfixCount,c:"#f59e0b"},{l:"Failed",v:failedCount,c:"#ef4444"}].map(x=>(
          <div key={x.l} style={s.stat}>
            <span style={{fontSize:22,fontWeight:700,color:x.c,fontFamily:"'JetBrains Mono', monospace"}}>{x.v}</span>
            <span style={{fontSize:10,color:t.textDim,textTransform:"uppercase",letterSpacing:"0.08em"}}>{x.l}</span>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div style={s.tabBar}>
        {[{key:"board",label:"Service Board"},{key:"deps",label:"Dependencies"},{key:"checklist",label:"Release Checklist"},{key:"notes",label:"Notes"}].map(tb=>(
          <button key={tb.key} onClick={()=>setTab(tb.key)} style={{
            ...s.tab,borderBottomColor:tab===tb.key?t.accent:"transparent",color:tab===tb.key?t.text:t.textDim,
          }}>{tb.label}</button>
        ))}
      </div>

      <div style={s.content}>
        {tab==="board"&&<BoardTab release={release} save={save} editingSvc={editingSvc} setEditingSvc={setEditingSvc} showAddForm={showAddForm} setShowAddForm={setShowAddForm} s={s} t={t}/>}
        {tab==="deps"&&<DepsTab release={release} s={s} t={t}/>}
        {tab==="checklist"&&<ChecklistTab release={release} save={save} s={s} t={t}/>}
        {tab==="notes"&&<NotesTab release={release} save={save} s={s} t={t}/>}
      </div>

      {/* Footer */}
      <div style={{padding:"12px 24px",borderTop:`1px solid ${t.border}`,background:t.bgPanel,display:"flex",justifyContent:"space-between",alignItems:"center"}}>
        <span style={{fontFamily:"'JetBrains Mono', monospace",fontSize:11,color:t.textFaint}}>📁 {fileName}</span>
        <span style={{fontSize:11,color:t.textFaint}}>{svcCount} service{svcCount!==1?"s":""} tracked</span>
      </div>
    </div>
  );
}

// ── Board Tab ────────────────────────────────────────────────────────────────
function BoardTab({release,save,editingSvc,setEditingSvc,showAddForm,setShowAddForm,s,t}) {
  const addService=(svc)=>{
    const id=`svc-${Date.now()}`;const regions={};
    REGION_LIST.forEach(r=>(regions[r]="pending"));
    save({...release,services:[...release.services,{...EMPTY_SERVICE,...svc,id,regions,updatedAt:Date.now()}]});
    setShowAddForm(false);
  };
  const updateService=(id,patch)=>{
    save({...release,services:release.services.map(x=>x.id===id?{...x,...patch,updatedAt:Date.now()}:x)});
  };
  const removeService=(id)=>{
    if(confirm("Remove this service?")){
      const now=Date.now();
      save({...release,services:release.services.map(x=>x.id===id?{...x,deletedAt:now,updatedAt:now}:x)});
      if(editingSvc===id)setEditingSvc(null);
    }
  };
  const toggleHotfix=(svcId)=>{
    save({...release,services:release.services.map(x=>x.id===svcId?{...x,hasHotfix:!x.hasHotfix,status:!x.hasHotfix?"needs-hotfix":x.status,updatedAt:Date.now()}:x)});
  };
  const updateHotfix=(svcId,field,val)=>{
    save({...release,services:release.services.map(x=>x.id===svcId?{...x,[field]:val,updatedAt:Date.now()}:x)});
  };
  const updateRegion=(svcId,region,regionStatus)=>{
    save({...release,services:release.services.map(x=>{
      if(x.id!==svcId)return x;
      const newRegions={...x.regions,[region]:regionStatus};
      const allDeployed=REGION_LIST.every(r=>(newRegions[r]||"pending")==="deployed");
      const newStatus=allDeployed?"deployed":x.status==="deployed"?"deploying":x.status;
      return {...x,regions:newRegions,status:newStatus,updatedAt:Date.now()};
    })});
  };

  const activeServices = release.services.filter(x => !x.deletedAt);

  return (
    <div>
      <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:16}}>
        <h2 style={s.sectionTitle}>Services in this Release</h2>
        <button style={s.addBtn} onClick={()=>setShowAddForm(true)}>+ Add Service</button>
      </div>
      {showAddForm&&<ServiceForm allServices={activeServices} onSave={addService} onCancel={()=>setShowAddForm(false)} s={s} t={t}/>}
      {activeServices.length===0&&<div style={s.empty}>No services added yet. Click "+ Add Service" to begin.</div>}
      <div style={{display:"flex",flexDirection:"column",gap:12}}>
        {activeServices.map(svc=>(
          <div key={svc.id} style={s.svcCard}>
            {/* Header */}
            <div style={{display:"flex",justifyContent:"space-between",alignItems:"flex-start",marginBottom:10}}>
              <div>
                <div style={{display:"flex",alignItems:"center",gap:8}}>
                  <span style={s.svcName}>{svc.name}</span>
                  <Pill color={STATUS_COLORS[svc.status]} active>{svc.status}</Pill>
                </div>
                <div style={{display:"flex",alignItems:"center",gap:6,marginTop:4,fontSize:12,color:t.textDim}}>
                  <span>{svc.repo}</span><span style={{color:t.textFaint}}>•</span>
                  <Pill color={svc.changeType==="code"?"#3b82f6":svc.changeType==="config"?"#f59e0b":"#8b5cf6"}>{svc.changeType}</Pill>
                </div>
              </div>
              <div style={{display:"flex",gap:6}}>
                <button style={s.iconBtn} onClick={()=>setEditingSvc(editingSvc===svc.id?null:svc.id)}>✏️</button>
                <button style={s.iconBtn} onClick={()=>removeService(svc.id)}>🗑️</button>
              </div>
            </div>
            {/* Info row */}
            <div style={{display:"flex",flexWrap:"wrap",gap:16,marginBottom:10}}>
              <div style={{display:"flex",flexDirection:"column",gap:2}}>
                <span style={s.svcLabel}>Label</span>
                {svc.hasHotfix&&svc.hotfixLabel
                  ?<code style={s.labelCode}>{svc.hotfixLabel}</code>
                  :<input style={{...s.input,padding:"3px 6px",fontSize:12,fontFamily:"'JetBrains Mono', monospace",width:150}} value={svc.label||""} onChange={e=>updateService(svc.id,{label:e.target.value})} placeholder="e.g. v2.14.0-rc1"/>
                }
              </div>
              <div style={{display:"flex",flexDirection:"column",gap:2}}>
                <span style={s.svcLabel}>POC</span><span style={{color:t.text}}>{svc.poc||"—"}</span>
              </div>
              {svc.dependencies.length>0&&<div style={{display:"flex",flexDirection:"column",gap:2}}>
                <span style={s.svcLabel}>Depends on</span><span style={{color:t.textMuted}}>{svc.dependencies.join(", ")}</span>
              </div>}
            </div>
            {/* Hotfix section */}
            {svc.hasHotfix&&(
              <div style={{marginTop:10,padding:"12px 14px",borderTop:"2px solid #ef444444",background:"#ef444408",borderRadius:"0 0 6px 6px"}}>
                <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:10}}>
                  <span style={{fontSize:11,fontWeight:700,color:"#ef4444",textTransform:"uppercase",letterSpacing:"0.08em"}}>🔥 Hotfix</span>
                </div>
                <div style={{display:"flex",gap:10,marginBottom:10,flexWrap:"wrap"}}>
                  <div style={{flex:1,minWidth:160}}><label style={s.formLabel}>Hotfix Label</label><input style={s.input} value={svc.hotfixLabel||""} onChange={e=>updateHotfix(svc.id,"hotfixLabel",e.target.value)} placeholder="e.g. v2.14.1-hotfix"/></div>
                  <div style={{flex:2,minWidth:200}}><label style={s.formLabel}>Hotfix Notes</label><input style={s.input} value={svc.hotfixNotes||""} onChange={e=>updateHotfix(svc.id,"hotfixNotes",e.target.value)} placeholder="What bug is being fixed?"/></div>
                </div>
                <div style={{display:"flex",flexDirection:"column",gap:6}}>
                  <label style={{display:"flex",alignItems:"center",gap:8,cursor:"pointer",fontSize:13,color:t.text}}>
                    <input type="checkbox" checked={!!svc.hotfixMergedMain} onChange={e=>updateHotfix(svc.id,"hotfixMergedMain",e.target.checked)} style={{accentColor:"#ef4444"}}/>
                    Merged to main
                  </label>
                  <label style={{display:"flex",alignItems:"center",gap:8,cursor:"pointer",fontSize:13,color:t.text}}>
                    <input type="checkbox" checked={!!svc.hotfixMergedRelease} onChange={e=>updateHotfix(svc.id,"hotfixMergedRelease",e.target.checked)} style={{accentColor:"#ef4444"}}/>
                    {release.releaseBranch?`Merged to ${release.releaseBranch}`:"Merged to release branch"}
                  </label>
                  <label style={{display:"flex",alignItems:"center",gap:8,cursor:"pointer",fontSize:13,color:t.text}}>
                    <input type="checkbox" checked={!!svc.hotfixMergedHotfix} onChange={e=>updateHotfix(svc.id,"hotfixMergedHotfix",e.target.checked)} style={{accentColor:"#ef4444"}}/>
                    {release.hotfixBranch?`Merged to ${release.hotfixBranch}`:"Merged to hotfix branch"}
                  </label>
                </div>
              </div>
            )}
            {/* Region deployment status */}
            <div style={{display:"flex",flexWrap:"wrap",alignItems:"center",gap:8,padding:"10px 0",borderTop:`1px solid ${t.border}`}}>
              <span style={{fontSize:10,color:t.textDim,textTransform:"uppercase",letterSpacing:"0.06em",marginRight:4}}>Regions</span>
              {REGION_LIST.map(r=>{
                const rStatus=svc.regions?.[r]||"pending";
                return (
                  <div key={r} style={{display:"flex",alignItems:"center",gap:4}}>
                    <span style={{fontSize:10,color:t.textDim,whiteSpace:"nowrap"}}>{r}</span>
                    <select aria-label={`Region ${r}`} value={rStatus} onChange={e=>updateRegion(svc.id,r,e.target.value)} style={{
                      ...s.regionSelect,background:REGION_COLORS[rStatus]+"22",
                      borderColor:REGION_COLORS[rStatus],color:REGION_COLORS[rStatus],
                    }}>{REGION_STATUSES.map(st=><option key={st} value={st}>{st}</option>)}</select>
                  </div>
                );
              })}
            </div>
            {/* Status strip */}
            <div style={{display:"flex",flexWrap:"wrap",gap:4,padding:"10px 0 0",borderTop:`1px solid ${t.border}`}}>
              {SVC_STATUSES.map(st=>{
                const isHotfixChip = st === "needs-hotfix";
                const isDeployedChip = st === "deployed";
                const active = isHotfixChip ? svc.hasHotfix : svc.status === st;
                const handleClick = isHotfixChip
                  ? ()=>toggleHotfix(svc.id)
                  : isDeployedChip ? undefined
                  : ()=>updateService(svc.id,{status:st});
                return (
                  <span key={st} onClick={handleClick} title={isDeployedChip?"Auto-set when all regions are deployed":undefined} style={{
                    fontSize:9,padding:"2px 6px",borderRadius:3,cursor:isDeployedChip?"default":"pointer",
                    background:active?STATUS_COLORS[st]:"transparent",
                    color:active?"#fff":t.textFaint,
                    border:`1px solid ${active?STATUS_COLORS[st]:t.border}`,
                    textTransform:"uppercase",fontWeight:600,letterSpacing:"0.03em",transition:"all 0.12s",
                    opacity:isDeployedChip&&!active?0.5:1,
                  }}>{st}</span>
                );
              })}
            </div>
            {editingSvc===svc.id&&<ServiceForm initial={svc} allServices={activeServices} onSave={patch=>{updateService(svc.id,patch);setEditingSvc(null);}} onCancel={()=>setEditingSvc(null)} isEdit s={s} t={t}/>}
          </div>
        ))}
      </div>
    </div>
  );
}

function ServiceForm({initial,allServices,onSave,onCancel,isEdit,s}) {
  const [form,setForm]=useState(initial||{name:"",repo:"",changeType:"code",label:"",poc:"",dependencies:[]});
  const otherServices=allServices.filter(x=>x.id!==initial?.id).map(x=>x.name);
  const toggleDep=(name)=>setForm(f=>({...f,dependencies:f.dependencies.includes(name)?f.dependencies.filter(d=>d!==name):[...f.dependencies,name]}));
  return (
    <div style={s.formWrap}>
      <div style={s.formGrid}>
        <div><label style={s.formLabel}>Service Name</label><input style={s.input} value={form.name} onChange={e=>setForm({...form,name:e.target.value})} placeholder="e.g. auth-service"/></div>
        <div><label style={s.formLabel}>Repository</label><input style={s.input} value={form.repo} onChange={e=>setForm({...form,repo:e.target.value})} placeholder="e.g. org/auth-service"/></div>
        <div><label style={s.formLabel}>Change Type</label><select style={s.input} value={form.changeType} onChange={e=>setForm({...form,changeType:e.target.value})}>{CHANGE_TYPES.map(ct=><option key={ct} value={ct}>{ct}</option>)}</select></div>
        <div><label style={s.formLabel}>Point of Contact</label><input style={s.input} value={form.poc} onChange={e=>setForm({...form,poc:e.target.value})} placeholder="Name"/></div>
      </div>
      {otherServices.length>0&&<div style={{marginTop:12}}>
        <label style={s.formLabel}>Dependencies (click to toggle)</label>
        <div style={{display:"flex",flexWrap:"wrap",gap:6,marginTop:4}}>
          {otherServices.map(n=><Pill key={n} color="#3b82f6" active={form.dependencies.includes(n)} onClick={()=>toggleDep(n)}>{n}</Pill>)}
        </div>
      </div>}
      <div style={{display:"flex",gap:8,marginTop:14}}>
        <button style={s.saveBtn} onClick={()=>{if(!form.name)return alert("Service name is required");onSave(form);}}>{isEdit?"Update":"Add"}</button>
        <button style={s.cancelBtn} onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

function DepsTab({release,s,t}) {
  const services=release.services.filter(x=>!x.deletedAt);
  if(services.length===0)return <div style={s.empty}>Add services first to see the dependency map.</div>;
  return (
    <div>
      <h2 style={s.sectionTitle}>Dependency Map</h2>
      {!services.some(x=>x.dependencies.length>0)&&<div style={s.empty}>No dependencies configured between services.</div>}
      <div style={{display:"grid",gridTemplateColumns:"repeat(auto-fill, minmax(280px, 1fr))",gap:12}}>
        {services.map(svc=>(
          <div key={svc.id} style={s.depCard}>
            <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:8}}>
              <span style={{fontWeight:700,color:t.text,fontSize:14}}>{svc.name}</span>
              <Pill color={STATUS_COLORS[svc.status]} active>{svc.status}</Pill>
            </div>
            {svc.dependencies.length>0?(
              <div>
                <span style={{color:t.textDim,fontSize:11,textTransform:"uppercase"}}>Depends on:</span>
                {svc.dependencies.map(dep=>{
                  const depSvc=services.find(x=>x.name===dep);
                  const depOk=depSvc&&(depSvc.status==="deployed"||depSvc.status==="approved");
                  return (
                    <div key={dep} style={{display:"flex",alignItems:"center",gap:6,marginTop:4,padding:"4px 8px",borderRadius:4,background:depOk?t.successBg:t.dangerBg,border:`1px solid ${depOk?t.successBorder:t.dangerBorder}`}}>
                      <span style={{fontSize:12}}>{depOk?"✅":"⏳"}</span>
                      <span style={{color:depOk?"#10b981":"#f87171",fontSize:13,fontWeight:600}}>{dep}</span>
                      <span style={{color:t.textDim,fontSize:11,marginLeft:"auto"}}>{depSvc?depSvc.status:"not in release"}</span>
                    </div>
                  );
                })}
              </div>
            ):<span style={{color:t.textFaint,fontSize:12,fontStyle:"italic"}}>No dependencies</span>}
            {services.filter(x=>x.dependencies.includes(svc.name)).length>0&&(
              <div style={{marginTop:10}}>
                <span style={{color:t.textDim,fontSize:11,textTransform:"uppercase"}}>Required by:</span>
                <div style={{display:"flex",flexWrap:"wrap",gap:4,marginTop:4}}>
                  {services.filter(x=>x.dependencies.includes(svc.name)).map(x=><Pill key={x.id} color="#8b5cf6">{x.name}</Pill>)}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}


function ChecklistTab({release,save,s,t}) {
  const items=[
    {key:"branches_cut",label:"Thursday: Release branches cut for all services",phase:"branch-cut"},
    {key:"labels_produced",label:"Thursday: Labels produced across all repositories",phase:"labeled"},
    {key:"preproduction_deployed",label:"Thursday/Friday: Labels deployed to pre-production",phase:"testing"},
    {key:"weekend_testing",label:"Weekend: Testing completed in pre-production",phase:"testing"},
    {key:"hotfixes_applied",label:"If needed: Hotfix branches cut and new labels produced",phase:"testing"},
    {key:"monday_review",label:"Monday: Teams confirm services ready to deploy",phase:"review"},
    {key:"deps_verified",label:"Monday: Upstream dependencies verified (tested & deployed)",phase:"review"},
    {key:"deploy_window",label:"Monday: Deployment window opened",phase:"deploying"},
    {key:"all_deployed",label:"Teams deploy approved labels (code/config/both)",phase:"deploying"},
    {key:"deploy_confirmed",label:"All teams confirm successful deployment",phase:"deploying"},
    {key:"release_done",label:"Release declared DONE",phase:"done"},
  ];
  const checklist=release.checklist||{};
  const toggle=(key)=>save({...release,checklist:{...checklist,[key]:!checklist[key]}});
  const completedCount=items.filter(it=>checklist[it.key]).length;
  return (
    <div>
      <h2 style={s.sectionTitle}>Release Checklist</h2>
      <div style={{marginBottom:16}}>
        <div style={s.progressBar}><div style={{...s.progressFill,width:`${(completedCount/items.length)*100}%`}}/></div>
        <span style={{color:t.textDim,fontSize:12}}>{completedCount} / {items.length} complete</span>
      </div>
      {items.map(it=>{
        const phase=PHASES.find(p=>p.key===it.phase);
        return (
          <div key={it.key} onClick={()=>toggle(it.key)} style={{...s.checkItem,opacity:checklist[it.key]?0.6:1}}>
            <span style={{fontSize:18,cursor:"pointer"}}>{checklist[it.key]?"☑":"☐"}</span>
            <span style={{color:checklist[it.key]?t.textDim:t.text,textDecoration:checklist[it.key]?"line-through":"none",fontSize:13,flex:1}}>{it.label}</span>
            {phase&&<Pill color={phase.color}>{phase.label}</Pill>}
          </div>
        );
      })}
    </div>
  );
}

// ── Notes Tab ─────────────────────────────────────────────────────────────────
function makeNote(text="") {
  return {
    id:`note-${Date.now()}-${Math.random().toString(36).slice(2,6)}`,
    text,done:false,tags:[],children:[],
    updatedAt:Date.now(),deletedAt:null,
  };
}

function parseUrls(text) {
  return [...(text.match(/https?:\/\/[^\s]+/g)||[])];
}

function NotesTab({release,save,s,t}) {
  const notes=Array.isArray(release.notes)?release.notes:[];
  return (
    <div>
      <h2 style={s.sectionTitle}>Notes</h2>
      <NoteList notes={notes} depth={0} onChange={n=>save({...release,notes:n})} t={t} s={s}/>
    </div>
  );
}

function NoteList({notes,depth,onChange,t,s}) {
  const visibleNotes = notes.filter(n => !n.deletedAt);
  const [dragIdx,setDragIdx]=useState(null);
  const [overIdx,setOverIdx]=useState(null);

  const drop=(toIdx)=>{
    if(dragIdx==null||dragIdx===toIdx)return;
    // Reorder visible notes, then append tombstoned notes at end
    const visible=[...visibleNotes];
    const [item]=visible.splice(dragIdx,1);
    visible.splice(toIdx,0,item);
    const tombstoned=notes.filter(n=>n.deletedAt);
    onChange([...visible,...tombstoned]);
    setDragIdx(null);setOverIdx(null);
  };

  return (
    <div style={{paddingLeft:depth*24}}>
      {visibleNotes.map((note,idx)=>(
        <div key={note.id}
          draggable
          onDragStart={e=>{e.stopPropagation();setDragIdx(idx);}}
          onDragOver={e=>{e.preventDefault();e.stopPropagation();setOverIdx(idx);}}
          onDrop={e=>{e.stopPropagation();drop(idx);}}
          onDragEnd={()=>{setDragIdx(null);setOverIdx(null);}}
          style={{opacity:dragIdx===idx?0.4:1,borderTop:overIdx===idx&&dragIdx!==idx?`2px solid ${t.accent}`:"2px solid transparent"}}
        >
          <NoteItem note={note} depth={depth}
            onChange={u=>onChange(notes.map(n=>n.id===note.id?u:n))}
            onDelete={()=>{
              const now=Date.now();
              onChange(notes.map(n=>n.id===note.id?{...n,deletedAt:now,updatedAt:now}:n));
            }}
            t={t} s={s}/>
        </div>
      ))}
      <button onClick={()=>onChange([...notes,makeNote()])}
        style={{background:"transparent",border:"none",color:t.textDim,fontSize:12,cursor:"pointer",padding:"6px 4px",fontFamily:"'IBM Plex Sans', sans-serif"}}>
        + {depth===0?"Add note":"Add sub-item"}
      </button>
    </div>
  );
}

function NoteItem({note,depth,onChange,onDelete,t,s}) {
  const [tagInput,setTagInput]=useState("");
  const [showTagInput,setShowTagInput]=useState(false);
  const urls=parseUrls(note.text);
  const addTag=()=>{
    const tag=tagInput.trim().replace(/^#/,"");
    if(tag&&!note.tags.includes(tag))onChange({...note,tags:[...note.tags,tag],updatedAt:Date.now()});
    setTagInput("");setShowTagInput(false);
  };
  return (
    <div>
      <div style={{display:"flex",alignItems:"flex-start",gap:6,padding:"5px 4px",borderRadius:4}}>
        <span style={{cursor:"grab",color:t.textFaint,fontSize:14,paddingTop:3,userSelect:"none",flexShrink:0}} title="Drag to reorder">⠿</span>
        <input type="checkbox" checked={note.done} onChange={e=>onChange({...note,done:e.target.checked,updatedAt:Date.now()})} style={{marginTop:4,cursor:"pointer",accentColor:t.accent,flexShrink:0}}/>
        <div style={{flex:1,minWidth:0}}>
          <input value={note.text} onChange={e=>onChange({...note,text:e.target.value,updatedAt:Date.now()})} placeholder="Note…"
            style={{...s.input,textDecoration:note.done?"line-through":"none",color:note.done?t.textDim:t.text,padding:"4px 8px",fontSize:13}}/>
          {(note.tags.length>0||urls.length>0||showTagInput)&&(
            <div style={{display:"flex",flexWrap:"wrap",gap:4,marginTop:4,alignItems:"center"}}>
              {note.tags.map(tag=>(
                <span key={tag} onClick={()=>onChange({...note,tags:note.tags.filter(x=>x!==tag),updatedAt:Date.now()})}
                  style={{background:t.accent+"22",color:t.accent,fontSize:11,padding:"1px 6px",borderRadius:10,cursor:"pointer"}} title="Click to remove">#{tag}</span>
              ))}
              {urls.map(url=>(
                <a key={url} href={url} target="_blank" rel="noopener noreferrer"
                  onClick={e=>e.stopPropagation()}
                  style={{fontSize:11,color:t.accent,maxWidth:200,overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap",display:"inline-block"}}
                  title={url}>🔗 {url}</a>
              ))}
              {showTagInput&&(
                <input autoFocus value={tagInput} onChange={e=>setTagInput(e.target.value)}
                  onKeyDown={e=>{if(e.key==="Enter")addTag();if(e.key==="Escape")setShowTagInput(false);}}
                  onBlur={addTag} placeholder="tag…"
                  style={{fontSize:11,padding:"1px 8px",borderRadius:10,border:`1px solid ${t.accent}`,background:t.inputBg,color:t.text,outline:"none",width:80,fontFamily:"'IBM Plex Sans', sans-serif"}}/>
              )}
            </div>
          )}
        </div>
        <button onClick={()=>setShowTagInput(v=>!v)} title="Add tag"
          style={{background:"transparent",border:`1px dashed ${t.borderLight}`,color:t.textFaint,fontSize:10,padding:"2px 6px",borderRadius:10,cursor:"pointer",flexShrink:0,marginTop:3}}>+tag</button>
        {depth<2&&(
          <button onClick={()=>onChange({...note,children:[...note.children,makeNote()]})} title="Add sub-item"
            style={{background:"transparent",border:`1px solid ${t.border}`,borderRadius:4,padding:"2px 6px",fontSize:11,cursor:"pointer",color:t.textDim,flexShrink:0,marginTop:3}}>↳</button>
        )}
        <button onClick={onDelete}
          style={{background:"transparent",border:"none",color:t.textFaint,fontSize:18,cursor:"pointer",padding:"0 2px",lineHeight:1,flexShrink:0,marginTop:1}}>×</button>
      </div>
      {note.children.filter(c=>!c.deletedAt).length>0&&(
        <NoteList notes={note.children} depth={depth+1} onChange={ch=>onChange({...note,children:ch})} t={t} s={s}/>
      )}
    </div>
  );
}

function makeStyles(t) {
  return {
    root:{fontFamily:"'IBM Plex Sans', -apple-system, sans-serif",background:t.bg,color:t.text,minHeight:"100vh",display:"flex",flexDirection:"column"},
    header:{display:"flex",justifyContent:"space-between",alignItems:"center",padding:"16px 24px",borderBottom:`1px solid ${t.border}`,background:t.bgPanel,flexWrap:"wrap",gap:12},
    headerLeft:{display:"flex",alignItems:"center",gap:24,flexWrap:"wrap"},
    rmInput:{background:"transparent",border:"none",borderBottom:`1px solid ${t.borderLight}`,color:t.text,fontWeight:600,fontSize:14,padding:"2px 0",outline:"none",minWidth:140,fontFamily:"'IBM Plex Sans', sans-serif",display:"block"},
    statsStrip:{display:"flex",gap:0,padding:"0 24px",background:t.bgPanel,borderBottom:`1px solid ${t.border}`},
    stat:{display:"flex",flexDirection:"column",alignItems:"center",gap:2,padding:"14px 24px",borderRight:`1px solid ${t.border}`,minWidth:90},
    tabBar:{display:"flex",gap:0,padding:"0 24px",background:t.bgPanel,borderBottom:`1px solid ${t.border}`,overflowX:"auto"},
    tab:{padding:"12px 20px",border:"none",borderBottom:"2px solid transparent",background:"transparent",fontWeight:600,fontSize:13,cursor:"pointer",whiteSpace:"nowrap",transition:"all 0.15s",fontFamily:"'IBM Plex Sans', sans-serif"},
    content:{padding:24,maxWidth:1200,flex:1},
    sectionTitle:{fontFamily:"'JetBrains Mono', monospace",fontWeight:700,fontSize:16,color:t.text,marginBottom:16,letterSpacing:"0.02em"},
    addBtn:{background:t.accent,border:"none",color:"#fff",padding:"8px 18px",borderRadius:6,fontWeight:600,fontSize:13,cursor:"pointer",fontFamily:"'IBM Plex Sans', sans-serif"},
    svcCard:{background:t.bgCard,border:`1px solid ${t.border}`,borderRadius:8,padding:16,transition:"border-color 0.15s",boxShadow:t.shadow},
    svcName:{fontFamily:"'JetBrains Mono', monospace",fontWeight:700,fontSize:15,color:t.text},
    svcLabel:{fontSize:10,color:t.textDim,textTransform:"uppercase",letterSpacing:"0.06em"},
    labelCode:{fontFamily:"'JetBrains Mono', monospace",color:t.labelColor,fontSize:12,background:t.labelBg,padding:"1px 6px",borderRadius:3},
    iconBtn:{background:"transparent",border:`1px solid ${t.border}`,borderRadius:4,padding:"4px 8px",cursor:"pointer",fontSize:14},
    formWrap:{background:t.bgPanel,border:`1px solid ${t.borderLight}`,borderRadius:8,padding:16,marginBottom:16,marginTop:12,boxShadow:t.shadow},
    formGrid:{display:"grid",gridTemplateColumns:"repeat(auto-fill, minmax(200px, 1fr))",gap:12},
    formLabel:{fontSize:10,color:t.textDim,textTransform:"uppercase",letterSpacing:"0.08em",fontWeight:600,display:"block",marginBottom:4},
    input:{width:"100%",background:t.inputBg,border:`1px solid ${t.borderLight}`,color:t.text,borderRadius:4,padding:"8px 10px",fontSize:13,outline:"none",fontFamily:"'IBM Plex Sans', sans-serif",boxSizing:"border-box"},
    saveBtn:{background:"#10b981",border:"none",color:"#fff",padding:"8px 20px",borderRadius:4,fontWeight:600,fontSize:13,cursor:"pointer"},
    cancelBtn:{background:"transparent",border:`1px solid ${t.borderLight}`,color:t.textMuted,padding:"8px 20px",borderRadius:4,fontWeight:600,fontSize:13,cursor:"pointer"},
    fileBtn:{background:t.saveBg,border:`1px solid ${t.borderLight}`,color:t.textMuted,borderRadius:4,padding:"5px 12px",cursor:"pointer",fontWeight:600,fontSize:11,fontFamily:"'IBM Plex Sans', sans-serif",whiteSpace:"nowrap"},
    empty:{color:t.textFaint,fontSize:14,textAlign:"center",padding:40,border:`1px dashed ${t.border}`,borderRadius:8},
    depCard:{background:t.bgCard,border:`1px solid ${t.border}`,borderRadius:8,padding:16,boxShadow:t.shadow},
    th:{padding:"10px 12px",textAlign:"left",fontSize:10,color:t.textDim,textTransform:"uppercase",letterSpacing:"0.08em",borderBottom:`1px solid ${t.border}`,fontWeight:600},
    td:{padding:"10px 12px",borderBottom:`1px solid ${t.border}11`,fontSize:13},
    regionSelect:{border:"1px solid",borderRadius:4,padding:"4px 8px",fontSize:11,fontWeight:600,textTransform:"uppercase",cursor:"pointer",outline:"none",fontFamily:"'IBM Plex Sans', sans-serif"},
    checkItem:{display:"flex",alignItems:"center",gap:10,padding:"10px 12px",borderBottom:`1px solid ${t.border}11`,cursor:"pointer",borderRadius:4,transition:"background 0.1s"},
    progressBar:{height:6,background:t.inputBg,borderRadius:3,overflow:"hidden",marginBottom:6},
    progressFill:{height:"100%",background:`linear-gradient(90deg, ${t.accent}, #10b981)`,borderRadius:3,transition:"width 0.3s"},
  };
}
