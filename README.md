# ShareDing для Android

ShareDing сохраняет ссылки из меню «Поделиться» в локальной очереди и отправляет их в [linkding](https://github.com/sissbruecker/linkding). Приложение написано на Kotlin. Интерфейс использует Jetpack Compose, очередь хранится в Room, фоновую отправку запускает WorkManager. Минимальная версия — Android 9 (API 28).

## Использование

1. В Settings укажите адрес linkding и API token. Адрес может начинаться с `https://` или `http://`.
2. Поделитесь ссылкой из браузера с ShareDing или добавьте её через кнопку `+` в Queue.
3. Ссылка попадает в очередь до сетевого запроса. Когда сеть доступна, приложение проверяет linkding и отправляет закладки. После успешного ответа запись удаляется из очереди.

При ошибке ссылка остаётся в очереди. WorkManager повторяет отправку с exponential backoff от 30 секунд; Android может запустить работу позже. Новая ссылка, кнопка Retry/Sync Now и сохранение настроек инициируют раннюю попытку. Сервер в LAN или VPN может работать без доступа к публичному интернету. HTTP допускается, но настройки предупреждают о передаче API token и содержимого запроса без TLS.

В Queue запись можно удалить вручную после подтверждения. Если ответ сервера потеряется после успешного POST, отправка может повториться; linkding обновляет существующую закладку с тем же URL.

## Установка и обновления

Для обычного использования рекомендуем [Obtainium](https://github.com/ImranR98/Obtainium): он устанавливает APK из GitHub Releases и проверяет новые версии. Добавьте в Obtainium адрес `https://github.com/juev/shareding` и включите **Include prereleases**, пока доступна только предварительная версия. Установите APK `ShareDing-v0.1.0-rc.1.apk` из найденного релиза.

Без Obtainium откройте [Releases](https://github.com/juev/shareding/releases) на телефоне, скачайте APK последней версии и подтвердите установку. Если Android запросит разрешение на установку из этого источника, выдайте его браузеру или файловому менеджеру. Для обновления скачайте новый APK и установите поверх предыдущей версии.

Через USB можно установить тот же APK командой `adb install -r ShareDing-v0.1.0-rc.1.apk`. Во всех вариантах нужен Android 9 или новее. APK из Releases подписаны одним ключом; для установки обновлений требуется та же подпись. Отладочный APK подписан другим ключом. Если вы уже установили отладочную сборку, перед установкой релиза удалите её: при удалении сотрутся настройки и ссылки в локальной очереди.

## Сборка

Нужны JDK 17 или новее и Android SDK Platform 36 с Build Tools 36.0.0. Укажите путь к SDK через `ANDROID_HOME` или `local.properties` (`sdk.dir=...`).

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

Для последней команды нужен запущенный emulator или подключённое устройство. Отладочный APK находится в `app/build/outputs/apk/debug/app-debug.apk`; его можно установить командой `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

CI запускает сборку, unit tests и lint для `main` и pull requests. Подписанный APK для Releases собирается локально на macOS командой `./scripts/build-release-macos.sh`. Скрипт использует ключ из `$HOME/.local/share/shareding/release.jks` и пароль из macOS Keychain (service `org.evsyukov.shareding.release`, account `shareding`). Вместо них можно указать `SHAREDING_RELEASE_KEYSTORE` и `SHAREDING_RELEASE_PASSWORD`. Ключ и запись Keychain нужно сохранить в надёжной резервной копии: без них последующие версии нельзя установить как обновление.

Для каждого нового релиза увеличивайте `versionCode` и обновляйте `versionName` в `app/build.gradle.kts`. Подписанный файл будет в `app/build/outputs/apk/release/app-release.apk`. Правило сохранения ключа подписи описано в [Android App Signing](https://developer.android.com/studio/publish/app-signing).

Контракт поведения описан в [спецификации](docs/specs/share-to-linkding.md).
