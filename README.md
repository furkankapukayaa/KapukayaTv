# 📺 Kapukaya TV Premium - Gelişmiş Android TV IPTV Oynatıcı

![Android TV](https://img.shields.io/badge/Platform-Android%20TV-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Media3 ExoPlayer](https://img.shields.io/badge/Player-Media3_ExoPlayer-blue)
![License](https://img.shields.io/badge/License-MIT-green)

Kapukaya TV Premium, Android TV ve TV Box cihazları için özel olarak tasarlanmış, **Netflix tarzı modern arayüze (UI)** ve eşsiz kullanıcı deneyimine (UX) sahip, son derece hızlı ve akıcı bir M3U / IPTV oynatıcı uygulamasıdır.

*(Not: Uygulama içerisinde hiçbir dahili yayın veya kanal bulunmaz. Sadece modern bir oynatıcı (player) görevi görür.)*

## ✨ Öne Çıkan Özellikler

* 🎨 **Premium UI/UX:** Netflix ve modern Smart TV standartlarında, göz yormayan, pürüzsüz animasyonlara sahip koyu "Cam (Glass)" arayüz.
* ⚡ **Hızlı Zapping Menüsü:** Yayın izlerken tam ekran keyfinizi bozmadan sol taraftan açılan son derece kibar hızlı kanal menüsü.
* 🔍 **Gelişmiş Arama & Araçlar:** Binlerce kanal arasında anında filtreleme yapmak için **Klavye** veya kumandadan **Sesli Arama (Voice Search)** desteği. Görüntü formatını değiştirme (Sığdır, Tam Ekran, Kırp) ayarları.
* ⭐ **Favoriler ve Özel Kategoriler:** İstediğiniz kanalı anında favorilere ekleme, favori listenizi dilediğiniz gibi sıralama ve kendi özel TV kategorilerinizi oluşturma imkanı.
* 📺 **Otomatik Yüksek Kalite (HD/4K) Seçimi:** M3U listesindeki aynı kanalın alternatif yayınları arasından en yüksek çözünürlüklü olanı otomatik tespit edip öncelikle onu oynatma. Akıllı logo eşleştirme.
* 🕹️ **Tam Kumanda (D-Pad) Uyumu:** TV ekranından taşmayan, odaklanılan öğeyi parlak şık bir çerçeve ile belirten, Android televizyon kumandaları için %100 optimize edilmiş kusursuz gezinme.

## 📥 İndir ve Kur (APK)

Projeyi derlemekle uğraşmak istemiyorsanız, uygulamanın hazır derlenmiş **APK** dosyasını doğrudan indirip televizyonunuza kurabilirsiniz:

👉 **[APK Dosyasını İndir (`/apk` Klasörü İçinde)](./apk/)**

**Televizyona Nasıl Kurulur?**
1. İndirdiğiniz `.apk` uzantılı dosyayı bir USB belleğe atın.
2. USB belleği Android TV veya TV Box cihazınıza takın.
3. TV'nizdeki bir Dosya Yöneticisi (File Manager) aracılığıyla APK'yı açıp **Yükle** deyin.
*(Alternatif olarak telefonunuza indirdiğiniz APK'yı "Send Files to TV" isimli uygulamayı kullanarak televizyona kablosuz aktarabilirsiniz.)*

## ⚙️ Kullanım & Kurulum (M3U Ekleme)

1. Uygulamayı televizyonunuzda açın.
2. Sağ üst köşedeki **Ayarlar (Çark)** ikonuna tıklayın.
3. Açılan ayarlar menüsünde **M3U Playlist URL** kısmına size ait olan IPTV veya M3U bağlantısını girin.
4. **"Kaydet ve Kanalları Yenile"** butonuna basın. Tüm kanallarınız, logolarınız ve kategorileriniz anında yüklenecektir.

## 🛠️ Teknolojiler ve Mimari

* **Dil:** Kotlin
* **Medya Oynatıcı:** Jetpack Media3 (ExoPlayer)
* **Veritabanı:** Room Database (Kategoriler ve Favori yönetimi için)
* **Asenkron İşlemler:** Kotlin Coroutines & Flow
* **Görsel Yükleyici:** Coil (Akıllı önbellekleme ve crossfade efektleri)
* **Ağ (Network):** OkHttp3 (M3U indirme işlemleri)
* **Arayüz (UI):** Android ViewBinding, ConstraintLayout, CardView, Custom XML Selectors

## ⚠️ Yasal Uyarı

**Kapukaya TV Premium** yalnızca boş bir medya oynatıcısıdır. Kendi sunucularında herhangi bir medya içeriği, canlı yayın, film veya dizi barındırmaz ve kullanıcılara sunmaz. Kullanıcıların kendi yasal içeriklerini (M3U bağlantısı vb.) eklemesi gerekmektedir. Telif hakkıyla korunan materyallerin izinsiz yayınlanması veya izlenmesinden doğacak her türlü hukuki sorumluluk tamamen son kullanıcıya aittir. Geliştirici hiçbir sorumluluk kabul etmez.

---
*Developed with ❤️ by [kapukaya.dev](https://github.com/kapukaya)*