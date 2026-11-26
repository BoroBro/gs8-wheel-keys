# GPS на GAC GS8: как устроено, почему плохо, и проброс в HUR

> Рабочие заметки по исследованию. Приоритет созвездий: **GLONASS + GPS**
> (машина ездит не только в Испании).

## 1. Как реализован GPS в прошивке

Приём координат — **штатный нативный Android GNSS**, не кастом:
антенна → GNSS-модуль SoC (Autochips/MTK) → нативный HAL (`gps.default.so`)
→ Android `LocationManager` (`GPS_PROVIDER`) + `FusedLocation.apk`.

Штатные приложения ZH* в приёме координат **не участвуют**, делают только обвязку:
- **Навигация — через broadcast-протоколы** (AMap/AutoNavi `AUTONAVI_STANDARD_BROADCAST`,
  Карелайд/CLD, CarLife, LuoTe): принимают метаданные маршрута для приборки и
  приглушения музыки под голос. Координаты не трогают.
- **MCU (`zhmcucomm` HIDL) координат не передаёт**: есть скорость (CAN), угол руля,
  гироскоп (xyz), флаг `isGpsTimeSync` (часы) и статус сигнала — полей lat/lon нет.
- `MapBus.sendGpsSignalStatus()` шлёт в приборку лишь индикатор качества сигнала.

Вывод: качество позиционирования определяется железом + конфигом HAL
(`gps.conf`), а не этими апками. Нативный слой в дампе уровня APK отсутствует —
проверяется только на живом аппарате.

## 2. Почему работает плохо (гипотезы — проверить на аппарате)

1. **Созвездия.** Если HAL включает только GPS (без GLONASS) — точность низкая.
   Цель: убедиться, что GLONASS включён.
2. **AGPS/SUPL.** Недоступный/китайский SUPL или выключенный AGPS → очень долгий
   холодный старт (TTFF минутами), особенно без интернета у ГУ.
3. **Антенна.** Слабая/плохо расположенная GPS-антенна → низкий C/N0.
4. **Частота 1 Гц** без dead-reckoning → рывки позиции.

## 3. Проброс в HUR (Headunit Revived)

HUR открытый (open-headunit, пакет `com.andrerinas.headunitrevived`) и **умеет
форвардить GPS в Android Auto**:
- `location/GpsLocation.kt` читает `LocationManager.GPS_PROVIDER`.
- `aap/protocol/messages/LocationUpdateEvent.kt` + `proto/sensors.proto`
  (`LOCATION = 1 // Working`) — отправка координат телефону по **sensor-каналу AA**.
- Гейт — настройка **«GPS for Navigation»** + разрешение `ACCESS_FINE_LOCATION`.

Значит «совсем не пробрасывается» = одно из: настройка выключена / нет разрешения
локации у HUR / нет живого фикса в `GPS_PROVIDER` на ГУ.

### Интент-API HUR (контракт, полезно для опытов)
- `LocationUpdateIntent` — action `com.andrerinas.headunitrevived.LOCATION_UPDATE`,
  extra `android.location.LocationManager.KEY_LOCATION_CHANGED` = Parcelable `Location`.
  **Внешнее приложение может пушить координаты в HUR** → HUR отдаёт их в AA.
- `MediaKeyIntent` — `...ACTION_MEDIA_KEYPRESS`, extra `event` (KeyEvent). Более чистый
  канал для кнопок руля, чем наш текущий ACTION_MEDIA_BUTTON (на заметку по wheel-keys).
- `NavigationUpdateIntent` — `...NAVIGATION_UPDATE`: HUR раздаёт наружу навигацию
  из AA (Maps/Яндекс). Потенциал: вывести навигацию AA на штатную приборку GS8.
- `HeadUnitCommand` (`...ACTION_SET_SETTINGS` с JSON) — программно включить
  «GPS for Navigation», и `ACTION_REFRESH_SENSORS` — пере-отправить сенсоры.

## 4. Пути решения

- **Сначала простое:** в настройках HUR включить «GPS for Navigation», выдать HUR
  разрешение локации. Если на ГУ есть фикс — он поедет в AA.
- **Если фикс плохой/отсутствует:** это GNSS HAL/антенна/AGPS. Правка `gps.conf`
  (GLONASS+GPS, рабочий SUPL, AGPS) — требует root/remount, проверить доступ.
- **Обходной путь через наше приложение:** читать локацию из любого источника
  (штатный провайдер, USB-GPS, fused) и пушить в HUR `LocationUpdateIntent` —
  не завязываясь на то, что HUR сам прочитает `GPS_PROVIDER`.

## 5. Диагностика на аппарате (ADB)

    adb shell dumpsys location
    adb shell cat /vendor/etc/gps.conf 2>/dev/null; adb shell cat /system/etc/gps.conf
    adb shell getprop | grep -iE "gps|gnss|glonass|beidou|supl|location"
    adb shell settings get secure location_mode

`dumpsys location`: есть ли фикс, провайдеры, созвездия, кто запрашивает локацию
(в т.ч. HUR). `gps.conf`: SUPL/AGPS/созвездия.

## 6. План тестового приложения (GNSS-диагностика + фидер в HUR)

Расширяем тест-утилиту:
- Экран GNSS: фикс (lat/lon/acc), число спутников по созвездиям (GPS/GLONASS/…),
  C/N0, TTFF — через `LocationManager` + `GnssStatus.Callback`.
- Кнопка «Feed to HUR»: слать текущий `Location` в HUR `LocationUpdateIntent`.
- Так на живом аппарате увидим реальное качество GNSS и проверим проброс.
