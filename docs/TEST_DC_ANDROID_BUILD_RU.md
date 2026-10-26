# Android: переход от офлайн-демо к Telegram TEST DC (26.10.2026)

Это техническая инструкция **для разработчика**, не для владельца аккаунта. Никаких Telegram-кодов или переписок для сборки не требуется. **APK ещё не собрана и сетевые испытания не пройдены.**

## Исправленная проблема

Раньше обе тестовые Android-сборки были намеренно офлайн: `debug` и `sandboxDc` удаляли INTERNET через AndroidManifest, а `askBeforeConnect()` запрещал вход при `TELEGRAM_TEST_DC=true`. Поэтому проверка настоящей TDLib с тестовым сервером была недоступна.

Теперь три безопасно разделённых варианта:

| Build type | Интернет | Авторизация | Android applicationId при telegramEnvironment=test |
|---|---|---|---|
| `debug` | НЕТ (manifest) | НЕТ | `org.vault.exporter.sandbox` |
| `sandboxDc` | НЕТ (manifest) | НЕТ | `org.vault.exporter.sandbox.testnet` |
| `networkDc` | ДА | только тестовый аккаунт и Telegram test DC | `org.vault.exporter.sandbox.networktest` |

Новый `networkDc`: `TELEGRAM_TEST_DC=true`, `ALLOW_TEST_DC_NETWORK=true`, `ALLOW_PRODUCTION=false`, `DEBUG=false`. Отдельная кнопка запрашивает подтверждение перед сетью; параметр официальной TDLib `setTdlibParameters.use_test_dc=true`. Production-сборка остаётся заблокированной отдельным одобрением. `networkDc` подписывается только отладочным ключом для alpha QA, не для публичного релиза.

**После реальной сборки обязательно проверить merged Android manifest:** у `networkDc` должен остаться только INTERNET, у `debug` и `sandboxDc` не должно быть интернет-разрешения. Нельзя считать статические проверки доказательством поведения установленной APK.

## Получение официальной libtdjson.so

Разработчику понадобятся Docker BuildKit, Git, Python 3, readelf, Android Studio/SDK 35, Gradle 8.7+, JDK 17, NDK и CMake 3.22.1. Это не инструкции по установке для пользователя телефона.

1. Разработчик **вручную** запускает `bash scripts/build_official_tdlib_android.sh` на своей машине. GitHub Actions по умолчанию не запускает дорогую native-сборку.
2. Скрипт загружает **официальные исходники** `https://github.com/tdlib/td` на commit `ea3f69ce11aef458bfbb09869b809ede4447486f`, проверяет upstream Dockerfile blob `0f00a668585883a403ffe3c63f2567d25af72d49` и патчит **только** arm64/API 26 команду сборки.
3. Для библиотеки используется JSON-интерфейс, Android NDK 26.3.11579264 и актуальный на 26.10.2026 OpenSSL `openssl-3.5.9` (LTS). На устаревший OpenSSL 1.1.1 скрипт не откатывается.
4. Библиотека извлекается из официального ZIP только по arm64-v8a .so-путям; `verify_tdlib_elf.py` проверяет ELF64/AArch64, необходимые экспортируемые функции, динамические зависимости и SHA-256.
5. После успешной проверки файлы ставятся **локально** в `app/src/main/jniLibs/arm64-v8a/`; provenance записывается в `build/tdlib-provenance/verified-build.json`. Native binaries и эта метаинформация не отслеживаются Git.
6. Только на доверенной машине разработчик добавляет **свой** `telegramApiId` / `telegramApiHash` в приватный `~/.gradle/gradle.properties`, после чего собирает Gradle task `:app:assembleNetworkDc` с `telegramEnvironment=test`.

**Ограничения:** закреплены исходники TDLib, версия OpenSSL и NDK; Docker базовый Ubuntu и SDK-загрузки пока не content-addressed. Утверждение о побайтно воспроизводимой сборке было бы преждевременным. Нативный билд не выполнялся в текущем окружении без Docker/Android SDK, совместимость OpenSSL 3.5.9/NDK 26 с этим commit TDLib нужно проверить на реальной сборке.

