# GS8 Wheel Keys → HUR

Небольшое Android-приложение для штатной магнитолы GAC GS8 (2023, без CarPlay,
прошивка V3.1-20230925, Android 9). Ловит нажатия кнопок руля из системного лога и
пересылает медиакоманды в Headunit Revived (HUR) 4+, чтобы в Android Auto работали
переключение треков и пауза с руля. Заменяет связку MacroDroid.

Рантайм работает БЕЗ ADB. ADB нужен один раз — выдать право READ_LOGS.

## Раскладка по умолчанию
- Короткий Next  → следующий трек в HUR
- Короткий Prev  → предыдущий трек в HUR
- Долгий «голос» (EasyConnect, штатно не занят) → Play/Pause в HUR
- Громкость, телефон, короткий «голос» — не трогаем, работают штатно.

Правится в `app/src/main/java/com/gs8/wheelkeys/KeyMap.java`.

## Как собрать APK через GitHub (без Android Studio)
1. Создайте на github.com новый репозиторий (можно приватный).
2. Загрузите в него все файлы этого проекта (кнопка "Add file" → "Upload files",
   перетащите папку целиком, включая скрытую `.github`).
3. Вкладка **Actions** → workflow **Build APK** запустится сам (или нажмите
   "Run workflow").
4. По окончании откройте завершённый запуск → раздел **Artifacts** →
   скачайте **GS8WheelKeys-apk** (это zip, внутри app-release.apk).

## Установка на магнитолу (по ADB)
    adb connect 192.168.x.x:9527
    adb install app-release.apk
    adb shell pm grant com.gs8.wheelkeys android.permission.READ_LOGS

Затем открыть приложение «GS8 Wheel Keys» на магнитоле, нажать «Запустить службу».
Проверка: открыть HUR, играть музыку, нажать кнопки руля.
Отключить для приложения экономию батареи, чтобы его не усыпляло.

## Сборка в Android Studio (альтернатива)
Открыть папку проекта → Run 'app' или Build → Build APK(s).

## Скачать APK
   Скачать APK можно справа в меню в Releases
