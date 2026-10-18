package com.example.remote

/** Telefonda açılan yönetim sayfası. Anahtar (t) adres çubuğundan okunur ve her isteğe eklenir. */
object RemotePage {

    fun html(): String = """
<!doctype html>
<html lang="tr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kapukaya TV · Telefondan Yönet</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background: #0b0b0d; color: #f2f2f2; padding: 20px 16px 40px; }
  h1 { font-size: 22px; margin: 0 0 4px; letter-spacing: .02em; }
  h1 span { color: #e50914; }
  .sub { color: #9a9a9a; font-size: 14px; margin-bottom: 20px; }
  .card { background: #161618; border: 1px solid #262629; border-radius: 14px; padding: 16px; margin-bottom: 14px; }
  .card h2 { font-size: 16px; margin: 0 0 10px; }
  label { display: block; font-size: 13px; color: #9a9a9a; margin: 10px 0 6px; }
  input { width: 100%; padding: 12px; border-radius: 10px; border: 1px solid #333; background: #0f0f11;
          color: #fff; font-size: 15px; }
  button { margin-top: 12px; width: 100%; padding: 13px; border: 0; border-radius: 10px; font-size: 15px;
           font-weight: 600; background: #e50914; color: #fff; }
  button.secondary { background: #2a2a2e; }
  .list button { margin-top: 8px; text-align: left; background: #222226; font-weight: 500; }
  .list button.active { outline: 2px solid #e50914; }
  #status { position: sticky; top: 0; margin: -20px -16px 16px; padding: 12px 16px; background: #1d1d20;
            font-size: 14px; display: none; }
  #status.ok { display: block; border-bottom: 2px solid #10b981; }
  #status.err { display: block; border-bottom: 2px solid #e50914; }
  .muted { color: #8a8a8a; font-size: 13px; }
</style>
</head>
<body>
<div id="status"></div>
<h1>KAPUKAYA <span>TV</span></h1>
<div class="sub">Telefondan yönetim · <span id="count">…</span></div>

<div class="card">
  <h2>Kanal Listeleri</h2>
  <div class="list" id="playlists"></div>
</div>

<div class="card">
  <h2>Yeni M3U Listesi Ekle</h2>
  <label for="plName">Liste adı</label>
  <input id="plName" placeholder="Örn. Ulusal Kanallar">
  <label for="plUrl">M3U adresi</label>
  <input id="plUrl" type="url" inputmode="url" placeholder="https://…/liste.m3u">
  <button onclick="addPlaylist()">Ekle ve TV'de Aç</button>
</div>

<div class="card">
  <h2>Yayın Akışı (EPG)</h2>
  <label for="epg">XMLTV adresi (.xml veya .xml.gz)</label>
  <input id="epg" type="url" inputmode="url" placeholder="https://…/epg.xml.gz">
  <button onclick="saveEpg()">Kaydet</button>
</div>

<div class="card">
  <h2>Yedekleme</h2>
  <p class="muted">Favoriler, kategoriler, listeler ve ayarlar tek dosyada.</p>
  <button class="secondary" onclick="downloadBackup()">Yedeği Telefona İndir</button>
  <label for="file">Yedekten geri yükle</label>
  <input id="file" type="file" accept=".json,application/json">
  <button class="secondary" onclick="restore()">Yedeği TV'ye Yükle</button>
</div>

<script>
  const token = new URLSearchParams(location.search).get('t') || '';
  const api = (path) => path + '?t=' + encodeURIComponent(token);

  function show(msg, ok) {
    const s = document.getElementById('status');
    s.textContent = msg;
    s.className = ok ? 'ok' : 'err';
  }

  async function post(path, body) {
    show('İşleniyor…', true);
    try {
      const r = await fetch(api(path), { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) });
      const j = await r.json();
      show(j.message, j.ok);
      load();
    } catch (e) {
      show('Bağlantı hatası: TV ekranındaki sayfa açık mı?', false);
    }
  }

  async function load() {
    try {
      const r = await fetch(api('/api/state'));
      const s = await r.json();
      document.getElementById('count').textContent = s.channelCount + ' kanal';
      document.getElementById('epg').value = s.epgUrl || '';
      const box = document.getElementById('playlists');
      box.innerHTML = '';
      s.playlists.forEach(p => {
        const b = document.createElement('button');
        b.className = p.active ? 'active' : '';
        b.textContent = (p.active ? '● ' : '○ ') + p.name;
        b.onclick = () => post('/api/activate', { id: p.id });
        box.appendChild(b);
      });
    } catch (e) {
      show('TV ile bağlantı kurulamadı', false);
    }
  }

  function addPlaylist() {
    const url = document.getElementById('plUrl').value.trim();
    const name = document.getElementById('plName').value.trim();
    if (!url) { show('M3U adresini girin', false); return; }
    post('/api/playlist', { name, url });
  }

  function saveEpg() { post('/api/epg', { url: document.getElementById('epg').value.trim() }); }

  function downloadBackup() { location.href = api('/api/backup'); }

  function restore() {
    const f = document.getElementById('file').files[0];
    if (!f) { show('Önce bir yedek dosyası seçin', false); return; }
    f.text().then(t => post('/api/restore', t));
  }

  load();
</script>
</body>
</html>
""".trimIndent()
}