## Gate: только отдельный тестовый Telegram

После появления APK сначала проверить установку и изоляцию package ID, merged manifest, настоящий выход TDLib только к test DC, отсутствие дополнительных SDK/аналитики и защиту локального хранилища. Затем — только на отдельном тестовом аккаунте, после явного согласия — придуманные сообщения, медиа, остановку/возобновление и корректный выход. Основной Telegram аккаунт и реальные личные чаты для первого испытания **не нужны**.

**Текущие результаты:** `RUN_ALL_OFFLINE.sh` локально PASS (JVM/шифрование/медиа/JNI mocks/CI/static), `RUN_BUILD_SAFETY_TESTS.py` PASS, синтаксис сборщика bash PASS. Android Gradle, нативная сборка, network test DC и APK НЕ проверены.

Официальная документация: https://github.com/tdlib/td/tree/master/example/android ; https://core.telegram.org/tdlib/getting-started ; https://openssl-library.org/source/ .

## Эксперимент: первая офлайн-APK через GitHub Actions (26.10.2026)

Дополнительно подготовлен `.github/workflows/build-offline-apk.yml`: единичная сборка **только** при добавлении/изменении этого workflow в QA-ветке либо ручном `workflow_dispatch`; выполняется только если репозиторий публичный, на стандартном `ubuntu-latest`, с read-only правами, отменой устаревших запусков и лимитом 60 минут.

CI **не получает** Telegram API-ключи, пароли, телефоны, сессии и пользовательские файлы. Использует `:app:assembleDebug` с `telegramApiId=0` и `telegramApiHash=`, официальный pinned Android TDLib, установку SDK 35/NDK 26 и фактическую проверку готовой APK через `scripts/verify_offline_apk.py`. Сборщик TDLib в CI допускается только при подтверждённом GitHub-контексте Public, правильном репозитории и явном `VAULT_ALLOW_PUBLIC_CI_TDLIB_BUILD=1`. В Private такие задания не стартуют.

**Это исключительно офлайн-приложение с синтетическими сообщениями:** в готовой APK должен отсутствовать Android INTERNET permission и не должны присутствовать реальные аккаунты. Она не сможет экспортировать настоящий Telegram; для этого необходим отдельный `networkDc` build и последующие сетевые испытания.

Запуски (ссылки позволяют проверить результат самостоятельно):
- [40390492441](https://github.com/Caprifan2078/telegram-vault-exporter-android/actions/runs/40390492441): **FAIL** на старом `android-actions/setup-android@v3` (установщик искал удалённый пакет `tools`). Вместо него используется preinstalled SDK `sdkmanager`.
- [40390692350](https://github.com/Caprifan2078/telegram-vault-exporter-android/actions/runs/40390692350): **FAIL** в preflight: переменная публичности не была передана средой runner. Исправлено: явное подтверждение публичности через GitHub workflow context и точное имя репозитория.
- [40391082134](https://github.com/Caprifan2078/telegram-vault-exporter-android/actions/runs/40391082134): **третья попытка, на момент записи native TDLib компилировалась**. Конечный результат/наличие APK ещё не подтверждены.

Выпуск APK подтверждается **только** успешным `verify_offline_apk.py` и наличием артефакта `VaultExporter-offline-qa-arm64-apk` в соответствующем GitHub Actions run. Артефакт хранится 1 день. Не выдавать debug APK за production release.

**Реальная проверка сборки:** в run 40391082134 official arm64 TDLib собрана успешно, но Gradle завершился ошибкой `BuildType names cannot start with 'test'` (до компиляции Java, `app/build.gradle:68`). Тип переименован в `networkDc` в QA-ветке; повторная APK-сборка ещё требует подтверждения. Не публиковать APK до успешной проверки manifest/ABI.
