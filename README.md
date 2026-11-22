# HavaAkor (Android prototipi)

Ön kamerayla elini izler, el işaretlerini akora çevirir. Kotlin + Jetpack Compose + CameraX + MediaPipe Hand Landmarker; ses AudioTrack ile üretilir.

## Çalıştırma

1. Android Studio'da **File > Open** ile bu klasörü aç (JDK 17, Android SDK 34 gerekir; Studio ilk açılışta Gradle wrapper'ı kendisi kurar).
2. El takip modelini indir ve şu konuma koy (klasör yoksa oluştur):

   `app/src/main/assets/hand_landmarker.task`

   Model adresi:
   `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

3. Gerçek bir telefonda çalıştır (emülatörde ön kamera ile el takibi yavaş ve güvenilmez olur).

## Jestler (kendi eşlemen; istediğin gibi değiştir)

| El | Jest | Etki |
|----|------|------|
| Ekranın solundaki | 1–5 parmak açık | Anahtara göre I, II, III, IV, V. derece akoru |
| | Yumruk / el yok | Sessizlik |
| | El ekranda sola yatık | Minör (aksi halde majör) |
| Ekranın sağındaki | Yukarı–aşağı | Ses seviyesi |
| | Sağa–sola | Tını parlaklığı (low-pass) |

Solaklar için "Solak modu" rolleri değiştirir. Üstteki çipler anahtarı (C…B) seçer.

## Dosyalar

- `HandAnalyzer.kt`: CameraX karesi → MediaPipe landmark'ları
- `ChordLogic.kt`: landmark'lardan jest okuma (`HandReader`) ve akor frekansları
- `ChordSynth.kt`: pad synth, AudioTrack'e akış
- `MainActivity.kt`: kamera, el iskeleti ve kontrol arayüzü

## Bilinen sınırlar / sıradaki adımlar

- Akor değişiminde hafif tıklama olabilir; frekans geçişini yumuşatmak (glide) iyileştirir.
- Ses gecikmesi cihaza bağlı. Gerekirse AudioTrack yerine Oboe (C++) ile 100 ms altına inilir.
- Parmak sayma ışığa ve açıya duyarlı; eşikler `HandReader.fingers` içinde ayarlanır.
- Eklenebilecekler: mikrofonla vokal algılayıp synth'i kısma, oyunlar, ekran kaydı, kayıt/paylaşım.
- Bu kod derlenmeden teslim edildi; ilk derlemede küçük sürüm/import uyarıları çıkabilir.
