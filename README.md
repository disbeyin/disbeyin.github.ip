# Ders Bekçisi

Ders saatlerinde tablet ekranını izleyen, ders dışı bir şey görürse önce uyaran, sonra son ses alarm çalan Android uygulaması. Her şey tabletin içinde çalışır: ekran görüntüleri kaydedilmez, internete ya da bir yapay zekâya gönderilmez.

**APK indir:** https://github.com/disbeyin/disbeyin.github.ip/releases/latest/download/DersBekcisi.apk

## Neyi ders sayar?

- **Ders uygulamaları** (sen seçersin, ör. Nebo, PDF okuyucu): önde açıkken her zaman ders sayılır.
- **Anahtar kelimeler** (TYT, AYT, matematik, türev, integral, limit…): ekrandaki yazıda ya da ekran görüntüsünden okunan yazıda (OCR) geçerse.
- **Matematik ifadeleri**: `x² + 3x = 0`, `f(x)`, `lim`, `∫` gibi. Saat, yüzde, fiyat gibi sıradan sayılar sayılmaz.
- **Anlatan kişi**: ekranda yüz + ses varsa, son 10 dakikada ders içeriği görüldüyse ders sayılır (ayarlardan "tek başına yeter" yapılabilir).
- **Yasaklı uygulamalar** (Instagram, TikTok…): ekranda ders kelimesi olsa bile asla ders sayılmaz.

## Ne olur?

Ders kanıtı kaybolunca: tolerans (45 sn) → uyarı şeridi + titreşim (30 sn) → kanıt dönene kadar son ses siren. Ekran kapalıysa ya da kilitliyse de aynısı olur (10 sn tolerans). Ders saatinde tablet yeniden başlatılırsa açılır açılmaz alarm çalar. Kulaklık takılıysa alarm tabletin hoparlörüne yönlendirilir ve ses sınırlanır. Tüm süreler ayarlardan değişir.

## Kapatmaya karşı

Koruma başlatıldıktan sonra ayar değiştirmek ya da bekçiyi kapatmak için ya acil durum PIN'i (başkası belirler) ya da 10 dakika bekleme gerekir; bekleme sırasında bekçi çalışmaya devam eder. Koruma açıkken Ayarlar'daki bekçiyle ilgili sayfalar (uygulama bilgisi, erişilebilirlik, cihaz yöneticisi) açılınca ana ekrana dönülür ve deneme rapora yazılır.

Engellenemeyenler: tableti kapatmak, Güvenli Mod, fabrika ayarlarına dönmek, telefonu kullanmak.

## Güncelleme

Her yeni sürüm aynı bağlantıdan indirilip eskisinin üstüne kurulur; ayarlar silinmez.
