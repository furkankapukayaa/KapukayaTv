# Değişiklik Günlüğü

## [1.2.0] - 2026-10-18

### Eklenenler
- Android TV ana ekranında "Kapukaya TV · Favoriler" satırı; karttan kanal doğrudan açılır (kapukayatv://channel/…).
- Tam ekran yayın akışı rehberi (Guide tuşu, ana ekrandaki takvim ikonu, oynatıcı seçenekleri).
- Telefondan yönetim: QR kodla açılan sayfadan M3U listesi ekleme/değiştirme, EPG adresi, yedek indirme/yükleme.
- Birden fazla M3U listesi (profil): ekle, değiştir, yeniden adlandır, sil; her listeye ayrı önbellek.
- Yedekleme ve geri yükleme (favoriler, kategoriler, listeler, gizlenen kanallar, ayarlar).
- Ebeveyn kilidi: PIN ile grup ve kategorileri kilitleme.
- Kanal gizleme ve Ayarlar'dan geri getirme.
- Çalışmayan kanalların arka planda tespiti, soluk gösterimi ve isteğe bağlı gizlenmesi.
- GitHub Actions: her push'ta test + debug APK, etiketlerde otomatik release.

### Değişenler
- Yeni açılış ekranı tasarımı; "Premium IPTV" ifadeleri kaldırıldı, uygulama adı "Kapukaya TV".
- Ana ekran ViewModel + StateFlow yapısına taşındı.
- Release derlemesinde kod küçültme (R8) ve kaynak küçültme açıldı.
- Ana ekrandaki yenile butonunun yerini rehber butonu aldı (yenileme Ayarlar'da ve otomatik).

## [1.1.0] - 2026-10-18

### Eklenenler
- Kumandadaki ▲/▼ ve CH+/CH− tuşlarıyla kanal değiştirme (hızlı basışlarda yalnızca son kanal açılır).
- Önceki kanala dönüş (tek başına `0` veya *Last* tuşu).
- Rakam girerken kanal adının önizlenmesi; ana ekranda da numarayla kanal açma.
- "Son İzlenenler" sekmesi ve ana ekranda "▶ son kanal" butonu.
- "Açılışta son kanalı oynat" ayarı.
- M3U gruplarının (Haber, Spor, Çocuk…) Türkçe sekmeler olarak gösterilmesi.
- Kanal kartında OK'a basılı tutunca / Menü tuşuyla açılan işlem menüsü (favori, kategori ekle/çıkar, sırala).
- Favorileri yön tuşlarıyla yeniden sıralama (taşıma modu).
- EPG (XMLTV, .xml/.xml.gz) desteği: kartlarda ve OSD'de şimdi/sonra bilgisi ve ilerleme çubuğu.
- Oynatıcıda ses dili / ses parçası ve görüntü kalitesi seçimi.
- Uyku zamanlayıcı (15–120 dk, 1 dk önceden uyarı).
- Aynı kanalın kopyalarını birleştirme ve yedek yayına otomatik geçiş.
- Kanal kartlarında kalite etiketi (4K/FHD/HD/SD), OSD'de çözünürlük ve saat.
- Ana ekranda saat/tarih, yenile butonu, bölüm bilgisi ve kumanda ipuçları.
- Çevrimdışı önbellek: son kanal listesi cihazda saklanır, uygulama anında açılır.
- `#EXTVLCOPT` User-Agent/Referer ve Kodi tarzı `url|başlık` desteği; http→https yönlendirmeleri.
- Ayarlar: EPG adresi, varsayılan listeye dön, liste durumu, kopya birleştirme ve grup sekmesi seçenekleri, geçmişi temizleme.
- Birim testleri (M3U ayrıştırıcı, Türkçe arama).

### Değişenler
- Arama artık Türkçe karakterden bağımsız (`sozcu` → *Sözcü*), kelime sırasından bağımsız ve tüm kanallarda yapılıyor.
- Kanal adları sadeleştirildi (`TRT 1 (1080p) [Geo-blocked]` → `TRT 1`).
- Yeniden deneme mantığı: sonsuz döngü yerine sınırlı deneme + yedek kaynak + açık hata ekranı.
- Yayın yükleme zaman aşımı 5 sn'den 15 sn'ye çıkarıldı (yavaş açılan IPTV yayınları için).
- Uygulamaya geri dönüldüğünde yayın canlı noktadan devam ediyor.
- Görüntü modu seçimi hatırlanıyor.
- Boş durum ekranları duruma özel açıklama ve "Tekrar Dene" butonu içeriyor.

### Kaldırılanlar
- Kullanılmayan drawable, renk, metin kaynakları, mipmap ikonları ve AI Studio kalıntısı `metadata.json`.
- Kullanılmayan DAO sorguları ve eski yardımcı fonksiyonlar.

### Düzeltilenler
- Büyük kanal listelerinde Intent boyut sınırı nedeniyle oluşabilecek çökme (TransactionTooLargeException).
- Arama kutusuna yapılan çift dinleyici kaydı.
- Kumandayla kanal kartındaki favori/kategori butonlarına erişilememesi.
- Özel kategoriden kanal çıkarılamaması.
- Logo eşleştirmede "star", "show", "24" gibi kısa anahtarların yanlış kanallara logo ataması.
- Veritabanı sürüm yükseltmesinde kullanıcı kategorilerinin silinmemesi için Room migration eklendi.

## [1.0.0]
- İlk sürüm.
