# GS8 Wheel Keys 2.0

Кнопки руля (next/prev/play-pause) → Headunit Revived (HUR) на штатной
магнитоле GAC GS8, без MacroDroid и без двойного срабатывания.

## Как работает
- Приложение регистрируется у штатного сервиса `com.zhonghong.logic`
  (LogicService) как слушатель источников (domain=2).
- Когда HUR берёт аудиофокус (`onThirdSourceChanged = 15`), приложение
  делает себя источником №15 (`setSourceId 15`). Тогда кнопки next/prev
  приходят помеченными источником 15 и **радио/мультимедиа их игнорируют**
  — двойного аудиопотока нет.
- next/prev идут чистым каналом `onMediaButtonPressed` и пересылаются в HUR.
- play/pause (длинное нажатие кнопки голоса) по AIDL не приходит —
  ловится через лёгкое слежение за логкатом (только строка `KEYCODE_EASYCONN`)
  и шлётся в HUR как KEYCODE_MEDIA_PLAY_PAUSE. **Нужен READ_LOGS.**
- Если HUR неактивен (не играет) — приложение пассивно, кнопки работают
  с локальным источником (радио и т.д.), в HUR ничего не шлётся.
- Автостарт при загрузке (BootReceiver), foreground-сервис.

## Установка
APK собирается в GitHub Actions (артефакт `GS8WheelKeys-apk`).
```
adb install -r app-release.apk
```
Открыть приложение один раз, нажать «Запустить сервис».

### play/pause (опционально)
```
adb shell pm grant com.gs8.wheelkeys android.permission.READ_LOGS
```
Без этого next/prev и авто-возврат работают, play/pause — нет.

## Требования под устройство
- Android 9 (minSdk/targetSdk 28), HUR = com.andrerinas.headunitrevived.
