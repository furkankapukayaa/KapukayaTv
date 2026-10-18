# 📺 Kapukaya TV

![Android TV](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20TV%20Box-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Media3 ExoPlayer](https://img.shields.io/badge/Player-Media3%20ExoPlayer-blue)
![Min SDK](https://img.shields.io/badge/minSdk-24-orange)
![Version](https://img.shields.io/badge/version-1.2.0-red)
![License](https://img.shields.io/badge/License-MIT-green)

**Kapukaya TV**, Android TV ve TV Box cihazlarında Türkiye kanallarını kumandayla rahatça izlemek için geliştirilmiş, hızlı ve sade bir M3U oynatıcısıdır. Normal bir televizyon gibi kullanılmak üzere tasarlandı: aç, kanalı seç, yukarı/aşağı tuşlarıyla kanal değiştir.

> ℹ️ Uygulama içinde hiçbir yayın barındırılmaz. Varsayılan olarak herkese açık [iptv-org](https://github.com/iptv-org/iptv) Türkiye listesini kullanır; dilerseniz kendi M3U adreslerinizi ekleyebilirsiniz.

---

## ✨ Özellikler

### 📡 İzleme
- **Gerçek TV gibi kanal değiştirme:** **▲ / ▼** veya **CH+ / CH−**. Hızlı basışlarda yalnızca son seçilen kanal açılır.
- **Önceki kanala dönüş:** Tek başına **0** (veya *Last*) tuşu.
- **Rakamla kanal açma:** Yazarken kanal adı önizlenir (`12  TRT Spor`). Ana ekranda da çalışır.
- **Kaldığın yerden devam:** Ana ekrandaki kırmızı buton son kanalı açar; isterseniz uygulama doğrudan son kanalla başlar.
- **Akıllı yeniden bağlanma:** Sınırlı deneme → yedek kaynağa geçiş → net hata ekranı. İnternet geri gelince yayın kendiliğinden sürer.
- **Ses dili, görüntü kalitesi, görüntü modu, uyku zamanlayıcı** ve destekleyen cihazlarda **Picture-in-Picture**.

### 🏠 Android TV ana ekranı
- TV'nin ana ekranında **"Kapukaya TV · Favoriler"** satırı. Favori kanallarınız (favori yoksa son izlenenler) kart olarak görünür; karta basınca kanal doğrudan başlar. Favoriler değiştikçe satır kendiliğinden güncellenir. *(Android 8.0+ TV cihazları)*

### 🗓️ Yayın akışı
- **Tam ekran rehber:** Solda kanallar ve şu anki programları, sağda seçili kanalın günlük akışı ve program açıklamaları. Kumandadaki **Guide** tuşu, ana ekrandaki takvim ikonu veya oynatıcı seçeneklerinden açılır.
- Kartlarda ve alt bilgi çubuğunda *şimdi / sonra* bilgisi ve ilerleme çubuğu.
- XMLTV (`.xml` / `.xml.gz`). Listede `x-tvg-url` varsa otomatik kullanılır.

### 📱 Telefondan yönetim
- Ayarlar → **Telefondan Yönet** ekranındaki QR kodu telefonla okutun. Açılan sayfadan:
  - M3U listesi ekleyin ve tek dokunuşla TV'de açın,
  - kayıtlı listeler arasında geçiş yapın,
  - EPG adresi girin,
  - yedeği telefona indirin veya telefondan geri yükleyin.
- Uzun adresleri kumandayla yazmaya gerek kalmaz. Bağlantı yalnızca ekran açıkken ve yerel ağda çalışır; adres her seferinde değişen bir anahtar içerir.

### 🗂️ Listeler ve düzen
- **Birden fazla M3U listesi:** "Ulusal", "Yerel" gibi listeler kaydedin, aralarında geçiş yapın. Her listenin kendi çevrimdışı önbelleği vardır.
- **Favoriler** (yön tuşlarıyla sıralanabilir), **Son İzlenenler**, **otomatik grup sekmeleri** (Haber, Spor, Çocuk…), **özel kategoriler**.
- **Kanal gizleme:** İstemediğiniz kanalları listeden kaldırın; Ayarlar'dan geri getirin.
- **Çalışmayan kanalların tespiti:** Yayını açılmayan kanallar soluk ve "⚠ Yayın şu an açılmıyor" olarak gösterilir; isterseniz tamamen gizlenir. Küçük listeler günde bir kez otomatik kontrol edilir, izlerken açılmayan kanallar da işaretlenir.
- **Kumandaya uygun işlem menüsü:** Kartta **OK'a basılı tutun** veya **Menü** tuşuna basın: İzle · Favori · Kategoriye ekle/çıkar · Sırala · Yayın akışı · Gizle.

### 🔒 Ebeveyn kilidi
- 4 haneli PIN ile seçtiğiniz **grupları ve kategorileri** kilitleyin. Kilitli içerik tüm listelerden, aramadan ve kanal numaralarından gizlenir; sekmesine girince PIN sorulur. PIN uygulama kapanana kadar geçerlidir.

### 💾 Yedekleme
- Favoriler, kategoriler, listeler, gizlenen kanallar ve ayarlar tek bir JSON dosyasına kaydedilir. Cihazdaki dosya seçiciyle veya telefondan yönetim sayfasıyla yedek alıp geri yükleyebilirsiniz.

### 🔍 Arama
- Türkçe karakter gerektirmez (`sozcu` → *Sözcü TV*), kelime sırasından bağımsızdır, tüm kanallarda ve gruplarda arar. Sesli arama desteklenir.

### ⚡ Hız ve uyumluluk
- Çevrimdışı önbellek sayesinde uygulama anında açılır; güncel liste arka planda iner.
- `#EXTVLCOPT` User-Agent/Referer, Kodi tarzı `url|başlık`, http→https yönlendirmeleri, uzantısız HLS adresleri.
- Aynı kanalın farklı kaliteleri tek kartta birleşir ve yedek yayın olarak kullanılır.

---

## 🎮 Kumanda Kısayolları

### Ana ekran
| Tuş | İşlev |
|---|---|
| Yön tuşları | Sekmeler ve kartlar arasında gezinme |
| **OK** | Kanalı aç |
| **OK (basılı tut)** / **Menü** | Kanal işlem menüsü |
| **0–9** | Numarayla kanala git |
| **Guide** | Yayın akışı rehberi |
| **Arama** | Arama kutusuna geç |
| **Geri** | Aramayı temizle / çıkış (iki kez) |

### İzleme ekranı
| Tuş | İşlev |
|---|---|
| **▲ / CH+** | Sonraki kanal |
| **▼ / CH−** | Önceki kanal (listede) |
| **◀** | Kanal listesi (bir kez daha ◀: kategori seçimi) |
| **▶** / **Menü** | Seçenekler: arama, görüntü modu, ses dili, kalite, uyku zamanlayıcı, rehber, yeniden yükle |
| **OK** | Kanal bilgisi. Bilgi açıkken OK: alt butonlara geçer |
| **0** (tek başına) / **Last** | Bir önceki kanala dön |
| **0–9** | Numarayla kanala git |
| **Guide** | Yayın akışı rehberi |
| **Info** | Kanal bilgisini aç/kapat |
| **Oynat/Duraklat** | Duraklat / canlıya dön |
| **Geri** | Açık paneli kapat, yoksa ana ekrana dön |

---

## 📥 İndir ve Kur

Derlenmiş APK'yı **[Releases](../../releases)** sayfasından indirebilirsiniz.

1. APK'yı USB belleğe kopyalayıp TV'ye takın ve bir dosya yöneticisiyle açın (gerekirse *Bilinmeyen kaynaklar* izni verin).
2. Alternatif: *Send Files to TV* uygulaması veya `adb install KapukayaTv.apk`.

> Önceki sürümün üzerine kurabilirsiniz; favoriler, kategoriler ve listeler korunur. Farklı bir anahtarla imzalanmış APK (ör. başka bilgisayarda veya GitHub Actions'ta derlenmiş) önceki kurulumun üzerine yüklenemez; bu durumda önce yedek alın.

## ⚙️ İlk Kullanım

1. Uygulamayı açın; varsayılan Türkiye listesi otomatik yüklenir.
2. Kendi listeniz için en kolay yol: **Ayarlar → Telefondan Yönet**, QR kodu okutun ve adresi telefondan yapıştırın.
3. (İsteğe bağlı) EPG adresi girerek yayın akışını açın.
4. Sık izlediğiniz kanalları favorilere ekleyin; TV ana ekranındaki satırda da görünürler.

| Ayar | Açıklama |
|---|---|
| Cihaz Açılışında Başlat | TV açılınca uygulama otomatik başlar |
| Açılışta Son Kanalı Oynat | Uygulama doğrudan son izlenen kanalla açılır |
| Telefondan Yönet | QR kodla telefondan liste / EPG / yedek yönetimi |
| M3U Listeleri | Liste ekle, değiştir, yeniden adlandır, sil |
| EPG XMLTV Adresi | Yayın akışı (boşsa listenin kendi adresi) |
| Kopyaları Birleştir / Grup Sekmeleri | Liste görünümü |
| Ebeveyn Kilidi | PIN ve kilitli gruplar/kategoriler |
| Kanal Bakımı | Yayın kontrolü, çalışmayanları gizle, gizlenen kanallar |
| Yedekleme | Yedeği kaydet / geri yükle |
| Kategori Yönetimi | Özel kategoriler |

---

## 🛠️ Geliştirme

### Derleme
1. Projeyi klonlayın ve Android Studio ile açın (**File → Open**):
   ```bash
   git clone https://github.com/furkankapukayaa/KapukayaTv.git
   ```
2. Gradle senkronizasyonu bitince **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. Birim testleri: `app/src/test` klasörüne sağ tıklayıp **Run 'Tests in …'**.

### Sürüm yayınlama (GitHub Actions)
`.github/workflows/android.yml` her push'ta testleri çalıştırıp debug APK üretir. `v` ile başlayan bir etiket gönderildiğinde release APK derlenir ve **Releases** sayfasına otomatik eklenir:
```bash
git tag v1.2.0
```
```bash
git push origin v1.2.0
```
Kendi anahtarınızla imzalamak için depo **Settings → Secrets and variables → Actions** altına şunları ekleyin: `KEYSTORE_BASE64` (keystore dosyasının base64 hali, anahtar adı `upload`), `STORE_PASSWORD`, `KEY_PASSWORD`. Tanımlı değilse APK debug anahtarıyla imzalanır.

### Teknolojiler
| Alan | Kullanılan |
|---|---|
| Dil / Mimari | Kotlin, Coroutines, ViewModel + StateFlow |
| Oynatıcı | Jetpack Media3 (ExoPlayer + HLS) |
| Veritabanı | Room (sürüm geçişli) |
| TV entegrasyonu | AndroidX TV Provider (ana ekran satırı) |
| Ağ | OkHttp, yerleşik küçük HTTP sunucusu (telefondan yönetim) |
| QR kod | ZXing core |
| Görseller | Coil |
| EPG | XmlPullParser ile akış halinde XMLTV (gzip destekli) |

### Proje yapısı
```
app/src/main/java/com/example/
├── SplashActivity.kt          # Açılış, TV ana ekranı bağlantıları
├── MainActivity.kt            # Ana ekran (MainViewModel ile)
├── PlayerActivity.kt          # Oynatıcı: zapping, OSD, yeniden deneme, ses/kalite, uyku
├── GuideActivity.kt           # Tam ekran yayın akışı
├── RemoteSetupActivity.kt     # Telefondan yönetim (QR)
├── SettingsActivity.kt        # Ayarlar
├── ui/main/MainViewModel.kt   # Ana ekran durumu
├── ui/adapter/                # RecyclerView adapter'ları
├── data/
│   ├── parser/                # M3U ayrıştırma, HTTP başlıkları
│   ├── repository/            # Önbellek, birleştirme, görünürlük, kilit, yayın kontrolü
│   ├── epg/                   # XMLTV yayın akışı
│   ├── health/                # Yayın erişilebilirlik kontrolü
│   ├── backup/                # Yedekleme / geri yükleme
│   └── db/                    # Room
├── remote/                    # Yerel web sunucusu ve telefon sayfası
├── tv/                        # Android TV ana ekran satırı
├── util/                      # Ayarlar, Türkçe arama, PIN penceresi, ağ
└── receiver/BootReceiver.kt   # Cihaz açılışında başlatma
```

---

## ⚠️ Yasal Uyarı

**Kapukaya TV** yalnızca bir medya oynatıcısıdır. Herhangi bir medya içeriği, canlı yayın, film veya dizi barındırmaz ve dağıtmaz. Kullanıcıların kendi yasal içeriklerini (M3U bağlantısı vb.) eklemesi gerekmektedir. Telif hakkıyla korunan materyallerin izinsiz yayınlanması veya izlenmesinden doğacak her türlü hukuki sorumluluk son kullanıcıya aittir.

## 📄 Lisans

[MIT Lisansı](./LICENSE)

---
*Developed with ❤️ by [Furkan Kapukaya](https://github.com/furkankapukayaa)*
