# HavaAkor (Android prototipi)

Ön kamerayla elini izler, el işaretlerini akora çevirir. Bir akor sayfasının linkini verirsin; sayfadaki akorlar parmaklara dağıtılır, sözler akorlarla birlikte ekranda akar. Kotlin + Jetpack Compose + CameraX + MediaPipe Hand Landmarker; ses AudioTrack ile üretilir (gitar + davul, ses dosyası yok).

## Çalıştırma

GitHub'a yükleyince `.github/workflows/build.yml` APK'yı derler (modeli de kendisi indirir). Elle çalıştırmak için:

1. Android Studio'da klasörü aç (JDK 17, Android SDK 34).
2. `app/src/main/assets/hand_landmarker.task` dosyasını ekle:
   `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
3. Gerçek bir telefonda çalıştır.

## Jestler (sol el akor, sağ el efekt)

| Jest | Akor |
|------|------|
| 1 parmak (işaret) | 1. akor |
| 2 parmak | 2. akor |
| 3 parmak | 3. akor |
| 4 parmak | 4. akor |
| 5 parmak (başparmak dahil) | 5. akor |
| Rock işareti (işaret + serçe) | 6. akor |
| Shaka (başparmak + serçe) | 7. akor |
| Yumruk / el yok | Sessizlik |
| El ekranda sola yatık | İkinci set: 8.–14. akorlar |

Sağdaki el: yukarı–aşağı = gitar ses seviyesi, sağa–sola = tını parlaklığı. Solaklar için Ayarlar'da "Solak modu".

## Şarkı yükleme

- **Şarkı linki** düğmesi: sayfa linkini yapıştır ya da tarayıcıda "Paylaş > HavaAkor" de.
- Sayfa açılmazsa ya da akor bulunamazsa, sayfadaki akorlu metni kopyalayıp aynı pencerede yapıştır.
- Akorlar parmaklara şarkıda ilk geçtikleri sıraya göre dağılır. Sıra Ayarlar > "Akor listesi" kutusundan değiştirilebilir (virgülle ayrılmış, örn. `Am, G, F, E`).
- Ayrıştırıcı sözlerin üstündeki akor satırlarını (`Am   G`) ve `[Am]söz` biçimini tanır. Her sitenin yapısı farklı olduğu için bazı sayfalarda akor yerleri kayabilir veya hiç bulunamayabilir.
- Kamera üstündeki rehber her jestin hangi akor olduğunu gösterir; sarı çerçeve sıradaki akordur. Çaldığınız akor sıradakiyle eşleşince şarkı ilerler; bir satıra dokunarak oraya atlayabilirsiniz.

## Ritim

- **Ritim ▶** düğmesi tempo saatini başlatır. Gitar kalıbı ve davul aynı saatten çalışır; akor değişimi sonraki vuruşa oturur.
- Tempo: Ayarlar'da kaydırıcı ya da **TAP** (şarkıyla birlikte birkaç kez dokun). Sayfada "BPM/tempo" yazıyorsa otomatik alınır.
- Gitar vuruşu: Pop, Rock, Sekizlik, Arpej, Serbest. Davul: Kapalı, Hi-hat, Pop, Rock, Balad. Kalıplar `ChordSynth.kt` başındaki `Rhythms` nesnesinde; yeni kalıp eklemek bir satırlık iş.
- Ritim kapalıyken akor değişince teller bir kez strum edilir.

## Dosyalar

- `HandAnalyzer.kt`: CameraX karesi → MediaPipe landmark'ları
- `HandReader.kt`: landmark'lardan parmak durumu ve jest tanıma
- `ChordLogic.kt`: akor adı çözme, ton kaydırma, akor → nota
- `SongParser.kt`: sayfa indirme, HTML → metin, akor/söz ayrıştırma
- `ChordSynth.kt`: gitar (Karplus-Strong), davul ve tempo saati
- `MainActivity.kt`: kamera, jest rehberi, sözler, ayarlar

## Bilinen sınırlar

- Kod derleme ortamı olmadan yazıldı; ilk derlemelerde küçük hatalar çıkabilir.
- Rock ve shaka işaretlerinde başparmak/serçe tanıma ışığa ve açıya duyarlı; eşikler `HandReader.fingerStates` içinde.
- Ses gecikmesi cihaza bağlı.
- Sözler yalnızca cihazda gösterilir. Uygulamayı yayınlamayı düşünürsen söz telifi ve sitelerin kullanım şartlarına bak.
